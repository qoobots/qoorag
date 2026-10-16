package com.qoobot.qoorag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG-044：多轮对话会话历史存储（Redis 支撑，按 sessionId 隔离，带 TTL 自动清理）。
 * <p>
 * 历史以 JSON 数组（[{@code role,content}...]）存储，取用时裁剪到最近 {@code maxTurns} 轮，
 * 写入时刷新 TTL；Redis 键过期即实现 ④ 的 TTL 清理，无需额外定时任务。
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private static final String KEY_PREFIX = "qoorag:chat:session:";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final StringRedisTemplate redis;
    private final int maxTurns;
    private final Duration ttl;

    public ChatHistoryService(StringRedisTemplate redis,
                              @Value("${qoorag.chat.session.max-turns:10}") int maxTurns,
                              @Value("${qoorag.chat.session.ttl-minutes:1440}") int ttlMinutes) {
        this.redis = redis;
        this.maxTurns = Math.max(1, maxTurns);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** 单轮对话记录 */
    public record Turn(String role, String content) {}

    /** 取最近 maxTurns 轮历史（按时间正序） */
    public List<Turn> getHistory(String sessionId) {
        try {
            String raw = redis.opsForValue().get(key(sessionId));
            if (raw == null || raw.isBlank()) {
                return new ArrayList<>();
            }
            List<Turn> all = MAPPER.readValue(raw, new TypeReference<List<Turn>>() {});
            return all.size() <= maxTurns
                    ? all
                    : new ArrayList<>(all.subList(all.size() - maxTurns, all.size()));
        } catch (Exception e) {
            log.warn("读取会话历史失败 sessionId={}: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 追加一轮（用户提问 + 助手回答），超出窗口自动截断并刷新 TTL */
    public void append(String sessionId, String userQuery, String assistantAnswer) {
        try {
            List<Turn> turns = getHistory(sessionId);
            turns.add(new Turn("user", userQuery));
            turns.add(new Turn("assistant", assistantAnswer));
            while (turns.size() > maxTurns * 2) {
                turns.remove(0);
            }
            redis.opsForValue().set(key(sessionId), MAPPER.writeValueAsString(turns), ttl);
        } catch (Exception e) {
            log.warn("写入会话历史失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    public void clear(String sessionId) {
        redis.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
