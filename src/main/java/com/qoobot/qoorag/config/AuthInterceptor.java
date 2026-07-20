package com.qoobot.qoorag.config;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 统一鉴权拦截器：会话令牌（/api 除 /api/v1）与 API Key（/api/v1） */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String auth = request.getHeader("Authorization");

        // 对外 API（4.10）：使用 API Key 鉴权
        if (path.startsWith("/api/v1/")) {
            if (auth == null || !auth.startsWith("Bearer ")) {
                return unauthorized(response, "缺少 API Key");
            }
            SessionInfo info = authService.validateApiKey(auth.substring(7).trim());
            if (info == null) {
                return unauthorized(response, "API Key 无效或已吊销");
            }
            SecurityContext.set(info);
            return true;
        }

        // 管理类接口：使用会话令牌
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(response, "未登录或令牌缺失");
        }
        SessionInfo info = authService.validateSession(auth.substring(7).trim());
        if (info == null) {
            return unauthorized(response, "令牌无效或已过期");
        }
        SecurityContext.set(info);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SecurityContext.clear();
    }

    private boolean unauthorized(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + msg + "\"}");
        return false;
    }
}
