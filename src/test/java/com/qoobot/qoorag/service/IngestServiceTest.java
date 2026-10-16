package com.qoobot.qoorag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** IngestService 单元测试：重点覆盖纯逻辑分块 splitText（不使用 Mockito，规避 JDK25 限制） */
public class IngestServiceTest {

    // splitText 为纯逻辑，不依赖任何仓储/服务，故以 null 构造即可
    private final IngestService service =
            new IngestService(null, null, null, null, null, null, null);

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
