package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.EvalDataset;
import com.qoobot.qoorag.entity.EvalRun;
import com.qoobot.qoorag.repository.EvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测运行服务（RAG-000）：可复现的自动评测管线 + 基线快照 + 历史对比。
 *
 * <p><b>改造前</b>：评测靠人肉执行 {@code scripts/eval/*.ps1}，报告只打印到控制台，
 * 不落库、不记环境、不可对比。结果是"改完之后指标到底涨没涨"永远说不清。
 *
 * <p>本服务每次评测：
 * <ol>
 *   <li>从库中加载评测集（{@link EvalDatasetService}）；</li>
 *   <li>跑检索端指标（{@link RetrievalEvalService}）；</li>
 *   <li>按抽样比例跑生成端指标（{@link GenEvalService}，RAG-003）；</li>
 *   <li>记录<b>环境指纹</b>（embedding / chat / rerank 模型 + 检索策略快照 + topK），
 *       使指标可归因、可复现；</li>
 *   <li>与上一次成功运行对比，给出每项的增减；</li>
 *   <li>落库为 {@link EvalRun}，供 CI 门禁（RAG-004）查询。</li>
 * </ol>
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);

    private final EvalRunRepository runRepository;
    private final EvalDatasetService datasetService;
    private final RetrievalEvalService retrievalEvalService;
    private final GenEvalService genEvalService;
    private final RetrieveService retrieveService;
    private final RerankService rerankService;
    private final ContextAssembler contextAssembler;
    private final ChatService chatService;
    private final BailianConfig bailianConfig;

    public EvalRunService(EvalRunRepository runRepository,
                          EvalDatasetService datasetService,
                          RetrievalEvalService retrievalEvalService,
                          GenEvalService genEvalService,
                          RetrieveService retrieveService,
                          RerankService rerankService,
                          ContextAssembler contextAssembler,
                          ChatService chatService,
                          BailianConfig bailianConfig) {
        this.runRepository = runRepository;
        this.datasetService = datasetService;
        this.retrievalEvalService = retrievalEvalService;
        this.genEvalService = genEvalService;
        this.retrieveService = retrieveService;
        this.rerankService = rerankService;
        this.contextAssembler = contextAssembler;
        this.chatService = chatService;
        this.bailianConfig = bailianConfig;
    }

    /** 运行结果：落库的运行记录 + 与上一次的对比 */
    public record RunResult(EvalRun run, Map<String, Object> delta) {}

    /**
     * 执行一次评测并落库。
     *
     * @param kbId      知识库
     * @param tenantId  租户
     * @param datasetId 评测集 ID
     * @param topK      topK
     * @param rerank    是否对召回结果做精排后再评估
     * @param withGen   是否评估生成端指标（会调用 judge，有成本）
     * @param userId    执行人
     */
    @Transactional
    public RunResult run(Long kbId, Long tenantId, Long datasetId, int topK,
                         boolean rerank, boolean withGen, Long userId) {
        EvalDataset ds = datasetService.get(kbId, datasetId);
        List<RetrievalEvalService.LabeledQuery> dataset = datasetService.loadItems(datasetId);
        if (dataset.isEmpty()) {
            throw new RuntimeException("评测集为空");
        }
        long start = System.currentTimeMillis();

        // 1. 检索端指标
        RetrievalEvalService.EvalMetrics m =
                retrievalEvalService.evaluateDataset(dataset, topK, kbId, tenantId, rerank);

        // 2. 生成端指标（可选）：对每条 query 重新检索 + 走 RAG prompt 生成，再 judge
        GenEvalService.GenScores gen = null;
        if (withGen) {
            gen = genEvalService.evaluateBatch(buildGenSamples(dataset, kbId, tenantId, topK, rerank));
        }

        // 3. 环境指纹
        EvalRun run = new EvalRun();
        run.setDatasetId(datasetId);
        run.setKbId(kbId);
        run.setTenantId(tenantId);
        run.setStatus(EvalRun.STATUS_SUCCESS);
        run.setTopK(topK);
        run.setRecallAtK(m.recallAtK());
        run.setPrecisionAtK(m.precisionAtK());
        run.setHitRate(m.hitRate());
        run.setMrr(m.mrr());
        run.setItemCount(m.count());
        run.setLoopSuspectedCount(m.loopSuspectedCount());
        run.setTrustworthy(m.trustworthy());
        if (gen != null && gen.judged()) {
            run.setFaithfulness(gen.faithfulness());
            run.setAnswerRelevancy(gen.answerRelevancy());
        }
        run.setEmbeddingModel(bailianConfig.getEmbeddingModel());
        run.setChatModel(bailianConfig.getChatModel());
        run.setRerankModel(rerankService.isEnabled() && !rerankService.getEndpoint().isBlank()
                ? "enabled(topN=" + rerankService.getTopN() + ")" : "disabled");
        run.setRerankEnabled(rerank);
        run.setStrategySnapshot(strategySnapshot());
        run.setDurationMs(System.currentTimeMillis() - start);
        run.setCreatedBy(userId);
        run.setCreatedAt(LocalDateTime.now());
        run = runRepository.save(run);

        // 4. 与上一次成功运行对比
        Map<String, Object> delta = compareWithPrevious(run);
        log.info("评测完成：runId={} dataset={} Recall={} Precision={} HitRate={} MRR={} Faith={}",
                run.getId(), datasetId, run.getRecallAtK(), run.getPrecisionAtK(),
                run.getHitRate(), run.getMrr(), run.getFaithfulness());
        return new RunResult(run, delta);
    }

    /** 查某 KB 的评测历史（时间倒序） */
    public List<EvalRun> history(Long kbId) {
        return runRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    /** 查某 KB 最近一次成功运行（CI 门禁的基线） */
    public EvalRun latestSuccess(Long kbId) {
        return runRepository.findFirstByKbIdAndStatusOrderByCreatedAtDesc(kbId, EvalRun.STATUS_SUCCESS);
    }

    /** 两次运行的指标对比（正数=提升，负数=下降） */
    public Map<String, Object> compare(Long currentId, Long baselineId) {
        EvalRun cur = runRepository.findById(currentId)
                .orElseThrow(() -> new RuntimeException("运行记录不存在: " + currentId));
        EvalRun base = runRepository.findById(baselineId)
                .orElseThrow(() -> new RuntimeException("基线运行记录不存在: " + baselineId));
        return diff(cur, base);
    }

    // ===== 内部 =====

    private Map<String, Object> compareWithPrevious(EvalRun current) {
        List<EvalRun> history = runRepository.findByKbIdOrderByCreatedAtDesc(current.getKbId());
        for (EvalRun prev : history) {
            if (!prev.getId().equals(current.getId())
                    && EvalRun.STATUS_SUCCESS.equals(prev.getStatus())) {
                return diff(current, prev);
            }
        }
        return Map.of();
    }

    private static Map<String, Object> diff(EvalRun cur, EvalRun base) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineRunId", base.getId());
        out.put("baselineCreatedAt", String.valueOf(base.getCreatedAt()));
        out.put("currentRunId", cur.getId());
        out.put("deltas", deltas(cur, base));
        out.put("environmentChanged", !String.valueOf(base.getStrategySnapshot())
                .equals(String.valueOf(cur.getStrategySnapshot()))
                || !String.valueOf(base.getEmbeddingModel()).equals(String.valueOf(cur.getEmbeddingModel())));
        return out;
    }

    private static Map<String, Object> deltas(EvalRun cur, EvalRun base) {
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "recallAtK", cur.getRecallAtK(), base.getRecallAtK());
        put(d, "precisionAtK", cur.getPrecisionAtK(), base.getPrecisionAtK());
        put(d, "hitRate", cur.getHitRate(), base.getHitRate());
        put(d, "mrr", cur.getMrr(), base.getMrr());
        put(d, "faithfulness", cur.getFaithfulness(), base.getFaithfulness());
        put(d, "answerRelevancy", cur.getAnswerRelevancy(), base.getAnswerRelevancy());
        return d;
    }

    private static void put(Map<String, Object> d, String key, Double cur, Double base) {
        if (cur == null || base == null) {
            d.put(key, Map.of("current", cur, "baseline", base, "delta", null));
            return;
        }
        d.put(key, Map.of("current", cur, "baseline", base, "delta", cur - base));
    }

    /** 检索策略快照：让两次运行的指标差异可归因 */
    private String strategySnapshot() {
        return "mode=" + retrieveService.getRerankMode()
                + "/pool=" + retrieveService.getCandidatePool()
                + "/minScore=" + retrieveService.getMinScore()
                + "/rrfK=" + retrieveService.getRrfK()
                + "/maxPerDoc=" + retrieveService.getDiversityMaxPerDoc()
                + "/efSearch=" + retrieveService.getHnswEfSearch();
    }

    /** 构造生成端评估样本：对每条 query 重新检索 + 组装上下文 + 生成答案，再交给 judge */
    private List<GenEvalService.GenSample> buildGenSamples(
            List<RetrievalEvalService.LabeledQuery> dataset, Long kbId, Long tenantId,
            int topK, boolean rerank) {
        List<GenEvalService.GenSample> out = new java.util.ArrayList<>(dataset.size());
        for (RetrievalEvalService.LabeledQuery q : dataset) {
            try {
                int retrieveTopK = rerank ? Math.max(topK, rerankService.getTopN() * 4) : topK;
                List<com.qoobot.qoorag.dto.RetrieveChunk> chunks =
                        retrieveService.retrieve(q.query(), retrieveTopK, kbId, tenantId);
                if (rerank) {
                    chunks = rerankService.rerank(q.query(), chunks);
                }
                ContextAssembler.AssembledContext ctx = contextAssembler.assemble(chunks);
                Map<String, Object> llm = chatService.chat(q.query(), ctx.promptContext(), kbId);
                out.add(new GenEvalService.GenSample(q.query(), chunks.stream()
                        .map(com.qoobot.qoorag.dto.RetrieveChunk::getContent).toList(),
                        String.valueOf(llm.get("answer"))));
            } catch (Exception e) {
                log.warn("生成端样本构造失败（跳过该条）: query='{}', {}",
                        q.query(), e.getMessage());
            }
        }
        return out;
    }
}
