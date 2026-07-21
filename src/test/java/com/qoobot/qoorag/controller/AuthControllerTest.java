package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.Result;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AuthController Web 测试：登录成功 / 失败映射（standalone MockMvc） */
public class AuthControllerTest {

    AuthService authService = mock(AuthService.class);
    AuthController controller = new AuthController(authService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void resetMock() {
        reset(authService);
    }

    @Test
    void login_success_returns_token() throws Exception {
        SessionInfo info = new SessionInfo();
        info.token = "t1";
        info.userId = 1L;
        info.tenantId = 7L;
        info.roles = Set.of("ADMIN");
        info.isApiKey = false;
        when(authService.login("admin", "pw")).thenReturn(info);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("t1"))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    void login_failure_maps_to_40101() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(new BizException(ErrorCode.UNAUTHENTICATED, "用户不存在"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(40101));
    }
}
