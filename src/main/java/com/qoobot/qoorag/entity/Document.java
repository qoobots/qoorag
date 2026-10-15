package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文档（RAG-020 元数据体系）。
 * <p>
 * 除文件名与状态外，携带来源信息（URI / MIME / 大小 / 标题 / 页数）与解析信息
 * （解析器及其版本），并记录内容哈希以支持增量更新与去重（RAG-029）。
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(length = 255)
    private String name;

    @Column(length = 16)
    private String status = "PENDING";

    /** 原始文件名 / 来源 URI */
    @Column(name = "source_uri", length = 512)
    private String sourceUri;

    /** 内容 SHA-256 十六进制，用于去重与增量更新判定 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** MIME 类型，如 application/pdf */
    @Column(name = "mime_type", length = 128)
    private String mimeType;

    /** 文件字节数 */
    @Column(name = "size")
    private Long size;

    /** 文档标题（取自文件名或文档内首个标题） */
    @Column(name = "doc_title", length = 512)
    private String docTitle;

    /** 页数（PDF 等分页文档） */
    @Column(name = "page_count")
    private Integer pageCount;

    /** 使用的解析器标识，如 pdfbox / ocr-paddle */
    @Column(length = 64)
    private String parser;

    /** 解析器版本，便于问题排查与回溯 */
    @Column(name = "parser_version", length = 32)
    private String parserVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getParser() { return parser; }
    public void setParser(String parser) { this.parser = parser; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
