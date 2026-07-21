package com.qoobot.qoorag.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** API Key 速率限制（4.10）：基于 Redis 的固定窗口计数，按 apiKeyId 维度限流 */
@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    /** 限流窗口（秒），与 ApiKey.rateLimit（次/分钟）对齐 */
    private static final int WINDOW_SECONDS = 60;

    private static final String KEY_PREFIX = "qoorag:ratelimit:";

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param apiKeyId         API Key ID
     * @param rateLimitPerMin  每分钟允许的最大请求数（取自 ApiKey.rateLimit）
     * @return true=放行，false=触发限流
     */
    public boolean tryAcquire(Long apiKeyId, int rateLimitPerMin) {
        if (apiKeyId == null) {
            return true; // 非 API Key 调用不限额
        }
        String key = KEY_PREFIX + apiKeyId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 首个请求设置窗口过期时间
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        int limit = rateLimitPerMin > 0 ? rateLimitPerMin : 60;
        return count == null || count <= limit;
    }
}
