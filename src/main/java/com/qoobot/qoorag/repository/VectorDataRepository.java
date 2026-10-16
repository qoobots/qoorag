package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.VectorData;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VectorDataRepository extends JpaRepository<VectorData, Long> {
    List<VectorData> findByKbId(Long kbId);
    void deleteByKbId(Long kbId);

    /** 按 chunk 查向量（Contextual 重建时定位需要重嵌的行） */
    Optional<VectorData> findByChunkId(Long chunkId);

    /** 批量按 chunk 删除向量（RAG-068 VectorStore 抽象） */
    void deleteByChunkIdIn(Collection<Long> chunkIds);
}
