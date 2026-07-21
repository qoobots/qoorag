package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.DataClassification;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.service.RetrievalEvalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 检索质量评估与数据分级管理（#16 / #17）。
 * <p>
 * - {@code GET /api/admin/data-classifications}：返回企业标准对齐的数据分级清单（为资源池 UI 预留）。
 * - {@code POST /api/admin/eval}：基于标注数据集运行检索质量评估，返回召回率/准确率/命中率/MRR。
 */
@RestController
@RequestMapping("/api/admin")
public class RetrievalEvalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalController.class);

    private final RetrievalEvalService evalService;

    public RetrievalEvalController(RetrievalEvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping("/data-classifications")
    public Result dataClassifications() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DataClassification d : DataClassification.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.code);
            m.put("label", d.label);
            m.put("rank", d.rank);
            list.add(m);
        }
        return Result.ok(list);
    }

    @PostMapping("/eval")
    public Result evaluate(@RequestBody Map<String, Object> body) {
        Object kbObj = body.get("kbId");
        if (kbObj == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "kbId 必填");
        }
        Long kbId = ((Number) kbObj).longValue();
        int topK = body.get("topK") != null ? ((Number) body.get("topK")).intValue() : 5;
        Object queriesObj = body.get("queries");
        if (!(queriesObj instanceof List) || ((List<?>) queriesObj).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "queries 必填且非空");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) queriesObj;

        Long tenantId = null;
        var ctx = SecurityContext.get();
        if (ctx != null) {
            tenantId = ctx.getTenantId();
        }

        List<RetrievalEvalService.LabeledQuery> dataset = new ArrayList<>();
        for (Map<String, Object> q : queries) {
            String qtext = (String) q.get("query");
            Set<Long> chunkIds = toLongSet(q.get("relevantChunkIds"));
            Set<Long> docIds = toLongSet(q.get("relevantDocIds"));
            dataset.add(new RetrievalEvalService.LabeledQuery(qtext, chunkIds, docIds));
        }

        // 检索策略临时覆盖（用于对比不同策略，评估后恢复默认）
        String overrideMode = body.get("rerankMode") != null ? String.valueOf(body.get("rerankMode")) : null;
        int overridePool = body.get("candidatePool") != null ? ((Number) body.get("candidatePool")).intValue() : -1;
        double overrideMin = body.get("minScore") != null ? ((Number) body.get("minScore")).doubleValue() : -1.0;
        int overrideMax = body.get("diversityMaxPerDoc") != null ? ((Number) body.get("diversityMaxPerDoc")).intValue() : -1;
        int overrideK = body.get("rrfK") != null ? ((Number) body.get("rrfK")).intValue() : -1;
        String savedMode = evalService.getRetrieveService().getRerankMode();
        int savedPool = evalService.getRetrieveService().getCandidatePool();
        double savedMin = evalService.getRetrieveService().getMinScore();
        int savedMax = evalService.getRetrieveService().getDiversityMaxPerDoc();
        int savedK = evalService.getRetrieveService().getRrfK();
        RetrievalEvalService.EvalMetrics metrics;
        try {
            evalService.getRetrieveService().overrideRerankConfig(overrideMode, overridePool, overrideMin, overrideMax, overrideK);
            if (overrideMode != null || overridePool >= 0 || overrideMin >= 0 || overrideMax >= 0 || overrideK > 0) {
                log.info("评估临时覆盖检索策略: mode={}, pool={}, minScore={}, maxPerDoc={}, rrfK={}",
                        overrideMode, overridePool, overrideMin, overrideMax, overrideK);
            }

            metrics = evalService.evaluateDataset(dataset, topK, kbId, tenantId);
        } finally {
            // 恢复默认检索策略，避免影响后续正常检索（如 Chat）
            evalService.getRetrieveService().overrideRerankConfig(savedMode, savedPool, savedMin, savedMax, savedK);
        }

        List<Map<String, Object>> detailsOut = new ArrayList<>();
        for (int i = 0; i < metrics.details().size(); i++) {
            RetrievalEvalService.SingleEval se = metrics.details().get(i);
            RetrievalEvalService.LabeledQuery lq = dataset.get(i);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("query", lq.query());
            d.put("recallAtK", se.recallAtK());
            d.put("precisionAtK", se.precisionAtK());
            d.put("hit", se.hit());
            d.put("firstHitRank", se.firstHitRank());
            List<Map<String, Object>> itemsOut = new ArrayList<>();
            for (RetrievalEvalService.RetrievedItem it : se.items()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("rank", it.rank());
                im.put("chunkId", it.chunkId());
                im.put("documentId", it.documentId());
                im.put("score", it.score());
                im.put("matched", it.matched());
                itemsOut.add(im);
            }
            d.put("items", itemsOut);
            d.put("loopSuspected", se.loopSuspected());
            d.put("overlapRatio", se.overlapRatio());
            detailsOut.add(d);
        }

        return Result.ok(Map.of(
                "kbId", kbId,
                "topK", topK,
                "recallAtK", metrics.recallAtK(),
                "precisionAtK", metrics.precisionAtK(),
                "hitRate", metrics.hitRate(),
                "mrr", metrics.mrr(),
                "count", metrics.count(),
                "loopSuspectedCount", metrics.loopSuspectedCount(),
                "trustworthy", metrics.trustworthy(),
                "details", detailsOut
        ));
    }

    @SuppressWarnings("unchecked")
    private Set<Long> toLongSet(Object raw) {
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
