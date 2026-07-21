package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RetrievalEvalService：recall@K / precision@K / 命中率 / MRR 与边界（空集/无相关/无召回） */
public class RetrievalEvalServiceTest {

    RetrieveService retrieveService = mock(RetrieveService.class);
    RetrievalEvalService evalService = new RetrievalEvalService(retrieveService);

    private RetrieveChunk chunk(long id, long chunkId, long docId, double score) {
        return new RetrieveChunk(id, chunkId, docId, 1, "content-" + chunkId, score);
    }

    @Test
    void evaluate_dataset_recall_precision_hit_mrr() {
        // Q1：相关 chunk {10,11}；检索返回 [10,20,11,30,40]
        when(retrieveService.retrieve("q1", 5, 2L, 7L))
                .thenReturn(List.of(chunk(1, 10, 1, .9), chunk(2, 20, 2, .8),
                        chunk(3, 11, 1, .7), chunk(4, 30, 3, .6), chunk(5, 40, 4, .5)));
        // Q2：相关 chunk {99}（不在结果中）；检索返回 [10,20,30]
        when(retrieveService.retrieve("q2", 5, 2L, 7L))
                .thenReturn(List.of(chunk(1, 10, 1, .9), chunk(2, 20, 2, .8), chunk(3, 30, 3, .6)));

        RetrievalEvalService.LabeledQuery q1 = new RetrievalEvalService.LabeledQuery(
                "q1", Set.of(10L, 11L), Set.of());
        RetrievalEvalService.LabeledQuery q2 = new RetrievalEvalService.LabeledQuery(
                "q2", Set.of(99L), Set.of());

        RetrievalEvalService.EvalMetrics m = evalService.evaluateDataset(List.of(q1, q2), 5, 2L, 7L);

        assertEquals(2, m.count());
        assertEquals(0.5, m.recallAtK(), 1e-9);   // (1.0 + 0) / 2
        assertEquals(0.2, m.precisionAtK(), 1e-9); // (0.4 + 0) / 2
        assertEquals(0.5, m.hitRate(), 1e-9);      // 1/2 命中
        assertEquals(0.5, m.mrr(), 1e-9);          // (1/1 + 0) / 2
    }

    @Test
    void evaluate_single_matches_by_documentId() {
        List<RetrieveChunk> results = List.of(
                chunk(1, 10, 1, .9), chunk(2, 20, 2, .8), chunk(3, 30, 5, .7));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(), Set.of(5L));
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 5);
        assertEquals(1.0, se.recallAtK(), 1e-9);  // 1 个相关 doc 命中
        assertEquals(0.2, se.precisionAtK(), 1e-9); // 1/5
        assertEquals(true, se.hit());
        assertEquals(3, se.firstHitRank());        // 第 3 位命中
    }

    @Test
    void evaluate_single_doc_relevance_multiple_chunks_caps_recall_at_one() {
        // 标注仅 1 个相关文档(5)，但其 4 个 chunk 全部进榜：召回率应为 100%，而非 400%
        List<RetrieveChunk> results = List.of(
                chunk(1, 30, 5, .9), chunk(2, 31, 5, .8),
                chunk(3, 32, 5, .7), chunk(4, 33, 5, .6));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(), Set.of(5L));
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 8);
        assertEquals(1.0, se.recallAtK(), 1e-9);   // 被覆盖的相关文档数=1 / 标注总数=1
        assertEquals(0.5, se.precisionAtK(), 1e-9); // 4 命中 / K=8
        assertEquals(true, se.hit());
        assertEquals(1, se.firstHitRank());
    }

    @Test
    void evaluate_single_no_relevant_labels_returns_zeros() {
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(), Set.of());
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, List.of(chunk(1, 10, 1, .9)), 5);
        assertEquals(0.0, se.recallAtK(), 1e-9);
        assertEquals(false, se.hit());
        assertEquals(-1, se.firstHitRank());
    }

    @Test
    void evaluate_dataset_empty_returns_zero_metrics() {
        RetrievalEvalService.EvalMetrics m = evalService.evaluateDataset(List.of(), 5, 2L, 7L);
        assertEquals(0, m.count());
        assertEquals(0.0, m.recallAtK(), 1e-9);
        assertEquals(0.0, m.hitRate(), 1e-9);
    }

    @Test
    void evaluate_single_detects_loop_labeling_high_overlap() {
        // 标注 {10,11,12,13,14}，召回 {10,11,12,13,99}：标注被召回覆盖 4/5=80% → 疑似循环标注
        List<RetrieveChunk> results = List.of(
                chunk(1, 10, 1, .9), chunk(2, 11, 1, .8), chunk(3, 12, 1, .7),
                chunk(4, 13, 1, .6), chunk(5, 99, 9, .5));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(10L, 11L, 12L, 13L, 14L), Set.of());
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 5);
        assertEquals(true, se.loopSuspected());
    }

    @Test
    void evaluate_single_not_loop_when_overlap_low() {
        // 标注 {10,11}，召回 {10,20,30,40,50}：覆盖仅 1/2=50% → 非循环标注
        List<RetrieveChunk> results = List.of(
                chunk(1, 10, 1, .9), chunk(2, 20, 2, .8), chunk(3, 30, 3, .7),
                chunk(4, 40, 4, .6), chunk(5, 50, 5, .5));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(10L, 11L), Set.of());
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 5);
        assertEquals(false, se.loopSuspected());
    }

    @Test
    void evaluate_single_doc_only_label_not_loop() {
        // 纯 doc 级标注（无 chunk 标注）不判定循环标注
        List<RetrieveChunk> results = List.of(chunk(1, 10, 5, .9), chunk(2, 20, 5, .8));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(), Set.of(5L));
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 5);
        assertEquals(false, se.loopSuspected());
    }

    @Test
    void evaluate_single_recall_perfect_flags_loop_redline() {
        // 标注 {10,11} 全部落入 TopK（recall=100%）→ 触发规范四.3 红线，判疑似循环标注，
        // 即使 chunk 集合重合 precision 仅 2/5=0.4（低于 0.5，单纯重合度检测不会触发）。
        List<RetrieveChunk> results = List.of(
                chunk(1, 10, 1, .9), chunk(2, 11, 1, .8), chunk(3, 20, 2, .7),
                chunk(4, 30, 3, .6), chunk(5, 40, 4, .5));
        RetrievalEvalService.LabeledQuery q = new RetrievalEvalService.LabeledQuery(
                "q", Set.of(10L, 11L), Set.of());
        RetrievalEvalService.SingleEval se = evalService.evaluateSingle(q, results, 5);
        assertEquals(1.0, se.recallAtK(), 1e-9);
        assertEquals(true, se.loopSuspected());
    }
}
