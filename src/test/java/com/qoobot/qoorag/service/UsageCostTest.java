package com.qoobot.qoorag.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RAG-053 成本估算纯函数单测（无 Mock，规避 JDK25/Mockito 预存环境问题） */
public class UsageCostTest {

    @Test
    void embeddingCost_usesTotalTokens() {
        double c = UsageCost.estimate(UsageCost.Metric.EMBEDDING, 0.0007, 0.004, 0.012, 0.0004, 0, 0, 1000);
        assertEquals(0.0007, c, 1e-9);
    }

    @Test
    void llmCost_distinguishesInputAndOutput() {
        // 1000 输入 * 0.004/1k + 500 输出 * 0.012/1k = 0.004 + 0.006 = 0.01
        double c = UsageCost.estimate(UsageCost.Metric.LLM, 0.0007, 0.004, 0.012, 0.0004, 1000, 500, 0);
        assertEquals(0.010, c, 1e-9);
    }

    @Test
    void rerankCost_usesTotalTokens() {
        double c = UsageCost.estimate(UsageCost.Metric.RERANK, 0.0007, 0.004, 0.012, 0.0004, 0, 0, 2000);
        assertEquals(0.0008, c, 1e-9);
    }
}
