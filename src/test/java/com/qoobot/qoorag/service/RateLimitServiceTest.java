package com.qoobot.qoorag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** RateLimitService 单元测试：基于 Redis 固定窗口限流（StringRedisTemplate mock） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RateLimitServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    RateLimitService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RateLimitService(redisTemplate);
    }

    @Test
    void first_request_sets_window_and_allows() {
        when(valueOps.increment("qoorag:ratelimit:1")).thenReturn(1L);
        assertTrue(service.tryAcquire(1L, 5));
        verify(redisTemplate).expire(eq("qoorag:ratelimit:1"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void within_limit_allows() {
        when(valueOps.increment("qoorag:ratelimit:1")).thenReturn(3L);
        assertTrue(service.tryAcquire(1L, 5));
    }

    @Test
    void over_limit_rejects() {
        when(valueOps.increment("qoorag:ratelimit:1")).thenReturn(6L);
        assertFalse(service.tryAcquire(1L, 5));
    }

    @Test
    void null_apiKey_not_limited() {
        assertTrue(service.tryAcquire(null, 5));
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void nonpositive_rateLimit_uses_default_60() {
        when(valueOps.increment(anyString())).thenReturn(61L);
        assertFalse(service.tryAcquire(1L, 0));
    }
}
