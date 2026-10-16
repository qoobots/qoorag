package com.qoobot.qoorag.store;

/**
 * 待写入向量库的单元（与具体后端无关的传输对象）。
 * docId 用于按文档级删除时的关联（后端无 doc 列时经 chunk 表反查）。
 */
public record ChunkVector(Long chunkId, Long docId, Long kbId, Long tenantId, float[] embedding) {
}
