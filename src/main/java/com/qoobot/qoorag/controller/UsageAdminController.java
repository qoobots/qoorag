package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.entity.UsageStat;
import com.qoobot.qoorag.service.UsageStatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用量与成本报表接口（RAG-053）。
 * <p>
 * 按日 / 区间查询 embedding / rerank / LLM 的调用次数、token 数与估算费用，
 * 支持按 KB、租户过滤；费用单价来自 {@code qoorag.cost.*}（见 {@link UsageStatService}）。
 */
@RestController
@RequestMapping("/api/admin/usage")
public class UsageAdminController {

    private static final Logger log = LoggerFactory.getLogger(UsageAdminController.class);
    private static final long GLOBAL_KB = -1L;

    private final UsageStatService usageStatService;

    public UsageAdminController(UsageStatService usageStatService) {
        this.usageStatService = usageStatService;
    }

    /** 每日明细（kbId 为 -1 时表示为租户级用量，前端展示为「全局」） */
    @GetMapping("/daily")
    public Result daily(@RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(required = false) Long kbId,
                        @RequestParam(required = false) Long tenantId) {
        LocalDate fromDate = from == null ? null : LocalDate.parse(from);
        LocalDate toDate = to == null ? null : LocalDate.parse(to);
        List<UsageStat> rows = usageStatService.queryRange(fromDate, toDate, kbId, tenantId);
        List<Map<String, Object>> data = rows.stream().map(this::toRow).collect(Collectors.toList());
        return Result.ok(Map.of(
                "currency", usageStatService.getCurrency(),
                "count", data.size(),
                "rows", data));
    }

    /** 区间汇总：总计 + 按指标类型 + 按 KB */
    @GetMapping("/summary")
    public Result summary(@RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(required = false) Long kbId,
                          @RequestParam(required = false) Long tenantId) {
        LocalDate fromDate = from == null ? null : LocalDate.parse(from);
        LocalDate toDate = to == null ? null : LocalDate.parse(to);
        List<UsageStat> rows = usageStatService.queryRange(fromDate, toDate, kbId, tenantId);

        long totalCalls = 0, totalTokens = 0;
        double totalCost = 0.0;
        Map<String, Agg> byMetric = new LinkedHashMap<>();
        Map<String, Agg> byKb = new LinkedHashMap<>();
        for (UsageStat u : rows) {
            totalCalls += u.getCallCount();
            totalTokens += u.getTokens();
            totalCost += u.getEstCost();
            agg(byMetric, u.getMetricType(), u);
            String kbLabel = Objects.equals(u.getKbId(), GLOBAL_KB) ? "全局" : String.valueOf(u.getKbId());
            agg(byKb, kbLabel, u);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currency", usageStatService.getCurrency());
        result.put("from", fromDate == null ? "近30天" : fromDate);
        result.put("to", toDate == null ? "今天" : toDate);
        result.put("total", Map.of("calls", totalCalls, "tokens", totalTokens, "cost", round(totalCost)));
        result.put("byMetric", byMetric.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toMap(), (a, b) -> a, LinkedHashMap::new)));
        result.put("byKb", byKb.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toMap(), (a, b) -> a, LinkedHashMap::new)));
        return Result.ok(result);
    }

    private void agg(Map<String, Agg> map, String key, UsageStat u) {
        map.computeIfAbsent(key, k -> new Agg()).add(u);
    }

    private Map<String, Object> toRow(UsageStat u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statDate", u.getStatDate());
        m.put("tenantId", u.getTenantId());
        m.put("kbId", Objects.equals(u.getKbId(), GLOBAL_KB) ? null : u.getKbId());
        m.put("apiKeyRef", u.getApiKeyRef());
        m.put("metricType", u.getMetricType());
        m.put("calls", u.getCallCount());
        m.put("tokens", u.getTokens());
        m.put("estCost", round(u.getEstCost()));
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 聚合累加器 */
    private static final class Agg {
        long calls = 0;
        long tokens = 0;
        double cost = 0.0;

        void add(UsageStat u) {
            calls += u.getCallCount();
            tokens += u.getTokens();
            cost += u.getEstCost();
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("tokens", tokens);
            m.put("cost", round(cost));
            return m;
        }
    }
}
