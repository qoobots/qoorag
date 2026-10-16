package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期 pgvector ANN 索引保障（RAG-010）。
 * <p>
 * {@code sql/init.sql} 只在库首次初始化时执行；已存在的库不会重跑建索引语句。
 * 本组件在每次启动时幂等地确保 HNSW 索引存在，并对存量数据补齐索引。
 * <p>
 * 要点：
 * <ul>
 *   <li>建索引前临时提升 {@code maintenance_work_mem}，加快大表索引构建；</li>
 *   <li>pgvector 0.5+ 才支持 HNSW，版本不足时优雅降级（只告警不阻断启动）；</li>
 *   <li>索引构建失败不阻塞应用启动，仅记录日志（检索可继续走顺序扫描，只是慢）。</li>
 * </ul>
 */
@Component
@Order(4)
@ConditionalOnProperty(value = "qoorag.vector.store", havingValue = "pgvector", matchIfMissing = true)
public class PgvectorIndexMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgvectorIndexMigrator.class);

    private final JdbcTemplate jdbcTemplate;

    /** HNSW 参数：每个节点最大连接数，越大召回越高、内存与构建耗时越高 */
    @Value("${qoorag.vector.hnsw.m:16}")
    private int hnswM;

    /** HNSW 参数：构建时搜索宽度，越大图质量越高、构建越慢 */
    @Value("${qoorag.vector.hnsw.ef-construction:128}")
    private int hnswEfConstruction;

    public PgvectorIndexMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String version = null;
        try {
            version = jdbcTemplate.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
        } catch (Exception e) {
            log.warn("读取 pgvector 扩展版本失败，跳过 ANN 索引保障: {}", e.getMessage());
            return;
        }
        if (version == null) {
            log.warn("未安装 pgvector 扩展，跳过 ANN 索引保障（需 CREATE EXTENSION vector）");
            return;
        }
        if (!supportsHnsw(version)) {
            log.warn("pgvector {} 不支持 HNSW 索引（需 0.5+），跳过；检索将走顺序扫描，请升级 pgvector", version);
            return;
        }
        try {
            long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_data", Long.class);
            boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'vector_data' "
                            + "AND indexname = 'idx_vector_embedding_hnsw')", Boolean.class));
            log.info("pgvector {} 就绪，vector_data 现有 {} 行，HNSW 索引 {}",
                    version, rows, exists ? "已存在" : "不存在，开始创建");
            // SET 与 CREATE INDEX 在同一 execute 内执行，保证作用于同一条连接
            jdbcTemplate.execute(String.format(
                    "SET maintenance_work_mem = '512MB'; "
                            + "CREATE INDEX IF NOT EXISTS idx_vector_embedding_hnsw "
                            + "ON vector_data USING hnsw (embedding vector_cosine_ops) "
                            + "WITH (m = %d, ef_construction = %d);",
                    clamp(hnswM, 4, 128), clamp(hnswEfConstruction, 16, 1000)));
            log.info("HNSW ANN 索引就绪：m={}, ef_construction={}", hnswM, hnswEfConstruction);
        } catch (Exception e) {
            log.error("HNSW 索引创建失败（不阻断启动，检索降级为顺序扫描）: {}", e.getMessage(), e);
        }
    }

    /** pgvector 版本字符串（如 "0.7.4"）是否 >= 0.5 */
    private boolean supportsHnsw(String version) {
        try {
            String[] parts = version.trim().split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 0 || minor >= 5;
        } catch (Exception e) {
            // 版本号无法解析时保守放行，交由 CREATE INDEX 自行成败
            return true;
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}
