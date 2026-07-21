package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.ApiKey;
import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.entity.User;
import com.qoobot.qoorag.repository.ApiKeyRepository;
import com.qoobot.qoorag.repository.UserRepository;
import com.qoobot.qoorag.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 鉴权服务：会话登录 + API Key 校验（4.4 / 4.10） */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RedisTemplate<String, SessionInfo> sessionRedisTemplate;
    private final AuditService auditService;

    /** 会话令牌 Redis key 前缀 */
    private static final String SESSION_KEY_PREFIX = "qoorag:session:";

    /** 会话 TTL（分钟），默认 120；可通过 qoorag.security.session-ttl-minutes 配置 */
    @Value("${qoorag.security.session-ttl-minutes:120}")
    private long sessionTtlMinutes;

    public AuthService(UserRepository userRepository, ApiKeyRepository apiKeyRepository,
                       BCryptPasswordEncoder passwordEncoder,
                       RedisTemplate<String, SessionInfo> sessionRedisTemplate,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRedisTemplate = sessionRedisTemplate;
        this.auditService = auditService;
    }

    public SessionInfo login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED, "用户不存在"));
        if (!"ACTIVE".equals(user.getStatus()) || user.getDeletedAt() != null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "账号已被停用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "密码错误");
        }
        SessionInfo info = new SessionInfo();
        info.userId = user.getId();
        info.tenantId = user.getTenantId();
        info.roles = user.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
        info.isApiKey = false;
        String token = UUID.randomUUID().toString();
        info.token = token;
        sessionRedisTemplate.opsForValue().set(keyOf(token), info, sessionTtlMinutes, TimeUnit.MINUTES);
        auditService.log("LOGIN", "User", String.valueOf(user.getId()), null,
                "login success", info.tenantId, info.userId);
        return info;
    }

    public void logout(String token) {
        SessionInfo info = sessionRedisTemplate.opsForValue().get(keyOf(token));
        Long tenantId = info != null ? info.tenantId : null;
        Long actorId = info != null ? info.userId : null;
        sessionRedisTemplate.delete(keyOf(token));
        auditService.log("LOGOUT", "User", actorId != null ? String.valueOf(actorId) : null,
                null, "logout", tenantId, actorId);
    }

    public SessionInfo validateSession(String token) {
        SessionInfo info = sessionRedisTemplate.opsForValue().get(keyOf(token));
        if (info != null) {
            // 访问续期：每次请求刷新 TTL，实现滑动过期
            sessionRedisTemplate.expire(keyOf(token), sessionTtlMinutes, TimeUnit.MINUTES);
        }
        return info;
    }

    /** 校验 API Key（4.10）：SHA-256 比对 key_hash */
    public SessionInfo validateApiKey(String rawKey) {
        ApiKey key = apiKeyRepository.findByKeyHashAndStatusAndDeletedAtIsNull(hash(rawKey), "ACTIVE")
                .orElse(null);
        if (key == null) {
            return null;
        }
        SessionInfo info = new SessionInfo();
        info.apiKeyId = key.getId();
        info.apiKeyRateLimit = key.getRateLimit();
        info.kbId = key.getKbId();
        info.tenantId = key.getTenantId();
        info.isApiKey = true;
        return info;
    }

    /** 生成 API Key 明文（仅展示一次）与哈希（存储） */
    public ApiKeyMaterial generateApiKey() {
        String raw = "qk_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().substring(0, 8);
        return new ApiKeyMaterial(raw, hash(raw));
    }

    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private String keyOf(String token) {
        return SESSION_KEY_PREFIX + token;
    }

    public record ApiKeyMaterial(String rawKey, String keyHash) {}
}
