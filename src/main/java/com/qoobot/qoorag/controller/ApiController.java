package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.QaTrace;
import com.qoobot.qoorag.repository.QaTraceRepository;
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

    public ApiController(QaTraceRepository qaTraceRepository,
                         RetrieveService retrieveService) {
        this.qaTraceRepository = qaTraceRepository;
        this.retrieveService = retrieveService;
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

    /** 问答（骨架：返回占位结构，真实实现接入 LLM） */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        Integer topK = body.get("topK") == null ? 5 : (Integer) body.get("topK");
        // TODO: 检索 -> 拼 Prompt -> 调 LLM -> 返回答案
        String answer = "[骨架占位] 知识库检索与生成能力待接入（kbId=" + SecurityContext.get().getKbId() + "）";

        // 问答留痕（4.8，独立于业务数据）
        QaTrace trace = new QaTrace();
        trace.setKbId(SecurityContext.get().getKbId());
        trace.setTenantId(SecurityContext.get().getTenantId());
        trace.setQuestion(query);
        trace.setAnswer(answer);
        trace.setModel("pending");
        trace.setParams("topK=" + topK);
        trace.setCreatedAt(LocalDateTime.now());
        qaTraceRepository.save(trace);

        return Result.ok(Map.of("answer", answer, "sources", List.of()));
    }
}
