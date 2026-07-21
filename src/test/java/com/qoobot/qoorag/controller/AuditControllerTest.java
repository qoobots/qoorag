package com.qoobot.qoorag.controller;

import com.qoobot.qoorag.common.GlobalExceptionHandler;
import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.AuditLog;
import com.qoobot.qoorag.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AuditController Web 测试：分页查询 / CSV 导出（租户隔离由 SecurityContext 注入） */
public class AuditControllerTest {

    AuditService auditService = mock(AuditService.class);
    AuditController controller = new AuditController(auditService);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setUp() {
        SessionInfo info = new SessionInfo();
        info.tenantId = 7L;
        SecurityContext.set(info);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void list_returns_paged_content() throws Exception {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setTenantId(7L);
        log.setAction("CREATE");
        log.setCreatedAt(LocalDateTime.now());
        Page<AuditLog> page = new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1);
        when(auditService.query(eq(7L), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void export_writes_csv() throws Exception {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setTenantId(7L);
        log.setActorId(9L);
        log.setAction("CREATE");
        log.setObjectType("KB");
        log.setObjectId("1");
        log.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0, 0));
        Page<AuditLog> page = new PageImpl<>(List.of(log));
        when(auditService.query(eq(7L), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        MvcResult res = mockMvc.perform(get("/api/admin/audit/export")).andReturn();
        String csv = res.getResponse().getContentAsString();

        assertTrue(csv.contains("id,tenant_id,actor_id,action,object_type,object_id"), "应包含 CSV 表头");
        assertTrue(csv.contains("CREATE"), "应包含审计动作");
        assertTrue(csv.contains("\"1\""), "应包含 object_id");
    }

    @Test
    void export_masks_pii_in_values() throws Exception {
        AuditLog log = new AuditLog();
        log.setId(2L);
        log.setTenantId(7L);
        log.setAction("CREATE_USER");
        log.setObjectType("User");
        log.setObjectId("5");
        log.setBeforeValue(null);
        log.setAfterValue("手机13812345678，身份证110101199003071234");
        log.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0, 0));
        Page<AuditLog> page = new PageImpl<>(List.of(log));
        when(auditService.query(eq(7L), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        MvcResult res = mockMvc.perform(get("/api/admin/audit/export")).andReturn();
        String csv = res.getResponse().getContentAsString();

        assertTrue(csv.contains("138****5678"), "手机号应脱敏");
        assertTrue(csv.contains("110101********1234"), "身份证应脱敏");
        assertFalse(csv.contains("13812345678"), "原始手机号不应出现");
    }
}
