package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.TenantContext;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.entity.VectorData;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contextual Chunking 存量重建（RAG-023）。
 * <p>
 * Contextual Chunking 改变的是 <b>embedding 文本</b>（前缀 + 原文），因此对存量数据
 * 必须重算前缀并<b>重新向量化</b>才生效——只加前缀不重嵌等于没改。
 * <p>
 * 设计：
 * <ul>
 *   <li>异步执行（单后台线程），不阻塞 HTTP 请求；</li>
 *   <li>按 KB 粒度，可随时触发；</li>
 *   <li>每个 chunk 独立事务更新，中途失败不影响已完成部分；</li>
 *   <li>重建期间该 KB 的检索仍可用（旧向量仍在），只是混用新旧直到完成。</li>
 * </ul>
 */
@Service
public class ContextualRebuildService {

    private static final Logger log = LoggerFactory.getLogger(ContextualRebuildService.class);

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final VectorDataRepository vectorDataRepository;
    private final ContextualChunkService contextualChunkService;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qoorag-contextual-rebuild");
        t.setDaemon(true);
        return t;
    });

    public ContextualRebuildService(ChunkRepository chunkRepository,
                                    DocumentRepository documentRepository,
                                    VectorDataRepository vectorDataRepository,
                                    ContextualChunkService contextualChunkService,
                                    EmbeddingService embeddingService,
                                    JdbcTemplate jdbcTemplate) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.vectorDataRepository = vectorDataRepository;
        this.contextualChunkService = contextualChunkService;
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 重建结果 */
    public record RebuildResult(Long kbId, int total, int done, int failed, String status) {}

    /**
     * 异步重建某 KB 的全部 chunk 上下文前缀与向量。
     *
     * @param kbId 知识库
     */
    public void rebuildAsync(Long kbId) {
        if (!contextualChunkService.isEnabled()) {
            throw new IllegalStateException("Contextual Chunking 未开启"
                    + "（qoorag.ingest.contextual.enabled=false），无需重建");
        }
        List<Chunk> chunks = chunkRepository.findByKbId(kbId);
        log.info("开始 Contextual Chunking 重建：kbId={}，共 {} 个 chunk", kbId, chunks.size());
        executor.execute(() -> {
            Long previous = TenantContext.get();
            TenantContext.set(chunks.isEmpty() ? null : chunks.get(0).getTenantId());
            int done = 0;
            int failed = 0;
            try {
                for (Chunk c : chunks) {
                    try {
                        rebuildOne(c);
                        done++;
                    } catch (Exception e) {
                        failed++;
                        log.warn("chunk {} 重建失败: {}", c.getId(), e.getMessage());
                    }
                    if ((done + failed) % 20 == 0) {
                        log.info("Contextual 重建进度：kbId={} {}/{}（失败 {}）",
                                kbId, done + failed, chunks.size(), failed);
                    }
                }
                log.info("Contextual Chunking 重建完成：kbId={} 成功 {} 失败 {}", kbId, done, failed);
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        });
    }

    /** 重建单个 chunk：生成前缀 → 更新行 → 重新向量化（native SQL 直接写 tsvector 无关列） */
    @Transactional
    public void rebuildOne(Chunk chunk) {
        Document doc = documentRepository.findById(chunk.getDocumentId()).orElse(null);
        String docTitle = doc == null ? null : doc.getDocTitle();
        String prefix = contextualChunkService.generatePrefix(
                chunk.getContent(), docTitle, chunk.getHeadingPath(), chunk.getChunkType());
        if (prefix == null) {
            return; // 生成失败：保持原状
        }
        // 1. 更新前缀（tsv 为生成列，会自动纳入前缀）
        jdbcTemplate.update("UPDATE chunk SET context_prefix = ? WHERE id = ?", prefix, chunk.getId());
        // 2. 重新向量化（embedding 文本 = 前缀 + 原文）
        float[] vec = embeddingService.embed(
                contextualChunkService.embeddingText(chunk.getContent(), prefix));
        // 3. 更新向量（PGvector 需经 PreparedStatement.setObject 绑定）
        VectorData vd = vectorDataRepository.findByChunkId(chunk.getId()).orElse(null);
        if (vd == null) {
            vd = new VectorData();
            vd.setChunkId(chunk.getId());
            vd.setKbId(chunk.getKbId());
            vd.setTenantId(chunk.getTenantId());
            vd.setCreatedAt(LocalDateTime.now());
        }
        vd.setEmbeddingArray(vec);
        vectorDataRepository.save(vd);
    }

    /** 供测试/运维：同步重建（小数据量用） */
    public RebuildResult rebuildSync(Long kbId) {
        List<Chunk> chunks = chunkRepository.findByKbId(kbId);
        int done = 0;
        int failed = 0;
        for (Chunk c : chunks) {
            try {
                rebuildOne(c);
                done++;
            } catch (Exception e) {
                failed++;
            }
        }
        return new RebuildResult(kbId, chunks.size(), done, failed,
                failed == 0 ? "SUCCESS" : "PARTIAL");
    }
}
