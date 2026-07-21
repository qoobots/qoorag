package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.dto.ResourcePoolItem;
import com.qoobot.qoorag.entity.ResourcePoolConfig;
import com.qoobot.qoorag.repository.ResourcePoolConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 资源池配置服务（#12）：平台级全局配置，存 DB 表。
 * 作为用户对 application.yml 默认值的「显式覆盖层」——config_value 为空时不覆盖 yml。
 */
@Service
public class ResourcePoolService {

    private final ResourcePoolConfigRepository repository;
    private final AuditService auditService;

    /** 脱敏占位符：UI 展示与审计落库均使用，避免泄露明文密钥 */
    public static final String MASK = "******";

    public ResourcePoolService(ResourcePoolConfigRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** 按 category 分组返回（masked 项脱敏展示） */
    public Map<String, List<ResourcePoolItem>> listGrouped() {
        Map<String, List<ResourcePoolItem>> result = new LinkedHashMap<>();
        List<ResourcePoolConfig> all = new ArrayList<>(repository.findAll());
        all.sort(Comparator.comparing(ResourcePoolConfig::getCategory)
                .thenComparing(ResourcePoolConfig::getConfigKey));
        for (ResourcePoolConfig c : all) {
            result.computeIfAbsent(c.getCategory(), k -> new ArrayList<>()).add(toItem(c));
        }
        return result;
    }

    /** 供启动加载器取真实值（不脱敏）；无配置返回 null */
    public String getRawValue(String category, String configKey) {
        return repository.findByCategoryAndConfigKey(category, configKey)
                .map(ResourcePoolConfig::getConfigValue)
                .orElse(null);
    }

    @Transactional
    public ResourcePoolItem save(String category, String configKey, String configValue, String description) {
        if (category == null || category.isBlank() || configKey == null || configKey.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "category 与 configKey 不能为空");
        }
        Optional<ResourcePoolConfig> existingOpt = repository.findByCategoryAndConfigKey(category, configKey);
        ResourcePoolConfig entity;
        String before = null;
        if (existingOpt.isPresent()) {
            entity = existingOpt.get();
            before = maskIfNeeded(entity);
            // 空值或占位符不覆盖原值（避免误清空 apiKey 等敏感项）
            if (isEffective(configValue)) {
                entity.setConfigValue(configValue);
            }
            if (description != null && !description.isBlank()) {
                entity.setDescription(description);
            }
            entity.setUpdatedAt(LocalDateTime.now());
        } else {
            entity = new ResourcePoolConfig();
            entity.setCategory(category);
            entity.setConfigKey(configKey);
            entity.setMasked(false);
            entity.setConfigValue(isEffective(configValue) ? configValue : "");
            entity.setDescription(description);
            entity.setUpdatedAt(LocalDateTime.now());
        }
        ResourcePoolConfig saved = repository.save(entity);
        String after = maskIfNeeded(saved);
        auditService.log("UPDATE", "RESOURCE_POOL", category + ":" + configKey, before, after);
        return toItem(saved);
    }

    private boolean isEffective(String v) {
        return v != null && !v.isEmpty() && !MASK.equals(v);
    }

    private ResourcePoolItem toItem(ResourcePoolConfig c) {
        String display = (c.isMasked() && c.getConfigValue() != null && !c.getConfigValue().isEmpty())
                ? MASK : c.getConfigValue();
        return ResourcePoolItem.of(c.getCategory(), c.getConfigKey(), display, c.isMasked(), c.getDescription());
    }

    private String maskIfNeeded(ResourcePoolConfig c) {
        if (c.isMasked() && c.getConfigValue() != null && !c.getConfigValue().isEmpty()) {
            return MASK;
        }
        return c.getConfigValue();
    }
}
