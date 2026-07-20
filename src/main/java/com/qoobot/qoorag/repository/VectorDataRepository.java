package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.VectorData;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VectorDataRepository extends JpaRepository<VectorData, Long> {
    List<VectorData> findByKbId(Long kbId);
    void deleteByKbId(Long kbId);
}
