package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.IngestTask;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.entity.VectorData;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import com.qoobot.qoorag.store.ChunkVector;
import com.qoobot.qoorag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文档入库流水线编排：解析 → 分块 → Embedding → 入库。
 * <p>状态机：PENDING → PROCESSING → COMPLETED / FAILED
 *
 * <p><b>RAG-020 元数据</b>：文档与分块均写入结构元数据（标题路径 / 页码 / 字符区间 / 块类型 /
 * token 数 / 内容哈希 / MIME / 大小），用于引用溯源、结构化过滤与增量去重。
 *
 * <p><b>RAG-021 结构感知切块</b>：默认策略 {@code structure} 按 Markdown 标题层级切分并保留
 * 标题路径，表格 / 代码块 / 列表作为原子单元不被切断；固定长度切块保留为 {@code fixed} 策略
 * （改造前行为），通过 {@code qoorag.ingest.chunk-strategy} 切换。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final int CHUNK_SIZE = 400;
    private static final int CHUNK_OVERLAP = 100;
    private static final int CHUNK_HARD_LIMIT = 500;

    /** 分块策略：structure（默认，RAG-021）/ fixed（改造前行为） */
    @Value("${qoorag.ingest.chunk-strategy:structure}")
    private String chunkStrategy;

    /** RAG-022 父子块：连续多少个子块聚合成一个父块（生成上下文单元）；<=1 表示不启用 */
    @Value("${qoorag.ingest.parent-chunk-group-size:4}")
    private int parentChunkGroupSize;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorDataRepository vectorDataRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ContentSafetyService contentSafety;
    private final ContextualChunkService contextualChunkService;

    /** 向量存储抽象（RAG-068）：字段注入，避免破坏 7 参构造的单测；缺失时回退 vectorDataRepository */
    @Autowired(required = false)
    private VectorStore vectorStore;

    public IngestService(DocumentRepository documentRepository,
                         ChunkRepository chunkRepository,
                         VectorDataRepository vectorDataRepository,
                         DocumentParserService parserService,
                         EmbeddingService embeddingService,
                         ContentSafetyService contentSafety,
                         ContextualChunkService contextualChunkService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorDataRepository = vectorDataRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.contentSafety = contentSafety;
        this.contextualChunkService = contextualChunkService;
    }

    /**
     * 执行单文件入库流水线
     *
     * @param file        上传文件
     * @param kbId        知识库 ID
     * @param tenantId    租户 ID
     * @return Document 实体（含最终状态）
     */
    @Transactional
    public Document ingest(MultipartFile file, Long kbId, Long tenantId) {
        return ingest(file, kbId, tenantId, null);
    }

    /**
     * 执行单文件入库流水线（带进度回调，RAG-030 异步入库用）。
     * <p>
     * 回调仅用于把「阶段 + 百分比」写回 {@link com.qoobot.qoorag.entity.IngestTask}，
     * 不得抛出影响主链路的异常（内部已兜底）。
     *
     * @param progress 进度回调，可为 null
     */
    @Transactional
    public Document ingest(MultipartFile file, Long kbId, Long tenantId, IngestProgress progress) {
        // 1. 创建 Document，状态 = PROCESSING
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTenantId(tenantId);
        doc.setName(file.getOriginalFilename());
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        // RAG-020：来源与解析元数据
        doc.setSourceUri(file.getOriginalFilename());
        doc.setSize(file.getSize());
        doc.setMimeType(file.getContentType());
        doc.setDocTitle(file.getOriginalFilename());
        doc.setParser(resolveParser(file.getOriginalFilename()));
        doc.setParserVersion(resolveParserVersion(doc.getParser()));
        try {
            doc.setContentHash(sha256(file.getBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("计算内容哈希失败（不影响入库）: {}", e.getMessage());
        }
        doc = documentRepository.save(doc);
        log.info("文档记录已创建：id={}, name={}", doc.getId(), doc.getName());

        try {
            // 2. 解析文件 → 纯文本
            report(progress, IngestTask.STAGE_PARSE, 10);
            String text = parserService.parse(file);
            if (text == null || text.isBlank()) {
                throw new RuntimeException("文档内容为空，无法解析");
            }
            report(progress, IngestTask.STAGE_PARSE, 25);

            // 2.1 内容安全检测（#18；关闭时 fail-open 放行）
            ContentSafetyService.Result safety = contentSafety.checkText(text);
            if (safety.verdict == ContentSafetyService.Verdict.BLOCKED) {
                throw new BizException(ErrorCode.CONTENT_BLOCKED, "文档内容命中内容安全拦截：" + safety.reason);
            }

            // 3. 文本分块（含元数据：标题路径 / 字符区间 / 块类型）
            report(progress, IngestTask.STAGE_CHUNK, 30);
            List<ChunkDraft> drafts = split(text);
            log.info("分块完成：{} 个块（策略 {}）", drafts.size(), chunkStrategy);
            if (drafts.isEmpty()) {
                throw new RuntimeException("文档分块后无有效内容");
            }
            report(progress, IngestTask.STAGE_CHUNK, 40);

            // 4. Contextual Chunking（RAG-023）：为每个块生成上下文前缀。
            //    前缀同时进入 embedding 文本与 tsv 词法索引（Contextual BM25）。
            //    未启用或生成失败时前缀为 null，退化为无前缀，行为与改造前一致。
            List<String> contextPrefixes = null;
            if (contextualChunkService.isEnabled()) {
                contextPrefixes = new ArrayList<>(drafts.size());
                int done = 0;
                for (ChunkDraft d : drafts) {
                    contextPrefixes.add(contextualChunkService.generatePrefix(
                            d.content, doc.getDocTitle(), d.headingPath, d.chunkType));
                    done++;
                    if (done % 10 == 0 || done == drafts.size()) {
                        report(progress, IngestTask.STAGE_CHUNK, 40 + (int) (10.0 * done / drafts.size()));
                    }
                }
                long ok = contextPrefixes.stream().filter(java.util.Objects::nonNull).count();
                log.info("Contextual Chunking 完成：{}/{} 个块生成了上下文前缀", ok, drafts.size());
            }

            // 5. 批量向量化（RAG-023：有前缀时 embedding 前缀 + 原文）
            List<String> chunkTexts = new ArrayList<>(drafts.size());
            for (int i = 0; i < drafts.size(); i++) {
                String prefix = contextPrefixes == null ? null : contextPrefixes.get(i);
                chunkTexts.add(contextualChunkService.embeddingText(drafts.get(i).content, prefix));
            }
            report(progress, IngestTask.STAGE_EMBED, 50);
            List<float[]> embeddings = Arrays.asList(embeddingService.embedBatch(chunkTexts));
            log.info("向量化完成：{} 条", embeddings.size());
            report(progress, IngestTask.STAGE_EMBED, 80);

            // 6. 写入 Chunk（RAG-022 父子块）+ VectorData（仅子块向量化）
            report(progress, IngestTask.STAGE_SAVE, 85);
            List<ParentGroup> parentGroups = groupIntoParents(drafts, parentChunkGroupSize);

            // 6.1 父块（生成上下文单元，本身不单独向量化）
            List<Chunk> parents = new ArrayList<>();
            for (int gi = 0; gi < parentGroups.size(); gi++) {
                ParentGroup g = parentGroups.get(gi);
                Chunk p = new Chunk();
                p.setDocumentId(doc.getId());
                p.setKbId(kbId);
                p.setTenantId(tenantId);
                p.setContent(g.parentContent());
                p.setChunkType(Chunk.TYPE_PARENT);
                p.setSeq(gi);
                p.setTokenCount(estimateTokens(g.parentContent()));
                p.setCreatedAt(LocalDateTime.now());
                parents.add(p);
            }
            parents = chunkRepository.saveAll(parents);

            // 6.2 子块（检索与向量化单元，关联父块）
            List<Chunk> children = new ArrayList<>();
            for (int gi = 0; gi < parentGroups.size(); gi++) {
                Chunk parent = parents.get(gi);
                for (int childIdx : parentGroups.get(gi).childIndices()) {
                    ChunkDraft d = drafts.get(childIdx);
                    Chunk c = new Chunk();
                    c.setDocumentId(doc.getId());
                    c.setKbId(kbId);
                    c.setTenantId(tenantId);
                    c.setContent(d.content());
                    c.setSeq(childIdx);
                    c.setHeadingPath(d.headingPath());
                    c.setCharStart(d.charStart());
                    c.setCharEnd(d.charEnd());
                    c.setChunkType(d.chunkType());
                    c.setTokenCount(estimateTokens(d.content()));
                    c.setParentChunkId(parent.getId());
                    if (contextPrefixes != null) {
                        c.setContextPrefix(contextPrefixes.get(childIdx));
                    }
                    c.setCreatedAt(LocalDateTime.now());
                    children.add(c);
                }
            }
            children = chunkRepository.saveAll(children);

            // 6.3 仅子块建向量（embeddings 与 drafts 顺序一致）
            //    经 VectorStore 抽象写入（RAG-068）；vectorStore 缺失时回退原 path
            List<ChunkVector> chunkVectors = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                chunkVectors.add(new ChunkVector(
                        children.get(i).getId(), doc.getId(), kbId, tenantId, embeddings.get(i)));
            }
            if (vectorStore != null) {
                vectorStore.upsert(chunkVectors);
            } else {
                List<VectorData> vectors = new ArrayList<>(children.size());
                for (int i = 0; i < children.size(); i++) {
                    VectorData vd = new VectorData();
                    vd.setChunkId(children.get(i).getId());
                    vd.setKbId(kbId);
                    vd.setTenantId(tenantId);
                    vd.setEmbeddingArray(embeddings.get(i));
                    vd.setCreatedAt(LocalDateTime.now());
                    vectors.add(vd);
                }
                vectorDataRepository.saveAll(vectors);
            }
            log.info("入库完成：{} 个子块 / {} 个父块，{} 条向量", children.size(), parents.size(), chunkVectors.size());
            report(progress, IngestTask.STAGE_DONE, 100);

            // 6. 更新状态 = COMPLETED
            doc.setStatus("COMPLETED");
            doc = documentRepository.save(doc);

        } catch (Exception e) {
            log.error("文档入库失败：id={}, error={}", doc.getId(), e.getMessage(), e);
            doc.setStatus("FAILED");
            doc = documentRepository.save(doc);
            throw new RuntimeException("文档入库失败: " + e.getMessage(), e);
        }

        return doc;
    }

    /** 入库进度回调（RAG-030） */
    public interface IngestProgress {
        void report(String stage, int percent);
    }

    /** 上报进度；回调异常不得影响主链路 */
    private static void report(IngestProgress progress, String stage, int percent) {
        if (progress == null) {
            return;
        }
        try {
            progress.report(stage, percent);
        } catch (Exception e) {
            // 进度上报失败不影响入库本身
        }
    }

    // ===== 文本分块 =====

    /** 分块草稿：内容 + 结构元数据 */
    record ChunkDraft(String content, String headingPath, Integer charStart,
                     Integer charEnd, String chunkType) {}

    /** RAG-022 父子块：父块内容 + 其下子块在原 drafts 中的索引 */
    record ParentGroup(String parentContent, List<Integer> childIndices) {}

    /**
     * 把连续的子块按 groupSize 聚合成父块（small-to-big）。
     * 父块内容 = 子块内容拼接，用于检索命中后回填为生成上下文；子块仍作为检索与向量化单元。
     */
    static List<ParentGroup> groupIntoParents(List<ChunkDraft> drafts, int groupSize) {
        int size = Math.max(1, groupSize);
        List<ParentGroup> groups = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i += size) {
            int end = Math.min(i + size, drafts.size());
            List<Integer> childIdx = new ArrayList<>(end - i);
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                childIdx.add(j);
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(drafts.get(j).content());
            }
            groups.add(new ParentGroup(sb.toString().trim(), childIdx));
        }
        return groups;
    }

    /** 依据文件名推断解析器标识（用于 document.parser 字段，RAG-025） */
    private static String resolveParser(String filename) {
        if (filename == null) {
            return "unknown";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "text";
        }
        if (lower.endsWith(".pdf")) {
            return "pdfbox";
        }
        if (lower.endsWith(".docx")) {
            return "poi-xwpf";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "jsoup";
        }
        return "unknown";
    }

    private static String resolveParserVersion(String parser) {
        return switch (parser) {
            case "pdfbox" -> "2.0.31";
            case "poi-xwpf" -> "5.2.5";
            case "jsoup" -> "1.17.2";
            case "text" -> "native";
            default -> "";
        };
    }

    /** 分块入口：按策略分发 */
    List<String> splitText(String text) {
        List<ChunkDraft> drafts = split(text);
        List<String> out = new ArrayList<>(drafts.size());
        for (ChunkDraft d : drafts) {
            out.add(d.content);
        }
        return out;
    }

    /**
     * 结构感知分块（RAG-021）。
     * <p>
     * 步骤：
     * <ol>
     *   <li>识别「原子块」：围栏代码块 {@code ```}、Markdown 表格、列表块，绝不切断；</li>
     *   <li>标题按 {@code #} ~ {@code ######} 层级维护「标题路径」，同级/更浅级标题会弹出更深的层级，
     *       避免上一章节的标题泄漏到后续正文；</li>
     *   <li>标题本身不单独成块（单行标题检索价值低），其文本体现在后续块的 {@code heading_path} 元数据中；
     *       标题上下文注入块内容由 RAG-023（Contextual Chunking）统一处理；</li>
     *   <li>普通段落按句子边界合并，超过 {@link #CHUNK_HARD_LIMIT} 时按 {@link #CHUNK_OVERLAP} 重叠切分；</li>
     *   <li>纯文本（无 Markdown 结构）自动退化为句子级固定长度切块，行为与 {@code fixed} 一致。</li>
     * </ol>
     * {@code char_start / char_end} 为块内容在<b>原文</b>中的字符偏移，供引用定位。
     */
    List<ChunkDraft> split(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        if ("fixed".equalsIgnoreCase(chunkStrategy)) {
            return fixedChunk(text);
        }
        List<ChunkDraft> out = new ArrayList<>();
        Deque<int[]> headingStack = new ArrayDeque<>(); // [level, ] 配合文本用双栈
        Deque<String> headingText = new ArrayDeque<>();
        for (Block block : segmentBlocks(text)) {
            if (block.type() == BlockType.HEADING) {
                // 弹出同级与更深的标题，只保留祖先链
                while (!headingStack.isEmpty() && headingStack.peek()[0] >= block.level()) {
                    headingStack.pop();
                    headingText.pop();
                }
                headingStack.push(new int[]{block.level()});
                headingText.push(block.text());
            } else if (block.type() == BlockType.ATOMIC) {
                out.add(new ChunkDraft(block.text(), joinPath(headingText),
                        block.start(), block.start() + block.text().length(), block.atomicType()));
            } else {
                for (ChunkDraft p : sentencesToChunks(block.text())) {
                    int rel = Math.max(block.text().indexOf(p.content()), 0);
                    int abs = block.start() + rel;
                    out.add(new ChunkDraft(p.content(), joinPath(headingText),
                            abs, abs + p.content().length(), Chunk.TYPE_PARAGRAPH));
                }
            }
        }
        // 去掉无内容的块
        out.removeIf(d -> d.content() == null || d.content().isBlank());
        return out;
    }

    /** 固定长度 + 重叠分块（改造前行为，RAG-021 的 {@code fixed} 策略） */
    List<ChunkDraft> fixedChunk(String text) {
        List<ChunkDraft> out = new ArrayList<>();
        for (String c : splitFixed(text)) {
            out.add(new ChunkDraft(c, null, null, null, Chunk.TYPE_PARAGRAPH));
        }
        return out;
    }

    /** 按段落 + 句子边界分块，固定大小 + 重叠（改造前算法，保留供 fixed 策略与测试对比） */
    List<String> splitFixed(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        // 1. 按段落拆分
        String[] paragraphs = text.split("\\n\\n+");

        // 2. 段内按句子拆分
        List<String> sentences = new ArrayList<>();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            // 中英文句子分隔
            String[] parts = trimmed.split("(?<=[。！？!?\\n])");
            for (String part : parts) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
            }
        }

        // 3. 合并句子为 chunk，含重叠
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String overlapBuffer = "";

        for (String sentence : sentences) {
            if (current.length() + sentence.length() > CHUNK_HARD_LIMIT && current.length() >= CHUNK_SIZE) {
                // 当前 chunk 已满，保存
                chunks.add(current.toString().trim());
                // 保留重叠部分
                if (current.length() > CHUNK_OVERLAP) {
                    overlapBuffer = current.substring(current.length() - CHUNK_OVERLAP);
                }
                current = new StringBuilder(overlapBuffer);
            }
            if (!current.isEmpty()) {
                current.append("\n");
            }
            current.append(sentence);
        }

        // 最后一个 chunk
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    // ===== 结构感知分块的内部实现 =====

    private enum BlockType { HEADING, ATOMIC, PARAGRAPH }

    /**
     * 原文分块单元：类型 + 文本 + 原子类型 + 标题层级 + <b>在原文中的起始偏移</b>。
     */
    private record Block(BlockType type, String text, String atomicType, int level, int start) {

        static Block heading(String text, int level, int start) {
            return new Block(BlockType.HEADING, text, null, level, start);
        }

        static Block atomic(String text, String atomicType, int start) {
            return new Block(BlockType.ATOMIC, text, atomicType, 0, start);
        }

        static Block paragraph(String text, int start) {
            return new Block(BlockType.PARAGRAPH, text, null, 0, start);
        }
    }

    /** 表格分隔行，如 {@code |---|---|} / {@code --- | ---} */
    private static final java.util.regex.Pattern TABLE_SEPARATOR =
            java.util.regex.Pattern.compile("^\\|?[\\s:-]+\\|[\\s:|-]*$");

    /** 列表项行，如 {@code - x} / {@code * x} / {@code + x} / {@code 1. x} */
    private static final java.util.regex.Pattern LIST_ITEM =
            java.util.regex.Pattern.compile("^([-*+]\\s|\\d+[.)]\\s).*");

    /** 围栏起始 */
    private static boolean isFence(String trimmed) {
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    /** Markdown 标题；返回层级 1~6，非标题返回 -1 */
    private static int headingLevel(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '#') {
            return -1;
        }
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        return (level <= 6 && level < trimmed.length() && trimmed.charAt(level) == ' ') ? level : -1;
    }

    /**
     * 把原文切成「标题 / 原子块 / 普通段落」序列，并记录每块在原文中的起始偏移。
     * <p>
     * 偏移口径：逐行扫描，每进入一行先记下该行起始偏移，再按 {@code 行长 + 1（换行符）} 推进；
     * 块文本用 {@code \n} 重新拼接（与原文字节序列一致），因此 {@code start + text.length()}
     * 即为块在原文中的结束偏移。空行不产出块但仍推进偏移，保证后续块偏移不错位。
     * <p>
     * 原子块识别：围栏代码块（{@code ```} / {@code ~~~}）、Markdown 表格（含分隔行）、列表块。
     */
    private static List<Block> segmentBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int offset = 0; // 当前行在原文中的起始偏移
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            int blockStart = offset;
            offset += line.length() + 1; // +1 为被 split 吃掉的 '\n'
            i++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 标题
            int level = headingLevel(trimmed);
            if (level > 0) {
                // 去掉 # 前缀与首空格，仅保留标题正文，使 heading_path 展示干净
                blocks.add(Block.heading(trimmed.substring(level).trim(), level, blockStart));
                continue;
            }
            // 围栏代码块
            if (isFence(trimmed)) {
                String fence = trimmed.substring(0, 3);
                StringBuilder sb = new StringBuilder(line);
                while (i < lines.length && !lines[i].trim().startsWith(fence)) {
                    sb.append("\n").append(lines[i]);
                    offset += lines[i].length() + 1;
                    i++;
                }
                if (i < lines.length) {
                    sb.append("\n").append(lines[i]);
                    offset += lines[i].length() + 1;
                    i++;
                }
                blocks.add(Block.atomic(sb.toString(), Chunk.TYPE_CODE, blockStart));
                continue;
            }
            // Markdown 表格：当前行含 |，且下一行是分隔行
            if (line.contains("|") && i < lines.length && TABLE_SEPARATOR.matcher(lines[i].trim()).matches()) {
                StringBuilder sb = new StringBuilder(line);
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    sb.append("\n").append(lines[i]);
                    offset += lines[i].length() + 1;
                    i++;
                }
                blocks.add(Block.atomic(sb.toString(), Chunk.TYPE_TABLE, blockStart));
                continue;
            }
            // 列表块：连续列表行 + 其缩进续行
            if (LIST_ITEM.matcher(trimmed).matches()) {
                StringBuilder sb = new StringBuilder(line);
                while (i < lines.length) {
                    String nt = lines[i].trim();
                    if (nt.isEmpty() || nt.startsWith("#") || isFence(nt)) {
                        break;
                    }
                    sb.append("\n").append(lines[i]);
                    offset += lines[i].length() + 1;
                    i++;
                }
                blocks.add(Block.atomic(sb.toString(), Chunk.TYPE_LIST, blockStart));
                continue;
            }
            // 普通段落：吃到下一个空行 / 标题 / 围栏
            StringBuilder sb = new StringBuilder(line);
            while (i < lines.length) {
                String next = lines[i];
                String nt = next.trim();
                if (nt.isEmpty() || nt.startsWith("#") || isFence(nt)) {
                    break;
                }
                sb.append("\n").append(next);
                offset += next.length() + 1;
                i++;
            }
            blocks.add(Block.paragraph(sb.toString(), blockStart));
        }
        return blocks;
    }

    /** 标题路径拼接（栈顶为最深标题，逆序后为「父 / 子」） */
    private static String joinPath(Deque<String> headingStack) {
        if (headingStack == null || headingStack.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>(headingStack);
        Collections.reverse(list);
        return String.join(" / ", list);
    }

    /** 段落按句子边界合并为 chunk（超过硬上限时切分并保留重叠） */
    private static List<ChunkDraft> sentencesToChunks(String paragraph) {
        List<String> sentences = new ArrayList<>();
        for (String part : paragraph.split("(?<=[。！？!?\\n])")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                sentences.add(s);
            }
        }
        List<ChunkDraft> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String overlapBuffer = "";
        for (String sentence : sentences) {
            if (current.length() + sentence.length() > CHUNK_HARD_LIMIT
                    && current.length() >= CHUNK_SIZE) {
                out.add(new ChunkDraft(current.toString().trim(), null, null, null, Chunk.TYPE_PARAGRAPH));
                if (current.length() > CHUNK_OVERLAP) {
                    overlapBuffer = current.substring(current.length() - CHUNK_OVERLAP);
                }
                current = new StringBuilder(overlapBuffer);
            }
            if (!current.isEmpty()) {
                current.append("\n");
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            out.add(new ChunkDraft(current.toString().trim(), null, null, null, Chunk.TYPE_PARAGRAPH));
        }
        return out;
    }

    // ===== 工具 =====

    /**
     * 估算 token 数：CJK 按字数、ASCII 连续字母数字串按词数（粗估，用于上下文预算裁剪）。
     * <p>不用正则切分：{@code "".split("\\s+")} 返回长度为 1 的数组，且正则字符类中的
     * unicode 转义在不同 JDK 上行为不一致，显式逐字符判定更稳。
     */
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

    /** 内容 SHA-256 十六进制（去重 / 增量更新判定） */
    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
