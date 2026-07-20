package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    List<ApiKey> findByKbIdAndDeletedAtIsNull(Long kbId);
    Optional<ApiKey> findByKeyHashAndStatusAndDeletedAtIsNull(String keyHash, String status);
}
