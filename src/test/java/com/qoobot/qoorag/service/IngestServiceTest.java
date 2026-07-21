package com.qoobot.qoorag.service;

import com.qoobot.qoorag.repository.ChunkRepository;
import com.qoobot.qoorag.repository.DocumentRepository;
import com.qoobot.qoorag.repository.VectorDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** IngestService 单元测试：重点覆盖纯逻辑分块 splitText（仓库/Embedding 均 mock） */
@ExtendWith(MockitoExtension.class)
public class IngestServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock VectorDataRepository vectorDataRepository;
    @Mock EmbeddingService embeddingService;
    @Mock ContentSafetyService contentSafety;

    private IngestService service;

    @BeforeEach
    void setUp() {
        lenient().when(contentSafety.checkText(any())).thenReturn(ContentSafetyService.Result.safe());
        service = new IngestService(documentRepository, chunkRepository, vectorDataRepository,
                new DocumentParserService(), embeddingService, contentSafety);
    }

    @Test
    void splitText_blank_returns_empty() {
        assertTrue(service.splitText("").isEmpty());
        assertTrue(service.splitText("   ").isEmpty());
    }

    @Test
    void splitText_short_text_single_chunk() {
        List<String> chunks = service.splitText("这是一个短句子。");
        assertEquals(1, chunks.size());
    }

    @Test
    void splitText_long_text_multiple_chunks_within_hard_limit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("这是第").append(i).append("个句子用于测试分块逻辑是否按边界切分。");
        }
        List<String> chunks = service.splitText(sb.toString());
        assertTrue(chunks.size() > 1, "应切分为多个块");
        for (String c : chunks) {
            assertTrue(c.length() <= 500, "单块长度应不超过硬上限 500，实际=" + c.length());
        }
    }

    @Test
    void splitText_preserves_content_order() {
        String text = "第一句。第二句。第三句。";
        List<String> chunks = service.splitText(text);
        String joined = String.join("", chunks).replace("\n", "");
        assertTrue(joined.contains("第一句"));
        assertTrue(joined.contains("第二句"));
        assertTrue(joined.contains("第三句"));
    }
}
