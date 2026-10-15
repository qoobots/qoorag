package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 检索评测集（RAG-001 / RAG-002）。
 * <p>
 * 改造前评测集是一份手写 JSON（{@code scripts/eval/qoorag-eval-dataset.json}），
 * 全部为文档级标注、不可扩展、无版本。本实体把评测集落库，支持：
 * 多版本并存、按 KB 归属、来源区分（人工标注 / LLM 自动生成）、与评测报告关联。
 */
@Entity
@Table(name = "eval_dataset")
public class EvalDataset {

    /** 来源：人工标注 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 来源：LLM 从库内文档反向自动生成 */
    public static final String SOURCE_GENERATED = "GENERATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(length = 255, nullable = false)
    private String name;

    /** MANUAL / GENERATED */
    @Column(length = 32)
    private String source = SOURCE_MANUAL;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "item_count")
    private Integer itemCount = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
