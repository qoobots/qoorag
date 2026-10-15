package com.qoobot.qoorag.service;

import com.qoobot.qoorag.dto.RetrieveChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文组装器单测（RAG-041）。
 * <p>纯逻辑，不触达 DB / 百炼，可直接运行（不依赖 Mockito）。
 */
class ContextAssemblerTest {

    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ContextAssembler();
        ReflectionTestUtils.setField(assembler, "maxContextTokens", 1000);
        ReflectionTestUtils.setField(assembler, "contextReorder", true);
        ReflectionTestUtils.setField(assembler, "maxChunkChars", 50);
    }

    private static RetrieveChunk chunk(long chunkId, long docId, int seq, String content, double score) {
        RetrieveChunk c = new RetrieveChunk(chunkId, chunkId, docId, seq, content, score);
        c.setDocumentName("文档" + docId + ".pdf");
        c.setHeadingPath("第一章 概述");
        c.setPageNo(seq + 1);
        return c;
    }

    @Nested
    @DisplayName("去重")
    class Dedup {

        @Test
        @DisplayName("规范化后相同内容的块只保留一个")
        void duplicate_content_removed() {
            List<RetrieveChunk> in = List.of(
                    chunk(1, 1, 0, "检索增强生成技术介绍", 0.9),
                    chunk(2, 1, 1, "检索增强生成技术介绍", 0.85),   // 完全相同
                    chunk(3, 2, 0, "  检索增强生成技术介绍  ", 0.8)  // 仅空白差异
            );
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            assertEquals(1, out.used().size(), "三条等价内容只应保留一条");
            assertEquals(2, out.droppedByDup());
            assertEquals(1L, out.used().get(0).getChunkId(), "保留最相关的那条");
        }

        @Test
        @DisplayName("不同内容不去重")
        void distinct_content_kept() {
            List<RetrieveChunk> in = List.of(
                    chunk(1, 1, 0, "第一段内容", 0.9),
                    chunk(2, 1, 1, "第二段内容", 0.8)
            );
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            assertEquals(2, out.used().size());
            assertEquals(0, out.droppedByDup());
        }
    }

    @Nested
    @DisplayName("Token 预算裁剪")
    class Budget {

        @Test
        @DisplayName("超出预算的低相关片段被丢弃，高相关优先保留")
        void budget_drops_low_relevance() {
            List<RetrieveChunk> in = new ArrayList<>();
            in.add(chunk(1, 1, 0, "甲".repeat(400), 0.9));  // 400 tokens
            in.add(chunk(2, 2, 0, "乙".repeat(400), 0.8));  // 400 tokens → 累计 800
            in.add(chunk(3, 3, 0, "丙".repeat(400), 0.7));  // 累计 1200 > 1000 → 丢弃
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            assertEquals(2, out.used().size(), "预算内只应保留 2 条");
            assertEquals(1, out.droppedByBudget());
            assertEquals(800, out.totalTokens());
            assertTrue(out.used().stream().noneMatch(c -> c.getChunkId() == 3L),
                    "最不相关的第 3 条应被丢弃");
        }

        @Test
        @DisplayName("预算 <=0 表示不限制")
        void zero_budget_means_unlimited() {
            ReflectionTestUtils.setField(assembler, "maxContextTokens", 0);
            List<RetrieveChunk> in = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                // 内容互不相同，避免触发去重
                in.add(chunk(i + 1, i + 1, 0, "片段" + i + "内容".repeat(300), 0.9 - i * 0.1));
            }
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            assertEquals(5, out.used().size(), "预算 0 表示不限制，5 条全保留");
            assertEquals(0, out.droppedByBudget());
            assertEquals(0, out.droppedByDup());
        }
    }

    @Nested
    @DisplayName("Lost-in-the-Middle 位置重排")
    class Reorder {

        @Test
        @DisplayName("最相关片段位于首端，第二相关位于末端，中间为低相关")
        void highest_relevance_at_edges() {
            ReflectionTestUtils.setField(assembler, "maxContextTokens", 100000);
            List<RetrieveChunk> in = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                in.add(chunk(i + 1, i + 1, 0, "片段" + i, 0.9 - i * 0.1));
            }
            // chunk1 最相关（score 0.9），chunk5 最不相关（score 0.5）
            List<RetrieveChunk> reordered = ContextAssembler.reorder(in);
            assertEquals(5, reordered.size());
            // U 型：[1, 5, 2, 4, 3]
            assertEquals(1L, reordered.get(0).getChunkId(), "最相关应在首位");
            assertEquals(5L, reordered.get(1).getChunkId(), "最不相关应在次位（向中间沉）");
            assertEquals(3L, reordered.get(4).getChunkId(), "中间应为次相关");
        }

        @Test
        @DisplayName("单元素与空列表重排安全")
        void reorder_edge_cases() {
            assertEquals(1, ContextAssembler.reorder(List.of(chunk(1, 1, 0, "a", 0.9))).size());
            assertTrue(ContextAssembler.reorder(List.of()).isEmpty());
        }

        @Test
        @DisplayName("重排是排列，不增不减")
        void reorder_is_permutation() {
            List<RetrieveChunk> in = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                in.add(chunk(i + 1, 1, i, "片段" + i, 0.9 - i * 0.1));
            }
            List<RetrieveChunk> reordered = ContextAssembler.reorder(in);
            assertEquals(in.size(), reordered.size());
            for (RetrieveChunk c : in) {
                assertTrue(reordered.stream().anyMatch(r -> r.getChunkId() == c.getChunkId()),
                        "不应丢失片段 " + c.getChunkId());
            }
        }
    }

    @Nested
    @DisplayName("Prompt 渲染与来源标注")
    class Rendering {

        @Test
        @DisplayName("每块带序号与来源标注（文档名 / 标题路径 / 页码）")
        void origin_label_included() {
            List<RetrieveChunk> in = List.of(
                    chunk(1, 7, 0, "第一段内容", 0.9),
                    chunk(2, 8, 1, "第二段内容", 0.8)
            );
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            String text = out.promptContext();
            assertTrue(text.contains("【参考资料 1】"), "应有编号");
            assertTrue(text.contains("【参考资料 2】"));
            assertTrue(text.contains("文档7.pdf"), "应含文档名");
            assertTrue(text.contains("第一章 概述"), "应含标题路径");
            assertTrue(text.contains("第 1 页"), "应含页码");
        }

        @Test
        @DisplayName("超长块被截断")
        void long_chunk_truncated() {
            List<RetrieveChunk> in = List.of(chunk(1, 1, 0, "长".repeat(500), 0.9));
            ContextAssembler.AssembledContext out = assembler.assemble(in);
            assertTrue(out.promptContext().contains("内容过长已截断"), "超长块应被截断");
            assertTrue(out.promptContext().length() < 500);
        }

        @Test
        @DisplayName("空输入返回空上下文")
        void empty_input() {
            ContextAssembler.AssembledContext out = assembler.assemble(List.of());
            assertEquals("", out.promptContext());
            assertTrue(out.used().isEmpty());
            assertEquals(0, out.totalTokens());
        }

        @Test
        @DisplayName("来源标注省略可空项")
        void origin_label_omits_missing() {
            RetrieveChunk c = new RetrieveChunk(1L, 1L, 1L, 0, "内容", 0.9);
            assertEquals("", ContextAssembler.originLabel(c), "无元数据时标注为空");
            c.setDocumentName("a.pdf");
            assertEquals("a.pdf", ContextAssembler.originLabel(c));
            c.setPageNo(3);
            assertEquals("a.pdf · 第 3 页", ContextAssembler.originLabel(c));
        }
    }

    @Nested
    @DisplayName("工具方法")
    class Utils {

        @Test
        @DisplayName("token 估算：CJK 按字、ASCII 按词")
        void token_estimation() {
            assertEquals(0, ContextAssembler.estimateTokens(null));
            assertEquals(0, ContextAssembler.estimateTokens(""));
            assertEquals(6, ContextAssembler.estimateTokens("检索增强生成"));
            assertEquals(3, ContextAssembler.estimateTokens("hello world foo"));
            assertEquals(6, ContextAssembler.estimateTokens("检索 rag 增强 pipeline"));
        }

        @Test
        @DisplayName("内容指纹稳定、区分大小写与空白差异")
        void fingerprint() {
            assertEquals(ContextAssembler.fingerprint("abc"), ContextAssembler.fingerprint("abc"));
            assertEquals(ContextAssembler.fingerprint("a b c"), ContextAssembler.fingerprint("abc"),
                    "空白不影响指纹");
            assertNotEquals(ContextAssembler.fingerprint("abc"), ContextAssembler.fingerprint("abd"));
            assertNull(ContextAssembler.fingerprint(null));
            assertNull(ContextAssembler.fingerprint("   "), "纯空白无指纹");
        }
    }
}
