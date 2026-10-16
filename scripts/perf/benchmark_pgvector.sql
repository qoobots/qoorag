-- =============================================================================
-- RAG-055 大规模向量性能压测（pgvector）
-- -----------------------------------------------------------------------------
-- 用法（需本地有 psql 且能连到 qoorag 库）：
--   psql "$QOORAG_DSN" -v n=100000 -v dim=1536 -v ef=64 -f benchmark_pgvector.sql
-- 参数：
--   n   向量条数（10万/100万 视机器规格调整）
--   dim 向量维度（生产为 1536；小机器可先用 256 试跑）
--   ef  HNSW 查询期 ef_search（越大越准越慢，建议 32~128）
--
-- 说明：使用「合成随机向量」，与语义无关，仅压测 索引/规模/维度 的影响，
--      因此不消耗任何 embedding 配额；可独立于百炼 key 运行。
-- 注意：仅创建独立的 perf_vec 表，不触碰业务数据；跑完可 DROP TABLE perf_vec;
-- =============================================================================
\set ON_ERROR_STOP on
\pset timing on

DROP TABLE IF EXISTS perf_vec;
CREATE TABLE perf_vec (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kb_id     bigint NOT NULL DEFAULT 1,
    embedding vector(:dim)
);

-- 批量生成 N 条随机向量（每行为一个 dim 维随机向量）
INSERT INTO perf_vec (embedding)
SELECT (
    SELECT ('[' || string_agg(random()::text, ',') || ']')::vector
    FROM generate_series(1, :dim)
) FROM generate_series(1, :n);

ANALYZE perf_vec;

-- HNSW 索引（生产推荐 m=16，ef_construction 取 64~128）
CREATE INDEX idx_perf_hnsw
    ON perf_vec USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 取一个固定查询向量用于复现
SELECT ('[' || string_agg(random()::text, ',' ) || ']')::vector AS q
INTO TEMP test_q FROM generate_series(1, :dim);

-- -------- A. HNSW 路径（默认） --------
SET enable_seqscan = off;        -- 强制走索引
SET hnsw.ef_search = :ef;

\echo '=== HNSW top-10 ==='
EXPLAIN ANALYZE
SELECT id FROM perf_vec
ORDER BY embedding <=> (SELECT q FROM test_q) LIMIT 10;

\echo '=== HNSW top-100 ==='
EXPLAIN ANALYZE
SELECT id FROM perf_vec
ORDER BY embedding <=> (SELECT q FROM test_q) LIMIT 100;

-- -------- B. 暴力全表扫描（基线，验证索引加速比） --------
SET enable_seqscan = on;
SET enable_indexscan = off;
SET enable_indexonlyscan = off;

\echo '=== 暴力扫描 top-10（基线） ==='
EXPLAIN ANALYZE
SELECT id FROM perf_vec
ORDER BY embedding <=> (SELECT q FROM test_q) LIMIT 10;

-- 还原
RESET enable_seqscan;
RESET enable_indexscan;
RESET enable_indexonlyscan;
RESET hnsw.ef_search;

\echo '压测完成。对比 A/B 的 Execution Time 即可得 HNSW 加速比。'
