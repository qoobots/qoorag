package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.entity.IngestTask;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.IngestQueueService;
import com.qoobot.qoorag.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理（知识管理员，4.2 / 4.11）。
 * <p>
 * RAG-030：上传改为<b>异步入库</b>——接口立即返回 {@code taskId}，后台线程执行
 * 「解析 → 分块 → 向量化 → 入库」，进度/失败原因可查、失败可重试。
 */
@RestController
@RequestMapping("/api/kb")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestQueueService ingestQueueService;
    private final IngestService ingestService;
    private final DocumentRepository documentRepository;
    private final AuditService auditService;

    public DocumentController(IngestQueueService ingestQueueService,
                              IngestService ingestService,
                              DocumentRepository documentRepository,
                              AuditService auditService) {
        this.ingestQueueService = ingestQueueService;
        this.ingestService = ingestService;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
    }

    private void requireKbAdmin() {
        if (!SecurityContext.get().hasRole("知识管理员")) {
            throw new RuntimeException("无权限：需要知识管理员角色");
        }
    }

    /**
     * 上传文档并异步入库（支持一次上传多个文件）。
     * <p>立即返回每个文件的 taskId，不等待入库完成。
     */
    @PostMapping("/{kbId}/documents")
    public Result upload(
            @PathVariable Long kbId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        requireKbAdmin();
        Long tenantId = SecurityContext.get().getTenantId();
        Long userId = SecurityContext.get().getUserId();

        if (files == null || files.isEmpty()) {
            return Result.fail("请选择要上传的文件");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
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
            } else if (!supported(filename)) {
                one.put("status", "FAILED");
                one.put("error", "不支持的格式，仅支持 .txt / .md / .pdf");
            } else {
                try {
                    log.info("收到文档上传：kbId={}, file={}, size={}", kbId, filename, file.getSize());
                    IngestTask task = ingestQueueService.enqueue(file, kbId, tenantId, userId);
                    auditService.log("UPLOAD_DOCUMENT", "Document", String.valueOf(task.getId()),
                            null, filename + "（异步入库 taskId=" + task.getId() + "）");
                    one.put("taskId", task.getId());
                    one.put("status", task.getStatus());
                    one.put("progress", task.getProgress());
                    accepted++;
                } catch (Exception e) {
                    one.put("status", "FAILED");
                    one.put("error", e.getMessage());
                }
            }
            results.add(one);
        }

        log.info("批量上传完成：kbId={}, 总数={}, 已入队={}", kbId, results.size(), accepted);
        return Result.ok(Map.of(
                "total", results.size(),
                "accepted", accepted,
                "results", results,
                "message", "文档已进入异步入库队列，可通过任务查询接口查看进度"
        ));
    }

    /** 查询知识库下文档列表（仅未删除） */
    @GetMapping("/{kbId}/documents")
    public Result list(@PathVariable Long kbId) {
        requireKbAdmin();
        List<Document> docs = documentRepository.findByKbIdAndDeletedAtIsNull(kbId);
        return Result.ok(docs);
    }

    /**
     * 查询入库任务列表（RAG-030）。
     * <p>含状态、阶段、进度百分比、失败原因、关联 documentId。
     */
    @GetMapping("/{kbId}/ingest-tasks")
    public Result listTasks(@PathVariable Long kbId) {
        requireKbAdmin();
        return Result.ok(ingestQueueService.listByKb(kbId));
    }

    /** 查询单个入库任务详情 */
    @GetMapping("/{kbId}/ingest-tasks/{taskId}")
    public Result getTask(@PathVariable Long kbId, @PathVariable Long taskId) {
        requireKbAdmin();
        return Result.ok(ingestQueueService.get(kbId, taskId));
    }

    /** 重试失败的入库任务（临时文件仍在时可重试） */
    @PostMapping("/{kbId}/ingest-tasks/{taskId}/retry")
    public Result retryTask(@PathVariable Long kbId, @PathVariable Long taskId) {
        requireKbAdmin();
        Long userId = SecurityContext.get().getUserId();
        IngestTask task = ingestQueueService.retry(kbId, taskId);
        auditService.log("RETRY_INGEST_TASK", "IngestTask", String.valueOf(taskId),
                null, "第 " + task.getRetryCount() + " 次重试", SecurityContext.get().getTenantId(), userId);
        return Result.ok(task);
    }

    private static boolean supported(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".pdf");
    }
}
