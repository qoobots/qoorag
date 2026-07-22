package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.dto.RetrieveChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 精排服务（方案 2 / #retrieval-tuning 进阶）：对已召回的候选片段做 cross-encoder 重排，
 * 在「宽召回」之上再做「窄精排」，从而同时保住召回率与上下文精读。
 * <p>
 * 典型链路：检索阶段 {@link RetrieveService} 以 hybrid + 大候选池召回 top-k（如 20 条，Recall≈93%），
 * 本服务再调用百炼 rerank 模型对这 20 条按与 query 的相关性打分，截断到 {@code rerank.top-n}（如 5 条）
 * 喂给大模型。这样 Recall 仍由宽召回保证，而 LLM 上下文噪声与 token 成本由精排压下来。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>开关 {@code qoorag.retrieval.rerank.enabled} 默认关闭，不影响现有行为；</li>
 *   <li>精排失败时优雅降级：返回原始候选（退化为未精排的宽召回），保证 Chat 不中断；</li>
 *   <li>复用百炼 {@code /rerank} 兼容接口（与 embedding 同源密钥）。</li>
 * </ul>
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;

    @Value("${qoorag.retrieval.rerank.enabled:false}")
    private boolean enabled;

    @Value("${qoorag.retrieval.rerank.top-n:5}")
    private int topN;

    @Value("${qoorag.retrieval.rerank.model:gte-rerank}")
    private String model;

    /** 单次 rerank 的最大文档数（百炼限制保护，超出部分不精排、原序追加） */
    private static final int MAX_DOCS = 100;

    public RerankService(RestTemplate restTemplate, BailianConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    public boolean isEnabled() { return enabled; }
    public int getTopN() { return topN; }

    /**
     * 对召回候选做精排并返回截断后的结果。
     *
     * @param query      查询文本
     * @param candidates 召回阶段返回的候选（已按召回相关性降序）
     * @return 精排后截断到 top-n 的候选；未开启或无需精排时原样返回
     */
    public List<RetrieveChunk> rerank(String query, List<RetrieveChunk> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        // 候选数本就不超过 top-n，无需精排
        if (candidates.size() <= topN) {
            return candidates;
        }

        // 拆分：参与精排的前 MAX_DOCS 条 + 余下保持原序（避免超出 API 限制时丢失候选）
        List<RetrieveChunk> toRerank = candidates.subList(0, Math.min(candidates.size(), MAX_DOCS));
        List<RetrieveChunk> rest = candidates.size() > MAX_DOCS
                ? new ArrayList<>(candidates.subList(MAX_DOCS, candidates.size()))
                : List.of();

        try {
            List<String> docs = new ArrayList<>(toRerank.size());
            for (RetrieveChunk c : toRerank) {
                docs.add(c.getContent() == null ? "" : c.getContent());
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("documents", docs);
            requestBody.put("return_documents", false);
            requestBody.put("top_n", toRerank.size());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getApiKey());
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            String url = config.getBaseUrl() + "/rerank";

            log.info("调用百炼 Rerank API，model={}，候选数={}", model, toRerank.size());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("output")) {
                log.error("Rerank API 返回异常: {}", body);
                return fallback(candidates, "API 返回为空");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) body.get("output");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
            if (results == null || results.isEmpty()) {
                return fallback(candidates, "results 为空");
            }

            // index -> 相关性分数
            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (Map<String, Object> r : results) {
                int idx = ((Number) r.get("index")).intValue();
                double sc = ((Number) r.get("relevance_score")).doubleValue();
                scoreByIndex.put(idx, sc);
            }

            // 按相关性分数降序重排候选，并把精排分数写回 chunk.score 便于溯源
            List<RetrieveChunk> reranked = new ArrayList<>(toRerank);
            reranked.sort((a, b) -> {
                int ai = toRerank.indexOf(a);
                int bi = toRerank.indexOf(b);
                return Double.compare(scoreByIndex.getOrDefault(bi, 0.0), scoreByIndex.getOrDefault(ai, 0.0));
            });
            for (RetrieveChunk c : reranked) {
                int idx = toRerank.indexOf(c);
                c.setScore(scoreByIndex.getOrDefault(idx, c.getScore()));
            }

            List<RetrieveChunk> out = new ArrayList<>(reranked.size() + rest.size());
            out.addAll(reranked);
            out.addAll(rest);
            if (out.size() > topN) {
                out = out.subList(0, topN);
            }
            log.info("精排完成，候选 {} -> 返回 {}", candidates.size(), out.size());
            return out;
        } catch (Exception e) {
            log.error("Rerank 调用失败，降级为原始候选: {}", e.getMessage(), e);
            return fallback(candidates, e.getMessage());
        }
    }

    /** 精排失败时的优雅降级：返回原始候选（保留宽召回，Chat 不中断） */
    private List<RetrieveChunk> fallback(List<RetrieveChunk> candidates, String reason) {
        log.warn("Rerank 降级：{}，返回 {} 条原始候选", reason, candidates.size());
        return candidates;
    }
}
