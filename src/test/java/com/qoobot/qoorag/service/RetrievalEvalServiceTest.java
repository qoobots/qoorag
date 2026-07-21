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
}
