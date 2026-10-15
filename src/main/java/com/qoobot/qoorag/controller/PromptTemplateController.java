package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.PromptTemplate;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.PromptTemplateService;
import com.qoobot.qoorag.service.PromptTemplateService.ResolvedTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理（RAG-040）。
 * <p>
 * 模板外置到数据库后，运营侧可在管理界面直接调整 Prompt 并即时生效，无需发版。
 * 渲染优先级：KB 级启用模板 → 全局启用模板 → 内置默认模板。
 */
@RestController
@RequestMapping("/api/admin/prompt-templates")
public class PromptTemplateController {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateController.class);

    private final PromptTemplateService service;
    private final AuditService auditService;

    public PromptTemplateController(PromptTemplateService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /**
     * 查询模板列表。
     *
     * @param kbId  知识库 ID；不传则查全局模板
     * @param scene 场景，默认 chat
     */
    @GetMapping
    public Result list(@RequestParam(required = false) Long kbId,
                       @RequestParam(defaultValue = "chat") String scene) {
        List<PromptTemplate> templates = (kbId != null)
                ? service.list(kbId, scene) : service.listGlobal(scene);
        return Result.ok(templates);
    }

    /**
     * 查看当前生效的模板（渲染前原文）。
     * 便于运营确认「这个 KB 现在实际用的是哪套 Prompt」。
     */
    @GetMapping("/effective")
    public Result effective(@RequestParam(required = false) Long kbId,
                            @RequestParam(defaultValue = "chat") String scene) {
        ResolvedTemplate tpl = service.resolveRaw(kbId, scene);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", tpl.source());
        out.put("templateId", tpl.templateId());
        out.put("version", tpl.version());
        out.put("content", tpl.content());
        return Result.ok(out);
    }

    /**
     * 新增模板；{@code enable=true} 时自动停用同作用域其它模板（单启用约束）。
     */
    @PostMapping
    public Result create(@RequestBody Map<String, Object> body) {
        Long kbId = body.get("kbId") == null ? null : ((Number) body.get("kbId")).longValue();
        String scene = body.get("scene") == null ? "chat" : String.valueOf(body.get("scene"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        boolean enable = body.get("enable") != null
                && Boolean.parseBoolean(String.valueOf(body.get("enable")));

        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "content 不能为空");
        }
        if (!content.contains("{{context}}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模板必须包含 {{context}} 占位符");
        }

        PromptTemplate saved = service.save(kbId, scene, name, content, enable);
        Long actor = SecurityContext.get() != null ? SecurityContext.get().getUserId() : null;
        Long tenant = SecurityContext.get() != null ? SecurityContext.get().getTenantId() : null;
        auditService.log("CREATE_PROMPT_TEMPLATE", "PromptTemplate", String.valueOf(saved.getId()),
                null, "kbId=" + kbId + ",scene=" + scene + ",enable=" + enable + ",v=" + saved.getVersion(),
                tenant, actor);
        return Result.ok(saved);
    }

    /**
     * 启用指定模板（会停用同作用域其它模板）。
     */
    @PostMapping("/{id}/enable")
    public Result enable(@PathVariable Long id) {
        PromptTemplate tpl = service.enable(id);
        Long actor = SecurityContext.get() != null ? SecurityContext.get().getUserId() : null;
        Long tenant = SecurityContext.get() != null ? SecurityContext.get().getTenantId() : null;
        auditService.log("ENABLE_PROMPT_TEMPLATE", "PromptTemplate", String.valueOf(id),
                null, "kbId=" + tpl.getKbId() + ",scene=" + tpl.getScene(), tenant, actor);
        log.info("启用 Prompt 模板 id={} kbId={} scene={}", id, tpl.getKbId(), tpl.getScene());
        return Result.ok(tpl);
    }

    /**
     * 删除模板。
     */
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        service.delete(id);
        Long actor = SecurityContext.get() != null ? SecurityContext.get().getUserId() : null;
        Long tenant = SecurityContext.get() != null ? SecurityContext.get().getTenantId() : null;
        auditService.log("DELETE_PROMPT_TEMPLATE", "PromptTemplate", String.valueOf(id),
                null, null, tenant, actor);
        return Result.ok("已删除");
    }
}
