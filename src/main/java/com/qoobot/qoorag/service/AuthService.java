package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.ApiKey;
import com.qoobot.qoorag.entity.Role;
import com.qoobot.qoorag.entity.User;
import com.qoobot.qoorag.repository.ApiKeyRepository;
import com.qoobot.qoorag.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 鉴权服务：会话登录 + API Key 校验（4.4 / 4.10） */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 会话令牌 -> 会话信息（内存态；生产应改用 Redis 等） */
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository, ApiKeyRepository apiKeyRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public SessionInfo login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (!"ACTIVE".equals(user.getStatus()) || user.getDeletedAt() != null) {
            throw new RuntimeException("账号已被停用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }
        SessionInfo info = new SessionInfo();
        info.userId = user.getId();
        info.tenantId = user.getTenantId();
        info.roles = user.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
        info.isApiKey = false;
        String token = UUID.randomUUID().toString();
        info.token = token;
        sessions.put(token, info);
        return info;
    }

    public void logout(String token) {
        sessions.remove(token);
    }

    public SessionInfo validateSession(String token) {
        return sessions.get(token);
    }

    /** 校验 API Key（4.10）：SHA-256 比对 key_hash */
    public SessionInfo validateApiKey(String rawKey) {
        ApiKey key = apiKeyRepository.findByKeyHashAndStatusAndDeletedAtIsNull(hash(rawKey), "ACTIVE")
                .orElse(null);
        if (key == null) {
            return null;
        }
        SessionInfo info = new SessionInfo();
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

    public record ApiKeyMaterial(String rawKey, String keyHash) {}
}
