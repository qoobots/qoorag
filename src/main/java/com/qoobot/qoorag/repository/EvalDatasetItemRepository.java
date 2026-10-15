package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.EvalDatasetItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalDatasetItemRepository extends JpaRepository<EvalDatasetItem, Long> {

    List<EvalDatasetItem> findByDatasetId(Long datasetId);

    long countByDatasetId(Long datasetId);

    void deleteByDatasetId(Long datasetId);
}
