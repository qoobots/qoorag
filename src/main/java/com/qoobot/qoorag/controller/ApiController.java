package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.config.MetricsConfig;
import com.qoobot.qoorag.dto.ChatResponse;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.QaTrace;
import com.qoobot.qoorag.repository.QaTraceRepository;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.ChatService;
import com.qoobot.qoorag.service.ContextAssembler;
import com.qoobot.qoorag.service.RerankService;
import com.qoobot.qoorag.service.RetrieveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 对外检索 / 问答 API（API Key 鉴权，4.10） */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final QaTraceRepository qaTraceRepository;
    private final RetrieveService retrieveService;
    private final ChatService chatService;
    private final RerankService rerankService;
    private final ContextAssembler contextAssembler;
    private final MetricsConfig.RagMetrics ragMetrics;
    private final AuditService auditService;

    /** RAG-043 流式问答专用线程池（守护线程，避免阻塞请求线程；CallerRuns 兜底防丢任务） */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            r -> { Thread t = new Thread(r, "qoorag-sse"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 生产检索/问答默认返回条数（application.yml: qoorag.retrieval.top-k） */
    @Value("${qoorag.retrieval.top-k:5}")
    private int defaultTopK;

    public ApiController(QaTraceRepository qaTraceRepository,
                         RetrieveService retrieveService,
                         ChatService chatService,
                         RerankService rerankService,
                         ContextAssembler contextAssembler,
                         MetricsConfig.RagMetrics ragMetrics,
                         AuditService auditService) {
        this.qaTraceRepository = qaTraceRepository;
        this.retrieveService = retrieveService;
        this.chatService = chatService;
        this.rerankService = rerankService;
        this.contextAssembler = contextAssembler;
        this.ragMetrics = ragMetrics;
        this.auditService = auditService;
    }

    /** 检索：查询向量化 → pgvector 余弦相似度 top-K 召回（受 kbId/tenantId 约束 + 权限校验） */
    @PostMapping("/retrieve")
    public Result retrieve(@RequestBody Map<String, Object> body) {
        SessionInfo ctx = SecurityContext.get();
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.fail(400, "query 不能为空");
        }
        Integer topK = body.get("topK") == null ? defaultTopK : ((Number) body.get("topK")).intValue();
        if (topK < 1 || topK > 100) {
            topK = Math.max(1, Math.min(topK, 100)); // 夹逼到 1~100
        }

        long start = System.currentTimeMillis();
        try {
            List<RetrieveChunk> chunks = retrieveService.retrieve(query, topK, ctx.getKbId(), ctx.getTenantId());
            ragMetrics.recordRetrieve(System.currentTimeMillis() - start, !chunks.isEmpty());
            auditService.log("RETRIEVE", "Query", String.valueOf(ctx.getKbId()), null,
                    "topK=" + topK + ",hits=" + chunks.size(), ctx.getTenantId(), actorOf(ctx));
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

    /**
     * 问答：检索 → 拼 Prompt → 调 LLM → 返回 answer + sources + usage，并留痕。
     * RAG-043：body 带 {@code stream:true} 时改为 SSE 流式返回（先出 reasoning/token，结束事件带 sources + usage）。
     */
    @PostMapping("/chat")
    public Object chat(@RequestBody Map<String, Object> body) {
        SessionInfo ctx = SecurityContext.get();
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.fail(400, "query 不能为空");
        }
        int rawTopK = body.get("topK") == null ? defaultTopK : ((Number) body.get("topK")).intValue();
        final int topK = Math.max(1, Math.min(rawTopK, 100));
        boolean stream = Boolean.TRUE.equals(body.get("stream"));

        // 先在请求线程抓取 ThreadLocal 安全上下文；流式路径将在后台线程执行，无法再读 ThreadLocal
        final Long reqKbId = ctx == null ? null : ctx.getKbId();
        final Long reqTenantId = ctx == null ? null : ctx.getTenantId();
        final Long reqActor = actorOf(ctx);

        if (!stream) {
            return chatSync(query, topK, ctx);
        }

        // ===== RAG-043 流式路径 =====
        SseEmitter emitter = new SseEmitter(180_000L);
        streamExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                int retrieveTopK = rerankService.isEnabled()
                        ? Math.max(topK, rerankService.getTopN() * 4) : topK;
                List<RetrieveChunk> chunks = retrieveService.retrieve(query, retrieveTopK, reqKbId, reqTenantId);
                chunks = rerankService.rerank(query, chunks);
                ContextAssembler.AssembledContext assembled = contextAssembler.assemble(chunks);
                List<RetrieveChunk> usedChunks = assembled.used();

                StringBuilder answerBuf = new StringBuilder();
                ChatService.StreamChatResult sr = chatService.streamChat(
                        query, assembled.promptContext(), reqKbId,
                        token -> { answerBuf.append(token); sendSse(emitter, "token", "token", token); },
                        reasoning -> sendSse(emitter, "reasoning", "reasoning", reasoning));

                // 结束事件：sources + citations（句子级引用归因）+ usage + 完整 answer
                ChatResponse chatResp = ChatResponse.of(answerBuf.toString(), sr.getModel(), 0, 0, usedChunks);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("model", sr.getModel());
                done.put("answer", answerBuf.toString());
                done.put("usage", Map.of("promptTokens", sr.getPromptTokens(),
                        "completionTokens", sr.getCompletionTokens(),
                        "totalTokens", sr.getTotalTokens()));
                done.put("sources", chatResp.getSources());
                done.put("citations", chatResp.getCitations());
                emitter.send(SseEmitter.event().name("done").data(done));
                emitter.complete();

                // 留痕（与同步路径一致）
                QaTrace trace = new QaTrace();
                trace.setKbId(reqKbId);
                trace.setTenantId(reqTenantId);
                trace.setQuestion(query);
                trace.setAnswer(answerBuf.toString());
                trace.setModel(sr.getModel());
                trace.setParams("topK=" + topK
                        + ",promptSource=stream"
                        + ",contextTokens=" + assembled.totalTokens()
                        + ",droppedDup=" + assembled.droppedByDup()
                        + ",droppedBudget=" + assembled.droppedByBudget());
                trace.setSources(String.valueOf(usedChunks.size()));
                trace.setTraceJson(String.format(
                        "{\"query\":\"%s\",\"topK\":%d,\"retrieved\":%d,\"contextUsed\":%d,\"contextTokens\":%d,\"promptSource\":\"stream\",\"totalTokens\":%d}",
                        escape(query), topK, chunks.size(), usedChunks.size(),
                        assembled.totalTokens(), sr.getTotalTokens()));
                trace.setCreatedAt(LocalDateTime.now());
                qaTraceRepository.save(trace);

                auditService.log("CHAT", "Query", String.valueOf(reqKbId), null,
                        "topK=" + topK + ",sources=" + usedChunks.size() + ",stream=true",
                        reqTenantId, reqActor);
                ragMetrics.recordChat(System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("流式问答失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("error", e.getMessage())));
                } catch (IOException ignored) { }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 同步问答（非流式），行为与原实现完全一致，供回归保证 */
    private Result chatSync(String query, int topK, SessionInfo ctx) {
        long start = System.currentTimeMillis();
        try {
            // 1. 检索召回（宽召回：hybrid + 大候选池，保证高 Recall）
            int retrieveTopK = rerankService.isEnabled()
                    ? Math.max(topK, rerankService.getTopN() * 4)
                    : topK;
            List<RetrieveChunk> chunks = retrieveService.retrieve(query, retrieveTopK, ctx.getKbId(), ctx.getTenantId());

            // 1.5. 精排
            chunks = rerankService.rerank(query, chunks);

            // 2. 上下文组装（RAG-041）
            ContextAssembler.AssembledContext assembled = contextAssembler.assemble(chunks);

            // 3. 调 LLM 生成回答（Prompt 模板外置，RAG-040）
            Map<String, Object> llmResult = chatService.chat(query, assembled.promptContext(), kbIdOf(ctx));
            String answer = (String) llmResult.get("answer");
            String model = (String) llmResult.get("model");
            @SuppressWarnings("unchecked")
            Map<String, Object> usageMap = (Map<String, Object>) llmResult.get("usage");

            // 4. 构建响应
            List<RetrieveChunk> usedChunks = assembled.used();
            ChatResponse chatResponse = ChatResponse.of(
                    answer, model,
                    (int) usageMap.get("promptTokens"),
                    (int) usageMap.get("completionTokens"),
                    usedChunks
            );

            // 5. 问答留痕
            QaTrace trace = new QaTrace();
            trace.setKbId(ctx.getKbId());
            trace.setTenantId(ctx.getTenantId());
            trace.setQuestion(query);
            trace.setAnswer(answer);
            trace.setModel(model);
            trace.setParams("topK=" + topK
                    + ",promptSource=" + llmResult.get("promptSource")
                    + ",contextTokens=" + assembled.totalTokens()
                    + ",droppedDup=" + assembled.droppedByDup()
                    + ",droppedBudget=" + assembled.droppedByBudget());
            trace.setSources(String.valueOf(usedChunks.size()));
            trace.setTraceJson(buildTraceJson(query, topK, chunks, assembled, llmResult));
            trace.setCreatedAt(LocalDateTime.now());
            qaTraceRepository.save(trace);

            auditService.log("CHAT", "Query", String.valueOf(ctx.getKbId()), null,
                    "topK=" + topK + ",sources=" + usedChunks.size(), ctx.getTenantId(), actorOf(ctx));
            ragMetrics.recordChat(System.currentTimeMillis() - start);
            return Result.ok(chatResponse);
        } catch (Exception e) {
            log.error("问答失败: {}", e.getMessage(), e);
            return Result.fail(500, "问答失败: " + e.getMessage());
        }
    }

    /** SSE 辅助：以 JSON 对象帧推送（key→value），避免裸字符串中的换行破坏 SSE 帧格式 */
    private static void sendSse(SseEmitter emitter, String name, String key, String value) {
        try {
            emitter.send(SseEmitter.event().name(name).data(Collections.singletonMap(key, value)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** KB ID：供 Prompt 模板按 KB 选择（API Key 调用时来自 Key 绑定） */
    private static Long kbIdOf(SessionInfo ctx) {
        return ctx == null ? null : ctx.getKbId();
    }

    /** 组装单次问答的全链路 trace（RAG-006 雏形，便于线上 badcase 归因） */
    private static String buildTraceJson(String query, int topK, List<RetrieveChunk> retrieved,
                                        ContextAssembler.AssembledContext assembled,
                                        Map<String, Object> llmResult) {
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"query\":\"").append(escape(query)).append('"')
              .append(",\"topK\":").append(topK)
              .append(",\"retrieved\":").append(retrieved.size())
              .append(",\"contextUsed\":").append(assembled.used().size())
              .append(",\"contextTokens\":").append(assembled.totalTokens())
              .append(",\"droppedByDup\":").append(assembled.droppedByDup())
              .append(",\"droppedByBudget\":").append(assembled.droppedByBudget())
              .append(",\"promptSource\":\"").append(escape(String.valueOf(llmResult.get("promptSource")))).append('"')
              .append(",\"promptVersion\":").append(llmResult.get("promptVersion") == null ? "0" : llmResult.get("promptVersion"))
              .append(",\"totalTokens\":").append(((Map<?, ?>) llmResult.get("usage")).get("totalTokens"))
              .append('}');
            return sb.toString();
        } catch (Exception e) {
            return null; // trace 仅为诊断用，不得影响主链路
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    /** 审计操作人：会话调用取 userId，API Key 调用取 apiKeyId */
    private Long actorOf(SessionInfo ctx) {
        return ctx.isApiKey ? ctx.apiKeyId : ctx.userId;
    }
}
