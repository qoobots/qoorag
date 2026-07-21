package com.qoobot.qoorag.service;

import com.qoobot.qoorag.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计日志留存清理（#18）：超过留存期（默认 180 天 = 6 个月）的审计日志物理删除。
 * 仅影响 {@code audit_log} 表，不影响业务数据。每日 03:10 低峰执行。
 */
@Component
public class AuditRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionScheduler.class);

    private final AuditLogRepository auditLogRepository;

    /** 审计日志留存期（天），取自 qoorag.security.audit-retention-days，默认 180（6 个月） */
    @Value("${qoorag.security.audit-retention-days:180}")
    private int retentionDays;

    public AuditRetentionScheduler(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long removed = auditLogRepository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("审计日志留存清理：删除 {} 条超过 {} 天的记录", removed, retentionDays);
        }
    }
}
