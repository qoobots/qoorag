package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    /** 仅查未软删除的（4.11） */
    List<KnowledgeBase> findByTenantIdAndDeletedAtIsNull(Long tenantId);
    List<KnowledgeBase> findByOwnerIdAndDeletedAtIsNull(Long ownerId);

    /** 保留期清理：查询已软删除且超过截止时间（deletedAt 不为空且早于 cutoff）的知识库 */
    List<KnowledgeBase> findByDeletedAtIsNotNullAndDeletedAtBefore(LocalDateTime cutoff);
}
