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
    private final RerankService rerankService;
    private final ContextAssembler contextAssembler;
    private final MetricsConfig.RagMetrics ragMetrics;
    private final AuditService auditService;

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

    /** 问答：检索 → 拼 Prompt → 调 LLM → 返回 answer + sources + usage，并留痕 */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, Object> body) {
        SessionInfo ctx = SecurityContext.get();
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            return Result.fail(400, "query 不能为空");
        }
        Integer topK = body.get("topK") == null ? defaultTopK : ((Number) body.get("topK")).intValue();
        if (topK < 1 || topK > 100) {
            topK = Math.max(1, Math.min(topK, 100));
        }

        long start = System.currentTimeMillis();
        try {
            // 1. 检索召回（宽召回：hybrid + 大候选池，保证高 Recall）
            //    精排开启时，拉取比最终需求更宽的候选（至少 rerank.top-n 的若干倍），
            //    供 cross-encoder 精排后再截断到 rerank.top-n，确保精排有候选可排（否则会因候选数<=top-n 空转）
            int retrieveTopK = rerankService.isEnabled()
                    ? Math.max(topK, rerankService.getTopN() * 4)
                    : topK;
            List<RetrieveChunk> chunks = retrieveService.retrieve(query, retrieveTopK, ctx.getKbId(), ctx.getTenantId());

            // 1.5. 精排（方案 2）：开启时对召回候选做 cross-encoder 重排并截断到 rerank.top-n，
            //      仅取高相关片段喂大模型，压低噪声与 token 成本；失败则优雅降级为原始候选
            chunks = rerankService.rerank(query, chunks);

            // 2. 上下文组装（RAG-041）：去重 → token 预算裁剪 → Lost-in-the-Middle 重排 → 来源标注
            ContextAssembler.AssembledContext assembled = contextAssembler.assemble(chunks);

            // 3. 调 LLM 生成回答（Prompt 模板外置，RAG-040）
            Map<String, Object> llmResult = chatService.chat(query, assembled.promptContext(), kbIdOf(ctx));
            String answer = (String) llmResult.get("answer");
            String model = (String) llmResult.get("model");
            @SuppressWarnings("unchecked")
            Map<String, Object> usageMap = (Map<String, Object>) llmResult.get("usage");

            // 4. 构建响应（sources 用预算裁剪后实际使用的片段，保证引用与答案一致）
            List<RetrieveChunk> usedChunks = assembled.used();
            ChatResponse chatResponse = ChatResponse.of(
                    answer, model,
                    (int) usageMap.get("promptTokens"),
                    (int) usageMap.get("completionTokens"),
                    usedChunks
            );

            // 5. 问答留痕（4.8，独立于业务数据）
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
