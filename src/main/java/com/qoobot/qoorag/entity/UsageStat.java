package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用量与成本统计（RAG-053）。
 * <p>
 * 按「日期 + 租户 + 知识库 + 密钥指纹 + 指标类型」聚合每日的调用次数、token 数与估算费用。
 * {@code kb_id} 取 -1 哨兵表示「租户级 / 未归属到具体 KB」（embedding、rerank 等跨 KB 的基础设施调用）。
 * 数据源为应用内缓冲 + 定时落库（{@code UsageStatService}），管理端可查用量报表。
 */
@Entity
@Table(name = "usage_stat")
public class UsageStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** -1 哨兵表示未归属到具体 KB（租户级） */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 密钥指纹（角色 + 末 4 位），不落明文密钥 */
    @Column(name = "api_key_ref", nullable = false)
    private String apiKeyRef;

    /** EMBEDDING / RERANK / LLM */
    @Column(name = "metric_type", nullable = false)
    private String metricType;

    @Column(name = "call_count", nullable = false)
    private long callCount;

    @Column(name = "tokens", nullable = false)
    private long tokens;

    @Column(name = "est_cost", nullable = false)
    private double estCost;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UsageStat() {
    }

    public UsageStat(LocalDate statDate, Long tenantId, Long kbId, String apiKeyRef,
                     String metricType, long callCount, long tokens, double estCost) {
        this.statDate = statDate;
        this.tenantId = tenantId;
        this.kbId = kbId;
        this.apiKeyRef = apiKeyRef;
        this.metricType = metricType;
        this.callCount = callCount;
        this.tokens = tokens;
        this.estCost = estCost;
    }

    public Long getId() { return id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public String getApiKeyRef() { return apiKeyRef; }
    public void setApiKeyRef(String apiKeyRef) { this.apiKeyRef = apiKeyRef; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }
    public long getTokens() { return tokens; }
    public void setTokens(long tokens) { this.tokens = tokens; }
    public double getEstCost() { return estCost; }
    public void setEstCost(double estCost) { this.estCost = estCost; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
