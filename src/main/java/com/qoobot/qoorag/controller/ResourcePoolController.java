package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.dto.ResourcePoolItem;
import com.qoobot.qoorag.service.ResourcePoolService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 资源池配置管理接口（#12）：仅启动期加载，修改后需重启生效 */
@RestController
@RequestMapping("/api/admin/resource-pool")
public class ResourcePoolController {

    private final ResourcePoolService service;

    public ResourcePoolController(ResourcePoolService service) {
        this.service = service;
    }

    /** 分组返回全部资源池配置（masked 项已脱敏） */
    @GetMapping
    public Result list() {
        return Result.ok(service.listGrouped());
    }

    /** 保存/更新单项配置；空值或 ****** 占位不覆盖原值 */
    @PutMapping
    public Result save(@RequestBody SaveRequest req) {
        if (req == null || req.getCategory() == null || req.getConfigKey() == null
                || req.getCategory().isBlank() || req.getConfigKey().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "category 与 configKey 必填");
        }
        ResourcePoolItem item = service.save(req.getCategory(), req.getConfigKey(),
                req.getConfigValue(), req.getDescription());
        return Result.ok(item);
    }

    /** 保存请求体 */
    public static class SaveRequest {
        private String category;
        private String configKey;
        private String configValue;
        private String description;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getConfigKey() { return configKey; }
        public void setConfigKey(String configKey) { this.configKey = configKey; }
        public String getConfigValue() { return configValue; }
        public void setConfigValue(String configValue) { this.configValue = configValue; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
