package com.qoobot.qoorag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.config.MetricsConfig;
import com.qoobot.qoorag.common.HttpRetryUtil;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 向量化服务：调用百炼 Embedding API（OpenAI 兼容模式）
 * 维度由 embedding 模型决定（text-embedding-v3/v4 = 1024 维，v1/v2/async-v2 = 1536 维），
 * 启动期由 VectorDimensionReconciler 对齐 vector_data.embedding 列维度。
 * <p>
 * RAG-050：按 {@code sha256(text|model)} 对 embedding 结果做 Redis 缓存（TTL 可配），
 * 相同文本（重复文档入库 / 相同 query）直接命中，跳过外部 API 调用，降本且提速。
 * <p>
 * RAG-052：批量大小与并发度可配。批量大小取自 {@code BailianConfig.embeddingBatchSize}（不再硬限 25）；
 * 多个批次通过固定大小线程池并发调用 API（{@code qoorag.embedding.concurrency}），提升入库吞吐。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final String CACHE_PREFIX = "qoorag:emb:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final BailianConfig config;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final boolean cacheEnabled;
    private final Duration cacheTtl;
    private final int concurrency;
    private final ExecutorService executor;
    private final MetricsConfig.RagMetrics ragMetrics;
    private final UsageStatService usageStat;

    public EmbeddingService(RestTemplate restTemplate,
                            BailianConfig config,
                            org.springframework.data.redis.core.StringRedisTemplate redis,
                            MetricsConfig.RagMetrics ragMetrics,
                            UsageStatService usageStat,
                            @Value("${qoorag.embedding.cache-enabled:true}") boolean cacheEnabled,
                            @Value("${qoorag.embedding.cache-ttl-minutes:1440}") int cacheTtlMinutes,
                            @Value("${qoorag.embedding.concurrency:4}") int concurrency) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.redis = redis;
        this.ragMetrics = ragMetrics;
        this.usageStat = usageStat;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
        this.concurrency = Math.max(1, concurrency);
        // RAG-052：并发 API 调用线程池（与 embedding 调用并发度一致）
        this.executor = Executors.newFixedThreadPool(this.concurrency,
                r -> {
                    Thread t = new Thread(r, "embedding-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 单文本向量化（不归属具体 KB） */
    public float[] embed(String text) {
        return embed(text, null);
    }

    /** 单文本向量化（归属到指定 KB 用于用量统计） */
    public float[] embed(String text, Long kbId) {
        float[][] result = embedBatch(Collections.singletonList(text), kbId);
        return result.length > 0 ? result[0] : new float[0];
    }

    /**
     * 批量向量化（按批次并发调用 API），自动按 batchSize 分批。
     * RAG-050：先逐条查缓存，仅对未命中部分调用 API，命中结果写回缓存。
     */
    public float[][] embedBatch(List<String> texts) {
        return embedBatch(texts, null);
    }

    /**
     * 批量向量化（按批次并发调用 API），自动按 batchSize 分批。
     * RAG-050：先逐条查缓存，仅对未命中部分调用 API，命中结果写回缓存。
     * RAG-053：传入 kbId 用于用量按 KB 归因（为 null 表示租户级）。
     */
    public float[][] embedBatch(List<String> texts, Long kbId) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        String model = config.getEmbeddingModel();
        int n = texts.size();
        float[][] result = new float[n][];
        List<Integer> missIdx = new ArrayList<>();

        int hits = 0;
        for (int i = 0; i < n; i++) {
            float[] cached = cacheEnabled ? getFromCache(texts.get(i), model) : null;
            if (cached != null) {
                result[i] = cached;
                hits++;
            } else {
                missIdx.add(i);
            }
        }

        if (!missIdx.isEmpty()) {
            int batchSize = Math.max(1, config.getEmbeddingBatchSize());
            // 切分为批次（顺序保持与 missIdx 一致）
            List<List<String>> batches = new ArrayList<>();
            for (int s = 0; s < missIdx.size(); s += batchSize) {
                int e = Math.min(s + batchSize, missIdx.size());
                List<String> batch = new ArrayList<>();
                for (int k = s; k < e; k++) {
                    batch.add(texts.get(missIdx.get(k)));
                }
                batches.add(batch);
            }

            // RAG-052：并发提交各批次 API 调用（callApi 返回 float[][]，每批多向量）
            List<Future<float[][]>> futures = new ArrayList<>(batches.size());
            for (List<String> batch : batches) {
                futures.add(executor.submit(() -> callApi(batch, model, kbId)));
            }

            // 顺序回填（futures 与 missIdx 一一对应），保证 result 与输入顺序一致
            int pos = 0;
            for (int b = 0; b < batches.size(); b++) {
                float[][] vecs;
                try {
                    vecs = futures.get(b).get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Embedding 并发调用被中断", ie);
                } catch (ExecutionException ee) {
                    throw new RuntimeException("Embedding 批量调用失败: " + ee.getMessage(), ee);
                }
                for (int j = 0; j < vecs.length; j++) {
                    int idx = missIdx.get(pos + j);
                    result[idx] = vecs[j];
                    if (cacheEnabled) {
                        putToCache(texts.get(idx), model, vecs[j]);
                    }
                }
                pos += vecs.length;
            }
        }

        if (cacheEnabled) {
            log.info("Embedding 缓存 命中={}/{}，并发={}", hits, n, concurrency);
        }
        return result;
    }

    /** 调用百炼 Embedding API（一次批量请求），返回与输入顺序一致的向量数组 */
    private float[][] callApi(List<String> batch, String model, Long kbId) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", batch);
        requestBody.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getEmbeddingApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        String url = config.getEmbeddingBaseUrl() + "/embeddings";

        try {
            log.info("调用 Embedding API，model={}，批量={}", model, batch.size());
            ResponseEntity<Map> response = HttpRetryUtil.executeWithRetry(
                    () -> restTemplate.postForEntity(url, request, Map.class),
                    3, 500, log, "Embedding API");
            Map<String, Object> body = response.getBody();

            if (body == null || !body.containsKey("data")) {
                log.error("Embedding API 返回异常: {}", body);
                throw new RuntimeException("Embedding API 返回为空");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
            float[][] vecs = new float[data.size()][];
            for (int i = 0; i < data.size(); i++) {
                @SuppressWarnings("unchecked")
                List<Double> embeddingList = (List<Double>) data.get(i).get("embedding");
                float[] vec = new float[embeddingList.size()];
                for (int j = 0; j < embeddingList.size(); j++) {
                    vec[j] = embeddingList.get(j).floatValue();
                }
                vecs[i] = vec;
            }
            int tokens = parseOrEstimateTokens(body, batch);
            ragMetrics.recordEmbedding(System.currentTimeMillis() - t0, true);
            ragMetrics.recordEmbeddingTokens(tokens);
            // RAG-053：embedding 用量按 KB 归因（kbId 为 null 时记为租户级）
            usageStat.recordEmbedding(TenantContext.get(), kbId, maskKey(config.getEmbeddingApiKey()), tokens);
            return vecs;
        } catch (Exception e) {
            ragMetrics.recordEmbedding(System.currentTimeMillis() - t0, false);
            log.error("Embedding API 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
    }

    // ===== RAG-050：embedding 缓存（Redis）=====

    private float[] getFromCache(String text, String model) {
        try {
            String raw = redis.opsForValue().get(cacheKey(text, model));
            if (raw == null) {
                return null;
            }
            return MAPPER.readValue(raw, float[].class);
        } catch (Exception e) {
            return null; // 缓存读取失败不影响主链路
        }
    }

    private void putToCache(String text, String model, float[] vec) {
        try {
            redis.opsForValue().set(cacheKey(text, model), MAPPER.writeValueAsString(vec), cacheTtl);
        } catch (Exception e) {
            // 缓存写入失败不影响主链路
        }
    }

    private String cacheKey(String text, String model) {
        return CACHE_PREFIX + sha256(text + "|" + model);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ===== RAG-053：embedding 用量埋点辅助 =====

    /** 密钥指纹：仅保留角色 + 末 4 位，不落明文 */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "embed:none";
        }
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "embed:****" + tail;
    }

    /** 优先取 API 返回的 usage.total_tokens，否则按字符数 /4 估算 */
    private static int parseOrEstimateTokens(Map<String, Object> body, List<String> batch) {
        if (body != null && body.containsKey("usage")) {
            Object u = body.get("usage");
            if (u instanceof Map) {
                Object t = ((Map<?, ?>) u).get("total_tokens");
                if (t instanceof Number) {
                    return ((Number) t).intValue();
                }
            }
        }
        int chars = 0;
        for (String s : batch) {
            if (s != null) {
                chars += s.length();
            }
        }
        return Math.max(1, chars / 4);
    }

    /** 优雅关闭并发线程池（RAG-052） */
    @PreDestroy
    public void destroy() {
        try {
            executor.shutdownNow();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }
}
