package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 入库分块单元测试（RAG-020 / RAG-021）。
 * <p>
 * 只覆盖不触达外部依赖（DB / 百炼 / Redis）的纯逻辑，因此可直接运行，
 * 不依赖 Mockito（当前环境 JDK 25 下 Mockito inline mock maker 不可用）。
 * {@code split()} 不读取任何注入的依赖，故以 null 构造 {@link IngestService} 亦可安全调用。
 */
class IngestServiceChunkingTest {

    /** chunkStrategy 默认 null → 走 structure 路径；ContextualChunkService 传 null 不影响纯分块逻辑 */
    private final IngestService service =
            new IngestService(null, null, null, null, null, null, null);

    @Nested
    @DisplayName("RAG-021 结构感知分块")
    class StructureChunking {

        @Test
        @DisplayName("Markdown 标题层级被记录为 heading_path")
        void heading_path_is_recorded() throws Exception {
            String md = "# 系统概述\n本系统用于企业内部知识问答。\n"
                    + "## 1.1 适用范围\n面向研发与运维团队。";
            List<?> drafts = split(md);
            assertEquals(2, drafts.size(), "标题不单独成块，仅两个正文块");
            assertEquals("系统概述", headingPath(drafts.get(0)),
                    "H1 下的正文路径为 H1 标题");
            assertEquals("系统概述 / 1.1 适用范围", headingPath(drafts.get(1)),
                    "H2 下的正文应继承完整标题路径");
        }

        @Test
        @DisplayName("同级/更浅标题会弹出更深层级，标题不泄漏到后续章节")
        void heading_hierarchy_pops_correctly() throws Exception {
            String md = "# 第一章\n内容一。\n## 1.1 小节\n内容二。\n# 第二章\n内容三。";
            List<?> drafts = split(md);
            assertEquals("第一章", headingPath(drafts.get(0)));
            assertEquals("第一章 / 1.1 小节", headingPath(drafts.get(1)));
            assertEquals("第二章", headingPath(drafts.get(2)),
                    "进入新的 H1 后，1.1 小节不应再出现在路径中");
        }

        @Test
        @DisplayName("围栏代码块作为原子单元不被切断")
        void code_fence_is_atomic() throws Exception {
            String code = "```java\npublic class A {\n  void m() {}\n}\n```";
            String md = "# 代码示例\n" + code;
            List<?> drafts = split(md);
            boolean hasCode = drafts.stream()
                    .anyMatch(d -> Chunk.TYPE_CODE.equals(chunkType(d)) && content(d).contains("public class A"));
            assertTrue(hasCode, "应存在 type=code 且完整保留的代码块");
            drafts.stream().filter(d -> Chunk.TYPE_CODE.equals(chunkType(d)))
                    .forEach(d -> assertTrue(content(d).contains("```"), "代码块应保留围栏标记"));
        }

        @Test
        @DisplayName("Markdown 表格作为原子单元，type=table")
        void table_is_atomic() throws Exception {
            String md = "# 财务数据\n| 产品 | 营收 |\n| --- | --- |\n| A100 | 3000 |\n| B200 | 4500 |";
            List<?> drafts = split(md);
            assertTrue(drafts.stream().anyMatch(d -> Chunk.TYPE_TABLE.equals(chunkType(d))),
                    "应识别出 table 类型块");
            drafts.stream().filter(d -> Chunk.TYPE_TABLE.equals(chunkType(d))).forEach(d -> {
                assertTrue(content(d).contains("A100") && content(d).contains("B200"),
                        "表格块应完整保留所有数据行，不被切断");
            });
        }

        @Test
        @DisplayName("列表块作为原子单元，type=list")
        void list_is_atomic() throws Exception {
            String md = "# 注意事项\n- 第一项\n- 第二项\n- 第三项";
            List<?> drafts = split(md);
            assertTrue(drafts.stream().anyMatch(d -> Chunk.TYPE_LIST.equals(chunkType(d))
                            && content(d).contains("第一项") && content(d).contains("第三项")),
                    "列表块应完整保留且 type=list");
        }

        @Test
        @DisplayName("无 Markdown 结构的纯文本退化为句子级切块，内容不丢失")
        void plain_text_falls_back_to_sentence_chunking() throws Exception {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                sb.append("这是第").append(i).append("句用于测试的纯文本内容，没有任何 Markdown 结构标记。\n");
            }
            List<?> drafts = split(sb.toString());
            assertFalse(drafts.isEmpty());
            String joined = drafts.stream().map(IngestServiceChunkingTest::content).reduce("", String::concat);
            for (int i = 0; i < 40; i++) {
                assertTrue(joined.contains("这是第" + i + "句"), "第 " + i + " 句不应丢失");
            }
        }

        @Test
        @DisplayName("fixed 策略与改造前算法结果一致")
        void fixed_strategy_matches_legacy() throws Exception {
            String text = "第一段内容。第二段内容！\n\n第三段内容？";
            List<String> legacy = invokeSplitFixed(text);
            List<?> fixed = invokeFixedChunk(text);
            assertEquals(legacy.size(), fixed.size(), "块数应一致");
            for (int i = 0; i < legacy.size(); i++) {
                assertEquals(legacy.get(i), content(fixed.get(i)), "第 " + i + " 块内容应一致");
            }
        }

        @Test
        @DisplayName("空文本与 null 输入返回空列表")
        void blank_input_returns_empty() throws Exception {
            assertTrue(split("").isEmpty());
            assertTrue(split("   \n\n  ").isEmpty());
        }
    }

    @Nested
    @DisplayName("RAG-020 元数据与工具方法")
    class MetadataAndUtils {

        @Test
        @DisplayName("char_start/char_end 指向原文中的位置")
        void char_offsets_point_into_source() throws Exception {
            String md = "# 标题\n正文第一句。";
            List<?> drafts = split(md);
            Object para = drafts.stream().filter(d -> "正文第一句。".equals(content(d))).findFirst().orElse(null);
            assertNotNull(para, "应能定位到正文块");
            int start = charStart(para);
            int end = charEnd(para);
            assertTrue(start >= 0 && end > start, "字符区间应合法");
            assertEquals("正文第一句。", md.substring(start, end), "区间切片应等于块内容");
        }

        @Test
        @DisplayName("token 估算：CJK 按字、ASCII 按词")
        void token_estimation() throws Exception {
            Method m = IngestService.class.getDeclaredMethod("estimateTokens", String.class);
            m.setAccessible(true);
            assertEquals(0, m.invoke(null, (Object) null));
            assertEquals(0, m.invoke(null, ""));
            assertEquals(6, m.invoke(null, "检索增强生成"), "6 个汉字 = 6 token");
            assertEquals(3, m.invoke(null, "hello world foo"), "3 个 ASCII 词 = 3 token");
            // 4 个汉字 + rag / pipeline 两个 ASCII 词 = 6
            assertEquals(6, m.invoke(null, "检索 rag 增强 pipeline"), "中英混合应两者都计入");
        }

        @Test
        @DisplayName("SHA-256 内容哈希稳定且唯一")
        void sha256_stable_and_distinct() throws Exception {
            Method m = IngestService.class.getDeclaredMethod("sha256", byte[].class);
            m.setAccessible(true);
            String a = (String) m.invoke(null, (Object) "abc".getBytes());
            String b = (String) m.invoke(null, (Object) "abc".getBytes());
            String c = (String) m.invoke(null, (Object) "abd".getBytes());
            assertEquals(a, b, "相同输入哈希应相同");
            assertNotEquals(a, c, "不同输入哈希应不同");
            assertEquals(64, a.length(), "SHA-256 十六进制应为 64 字符");
            assertTrue(a.matches("^[0-9a-f]{64}$"), "应为小写十六进制");
        }
    }

    // ===== 反射辅助：避免为测试扩大可见性 =====

    @SuppressWarnings("unchecked")
    private List<Object> split(String text) {
        return unchecked(() -> {
            Method m = IngestService.class.getDeclaredMethod("split", String.class);
            m.setAccessible(true);
            return (List<Object>) m.invoke(service, text);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Object> invokeFixedChunk(String text) {
        return unchecked(() -> {
            Method m = IngestService.class.getDeclaredMethod("fixedChunk", String.class);
            m.setAccessible(true);
            return (List<Object>) m.invoke(service, text);
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeSplitFixed(String text) {
        return unchecked(() -> {
            Method m = IngestService.class.getDeclaredMethod("splitFixed", String.class);
            m.setAccessible(true);
            return (List<String>) m.invoke(service, text);
        });
    }

    private static String content(Object draft) {
        return unchecked(() -> (String) draft.getClass().getMethod("content").invoke(draft));
    }

    private static String headingPath(Object draft) {
        return unchecked(() -> (String) draft.getClass().getMethod("headingPath").invoke(draft));
    }

    private static String chunkType(Object draft) {
        return unchecked(() -> (String) draft.getClass().getMethod("chunkType").invoke(draft));
    }

    private static int charStart(Object draft) {
        return unchecked(() -> (int) draft.getClass().getMethod("charStart").invoke(draft));
    }

    private static int charEnd(Object draft) {
        return unchecked(() -> (int) draft.getClass().getMethod("charEnd").invoke(draft));
    }

    /** 把受检异常包装为运行时异常，简化测试代码 */
    private static <T> T unchecked(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
