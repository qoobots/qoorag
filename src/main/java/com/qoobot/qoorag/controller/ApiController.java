package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.QaTrace;
import com.qoobot.qoorag.repository.QaTraceRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 对外检索 / 问答 API（API Key 鉴权，4.10） */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final QaTraceRepository qaTraceRepository;

    public ApiController(QaTraceRepository qaTraceRepository) {
        this.qaTraceRepository = qaTraceRepository;
    }

    /** 检索（骨架：返回占位结构，真实实现接入向量库 + 权限校验） */
    @PostMapping("/retrieve")
    public Result retrieve(@RequestBody Map<String, Object> body) {
        SecurityContext.get(); // 已由拦截器写入 kbId / tenantId
        String query = (String) body.get("query");
        Integer topK = body.get("topK") == null ? 5 : (Integer) body.get("topK");
        // TODO: 调用 embedding + pgvector 相似检索（受 kbId / tenant_id 约束），并校验检索权限
        return Result.ok(Map.of(
                "query", query,
                "topK", topK,
                "chunks", List.of()   // 占位：实际返回召回片段
        ));
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
