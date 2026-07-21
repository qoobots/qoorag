package com.qoobot.qoorag.dto;

/** 资源池配置项视图（#12）：masked 项在前端脱敏展示 */
public class ResourcePoolItem {

    private String category;
    private String configKey;
    private String configValue;   // 脱敏后的展示值
    private boolean masked;
    private String description;

    public static ResourcePoolItem of(String category, String configKey,
                                      String configValue, boolean masked, String description) {
        ResourcePoolItem i = new ResourcePoolItem();
        i.category = category;
        i.configKey = configKey;
        i.configValue = configValue;
        i.masked = masked;
        i.description = description;
        return i;
    }

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
}
