package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 向量化服务：调用百炼 Embedding API（OpenAI 兼容模式）
 * 维度由 embedding 模型决定（text-embedding-v3/v4 = 1024 维，v1/v2/async-v2 = 1536 维），
 * 启动期由 VectorDimensionReconciler 对齐 vector_data.embedding 列维度。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestTemplate restTemplate;
    private final BailianConfig config;

    public EmbeddingService(RestTemplate restTemplate, BailianConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /** 单文本向量化 */
    public float[] embed(String text) {
        float[][] result = embedBatch(Collections.singletonList(text));
        return result.length > 0 ? result[0] : new float[0];
    }

    /** 批量向量化（一次 API 调用），自动按 batchSize 分批 */
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        int batchSize = Math.min(config.getEmbeddingBatchSize(), 25);
        List<float[]> allVectors = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getEmbeddingModel());
            requestBody.put("input", batch);
            requestBody.put("encoding_format", "float");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getApiKey());

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            String url = config.getBaseUrl() + "/embeddings";

            try {
                log.info("调用百炼 Embedding API，批量={}/{}", batch.size(), texts.size());
                ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
                Map<String, Object> body = response.getBody();

                if (body == null || !body.containsKey("data")) {
                    log.error("Embedding API 返回异常: {}", body);
                    throw new RuntimeException("Embedding API 返回为空");
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                for (Map<String, Object> item : data) {
                    @SuppressWarnings("unchecked")
                    List<Double> embeddingList = (List<Double>) item.get("embedding");
                    float[] vec = new float[embeddingList.size()];
                    for (int j = 0; j < embeddingList.size(); j++) {
                        vec[j] = embeddingList.get(j).floatValue();
                    }
                    allVectors.add(vec);
                }
            } catch (Exception e) {
                log.error("Embedding API 调用失败: {}", e.getMessage(), e);
                throw new RuntimeException("向量化失败: " + e.getMessage(), e);
            }
        }

        return allVectors.toArray(new float[0][]);
    }
}
