package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.entity.UsageStat;
import com.qoobot.qoorag.repository.UsageStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用量与成本埋点服务（RAG-053）。
 * <p>
 * 各 LLM / Embedding / Rerank 调用处调用 {@code recordXxx(...)} 累加用量；为避免每次调用都写库，
 * 采用「进程内按 (日期, 租户, KB, 密钥指纹, 类型) 聚合 + 每 30s 批量 upsert」策略，
 * 落库后由管理端按日 / 区间查询报表。费用按可配置单价估算（见 {@code qoorag.cost.*}）。
 */
@Service
@EnableScheduling
public class UsageStatService {

    private static final Logger log = LoggerFactory.getLogger(UsageStatService.class);

    private final UsageStatRepository repo;
    private final double embeddingPrice;
    private final double llmInputPrice;
    private final double llmOutputPrice;
    private final double rerankPrice;
    private final String currency;

    public UsageStatService(UsageStatRepository repo,
                            @Value("${qoorag.cost.embedding.price-per-1k-tokens:0.0007}") double embeddingPrice,
                            @Value("${qoorag.cost.llm.input-price-per-1k-tokens:0.004}") double llmInputPrice,
                            @Value("${qoorag.cost.llm.output-price-per-1k-tokens:0.012}") double llmOutputPrice,
                            @Value("${qoorag.cost.rerank.price-per-1k-tokens:0.0004}") double rerankPrice,
                            @Value("${qoorag.cost.currency:CNY}") String currency) {
        this.repo = repo;
        this.embeddingPrice = embeddingPrice;
        this.llmInputPrice = llmInputPrice;
        this.llmOutputPrice = llmOutputPrice;
        this.rerankPrice = rerankPrice;
        this.currency = currency;
    }

    public String getCurrency() {
        return currency;
    }

    // ===== 聚合缓冲 =====

    private record Key(LocalDate date, long tenantId, long kbId, String apiKeyRef, String metricType) {
    }

    private static final class Delta {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong tokens = new AtomicLong();
        final AtomicReference<Double> cost = new AtomicReference<>(0.0);

        void add(long tokens, double cost) {
            calls.incrementAndGet();
            this.tokens.addAndGet(tokens);
            this.cost.accumulateAndGet(cost, Double::sum);
        }
    }

    private final Map<Key, Delta> buffer = new ConcurrentHashMap<>();

    /** embedding 调用埋点（token 为本次批量总 token） */
    public void recordEmbedding(Long tenantId, Long kbId, String apiKeyRef, int totalTokens) {
        record(tenantId, kbId, apiKeyRef, UsageCost.Metric.EMBEDDING, totalTokens, 0, 0);
    }

    /** rerank 调用埋点 */
    public void recordRerank(Long tenantId, Long kbId, String apiKeyRef, int totalTokens) {
        record(tenantId, kbId, apiKeyRef, UsageCost.Metric.RERANK, totalTokens, 0, 0);
    }

    /** LLM 调用埋点（区分输入/输出 token） */
    public void recordLlm(Long tenantId, Long kbId, String apiKeyRef, int promptTokens, int completionTokens) {
        record(tenantId, kbId, apiKeyRef, UsageCost.Metric.LLM, promptTokens + completionTokens, promptTokens, completionTokens);
    }

    private void record(Long tenantId, Long kbId, String apiKeyRef, UsageCost.Metric metric,
                       int totalTokens, int promptTokens, int completionTokens) {
        long tid = tenantId == null ? -1L : tenantId;
        long kid = kbId == null ? -1L : kbId;
        double cost = UsageCost.estimate(metric, embeddingPrice, llmInputPrice, llmOutputPrice, rerankPrice,
                promptTokens, completionTokens, totalTokens);
        Key k = new Key(LocalDate.now(), tid, kid, apiKeyRef, metric.name());
        buffer.computeIfAbsent(k, x -> new Delta()).add(totalTokens, cost);
    }

    // ===== 定时落库 =====

    @Scheduled(fixedDelay = 30_000)
    public void scheduledFlush() {
        flush();
    }

    @PreDestroy
    public void flushOnShutdown() {
        flush();
    }

    /** 将缓冲聚合结果批量 upsert 到 usage_stat 表 */
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        Map<Key, Delta> snapshot = new HashMap<>(buffer);
        buffer.clear();
        for (Map.Entry<Key, Delta> e : snapshot.entrySet()) {
            Key k = e.getKey();
            Delta d = e.getValue();
            try {
                repo.upsert(k.date(), k.tenantId(), k.kbId(), k.apiKeyRef(), k.metricType(),
                        d.calls.get(), d.tokens.get(), d.cost.get());
            } catch (Exception ex) {
                log.warn("用量统计落库失败（本次缓冲丢弃）: {}", ex.getMessage());
            }
        }
    }

    // ===== 报表查询 =====

    /** 区间明细；kbId / tenantId 为 null 表示不过滤（查全部，含 -1 哨兵行） */
    public List<UsageStat> queryRange(LocalDate from, LocalDate to, Long kbId, Long tenantId) {
        if (from == null) {
            from = LocalDate.now().minusDays(30);
        }
        if (to == null) {
            to = LocalDate.now();
        }
        return repo.findRange(from, to, tenantId, kbId);
    }
}
