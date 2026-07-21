package com.qoobot.qoorag.service;

import com.qoobot.qoorag.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** AuditRetentionScheduler 单测（#18 留存清理） */
@ExtendWith(MockitoExtension.class)
class AuditRetentionSchedulerTest {

    @Mock
    AuditLogRepository auditLogRepository;

    @Test
    void purgeExpired_deletes_old_records() {
        when(auditLogRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(3L);
        AuditRetentionScheduler scheduler = new AuditRetentionScheduler(auditLogRepository);
        scheduler.purgeExpired();
        verify(auditLogRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    @Test
    void purgeExpired_no_records_is_noop() {
        when(auditLogRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(0L);
        AuditRetentionScheduler scheduler = new AuditRetentionScheduler(auditLogRepository);
        scheduler.purgeExpired();
        verify(auditLogRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
