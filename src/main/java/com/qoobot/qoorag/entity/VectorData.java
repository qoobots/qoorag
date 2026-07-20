package com.qoobot.qoorag.entity;

import com.pgvector.PGvector;
import com.qoobot.qoorag.config.PGvectorUserType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;

/**
 * 向量（pgvector；4.9 受 tenant_id + RLS 约束）
 * <p>已接入 com.pgvector:pgvector 库，embedding 字段使用 PGvector 原生类型，
 * 支持 ANN 相似检索（<=> 余弦距离算子）。
 */
@Entity
@Table(name = "vector_data")
public class VectorData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Type(PGvectorUserType.class)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private PGvector embedding;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getKbId() { return kbId; }
    public void setKbId(Long kbId) { this.kbId = kbId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public PGvector getEmbedding() { return embedding; }
    public void setEmbedding(PGvector embedding) { this.embedding = embedding; }

    /** 便捷：以 float[] 读写 embedding */
    public float[] getEmbeddingArray() {
        return embedding != null ? embedding.toArray() : null;
    }
    public void setEmbeddingArray(float[] array) {
        this.embedding = new PGvector(array);
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
