package com.qoobot.qoorag.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.qoobot.qoorag.dto.RetrieveChunk;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现（RAG-068）。
 * <p>经 {@code qoorag.vector.store=milvus} 启用；collection 字段：chunk_id(PK,Int64)、
 * embedding(FloatVector,dim)、kb_id/tenant_id/doc_id(Int64 标量，用于过滤)。
 * 检索召回 chunk_id + 距离后，复用 Chunk/Document 仓储回填内容等元数据（与 PgVectorStore 的 JOIN 等价）。</p>
 * <p><b>注意</b>：score 口径遵循设计文档——Milvus COSINE 返回距离为 {@code 1 - cosine}，
 * 故相似度取 {@code 1 - distance}；与 pgvector 的 {@code 1 - (embedding <=> query)} 一致。</p>
 */
@Service
@ConditionalOnProperty(value = "qoorag.vector.store", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final int INSERT_BATCH = 500;

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final MilvusClientV2 client;
    private final Gson gson = new Gson();

    @Value("${qoorag.milvus.collection:qoorag_vectors}")
    private String collection;
    @Value("${qoorag.milvus.dim:1024}")
    private int dim;
    @Value("${qoorag.milvus.m:16}")
    private int m;
    @Value("${qoorag.milvus.ef-construction:256}")
    private int efConstruction;
    @Value("${qoorag.milvus.ef-search:40}")
    private int efSearch;

    public MilvusVectorStore(ChunkRepository chunkRepository,
                             DocumentRepository documentRepository,
                             @Value("${qoorag.milvus.uri:}") String uri,
                             @Value("${qoorag.milvus.token:}") String token) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder();
        if (uri != null && !uri.isBlank()) {
            builder.uri(uri);
        }
        if (token != null && !token.isBlank()) {
            builder.token(token);
        }
        this.client = new MilvusClientV2(builder.build());
    }

    @PostConstruct
    public void init() {
        ensureIndex();
    }

    @Override
    public List<RetrieveChunk> querySimilar(float[] query, int limit, Long kbId, Long tenantId) {
        SearchReq req = SearchReq.builder()
                .collectionName(collection)
                .annsField("embedding")
                .data(Collections.singletonList(new FloatVec(query)))
                .topK(limit)
                .filter(buildFilter(kbId, tenantId))
                .outputFields(Collections.singletonList("chunk_id"))
                .searchParams(Collections.singletonMap("ef", efSearch))
                .build();

        SearchResp resp = client.search(req);
        List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);

        // 1) 提取 chunkId + 距离
        List<Long> chunkIds = new ArrayList<>(results.size());
        Map<Long, Double> similarityById = new LinkedHashMap<>();
        for (SearchResp.SearchResult r : results) {
            Object idObj = r.getEntity().get("chunk_id");
            if (idObj == null) {
                continue;
            }
            long chunkId = ((Number) idObj).longValue();
            // Milvus COSINE 返回距离（1 - cosine），相似度 = 1 - distance
            double similarity = 1.0 - Optional.ofNullable(r.getScore()).map(Number::doubleValue).orElse(1.0);
            chunkIds.add(chunkId);
            similarityById.put(chunkId, similarity);
        }
        if (chunkIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2) 复用 DB 回填内容/元数据（与 PgVectorStore 的 SQL JOIN 等价）
        Map<Long, Chunk> chunkMap = chunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(Chunk::getId, c -> c, (a, b) -> a));
        Set<Long> docIds = chunkMap.values().stream()
                .map(Chunk::getDocumentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Document> docMap = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(Document::getId, d -> d, (a, b) -> a));

        List<RetrieveChunk> out = new ArrayList<>(chunkIds.size());
        for (Long chunkId : chunkIds) {
            Chunk c = chunkMap.get(chunkId);
            if (c == null) {
                continue;
            }
            double sim = similarityById.getOrDefault(chunkId, 0.0);
            RetrieveChunk rc = new RetrieveChunk(
                    chunkId, chunkId, c.getDocumentId(), c.getSeq(), c.getContent(), sim);
            rc.setHeadingPath(c.getHeadingPath());
            rc.setPageNo(c.getPageNo());
            rc.setChunkType(c.getChunkType());
            rc.setParentChunkId(c.getParentChunkId());
            Document doc = (c.getDocumentId() != null) ? docMap.get(c.getDocumentId()) : null;
            rc.setDocumentName(doc != null ? doc.getName() : null);
            out.add(rc);
        }
        return out;
    }

    @Override
    public void upsert(Collection<ChunkVector> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        List<ChunkVector> list = new ArrayList<>(vectors);
        for (int i = 0; i < list.size(); i += INSERT_BATCH) {
            int end = Math.min(i + INSERT_BATCH, list.size());
            List<JsonObject> rows = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                ChunkVector cv = list.get(j);
                JsonObject row = new JsonObject();
                row.addProperty("chunk_id", cv.chunkId());
                row.add("embedding", gson.toJsonTree(toFloatList(cv.embedding())));
                row.addProperty("kb_id", cv.kbId());
                row.addProperty("tenant_id", cv.tenantId());
                if (cv.docId() != null) {
                    row.addProperty("doc_id", cv.docId());
                }
                rows.add(row);
            }
            client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
        }
        log.info("Milvus 向量写入完成：{} 条", list.size());
    }

    @Override
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String filter = "chunk_id in [" + chunkIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
        client.delete(DeleteReq.builder().collectionName(collection).filter(filter).build());
    }

    @Override
    public void deleteByDocument(Long docId) {
        if (docId == null) {
            return;
        }
        client.delete(DeleteReq.builder()
                .collectionName(collection)
                .filter("doc_id == " + docId)
                .build());
    }

    @Override
    public void ensureIndex() {
        try {
            Boolean exists = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
            if (Boolean.TRUE.equals(exists)) {
                log.info("Milvus collection 已存在：{}", collection);
                return;
            }
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(List.of(
                            CreateCollectionReq.FieldSchema.builder()
                                    .name("chunk_id").dataType(DataType.Int64)
                                    .isPrimaryKey(true).autoID(false).build(),
                            CreateCollectionReq.FieldSchema.builder()
                                    .name("embedding").dataType(DataType.FloatVector)
                                    .dimension(dim).build(),
                            CreateCollectionReq.FieldSchema.builder()
                                    .name("kb_id").dataType(DataType.Int64).build(),
                            CreateCollectionReq.FieldSchema.builder()
                                    .name("tenant_id").dataType(DataType.Int64).build(),
                            CreateCollectionReq.FieldSchema.builder()
                                    .name("doc_id").dataType(DataType.Int64).build()))
                    .build();
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collection)
                    .collectionSchema(schema)
                    .enableDynamicField(false)
                    .build());

            IndexParam idx = IndexParam.builder()
                    .fieldName("embedding")
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", m, "efConstruction", efConstruction))
                    .build();
            client.createIndex(CreateIndexReq.builder()
                    .collectionName(collection)
                    .indexParams(Collections.singletonList(idx))
                    .build());
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            log.info("Milvus collection 创建并加载完成：{}（dim={}）", collection, dim);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 初始化失败（collection=" + collection + "）：" + e.getMessage(), e);
        }
    }

    static String buildFilter(Long kbId, Long tenantId) {
        return "kb_id == " + kbId + " && tenant_id == " + tenantId;
    }

    static List<Float> toFloatList(float[] arr) {
        if (arr == null) {
            return Collections.emptyList();
        }
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }
}
