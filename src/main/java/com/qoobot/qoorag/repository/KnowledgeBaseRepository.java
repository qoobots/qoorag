package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    /** 仅查未软删除的（4.11） */
    List<KnowledgeBase> findByTenantIdAndDeletedAtIsNull(Long tenantId);
    List<KnowledgeBase> findByOwnerIdAndDeletedAtIsNull(Long ownerId);
}
