package com.qoobot.qoorag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qoobot.qoorag.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * RAG-051：查询结果缓存。
 * <p>
 * 相同 KB + 相同 query + 相同策略参数（topK / rerank / rewrite / mmr 开关）的问答结果缓存，
 * 命中时跳过检索与 LLM 调用，直接返回历史答案（热点问题成本直降）。
 * <p>
 * 说明：
 * ① 多轮会话（依赖历史上下文）不缓存——答案不具备可复用性，仅缓存无历史的单轮请求；
 * ② 文档变更时调用 {@link #invalidateKb(Long)} 主动失效该 KB 全部缓存，TTL 兜底防止长期脏读；
 * ③ 缓存值为完整 {@link ChatResponse}（answer/sources/citations/confidence），读写均 fail-soft。
 */
@Service
public class ChatCacheService {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheService.class);
    private static final String PREFIX = "qoorag:chatcache:kb:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    public ChatCacheService(StringRedisTemplate redis,
                            @Value("${qoorag.chat.cache-enabled:true}") boolean enabled,
                            @Value("${qoorag.chat.cache-ttl-minutes:5}") int ttlMinutes) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 构造缓存键：KB + query + topK + 策略签名（rerank/rewrite/mmr 开关） */
    public String key(Long kbId, String query, int topK, String paramSig) {
        String scope = (kbId == null ? "null" : kbId.toString());
        String raw = scope + "|" + topK + "|" + (paramSig == null ? "" : paramSig) + "|" + (query == null ? "" : query);
        return PREFIX + scope + ":" + sha256(raw);
    }

    public ChatResponse get(String key) {
        if (!enabled) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            return MAPPER.readValue(raw, ChatResponse.class);
        } catch (Exception e) {
            return null; // 缓存读取失败不影响主链路
        }
    }

    public void put(String key, ChatResponse resp) {
        if (!enabled || resp == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, MAPPER.writeValueAsString(resp), ttl);
        } catch (Exception e) {
            // 缓存写入失败不影响主链路
        }
    }

    /** RAG-051③：文档变更时主动失效该 KB 的全部查询结果缓存（TTL 兜底防脏读） */
    public void invalidateKb(Long kbId) {
        if (kbId == null) {
            return;
        }
        try {
            String pattern = PREFIX + kbId + ":*";
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("RAG-051 失效 KB={} 查询结果缓存 {} 条", kbId, keys.size());
            }
        } catch (Exception ignored) {
            // 失效失败不影响主链路，TTL 兜底
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
