# RAG-055 大规模数据性能验证 · 容量规划与压测方法

> 目标：验证 Qoorag 在 **10 万 / 100 万** 级 chunk 向量下的检索延迟、召回与资源占用，给出生产容量建议。
> 配套脚本：`scripts/perf/benchmark_pgvector.sql`（基于合成随机向量，零 embedding 配额消耗）。

## 1. 为什么用「合成随机向量」
向量索引的代价只与 **向量维度、向量条数、索引类型、查询 top-k、ef_search** 相关，与向量的语义无关。
因此用 `random()` 生成的向量压测索引，能直接反映生产在同等规模下的延迟分布，且**不消耗任何百炼 embedding 配额**，
可独立于 `EMBEDDING_API_KEY` 是否可用运行（本环境 key 失效不影响压测）。

> 注：召回率/相关性的语义验证请用真实文档 + `eval` 模块（RAG-043/051），不属于本压测范围。

## 2. 运行方式
```bash
# 前置：能连到 qoorag 库（docker compose 起的 qoorag-postgres，端口见 .env）
export QOORAG_DSN="postgresql://qoorag:${POSTGRES_PASSWORD}@localhost:5433/qoorag"

# 小试跑（先确认脚本可用，dim=256 更快）
psql "$QOORAG_DSN" -v n=20000 -v dim=256 -v ef=64 -f scripts/perf/benchmark_pgvector.sql

# 10 万级
psql "$QOORAG_DSN" -v n=100000 -v dim=1536 -v ef=64 -f scripts/perf/benchmark_pgvector.sql

# 100 万级（建议独立实例、内存 ≥ 8G 留给 shared_buffers/index）
psql "$QOORAG_DSN" -v n=1000000 -v dim=1536 -v ef=64 -f scripts/perf/benchmark_pgvector.sql
```
脚本对比两条路径的 `EXPLAIN ANALYZE` Execution Time：
- **A. HNSW 索引**（`enable_seqscan=off`，`hnsw.ef_search=:ef`）
- **B. 暴力全表扫描**（`enable_indexscan=off`，基线，验证加速比）

## 3. 关键调优项（写入建表/迁移即可生效）
| 项 | 建议 | 说明 |
|----|------|------|
| 索引类型 | `hnsw (vector_cosine_ops)` | 召回质量与延迟均衡；`ivfflat` 仅适合超大规模且可接受召回波动 |
| `m`（HNSW 构建） | 16 | 越大召回越好、构建/内存越高 |
| `ef_construction` | 64~128 | 构建期搜索宽度，影响索引质量 |
| `hnsw.ef_search`（查询期） | 32~128 | 与 `candidate-pool`(RAG-051) 协同；越大越准越慢 |
| 距离度量 | cosine | 与 embedding 模型一致 |
| 维度 | 1536（qwen 默认） | 维度翻倍约 2× 距离计算；可用 `halfvec` 降低内存 |

## 4. 规模容量参考（pgvector 公开基准 + 本架构实测预期）
> 以下为公开 pgvector HNSW 基准的量级参考（具体以本机 `EXPLAIN ANALYZE` 为准）：
- **10 万 × 1536d**：top-10 检索 **个位数~十几 ms**；索引内存约数百 MB。
- **100 万 × 1536d**：top-10 检索 **数十 ms**；HNSW 索引内存约数 GB，需 `shared_buffers` 能容纳热点层。
- 暴力扫描在 100 万级会退化到**秒级**，务必确保查询走 HNSW（`EXPLAIN` 出现 `Index Scan using idx_*`）。

## 5. 生产落地建议
1. **分 KB 隔离**：`vector_data.kb_id` 已建索引；超大租户可按 `kb_id` 分区（参考 RAG-021/ schema 的 `idx_vector_kb_tenant`），避免单表无限膨胀。
2. **小 KB 走暴力、大 KB 走 HNSW**：当某 KB 向量 < 数千时，Planner 可能选 seqscan 反而更快；无需强制。
3. **监控**：RAG-054 已暴露 `qoorag_retrieve_latency_seconds`（P95/P99）与 `qoorag_retrieve_hits_total`，压测/上线后用 Prometheus + `rag_alerts.yml` 看板跟踪回归。
4. **召回质量另测**：语义召回率用 `eval` 模块（RAG-043/051）在真实语料上度量，不在本压测范围。
5. **内存规划**：HNSW 索引常驻内存；100 万 × 1536d × 4B ≈ 6GB 原始 + 图结构开销，建议 DB 实例内存 ≥ 索引 1.5×。

## 6. 验收
- [ ] 10 万级 `EXPLAIN ANALYZE` top-10 < 50ms（HNSW）
- [ ] 100 万级 top-10 < 200ms（HNSW），且 `EXPLAIN` 确认走索引
- [ ] HNSW vs 暴力 加速比 ≥ 10×（10万级）
- [ ] 索引构建期间不影响在线检索（CONCURRENTLY 或低峰期）
