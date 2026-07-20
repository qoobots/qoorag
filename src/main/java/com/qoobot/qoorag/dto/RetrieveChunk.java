package com.qoobot.qoorag.dto;

/**
 * 检索召回片段 DTO — 包含 chunk 内容 + 相似度分数 + 所属文档信息
 */
public class RetrieveChunk {

    private Long id;             // vector_data.id
    private Long chunkId;
    private Long documentId;
    private Integer chunkSeq;    // chunk 在文档中的序号
    private String content;      // chunk 文本内容
    private double score;        // 余弦相似度（0~1，1 = 完全相同）

    public RetrieveChunk() {}

    public RetrieveChunk(Long id, Long chunkId, Long documentId, Integer chunkSeq,
                         String content, double score) {
        this.id = id;
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.chunkSeq = chunkSeq;
        this.content = content;
        this.score = score;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Integer getChunkSeq() { return chunkSeq; }
    public void setChunkSeq(Integer chunkSeq) { this.chunkSeq = chunkSeq; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    @Override
    public String toString() {
        return "RetrieveChunk{id=" + id + ", chunkId=" + chunkId +
                ", documentId=" + documentId + ", seq=" + chunkSeq +
                ", score=" + String.format("%.4f", score) +
                ", content='" + (content != null ? content.substring(0, Math.min(80, content.length())) + "..." : "null") + "'}";
    }
}
