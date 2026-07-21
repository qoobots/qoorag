package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.ResourcePoolConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourcePoolConfigRepository extends JpaRepository<ResourcePoolConfig, Long> {

    List<ResourcePoolConfig> findByCategory(String category);

    Optional<ResourcePoolConfig> findByCategoryAndConfigKey(String category, String configKey);
}
