package com.qoobot.qoorag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** 阿里云百炼大模型配置（OpenAI 兼容模式） */
@Configuration
@ConfigurationProperties(prefix = "qoorag.bailian")
public class BailianConfig {

    private String baseUrl;
    private String apiKey;
    private String embeddingModel;
    private String chatModel;
    private int embeddingBatchSize = 25;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int embeddingBatchSize) { this.embeddingBatchSize = embeddingBatchSize; }
}
