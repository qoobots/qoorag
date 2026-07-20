package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.ApiKey;
import com.qoobot.qoorag.entity.KbPermission;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.service.ApiKeyService;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 知识库管理（知识管理员，4.2 / 4.10 / 4.11） */
@RestController
@RequestMapping("/api/kb")
public class RagAdminController {

    private final KnowledgeBaseService kbService;
    private final ApiKeyService apiKeyService;
    private final AuditService auditService;

    public RagAdminController(KnowledgeBaseService kbService, ApiKeyService apiKeyService, AuditService auditService) {
        this.kbService = kbService;
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
    }

    private void requireKbAdmin() {
        if (!SecurityContext.get().hasRole("知识管理员")) {
            throw new RuntimeException("无权限：需要知识管理员角色");
        }
    }

    @GetMapping
    public Result list() {
        requireKbAdmin();
        return Result.ok(kbService.list());
    }

    @PostMapping
    public Result create(@RequestBody Map<String, String> body) {
        requireKbAdmin();
        KnowledgeBase kb = kbService.create(body.get("name"), body.get("description"), body.get("dataClassification"));
        auditService.log("CREATE_KB", "KnowledgeBase", String.valueOf(kb.getId()), null, kb.getName());
        return Result.ok(kb);
    }

    @DeleteMapping("/{id}")
    public Result softDelete(@PathVariable Long id) {
        requireKbAdmin();
        kbService.softDelete(id);
        auditService.log("DELETE_KB", "KnowledgeBase", String.valueOf(id), null, "soft delete");
        return Result.ok();
    }

    @GetMapping("/{id}/permissions")
    public Result listPermissions(@PathVariable Long id) {
        requireKbAdmin();
        return Result.ok(kbService.listPermissions(id));
    }

    @PostMapping("/{id}/permissions")
    public Result addPermission(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requireKbAdmin();
        KbPermission p = kbService.addPermission(id, body.get("targetType"), body.get("targetId"));
        auditService.log("GRANT_KB_PERMISSION", "KbPermission", String.valueOf(p.getId()), null,
                body.get("targetType") + ":" + body.get("targetId"));
        return Result.ok(p);
    }

    @DeleteMapping("/{id}/permissions/{permId}")
    public Result removePermission(@PathVariable Long id, @PathVariable Long permId) {
        requireKbAdmin();
        kbService.removePermission(id, permId);
        auditService.log("REVOKE_KB_PERMISSION", "KbPermission", String.valueOf(permId), null, null);
        return Result.ok();
    }

    @GetMapping("/{id}/apikeys")
    public Result listApiKeys(@PathVariable Long id) {
        requireKbAdmin();
        List<ApiKey> keys = apiKeyService.list(id);
        return Result.ok(keys);
    }

    @PostMapping("/{id}/apikeys")
    public Result createApiKey(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requireKbAdmin();
        ApiKeyService.ApiKeyMaterial material = apiKeyService.create(id, body.get("name"));
        auditService.log("CREATE_API_KEY", "ApiKey", String.valueOf(material.id()), null, body.get("name"));
        // 明文仅返回一次
        return Result.ok(Map.of("id", material.id(), "rawKey", material.rawKey()));
    }

    @DeleteMapping("/{id}/apikeys/{keyId}")
    public Result revokeApiKey(@PathVariable Long id, @PathVariable Long keyId) {
        requireKbAdmin();
        apiKeyService.revoke(id, keyId);
        auditService.log("REVOKE_API_KEY", "ApiKey", String.valueOf(keyId), null, null);
        return Result.ok();
    }
}
