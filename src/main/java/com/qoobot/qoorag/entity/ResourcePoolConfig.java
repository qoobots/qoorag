package com.qoobot.qoorag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 资源池配置（#12）：平台级全局配置，不绑定租户。
 * 作为用户对 application.yml 默认值的「显式覆盖层」——config_value 为空时不覆盖 yml。
 */
@Entity
@Table(name = "resource_pool_config",
        uniqueConstraints = @UniqueConstraint(columnNames = {"category", "config_key"}))
public class ResourcePoolConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "masked", nullable = false)
    private boolean masked = false;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public boolean isMasked() { return masked; }
    public void setMasked(boolean masked) { this.masked = masked; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
