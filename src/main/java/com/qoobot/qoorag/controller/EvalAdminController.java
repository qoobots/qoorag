package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.EvalDataset;
import com.qoobot.qoorag.entity.EvalRun;
import com.qoobot.qoorag.service.AuditService;
import com.qoobot.qoorag.service.EvalDatasetGenService;
import com.qoobot.qoorag.service.EvalDatasetService;
import com.qoobot.qoorag.service.EvalRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 评测管理接口（RAG-000 / RAG-001 / RAG-002 / RAG-003）。
 * <p>
 * 把原先"人肉跑 PowerShell + 控制台看数字"的评测流程产品化：
 * 评测集可建可导、运行可落库可对比、指标含环境指纹可归因。
 */
@RestController
@RequestMapping("/api/admin/eval")
public class EvalAdminController {

    private static final Logger log = LoggerFactory.getLogger(EvalAdminController.class);

    private final EvalDatasetService datasetService;
    private final EvalDatasetGenService genService;
    private final EvalRunService runService;
    private final AuditService auditService;

    public EvalAdminController(EvalDatasetService datasetService,
                               EvalDatasetGenService genService,
                               EvalRunService runService,
                               AuditService auditService) {
        this.datasetService = datasetService;
        this.genService = genService;
        this.runService = runService;
        this.auditService = auditService;
    }

    /**
     * 创建评测集（人工标注或程序生成的条目均可）。
     * <p>入参格式与 {@code POST /api/admin/eval} 的 {@code queries} 一致。
     */
    @PostMapping("/datasets")
    public Result createDataset(@RequestBody Map<String, Object> body) {
        Long kbId = requireLong(body.get("kbId"), "kbId");
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String source = body.get("source") == null ? EvalDataset.SOURCE_MANUAL
                : String.valueOf(body.get("source"));
        Object queriesObj = body.get("queries");
        if (!(queriesObj instanceof List) || ((List<?>) queriesObj).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "queries 必填且非空");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) queriesObj;

        List<EvalDatasetService.EvalItem> items = new ArrayList<>(queries.size());
        for (Map<String, Object> q : queries) {
            items.add(new EvalDatasetService.EvalItem(
                    (String) q.get("query"),
                    toLongSet(q.get("relevantChunkIds")),
                    toLongSet(q.get("relevantDocIds")),
                    q.get("originQuery") == null ? null : String.valueOf(q.get("originQuery"))));
        }
        Long userId = SecurityContext.get() == null ? null : SecurityContext.get().getUserId();
        EvalDataset ds = datasetService.create(kbId, tenantId(), name, source, items, userId);
        auditService.log("CREATE_EVAL_DATASET", "EvalDataset", String.valueOf(ds.getId()), null,
                "kbId=" + kbId + ",source=" + source + ",count=" + ds.getItemCount(), tenantId(), userId);
        return Result.ok(ds);
    }

    /** 列出某 KB 的评测集 */
    @GetMapping("/datasets")
    public Result listDatasets(@RequestParam Long kbId) {
        return Result.ok(datasetService.list(kbId));
    }

    /**
     * 自动生成评测集（RAG-001）：从 KB 内 chunk 反向生成问题 + chunk 级标注。
     * <p>入参：{@code kbId}（必填）、{@code name}、{@code totalItems}（目标条数，默认 30）、
     * {@code maxChunks}（最多抽样 chunk 数，默认 50）。
     */
    @PostMapping("/generate")
    public Result generate(@RequestBody Map<String, Object> body) {
        Long kbId = requireLong(body.get("kbId"), "kbId");
        String name = body.get("name") == null ? "自动生成评测集" : String.valueOf(body.get("name"));
        int totalItems = body.get("totalItems") == null ? 30 : ((Number) body.get("totalItems")).intValue();
        int maxChunks = body.get("maxChunks") == null ? 50 : ((Number) body.get("maxChunks")).intValue();
        Long userId = SecurityContext.get() == null ? null : SecurityContext.get().getUserId();

        EvalDatasetGenService.GenReport report =
                genService.generate(kbId, tenantId(), name, totalItems, maxChunks, userId);
        auditService.log("GENERATE_EVAL_DATASET", "EvalDataset",
                String.valueOf(report.dataset().getId()), null,
                "kbId=" + kbId + ",items=" + report.itemsGenerated(), tenantId(), userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", report.dataset());
        out.put("chunksSampled", report.chunksSampled());
        out.put("itemsGenerated", report.itemsGenerated());
        out.put("chunksFailed", report.chunksFailed());
        out.put("qualityHint", report.qualityHint());
        return Result.ok(out);
    }

    /** 导出评测集为与 {@code POST /api/admin/eval} 入参兼容的结构 */
    @GetMapping("/datasets/{datasetId}/export")
    public Result exportDataset(@RequestParam Long kbId, @PathVariable Long datasetId) {
        return Result.ok(datasetService.export(kbId, datasetId));
    }

    /** 删除评测集 */
    @DeleteMapping("/datasets/{datasetId}")
    public Result deleteDataset(@RequestParam Long kbId, @PathVariable Long datasetId) {
        datasetService.delete(kbId, datasetId);
        Long userId = SecurityContext.get() == null ? null : SecurityContext.get().getUserId();
        auditService.log("DELETE_EVAL_DATASET", "EvalDataset", String.valueOf(datasetId),
                null, null, tenantId(), userId);
        return Result.ok("已删除");
    }

    /**
     * 执行一次评测并落库（RAG-000）。
     * <p>返回本次运行记录 + 与上一次成功运行的逐项增减。
     */
    @PostMapping("/run")
    public Result run(@RequestBody Map<String, Object> body) {
        Long kbId = requireLong(body.get("kbId"), "kbId");
        Long datasetId = requireLong(body.get("datasetId"), "datasetId");
        int topK = body.get("topK") == null ? 5 : ((Number) body.get("topK")).intValue();
        boolean rerank = body.get("rerank") != null
                && Boolean.parseBoolean(String.valueOf(body.get("rerank")));
        boolean withGen = body.get("withGen") != null
                && Boolean.parseBoolean(String.valueOf(body.get("withGen")));
        Long userId = SecurityContext.get() == null ? null : SecurityContext.get().getUserId();

        EvalRunService.RunResult result =
                runService.run(kbId, tenantId(), datasetId, topK, rerank, withGen, userId);
        auditService.log("RUN_EVAL", "EvalRun", String.valueOf(result.run().getId()), null,
                "dataset=" + datasetId + ",topK=" + topK + ",rerank=" + rerank
                        + ",recall=" + result.run().getRecallAtK(), tenantId(), userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", result.run());
        out.put("delta", result.delta());
        return Result.ok(out);
    }

    /** 查某 KB 的评测历史（含环境指纹，可对比趋势） */
    @GetMapping("/runs")
    public Result history(@RequestParam Long kbId) {
        return Result.ok(runService.history(kbId));
    }

    /** 对比两次运行 */
    @GetMapping("/compare")
    public Result compare(@RequestParam Long currentId, @RequestParam Long baselineId) {
        return Result.ok(runService.compare(currentId, baselineId));
    }

    // ===== 工具 =====

    private static Long requireLong(Object raw, String field) {
        if (raw == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 必填");
        }
        return ((Number) raw).longValue();
    }

    private static Long tenantId() {
        return SecurityContext.get() == null ? null : SecurityContext.get().getTenantId();
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> toLongSet(Object raw) {
        if (!(raw instanceof List)) {
            return Set.of();
        }
        Set<Long> set = new TreeSet<>();
        for (Object o : (List<Object>) raw) {
            if (o instanceof Number n) {
                set.add(n.longValue());
            }
        }
        return set;
    }
}
