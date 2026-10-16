package com.qoobot.qoorag.dto;

/**
 * 检索召回片段 DTO — 包含 chunk 内容 + 相关度分数 + 结构元数据 + 所属文档信息。
 * <p>
 * RAG-020 起携带元数据（标题路径 / 页码 / 块类型 / 文档名），用于引用溯源展示（RAG-042）与
 * Prompt 中的来源标注（RAG-041）。
 * <p>
 * 多路召回的分数口径：
 * <ul>
 *   <li>{@code score}：向量余弦相似度（0~1）；仅词法召回的片段为 0.0；</li>
 *   <li>{@code lexScore}：词法相关度（{@code ts_rank_cd}），仅词法召回时有值；</li>
 *   <li>精排（RerankService）会用 cross-encoder 分数覆写 {@code score}，沿用既有约定。</li>
 * </ul>
 */
public class RetrieveChunk {

    private Long id;             // vector_data.id（纯词法召回时为 chunk.id）
    private Long chunkId;
    private Long documentId;
    private Integer chunkSeq;    // chunk 在文档中的序号
    private String content;      // chunk 文本内容
    private double score;        // 余弦相似度（0~1，1 = 完全相同）

    // ===== RAG-020 结构元数据（可空）=====
    private String headingPath;  // 标题路径，如「第一章 概述 / 1.2 术语」
    private Integer pageNo;      // 页码
    private String chunkType;    // paragraph / heading / table / code / list / figure
    private String documentName; // 文档名（引用展示用）

    // ===== RAG-011 词法信号（可空）=====
    private Double lexScore;     // ts_rank_cd 词法相关度

    // ===== RAG-022 父子块（可空）=====
    private Long parentChunkId;  // 父块 ID；非空时上下文组装应回填父块内容（small-to-big）

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
    public String getHeadingPath() { return headingPath; }
    public void setHeadingPath(String headingPath) { this.headingPath = headingPath; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public Double getLexScore() { return lexScore; }
    public void setLexScore(Double lexScore) { this.lexScore = lexScore; }
    public Long getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(Long parentChunkId) { this.parentChunkId = parentChunkId; }

    @Override
    public String toString() {
        return "RetrieveChunk{id=" + id + ", chunkId=" + chunkId +
                ", documentId=" + documentId + ", seq=" + chunkSeq +
                ", score=" + String.format("%.4f", score) +
                (lexScore != null ? ", lex=" + String.format("%.4f", lexScore) : "") +
                (headingPath != null ? ", heading='" + headingPath + "'" : "") +
                ", content='" + (content != null ? content.substring(0, Math.min(80, content.length())) + "..." : "null") + "'}";
    }
}
