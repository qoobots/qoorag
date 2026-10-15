package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 异步入库任务（RAG-030）。
 * <p>
 * 改造前：上传请求内同步执行「解析 → 分块 → 向量化 → 入库」，大文档容易超时，
 * 失败即整篇重来，且无进度可查。现在上传立即返回 taskId，后台线程消费，
 * 状态机 {@code QUEUED → RUNNING → SUCCEEDED / FAILED}，带阶段、进度与失败原因。
 */
@Entity
@Table(name = "ingest_task")
public class IngestTask {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    /** 阶段：PENDING / PARSE / CHUNK / EMBED / SAVE / DONE */
    public static final String STAGE_PARSE = "PARSE";
    public static final String STAGE_CHUNK = "CHUNK";
    public static final String STAGE_EMBED = "EMBED";
    public static final String STAGE_SAVE = "SAVE";
    public static final String STAGE_DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 入库完成后回填的 document_id */
    @Column(name = "document_id")
    private Long documentId;

    @Column(length = 512)
    private String filename;

    @Column(length = 24, nullable = false)
    private String status = QUEUED;

    /** 当前阶段，见本类 STAGE_* 常量 */
    @Column(length = 32)
    private String stage;

    /** 进度百分比 0~100 */
    @Column(nullable = false)
    private Integer progress = 0;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
