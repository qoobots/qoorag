package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.ContextualChunkService;
import com.qoobot.qoorag.service.ContextualRebuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contextual Chunking 管理（RAG-023）。
 * <p>
 * Contextual Chunking 改变 embedding 文本（前缀 + 原文），因此存量数据必须重建才生效。
 * 本控制器暴露开关状态查询与重建触发。
 */
@RestController
@RequestMapping("/api/admin/contextual")
public class ContextualRebuildController {

    private static final Logger log = LoggerFactory.getLogger(ContextualRebuildController.class);

    private final ContextualChunkService contextualChunkService;
    private final ContextualRebuildService rebuildService;
    private final AuditService auditService;

    public ContextualRebuildController(ContextualChunkService contextualChunkService,
                                       ContextualRebuildService rebuildService,
                                       AuditService auditService) {
        this.contextualChunkService = contextualChunkService;
        this.rebuildService = rebuildService;
        this.auditService = auditService;
    }

    /** 查询开关状态与成本提示 */
    @GetMapping("/status")
    public Result status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", contextualChunkService.isEnabled());
        out.put("costNote", contextualChunkService.isEnabled()
                ? "已开启：每个新入库 chunk 会多一次 LLM 调用生成上下文前缀（仅影响离线入库，不增加在线延迟）"
                : "未开启：qoorag.ingest.contextual.enabled=false。开启后新入库 chunk 自动带前缀；"
                        + "存量数据需调用 rebuild 才会生效");
        return Result.ok(out);
    }

    /**
     * 触发某 KB 的存量重建（异步）。
     * <p>重建内容：为每个 chunk 生成上下文前缀 + 重新向量化。
     */
    @PostMapping("/rebuild")
    public Result rebuild(@RequestBody Map<String, Object> body) {
        Object kbObj = body.get("kbId");
        if (kbObj == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "kbId 必填");
        }
        Long kbId = ((Number) kbObj).longValue();
        if (!contextualChunkService.isEnabled()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "Contextual Chunking 未开启（qoorag.ingest.contextual.enabled=false），无需重建");
        }
        rebuildService.rebuildAsync(kbId);
        Long userId = SecurityContext.get() == null ? null : SecurityContext.get().getUserId();
        Long tenant = SecurityContext.get() == null ? null : SecurityContext.get().getTenantId();
        auditService.log("CONTEXTUAL_REBUILD", "KnowledgeBase", String.valueOf(kbId), null,
                "触发 Contextual Chunking 存量重建", tenant, userId);
        log.info("已触发 Contextual 重建：kbId={}", kbId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kbId", kbId);
        out.put("status", "STARTED");
        out.put("message", "重建已在后台启动，进度见应用日志；重建期间检索仍可用（旧向量生效）");
        return Result.ok(out);
    }
}
