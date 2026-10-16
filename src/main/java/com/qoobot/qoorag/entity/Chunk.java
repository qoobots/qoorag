package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文本分块（RAG-020 元数据体系）。
 * <p>
 * 除内容与序号外，携带结构元数据（标题路径 / 页码 / 字符区间 / 块类型 / token 数 / 父块），
 * 用于：引用溯源展示、结构化过滤检索、上下文回填（父子块）、Prompt 中的来源标注。
 * <p>
 * 词法检索列 {@code tsv} 为数据库生成列（{@code GENERATED ALWAYS AS qoorag_cjk_tsvector(content)}），
 * 由 PostgreSQL 自动维护，因此不在本实体中映射，避免 JPA 尝试写入只读列。
 */
@Entity
@Table(name = "chunk")
public class Chunk {

    /** 块类型：paragraph / heading / table / code / list / figure / parent */
    public static final String TYPE_PARAGRAPH = "paragraph";
    public static final String TYPE_HEADING = "heading";
    public static final String TYPE_TABLE = "table";
    public static final String TYPE_CODE = "code";
    public static final String TYPE_LIST = "list";
    public static final String TYPE_FIGURE = "figure";
    /** RAG-022 父子块：父块（生成上下文单元，本身不单独向量化） */
    public static final String TYPE_PARENT = "parent";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Integer seq;

    /** 标题路径，如「第一章 概述 / 1.2 术语」，多级用「 / 」拼接 */
    @Column(name = "heading_path", columnDefinition = "TEXT")
    private String headingPath;

    /** 页码（PDF 等分页文档），无分页信息时为 null */
    @Column(name = "page_no")
    private Integer pageNo;

    /** 在文档纯文本中的起始字符偏移（含），用于原文定位 */
    @Column(name = "char_start")
    private Integer charStart;

    /** 在文档纯文本中的结束字符偏移（不含） */
    @Column(name = "char_end")
    private Integer charEnd;

    /** 块类型，见本类常量 */
    @Column(name = "chunk_type", length = 32)
    private String chunkType = TYPE_PARAGRAPH;

    /** 估算 token 数（中文按字数、ASCII 按词数粗估），用于上下文预算裁剪 */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 父块 ID（RAG-022 父子块：子块用于检索，父块用于生成上下文回填） */
    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    /**
     * 上下文前缀（RAG-023 Contextual Chunking）：LLM 生成的"该块在原文中的位置与内容"说明。
     * <p>拼在原文之前一起 embedding 与建词法索引，解决切块后 chunk"失忆"的问题。
     * 未启用时为 null，检索与展示均退化为仅用 content。
     */
    @Column(name = "context_prefix", columnDefinition = "TEXT")
    private String contextPrefix;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getHeadingPath() { return headingPath; }
    public void setHeadingPath(String headingPath) { this.headingPath = headingPath; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public Integer getCharStart() { return charStart; }
    public void setCharStart(Integer charStart) { this.charStart = charStart; }
    public Integer getCharEnd() { return charEnd; }
    public void setCharEnd(Integer charEnd) { this.charEnd = charEnd; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public Long getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(Long parentChunkId) { this.parentChunkId = parentChunkId; }
    public String getContextPrefix() { return contextPrefix; }
    public void setContextPrefix(String contextPrefix) { this.contextPrefix = contextPrefix; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
