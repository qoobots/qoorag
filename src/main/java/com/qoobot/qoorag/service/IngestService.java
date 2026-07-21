package com.qoobot.qoorag.service;

import com.qoobot.qoorag.common.BizException;
import com.qoobot.qoorag.common.ErrorCode;
import com.qoobot.qoorag.entity.Chunk;
import com.qoobot.qoorag.entity.Document;
import com.qoobot.qoorag.entity.VectorData;
import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文档入库流水线编排：解析 → 分块 → Embedding → 入库
 * <p>状态机：PENDING → PROCESSING → COMPLETED / FAILED
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final int CHUNK_SIZE = 400;
    private static final int CHUNK_OVERLAP = 100;
    private static final int CHUNK_HARD_LIMIT = 500;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorDataRepository vectorDataRepository;
    private final DocumentParserService parserService;
    private final EmbeddingService embeddingService;
    private final ContentSafetyService contentSafety;

    public IngestService(DocumentRepository documentRepository,
                         ChunkRepository chunkRepository,
                         VectorDataRepository vectorDataRepository,
                         DocumentParserService parserService,
                         EmbeddingService embeddingService,
                         ContentSafetyService contentSafety) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorDataRepository = vectorDataRepository;
        this.parserService = parserService;
        this.embeddingService = embeddingService;
        this.contentSafety = contentSafety;
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
        // 1. 创建 Document，状态 = PROCESSING
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTenantId(tenantId);
        doc.setName(file.getOriginalFilename());
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc = documentRepository.save(doc);
        log.info("文档记录已创建：id={}, name={}", doc.getId(), doc.getName());

        try {
            // 2. 解析文件 → 纯文本
            String text = parserService.parse(file);
            if (text == null || text.isBlank()) {
                throw new RuntimeException("文档内容为空，无法解析");
            }

            // 2.1 内容安全检测（#18；关闭时 fail-open 放行）
            ContentSafetyService.Result safety = contentSafety.checkText(text);
            if (safety.verdict == ContentSafetyService.Verdict.BLOCKED) {
                throw new BizException(ErrorCode.CONTENT_BLOCKED, "文档内容命中内容安全拦截：" + safety.reason);
            }

            // 3. 文本分块
            List<String> chunkTexts = splitText(text);
            log.info("分块完成：{} 个块", chunkTexts.size());
            if (chunkTexts.isEmpty()) {
                throw new RuntimeException("文档分块后无有效内容");
            }

            // 4. 批量向量化
            List<float[]> embeddings = Arrays.asList(embeddingService.embedBatch(chunkTexts));
            log.info("向量化完成：{} 条", embeddings.size());

            // 5. 写入 Chunk + VectorData
            List<Chunk> chunks = new ArrayList<>();
            List<VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < chunkTexts.size(); i++) {
                Chunk chunk = new Chunk();
                chunk.setDocumentId(doc.getId());
                chunk.setKbId(kbId);
                chunk.setTenantId(tenantId);
                chunk.setContent(chunkTexts.get(i));
                chunk.setSeq(i);
                chunk.setCreatedAt(LocalDateTime.now());
                chunks.add(chunk);
            }
            chunks = chunkRepository.saveAll(chunks);

            for (int i = 0; i < chunks.size(); i++) {
                VectorData vd = new VectorData();
                vd.setChunkId(chunks.get(i).getId());
                vd.setKbId(kbId);
                vd.setTenantId(tenantId);
                vd.setEmbeddingArray(embeddings.get(i));
                vd.setCreatedAt(LocalDateTime.now());
                vectors.add(vd);
            }
            vectorDataRepository.saveAll(vectors);
            log.info("入库完成：{} 个 chunk，{} 条向量", chunks.size(), vectors.size());

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

    // ===== 文本分块 =====

    /** 按段落 + 句子边界分块，固定大小 + 重叠 */
    List<String> splitText(String text) {
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
}
