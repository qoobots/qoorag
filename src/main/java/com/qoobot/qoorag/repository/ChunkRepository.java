package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {
    List<Chunk> findByKbId(Long kbId);
}
