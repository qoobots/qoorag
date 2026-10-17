package com.qoobot.qoorag.store;

import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MilvusVectorStore 端到端集成测试：用 Testcontainers 起 Milvus standalone，
 * 验证 upsert / querySimilar / delete 全链路（RAG-068 设计第 7 节验收）。
 * <p>无 Docker 环境自动跳过（assumeTrue）。enrichment 钩子被覆写，脱离真实 JPA 仓储。</p>
 */
class MilvusVectorStoreIntegrationTest {

    private static MilvusContainer milvus;
    private static TestableMilvusVectorStore store;
    private static final int DIM = 4;

    @BeforeAll
    static void start() {
        String external = System.getenv("MILVUS_URI");
        boolean useExternal = external != null && !external.isBlank();
        boolean enabled = useExternal
                || ("true".equalsIgnoreCase(System.getProperty("run.milvus.it"))
                    && DockerClientFactory.instance().isDockerAvailable());
        assumeTrue(enabled,
                "Milvus 集成测试未启用：设置 MILVUS_URI（指向已运行的 Milvus）或 -Drun.milvus.it=true（需可用 Docker）以运行");
        if (useExternal) {
            // 已有外部运行的 Milvus（如本地 `docker run ... milvus-standalone`），直接连，跳过 Testcontainers
            store = new TestableMilvusVectorStore(external);
        } else {
            milvus = new MilvusContainer(DockerImageName.parse("milvusdb/milvus:v2.6.24"));
            milvus.start();
            String uri = "http://" + milvus.getHost() + ":" + milvus.getMappedPort(19530);
            store = new TestableMilvusVectorStore(uri);
        }
        store.ensureIndex();
    }

    @AfterAll
    static void stop() {
        if (milvus != null && milvus.isRunning()) {
            milvus.stop();
        }
    }

    @Test
    void upsertAndQuerySimilar_roundtrip() {
        float[] q = {0.1f, 0.2f, 0.3f, 0.4f};
        List<ChunkVector> vectors = List.of(
                new ChunkVector(1L, 10L, 100L, 1L, q),
                new ChunkVector(2L, 10L, 100L, 1L, new float[]{0.9f, 0.1f, 0.2f, 0.3f}),
                new ChunkVector(3L, 10L, 100L, 1L, new float[]{0.2f, 0.8f, 0.1f, 0.4f}),
                new ChunkVector(4L, 10L, 100L, 1L, new float[]{0.5f, 0.5f, 0.5f, 0.5f}));
        store.upsert(vectors);

        List<RetrieveChunk> res = store.querySimilar(q, 4, 100L, 1L);
        assertEquals(4, res.size(), "应召回全部 4 条");
        assertEquals(1L, res.get(0).getChunkId(), "完全相同向量应排第一");
        for (int i = 1; i < res.size(); i++) {
            assertTrue(res.get(i - 1).getScore() >= res.get(i).getScore(), "相似度应单调不增");
        }
        // 诊断：打印分数以确定 getScore 语义（distance vs similarity）
        System.out.println("[MilvusVectorStore IT] scores=" + res.stream()
                .map(r -> r.getChunkId() + ":" + String.format("%.4f", r.getScore()))
                .collect(Collectors.toList()));
    }

    @Test
    void deleteByChunkIds_removesVector() {
        float[] q = {0.1f, 0.2f, 0.3f, 0.4f};
        List<ChunkVector> vectors = List.of(
                new ChunkVector(11L, 10L, 100L, 2L, q),
                new ChunkVector(12L, 10L, 100L, 2L, new float[]{0.9f, 0.1f, 0.2f, 0.3f}),
                new ChunkVector(13L, 10L, 100L, 2L, new float[]{0.2f, 0.8f, 0.1f, 0.4f}),
                new ChunkVector(14L, 10L, 100L, 2L, new float[]{0.5f, 0.5f, 0.5f, 0.5f}));
        store.upsert(vectors);

        store.deleteByChunkIds(List.of(11L));
        List<RetrieveChunk> res = store.querySimilar(q, 4, 100L, 2L);
        assertTrue(res.stream().noneMatch(r -> r.getChunkId() == 11L), "删除后不应召回 chunk 11");
        assertEquals(3, res.size());
    }

    @Test
    void deleteByDocument_removesAllInDoc() {
        float[] q = {0.1f, 0.2f, 0.3f, 0.4f};
        List<ChunkVector> vectors = List.of(
                new ChunkVector(21L, 20L, 100L, 3L, q),
                new ChunkVector(22L, 20L, 100L, 3L, new float[]{0.9f, 0.1f, 0.2f, 0.3f}),
                new ChunkVector(23L, 20L, 100L, 3L, new float[]{0.2f, 0.8f, 0.1f, 0.4f}),
                new ChunkVector(24L, 20L, 100L, 3L, new float[]{0.5f, 0.5f, 0.5f, 0.5f}));
        store.upsert(vectors);

        store.deleteByDocument(20L);
        List<RetrieveChunk> res = store.querySimilar(q, 4, 100L, 3L);
        assertTrue(res.isEmpty(), "删除文档后不应召回任何块");
    }

    /** 覆写 enrichment 钩子，脱离真实 JPA 仓储，返回合成实体 */
    static class TestableMilvusVectorStore extends MilvusVectorStore {
        TestableMilvusVectorStore(String uri) {
            super(null, null, uri, null, "test_vectors", DIM, 8, 128, 40);
        }

        @Override
        protected Map<Long, Chunk> loadChunks(Collection<Long> ids) {
            Map<Long, Chunk> m = new HashMap<>();
            for (Long id : ids) {
                Chunk c = new Chunk();
                c.setId(id);
                c.setDocumentId(10L);
                c.setSeq(1);
                c.setContent("content-" + id);
                c.setHeadingPath("H" + id);
                c.setPageNo(1);
                c.setChunkType("paragraph");
                m.put(id, c);
            }
            return m;
        }

        @Override
        protected Map<Long, Document> loadDocuments(Collection<Long> ids) {
            Map<Long, Document> m = new HashMap<>();
            for (Long id : ids) {
                Document d = new Document();
                d.setId(id);
                d.setName("doc-" + id);
                m.put(id, d);
            }
            return m;
        }
    }
}
