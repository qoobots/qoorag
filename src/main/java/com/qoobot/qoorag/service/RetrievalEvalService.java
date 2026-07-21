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

    /** 聚合指标：平均召回率 / 平均准确率 / 命中率 / 平均倒数排名(MRR) / 样本数 */
    public record EvalMetrics(double recallAtK, double precisionAtK, double hitRate, double mrr, int count) {}

    /** 单条评估结果 */
    public record SingleEval(double recallAtK, double precisionAtK, boolean hit, int firstHitRank) {}

    /** 单条查询评估：在 topK 范围内比对相关性 */
    public SingleEval evaluateSingle(LabeledQuery q, List<RetrieveChunk> results, int topK) {
        int relevantTotal = countRelevant(q);
        if (relevantTotal == 0 || topK <= 0) {
            // 无标注相关项（或 K 非法）无法计算召回，置 0
            return new SingleEval(0.0, 0.0, false, -1);
        }
        Set<Long> relChunk = q.relevantChunkIds();
        Set<Long> relDoc = q.relevantDocIds();
        int hits = 0;
        int firstHitRank = -1;
        int limit = Math.min(topK, results.size());
        for (int i = 0; i < limit; i++) {
            RetrieveChunk c = results.get(i);
            boolean match = relChunk.contains(c.getChunkId()) || relDoc.contains(c.getDocumentId());
            if (match) {
                hits++;
                if (firstHitRank < 0) {
                    firstHitRank = i + 1;
                }
            }
        }
        double recall = (double) hits / relevantTotal;
        double precision = (double) hits / topK; // 标准 precision@K：相关命中数 / K
        return new SingleEval(recall, precision, hits > 0, firstHitRank);
    }

    /** 数据集聚合评估：逐条调用 RetrieveService.retrieve 后汇总 */
    public EvalMetrics evaluateDataset(List<LabeledQuery> dataset, int topK, Long kbId, Long tenantId) {
        if (dataset == null || dataset.isEmpty()) {
            return new EvalMetrics(0.0, 0.0, 0.0, 0.0, 0);
        }
        double sumRecall = 0, sumPrecision = 0, sumHit = 0, sumRR = 0;
        for (LabeledQuery q : dataset) {
            List<RetrieveChunk> results = retrieveService.retrieve(q.query(), topK, kbId, tenantId);
            SingleEval se = evaluateSingle(q, results, topK);
            sumRecall += se.recallAtK();
            sumPrecision += se.precisionAtK();
            sumHit += se.hit() ? 1 : 0;
            sumRR += se.firstHitRank() > 0 ? 1.0 / se.firstHitRank() : 0.0;
        }
        int n = dataset.size();
        return new EvalMetrics(sumRecall / n, sumPrecision / n, sumHit / n, sumRR / n, n);
    }

    /** 去重计数相关项（chunk 与 doc 可能重叠） */
    private int countRelevant(LabeledQuery q) {
        Set<Long> merged = new HashSet<>(q.relevantChunkIds());
        merged.addAll(q.relevantDocIds());
        return merged.size();
    }
}
