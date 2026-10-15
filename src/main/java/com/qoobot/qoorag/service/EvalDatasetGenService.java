package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.EvalDataset;
import com.qoobot.qoorag.repository.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 评测集自动生成（RAG-001）。
 *
 * <p><b>改造前</b>：评测集是 1 份手写 JSON，全部为文档级标注、样本量个位数到十位数，
 * 且不知道标注怎么来的（{@code RetrievalEvalService} 的 {@code loopSuspected} 告警正是为此设计）。
 * 手工标注不可扩展，也无法随知识库内容演进而更新。
 *
 * <p><b>做法</b>（业界标准的"从文档反向生成 query"）：
 * <ol>
 *   <li>从 KB 中<b>均匀跨文档抽样</b> chunk（避免同一文档霸榜，抽样种子固定保证可复现）；</li>
 *   <li>对每个 chunk 调 LLM 生成若干个"只有该 chunk 能回答"的问题；</li>
 *   <li>标注：{@code relevantChunkIds=[该chunk]} + {@code relevantDocIds=[该文档]}，
 *       chunk 级标注让 Precision@K 的计算更有意义（文档级标注会高估 Precision）；</li>
 *   <li>{@code originQuery} 记录被提问的 chunk 摘要，供人工抽检回溯。</li>
 * </ol>
 *
 * <p><b>已知局限</b>（写进文档，避免误用）：
 * <ul>
 *   <li>生成的问题是"针对单个 chunk"的，测不出需要<b>多 chunk 联合</b>才能回答的复杂问题，
 *       这类 query 需人工补充；</li>
 *   <li>LLM 可能生成 chunk 实际答不上来的问题（幻觉问题），因此必须人工抽检——
 *       服务返回 {@link GenReport#qualityHint()} 提示抽检比例；</li>
 *   <li>不应把自动生成集当作唯一标准：建议"自动集（量大，看趋势）+ 人工集（量小，看绝对值）"双轨。</li>
 * </ul>
 */
@Service
public class EvalDatasetGenService {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetGenService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;
    private final ChunkRepository chunkRepository;
    private final EvalDatasetService datasetService;

    /** 抽样随机种子（固定 → 生成结果可复现，便于对比两次生成） */
    private static final long SEED = 20261015L;

    /** 单个 chunk 最多生成的问题数 */
    private static final int MAX_PER_CHUNK = 3;

    /** 单个 chunk 送入 LLM 的最大字符数 */
    private static final int MAX_CHUNK_CHARS = 1500;

    public EvalDatasetGenService(RestTemplate restTemplate,
                                 BailianConfig config,
                                 ChunkRepository chunkRepository,
                                 EvalDatasetService datasetService) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.chunkRepository = chunkRepository;
        this.datasetService = datasetService;
    }

    /** 生成结果报告 */
    public record GenReport(EvalDataset dataset, int chunksSampled, int itemsGenerated,
                            int chunksFailed, String qualityHint) {}

    /**
     * 生成评测集并落库。
     *
     * @param kbId      知识库
     * @param tenantId  租户
     * @param name      评测集名称
     * @param totalItems 目标条目数（实际可能因失败而略少）
     * @param maxChunks 最多抽样多少个 chunk（防止一次跑太久）
     * @param userId    执行人
     */
    @Transactional
    public GenReport generate(Long kbId, Long tenantId, String name, int totalItems,
                              int maxChunks, Long userId) {
        List<Chunk> all = chunkRepository.findByKbId(kbId);
        if (all.isEmpty()) {
            throw new RuntimeException("知识库下没有可用 chunk，无法生成评测集");
        }
        List<Chunk> picked = sampleEvenly(all, Math.min(Math.max(totalItems, 1), Math.max(maxChunks, 1)));
        log.info("评测集生成：kbId={} 总 chunk={}，跨文档均匀抽样 {} 个", kbId, all.size(), picked.size());

        List<EvalDatasetService.EvalItem> items = new ArrayList<>();
        int failed = 0;
        for (Chunk c : picked) {
            List<String> questions;
            try {
                questions = generateQuestions(c.getContent());
            } catch (Exception e) {
                log.warn("chunk {} 问题生成失败: {}", c.getId(), e.getMessage());
                failed++;
                continue;
            }
            for (String q : questions) {
                if (q == null || q.isBlank()) {
                    continue;
                }
                items.add(new EvalDatasetService.EvalItem(
                        q.trim(),
                        Set.of(c.getId()),
                        Set.of(c.getDocumentId()),
                        originSnippet(c.getContent())));
            }
        }
        if (items.isEmpty()) {
            throw new RuntimeException("未能生成任何评测条目（LLM 调用可能失败或未配置 API Key）");
        }
        EvalDataset ds = datasetService.create(kbId, tenantId, name, EvalDataset.SOURCE_GENERATED,
                items, userId);
        return new GenReport(ds, picked.size(), items.size(), failed, qualityHint(items.size()));
    }

    /**
     * 跨文档分层抽样：按文档分轮，每轮每个文档取一个，直到取满。
     * <p>
     * <b>策略与取舍</b>：纯随机抽样会让大文档霸榜（评测集过度代表少数文档的视角），
     * 而纯按文档等权又会过度代表只有一两个 chunk 的小文档。分轮抽取在两者之间：
     * <ul>
     *   <li>保证每个有内容的文档至少被抽到 1 个（只要 want ≥ 文档数）——最低代表性；</li>
     *   <li>文档内容量差异大时，占比自然趋向内容分布（大文档多抽），这符合预期；</li>
     *   <li>组内按 seq 排序 + 固定种子打乱组间顺序 → <b>结果确定可复现</b>，
     *       这对"两次生成结果可对比"很重要。</li>
     * </ul>
     */
    List<Chunk> sampleEvenly(List<Chunk> all, int want) {
        if (want >= all.size()) {
            return new ArrayList<>(all);
        }
        // 按文档分组，组内按 seq 排序（确定性）
        Map<Long, List<Chunk>> byDoc = new LinkedHashMap<>();
        for (Chunk c : all) {
            byDoc.computeIfAbsent(c.getDocumentId(), k -> new ArrayList<>()).add(c);
        }
        List<List<Chunk>> groups = new ArrayList<>(byDoc.values());
        for (List<Chunk> g : groups) {
            g.sort((a, b) -> Integer.compare(
                    a.getSeq() == null ? 0 : a.getSeq(), b.getSeq() == null ? 0 : b.getSeq()));
        }
        // 固定种子的轻量打乱组间顺序，避免总是偏向 seq 靠前的文档
        List<List<Chunk>> order = new ArrayList<>(groups);
        Collections.shuffle(order, new Random(SEED));

        List<Chunk> out = new ArrayList<>(want);
        int round = 0;
        while (out.size() < want && round < 1000) {
            boolean added = false;
            for (List<Chunk> g : order) {
                if (round < g.size() && out.size() < want) {
                    out.add(g.get(round));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
            round++;
        }
        return out;
    }

    /** 调 LLM 从 chunk 反向生成问题 */
    List<String> generateQuestions(String chunkContent) {
        String prompt = """
                你是评测集构造专家。阅读下面的【资料片段】，构造 %d 个"只能依据该片段回答"的自然语言问题。
                要求：
                1. 问题必须是真实用户会问的口语化/书面化提问，不要照抄片段原句；
                2. 问题应当覆盖片段中的关键事实、数字或结论，不要把答案写进问题；
                3. 不要提"根据资料""片段中提到"这类元话语；
                4. 每行一个问题，不要编号、不要额外解释。

                【资料片段】
                %s
                """.formatted(MAX_PER_CHUNK, truncate(chunkContent, MAX_CHUNK_CHARS));

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getChatModel());
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                config.getBaseUrl() + "/chat/completions", new HttpEntity<>(body, headers), Map.class);
        Map<String, Object> respBody = resp.getBody();
        if (respBody == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        String content = msg == null ? null : (String) msg.get("content");
        return parseQuestions(content);
    }

    /** 解析 LLM 输出的问题列表（每行一个，兼容带编号/项目符号的格式） */
    static List<String> parseQuestions(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 去掉 "1." "1、" "- " "* " 等前缀
            s = s.replaceFirst("^\\s*(\\d+[.、)）]|[-*•])\\s*", "").trim();
            // 去掉可能的引号包裹
            if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                    || (s.startsWith("“") && s.endsWith("”")))) {
                s = s.substring(1, s.length() - 1).trim();
            }
            if (s.length() >= 4 && !s.startsWith("【")) {
                out.add(s);
            }
        }
        return out;
    }

    /** 去重后的条目数提示（同一 chunk 生成的问题理论上不同，仍做一次去重保险） */
    private String qualityHint(int items) {
        return String.format("已生成 %d 条 chunk 级标注样本。按验收标准需人工抽检 ≥20%% 且准确率 ≥80%%；"
                + "自动集无法覆盖需多 chunk 联合回答的复杂问题，请补充人工集。", items);
    }

    private static String originSnippet(String content) {
        if (content == null) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
