package com.qoobot.qoorag.repository;

import com.qoobot.qoorag.entity.IngestTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngestTaskRepository extends JpaRepository<IngestTask, Long> {

    /** 查某 KB 的任务列表（按创建时间倒序） */
    List<IngestTask> findByKbIdOrderByCreatedAtDesc(Long kbId);

    /** 查某 KB 未完结的任务 */
    List<IngestTask> findByKbIdAndStatusIn(Long kbId, List<String> statuses);

    /** 查所有待处理任务（用于重启后恢复） */
    List<IngestTask> findByStatusIn(List<String> statuses);

    Optional<IngestTask> findByIdAndKbId(Long id, Long kbId);
}
