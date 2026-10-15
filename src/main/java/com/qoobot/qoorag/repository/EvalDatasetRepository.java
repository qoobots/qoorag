package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.EvalDataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalDatasetRepository extends JpaRepository<EvalDataset, Long> {

    /** 查某 KB 的评测集（创建时间倒序） */
    List<EvalDataset> findByKbIdOrderByCreatedAtDesc(Long kbId);

    /** 查某 KB 最新版本号 */
    EvalDataset findFirstByKbIdOrderByVersionDesc(Long kbId);
}
