package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.AuditLog;
import com.qoobot.qoorag.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 审计日志写入（4.8） */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(String action, String objectType, String objectId,
                    String beforeValue, String afterValue) {
        AuditLog log = new AuditLog();
        SessionInfoHolder holder = SessionInfoHolder.current();
        if (holder != null) {
            log.setTenantId(holder.tenantId());
            log.setActorId(holder.actorId());
        }
        log.setAction(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setBeforeValue(beforeValue);
        log.setAfterValue(afterValue);
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(log);
    }

    /** 用于在异步/非请求线程传递上下文（骨架简化为从 SecurityContext 取值） */
    static class SessionInfoHolder {
        static SessionInfoHolder current() {
            var info = com.qoobot.qoorag.common.SecurityContext.get();
            if (info == null) {
                return null;
            }
            return new SessionInfoHolder(info.getTenantId(), info.getUserId());
        }

        private final Long tenantId;
        private final Long actorId;

        SessionInfoHolder(Long tenantId, Long actorId) {
            this.tenantId = tenantId;
            this.actorId = actorId;
        }

        Long tenantId() { return tenantId; }
        Long actorId() { return actorId; }
    }
}
