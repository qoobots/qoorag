package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

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

    public ChatService(RestTemplate restTemplate,
                       BailianConfig config,
                       ContentSafetyService contentSafety,
                       PromptTemplateService promptTemplateService) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.contentSafety = contentSafety;
        this.promptTemplateService = promptTemplateService;
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
            return result;

        } catch (Exception e) {
            log.error("Chat API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }
}
