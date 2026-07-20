package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByKbIdAndDeletedAtIsNull(Long kbId);
}
