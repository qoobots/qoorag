package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.KbPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KbPermissionRepository extends JpaRepository<KbPermission, Long> {
    List<KbPermission> findByKbId(Long kbId);
    void deleteByKbIdAndId(Long kbId, Long id);
}
