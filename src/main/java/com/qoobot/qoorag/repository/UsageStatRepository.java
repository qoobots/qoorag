package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.UsageStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface UsageStatRepository extends JpaRepository<UsageStat, Long> {

    /**
     * 按唯一键累加（PostgreSQL upsert）。kb_id 用 -1 哨兵表示「未归属 KB」，
     * 与实体/迁移脚本中的唯一约束列完全一致。
     */
    @Modifying
    @Query(value = """
            INSERT INTO usage_stat (stat_date, tenant_id, kb_id, api_key_ref, metric_type, call_count, tokens, est_cost, updated_at)
            VALUES (:statDate, :tenantId, :kbId, :apiKeyRef, :metricType, :calls, :tokens, :cost, now())
            ON CONFLICT (stat_date, tenant_id, kb_id, api_key_ref, metric_type)
            DO UPDATE SET call_count = usage_stat.call_count + EXCLUDED.call_count,
                          tokens    = usage_stat.tokens + EXCLUDED.tokens,
                          est_cost  = usage_stat.est_cost + EXCLUDED.est_cost,
                          updated_at = now()
            """, nativeQuery = true)
    void upsert(@Param("statDate") LocalDate statDate,
                @Param("tenantId") Long tenantId,
                @Param("kbId") Long kbId,
                @Param("apiKeyRef") String apiKeyRef,
                @Param("metricType") String metricType,
                @Param("calls") long calls,
                @Param("tokens") long tokens,
                @Param("cost") double cost);

    /**
     * 区间 + 可选租户 / KB 过滤的用量明细。kbId / tenantId 为 null 表示不过滤（查全部）。
     */
    @Query("SELECT u FROM UsageStat u WHERE u.statDate BETWEEN :from AND :to "
            + "AND (:tenantId IS NULL OR u.tenantId = :tenantId) "
            + "AND (:kbId IS NULL OR u.kbId = :kbId)")
    List<UsageStat> findRange(@Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             @Param("tenantId") Long tenantId,
                             @Param("kbId") Long kbId);
}
