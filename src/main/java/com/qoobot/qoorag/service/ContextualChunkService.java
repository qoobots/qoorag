package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contextual Chunking 服务（RAG-023）：为每个 chunk 生成"它在原文中的位置与上下文"描述。
 *
 * <p><b>问题</b>：固定长度切块后，单个 chunk 往往是"失忆"的——
 * 「营收 3000 万」单独看不知道是哪家公司、哪个季度、哪个产品线；
 * 向量模型只能基于这十几个字编码，语义被严重削弱。这是"短文档/摘要片段被大文档淹没"的根因之一。
 *
 * <p><b>做法</b>（Anthropic Contextual Retrieval，2026 年摄入侧收益最大的单项优化）：
 * 入库时用 LLM 为每个 chunk 生成 50~100 字的上下文说明，拼在 chunk 文本前面一起 embedding
 * 与建词法索引。官方实测：仅 Contextual Embedding 使 top-20 检索失败率 5.7%→3.7%（-35%），
 * 叠加 Contextual BM25 为 -49%，再叠加 rerank 累计 -67%。
 *
 * <p><b>本项目的落地取舍</b>：
 * <ul>
 *   <li><b>只影响离线入库，不增加在线延迟</b>——前缀在入库时算好存库，检索/问答链路零改动；</li>
 *   <li><b>默认关闭</b>（{@code qoorag.ingest.contextual.enabled=false}）：每 chunk 多一次 LLM 调用，
 *       成本敏感场景需显式开启；开启后建议配 prompt caching；</li>
 *   <li><b>上下文来源用文档标题 + 标题路径</b>而非整篇原文：整篇原文 prompt 过长、
 *       成本高且收益递减；标题路径已由 RAG-021 结构感知切块提供，恰好是最有价值的定位信号；</li>
 *   <li><b>生成失败静默降级</b>：返回 null，调用方退化为无前缀 embedding，不阻塞入库。</li>
 * </ul>
 */
@Service
public class ContextualChunkService {

    private static final Logger log = LoggerFactory.getLogger(ContextualChunkService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;

    /** 是否启用 Contextual Chunking（默认关闭：每 chunk 多一次 LLM 调用） */
    @Value("${qoorag.ingest.contextual.enabled:false}")
    private boolean enabled;

    /** 生成上下文用的模型；为空则复用 chat 模型（建议用更轻量的模型降本） */
    @Value("${qoorag.ingest.contextual.model:}")
    private String model;

    /** 上下文前缀的最大字符数 */
    @Value("${qoorag.ingest.contextual.max-prefix-chars:200}")
    private int maxPrefixChars;

    /** 单次生成的 temperature，低温保证描述稳定可复现 */
    @Value("${qoorag.ingest.contextual.temperature:0.1}")
    private double temperature;

    public ContextualChunkService(RestTemplate restTemplate, BailianConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 为一个 chunk 生成上下文前缀。
     *
     * @param chunkContent chunk 原文
     * @param docTitle     文档标题
     * @param headingPath  标题路径（RAG-021 提供，最重要的定位信号）
     * @param chunkType    块类型（paragraph/table/code/...）
     * @return 上下文前缀；未启用或生成失败时返回 null（调用方应退化为无前缀）
     */
    public String generatePrefix(String chunkContent, String docTitle, String headingPath, String chunkType) {
        if (!enabled || chunkContent == null || chunkContent.isBlank()) {
            return null;
        }
        String prompt = buildPrompt(chunkContent, docTitle, headingPath, chunkType);
        String raw = call(prompt);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String prefix = raw.trim().replaceAll("\\s+", " ");
        if (prefix.length() > maxPrefixChars) {
            prefix = prefix.substring(0, maxPrefixChars);
        }
        return prefix;
    }

    /**
     * 拼装最终用于 embedding / 词法索引的文本。
     * <p>前缀在前、原文在后；未启用时原文原样返回（行为与改造前完全一致）。
     */
    public String embeddingText(String chunkContent, String contextPrefix) {
        if (contextPrefix == null || contextPrefix.isBlank()) {
            return chunkContent;
        }
        return contextPrefix + "\n\n" + chunkContent;
    }

    /** 构造生成 prompt */
    String buildPrompt(String chunkContent, String docTitle, String headingPath, String chunkType) {
        StringBuilder location = new StringBuilder();
        if (notBlank(docTitle)) {
            location.append("文档标题：").append(docTitle).append('\n');
        }
        if (notBlank(headingPath)) {
            location.append("所在章节：").append(headingPath).append('\n');
        }
        if (notBlank(chunkType)) {
            location.append("块类型：").append(chunkType).append('\n');
        }
        if (location.length() == 0) {
            location.append("（无文档结构信息）\n");
        }
        return """
                下面是从一篇文档中切出的一个片段。请写一段 50~100 字的简洁说明，
                交代这个片段出自哪里、讲的是什么，使单独看这段说明 + 片段就能理解其含义。
                只输出说明文字，不要输出解释、引号或任何前后缀。

                %s【片段】
                %s
                """.formatted(location, truncate(chunkContent, 1500));
    }

    private String call(String prompt) {
        String useModel = notBlank(model) ? model : config.getChatModel();
        Map<String, Object> body = new HashMap<>();
        body.put("model", useModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", temperature);
        body.put("max_tokens", 200);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    config.getBaseUrl() + "/chat/completions", new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> respBody = resp.getBody();
            if (respBody == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            return msg == null ? null : (String) msg.get("content");
        } catch (Exception e) {
            // 生成失败不应阻塞入库：退化为无前缀
            log.warn("Contextual Chunking 前缀生成失败，退化为无前缀: {}", e.getMessage());
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
