package com.qoobot.qoorag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 大模型 Provider 配置（OpenAI 兼容模式）。
 *
 * <p><b>为什么按角色拆开</b>：改造前 {@code baseUrl} + {@code apiKey} 是<b>一套配置同时供
 * 对话与向量化使用</b>，这意味着"换个只提供 Chat 的 Provider（如 DeepSeek）"会把向量化一起搞坏——
 * 而 RAG 的向量化是必需项，不可降级。
 *
 * <p>现在每个角色可独立配置，未单独配置时回退到共享默认值（向后兼容旧配置）：
 * <table border="1">
 *   <tr><th>角色</th><th>字段</th><th>用途</th><th>可否缺省</th></tr>
 *   <tr><td>Chat</td><td>{@code chat-base-url} / {@code chat-api-key} / {@code chat-model}</td>
 *       <td>问答生成、Contextual Chunking 前缀生成、评测 Judge</td><td>不可</td></tr>
 *   <tr><td>Embedding</td><td>{@code embedding-base-url} / {@code embedding-api-key} / {@code embedding-model}</td>
 *       <td>文档向量化、查询向量化</td><td>不可（RAG 必需）</td></tr>
 *   <tr><td>Rerank</td><td>{@code qoorag.retrieval.rerank.*}</td>
 *       <td>精排</td><td>可（关掉即可）</td></tr>
 * </table>
 *
 * <p>对应 {@code .env} 变量：{@code LLM_*} → Chat，{@code EMBEDDING_*} → Embedding，
 * {@code RERANK_*} → Rerank。
 */
@Configuration
@ConfigurationProperties(prefix = "qoorag.bailian")
public class BailianConfig {

    // ===== 共享默认值（兼容旧配置）：角色未单独配置时回退到这里 =====
    private String baseUrl;
    private String apiKey;

    // ===== 旧版百炼专用变量（ALI-API-KEY）：Embedding 的历史默认来源 =====
    // 单独拆出的原因：.env 里 EMBEDDING_API_KEY 留空（表示"未配置"）时，
    // Spring 的 ${A:${B:}} 会把空串当作"已设置"而不再回退到 B，导致本该命中
    // ALI-API-KEY 的链路断裂。用一个独立字段承载 B，即可在 Java 侧正确回退。
    private String legacyBaseUrl;
    private String legacyApiKey;

    // ===== Chat 角色 =====
    private String chatBaseUrl;
    private String chatApiKey;
    private String chatModel;

    // ===== Embedding 角色 =====
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel;
    private int embeddingBatchSize = 25;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    // ===== 共享字段 getter/setter（ResourcePoolLoader 会 set 这些）=====

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    // ===== 角色解析：角色配置优先，逐级回退 =====
    // Chat：chat-* > 共享 > 旧版百炼
    public String getChatBaseUrl() { return firstNonBlank(chatBaseUrl, baseUrl, legacyBaseUrl); }
    public String getChatApiKey() { return firstNonBlank(chatApiKey, apiKey, legacyApiKey); }
    public String getChatModel() { return firstNonBlank(chatModel, "qwen-plus"); }

    // Embedding：embedding-* > 旧版百炼（ALI-API-KEY，历史默认）> 共享
    // 注意回退顺序与 Chat 不同：Embedding 历来用 ALI-API-KEY，而 Chat 现在可能已切到
    // 别的 Provider（如 DeepSeek），不能反过来让 Embedding 继承 Chat 的 key。
    public String getEmbeddingBaseUrl() { return firstNonBlank(embeddingBaseUrl, legacyBaseUrl, baseUrl); }
    public String getEmbeddingApiKey() { return firstNonBlank(embeddingApiKey, legacyApiKey, apiKey); }
    public String getEmbeddingModel() { return firstNonBlank(embeddingModel, "text-embedding-v2"); }

    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    // ===== 角色字段 setter（供 ResourcePoolLoader 等按角色覆盖）=====

    public void setChatBaseUrl(String v) { this.chatBaseUrl = v; }
    public void setChatApiKey(String v) { this.chatApiKey = v; }
    public void setChatModel(String v) { this.chatModel = v; }
    public void setEmbeddingBaseUrl(String v) { this.embeddingBaseUrl = v; }
    public void setEmbeddingApiKey(String v) { this.embeddingApiKey = v; }
    public void setEmbeddingModel(String v) { this.embeddingModel = v; }
    public void setLegacyBaseUrl(String v) { this.legacyBaseUrl = v; }
    public void setLegacyApiKey(String v) { this.legacyApiKey = v; }

    /** 某角色的密钥是否已配置（供启动期就绪检查给出明确提示） */
    public boolean hasChatKey() { return isNotBlank(getChatApiKey()); }
    public boolean hasEmbeddingKey() { return isNotBlank(getEmbeddingApiKey()); }

    /**
     * 取第一个非空白值；全部空白时返回 {@code null}（而非最后一个空串），
     * 避免把空串当作"已配置的值"传给下游（例如拼出 {@code Authorization: Bearer }）。
     */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (isNotBlank(c)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
