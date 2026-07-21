package com.qoobot.qoorag.config;

import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.service.AuthService;
import com.qoobot.qoorag.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 统一鉴权拦截器：会话令牌（/api 除 /api/v1）与 API Key（/api/v1） */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final RateLimitService rateLimitService;

    public AuthInterceptor(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String auth = request.getHeader("Authorization");

        // 对外 API（4.10）：使用 API Key 鉴权
        if (path.startsWith("/api/v1/")) {
            if (auth == null || !auth.startsWith("Bearer ")) {
                return unauthorized(response, ErrorCode.UNAUTHENTICATED, "缺少 API Key");
            }
            SessionInfo info = authService.validateApiKey(auth.substring(7).trim());
            if (info == null) {
                return unauthorized(response, ErrorCode.APIKEY_INVALID, "API Key 无效或已吊销");
            }
            // 速率限制（4.10）：按 apiKeyId 维度，超限返回 42901
            if (!rateLimitService.tryAcquire(info.getApiKeyId(), info.getApiKeyRateLimit())) {
                return writeJson(response, ErrorCode.RATE_LIMITED, "请求过于频繁，已超过速率限制");
            }
            SecurityContext.set(info);
            TenantContext.set(info.getTenantId());
            return true;
        }

        // 管理类接口：使用会话令牌
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(response, ErrorCode.UNAUTHENTICATED, "未登录或令牌缺失");
        }
        SessionInfo info = authService.validateSession(auth.substring(7).trim());
        if (info == null) {
            return unauthorized(response, ErrorCode.UNAUTHENTICATED, "令牌无效或已过期");
        }
        SecurityContext.set(info);
        TenantContext.set(info.getTenantId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SecurityContext.clear();
        TenantContext.clear();
    }

    private boolean unauthorized(HttpServletResponse response, int code, String msg) throws IOException {
        return writeJson(response, code, msg);
    }

    private boolean writeJson(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        if (code == ErrorCode.RATE_LIMITED) {
            response.setStatus(429);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + msg + "\"}");
        return false;
    }
}
