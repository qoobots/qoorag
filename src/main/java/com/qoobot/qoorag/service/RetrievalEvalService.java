package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量评估服务（#16）：基于标注的「查询-相关片段」数据集，量化向量检索召回质量。
 * <p>
 * 相关性判定支持按 chunkId 或 documentId 匹配（任一命中即计为命中），适配"以文档为单位标注"
 * 或"以分块为单位标注"两种标注习惯。
 * 对 {@link RetrieveService} 仅做方法调用，测试中以 Mockito mock，不触达 pgvector / 百炼。
 */
@Service
public class RetrievalEvalService {

    private final RetrieveService retrieveService;

    public RetrievalEvalService(RetrieveService retrieveService) {
        this.retrieveService = retrieveService;
    }

    /** 一条标注：查询文本 + 相关 chunkId 集合 + 相关 documentId 集合（任一可空） */
    public record LabeledQuery(String query, Set<Long> relevantChunkIds, Set<Long> relevantDocIds) {
        public LabeledQuery {
            relevantChunkIds = relevantChunkIds == null ? Set.of() : relevantChunkIds;
            relevantDocIds = relevantDocIds == null ? Set.of() : relevantDocIds;
        }
    }

    /** 单条查询中实际召回的片段及命中情况（逐条明细诊断用） */
    public record RetrievedItem(long chunkId, long documentId, double score, boolean matched, int rank) {}

    /** 聚合指标：平均召回率 / 平均准确率 / 命中率 / 平均倒数排名(MRR) / 样本数 / 逐条明细
     *  / 疑似循环标注条数 / 指标是否可信（规范五：全部 100% 默认不可信） */
    public record EvalMetrics(double recallAtK, double precisionAtK, double hitRate, double mrr, int count,
                               List<SingleEval> details, int loopSuspectedCount, boolean trustworthy) {}

    /** 单条评估结果 */
    public record SingleEval(double recallAtK, double precisionAtK, boolean hit, int firstHitRank,
                             List<RetrievedItem> items, boolean loopSuspected, double overlapRatio) {}

    /** 单条查询评估：在 topK 范围内比对相关性 */
    public SingleEval evaluateSingle(LabeledQuery q, List<RetrieveChunk> results, int topK) {
        int relevantTotal = countRelevant(q);
        if (relevantTotal == 0 || topK <= 0) {
            // 无标注相关项（或 K 非法）无法计算召回，置 0；无 chunk 标注故不判定循环标注
            return new SingleEval(0.0, 0.0, false, -1, List.of(), false, 0.0);
        }
        Set<Long> relChunk = q.relevantChunkIds();
        Set<Long> relDoc = q.relevantDocIds();
        Set<Long> coveredChunks = new HashSet<>();
        Set<Long> coveredDocs = new HashSet<>();
        int hits = 0;
        int firstHitRank = -1;
        int limit = Math.min(topK, results.size());
        List<RetrievedItem> items = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RetrieveChunk c = results.get(i);
            boolean matchChunk = relChunk.contains(c.getChunkId());
            boolean matchDoc = relDoc.contains(c.getDocumentId());
            boolean match = matchChunk || matchDoc;
            if (match) {
                hits++;
                if (firstHitRank < 0) {
                    firstHitRank = i + 1;
                }
                // 记录被覆盖的不同相关项：同一相关文档只要有任一 chunk 进榜即算覆盖一次
                if (matchChunk) {
                    coveredChunks.add(c.getChunkId());
                }
                if (matchDoc) {
                    coveredDocs.add(c.getDocumentId());
                }
            }
            items.add(new RetrievedItem(c.getChunkId(), c.getDocumentId(), c.getScore(), match, i + 1));
        }
        // 召回率 = 被覆盖的不同相关项数 / 标注相关总数（covered ⊆ relevantTotal，故恒 ≤ 100%）
        int covered = coveredChunks.size() + coveredDocs.size();
        double recall = (double) covered / relevantTotal;
        double precision = (double) hits / topK; // 标准 precision@K：相关命中数 / K（hits≤K，故恒 ≤ 100%）

        // 循环标注 / 标注污染检测，结合两类信号：
        //  (a) chunk 集合重合度：标注 chunk 与召回 chunk 的重合（coverage/prec 高 → 极可能直接取自召回结果）；
        //  (b) 规范红线（四.3）：标注的 chunk 100% 落在 TopK 召回内（recall==1.0），
        //      优先怀疑标注污染，该条指标无参考意义。
        // 注：doc 级标注（relChunk 为空）不触发红线 (b)，因 doc 级 recall==100% 较易自然出现，非强信号。
        boolean loopSuspected = false;
        double overlapRatio = 0.0;
        if (!relChunk.isEmpty()) {
            Set<Long> retrievedChunks = new HashSet<>();
            for (RetrievedItem it : items) {
                retrievedChunks.add(it.chunkId());
            }
            Set<Long> inter = new HashSet<>(relChunk);
            inter.retainAll(retrievedChunks);
            double coverage = (double) inter.size() / relChunk.size();
            double prec = retrievedChunks.isEmpty() ? 0.0 : (double) inter.size() / retrievedChunks.size();
            loopSuspected = coverage >= 0.8 && prec >= 0.5;
            Set<Long> union = new HashSet<>(relChunk);
            union.addAll(retrievedChunks);
            overlapRatio = union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
            // 红线 (b)：标注相关 chunk 100% 被召回
            if (recall >= 1.0 - 1e-9) {
                loopSuspected = true;
            }
        }
        return new SingleEval(recall, precision, covered > 0, firstHitRank, items, loopSuspected, overlapRatio);
    }

    /** 数据集聚合评估：逐条调用 RetrieveService.retrieve 后汇总 */
    public EvalMetrics evaluateDataset(List<LabeledQuery> dataset, int topK, Long kbId, Long tenantId) {
        if (dataset == null || dataset.isEmpty()) {
            return new EvalMetrics(0.0, 0.0, 0.0, 0.0, 0, List.of(), 0, true);
        }
        double sumRecall = 0, sumPrecision = 0, sumHit = 0, sumRR = 0;
        List<SingleEval> details = new ArrayList<>(dataset.size());
        for (LabeledQuery q : dataset) {
            List<RetrieveChunk> results = retrieveService.retrieve(q.query(), topK, kbId, tenantId);
            SingleEval se = evaluateSingle(q, results, topK);
            details.add(se);
            sumRecall += se.recallAtK();
            sumPrecision += se.precisionAtK();
            sumHit += se.hit() ? 1 : 0;
            sumRR += se.firstHitRank() > 0 ? 1.0 / se.firstHitRank() : 0.0;
        }
        int n = dataset.size();
        double avgRecall = sumRecall / n;
        double avgPrecision = sumPrecision / n;
        int loopCount = 0;
        for (SingleEval se : details) {
            if (se.loopSuspected()) {
                loopCount++;
            }
        }
        // 指标有效性自检（规范五）：有效评估集应存在 Recall<100% 与 Precision<100%。
        // 若平均指标双百，或所有查询均被标记疑似循环标注，则评估结果不可信。
        boolean trustworthy = n > 0
                && !(avgRecall >= 1.0 - 1e-9 && avgPrecision >= 1.0 - 1e-9)
                && !(loopCount == n);
        return new EvalMetrics(avgRecall, avgPrecision, sumHit / n, sumRR / n, n, details, loopCount, trustworthy);
    }

    /** 去重计数相关项（chunk 与 doc 可能重叠） */
    private int countRelevant(LabeledQuery q) {
        Set<Long> merged = new HashSet<>(q.relevantChunkIds());
        merged.addAll(q.relevantDocIds());
        return merged.size();
    }
}
