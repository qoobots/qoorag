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

    /** 上传文档并异步入库 */
    @PostMapping("/{kbId}/documents")
    public Result upload(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file) {
        requireKbAdmin();
        Long tenantId = SecurityContext.get().getTenantId();

        if (file.isEmpty()) {
            return Result.fail("文件为空");
        }

        String filename = file.getOriginalFilename();
        if (filename == null) {
            return Result.fail("文件名为空");
        }

        String lowerName = filename.toLowerCase();
        if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".md") && !lowerName.endsWith(".pdf")) {
            return Result.fail("仅支持 .txt、.md 和 .pdf 格式，当前文件: " + filename);
        }

        log.info("收到文档上传：kbId={}, file={}, size={}", kbId, filename, file.getSize());
        Document doc = ingestService.ingest(file, kbId, tenantId);
        auditService.log("UPLOAD_DOCUMENT", "Document", String.valueOf(doc.getId()), null, filename);
        return Result.ok(Map.of(
                "id", doc.getId(),
                "name", doc.getName(),
                "status", doc.getStatus()
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
