package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.SecurityContext;
import com.qoobot.qoorag.entity.KbPermission;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.KbPermissionRepository;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 知识库管理：CRUD + 检索权限 + 软删除清理（4.2 / 4.11） */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final KbPermissionRepository permissionRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorDataRepository vectorDataRepository;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                KbPermissionRepository permissionRepository,
                                DocumentRepository documentRepository,
                                ChunkRepository chunkRepository,
                                VectorDataRepository vectorDataRepository) {
        this.kbRepository = kbRepository;
        this.permissionRepository = permissionRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorDataRepository = vectorDataRepository;
    }

    public List<KnowledgeBase> list() {
        Long tenantId = SecurityContext.get().getTenantId();
        return kbRepository.findByTenantIdAndDeletedAtIsNull(tenantId);
    }

    public KnowledgeBase get(Long id) {
        return kbRepository.findById(id)
                .filter(kb -> kb.getDeletedAt() == null)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));
    }

    @Transactional
    public KnowledgeBase create(String name, String description, String dataClassification) {
        Long tenantId = SecurityContext.get().getTenantId();
        Long ownerId = SecurityContext.get().getUserId();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(tenantId);
        kb.setOwnerId(ownerId);
        kb.setName(name);
        kb.setDescription(description);
        kb.setDataClassification(dataClassification);
        kb.setStatus("ACTIVE");
        kb.setCreatedAt(LocalDateTime.now());
        return kbRepository.save(kb);
    }

    /** 软删除（4.11）：标记 deleted_at，业务数据暂不物理删除，待保留期清理 */
    @Transactional
    public void softDelete(Long id) {
        KnowledgeBase kb = get(id);
        kb.setDeletedAt(LocalDateTime.now());
        kb.setStatus("DELETED");
        kbRepository.save(kb);
    }

    public List<KbPermission> listPermissions(Long kbId) {
        return permissionRepository.findByKbId(kbId);
    }

    @Transactional
    public KbPermission addPermission(Long kbId, String targetType, String targetId) {
        get(kbId); // 校验存在
        KbPermission p = new KbPermission();
        p.setKbId(kbId);
        p.setTenantId(SecurityContext.get().getTenantId());
        p.setTargetType(targetType);
        p.setTargetId(targetId);
        p.setPermission("RETRIEVE");
        p.setCreatedAt(LocalDateTime.now());
        return permissionRepository.save(p);
    }

    @Transactional
    public void removePermission(Long kbId, Long permId) {
        permissionRepository.deleteByKbIdAndId(kbId, permId);
    }

    /** 物理清理（4.11）：删除文档/分块/向量；审计与问答留痕不在此删除 */
    @Transactional
    public void purge(Long kbId) {
        vectorDataRepository.deleteByKbId(kbId);
        chunkRepository.findByKbId(kbId).forEach(c -> chunkRepository.delete(c));
        documentRepository.findByKbIdAndDeletedAtIsNull(kbId).forEach(d -> documentRepository.delete(d));
        kbRepository.deleteById(kbId);
    }
}
