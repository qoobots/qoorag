package com.qoobot.qoorag.store;

import com.qoobot.qoorag.entity.VectorData;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * pgvector → Milvus 数据迁移（RAG-068 设计第 ④ 步）。
 * <p>仅当 {@code qoorag.vector.store=milvus} 且 {@code qoorag.milvus.migrate-on-startup=true} 时，
 * 在启动期把 {@code vector_data} 全部同步到 Milvus；默认关闭，避免误触。</p>
 */
@Service
@ConditionalOnProperty(value = "qoorag.vector.store", havingValue = "milvus")
public class MilvusMigrationService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MilvusMigrationService.class);
    private static final int BATCH = 500;

    private final VectorDataRepository vectorDataRepository;
    private final VectorStore vectorStore;

    @Value("${qoorag.milvus.migrate-on-startup:false}")
    private boolean migrateOnStartup;

    public MilvusMigrationService(VectorDataRepository vectorDataRepository, VectorStore vectorStore) {
        this.vectorDataRepository = vectorDataRepository;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        if (!migrateOnStartup) {
            return;
        }
        long n = migrateAll();
        log.info("Milvus 迁移完成：从 pgvector 同步 {} 条向量", n);
    }

    /** 读取全部 pgvector 向量并写入当前 VectorStore（Milvus）。返回同步条数。 */
    public long migrateAll() {
        List<VectorData> all = vectorDataRepository.findAll();
        long total = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            int end = Math.min(i + BATCH, all.size());
            List<ChunkVector> batch = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                VectorData vd = all.get(j);
                batch.add(new ChunkVector(
                        vd.getChunkId(), null, vd.getKbId(), vd.getTenantId(), vd.getEmbeddingArray()));
            }
            vectorStore.upsert(batch);
            total += batch.size();
        }
        return total;
    }
}
