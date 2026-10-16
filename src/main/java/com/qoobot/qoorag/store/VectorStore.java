package com.qoobot.qoorag.store;

import com.qoobot.qoorag.dto.RetrieveChunk;

import java.util.Collection;
import java.util.List;

/**
 * 向量存储抽象（RAG-068）：解耦检索/入库与具体向量后端。
 * <p>当前实现 {@link PgVectorStore}（pgvector）；后续可扩展 MilvusVectorStore，
 * 通过 {@code qoorag.vector.store} 配置切换，业务代码无需改动。</p>
 */
public interface VectorStore {

    /** 余弦相似度召回 top-{@code limit} 候选（不过滤 minScore，交由调用方处理） */
    List<RetrieveChunk> querySimilar(float[] query, int limit, Long kbId, Long tenantId);

    /** 批量写入向量（chunk 入库即写） */
    void upsert(Collection<ChunkVector> vectors);

    /** 按 chunkId 删除向量 */
    void deleteByChunkIds(Collection<Long> chunkIds);

    /** 按文档删除其全部向量（经 chunk 表反查） */
    void deleteByDocument(Long docId);

    /** 确保 ANN 索引存在（pgvector 由 PgvectorIndexMigrator 负责，此处为 no-op） */
    void ensureIndex();
}
