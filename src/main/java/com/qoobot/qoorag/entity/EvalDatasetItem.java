package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 评测集条目（RAG-001 / RAG-002）。
 * <p>
 * 每条 = 一个查询 + 相关 chunk 集合 + 相关文档集合。相关 ID 以逗号分隔字符串存储，
 * 与既有 {@code /api/admin/eval} 的入参格式（{@code relevantChunkIds} /
 * {@code relevantDocIds}）保持一致，便于互相导入导出。
 * <p>
 * {@code originQuery} 仅在自动生成时使用：记录生成该条目的原始依据（如被提问的 chunk 摘要），
 * 便于人工抽检时回溯"这个问题是怎么来的"。
 */
@Entity
@Table(name = "eval_dataset_item")
public class EvalDatasetItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String query;

    /** 相关 chunk id，逗号分隔 */
    @Column(name = "relevant_chunk_ids", columnDefinition = "TEXT")
    private String relevantChunkIds;

    /** 相关文档 id，逗号分隔 */
    @Column(name = "relevant_doc_ids", columnDefinition = "TEXT")
    private String relevantDocIds;

    /** 自动生成时的原始依据（人工标注为空） */
    @Column(name = "origin_query", columnDefinition = "TEXT")
    private String originQuery;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getRelevantChunkIds() { return relevantChunkIds; }
    public void setRelevantChunkIds(String relevantChunkIds) { this.relevantChunkIds = relevantChunkIds; }
    public String getRelevantDocIds() { return relevantDocIds; }
    public void setRelevantDocIds(String relevantDocIds) { this.relevantDocIds = relevantDocIds; }
    public String getOriginQuery() { return originQuery; }
    public void setOriginQuery(String originQuery) { this.originQuery = originQuery; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
