package com.qoobot.qoorag.service;

/**
 * 用量成本估算（RAG-053）。
 * <p>
 * 纯函数，按「每 1k token 单价」估算单次调用的费用；embedding / rerank 仅按总 token 计，
 * LLM 区分输入/输出单价。价格为可配置项（见 {@code qoorag.cost.*}），此处只做计算。
 */
public final class UsageCost {

    public enum Metric { EMBEDDING, RERANK, LLM }

    private UsageCost() {
    }

    /**
     * @param metric            指标类型
     * @param embeddingPricePer1k  embedding 每 1k token 单价
     * @param llmInputPricePer1k   LLM 输入每 1k token 单价
     * @param llmOutputPricePer1k  LLM 输出每 1k token 单价
     * @param rerankPricePer1k     rerank 每 1k token 单价
     * @param promptTokens         LLM 输入 token（embedding/rerank 传 0）
     * @param completionTokens     LLM 输出 token（embedding/rerank 传 0）
     * @param totalTokens          总 token（embedding/rerank 用此计；LLM 可传 prompt+completion）
     * @return 估算费用（与价格同币种）
     */
    public static double estimate(Metric metric,
                                 double embeddingPricePer1k,
                                 double llmInputPricePer1k,
                                 double llmOutputPricePer1k,
                                 double rerankPricePer1k,
                                 int promptTokens, int completionTokens, int totalTokens) {
        return switch (metric) {
            case EMBEDDING -> totalTokens * embeddingPricePer1k / 1000.0;
            case RERANK    -> totalTokens * rerankPricePer1k / 1000.0;
            case LLM       -> (promptTokens * llmInputPricePer1k + completionTokens * llmOutputPricePer1k) / 1000.0;
        };
    }
}
