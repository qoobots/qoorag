package com.qoobot.qoorag.service;

import com.qoobot.qoorag.config.BailianConfig;
import com.qoobot.qoorag.entity.ResourcePoolConfig;
import com.qoobot.qoorag.repository.ResourcePoolConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 资源池启动加载器（#12）：应用启动期从 DB 读取 LLM/Embedding 配置，覆盖 BailianConfig。
 * 仅启动期加载（用户要求）：运行期修改 DB 需重启生效。
 * DB 表缺失或读取异常时优雅降级为 application.yml 默认值，不阻断启动。
 */
@Component
@Order(10)
public class ResourcePoolLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ResourcePoolLoader.class);

    private final ResourcePoolConfigRepository repository;
    private final BailianConfig bailianConfig;

    public ResourcePoolLoader(ResourcePoolConfigRepository repository, BailianConfig bailianConfig) {
        this.repository = repository;
        this.bailianConfig = bailianConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            applyIfPresent("LLM", "base_url", bailianConfig::setBaseUrl);
            applyIfPresent("LLM", "api_key", bailianConfig::setApiKey);
            applyIfPresent("LLM", "chat_model", bailianConfig::setChatModel);
            applyIfPresent("EMBEDDING", "embedding_model", bailianConfig::setEmbeddingModel);
            String batch = getRaw("EMBEDDING", "embedding_batch_size");
            if (batch != null && !batch.isBlank()) {
                try {
                    bailianConfig.setEmbeddingBatchSize(Integer.parseInt(batch.trim()));
                } catch (NumberFormatException e) {
                    log.warn("embedding_batch_size 非整数，忽略: {}", batch);
                }
            }
            log.info("资源池配置启动加载完成（DB 覆盖层已应用，仅启动期生效，修改需重启）");
        } catch (Exception e) {
            log.warn("资源池配置启动加载失败，回退使用 application.yml 默认值: {}", e.getMessage());
        }
    }

    private void applyIfPresent(String category, String key, Consumer<String> setter) {
        String v = getRaw(category, key);
        if (v != null && !v.isBlank()) {
            setter.accept(v.trim());
        }
    }

    private String getRaw(String category, String key) {
        return repository.findByCategoryAndConfigKey(category, key)
                .map(ResourcePoolConfig::getConfigValue)
                .orElse(null);
    }
}
