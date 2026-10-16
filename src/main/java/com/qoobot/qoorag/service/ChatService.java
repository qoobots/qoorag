package com.qoobot.qoorag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.config.MetricsConfig;
import com.qoobot.qoorag.entity.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * LLM 问答服务：调用百炼 Chat API（OpenAI 兼容模式）。
 * <p>
 * RAG-040：Prompt 模板由 {@link PromptTemplateService} 提供（KB 级 → 全局 → 内置默认），
 * 不再硬编码在 Java 里；默认模板与改造前文本完全一致，默认行为零变化。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;
    private final ContentSafetyService contentSafety;
    private final PromptTemplateService promptTemplateService;
    private final MetricsConfig.RagMetrics ragMetrics;
    private final UsageStatService usageStat;

    public ChatService(RestTemplate restTemplate,
                       BailianConfig config,
                       ContentSafetyService contentSafety,
                       PromptTemplateService promptTemplateService,
                       MetricsConfig.RagMetrics ragMetrics,
                       UsageStatService usageStat) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.contentSafety = contentSafety;
        this.promptTemplateService = promptTemplateService;
        this.ragMetrics = ragMetrics;
        this.usageStat = usageStat;
    }

    /**
     * RAG 问答：模板渲染 + 检索上下文 + 用户问题 → LLM 生成回答。
     *
     * @param query        用户问题
     * @param contextText  已组装好的参考资料文本（由 {@link ContextAssembler} 产出）
     * @param kbId         知识库 ID（用于选择 Prompt 模板；可为 null）
     * @return LLM 响应 Map：{@code answer, model, usage:{...}, promptSource, promptTemplateId, promptVersion}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(String query, String contextText, Long kbId) {
        return chat(query, contextText, kbId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(String query, String contextText, Long kbId, String historyText) {
        // 0. 内容安全检测（#18；关闭时 fail-open 放行）
        ContentSafetyService.Result safety = contentSafety.checkText(query);
        if (safety.verdict == ContentSafetyService.Verdict.BLOCKED) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED, "提问内容命中内容安全拦截：" + safety.reason);
        }

        // 1. 渲染 Prompt（KB 级模板 → 全局模板 → 内置默认）
        PromptTemplateService.ResolvedTemplate tpl =
                promptTemplateService.resolveAndRender(kbId, PromptTemplate.SCENE_CHAT, contextText, query);

        // 2. 构建 OpenAI 兼容请求
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", tpl.content()));
        messages.add(Map.of("role", "user", "content", buildUserContent(query, historyText)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getChatModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 2048);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getChatApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        String url = config.getChatBaseUrl() + "/chat/completions";

        // 3. 调用 LLM
        long llmStart = System.currentTimeMillis();
        try {
            log.info("调用百炼 Chat API，model={}，prompt 来源={}（模板 id={} v={}），query='{}'",
                    config.getChatModel(), tpl.source(), tpl.templateId(), tpl.version(),
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
            // RAG-040：模板溯源，便于对比不同模板的效果
            result.put("promptSource", tpl.source());
            result.put("promptTemplateId", tpl.templateId());
            result.put("promptVersion", tpl.version());
            ragMetrics.recordLlm(System.currentTimeMillis() - llmStart, true);
            // RAG-053：LLM 用量按 KB 归因（kbId 为 null 时记为租户级）
            recordLlmUsage(promptTokens, completionTokens, kbId);
            return result;

        } catch (Exception e) {
            ragMetrics.recordLlm(System.currentTimeMillis() - llmStart, false);
            log.error("Chat API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    // ===== RAG-043：流式问答 =====
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * RAG-043：流式问答。与 {@link #chat(String, String, Long)} 行为一致（内容安全 + Prompt 模板 + 检索上下文），
     * 但 LLM 以 SSE 方式逐 token 返回：每产生一个增量就回调 {@code onToken}；
     * 推理模型的 {@code reasoning_content} 通过 {@code onReasoning} 回调（可为 null 表示忽略）。
     *
     * @return 流式结束后的元信息（模型与用量；部分网关未回传 usage 时量为 0）
     */
    @SuppressWarnings("unchecked")
    public StreamChatResult streamChat(String query, String contextText, Long kbId,
                                       Consumer<String> onToken, Consumer<String> onReasoning) {
        return streamChat(query, contextText, kbId, null, onToken, onReasoning);
    }

    @SuppressWarnings("unchecked")
    public StreamChatResult streamChat(String query, String contextText, Long kbId, String historyText,
                                       Consumer<String> onToken, Consumer<String> onReasoning) {
        ContentSafetyService.Result safety = contentSafety.checkText(query);
        if (safety.verdict == ContentSafetyService.Verdict.BLOCKED) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED, "提问内容命中内容安全拦截：" + safety.reason);
        }

        PromptTemplateService.ResolvedTemplate tpl =
                promptTemplateService.resolveAndRender(kbId, PromptTemplate.SCENE_CHAT, contextText, query);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", tpl.content()));
        messages.add(Map.of("role", "user", "content", buildUserContent(query, historyText)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getChatModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 2048);
        requestBody.put("stream", true);
        requestBody.put("stream_options", Map.of("include_usage", true));

        String url = config.getChatBaseUrl() + "/chat/completions";
        String apiKey = config.getChatApiKey();

        HttpRequest request;
        try {
            String json = MAPPER.writeValueAsString(requestBody);
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("构造流式请求失败: " + e.getMessage(), e);
        }

        String model = config.getChatModel();
        int promptTokens = 0, completionTokens = 0, totalTokens = 0;

        long llmStart = System.currentTimeMillis();
        try {
            HttpResponse<InputStream> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("Chat 流式接口 HTTP " + response.statusCode() + ": " + errBody);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue; // 忽略注释行（: ping）与空行
                    }
                    String payload = line.substring(5).strip();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(payload);
                        JsonNode choices = node.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).get("delta");
                            if (delta != null) {
                                JsonNode content = delta.get("content");
                                if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                                    onToken.accept(content.asText());
                                }
                                if (onReasoning != null) {
                                    JsonNode reasoning = delta.get("reasoning_content");
                                    if (reasoning != null && !reasoning.isNull() && !reasoning.asText().isEmpty()) {
                                        onReasoning.accept(reasoning.asText());
                                    }
                                }
                            }
                        }
                        JsonNode usageNode = node.get("usage");
                        if (usageNode != null && !usageNode.isNull()) {
                            if (usageNode.has("prompt_tokens")) {
                                promptTokens = usageNode.get("prompt_tokens").asInt();
                            }
                            if (usageNode.has("completion_tokens")) {
                                completionTokens = usageNode.get("completion_tokens").asInt();
                            }
                            if (usageNode.has("total_tokens")) {
                                totalTokens = usageNode.get("total_tokens").asInt();
                            }
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        log.warn("跳过无法解析的 SSE 数据帧: {}", payload);
                    }
                }
            }
        } catch (IOException e) {
            ragMetrics.recordLlm(System.currentTimeMillis() - llmStart, false);
            throw new RuntimeException("读取流式响应失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            ragMetrics.recordLlm(System.currentTimeMillis() - llmStart, false);
            Thread.currentThread().interrupt();
            throw new RuntimeException("流式响应被中断", e);
        }

        log.info("Chat 流式完成，model={} tokens(p={} c={} t={})", model, promptTokens, completionTokens, totalTokens);
        ragMetrics.recordLlm(System.currentTimeMillis() - llmStart, true);
        // RAG-053：流式 LLM 用量按 KB 归因（kbId 为 null 时记为租户级）
        recordLlmUsage(promptTokens, completionTokens, kbId);
        return new StreamChatResult(model, promptTokens, completionTokens, totalTokens);
    }

    // ===== RAG-044：多轮对话与指代消解 =====

    /** 指代消解系统提示：将追问改写为独立、自包含的检索查询 */
    private static final String COREF_SYSTEM = "你是一个对话指代消解助手。下面是多轮对话历史与用户的最新提问。"
            + "请把最新提问改写成一个独立、自包含的检索查询，消解其中的代词与省略"
            + "（如“它”“这个”“前者”“上一步”“为什么”等需要结合历史才能理解的部分）。"
            + "只输出改写后的查询文本本身，不要解释，不要加任何引号或前缀。";

    /**
     * RAG-044：根据对话历史消解追问中的指代/省略，得到可独立检索的 query。
     * 历史为空或消解失败时回退为原 query（fail-soft，不阻塞主链路）。
     */
    public String resolveQuery(String query, String historyText) {
        if (historyText == null || historyText.isBlank()) {
            return query;
        }
        String rewritten = complete(COREF_SYSTEM, historyText + "\n\n最新提问：" + query);
        if (rewritten == null || rewritten.isBlank()) {
            return query;
        }
        return rewritten.trim();
    }

    /** 将历史拼接到用户问题前，使 LLM 拥有多轮上下文；无历史时原样返回 query */
    private String buildUserContent(String query, String historyText) {
        if (historyText == null || historyText.isBlank()) {
            return query;
        }
        return historyText + "\n\n当前问题：" + query;
    }

    /** 纯 LLM 补全（无检索），用于指代消解/查询改写等轻量调用；失败返回 null（fail-soft）。包级可见，供 QueryTransformService 复用 */
    String complete(String system, String user) {
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            messages.add(Map.of("role", "user", "content", user));
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getChatModel());
            body.put("messages", messages);
            body.put("temperature", 0.1);
            body.put("max_tokens", 512);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getChatApiKey());
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String url = config.getChatBaseUrl() + "/chat/completions";
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> b = resp.getBody();
            if (b == null) {
                return null;
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) b.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            // RAG-053：轻量 LLM 调用（指代消解/改写）也计入用量（租户级，kbId 为 null）
            int p = 0, c = 0;
            Object u = b.get("usage");
            if (u instanceof Map) {
                Map<?, ?> um = (Map<?, ?>) u;
                if (um.get("prompt_tokens") instanceof Number) {
                    p = ((Number) um.get("prompt_tokens")).intValue();
                }
                if (um.get("completion_tokens") instanceof Number) {
                    c = ((Number) um.get("completion_tokens")).intValue();
                }
            }
            recordLlmUsage(p, c, null);
            return (String) message.get("content");
        } catch (Exception e) {
            log.warn("指代消解 LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    // ===== RAG-053：LLM 用量埋点辅助 =====

    /** 统一记录 LLM token 用量到 Prometheus 计数与按 KB 的用量统计（kbId 为 null 时记租户级） */
    private void recordLlmUsage(int promptTokens, int completionTokens, Long kbId) {
        ragMetrics.recordLlmTokens(promptTokens, completionTokens);
        usageStat.recordLlm(TenantContext.get(), kbId, maskKey(config.getChatApiKey()), promptTokens, completionTokens);
    }

    /** 密钥指纹：仅保留角色 + 末 4 位，不落明文 */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "chat:none";
        }
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "chat:****" + tail;
    }

    /** RAG-043：流式问答结果（增量已通过回调送出，这里只回传结束时的元信息） */
    public static class StreamChatResult {
        private final String model;
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        public StreamChatResult(String model, int promptTokens, int completionTokens, int totalTokens) {
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public String getModel() { return model; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
    }
}
