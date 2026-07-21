package com.qoobot.qoorag.service;

import com.pgvector.PGvector;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索服务：查询向量化 → pgvector 余弦相似度检索 → 两阶段重排 → 组装召回片段
 * <p>
 * 使用 pgvector {@code <=>} 余弦距离算子，1 - (embedding <=> query_vec) = 余弦相似度。
 * 检索结果受 kbId + tenantId 约束，确保租户与知识库隔离（4.9）。
 * <p>
 * 检索增强（#retrieval-tuning，默认关闭不破坏现有行为，可在 application.yml 开启）：
 * <ul>
 *   <li>candidate-pool：先取更大的候选集，再重排裁剪到 topK，避免好结果被截断在 topK 之外；</li>
 *   <li>min-score：过滤低于相似度阈值的噪声候选，提升 Precision；</li>
 *   <li>rerank-mode：
 *     <ul>
 *       <li>{@code none}：原样返回（默认，行为不变）；</li>
 *       <li>{@code diversity}：按文档多样性轮询重排，避免单文档霸榜；</li>
 *       <li>{@code hybrid}：向量余弦 + 中文 bigram 词法信号做 RRF 融合重排，
 *           能从根上提升"语义重叠被大文档淹没"的短文档（如摘要/作者/讨论章）召回，
 *           同时用 diversity 去冗余、提升 Precision。</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * 注意：向量参数通过 JDBC {@code PreparedStatement.setObject(idx, PGvector)} 绑定，
 * 由 PostgreSQL 驱动正确识别为 vector 类型；若经 JPA 原生查询的 setParameter 绑定，
 * 会被误判为 bytea，导致 "vector <=> bytea" 算子缺失。
 */
@Service
public class RetrieveService {

    private static final Logger log = LoggerFactory.getLogger(RetrieveService.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 候选池大小：0 = 直接使用 topK；>0 时先取 candidate-pool 个候选再做重排裁剪 */
    @Value("${qoorag.retrieval.candidate-pool:0}")
    private int candidatePool;

    /** 最低相似度阈值：0.0 = 不过滤；>0 时过滤低于该余弦相似度的候选 */
    @Value("${qoorag.retrieval.min-score:0.0}")
    private double minScore;

    /** 重排模式：none / diversity / hybrid */
    @Value("${qoorag.retrieval.rerank-mode:none}")
    private String rerankMode;

    /** 多样性重排时单文档最多保留的片段数（<=0 表示不限制） */
    @Value("${qoorag.retrieval.diversity-max-per-doc:2}")
    private int diversityMaxPerDoc;

    /** RRF 融合的平滑常数 k */
    @Value("${qoorag.retrieval.rrf-k:60}")
    private int rrfK;

    /** 运行时覆盖检索策略（供评估接口临时对比不同策略，不影响 @Value 默认值） */
    public void overrideRerankConfig(String mode, int pool, double minSc, int maxPerDoc, int k) {
        if (mode != null) this.rerankMode = mode;
        if (pool >= 0) this.candidatePool = pool;
        if (minSc >= 0) this.minScore = minSc;
        if (maxPerDoc >= 0) this.diversityMaxPerDoc = maxPerDoc;
        if (k > 0) this.rrfK = k;
    }

    public String getRerankMode() { return rerankMode; }
    public int getCandidatePool() { return candidatePool; }
    public double getMinScore() { return minScore; }
    public int getDiversityMaxPerDoc() { return diversityMaxPerDoc; }
    public int getRrfK() { return rrfK; }

    public RetrieveService(JdbcTemplate jdbcTemplate,
                           EmbeddingService embeddingService,
                           KnowledgeBaseRepository knowledgeBaseRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 检索召回：查询文本 → 向量化 → pgvector 余弦相似 top-候选 → 两阶段重排 → topK
     *
     * @param query    检索问句
     * @param topK     返回条数（默认 5）
     * @param kbId     知识库 ID（来自 API Key）
     * @param tenantId 租户 ID（来自 API Key）
     * @return 重排后按相关性降序排列的召回片段
     */
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
        log.info("检索 kbId={} tenantId={} topK={} rerank={} query='{}'", kbId, tenantId, topK,
                rerankMode, query.length() > 100 ? query.substring(0, 100) + "..." : query);

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

        // 3. 取候选集（候选池 >= topK，便于重排阶段有更全的候选）
        int cand = (candidatePool > 0) ? Math.max(candidatePool, topK) : topK;
        List<RetrieveChunk> candidates = queryVectorDb(queryVec, cand, kbId, tenantId);
        if (candidates.isEmpty()) {
            return candidates;
        }

        // 4. 最低相似度阈值过滤（去噪声，提升 Precision）
        if (minScore > 0.0) {
            List<RetrieveChunk> filtered = candidates.stream()
                    .filter(c -> c.getScore() >= minScore)
                    .collect(Collectors.toList());
            log.info("阈值过滤 minScore={:.4f}: {} -> {} 条", minScore, candidates.size(), filtered.size());
            candidates = filtered;
        }

        // 5. 重排
        List<RetrieveChunk> result;
        if ("hybrid".equalsIgnoreCase(rerankMode)) {
            result = hybridRerank(query, candidates, topK);
        } else if ("diversity".equalsIgnoreCase(rerankMode)) {
            result = diversityRerank(candidates, topK, diversityMaxPerDoc);
        } else {
            result = candidates;
        }

        // 6. 截断到 topK（重排阶段可能已裁剪，这里兜底）
        if (result.size() > topK) {
            result = result.subList(0, topK);
        }
        log.info("检索完成，返回 {} 条结果（kbId={}, rerank={}）", result.size(), kbId, rerankMode);
        return result;
    }

    /** pgvector 余弦相似度检索（native SQL，<=> 算子） */
    private List<RetrieveChunk> queryVectorDb(float[] queryVec, int limit, Long kbId, Long tenantId) {
        String sql = "SELECT v.id, v.chunk_id, c.document_id, c.seq, c.content, "
                + "1 - (v.embedding <=> ?) AS similarity "
                + "FROM vector_data v JOIN chunk c ON c.id = v.chunk_id "
                + "WHERE v.kb_id = ? AND v.tenant_id = ? "
                + "ORDER BY v.embedding <=> ? LIMIT ?";

        PGvector qv = new PGvector(queryVec);
        RowMapper<RetrieveChunk> rowMapper = (rs, rn) -> new RetrieveChunk(
                rs.getLong("id"),
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getDouble("similarity"));

        return jdbcTemplate.query(sql, (ps) -> {
            try {
                ps.setObject(1, qv);
                ps.setLong(2, kbId);
                ps.setLong(3, tenantId);
                ps.setObject(4, qv);
                ps.setInt(5, limit);
            } catch (SQLException e) {
                throw new RuntimeException("向量参数绑定失败: " + e.getMessage(), e);
            }
        }, rowMapper);
    }

    /**
     * 混合重排（hybrid）：向量余弦排序 + 中文 bigram 词法排序，做 RRF 融合。
     * 词法信号能捕捉"查询关键词在片段中的命中"，从根上把被大文档语义淹没的
     * 短文档（摘要/作者/讨论章）片段提上来；最后用 diversity 去冗余。
     */
    private List<RetrieveChunk> hybridRerank(String query, List<RetrieveChunk> candidates, int topK) {
        // 向量名次（candidates 已按相似度降序）
        Map<Long, Integer> vecRank = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            vecRank.put(candidates.get(i).getChunkId(), i + 1);
        }
        // 词法排序（按 query 与片段的 bigram 覆盖率降序）
        List<RetrieveChunk> lexSorted = new ArrayList<>(candidates);
        lexSorted.sort((a, b) -> Double.compare(lexicalScore(query, b), lexicalScore(query, a)));
        Map<Long, Integer> lexRank = new HashMap<>();
        for (int i = 0; i < lexSorted.size(); i++) {
            lexRank.put(lexSorted.get(i).getChunkId(), i + 1);
        }
        // RRF 融合排序
        List<RetrieveChunk> fused = new ArrayList<>(candidates);
        fused.sort((a, b) -> {
            double sa = rrf(vecRank.get(a.getChunkId())) + rrf(lexRank.get(a.getChunkId()));
            double sb = rrf(vecRank.get(b.getChunkId())) + rrf(lexRank.get(b.getChunkId()));
            return Double.compare(sb, sa);
        });
        // 多样性去冗余（可选）
        if (diversityMaxPerDoc > 0) {
            fused = diversityRerank(fused, topK, diversityMaxPerDoc);
        }
        return fused;
    }

    /** RRF 分数：1 / (k + rank) */
    private double rrf(int rank) {
        return 1.0 / (rrfK + rank);
    }

    /**
     * 文档多样性重排：按相关性降序，每轮从每个文档组取一个片段，
     * 单文档最多保留 diversityMaxPerDoc 个，避免单文档霸榜、提升结果多样性与 Precision。
     */
    private List<RetrieveChunk> diversityRerank(List<RetrieveChunk> candidates, int topK, int maxPerDoc) {
        List<RetrieveChunk> sorted = new ArrayList<>(candidates); // 已按分数/融合分降序
        Map<Long, Queue<RetrieveChunk>> byDoc = new LinkedHashMap<>();
        for (RetrieveChunk c : sorted) {
            byDoc.computeIfAbsent(c.getDocumentId(), k -> new LinkedList<>()).add(c);
        }
        List<RetrieveChunk> out = new ArrayList<>();
        Map<Long, Integer> taken = new HashMap<>();
        boolean added;
        do {
            added = false;
            for (Queue<RetrieveChunk> q : byDoc.values()) {
                if (!q.isEmpty()) {
                    RetrieveChunk c = q.peek();
                    int cnt = taken.getOrDefault(c.getDocumentId(), 0);
                    if (cnt < maxPerDoc && out.size() < topK) {
                        out.add(q.poll());
                        taken.put(c.getDocumentId(), cnt + 1);
                        added = true;
                    }
                }
            }
        } while (added && out.size() < topK);
        // 候选不足时补齐
        if (out.size() < topK) {
            for (Queue<RetrieveChunk> q : byDoc.values()) {
                while (!q.isEmpty() && out.size() < topK) {
                    out.add(q.poll());
                }
            }
        }
        return out;
    }

    /** 中文 bigram 集合（去空白），用于词法信号 */
    private Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) return set;
        String t = text.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    /**
     * 词法相关性 = 查询 bigram 被片段覆盖的比例（查询覆盖率），
     * 侧重"查询的需求被该片段满足"，比 Jaccard 更适配检索场景。
     */
    private double lexicalScore(String query, RetrieveChunk c) {
        Set<String> qb = bigrams(query);
        if (qb.isEmpty()) return 0.0;
        Set<String> cb = bigrams(c.getContent());
        if (cb.isEmpty()) return 0.0;
        int inter = 0;
        for (String g : qb) {
            if (cb.contains(g)) inter++;
        }
        return (double) inter / qb.size();
    }
}
