package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 文档管理（知识管理员，4.2 / 4.11） */
@RestController
@RequestMapping("/api/kb")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestService ingestService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;

    public DocumentController(IngestService ingestService,
                              DocumentRepository documentRepository,
                              AuditService auditService) {
        this.ingestService = ingestService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
    }

    private void requireKbAdmin() {
        if (!SecurityContext.get().hasRole("知识管理员")) {
            throw new RuntimeException("无权限：需要知识管理员角色");
        }
    }

    /** 上传文档并异步入库（支持一次上传多个文件） */
    @PostMapping("/{kbId}/documents")
    public Result upload(
            @PathVariable Long kbId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        requireKbAdmin();
        Long tenantId = SecurityContext.get().getTenantId();

        if (files == null || files.isEmpty()) {
            return Result.fail("请选择要上传的文件");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        for (MultipartFile file : files) {
            Map<String, Object> one = new HashMap<>();
            String filename = file.getOriginalFilename();
            one.put("name", filename);
            if (file.isEmpty()) {
                one.put("status", "FAILED");
                one.put("error", "文件为空");
            } else if (filename == null) {
                one.put("status", "FAILED");
                one.put("error", "文件名为空");
            } else {
                String lowerName = filename.toLowerCase();
                if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".md") && !lowerName.endsWith(".pdf")) {
                    one.put("status", "FAILED");
                    one.put("error", "不支持的格式，仅支持 .txt / .md / .pdf");
                } else {
                    try {
                        log.info("收到文档上传：kbId={}, file={}, size={}", kbId, filename, file.getSize());
                        Document doc = ingestService.ingest(file, kbId, tenantId);
                        auditService.log("UPLOAD_DOCUMENT", "Document", String.valueOf(doc.getId()), null, filename);
                        one.put("id", doc.getId());
                        one.put("status", doc.getStatus());
                    } catch (Exception e) {
                        one.put("status", "FAILED");
                        one.put("error", e.getMessage());
                    }
                }
            }
            if (!"FAILED".equals(one.get("status"))) {
                successCount++;
            }
            results.add(one);
        }

        int failedCount = results.size() - successCount;
        log.info("批量上传完成：kbId={}, 总数={}, 成功={}, 失败={}", kbId, results.size(), successCount, failedCount);
        return Result.ok(Map.of(
                "total", results.size(),
                "success", successCount,
                "failed", failedCount,
                "results", results
        ));
    }

    /** 查询知识库下文档列表（仅未删除） */
    @GetMapping("/{kbId}/documents")
    public Result list(@PathVariable Long kbId) {
        requireKbAdmin();
        List<Document> docs = documentRepository.findByKbIdAndDeletedAtIsNull(kbId);
        return Result.ok(docs);
    }
}
