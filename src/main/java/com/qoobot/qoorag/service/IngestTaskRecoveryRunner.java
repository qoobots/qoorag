package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 入库任务重启恢复（RAG-030）。
 * <p>
 * 顺序在 {@code RagSchemaMigrator}（@Order 6）之后，确保 {@code ingest_task} 表已就绪。
 * 应用重启会把中断的 RUNNING/QUEUED 任务重新入队（临时文件仍在磁盘上，可无损恢复）。
 */
@Component
@Order(7)
public class IngestTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskRecoveryRunner.class);

    private final IngestQueueService ingestQueueService;

    public IngestTaskRecoveryRunner(IngestQueueService ingestQueueService) {
        this.ingestQueueService = ingestQueueService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = ingestQueueService.recoverPending();
            if (recovered > 0) {
                log.info("重启后恢复入库任务 {} 个", recovered);
            }
        } catch (Exception e) {
            log.warn("入库任务重启恢复跳过（不影响启动）: {}", e.getMessage());
        }
    }
}
