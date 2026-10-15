package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 词法检索服务（RAG-011）：PostgreSQL 全文检索（GIN + tsvector）。
 * <p>
 * <b>为什么不是「伪混合」</b>：改造前系统只有「向量余弦 + Java 自造中文 bigram 覆盖率 RRF」，
 * 词法信号没有索引、没有 IDF、排序不公平（长文档天然占优）。本服务把词法召回下沉到数据库：
 * <ul>
 *   <li>{@code chunk.tsv} 为生成列，由 {@code qoorag_cjk_tsvector(content)} 自动维护（ASCII 词 + CJK 二元组）；</li>
 *   <li>GIN 索引支撑 {@code tsv @@ qoorag_cjk_tsquery(?)} 的谓词下推；</li>
 *   <li>排序用 {@code ts_rank_cd}（PG 标准词法相关度，考虑词频与覆盖密度）。</li>
 * </ul>
 * <p>
 * <b>与真 BM25 的差距及取舍</b>：PostgreSQL 未内置 BM25（{@code ts_rank_cd} 是 cover-density
 * 排序，不是 BM25）。考虑到：
 * <ol>
 *   <li>混合检索采用 RRF 做<b>名次</b>融合，只需要相对排序而非绝对分值，RRF 对分值口径不敏感；</li>
 *   <li>引入 {@code pg_jieba}/{@code zhparser} 需自行编译镜像，引入 ES 则违背「轻量自托管」定位；</li>
 *   <li>本项目的词法短板在「没有索引 + 没有 IDF」，GIN + ts_rank_cd 已解决主要矛盾。</li>
 * </ol>
 * 故采用 PG 原生全文检索。若后续需要严格 BM25，只需把本类内部实现替换为 ES/BM25 实现，
 * 上层 {@link RetrieveService} 的融合逻辑无需改动。
 */
@Service
public class LexicalSearchService {

    private static final Logger log = LoggerFactory.getLogger(LexicalSearchService.class);

    private final JdbcTemplate jdbcTemplate;

    /** 词法召回条数；<=0 表示关闭词法检索 */
    @Value("${qoorag.retrieval.lexical-pool:200}")
    private int lexicalPool;

    /** 最低词法相关度（ts_rank_cd），<=0 不过滤 */
    @Value("${qoorag.retrieval.lexical-min-score:0.0}")
    private double lexicalMinScore;

    private static final RowMapper<RetrieveChunk> ROW_MAPPER = (rs, rn) -> {
        RetrieveChunk c = new RetrieveChunk(
                rs.getLong("chunk_id"),
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getInt("seq"),
                rs.getString("content"),
                rs.getDouble("lex_score"));
        c.setLexScore(rs.getDouble("lex_score"));
        c.setHeadingPath(rs.getString("heading_path"));
        c.setPageNo(rs.getObject("page_no") != null ? rs.getInt("page_no") : null);
        c.setChunkType(rs.getString("chunk_type"));
        c.setDocumentName(rs.getString("document_name"));
        return c;
    };

    public LexicalSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isEnabled() {
        return lexicalPool > 0;
    }

    /**
     * 词法召回：按 query 的 CJK bigram + ASCII 词检索同租户同知识库下的 chunk。
     *
     * @param query    用户原始查询（内部用与索引一致的分词器切分）
     * @param limit    返回条数
     * @param kbId     知识库 ID
     * @param tenantId 租户 ID
     * @return 按词法相关度降序的候选；分词结果为空（如纯符号查询）时返回空列表
     */
    public List<RetrieveChunk> search(String query, int limit, Long kbId, Long tenantId) {
        if (!isEnabled() || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String sql = "SELECT c.id AS chunk_id, c.document_id, c.seq, c.content, "
                + "c.heading_path, c.page_no, c.chunk_type, d.name AS document_name, "
                + "ts_rank_cd(c.tsv, qoorag_cjk_tsquery(?)) AS lex_score "
                + "FROM chunk c JOIN document d ON d.id = c.document_id "
                + "WHERE c.kb_id = ? AND c.tenant_id = ? "
                + "AND c.tsv @@ qoorag_cjk_tsquery(?) "
                + "ORDER BY lex_score DESC LIMIT ?";

        try {
            List<RetrieveChunk> out = jdbcTemplate.query(sql, (PreparedStatement ps) -> {
                ps.setString(1, query);
                ps.setLong(2, kbId);
                ps.setLong(3, tenantId);
                ps.setString(4, query);
                ps.setInt(5, limit);
            }, ROW_MAPPER);
            if (lexicalMinScore > 0.0) {
                List<RetrieveChunk> filtered = new ArrayList<>(out.size());
                for (RetrieveChunk c : out) {
                    if (c.getLexScore() >= lexicalMinScore) {
                        filtered.add(c);
                    }
                }
                out = filtered;
            }
            log.info("词法召回 kbId={} tenantId={} limit={} -> {} 条", kbId, tenantId, limit, out.size());
            return out;
        } catch (Exception e) {
            // 词法检索失败不应拖垮整条检索链路：降级为纯向量检索
            log.warn("词法检索失败，降级为纯向量检索: {}", e.getMessage());
            return List.of();
        }
    }

    /** 暴露配置供评估接口覆盖 */
    public int getLexicalPool() { return lexicalPool; }
    public double getLexicalMinScore() { return lexicalMinScore; }

    /** 评估接口临时覆盖词法配置（不影响 @Value 默认值） */
    public void overrideConfig(Integer pool, Double minScore) {
        if (pool != null) this.lexicalPool = pool;
        if (minScore != null) this.lexicalMinScore = minScore;
    }
}
