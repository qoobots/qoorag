package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.entity.IngestTask;
import com.qoobot.qoorag.repository.IngestTaskRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步入库队列（RAG-030）。
 * <p>
 * 改造前上传请求内同步跑完整条流水线：大文档 HTTP 超时、无进度、失败即整篇重来。
 * 现在：上传 → 落盘临时文件 → 建 {@code ingest_task}（QUEUED）→ 立即返回 taskId，
 * 由后台线程池消费，进度/阶段/失败原因实时可查，支持手动重试。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>文件<b>先落盘</b>再入队：避免请求结束后 {@link MultipartFile} 被清理导致读不到内容；</li>
 *   <li>线程池有界（默认 2），避免大文档并发入库把 embedding API 与 DB 打满；</li>
 *   <li>队列内存有界 + 拒绝策略 {@code CallerRuns}：过载时退回调用线程执行，不丢任务；</li>
 *   <li>工作线程内设置 {@link TenantContext}，保证 RLS 兜底与代码层过滤一致；</li>
 *   <li>重启后 {@link #recoverPending} 会把中断的 RUNNING 任务重置为 QUEUED 重新入队。</li>
 * </ul>
 */
@Service
public class IngestQueueService {

    private static final Logger log = LoggerFactory.getLogger(IngestQueueService.class);

    /** 落盘目录：系统临时目录下的 qoorag-ingest */
    private static final Path SPOOL_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "qoorag-ingest");

    private final IngestTaskRepository taskRepository;
    private final IngestService ingestService;
    private final AuditService auditService;

    /** 后台消费线程数 */
    @Value("${qoorag.ingest.workers:2}")
    private int workers;

    /** 内存队列容量；超出后由调用线程直接执行（CallerRuns），避免丢任务 */
    @Value("${qoorag.ingest.queue-capacity:100}")
    private int queueCapacity;

    private final ExecutorService executor;

    public IngestQueueService(IngestTaskRepository taskRepository,
                              IngestService ingestService,
                              AuditService auditService) {
        this.taskRepository = taskRepository;
        this.ingestService = ingestService;
        this.auditService = auditService;
        int n = Math.max(1, workers);
        this.executor = new java.util.concurrent.ThreadPoolExecutor(
                n, n, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "qoorag-ingest-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        try {
            Files.createDirectories(SPOOL_DIR);
        } catch (IOException e) {
            log.warn("创建入库临时目录失败（{}）: {}", SPOOL_DIR, e.getMessage());
        }
    }

    /**
     * 入队：建任务 → 文件落盘（文件名带 taskId，便于重试/恢复时精确定位）→ 提交后台执行。
     *
     * @return 已创建的任务（状态 QUEUED）
     */
    @Transactional
    public IngestTask enqueue(MultipartFile file, Long kbId, Long tenantId, Long userId) {
        String filename = file.getOriginalFilename();
        IngestTask task = new IngestTask();
        task.setKbId(kbId);
        task.setTenantId(tenantId);
        task.setFilename(filename);
        task.setStatus(IngestTask.QUEUED);
        task.setStage(IngestTask.STAGE_PARSE);
        task.setProgress(0);
        task.setRetryCount(0);
        task.setCreatedBy(userId);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task = taskRepository.save(task);

        Path spooled = spool(file, task.getId(), filename);
        final long taskId = task.getId();
        submit(taskId, spooled, tenantId);
        log.info("入库任务已入队：taskId={} kbId={} file={}", taskId, kbId, filename);
        return task;
    }

    /**
     * 提交后台任务。
     * <p>
     * <b>必须在事务提交后提交</b>：{@code enqueue} / {@code retry} / {@code recoverPending}
     * 都带 {@code @Transactional}，若在事务内直接 {@code executor.execute}，
     * 工作线程会早于事务提交执行，{@code findById} 读不到刚插入的任务行，
     * 从而静默丢弃任务（实测现象：任务永远停在 QUEUED）。
     * 因此这里注册 {@code afterCommit} 回调；无事务时直接提交。
     */
    private void submit(long taskId, Path filePath, Long tenantId) {
        Runnable work = () -> run(taskId, filePath, tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(work);
                }
            });
        } else {
            executor.execute(work);
        }
    }

    /** 执行单个任务 */
    private void run(long taskId, Path filePath, Long tenantId) {
        IngestTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            cleanup(filePath);
            return;
        }
        // 工作线程内设置租户上下文：保证 RLS 兜底与代码层 tenant 过滤一致
        Long previous = TenantContext.get();
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
        try {
            task.setStatus(IngestTask.RUNNING);
            task.setStartedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            MultipartFile file = new SpooledMultipartFile(filePath, task.getFilename());
            Document doc = ingestService.ingest(file, task.getKbId(), task.getTenantId(),
                    (stage, percent) -> updateProgress(taskId, stage, percent));

            task.setStatus(IngestTask.SUCCEEDED);
            task.setStage(IngestTask.STAGE_DONE);
            task.setProgress(100);
            task.setDocumentId(doc.getId());
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            task.setError(null);
            taskRepository.save(task);
            cleanup(filePath); // 成功后即删临时文件，避免堆积
            log.info("入库任务完成：taskId={} documentId={}", taskId, doc.getId());
        } catch (Exception e) {
            log.error("入库任务失败：taskId={}, error={}", taskId, e.getMessage(), e);
            task.setStatus(IngestTask.FAILED);
            task.setProgress(0);
            task.setError(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
            // 失败保留临时文件，便于重试
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /** 更新进度（每次 stage 变化时调用） */
    @Transactional
    public void updateProgress(long taskId, String stage, int percent) {
        try {
            taskRepository.findById(taskId).ifPresent(t -> {
                t.setStage(stage);
                t.setProgress(Math.max(0, Math.min(100, percent)));
                t.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(t);
            });
        } catch (Exception e) {
            log.warn("更新入库进度失败（taskId={}）: {}", taskId, e.getMessage());
        }
    }

    /** 重试失败任务：重置为 QUEUED 并重新提交 */
    @Transactional
    public IngestTask retry(Long kbId, Long taskId) {
        IngestTask task = taskRepository.findByIdAndKbId(taskId, kbId)
                .orElseThrow(() -> new RuntimeException("任务不存在或无权访问"));
        if (IngestTask.SUCCEEDED.equals(task.getStatus())) {
            throw new RuntimeException("任务已成功，无需重试");
        }
        task.setStatus(IngestTask.QUEUED);
        task.setProgress(0);
        task.setStage(IngestTask.STAGE_PARSE);
        task.setError(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setFinishedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        task = taskRepository.save(task);

        Path filePath = spoolPath(taskId);
        if (!Files.exists(filePath)) {
            task.setStatus(IngestTask.FAILED);
            task.setError("临时文件已丢失，无法重试，请重新上传");
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
            throw new RuntimeException("临时文件已丢失，无法重试，请重新上传");
        }
        final long id = task.getId();
        final Long tenant = task.getTenantId();
        submit(id, spoolPath(id), tenant);
        log.info("入库任务重试：taskId={} 第 {} 次", taskId, task.getRetryCount());
        return task;
    }

    /** 查询某 KB 的任务列表 */
    public List<IngestTask> listByKb(Long kbId) {
        return taskRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    /** 查询单个任务（带 KB 归属校验） */
    public IngestTask get(Long kbId, Long taskId) {
        return taskRepository.findByIdAndKbId(taskId, kbId)
                .orElseThrow(() -> new RuntimeException("任务不存在或无权访问"));
    }

    /**
     * 重启恢复：把中断的 RUNNING/QUEUED 任务重新入队。
     * <p>临时文件仍在磁盘上，因此可无损恢复。
     */
    @Transactional
    public int recoverPending() {
        List<IngestTask> pending = taskRepository.findByStatusIn(
                List.of(IngestTask.RUNNING, IngestTask.QUEUED));
        int recovered = 0;
        for (IngestTask t : pending) {
            Path filePath = spoolPath(t.getId());
            if (!Files.exists(filePath)) {
                t.setStatus(IngestTask.FAILED);
                t.setError("应用重启导致临时文件丢失，请重新上传");
                t.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(t);
                continue;
            }
            t.setStatus(IngestTask.QUEUED);
            t.setProgress(0);
            t.setStage(IngestTask.STAGE_PARSE);
            t.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(t);
            final long id = t.getId();
            final Long tenant = t.getTenantId();
            submit(id, filePath, tenant);
            recovered++;
        }
        if (recovered > 0 || !pending.isEmpty()) {
            log.info("重启恢复入库任务：待恢复 {} 个，成功重新入队 {} 个", pending.size(), recovered);
        }
        return recovered;
    }

    /** 文件落盘；文件名带 taskId 前缀，便于重试/重启恢复时精确定位 */
    private Path spool(MultipartFile file, long taskId, String filename) {
        String safe = (filename == null ? "unnamed" : filename).replaceAll("[^\\w.\\-]", "_");
        Path target = SPOOL_DIR.resolve(taskId + "_" + UUID.randomUUID() + "_" + safe);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("入库文件落盘失败: " + e.getMessage(), e);
        }
        return target;
    }

    /** 由 taskId 定位落盘文件（文件名格式 {@code {taskId}_{uuid}_{safeName}}） */
    private Path spoolPath(long taskId) {
        if (!Files.isDirectory(SPOOL_DIR)) {
            return SPOOL_DIR.resolve("missing_" + taskId);
        }
        String prefix = taskId + "_";
        try (var stream = Files.list(SPOOL_DIR)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .min(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(SPOOL_DIR.resolve("missing_" + taskId));
        } catch (IOException e) {
            return SPOOL_DIR.resolve("missing_" + taskId);
        }
    }

    /** 清理临时文件 */
    private void cleanup(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主链路
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭入库线程池（等待在途任务，最多 30s）");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 仅供测试/运维：暴露线程池 */
    public ExecutorService executor() {
        return executor;
    }
}
