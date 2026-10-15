package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 评测运行记录（RAG-000）。
 * <p>
 * 每次评测落一条记录，含<b>环境指纹</b>（embedding 模型 / chat 模型 / rerank 模型 / 检索策略 /
 * 精排开关 / topK），使指标可复现、可对比。没有环境指纹的指标没有意义——
 * 换了 embedding 模型或检索策略，Recall 的变化无法归因。
 */
@Entity
@Table(name = "eval_run")
public class EvalRun {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id")
    private Long datasetId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(length = 64, nullable = false)
    private String status = STATUS_SUCCESS;

    @Column(name = "top_k")
    private Integer topK;

    // ===== 指标 =====
    @Column(name = "recall_at_k")
    private Double recallAtK;

    @Column(name = "precision_at_k")
    private Double precisionAtK;

    @Column(name = "hit_rate")
    private Double hitRate;

    private Double mrr;

    /** 生成端：忠实度（RAG-003） */
    private Double faithfulness;

    /** 生成端：答案相关度（RAG-003） */
    @Column(name = "answer_relevancy")
    private Double answerRelevancy;

    @Column(name = "context_precision")
    private Double contextPrecision;

    @Column(name = "context_recall")
    private Double contextRecall;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "loop_suspected_count")
    private Integer loopSuspectedCount;

    /** 指标是否可信（规范五：双百默认不可信） */
    private Boolean trustworthy;

    // ===== 环境指纹（RAG-000 可复现性的关键）=====
    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "chat_model", length = 128)
    private String chatModel;

    @Column(name = "rerank_model", length = 128)
    private String rerankModel;

    /** 检索策略快照，如 hybrid-rrf/pool=200/minScore=0.0/rrfK=60/maxPerDoc=2 */
    @Column(name = "strategy_snapshot", length = 512)
    private String strategySnapshot;

    @Column(name = "rerank_enabled")
    private Boolean rerankEnabled;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Double getRecallAtK() { return recallAtK; }
    public void setRecallAtK(Double recallAtK) { this.recallAtK = recallAtK; }
    public Double getPrecisionAtK() { return precisionAtK; }
    public void setPrecisionAtK(Double precisionAtK) { this.precisionAtK = precisionAtK; }
    public Double getHitRate() { return hitRate; }
    public void setHitRate(Double hitRate) { this.hitRate = hitRate; }
    public Double getMrr() { return mrr; }
    public void setMrr(Double mrr) { this.mrr = mrr; }
    public Double getFaithfulness() { return faithfulness; }
    public void setFaithfulness(Double faithfulness) { this.faithfulness = faithfulness; }
    public Double getAnswerRelevancy() { return answerRelevancy; }
    public void setAnswerRelevancy(Double answerRelevancy) { this.answerRelevancy = answerRelevancy; }
    public Double getContextPrecision() { return contextPrecision; }
    public void setContextPrecision(Double contextPrecision) { this.contextPrecision = contextPrecision; }
    public Double getContextRecall() { return contextRecall; }
    public void setContextRecall(Double contextRecall) { this.contextRecall = contextRecall; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public Integer getLoopSuspectedCount() { return loopSuspectedCount; }
    public void setLoopSuspectedCount(Integer loopSuspectedCount) { this.loopSuspectedCount = loopSuspectedCount; }
    public Boolean getTrustworthy() { return trustworthy; }
    public void setTrustworthy(Boolean trustworthy) { this.trustworthy = trustworthy; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public String getRerankModel() { return rerankModel; }
    public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }
    public String getStrategySnapshot() { return strategySnapshot; }
    public void setStrategySnapshot(String strategySnapshot) { this.strategySnapshot = strategySnapshot; }
    public Boolean getRerankEnabled() { return rerankEnabled; }
    public void setRerankEnabled(Boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
