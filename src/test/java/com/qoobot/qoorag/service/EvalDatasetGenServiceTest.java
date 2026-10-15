package com.qoobot.qoorag.service;

import com.qoobot.qoorag.entity.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评测集自动生成服务单测（RAG-001）。
 * <p>只覆盖不触达 LLM / DB 的纯逻辑（跨文档均匀抽样、问题解析），可直接运行。
 */
class EvalDatasetGenServiceTest {

    /** 不注入 RestTemplate 等依赖：被测的纯方法不依赖它们 */
    private final EvalDatasetGenService service =
            new EvalDatasetGenService(null, null, null, null);

    private static Chunk chunk(long id, long docId, int seq) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocumentId(docId);
        c.setSeq(seq);
        c.setContent("内容-" + id);
        return c;
    }

    @Nested
    @DisplayName("跨文档均匀抽样")
    class EvenSampling {

        @Test
        @DisplayName("文档均衡时每个文档恰好被代表一次")
        void balanced_docs_each_represented_once() {
            List<Chunk> all = new ArrayList<>();
            for (long d = 1; d <= 10; d++) {
                for (int i = 0; i < 10; i++) {
                    all.add(chunk(d * 100 + i, d, i));
                }
            }
            List<Chunk> picked = service.sampleEvenly(all, 10);
            assertEquals(10, picked.size());
            for (long d = 1; d <= 10; d++) {
                final long doc = d;
                assertEquals(1, picked.stream().filter(c -> c.getDocumentId() == doc).count(),
                        "均衡分布下文档 " + d + " 应恰好贡献 1 个 chunk");
            }
        }

        @Test
        @DisplayName("内容倾斜时小文档仍有最低代表性（每个文档至少 1 个）")
        void skewed_docs_still_represented() {
            List<Chunk> all = new ArrayList<>();
            // 文档 1 占 98% 内容，文档 2/3 各 1 个 chunk
            for (int i = 0; i < 100; i++) {
                all.add(chunk(1000 + i, 1L, i));
            }
            all.add(chunk(1, 2L, 0));
            all.add(chunk(2, 3L, 0));

            List<Chunk> picked = service.sampleEvenly(all, 10);
            assertEquals(10, picked.size());
            assertTrue(picked.stream().anyMatch(c -> c.getDocumentId() == 2L),
                    "小文档必须至少被抽到 1 个，否则评测集完全看不到它");
            assertTrue(picked.stream().anyMatch(c -> c.getDocumentId() == 3L),
                    "小文档必须至少被抽到 1 个");
            // 内容倾斜时大文档占比高是符合预期的（它占了 98% 的内容），
            // 这里只断言不会 100% 来自同一个文档
            long fromDoc1 = picked.stream().filter(c -> c.getDocumentId() == 1L).count();
            assertTrue(fromDoc1 < 10, "不应 100% 来自同一个文档");
        }

        @Test
        @DisplayName("抽样数量不超过总量时全取")
        void takes_all_when_small() {
            List<Chunk> all = List.of(chunk(1, 1L, 0), chunk(2, 1L, 1));
            assertEquals(2, service.sampleEvenly(all, 10).size());
        }

        @Test
        @DisplayName("抽样结果确定可复现（固定种子）")
        void deterministic() {
            List<Chunk> all = new ArrayList<>();
            for (long d = 1; d <= 5; d++) {
                for (int i = 0; i < 6; i++) {
                    all.add(chunk(d * 100 + i, d, i));
                }
            }
            List<Chunk> a = service.sampleEvenly(all, 12);
            List<Chunk> b = service.sampleEvenly(all, 12);
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).getId(), b.get(i).getId());
            }
        }

        @Test
        @DisplayName("无重复 chunk")
        void no_duplicates() {
            List<Chunk> all = new ArrayList<>();
            for (long d = 1; d <= 4; d++) {
                for (int i = 0; i < 5; i++) {
                    all.add(chunk(d * 100 + i, d, i));
                }
            }
            List<Chunk> picked = service.sampleEvenly(all, 15);
            long distinct = picked.stream().map(Chunk::getId).distinct().count();
            assertEquals(picked.size(), distinct, "同一 chunk 不应被抽中两次");
        }

        @Test
        @DisplayName("seq 为 null 时不抛异常")
        void null_seq_safe() {
            Chunk c = new Chunk();
            c.setId(1L);
            c.setDocumentId(1L);
            c.setSeq(null);
            assertEquals(1, service.sampleEvenly(List.of(c), 1).size());
        }
    }

    @Nested
    @DisplayName("LLM 输出解析")
    class QuestionParsing {

        @Test
        @DisplayName("每行一个问题")
        void one_per_line() {
            List<String> out = EvalDatasetGenService.parseQuestions(
                    "系统的检索延迟目标是多少？\nHNSW 索引的作用是什么？");
            assertEquals(2, out.size());
        }

        @Test
        @DisplayName("兼容编号与项目符号前缀")
        void strips_numbering() {
            List<String> out = EvalDatasetGenService.parseQuestions(
                    "1. 第一个问题？\n2、第二个问题？\n- 第三个问题？\n* 第四个问题？");
            assertEquals(4, out.size());
            assertEquals("第一个问题？", out.get(0));
            assertEquals("第二个问题？", out.get(1));
            assertEquals("第三个问题？", out.get(2));
        }

        @Test
        @DisplayName("去掉引号包裹")
        void strips_quotes() {
            List<String> out = EvalDatasetGenService.parseQuestions(
                    "\"这是带引号的问题？\"\n“这是中文引号的问题？”");
            assertEquals("这是带引号的问题？", out.get(0));
            assertEquals("这是中文引号的问题？", out.get(1));
        }

        @Test
        @DisplayName("过滤空行、过短行与元话语")
        void filters_noise() {
            List<String> out = EvalDatasetGenService.parseQuestions(
                    "\n好的\n【资料片段】\n这是一个足够长的问题？\n");
            assertEquals(1, out.size(), "空行、2 字行、元话语应被过滤");
            assertEquals("这是一个足够长的问题？", out.get(0));
        }

        @Test
        @DisplayName("null 与空输入安全")
        void null_safe() {
            assertTrue(EvalDatasetGenService.parseQuestions(null).isEmpty());
            assertTrue(EvalDatasetGenService.parseQuestions("").isEmpty());
        }
    }
}
