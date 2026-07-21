package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.common.SessionInfo;
import com.qoobot.qoorag.entity.AuditLog;
import com.qoobot.qoorag.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** AuditService 单元测试：写入上下文捕获 + 分页查询委托 */
@ExtendWith(MockitoExtension.class)
public class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditLogRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void log_captures_security_context() {
        SessionInfo info = new SessionInfo();
        info.tenantId = 5L;
        info.userId = 9L;
        SecurityContext.set(info);

        service.log("CREATE", "KB", "1", null, null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertEquals(5L, cap.getValue().getTenantId());
        assertEquals(9L, cap.getValue().getActorId());
        assertEquals("CREATE", cap.getValue().getAction());
    }

    @Test
    void query_delegates_to_repository_with_specification() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AuditLog> page = new PageImpl<>(List.of());
        when(auditLogRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<AuditLog> result = service.query(7L, "CREATE", null, null, null, pageable);

        assertSame(page, result);
        verify(auditLogRepository).findAll(any(Specification.class), eq(pageable));
    }
}
