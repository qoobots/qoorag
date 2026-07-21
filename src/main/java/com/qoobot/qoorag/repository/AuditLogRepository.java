package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    /** 物理删除创建时间早于 cutoff 的审计日志（#18 留存清理） */
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}
