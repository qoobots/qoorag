package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalRunRepository extends JpaRepository<EvalRun, Long> {

    /** 查某 KB 的评测运行记录（时间倒序），用于趋势对比 */
    List<EvalRun> findByKbIdOrderByCreatedAtDesc(Long kbId);

    /** 查某 KB 最近一次成功运行，作为对比基线 */
    EvalRun findFirstByKbIdAndStatusOrderByCreatedAtDesc(Long kbId, String status);

    /** 查某数据集的历史运行 */
    List<EvalRun> findByDatasetIdOrderByCreatedAtDesc(Long datasetId);
}
