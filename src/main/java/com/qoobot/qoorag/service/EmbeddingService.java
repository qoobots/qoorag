package com.qoobot.qoorag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.common.HttpRetryUtil;
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

/**
 * 向量化服务：调用百炼 Embedding API（OpenAI 兼容模式）
 * 维度由 embedding 模型决定（text-embedding-v3/v4 = 1024 维，v1/v2/async-v2 = 1536 维），
 * 启动期由 VectorDimensionReconciler 对齐 vector_data.embedding 列维度。
 * <p>
 * RAG-050：按 {@code sha256(text|model)} 对 embedding 结果做 Redis 缓存（TTL 可配），
 * 相同文本（重复文档入库 / 相同 query）直接命中，跳过外部 API 调用，降本且提速。
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

    public EmbeddingService(RestTemplate restTemplate,
                            BailianConfig config,
                            org.springframework.data.redis.core.StringRedisTemplate redis,
                            @Value("${qoorag.embedding.cache-enabled:true}") boolean cacheEnabled,
                            @Value("${qoorag.embedding.cache-ttl-minutes:1440}") int cacheTtlMinutes) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.redis = redis;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtl = Duration.ofMinutes(cacheTtlMinutes);
    }

    /** 单文本向量化 */
    public float[] embed(String text) {
        float[][] result = embedBatch(Collections.singletonList(text));
        return result.length > 0 ? result[0] : new float[0];
    }

    /**
     * 批量向量化（一次 API 调用），自动按 batchSize 分批。
     * RAG-050：先逐条查缓存，仅对未命中部分调用 API，命中结果写回缓存。
     */
    public float[][] embedBatch(List<String> texts) {
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
            int batchSize = Math.min(config.getEmbeddingBatchSize(), 25);
            List<float[]> missVectors = new ArrayList<>();
            for (int s = 0; s < missIdx.size(); s += batchSize) {
                int e = Math.min(s + batchSize, missIdx.size());
                List<String> batch = new ArrayList<>();
                for (int k = s; k < e; k++) {
                    batch.add(texts.get(missIdx.get(k)));
                }
                float[][] vecs = callApi(batch, model);
                for (float[] v : vecs) {
                    missVectors.add(v);
                }
            }
            // 回填结果数组并写缓存
            for (int k = 0; k < missIdx.size(); k++) {
                int idx = missIdx.get(k);
                float[] v = missVectors.get(k);
                result[idx] = v;
                if (cacheEnabled) {
                    putToCache(texts.get(idx), model, v);
                }
            }
        }

        if (cacheEnabled) {
            log.info("Embedding 缓存 命中={}/{}", hits, n);
        }
        return result;
    }

    /** 调用百炼 Embedding API（一次批量请求），返回与输入顺序一致的向量数组 */
    private float[][] callApi(List<String> batch, String model) {
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
            return vecs;
        } catch (Exception e) {
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
}
