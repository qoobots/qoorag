package com.qoobot.qoorag.service;

import com.pgvector.PGvector;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索服务：查询向量化 → pgvector 余弦相似度检索 → 组装召回片段
 * <p>
 * 使用 pgvector {@code <=>} 余弦距离算子，1 - (embedding <=> query_vec) = 余弦相似度。
 * 检索结果受 kbId + tenantId 约束，确保租户与知识库隔离（4.9）。
 */
@Service
public class RetrieveService {

    private static final Logger log = LoggerFactory.getLogger(RetrieveService.class);

    private final EntityManager entityManager;
    private final EmbeddingService embeddingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public RetrieveService(EntityManager entityManager,
                           EmbeddingService embeddingService,
                           KnowledgeBaseRepository knowledgeBaseRepository) {
        this.entityManager = entityManager;
        this.embeddingService = embeddingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 检索召回：查询文本 → 向量化 → pgvector 余弦相似 top-K
     *
     * @param query    检索问句
     * @param topK     返回条数（默认 5）
     * @param kbId     知识库 ID（来自 API Key）
     * @param tenantId 租户 ID（来自 API Key）
     * @return 按余弦相似度降序排列的召回片段
     */
    @SuppressWarnings("unchecked")
    public List<RetrieveChunk> retrieve(String query, int topK, Long kbId, Long tenantId) {
        // 1. 权限校验：KB 存在且未被软删
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));
        if (kb.getDeletedAt() != null) {
            throw new RuntimeException("知识库已删除");
        }
        if (!kb.getTenantId().equals(tenantId)) {
            throw new RuntimeException("无权访问该知识库");
        }
        log.info("检索 kbId={} tenantId={} topK={} query='{}'", kbId, tenantId, topK,
                query.length() > 100 ? query.substring(0, 100) + "..." : query);

        // 2. 查询向量化
        float[] queryVec;
        try {
            queryVec = embeddingService.embed(query);
        } catch (Exception e) {
            log.error("查询向量化失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
        if (queryVec == null || queryVec.length == 0) {
            throw new RuntimeException("向量化返回为空");
        }

        // 3. pgvector 余弦相似度检索（native SQL，<=> 算子）
        String sql = """
                SELECT v.id,
                       v.chunk_id,
                       c.document_id,
                       c.seq,
                       c.content,
                       1 - (v.embedding <=> :qvec) AS similarity
                  FROM vector_data v
                  JOIN chunk c ON c.id = v.chunk_id
                 WHERE v.kb_id     = :kbId
                   AND v.tenant_id = :tenantId
                 ORDER BY v.embedding <=> :qvec
                 LIMIT :topK
                """;

        Query nativeQuery = entityManager.createNativeQuery(sql);
        nativeQuery.setParameter("qvec", new PGvector(queryVec));
        nativeQuery.setParameter("kbId", kbId);
        nativeQuery.setParameter("tenantId", tenantId);
        nativeQuery.setParameter("topK", topK);

        List<Object[]> rows = nativeQuery.getResultList();
        List<RetrieveChunk> results = new ArrayList<>();

        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            Long chunkId = ((Number) row[1]).longValue();
            Long documentId = row[2] != null ? ((Number) row[2]).longValue() : null;
            Integer seq = row[3] != null ? ((Number) row[3]).intValue() : null;
            String content = (String) row[4];
            double similarity = ((Number) row[5]).doubleValue();

            results.add(new RetrieveChunk(id, chunkId, documentId, seq, content, similarity));
        }

        log.info("检索完成，返回 {} 条结果（kbId={}）", results.size(), kbId);
        return results;
    }
}
