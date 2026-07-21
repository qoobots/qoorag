package com.qoobot.qoorag.config;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.service.AuthService;
import com.qoobot.qoorag.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AuthInterceptor 单元测试：会话 / API Key 双通道鉴权、限流、上下文写入与清理 */
@ExtendWith(MockitoExtension.class)
public class AuthInterceptorTest {

    @Mock AuthService authService;
    @Mock RateLimitService rateLimitService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(authService, rateLimitService);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
        TenantContext.clear();
    }

    private void mockResponseWriter() throws IOException {
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
    }

    @Test
    void session_missing_header_returns_401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getHeader("Authorization")).thenReturn(null);
        mockResponseWriter();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    void session_invalid_token_returns_401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer tok");
        when(authService.validateSession("tok")).thenReturn(null);
        mockResponseWriter();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    void session_valid_sets_context_and_clears_after() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer tok");
        SessionInfo info = new SessionInfo();
        info.userId = 1L;
        info.tenantId = 7L;
        info.isApiKey = false;
        when(authService.validateSession("tok")).thenReturn(info);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertSame(info, SecurityContext.get());
        assertEquals(7L, TenantContext.get());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(SecurityContext.get());
        assertNull(TenantContext.get());
    }

    @Test
    void apikey_missing_header_returns_401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/retrieve");
        when(request.getHeader("Authorization")).thenReturn(null);
        mockResponseWriter();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    void apikey_invalid_returns_401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/retrieve");
        when(request.getHeader("Authorization")).thenReturn("Bearer key");
        when(authService.validateApiKey("key")).thenReturn(null);
        mockResponseWriter();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    void apikey_valid_and_within_limit_allows() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/retrieve");
        when(request.getHeader("Authorization")).thenReturn("Bearer key");
        SessionInfo info = new SessionInfo();
        info.apiKeyId = 9L;
        info.tenantId = 7L;
        info.kbId = 2L;
        info.isApiKey = true;
        info.apiKeyRateLimit = 10;
        when(authService.validateApiKey("key")).thenReturn(info);
        when(rateLimitService.tryAcquire(9L, 10)).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertSame(info, SecurityContext.get());
        assertEquals(7L, TenantContext.get());
    }

    @Test
    void apikey_rate_limited_returns_429() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/retrieve");
        when(request.getHeader("Authorization")).thenReturn("Bearer key");
        SessionInfo info = new SessionInfo();
        info.apiKeyId = 9L;
        info.tenantId = 7L;
        info.isApiKey = true;
        info.apiKeyRateLimit = 10;
        when(authService.validateApiKey("key")).thenReturn(info);
        when(rateLimitService.tryAcquire(9L, 10)).thenReturn(false);
        mockResponseWriter();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(429);
    }
}
