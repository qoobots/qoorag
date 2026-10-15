package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Provider 就绪检查：启动时明确报告各角色配置状态（密钥整理配套）。
 *
 * <p><b>为什么加这个</b>：改造前密钥缺失时的表现是入库时报一句
 * {@code 向量化失败: 401 Unauthorized: [no body]}——用户完全看不出是"没配 key"、
 * "key 错了"还是"配错角色了"（DeepSeek 的 key 填到 embedding 槽就是典型）。
 *
 * <p>现在启动即打印一张表，把"哪个角色没配 / 配了哪个 Provider"说清楚，缺失项给出下一步动作。
 */
@Component
@Order(5)
public class ProviderReadinessChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProviderReadinessChecker.class);

    private final BailianConfig config;
    private final RerankService rerankService;

    public ProviderReadinessChecker(BailianConfig config, RerankService rerankService) {
        this.config = config;
        this.rerankService = rerankService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean chatOk = config.hasChatKey();
        boolean embedOk = config.hasEmbeddingKey();

        log.info("========== LLM Provider 就绪状态 ==========");
        log.info("Chat     : {}  model={}  base={}",
                chatOk ? "已配置" : "★未配置★", config.getChatModel(), config.getChatBaseUrl());
        log.info("Embedding: {}  model={}  base={}",
                embedOk ? "已配置" : "★未配置★", config.getEmbeddingModel(), config.getEmbeddingBaseUrl());
        log.info("Rerank   : {}  endpoint={}",
                rerankService.isEnabled() ? "已开启" : "未开启（不影响使用）",
                rerankService.isEnabled() ? rerankService.getEndpoint() : "-");

        if (!chatOk) {
            log.warn("Chat 未配置密钥：问答/Contextual Chunking/评测 Judge 均不可用。"
                    + "请在 .env 设置 LLM_API_KEY（或在管理后台资源池配置 LLM.api_key）");
        }
        if (!embedOk) {
            log.warn("Embedding 未配置密钥：★RAG 核心链路不可用★（文档无法向量化、查询无法召回）。"
                    + "请在 .env 设置 EMBEDDING_API_KEY。"
                    + "注意：DeepSeek 只提供 Chat 模型，不提供 embedding，需另配 Provider"
                    + "（如阿里云百炼 text-embedding-v2 / OpenAI text-embedding-3-small）");
        } else if (isFallbackFromDifferentProvider()) {
            // 典型误配：EMBEDDING_API_KEY 留空 → 回退到 Chat 的 key，
            // 而 Chat 用的是不提供 embedding 的 Provider（如 DeepSeek）。
            // 此时"已配置"是假象，首次向量化才会以 401 失败。
            log.warn("Embedding 密钥是回退沿用 Chat 密钥的，且两者 base-url 不同："
                    + "chat={} embedding={}。", config.getChatBaseUrl(), config.getEmbeddingBaseUrl());
            log.warn("若 Chat 用的 Provider 不提供 embedding（如 DeepSeek），向量化将以 401 失败。"
                    + "请在 .env 单独设置 EMBEDDING_API_KEY（阿里云百炼 / OpenAI / 其它兼容服务）。");
        }
        if (rerankService.isEnabled()
                && (rerankService.resolveApiKey() == null || rerankService.resolveApiKey().isBlank())) {
            log.warn("Rerank 已开启但无可用密钥，将自动降级为不精排（不影响检索）。"
                    + "如需精排请在 .env 设置 RERANK_API_KEY");
        }
        log.info("===========================================");
    }

    /**
     * Embedding 密钥是否"回退沿用了 Chat 密钥、且两者指向不同 Provider"。
     * <p>判定条件：{@code EMBEDDING_API_KEY} 未显式配置（与共享密钥相同），
     * 且 embedding 与 chat 的 base-url 不同——同一 Provider 时复用密钥是合理的，
     * 不同 Provider 时几乎必然是误配（例如 DeepSeek 的 key 拿去调百炼的 embedding）。
     */
    private boolean isFallbackFromDifferentProvider() {
        String shared = config.getApiKey();
        String embeddingKey = config.getEmbeddingApiKey();
        // 显式配置了 embedding 专用密钥 → 不是回退
        if (shared == null || !shared.equals(embeddingKey)) {
            return false;
        }
        String chatBase = config.getChatBaseUrl();
        String embedBase = config.getEmbeddingBaseUrl();
        if (chatBase == null || embedBase == null) {
            return false;
        }
        return !normalizeBase(chatBase).equals(normalizeBase(embedBase));
    }

    /** base-url 归一化：忽略协议前缀与末尾斜杠，避免 https://x.com 与 https://x.com/ 判为不同 */
    private static String normalizeBase(String url) {
        String s = url.trim();
        int idx = s.indexOf("://");
        if (idx >= 0) {
            s = s.substring(idx + 3);
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
