# RAG-068 向量存储可插拔 · Milvus 支持（设计态）

> 关联任务清单：`.docs/07_RAG升级改造产品需求任务清单.md` → EPIC-6 → RAG-068。
> 状态：**设计态完成**（目标 / 方案 / 取舍 / 验收 / 依赖）。完整实现需独立部署 Milvus 服务。

## 1. 背景与现状
当前 Qoorag 的向量层**强耦合 PostgreSQL + pgvector**：
- `RetrieveService` 用原生 SQL `... ORDER BY v.embedding <=> ? ...`，绑定 `com.pgvector.PGvector` 类型（`<=>` 为 pgvector 余弦算子）；
- 启动期 `PgvectorIndexMigrator`（保障 HNSW 索引）、`VectorDimensionReconciler`（pgvector 列维度对账）；
- `vector_data` 表列类型 `vector(dim)`，索引 `USING hnsw (embedding vector_cosine_ops)`。

全仓无任何 Milvus 依赖。因此**当前不支持 Milvus**，切换需先做存储抽象。

## 2. 目标
- 抽出 `VectorStore` 抽象，使向量读写与具体后端解耦；
- 保留现有 pgvector 实现作为默认后端，新增 **Milvus 实现**（cosine / HNSW）；
- 通过配置 `qoorag.vector.store` 在 `pgvector` 与 `milvus` 间切换，无需改业务代码；
- 提供 pgvector → Milvus 数据迁移工具，保证召回一致。

## 3. 业界方案
- **可插拔向量后端**：LangChain 的 `VectorStore` 接口、LlamaIndex `VectorStore` 抽象；Qoorag 复用同样思路，但只实现 pgvector + Milvus 两款。
- **Milvus 数据模型**：collection 可全局单库（用 `tenant_id`/`kb_id` 作为 partition key 或标量过滤字段），或按租户分 collection；metric_type=COSINE，index_type=HNSW（或 IVF_FLAT 兜底），字段至少含 `chunk_id`（唯一）、`embedding`（FloatVector）、`kb_id`、`tenant_id`、`content_hash`。
- **切换成本**：Milvus 需独立集群/容器，运维比 pgvector 重；适合**千万级 / 高并发 / 多模型多集合**场景，小规模 pgvector 更省心。

## 4. 与现有架构的接入点
1. **新增接口 `VectorStore`**（建议包 `com.qoobot.qoorag.store`）：
   ```java
   public interface VectorStore {
       void upsert(Collection<ChunkVector> vectors);          // chunk 入库即写向量
       List<VectorHit> querySimilar(float[] query, int topK,
                                    Long kbId, Long tenantId, double minScore);
       void deleteByChunkIds(Collection<Long> chunkIds);
       void deleteByDocument(Long docId);
       void ensureIndex();                                    // 建/校验 ANN 索引
   }
   ```
   其中 `ChunkVector{chunkId, docId, kbId, tenantId, embedding[], contentHash}`，`VectorHit{chunkId, similarity}`。
2. **`PgVectorStore`**：把现有 `RetrieveService.queryVectorDb` / `IngestService` 向量写入 / `vectorDataRepository` 逻辑迁入此实现；SQL 与 `PGvector` 绑定原样保留。
3. **`MilvusVectorStore`**：基于官方 `milvus-sdk-java`（`io.milvus:milvus-sdk-java:2.x`）；
   - 启动 `ensureIndex()`：若不存在 collection 则按 `qoorag.milvus.dim` 创建，建 HNSW 索引（metric=COSINE）；
   - `upsert`：转 `InsertParam`（用 `chunk_id` 作主键，标量字段 `kb_id`/`tenant_id` 用于过滤）；
   - `querySimilar`：`SearchParam` 设 `metric_type=COSINE`、`topK`，`expr = "kb_id == {kb} && tenant_id == {t}"`，返回 `chunk_id` + 距离→相似度 `1 - distance`。
4. **装配**：`VectorStoreConfig` 按 `qoorag.vector.store` 用 `@ConditionalOnProperty` 选 bean；`RetrieveService`/`IngestService` 注入 `VectorStore`（替换原有 `vectorDataRepository` 直接调用与 `<=>` SQL）。
5. **迁移器条件化**：`PgvectorIndexMigrator` / `VectorDimensionReconciler` 加 `@ConditionalOnProperty(value="qoorag.vector.store", havingValue="pgvector", matchIfMissing=true)`，仅 pgvector 时生效。
6. **迁移工具**：`MilvusMigrationService`（或一次性 `scripts/migrate_pgvector_to_milvus.sql` + 一个 Spring `CommandLineRunner`），批量读取 `vector_data` → `MilvusVectorStore.upsert`，跑完切配置。

## 5. 配置草案（application.yml）
```yaml
qoorag:
  vector:
    store: pgvector            # pgvector | milvus
    hnsw:
      ef-search: 40
  milvus:
    enabled: ${MILVUS_ENABLED:false}
    uri: ${MILVUS_URI:}        # 如 http://localhost:19530
    token: ${MILVUS_TOKEN:}
    collection: qoorag_vectors
    dim: 1536
    metric: COSINE
    index-type: HNSW
    m: 16
    ef-construction: 256
    ef-search: 40
```

## 6. 关键取舍
- **维度变化**：Milvus collection 维度在建库时固定；切换 embedding 模型（维度变）需重建 collection 或启用可变维度方案（如 Matryoshka RAG-064 截断）。`PgVectorStore` 当前用 `vector(dim)` 也类似，需同步处理。
- **一致性**：双写/迁移期间为最终一致；建议迁移完成并校验召回后再切读流量。
- **过滤表达**：Milvus 标量过滤 `expr` 需防注入（kb_id/tenant_id 为整数，参数化）。
- **运维**：Milvus 比 pgvector 重，仅在确有规模/并发诉求时启用；默认仍走 pgvector。

## 7. 验收计划
1. 配置 `qoorag.vector.store=milvus` 后应用正常启动，`ensureIndex` 自动建 collection + HNSW 索引；
2. 入库文档其向量写入 Milvus（可用 `milvus-cli` / Attu 验证条数）；
3. 检索 P95 延迟在 **百万级** 不退化（对比 pgvector 基线，参考 RAG-055 压测法）；
4. 提供 pgvector→Milvus 迁移工具，迁移后**余弦 top-k 重合率 ≥ 95%**；
5. 切回 `pgvector` 无数据丢失、问答正常。

## 8. 依赖与风险
- **依赖**：RAG-010（pgvector ANN 基准）、RAG-020（chunk/vector 表结构）、现有向量层；需可访问的 **Milvus 服务**。
- **风险**：Milvus 版本/SDK API 变动；维度与模型切换的重建成本；跨后端迁移的召回偏差需回归评测（复用 `eval` 模块 RAG-043/051）。
- **优先级/工作量**：P2，约 **13d**。
