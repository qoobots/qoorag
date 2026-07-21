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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AuthService 单元测试：登录/登出/会话校验/API Key 校验/Key 生成（Redis 与仓库均 mock） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock ApiKeyRepository apiKeyRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock RedisTemplate<String, SessionInfo> redisTemplate;
    @Mock ValueOperations<String, SessionInfo> valueOps;
    @Mock AuditService auditService;

    AuthService authService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        authService = new AuthService(userRepository, apiKeyRepository, passwordEncoder, redisTemplate, auditService);
    }

    private User activeUser() {
        User u = new User();
        u.setId(1L);
        u.setTenantId(7L);
        u.setUsername("admin");
        u.setPassword("ENC");
        u.setStatus("ACTIVE");
        Role r = new Role();
        r.setName("ADMIN");
        u.setRoles(Set.of(r));
        return u;
    }

    @Test
    void login_success() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("pw", "ENC")).thenReturn(true);

        SessionInfo info = authService.login("admin", "pw");

        assertEquals(1L, info.userId);
        assertEquals(7L, info.tenantId);
        assertFalse(info.isApiKey);
        assertNotNull(info.token);
        assertTrue(info.hasRole("ADMIN"));
        verify(valueOps).set(anyString(), any(SessionInfo.class), anyLong(), any());
        verify(auditService).log(eq("LOGIN"), eq("User"), eq("1"), isNull(), eq("login success"), eq(7L), eq(1L));
    }

    @Test
    void login_userNotFound() {
        when(userRepository.findByUsername("x")).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> authService.login("x", "p"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getCode());
    }

    @Test
    void login_disabled() {
        User u = activeUser();
        u.setStatus("DISABLED");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(u));
        BizException ex = assertThrows(BizException.class, () -> authService.login("admin", "pw"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getCode());
    }

    @Test
    void login_wrongPassword() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("pw", "ENC")).thenReturn(false);
        BizException ex = assertThrows(BizException.class, () -> authService.login("admin", "pw"));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getCode());
    }

    @Test
    void logout_deletes_session() {
        SessionInfo info = new SessionInfo();
        info.userId = 1L;
        info.tenantId = 7L;
        when(valueOps.get("qoorag:session:tok")).thenReturn(info);

        authService.logout("tok");

        verify(redisTemplate).delete("qoorag:session:tok");
        verify(auditService).log(eq("LOGOUT"), eq("User"), eq("1"), isNull(), eq("logout"), eq(7L), eq(1L));
    }

    @Test
    void validateSession_renews_ttl() {
        SessionInfo info = new SessionInfo();
        info.userId = 1L;
        when(valueOps.get("qoorag:session:tok")).thenReturn(info);

        SessionInfo result = authService.validateSession("tok");

        assertSame(info, result);
        verify(redisTemplate).expire(eq("qoorag:session:tok"), anyLong(), any());
    }

    @Test
    void validateSession_notFound_no_renew() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertNull(authService.validateSession("tok"));
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void validateApiKey_found() {
        ApiKey k = new ApiKey();
        k.setId(9L);
        k.setKbId(2L);
        k.setTenantId(7L);
        k.setRateLimit(50);
        when(apiKeyRepository.findByKeyHashAndStatusAndDeletedAtIsNull(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(k));

        SessionInfo info = authService.validateApiKey("raw");

        assertEquals(9L, info.apiKeyId);
        assertEquals(2L, info.kbId);
        assertEquals(7L, info.tenantId);
        assertEquals(50, info.apiKeyRateLimit);
        assertTrue(info.isApiKey);
    }

    @Test
    void validateApiKey_notFound() {
        when(apiKeyRepository.findByKeyHashAndStatusAndDeletedAtIsNull(anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        assertNull(authService.validateApiKey("raw"));
    }

    @Test
    void generateApiKey_format_and_hash() {
        AuthService.ApiKeyMaterial m = authService.generateApiKey();
        assertTrue(m.rawKey().startsWith("qk_"));
        assertEquals(AuthService.hash(m.rawKey()), m.keyHash());
    }

    @Test
    void hash_deterministic() {
        assertEquals(AuthService.hash("abc"), AuthService.hash("abc"));
        assertNotEquals(AuthService.hash("abc"), AuthService.hash("abd"));
    }
}
