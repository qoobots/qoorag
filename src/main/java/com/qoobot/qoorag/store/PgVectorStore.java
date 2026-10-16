package com.qoobot.qoorag.store;

import com.pgvector.PGvector;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.VectorData;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * pgvector 向量存储实现（RAG-068 / 默认后端）。
 * 逻辑由原 {@code RetrieveService.queryVectorDb} 与 IngestService 向量写入迁移而来，行为保持一致。
 */
@Service
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final VectorDataRepository vectorDataRepository;

    /** HNSW 查询期召回宽度 hnsw.ef_search：<=0 表示不设置，用 pgvector 默认值（40） */
    @Value("${qoorag.vector.hnsw.ef-search:40}")
    private int hnswEfSearch;

    public PgVectorStore(JdbcTemplate jdbcTemplate, VectorDataRepository vectorDataRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorDataRepository = vectorDataRepository;
    }

    @Override
    public List<RetrieveChunk> querySimilar(float[] queryVec, int limit, Long kbId, Long tenantId) {
        String sql = "SELECT v.id, v.chunk_id, c.document_id, c.seq, c.content, "
                + "c.heading_path, c.page_no, c.chunk_type, c.parent_chunk_id, d.name AS document_name, "
                + "1 - (v.embedding <=> ?) AS similarity "
                + "FROM vector_data v JOIN chunk c ON c.id = v.chunk_id "
                + "JOIN document d ON d.id = c.document_id "
                + "WHERE v.kb_id = ? AND v.tenant_id = ? "
                + "ORDER BY v.embedding <=> ? LIMIT ?";

        PGvector qv = new PGvector(queryVec);
        RowMapper<RetrieveChunk> rowMapper = (rs, rn) -> {
            RetrieveChunk c = new RetrieveChunk(
                    rs.getLong("id"),
                    rs.getLong("chunk_id"),
                    rs.getLong("document_id"),
                    rs.getInt("seq"),
                    rs.getString("content"),
                    rs.getDouble("similarity"));
            c.setHeadingPath(rs.getString("heading_path"));
            c.setPageNo(rs.getObject("page_no") != null ? rs.getInt("page_no") : null);
            c.setChunkType(rs.getString("chunk_type"));
            c.setDocumentName(rs.getString("document_name"));
            c.setParentChunkId(rs.getObject("parent_chunk_id", Long.class)); // RAG-022：父子块回填
            return c;
        };

        return jdbcTemplate.execute((ConnectionCallback<List<RetrieveChunk>>) con -> {
            boolean prevAutoCommit = con.getAutoCommit();
            List<RetrieveChunk> out = new ArrayList<>();
            try {
                con.setAutoCommit(false);
                if (hnswEfSearch > 0) {
                    try (Statement st = con.createStatement()) {
                        st.execute("SET LOCAL hnsw.ef_search = " + hnswEfSearch);
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setObject(1, qv);
                    ps.setLong(2, kbId);
                    ps.setLong(3, tenantId);
                    ps.setObject(4, qv);
                    ps.setInt(5, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        int rowNum = 0;
                        while (rs.next()) {
                            out.add(rowMapper.mapRow(rs, ++rowNum));
                        }
                    }
                }
                con.commit();
            } catch (SQLException e) {
                try {
                    con.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败时保留原始异常
                }
                throw new DataAccessResourceFailureException("向量检索执行失败: " + e.getMessage(), e);
            } finally {
                try {
                    con.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                    // 连接状态恢复失败不影响返回结果
                }
            }
            return out;
        });
    }

    @Override
    public void upsert(Collection<ChunkVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        List<VectorData> entities = vectors.stream().map(cv -> {
            VectorData vd = new VectorData();
            vd.setChunkId(cv.chunkId());
            vd.setKbId(cv.kbId());
            vd.setTenantId(cv.tenantId());
            vd.setEmbeddingArray(cv.embedding());
            vd.setCreatedAt(LocalDateTime.now());
            return vd;
        }).collect(Collectors.toList());
        vectorDataRepository.saveAll(entities);
    }

    @Override
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        vectorDataRepository.deleteByChunkIdIn(chunkIds);
    }

    @Override
    public void deleteByDocument(Long docId) {
        if (docId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM vector_data WHERE chunk_id IN (SELECT id FROM chunk WHERE document_id = ?)",
                docId);
    }

    @Override
    public void ensureIndex() {
        // pgvector 的 HNSW 索引由 PgvectorIndexMigrator 在启动期幂等创建，此处无需操作
    }
}
