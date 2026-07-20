package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * LLM 问答服务：调用百炼 Chat API（OpenAI 兼容模式）
 * 基于检索上下文构建 RAG Prompt，调用 qwen-plus 生成回答。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;

    public ChatService(RestTemplate restTemplate, BailianConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * RAG 问答：检索上下文 + 用户问题 → LLM 生成回答
     *
     * @param query          用户问题
     * @param contextChunks  检索召回的参考片段（内容文本）
     * @return LLM 原始响应 Map：{answer, model, usage: {prompt_tokens, completion_tokens, total_tokens}}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(String query, List<String> contextChunks) {
        // 1. 构建 RAG Prompt
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < contextChunks.size(); i++) {
            contextBuilder.append("【参考资料 ").append(i + 1).append("】\n")
                    .append(contextChunks.get(i)).append("\n\n");
        }

        String systemPrompt = """
                你是一个基于知识库的智能问答助手。请严格根据以下参考资料回答用户问题。
                
                规则：
                1. 仅使用【参考资料】中的信息作答，不要编造。
                2. 如果参考资料不足以回答，请如实告知"参考资料中未包含相关信息"。
                3. 回答简洁准确，必要时标注引用序号。
                4. 使用中文回答。
                
                """ + contextBuilder;

        // 2. 构建 OpenAI 兼容请求
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", query));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getChatModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 2048);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        String url = config.getBaseUrl() + "/chat/completions";

        // 3. 调用 LLM
        try {
            log.info("调用百炼 Chat API，model={}，query='{}'",
                    config.getChatModel(),
                    query.length() > 100 ? query.substring(0, 100) + "..." : query);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null) {
                throw new RuntimeException("Chat API 返回为空");
            }

            // 4. 解析响应
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Chat API 无 choices");
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String answer = (String) message.get("content");
            String model = (String) body.get("model");

            Map<String, Object> usage = (Map<String, Object>) body.get("usage");
            int promptTokens = usage != null ? ((Number) usage.get("prompt_tokens")).intValue() : 0;
            int completionTokens = usage != null ? ((Number) usage.get("completion_tokens")).intValue() : 0;
            int totalTokens = usage != null ? ((Number) usage.get("total_tokens")).intValue() : 0;

            log.info("Chat API 完成，model={} tokens(p={} c={} t={})",
                    model, promptTokens, completionTokens, totalTokens);

            Map<String, Object> result = new HashMap<>();
            result.put("answer", answer);
            result.put("model", model);
            result.put("usage", Map.of(
                    "promptTokens", promptTokens,
                    "completionTokens", completionTokens,
                    "totalTokens", totalTokens
            ));
            return result;

        } catch (Exception e) {
            log.error("Chat API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
