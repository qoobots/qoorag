package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.config.MetricsConfig;
import com.qoobot.qoorag.common.HttpRetryUtil;
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
 *   <li>使用百炼 rerank 模型（默认 qwen3-rerank），走 MAAS 工作空间专属域名
 *       {@code qoorag.retrieval.rerank.endpoint}（与 embedding 同源密钥）。</li>
 * </ul>
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;
    private final UsageStatService usageStat;
    private final MetricsConfig.RagMetrics ragMetrics;

    @Value("${qoorag.retrieval.rerank.enabled:false}")
    private boolean enabled;

    @Value("${qoorag.retrieval.rerank.top-n:5}")
    private int topN;

    @Value("${qoorag.retrieval.rerank.model:qwen3-rerank}")
    private String model;

    /** 百炼 rerank 接口完整地址。qwen3-rerank 走 MAAS 工作空间专属域名，
     *  形如 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks，
     *  需把 {WorkspaceId} 换成真实业务空间 ID（可在 application.yml 或环境变量 QOORAG_RERANK_ENDPOINT 配置）。
     *  注意：与 embeddings/chat 的 dashscope.aliyuncs.com/compatible-mode/v1 不是同一个域。 */
    @Value("${qoorag.retrieval.rerank.endpoint:}")
    private String endpoint;

    /** 精排服务密钥。为空时回退到 Embedding / 共享密钥。
     *  <p>单独拆出的原因：rerank 的 endpoint 往往与 chat、embedding 不同域
     *  （例如百炼 MAAS 工作空间域名 vs dashscope），密钥自然也可能不同——
     *  当 chat 用 DeepSeek 而 rerank 用百炼时，共用一把 key 必然有一边 401。 */
    @Value("${qoorag.retrieval.rerank.api-key:}")
    private String apiKey;

    /** 检索场景指令：引导 rerank 模型按"问答检索"策略排序（优先给能回答 Query 的文档高分） */
    private static final String RETRIEVE_INSTRUCT =
            "Given a web search query, retrieve relevant passages that answer the query.";

    /** 单次 rerank 的最大文档数（百炼限制保护，超出部分不精排、原序追加） */
    private static final int MAX_DOCS = 100;

    public RerankService(RestTemplate restTemplate, BailianConfig config,
                         UsageStatService usageStat, MetricsConfig.RagMetrics ragMetrics) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.usageStat = usageStat;
        this.ragMetrics = ragMetrics;
    }

    public boolean isEnabled() { return enabled; }
    public int getTopN() { return topN; }
    public String getEndpoint() { return endpoint; }

    /** 实际使用的精排密钥：rerank 专用 > embedding > 共享 */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return config.getEmbeddingApiKey();
    }

    /**
     * 对召回候选做精排并返回截断后的结果。
     *
     * @param query      查询文本
     * @param candidates 召回阶段返回的候选（已按召回相关性降序）
     * @return 精排后截断到 top-n 的候选；未开启或无需精排时原样返回
     */
    /** 精排入口（不归属具体 KB，记为租户级用量） */
    public List<RetrieveChunk> rerank(String query, List<RetrieveChunk> candidates) {
        return rerank(query, candidates, null);
    }

    /**
     * 对召回候选做精排并返回截断后的结果。
     * <p>RAG-053：kbId 用于用量按 KB 归因（为 null 表示租户级）。
     */
    public List<RetrieveChunk> rerank(String query, List<RetrieveChunk> candidates, Long kbId) {
        if (!enabled || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        // 候选数本就不超过 top-n，无需精排
        if (candidates.size() <= topN) {
            return candidates;
        }

        // endpoint 未配置（如未填入百炼工作空间 ID）时直接降级，避免无意义的错误调用
        if (endpoint == null || endpoint.isBlank()) {
            return fallback(candidates, "rerank endpoint 未配置");
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

            // 请求体格式随 endpoint 类型自适应：
            //  - MAAS text-rerank 服务（路径含 /services/rerank/，如 gte-rerank-v2）：嵌套体
            //      { model, input: { query, documents }, parameters: { top_n } }
            //  - 兼容模式（compatible-api/v1/reranks，如 qwen3-rerank）：扁平体
            //      { model, query, documents, top_n, instruct }
            Map<String, Object> requestBody;
            if (endpoint.contains("/services/rerank/")) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("query", query);
                input.put("documents", docs);
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("top_n", toRerank.size());
                requestBody = new LinkedHashMap<>();
                requestBody.put("model", model);
                requestBody.put("input", input);
                requestBody.put("parameters", parameters);
            } else {
                requestBody = new LinkedHashMap<>();
                requestBody.put("model", model);
                requestBody.put("query", query);
                requestBody.put("documents", docs);
                requestBody.put("top_n", toRerank.size());
                requestBody.put("instruct", RETRIEVE_INSTRUCT);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resolveApiKey());
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("调用百炼 Rerank API，model={}，候选数={}", model, toRerank.size());
            ResponseEntity<Map> response = HttpRetryUtil.executeWithRetry(
                    () -> restTemplate.postForEntity(endpoint, request, Map.class),
                    3, 500, log, "Rerank API");
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return fallback(candidates, "API 返回为空");
            }

            // 兼容两种响应结构：qwen3-rerank 返回顶层 results；gte-rerank-v2 返回 output.results
            List<Map<String, Object>> results;
            if (body.containsKey("output")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) body.get("output");
                results = (List<Map<String, Object>>) output.get("results");
            } else if (body.containsKey("results")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> topResults = (List<Map<String, Object>>) body.get("results");
                results = topResults;
            } else {
                log.error("Rerank API 返回异常: {}", body);
                return fallback(candidates, "API 返回无 results");
            }
            if (results == null || results.isEmpty()) {
                return fallback(candidates, "results 为空");
            }

            // index -> 相关性分数（兼容 relevance_score / score 两种字段名）
            Map<Integer, Double> scoreByIndex = new HashMap<>();
            for (Map<String, Object> r : results) {
                int idx = ((Number) r.get("index")).intValue();
                Object s = r.get("relevance_score");
                if (s == null) {
                    s = r.get("score");
                }
                if (s == null) {
                    log.error("Rerank 结果缺少分数字段: {}", r);
                    return fallback(candidates, "results 缺少分数字段");
                }
                double sc = ((Number) s).doubleValue();
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
            int tokens = estimateRerankTokens(query, toRerank);
            ragMetrics.recordRerankTokens(tokens);
            // RAG-053：rerank 用量按 KB 归因（kbId 为 null 时记为租户级）
            usageStat.recordRerank(TenantContext.get(), kbId, maskKey(resolveApiKey()), tokens);
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

    // ===== RAG-053：rerank 用量埋点辅助 =====

    /** 密钥指纹：仅保留角色 + 末 4 位，不落明文 */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "rerank:none";
        }
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "rerank:****" + tail;
    }

    /** rerank token 估算：query + 候选文档字符数 /4（rerank 接口通常不回传 usage） */
    private static int estimateRerankTokens(String query, List<RetrieveChunk> docs) {
        int chars = query == null ? 0 : query.length();
        if (docs != null) {
            for (RetrieveChunk c : docs) {
                String content = c.getContent();
                if (content != null) {
                    chars += content.length();
                }
            }
        }
        return Math.max(1, chars / 4);
    }
}
