package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.KnowledgeBase;
import com.qoobot.qoorag.repository.KnowledgeBaseRepository;
import com.qoobot.qoorag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索服务（RAG-011 / RAG-012 / RAG-013）。
 * <p>
 * 完整链路：查询向量化 → <b>向量 ANN 召回</b> + <b>词法全文召回</b> → RRF 名次融合 →
 * 多样性去冗余 → 截断到 topK。
 * <p>
 * <b>多路召回</b>（{@code hybrid-rrf}，默认）：
 * <ul>
 *   <li>向量路：pgvector HNSW 余弦相似度（RAG-010）；</li>
 *   <li>词法路：PostgreSQL 全文检索（GIN + tsvector + ts_rank_cd，RAG-011）；</li>
 *   <li>融合：RRF {@code 1/(k+rank)} 按<b>名次</b>融合，不依赖两路分值的可比性；</li>
 *   <li>词法路可捞回「向量语义被大文档淹没」的短片段，也可命中向量模型不敏感的专名/型号/编号。</li>
 * </ul>
 * <p>
 * <b>重排模式</b>（{@code qoorag.retrieval.rerank-mode}）：
 * <ul>
 *   <li>{@code none}：原样返回向量召回（行为与改造前完全一致）；</li>
 *   <li>{@code hybrid-rrf}（默认）：向量 + 词法 RRF 融合 + 多样性去冗余；</li>
 *   <li>{@code diversity}：仅多样性重排（不引入词法路）；</li>
 *   <li>{@code legacy-hybrid}：改造前的「向量 + Java bigram 覆盖率」伪混合，保留仅用于 A/B 对比，
 *       不建议用于生产（无索引、无 IDF、长文档占优）。</li>
 * </ul>
 * <p>
 * <b>线程安全</b>（RAG-013）：改造前用可变 {@code @Value} 字段承载请求级覆盖，
 * 多线程并发检索/评估时会互相污染。现改为把策略收敛为不可变 {@link RetrievalStrategy}，
 * 由调用方按请求传入；{@code overrideRerankConfig} 仅为兼容旧评估接口保留。
 * <p>
 * 注意：向量参数通过 JDBC {@code PreparedStatement.setObject(idx, PGvector)} 绑定，
 * 由 PostgreSQL 驱动正确识别为 vector 类型；若经 JPA 原生查询的 setParameter 绑定，
 * 会被误判为 bytea，导致 "vector <=> bytea" 算子缺失。
 */
@Service
public class RetrieveService {

    private static final Logger log = LoggerFactory.getLogger(RetrieveService.class);

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final LexicalSearchService lexicalSearchService;

    /** 候选池大小：0 = 直接使用 topK；>0 时先取 candidate-pool 个候选再做重排裁剪 */
    @Value("${qoorag.retrieval.candidate-pool:0}")
    private int candidatePool;

    /** 最低相似度阈值：0.0 = 不过滤；>0 时过滤低于该余弦相似度的向量候选 */
    @Value("${qoorag.retrieval.min-score:0.0}")
    private double minScore;

    /** 重排模式：none / hybrid-rrf / diversity / legacy-hybrid */
    @Value("${qoorag.retrieval.rerank-mode:none}")
    private String rerankMode;

    /** 多样性重排时单文档最多保留的片段数（<=0 表示不限制） */
    @Value("${qoorag.retrieval.diversity-max-per-doc:2}")
    private int diversityMaxPerDoc;

    /** RRF 融合的平滑常数 k */
    @Value("${qoorag.retrieval.rrf-k:60}")
    private int rrfK;

    /** HNSW 查询期召回宽度 hnsw.ef_search：<=0 表示不设置，用 pgvector 默认值（40）。
     *  调大 → 召回更高、延迟更高；数据量大或大量删除后建议调大（RAG-010） */
    @Value("${qoorag.vector.hnsw.ef-search:40}")
    private int hnswEfSearch;

    public RetrieveService(VectorStore vectorStore,
                           EmbeddingService embeddingService,
                           KnowledgeBaseRepository knowledgeBaseRepository,
                           LexicalSearchService lexicalSearchService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.lexicalSearchService = lexicalSearchService;
    }

    // ===== 策略（RAG-013：不可变、按请求传入，避免共享可变状态）=====

    /** 检索策略快照：一次检索全过程中口径一致的参数集合 */
    public record RetrievalStrategy(String mode, int candidatePool, double minScore,
                                    int diversityMaxPerDoc, int rrfK, int lexicalPool) {
        public static RetrievalStrategy of(String mode, int pool, double minSc,
                                           int maxPerDoc, int k, int lexicalPool) {
            return new RetrievalStrategy(mode, Math.max(pool, 0), Math.max(minSc, 0.0),
                    maxPerDoc, Math.max(k, 1), Math.max(lexicalPool, 0));
        }
    }

    /** 当前配置生成默认策略（生产链路使用） */
    public RetrievalStrategy defaultStrategy() {
        return RetrievalStrategy.of(rerankMode, candidatePool, minScore, diversityMaxPerDoc,
                rrfK, lexicalSearchService.getLexicalPool());
    }

    // ===== 兼容旧评估接口的运行时覆盖（已标注 deprecated，见类注释）=====

    /**
     * @deprecated 改用 {@link #retrieve(String, int, Long, Long, RetrievalStrategy)} 显式传策略。
     *   保留仅为兼容既有 {@code /api/admin/eval} 的覆盖入参；并发调用存在共享状态竞争，
     *   新代码请勿使用。
     */
    @Deprecated
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
    public int getHnswEfSearch() { return hnswEfSearch; }

    // ===== 检索入口 =====

    /**
     * 检索召回（使用默认策略）。
     */
    public List<RetrieveChunk> retrieve(String query, int topK, Long kbId, Long tenantId) {
        return retrieve(query, topK, kbId, tenantId, defaultStrategy());
    }

    /**
     * 检索召回（显式指定策略，线程安全）。
     *
     * @param query    检索问句
     * @param topK     返回条数
     * @param kbId     知识库 ID
     * @param tenantId 租户 ID
     * @param strategy 本次检索的策略快照
     * @return 按相关性降序排列的召回片段
     */
    public List<RetrieveChunk> retrieve(String query, int topK, Long kbId, Long tenantId,
                                        RetrievalStrategy strategy) {
        RetrievalStrategy effective = (strategy != null) ? strategy : defaultStrategy();
        // 1. 权限校验：KB 存在且未被软删
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));
        if (kb.getDeletedAt() != null) {
            throw new RuntimeException("知识库已删除");
        }
        if (!kb.getTenantId().equals(tenantId)) {
            throw new RuntimeException("无权访问该知识库");
        }
        log.info("检索 kbId={} tenantId={} topK={} mode={} query='{}'", kbId, tenantId, topK,
                effective.mode(), query.length() > 100 ? query.substring(0, 100) + "..." : query);

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

        // 3. 向量路召回（候选池 >= topK，便于重排阶段有更全的候选）
        int cand = (effective.candidatePool() > 0) ? Math.max(effective.candidatePool(), topK) : topK;
        List<RetrieveChunk> vectorCands = vectorStore.querySimilar(queryVec, cand, kbId, tenantId);
        if (vectorCands.isEmpty()) {
            return vectorCands;
        }

        // 4. 最低相似度阈值过滤（去噪声，提升 Precision；只作用于向量候选）
        List<RetrieveChunk> candidates = vectorCands;
        if (effective.minScore() > 0.0) {
            List<RetrieveChunk> filtered = vectorCands.stream()
                    .filter(c -> c.getScore() >= effective.minScore())
                    .collect(Collectors.toList());
            log.info("阈值过滤 minScore={:.4f}: {} -> {} 条", effective.minScore(), vectorCands.size(), filtered.size());
            candidates = filtered;
        }

        // 5. 重排 / 融合
        String mode = effective.mode() == null ? "none" : effective.mode();
        List<RetrieveChunk> result;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "hybrid-rrf" -> result = hybridRrfRerank(query, candidates, topK, kbId, tenantId, effective);
            case "legacy-hybrid" -> result = hybridRerank(query, candidates, topK);
            case "diversity" -> result = diversityRerank(candidates, topK, effective.diversityMaxPerDoc());
            default -> result = candidates;
        }

        // 6. 截断到 topK（重排阶段可能已裁剪，这里兜底）
        if (result.size() > topK) {
            result = result.subList(0, topK);
        }
        log.info("检索完成，返回 {} 条结果（kbId={}, mode={}）", result.size(), kbId, mode);
        return result;
    }

    // ===== 混合检索：向量 + 词法 RRF 融合（RAG-011 / RAG-012）=====

    /**
     * 向量名次 + 词法名次 RRF 融合。
     * <p>
     * 两路候选取并集：词法路可以捞回纯词法命中（向量路未进候选池）的片段，
     * 这是「真混合」相对「伪混合」的关键增量。
     */
    private List<RetrieveChunk> hybridRrfRerank(String query, List<RetrieveChunk> vectorCands, int topK,
                                                Long kbId, Long tenantId, RetrievalStrategy strategy) {
        // 词法候选池与向量候选池口径一致，保证两路名次可比
        int cand = (strategy.candidatePool() > 0)
                ? Math.max(strategy.candidatePool(), topK) : Math.max(topK, vectorCands.size());
        List<RetrieveChunk> lexCands = lexicalSearchService.search(
                query, Math.max(strategy.lexicalPool(), cand), kbId, tenantId);
        if (lexCands.isEmpty()) {
            log.info("词法召回为空，退化为多样性重排（kbId={}）", kbId);
            return diversityRerank(vectorCands, topK, strategy.diversityMaxPerDoc());
        }

        // 名次表（候选已按各自相关度降序，1-based）
        Map<Long, Integer> vecRank = rankMap(vectorCands);
        Map<Long, Integer> lexRank = rankMap(lexCands);

        // 并集：以向量候选为主体（保留向量分数与元数据），补齐词法独有片段
        Map<Long, RetrieveChunk> merged = new LinkedHashMap<>();
        for (RetrieveChunk c : vectorCands) {
            merged.put(c.getChunkId(), c);
        }
        int lexOnly = 0;
        for (RetrieveChunk c : lexCands) {
            RetrieveChunk existing = merged.get(c.getChunkId());
            if (existing == null) {
                // 纯词法命中：向量分无意义，置 0 并保留词法分
                c.setScore(0.0);
                merged.put(c.getChunkId(), c);
                lexOnly++;
            } else {
                existing.setLexScore(c.getLexScore());
            }
        }
        log.info("混合融合：向量 {} 条 + 词法 {} 条（其中词法独有 {} 条）",
                vectorCands.size(), lexCands.size(), lexOnly);

        // RRF 名次融合：1/(k + rank)，某一路缺失的名次记 0（等价于不贡献该项）
        int k = strategy.rrfK();
        List<RetrieveChunk> fused = new ArrayList<>(merged.values());
        fused.sort((a, b) -> {
            double sa = rrf(k, vecRank.get(a.getChunkId())) + rrf(k, lexRank.get(a.getChunkId()));
            double sb = rrf(k, vecRank.get(b.getChunkId())) + rrf(k, lexRank.get(b.getChunkId()));
            return Double.compare(sb, sa);
        });
        // 多样性去冗余（可选）
        if (strategy.diversityMaxPerDoc() > 0) {
            fused = diversityRerank(fused, topK, strategy.diversityMaxPerDoc());
        }
        return fused;
    }

    /** 候选列表 → chunkId 名次表（1-based，重复 chunkId 保留最靠前的名次） */
    private static Map<Long, Integer> rankMap(List<RetrieveChunk> sorted) {
        Map<Long, Integer> rank = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.putIfAbsent(sorted.get(i).getChunkId(), i + 1);
        }
        return rank;
    }

    /** RRF 分数：名次缺失时贡献 0 */
    private static double rrf(int k, Integer rank) {
        return (rank == null || rank <= 0) ? 0.0 : 1.0 / (k + rank);
    }

    // ===== 以下为 legacy-hybrid（改造前的伪混合），保留仅用于 A/B 对比，勿用于生产 =====

    /** @deprecated 伪混合：向量序 + Java bigram 覆盖率 RRF，无索引、无 IDF、长文档占优。
     *  仅作为改造前基线保留，与 {@code hybrid-rrf} 做 A/B 对比用。 */
    @Deprecated
    private List<RetrieveChunk> hybridRerank(String query, List<RetrieveChunk> candidates, int topK) {
        Map<Long, Integer> vecRank = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            vecRank.put(candidates.get(i).getChunkId(), i + 1);
        }
        List<RetrieveChunk> lexSorted = new ArrayList<>(candidates);
        lexSorted.sort((a, b) -> Double.compare(lexicalScore(query, b), lexicalScore(query, a)));
        Map<Long, Integer> lexRank = new HashMap<>();
        for (int i = 0; i < lexSorted.size(); i++) {
            lexRank.put(lexSorted.get(i).getChunkId(), i + 1);
        }
        List<RetrieveChunk> fused = new ArrayList<>(candidates);
        fused.sort((a, b) -> {
            double sa = rrf(rrfK, vecRank.get(a.getChunkId())) + rrf(rrfK, lexRank.get(a.getChunkId()));
            double sb = rrf(rrfK, vecRank.get(b.getChunkId())) + rrf(rrfK, lexRank.get(b.getChunkId()));
            return Double.compare(sb, sa);
        });
        if (diversityMaxPerDoc > 0) {
            fused = diversityRerank(fused, topK, diversityMaxPerDoc);
        }
        return fused;
    }

    /** 文档多样性重排：按相关性降序，每轮从每个文档组取一个片段，
     *  单文档最多保留 diversityMaxPerDoc 个，避免单文档霸榜、提升结果多样性与 Precision。 */
    private static List<RetrieveChunk> diversityRerank(List<RetrieveChunk> candidates, int topK, int maxPerDoc) {
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

    /** 中文 bigram 集合（去空白），仅 legacy-hybrid 使用 */
    private static Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) return set;
        String t = text.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    /**
     * 词法相关性 = 查询 bigram 被片段覆盖的比例（查询覆盖率），仅 legacy-hybrid 使用。
     * 侧重"查询的需求被该片段满足"，比 Jaccard 更适配检索场景。
     */
    private static double lexicalScore(String query, RetrieveChunk c) {
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
