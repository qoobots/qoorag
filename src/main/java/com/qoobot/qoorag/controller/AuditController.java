package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.DesensitizeUtil;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.dto.AuditLogQuery;
import com.qoobot.qoorag.entity.AuditLog;
import com.qoobot.qoorag.service.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 审计日志查询与导出（管理接口，4.8；租户隔离） */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 分页查询审计日志（按动作/对象类型/时间范围可选过滤） */
    @GetMapping
    public Result list(@Valid @ModelAttribute AuditLogQuery query) {
        Long tenantId = SecurityContext.get().getTenantId();
        Page<AuditLog> page = auditService.query(
                tenantId, query.getAction(), query.getObjectType(),
                query.getStart(), query.getEnd(),
                PageRequest.of(query.getPage(), query.getSize(), Sort.by(Sort.Direction.DESC, "createdAt")));
        return Result.ok(java.util.Map.of(
                "content", page.getContent(),
                "page", page.getNumber(),
                "size", page.getSize(),
                "totalElements", page.getTotalElements(),
                "totalPages", page.getTotalPages()
        ));
    }

    /** 导出审计日志为 CSV（同过滤条件，租户隔离） */
    @GetMapping("/export")
    public void export(@Valid @ModelAttribute AuditLogQuery query, HttpServletResponse response) throws Exception {
        Long tenantId = SecurityContext.get().getTenantId();
        // 导出不分页，按时间倒序全量返回（租户隔离）
        Page<AuditLog> page = auditService.query(
                tenantId, query.getAction(), query.getObjectType(),
                query.getStart(), query.getEnd(),
                PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "createdAt")));

        String filename = "audit_log_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + URLEncoder.encode(filename, "UTF-8"));

        try (PrintWriter writer = response.getWriter()) {
            writer.println("id,tenant_id,actor_id,action,object_type,object_id,before_value,after_value,created_at");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (AuditLog log : page.getContent()) {
                writer.println(String.join(",",
                        csv(log.getId() == null ? "" : log.getId().toString()),
                        csv(log.getTenantId() == null ? "" : log.getTenantId().toString()),
                        csv(log.getActorId() == null ? "" : log.getActorId().toString()),
                        csv(log.getAction()),
                        csv(log.getObjectType()),
                        csv(log.getObjectId()),
                        csv(DesensitizeUtil.maskText(log.getBeforeValue())),
                        csv(DesensitizeUtil.maskText(log.getAfterValue())),
                        csv(log.getCreatedAt() == null ? "" : log.getCreatedAt().format(fmt))
                ));
            }
        }
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }
}
