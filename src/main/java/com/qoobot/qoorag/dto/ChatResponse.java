package com.qoobot.qoorag.dto;

import com.qoobot.qoorag.dto.RetrieveChunk;
import java.util.List;

/**
 * Chat 问答响应 DTO — answer + sources + usage
 */
public class ChatResponse {

    private String answer;
    private String model;
    private Usage usage;
    private List<SourceInfo> sources;

    public ChatResponse() {}

    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        public Usage() {}
        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    }

    /** 精简后的参考来源（透出文档 ID、片段主键、片段序号、相似度、内容摘要） */
    public static class SourceInfo {
        private Long documentId;
        private Long chunkId;      // chunk 表主键，用于召回评估标注 relevantChunkIds
        private Integer chunkSeq;
        private double score;
        private String snippet;  // 前150字符摘要

        public SourceInfo() {}
        public SourceInfo(Long documentId, Long chunkId, Integer chunkSeq, double score, String snippet) {
            this.documentId = documentId;
            this.chunkId = chunkId;
            this.chunkSeq = chunkSeq;
            this.score = score;
            this.snippet = snippet;
        }
        public Long getDocumentId() { return documentId; }
        public void setDocumentId(Long documentId) { this.documentId = documentId; }
        public Long getChunkId() { return chunkId; }
        public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
        public Integer getChunkSeq() { return chunkSeq; }
        public void setChunkSeq(Integer chunkSeq) { this.chunkSeq = chunkSeq; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
    }

    // ---- 工厂方法 ----

    public static ChatResponse of(String answer, String model,
                                   int promptTokens, int completionTokens,
                                   List<RetrieveChunk> chunks) {
        ChatResponse r = new ChatResponse();
        r.answer = answer;
        r.model = model;
        r.usage = new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
        r.sources = chunks.stream()
                .map(c -> new SourceInfo(
                        c.getDocumentId(),
                        c.getChunkId(),
                        c.getChunkSeq(),
                        c.getScore(),
                        c.getContent() != null && c.getContent().length() > 150
                                ? c.getContent().substring(0, 150) + "..." : c.getContent()))
                .toList();
        return r;
    }

    // ---- getters/setters ----

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }
    public List<SourceInfo> getSources() { return sources; }
    public void setSources(List<SourceInfo> sources) { this.sources = sources; }
}
