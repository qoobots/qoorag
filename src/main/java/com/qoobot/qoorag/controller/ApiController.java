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
import com.qoobot.qoorag.service.ChatHistoryService;
import com.qoobot.qoorag.service.ChatService;
import com.qoobot.qoorag.service.MmrService;
import com.qoobot.qoorag.service.QueryTransformService;
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
    private final ChatHistoryService historyService;
    private final MmrService mmrService;
    private final QueryTransformService queryTransformService;

    /** RAG-043 流式问答专用线程池（守护线程，避免阻塞请求线程；CallerRuns 兜底防丢任务） */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            r -> { Thread t = new Thread(r, "qoorag-sse"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 生产检索/问答默认返回条数（application.yml: qoorag.retrieval.top-k） */
    @Value("${qoorag.retrieval.top-k:5}")
    private int defaultTopK;

    /** RAG-046：检索 top 分数低于此值视为低置信，走拒答分支（省 LLM 调用） */
    @Value("${qoorag.chat.low-confidence-score:0.3}")
    private double lowConfidenceScore;
    /** RAG-046：置信度分级阈值（低于为 medium，否则 high） */
    @Value("${qoorag.chat.medium-confidence-score:0.55}")
    private double mediumConfidenceScore;
    /** RAG-015：检索前是否开启查询改写（默认关，避免未评估回归） */
    @Value("${qoorag.retrieval.query-rewrite-enabled:false}")
    private boolean queryRewriteEnabled;

    public ApiController(QaTraceRepository qaTraceRepository,
                         RetrieveService retrieveService,
                         ChatService chatService,
                         RerankService rerankService,
                         ContextAssembler contextAssembler,
                         MetricsConfig.RagMetrics ragMetrics,
                         AuditService auditService,
                         ChatHistoryService historyService,
                         MmrService mmrService,
                         QueryTransformService queryTransformService) {
        this.qaTraceRepository = qaTraceRepository;
        this.retrieveService = retrieveService;
        this.chatService = chatService;
        this.rerankService = rerankService;
        this.contextAssembler = contextAssembler;
        this.ragMetrics = ragMetrics;
        this.auditService = auditService;
        this.historyService = historyService;
        this.mmrService = mmrService;
        this.queryTransformService = queryTransformService;
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

        // RAG-044：多轮会话上下文 — 在请求线程解析历史与指代消解（historyService 不依赖 ThreadLocal）
        final String reqSessionId = (body.get("sessionId") instanceof String s && !s.isBlank()) ? s : null;
        final List<ChatHistoryService.Turn> history =
                reqSessionId != null ? historyService.getHistory(reqSessionId) : List.of();
        final String reqHistoryText = history.isEmpty() ? "" : formatHistory(history);
        // 检索用 query：多轮→指代消解（RAG-044）；单轮且开启改写→查询改写（RAG-015）；否则原 query
        String effectiveQuery = query;
        if (reqSessionId != null && !history.isEmpty()) {
            effectiveQuery = chatService.resolveQuery(query, reqHistoryText);
        } else if (queryRewriteEnabled) {
            effectiveQuery = queryTransformService.rewrite(query);
            if (!effectiveQuery.equals(query)) {
                log.info("RAG-015 查询改写：'{}' -> '{}'", query, effectiveQuery);
            }
        }
        final String reqEffectiveQuery = effectiveQuery;

        if (!stream) {
            return chatSync(query, topK, ctx, reqEffectiveQuery, reqHistoryText, reqSessionId);
        }

        // ===== RAG-043 流式路径 =====
        SseEmitter emitter = new SseEmitter(180_000L);
        streamExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                int retrieveTopK = rerankService.isEnabled()
                        ? Math.max(topK, rerankService.getTopN() * 4) : topK;
                List<RetrieveChunk> chunks = retrieveService.retrieve(reqEffectiveQuery, retrieveTopK, reqKbId, reqTenantId);
                chunks = rerankService.rerank(reqEffectiveQuery, chunks);
                chunks = mmrService.mmr(reqEffectiveQuery, chunks); // RAG-018：多样性重排（精排后、组装前）
                ContextAssembler.AssembledContext assembled = contextAssembler.assemble(chunks);
                List<RetrieveChunk> usedChunks = assembled.used();

                // RAG-046：置信度分级；低置信（检索 top 分数过低）直接走拒答分支，省去 LLM 调用
                double topScore = usedChunks.stream().mapToDouble(RetrieveChunk::getScore).max().orElse(0.0);
                String confidence = confidenceLevel(topScore);

                String answer;
                String model;
                int pTokens = 0, cTokens = 0, tTokens = 0;
                if (topScore < lowConfidenceScore) {
                    answer = LOW_CONFIDENCE_ANSWER;
                    model = null;
                } else {
                    StringBuilder answerBuf = new StringBuilder();
                    ChatService.StreamChatResult sr = chatService.streamChat(
                            query, assembled.promptContext(), reqKbId, reqHistoryText,
                            token -> { answerBuf.append(token); sendSse(emitter, "token", "token", token); },
                            reasoning -> sendSse(emitter, "reasoning", "reasoning", reasoning));
                    answer = answerBuf.toString();
                    model = sr.getModel();
                    pTokens = sr.getPromptTokens();
                    cTokens = sr.getCompletionTokens();
                    tTokens = sr.getTotalTokens();
                }

                // 结束事件：sources + citations + confidence + usage + 完整 answer
                ChatResponse chatResp = ChatResponse.of(answer, model, pTokens, cTokens, usedChunks);
                chatResp.setConfidence(confidence);
                chatResp.setConfidenceScore(topScore);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("model", model);
                done.put("answer", answer);
                done.put("usage", Map.of("promptTokens", pTokens,
                        "completionTokens", cTokens,
                        "totalTokens", tTokens));
                done.put("sources", chatResp.getSources());
                done.put("citations", chatResp.getCitations());
                done.put("confidence", confidence);
                done.put("confidenceScore", topScore);
                emitter.send(SseEmitter.event().name("done").data(done));
                emitter.complete();
                // RAG-044：持久化本轮对话到会话历史（即使后续留痕失败也不影响多轮）
                if (reqSessionId != null) {
                    historyService.append(reqSessionId, query, answer);
                }

                // 留痕（与同步路径一致）
                QaTrace trace = new QaTrace();
                trace.setKbId(reqKbId);
                trace.setTenantId(reqTenantId);
                trace.setQuestion(query);
                trace.setAnswer(answer);
                trace.setModel(model);
                trace.setParams("topK=" + topK
                        + ",promptSource=stream"
                        + ",confidence=" + confidence
                        + ",contextTokens=" + assembled.totalTokens()
                        + ",droppedDup=" + assembled.droppedByDup()
                        + ",droppedBudget=" + assembled.droppedByBudget());
                trace.setSources(String.valueOf(usedChunks.size()));
                trace.setTraceJson(String.format(
                        "{\"query\":\"%s\",\"topK\":%d,\"retrieved\":%d,\"contextUsed\":%d,\"contextTokens\":%d,\"promptSource\":\"stream\",\"confidence\":\"%s\",\"totalTokens\":%d}",
                        escape(query), topK, chunks.size(), usedChunks.size(),
                        assembled.totalTokens(), confidence, tTokens));
                trace.setCreatedAt(LocalDateTime.now());
                qaTraceRepository.save(trace);

                auditService.log("CHAT", "Query", String.valueOf(reqKbId), null,
                        "topK=" + topK + ",sources=" + usedChunks.size() + ",stream=true,confidence=" + confidence,
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

    /** 同步问答（非流式），行为与原实现一致，并支持 RAG-044 多轮会话上下文 */
    private Result chatSync(String query, int topK, SessionInfo ctx,
                            String effectiveQuery, String historyText, String sessionId) {
        long start = System.currentTimeMillis();
        try {
            // 1. 检索召回（宽召回：hybrid + 大候选池，保证高 Recall）；多轮时用指代消解后的独立 query
            int retrieveTopK = rerankService.isEnabled()
                    ? Math.max(topK, rerankService.getTopN() * 4)
                    : topK;
            List<RetrieveChunk> chunks = retrieveService.retrieve(effectiveQuery, retrieveTopK, ctx.getKbId(), ctx.getTenantId());

            // 1.5. 精排
            chunks = rerankService.rerank(effectiveQuery, chunks);
            chunks = mmrService.mmr(effectiveQuery, chunks); // RAG-018：多样性重排（精排后、组装前）

            // 2. 上下文组装（RAG-041）
            ContextAssembler.AssembledContext assembled = contextAssembler.assemble(chunks);
            List<RetrieveChunk> usedChunks = assembled.used();

            // RAG-046：置信度分级；低置信（检索 top 分数过低）走拒答分支，不调用 LLM
            double topScore = usedChunks.stream().mapToDouble(RetrieveChunk::getScore).max().orElse(0.0);
            String confidence = confidenceLevel(topScore);

            String answer;
            String model;
            int pTokens = 0, cTokens = 0, tTokens = 0;
            Map<String, Object> llmResult = null;
            if (topScore < lowConfidenceScore) {
                answer = LOW_CONFIDENCE_ANSWER;
                model = null;
            } else {
                // 3. 调 LLM 生成回答（Prompt 模板外置，RAG-040）；historyText 注入多轮上下文
                llmResult = chatService.chat(query, assembled.promptContext(), kbIdOf(ctx), historyText);
                answer = (String) llmResult.get("answer");
                model = (String) llmResult.get("model");
                @SuppressWarnings("unchecked")
                Map<String, Object> usageMap = (Map<String, Object>) llmResult.get("usage");
                pTokens = (int) usageMap.get("promptTokens");
                cTokens = (int) usageMap.get("completionTokens");
                tTokens = (int) usageMap.get("totalTokens");
            }

            // 4. 构建响应
            ChatResponse chatResponse = ChatResponse.of(answer, model, pTokens, cTokens, usedChunks);
            chatResponse.setConfidence(confidence);
            chatResponse.setConfidenceScore(topScore);

            // RAG-044：持久化本轮对话到会话历史
            if (sessionId != null) {
                historyService.append(sessionId, query, answer);
            }

            // 5. 问答留痕
            QaTrace trace = new QaTrace();
            trace.setKbId(ctx.getKbId());
            trace.setTenantId(ctx.getTenantId());
            trace.setQuestion(query);
            trace.setAnswer(answer);
            trace.setModel(model);
            trace.setParams("topK=" + topK
                    + (llmResult != null ? ",promptSource=" + llmResult.get("promptSource") : ",promptSource=low-confidence")
                    + ",confidence=" + confidence
                    + ",contextTokens=" + assembled.totalTokens()
                    + ",droppedDup=" + assembled.droppedByDup()
                    + ",droppedBudget=" + assembled.droppedByBudget());
            trace.setSources(String.valueOf(usedChunks.size()));
            trace.setTraceJson(llmResult != null
                    ? buildTraceJson(query, topK, chunks, assembled, llmResult)
                    : String.format(
                        "{\"query\":\"%s\",\"topK\":%d,\"retrieved\":%d,\"contextUsed\":%d,\"contextTokens\":%d,\"promptSource\":\"low-confidence\",\"confidence\":\"%s\",\"totalTokens\":0}",
                        escape(query), topK, chunks.size(), usedChunks.size(), assembled.totalTokens(), confidence));
            trace.setCreatedAt(LocalDateTime.now());
            qaTraceRepository.save(trace);

            auditService.log("CHAT", "Query", String.valueOf(ctx.getKbId()), null,
                    "topK=" + topK + ",sources=" + usedChunks.size() + ",confidence=" + confidence, ctx.getTenantId(), actorOf(ctx));
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

    /** RAG-044：将历史轮次格式化为可读文本（供指代消解与 LLM 上下文注入） */
    private static String formatHistory(List<ChatHistoryService.Turn> history) {
        StringBuilder sb = new StringBuilder();
        for (ChatHistoryService.Turn t : history) {
            sb.append("user".equals(t.role()) ? "用户：" : "助手：").append(t.content()).append("\n");
        }
        return sb.toString().strip();
    }

    /** RAG-046：低置信（未检索到相关资料）时的优雅拒答文案 */
    private static final String LOW_CONFIDENCE_ANSWER =
            "抱歉，我在当前知识库中未检索到与您问题相关的资料，因此无法给出可靠回答。"
            + "您可以尝试：换一种表述、补充更具体的背景，或确认相关资料已成功入库。";

    /** RAG-046：基于检索 top 分数的置信度分级 */
    private String confidenceLevel(double topScore) {
        if (topScore < lowConfidenceScore) {
            return "low";
        }
        if (topScore < mediumConfidenceScore) {
            return "medium";
        }
        return "high";
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
