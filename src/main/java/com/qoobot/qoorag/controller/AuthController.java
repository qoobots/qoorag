package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** 认证接口（登录公开，其余需令牌） */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        SessionInfo info = authService.login(username, password);
        Map<String, Object> data = new HashMap<>();
        data.put("token", info.token);
        data.put("userId", info.userId);
        data.put("roles", info.roles);
        return Result.ok(data);
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader("Authorization") String auth) {
        String token = auth.replace("Bearer ", "").trim();
        authService.logout(token);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me() {
        SessionInfo info = SecurityContext.get();
        Map<String, Object> data = new HashMap<>();
        data.put("userId", info.userId);
        data.put("tenantId", info.tenantId);
        data.put("roles", info.roles);
        return Result.ok(data);
    }
}
