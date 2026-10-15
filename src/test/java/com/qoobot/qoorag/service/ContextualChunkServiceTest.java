package com.qoobot.qoorag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contextual Chunking 服务单测（RAG-023）。
 * <p>只覆盖不触达 LLM 的纯逻辑（prompt 构造、文本拼接），可直接运行。
 */
class ContextualChunkServiceTest {

    private final ContextualChunkService service = new ContextualChunkService(null, null);

    @Nested
    @DisplayName("embeddingText 拼接")
    class EmbeddingText {

        @Test
        @DisplayName("无前缀时原样返回（行为与改造前一致）")
        void no_prefix_returns_original() {
            assertEquals("原文内容", service.embeddingText("原文内容", null));
            assertEquals("原文内容", service.embeddingText("原文内容", ""));
            assertEquals("原文内容", service.embeddingText("原文内容", "   "));
        }

        @Test
        @DisplayName("有前缀时前缀在前、原文在后")
        void prefix_prepended() {
            String out = service.embeddingText("原文内容", "这是上下文说明");
            assertEquals("这是上下文说明\n\n原文内容", out);
            assertTrue(out.startsWith("这是上下文说明"), "前缀必须在前");
            assertTrue(out.endsWith("原文内容"), "原文必须完整保留在后");
        }

        @Test
        @DisplayName("原文不被截断或改写（保证 char_start/char_end 偏移仍有效）")
        void original_preserved_verbatim() {
            String original = "多行\n原文\n内容";
            String out = service.embeddingText(original, "前缀");
            assertTrue(out.contains(original), "原文必须逐字保留");
        }
    }

    @Nested
    @DisplayName("生成 prompt 构造")
    class Prompt {

        @Test
        @DisplayName("包含文档标题、章节路径与块类型")
        void includes_all_location_signals() {
            String p = service.buildPrompt("片段内容", "技术白皮书", "第二章 / 2.1 检索", "paragraph");
            assertTrue(p.contains("文档标题：技术白皮书"));
            assertTrue(p.contains("所在章节：第二章 / 2.1 检索"));
            assertTrue(p.contains("块类型：paragraph"));
            assertTrue(p.contains("片段内容"));
        }

        @Test
        @DisplayName("结构信息缺失时给出占位而不是空")
        void missing_metadata_placeholder() {
            String p = service.buildPrompt("片段内容", null, null, null);
            assertTrue(p.contains("（无文档结构信息）"), "应明确告知无结构信息");
            assertTrue(p.contains("片段内容"));
        }

        @Test
        @DisplayName("部分结构信息缺失时只输出已有的")
        void partial_metadata() {
            String p = service.buildPrompt("片段内容", "技术白皮书", null, "table");
            assertTrue(p.contains("文档标题：技术白皮书"));
            assertFalse(p.contains("所在章节："));
            assertTrue(p.contains("块类型：table"));
            assertFalse(p.contains("（无文档结构信息）"), "有部分信息时不应显示占位");
        }

        @Test
        @DisplayName("长片段被截断以控制 prompt 成本")
        void long_chunk_truncated() {
            String p = service.buildPrompt("长".repeat(5000), "标题", "章节", "paragraph");
            assertTrue(p.contains("…"), "超长片段应被截断");
            assertTrue(p.length() < 2500, "prompt 长度应受控，实际 " + p.length());
        }

        @Test
        @DisplayName("prompt 要求只输出说明文字（避免 LLM 输出解释性前后缀）")
        void prompt_asks_for_bare_output() {
            String p = service.buildPrompt("x", "t", "h", "paragraph");
            assertTrue(p.contains("只输出说明文字"));
        }
    }

    @Nested
    @DisplayName("开关与降级")
    class Toggle {

        @Test
        @DisplayName("默认关闭（成本敏感）")
        void disabled_by_default() {
            assertFalse(service.isEnabled(), "默认必须关闭：每 chunk 多一次 LLM 调用");
        }

        @Test
        @DisplayName("关闭时 generatePrefix 直接返回 null，不调用 LLM")
        void disabled_returns_null() {
            ReflectionTestUtils.setField(service, "enabled", false);
            assertNull(service.generatePrefix("内容", "标题", "章节", "paragraph"));
        }

        @Test
        @DisplayName("开启但内容为空时返回 null")
        void enabled_blank_content_returns_null() {
            ReflectionTestUtils.setField(service, "enabled", true);
            assertNull(service.generatePrefix("", "标题", "章节", "paragraph"));
            assertNull(service.generatePrefix("   ", "标题", "章节", "paragraph"));
            assertNull(service.generatePrefix(null, "标题", "章节", "paragraph"));
        }
    }
}
