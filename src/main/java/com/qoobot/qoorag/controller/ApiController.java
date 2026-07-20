package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.dto.ChatResponse;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.QaTrace;
import com.qoobot.qoorag.repository.QaTraceRepository;
import com.qoobot.qoorag.service.ChatService;
import com.qoobot.qoorag.service.RetrieveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 对外检索 / 问答 API（API Key 鉴权，4.10） */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final QaTraceRepository qaTraceRepository;
    private final RetrieveService retrieveService;
    private final ChatService chatService;

    public ApiController(QaTraceRepository qaTraceRepository,
                         RetrieveService retrieveService,
                         ChatService chatService) {
        this.qaTraceRepository = qaTraceRepository;
        this.retrieveService = retrieveService;
        this.chatService = chatService;
    }

    /** 检索：查询向量化 → pgvector 余弦相似度 top-K 召回（受 kbId/tenantId 约束 + 权限校验） */
    @PostMapping("/retrieve")
    public Result retrieve(@RequestBody Map<String, Object> body) {
        SessionInfo ctx = SecurityContext.get();
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.fail(400, "query 不能为空");
        }
        Integer topK = body.get("topK") == null ? 5 : ((Number) body.get("topK")).intValue();
        if (topK < 1 || topK > 100) {
            topK = Math.max(1, Math.min(topK, 100)); // 夹逼到 1~100
        }

        try {
            List<RetrieveChunk> chunks = retrieveService.retrieve(query, topK, ctx.getKbId(), ctx.getTenantId());
            return Result.ok(Map.of(
                    "query", query,
                    "topK", topK,
                    "chunks", chunks
            ));
        } catch (Exception e) {
            log.error("检索失败: {}", e.getMessage(), e);
            return Result.fail(500, "检索失败: " + e.getMessage());
        }
    }

    /** 问答：检索 → 拼 Prompt → 调 LLM → 返回 answer + sources + usage，并留痕 */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, Object> body) {
        SessionInfo ctx = SecurityContext.get();
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.fail(400, "query 不能为空");
        }
        Integer topK = body.get("topK") == null ? 5 : ((Number) body.get("topK")).intValue();
        if (topK < 1 || topK > 100) {
            topK = Math.max(1, Math.min(topK, 100));
        }

        try {
            // 1. 检索召回
            List<RetrieveChunk> chunks = retrieveService.retrieve(query, topK, ctx.getKbId(), ctx.getTenantId());

            // 2. 提取 chunk 文本作为上下文
            List<String> contextChunks = chunks.stream()
                    .map(RetrieveChunk::getContent)
                    .toList();

            // 3. 调 LLM 生成回答
            Map<String, Object> llmResult = chatService.chat(query, contextChunks);
            String answer = (String) llmResult.get("answer");
            String model = (String) llmResult.get("model");
            @SuppressWarnings("unchecked")
            Map<String, Object> usageMap = (Map<String, Object>) llmResult.get("usage");

            // 4. 构建响应
            ChatResponse chatResponse = ChatResponse.of(
                    answer, model,
                    (int) usageMap.get("promptTokens"),
                    (int) usageMap.get("completionTokens"),
                    chunks
            );

            // 5. 问答留痕（4.8，独立于业务数据）
            QaTrace trace = new QaTrace();
            trace.setKbId(ctx.getKbId());
            trace.setTenantId(ctx.getTenantId());
            trace.setQuestion(query);
            trace.setAnswer(answer);
            trace.setModel(model);
            trace.setParams("topK=" + topK);
            trace.setSources(String.valueOf(chunks.size()));
            trace.setCreatedAt(LocalDateTime.now());
            qaTraceRepository.save(trace);

            return Result.ok(chatResponse);
        } catch (Exception e) {
            log.error("问答失败: {}", e.getMessage(), e);
            return Result.fail(500, "问答失败: " + e.getMessage());
        }
    }
}
