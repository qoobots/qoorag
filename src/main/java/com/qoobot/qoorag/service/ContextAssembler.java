package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文组装器（RAG-041）：把召回片段组装成喂给 LLM 的参考资料文本。
 * <p>
 * 改造前的问题：{@code ApiController} 直接 {@code chunks.stream().map(getContent)} 裸拼接 ——
 * 不去重、不控制 token 预算、不考虑位置偏差（Lost in the Middle）、不带来源信息。
 * <p>
 * 本服务按序做四件事：
 * <ol>
 *   <li><b>去重</b>：按内容指纹去重，避免重复片段挤占上下文；</li>
 *   <li><b>预算裁剪</b>：按估算 token 数累加，超出 {@code max-context-tokens} 的片段丢弃
 *       （高相关片段优先保留，低相关先丢）；</li>
 *   <li><b>位置重排</b>：缓解 "Lost in the Middle"——把最相关的放首尾、次相关的放中间；</li>
 *   <li><b>来源标注</b>：每块前置「文档名 / 标题路径 / 页码」，既帮助模型归因，也让引用可点击。</li>
 * </ol>
 * 组装结果 {@link AssembledContext} 的 {@code used} 与 prompt 中【参考资料 N】编号同序，
 * 供 API 的 {@code sources} 回传与句子级引用归因（RAG-042）对齐。
 */
@Service
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    /** 参考资料的最大估算 token 数；<=0 表示不限制 */
    @Value("${qoorag.retrieval.max-context-tokens:3000}")
    private int maxContextTokens;

    /** 是否启用 Lost-in-the-Middle 位置重排 */
    @Value("${qoorag.retrieval.context-reorder:true}")
    private boolean contextReorder;

    /** 单块内容在 Prompt 中的最大字符数；<=0 表示不截断 */
    @Value("${qoorag.retrieval.max-chunk-chars:1200}")
    private int maxChunkChars;

    /** 组装结果 */
    public record AssembledContext(String promptContext, List<RetrieveChunk> used,
                                   int totalTokens, int droppedByBudget, int droppedByDup) {}

    /**
     * 组装参考资料文本。
     *
     * @param candidates 已按相关度降序排列的召回片段（精排后）
     * @return 组装结果；输入为空时返回空上下文
     */
    public AssembledContext assemble(List<RetrieveChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new AssembledContext("", List.of(), 0, 0, 0);
        }

        // 1. 去重（按内容指纹）
        List<RetrieveChunk> deduped = new ArrayList<>(candidates.size());
        Set<String> seen = new LinkedHashSet<>();
        int droppedByDup = 0;
        for (RetrieveChunk c : candidates) {
            String fp = fingerprint(c.getContent());
            if (fp != null && !seen.add(fp)) {
                droppedByDup++;
                continue;
            }
            deduped.add(c);
        }

        // 2. 预算裁剪（保留高相关，丢弃低相关）
        List<RetrieveChunk> kept = new ArrayList<>(deduped.size());
        int totalTokens = 0;
        int droppedByBudget = 0;
        for (RetrieveChunk c : deduped) {
            int tokens = estimateTokens(c.getContent());
            if (maxContextTokens > 0 && !kept.isEmpty() && totalTokens + tokens > maxContextTokens) {
                droppedByBudget++;
                continue;
            }
            kept.add(c);
            totalTokens += tokens;
            if (maxContextTokens > 0 && totalTokens >= maxContextTokens) {
                // 预算已用尽，后续片段全部丢弃
                droppedByBudget += deduped.size() - kept.size();
                break;
            }
        }

        // 3. 位置重排（Lost in the Middle 缓解）
        List<RetrieveChunk> ordered = contextReorder ? reorder(kept) : kept;

        // 4. 渲染 Prompt 文本（含来源标注）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            RetrieveChunk c = ordered.get(i);
            sb.append("【参考资料 ").append(i + 1).append("】");
            String origin = originLabel(c);
            if (!origin.isEmpty()) {
                sb.append("（来源：").append(origin).append("）");
            }
            sb.append("\n").append(truncate(c.getContent())).append("\n\n");
        }

        log.info("上下文组装：输入 {} → 去重后 {} → 预算内 {}（丢重 {} / 丢预算 {}），估算 {} tokens",
                candidates.size(), deduped.size(), kept.size(), droppedByDup, droppedByBudget, totalTokens);
        // 注意：used 必须与 prompt 中【参考资料 N】的编号同序（均为 ordered），
        // 否则答案里的 [n] 引用会错指到 sources 列表的错误位置（RAG-042 句子级归因依赖此对齐）
        return new AssembledContext(sb.toString().stripTrailing(), ordered, totalTokens,
                droppedByBudget, droppedByDup);
    }

    /**
     * 位置重排（Lost in the Middle 缓解）：把「最相关放两端、最不相关放中间」。
     * <p>
     * 输入已按相关度降序。做法：偶数位从序列头部取、奇数位从序列尾部取，
     * 得到 U 型分布——第 1 相关在首位、第 2 相关在末位、第 3 相关在次首位，
     * 最不相关的集中在中间。例 {@code [1,2,3,4,5]} → {@code [1,5,2,4,3]}。
     */
    static List<RetrieveChunk> reorder(List<RetrieveChunk> sorted) {
        List<RetrieveChunk> out = new ArrayList<>(sorted.size());
        int lo = 0;
        int hi = sorted.size() - 1;
        for (int i = 0; i < sorted.size(); i++) {
            out.add((i % 2 == 0) ? sorted.get(lo++) : sorted.get(hi--));
        }
        return out;
    }

    /** 来源标注：文档名 / 标题路径 / 页码（可空项自动省略） */
    static String originLabel(RetrieveChunk c) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(c.getDocumentName())) {
            sb.append(c.getDocumentName());
        }
        if (notBlank(c.getHeadingPath())) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(c.getHeadingPath());
        }
        if (c.getPageNo() != null && c.getPageNo() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("第 ").append(c.getPageNo()).append(" 页");
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 单块内容截断，避免超长块挤爆预算 */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        if (maxChunkChars > 0 && content.length() > maxChunkChars) {
            return content.substring(0, maxChunkChars) + "…（内容过长已截断）";
        }
        return content;
    }

    /** 内容指纹：规范化（去空白）+ SHA-256 前 16 位十六进制 */
    static String fingerprint(String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必然存在；兜底用内容本身
            return normalized;
        }
    }

    /** token 估算：CJK 按字数、ASCII 连续字母数字串按词数 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int asciiWords = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                cjk++;
                inWord = false;
                continue;
            }
            boolean wordChar = c < 128 && (Character.isLetterOrDigit(c) || c == '_');
            if (wordChar && !inWord) {
                asciiWords++;
                inWord = true;
            } else if (!wordChar) {
                inWord = false;
            }
        }
        return cjk + asciiWords;
    }

    public int getMaxContextTokens() { return maxContextTokens; }
    public boolean isContextReorder() { return contextReorder; }
    public int getMaxChunkChars() { return maxChunkChars; }

    /** 评估接口临时覆盖配置 */
    public void overrideConfig(Integer maxTokens, Boolean reorder, Integer maxChunkChars) {
        if (maxTokens != null) this.maxContextTokens = maxTokens;
        if (reorder != null) this.contextReorder = reorder;
        if (maxChunkChars != null) this.maxChunkChars = maxChunkChars;
    }

    /** 仅供测试/调试：当前配置 Map */
    public Map<String, Object> configSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxContextTokens", maxContextTokens);
        m.put("contextReorder", contextReorder);
        m.put("maxChunkChars", maxChunkChars);
        return m;
    }
}
