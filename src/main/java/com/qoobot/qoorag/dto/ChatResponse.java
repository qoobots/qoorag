package com.qoobot.qoorag.dto;

import com.qoobot.qoorag.dto.RetrieveChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat 问答响应 DTO — answer + sources + usage + citations（RAG-042 句子级引用归因）
 */
public class ChatResponse {

    private String answer;
    private String model;
    private Usage usage;
    private List<SourceInfo> sources;
    /** RAG-042：句子级引用归因。每条对应答案中带 [n] 标记的一个句子，解析出所引用的来源 */
    private List<Citation> citations;

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

    /**
     * 精简后的参考来源（透出文档 ID、片段主键、片段序号、相似度、内容摘要 + RAG-020 结构元数据）。
     * 顺序与 prompt 中【参考资料 N】编号同序，保证 [n] 引用可正确回溯到此条目。
     */
    public static class SourceInfo {
        private Long documentId;
        private Long chunkId;      // chunk 表主键，用于召回评估标注 relevantChunkIds
        private Integer chunkSeq;
        private double score;
        private String snippet;    // 前150字符摘要
        // ===== RAG-042 扩展的结构元数据（可空）=====
        private String documentName; // 文档名（引用展示用）
        private String headingPath;  // 标题路径，如「第一章 概述 / 1.2 术语」
        private Integer pageNo;      // 页码
        private String chunkType;    // paragraph / heading / table / code / list / figure

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
        public String getDocumentName() { return documentName; }
        public void setDocumentName(String documentName) { this.documentName = documentName; }
        public String getHeadingPath() { return headingPath; }
        public void setHeadingPath(String headingPath) { this.headingPath = headingPath; }
        public Integer getPageNo() { return pageNo; }
        public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
        public String getChunkType() { return chunkType; }
        public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    }

    /** RAG-042：单句引用归因。text 为带 [n] 标记的原句，sourceIndices 为引用的来源序号，
     *  chunkIds / documentIds 为解析后对应的片段/文档主键，供前端可点击溯源 */
    public static class Citation {
        private String text;
        private List<Integer> sourceIndices;
        private List<Long> chunkIds;
        private List<Long> documentIds;

        public Citation() {}
        public Citation(String text, List<Integer> sourceIndices, List<Long> chunkIds, List<Long> documentIds) {
            this.text = text;
            this.sourceIndices = sourceIndices;
            this.chunkIds = chunkIds;
            this.documentIds = documentIds;
        }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public List<Integer> getSourceIndices() { return sourceIndices; }
        public void setSourceIndices(List<Integer> sourceIndices) { this.sourceIndices = sourceIndices; }
        public List<Long> getChunkIds() { return chunkIds; }
        public void setChunkIds(List<Long> chunkIds) { this.chunkIds = chunkIds; }
        public List<Long> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
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
                .map(c -> {
                    SourceInfo si = new SourceInfo(
                            c.getDocumentId(),
                            c.getChunkId(),
                            c.getChunkSeq(),
                            c.getScore(),
                            c.getContent() != null && c.getContent().length() > 150
                                    ? c.getContent().substring(0, 150) + "..." : c.getContent());
                    si.setDocumentName(c.getDocumentName());
                    si.setHeadingPath(c.getHeadingPath());
                    si.setPageNo(c.getPageNo());
                    si.setChunkType(c.getChunkType());
                    return si;
                })
                .toList();
        r.citations = parseCitations(answer, chunks);
        return r;
    }

    /** 解析答案中的 [n] / 【n】 标记为句子级引用（仅接受 1..来源数 范围内的序号） */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]|【(\\d+)】");

    public static List<Citation> parseCitations(String answer, List<RetrieveChunk> chunks) {
        List<Citation> out = new ArrayList<>();
        if (answer == null || chunks == null || chunks.isEmpty()) {
            return out;
        }
        int nSources = chunks.size();
        // 按句切分（保留句末标点以便还原原句）
        String[] sentences = answer.split("(?<=[。！？!?；;\\n])");
        for (String sentence : sentences) {
            Matcher m = CITATION_PATTERN.matcher(sentence);
            List<Integer> idx = new ArrayList<>();
            while (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                try {
                    int n = Integer.parseInt(g);
                    if (n >= 1 && n <= nSources) {
                        idx.add(n);
                    }
                } catch (NumberFormatException ignored) { }
            }
            if (!idx.isEmpty()) {
                List<Long> chunkIds = new ArrayList<>();
                List<Long> docIds = new ArrayList<>();
                for (int n : idx) {
                    RetrieveChunk c = chunks.get(n - 1);
                    if (c.getChunkId() != null) {
                        chunkIds.add(c.getChunkId());
                    }
                    if (c.getDocumentId() != null) {
                        docIds.add(c.getDocumentId());
                    }
                }
                out.add(new Citation(sentence.trim(), idx, chunkIds, docIds));
            }
        }
        return out;
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
    public List<Citation> getCitations() { return citations; }
    public void setCitations(List<Citation> citations) { this.citations = citations; }
}
