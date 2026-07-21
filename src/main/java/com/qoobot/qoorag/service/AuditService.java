package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.AuditLog;
import com.qoobot.qoorag.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
        SessionInfoHolder holder = SessionInfoHolder.current();
        Long tenantId = holder != null ? holder.tenantId() : null;
        Long actorId = holder != null ? holder.actorId() : null;
        log(action, objectType, objectId, beforeValue, afterValue, tenantId, actorId);
    }

    /** 显式指定租户/操作人（用于登录等 SecurityContext 尚未建立的场景） */
    public void log(String action, String objectType, String objectId,
                    String beforeValue, String afterValue, Long tenantId, Long actorId) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActorId(actorId);
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

    /** 审计日志分页查询（租户隔离）：按动作/对象类型/时间范围可选过滤 */
    public Page<AuditLog> query(Long tenantId, String action, String objectType,
                                LocalDateTime start, LocalDateTime end, Pageable pageable) {
        Specification<AuditLog> spec = (root, cq, cb) -> {
            Predicate p = cb.equal(root.get("tenantId"), tenantId);
            if (action != null && !action.isBlank()) {
                p = cb.and(p, cb.equal(root.get("action"), action));
            }
            if (objectType != null && !objectType.isBlank()) {
                p = cb.and(p, cb.equal(root.get("objectType"), objectType));
            }
            if (start != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("createdAt"), end));
            }
            return p;
        };
        return auditLogRepository.findAll(spec, pageable);
    }
}
