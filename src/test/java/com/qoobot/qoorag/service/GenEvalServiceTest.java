package com.qoobot.qoorag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生成端评估服务单测（RAG-003）。
 * <p>只覆盖不触达 LLM 的纯逻辑（judge 输出解析、抽样、句子切分），可直接运行。
 */
class GenEvalServiceTest {

    /** 不注入 RestTemplate/BailianConfig：纯方法不依赖它们 */
    private final GenEvalService service = new GenEvalService(null, null);

    @Nested
    @DisplayName("Judge 输出解析")
    class VerdictParsing {

        @Test
        @DisplayName("标准 JSON 数组解析")
        void parses_standard_json() {
            String raw = """
                    [{"s": 1, "supported": true}, {"s": 2, "supported": false}, {"s": 3, "supported": true}]
                    """;
            Map<Integer, Boolean> out = GenEvalService.parseVerdicts(raw);
            assertEquals(3, out.size());
            assertTrue(out.get(1));
            assertFalse(out.get(2));
            assertTrue(out.get(3));
        }

        @Test
        @DisplayName("大小写混合的 true/false 也能解析")
        void parses_case_insensitive() {
            String raw = "[{\"s\":1,\"supported\":TRUE},{\"s\":2,\"supported\":False}]";
            Map<Integer, Boolean> out = GenEvalService.parseVerdicts(raw);
            assertTrue(out.get(1));
            assertFalse(out.get(2));
        }

        @Test
        @DisplayName("judge 未按 JSON 输出时按自由格式兜底解析")
        void parses_free_form() {
            String raw = "1. 支持\n2. 不支持\n3. 支持";
            Map<Integer, Boolean> out = GenEvalService.parseVerdicts(raw);
            assertEquals(3, out.size());
            assertTrue(out.get(1));
            assertFalse(out.get(2));
            assertTrue(out.get(3));
        }

        @Test
        @DisplayName("无法解析时返回空 Map（上层据此把该维度置空而非置 0）")
        void unparseable_returns_empty() {
            assertTrue(GenEvalService.parseVerdicts("我不知道").isEmpty());
            assertTrue(GenEvalService.parseVerdicts("").isEmpty());
            assertTrue(GenEvalService.parseVerdicts(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("句子切分")
    class Sentences {

        @Test
        @DisplayName("中英文句子均能切出（分号也作为句界）")
        void splits_cn_and_en() {
            List<String> out = GenEvalService.splitSentences(
                    "系统支持多租户。It uses pgvector! Does it scale? 是的；可以。");
            assertEquals(5, out.size(), "应按中英标点（含分号）切成 5 句");
            assertTrue(out.get(0).startsWith("系统支持多租户"));
            assertTrue(out.contains("It uses pgvector!"));
        }

        @Test
        @DisplayName("去标点后不足 2 字的片段被丢弃，避免噪声句稀释指标")
        void drops_tiny_fragments() {
            assertEquals(1, GenEvalService.splitSentences("好。这是一个完整的句子。").size(),
                    "「好。」去标点后仅 1 字，应被丢弃");
            assertEquals(1, GenEvalService.splitSentences("。。。这是一个完整的句子。").size(),
                    "纯标点片段应被丢弃");
        }

        @Test
        @DisplayName("空输入安全")
        void null_safe() {
            assertTrue(GenEvalService.splitSentences(null).isEmpty());
            assertTrue(GenEvalService.splitSentences("").isEmpty());
        }
    }

    @Nested
    @DisplayName("抽样（可复现）")
    class Sampling {

        private List<GenEvalService.GenSample> samples(int n) {
            List<GenEvalService.GenSample> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(new GenEvalService.GenSample("q" + i, List.of("c" + i), "a" + i));
            }
            return out;
        }

        @Test
        @DisplayName("比例 1.0 且不超上限时全量")
        void full_when_ratio_one() {
            ReflectionTestUtils.setField(service, "sampleRatio", 1.0);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 50);
            assertEquals(10, service.sample(samples(10)).size());
        }

        @Test
        @DisplayName("比例 0.3 时按 30% 抽取（向上取整，至少 1 条）")
        void partial_by_ratio() {
            ReflectionTestUtils.setField(service, "sampleRatio", 0.3);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 50);
            List<GenEvalService.GenSample> out = service.sample(samples(10));
            assertEquals(3, out.size(), "ceil(10*0.3)=3");
        }

        @Test
        @DisplayName("上限优先于比例：样本多时按 max-judge-calls 截断")
        void capped_by_max_calls() {
            ReflectionTestUtils.setField(service, "sampleRatio", 1.0);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 5);
            assertEquals(5, service.sample(samples(20)).size());
        }

        @Test
        @DisplayName("抽样是确定性的（同样的输入得到同样的样本）")
        void deterministic() {
            ReflectionTestUtils.setField(service, "sampleRatio", 0.3);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 50);
            List<GenEvalService.GenSample> a = service.sample(samples(10));
            List<GenEvalService.GenSample> b = service.sample(samples(10));
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).question(), b.get(i).question());
            }
        }

        @Test
        @DisplayName("极小比例也至少抽 1 条")
        void at_least_one() {
            ReflectionTestUtils.setField(service, "sampleRatio", 0.01);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 50);
            assertEquals(1, service.sample(samples(10)).size());
        }

        @Test
        @DisplayName("空输入安全")
        void empty_safe() {
            ReflectionTestUtils.setField(service, "sampleRatio", 0.3);
            ReflectionTestUtils.setField(service, "maxJudgeCalls", 50);
            assertTrue(service.sample(List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("空值安全")
    class NullSafety {

        @Test
        @DisplayName("answer 为空时四维全空且标记未评测")
        void blank_answer() {
            GenEvalService.GenScores gs = service.evaluate(
                    new GenEvalService.GenSample("q", List.of("c"), ""));
            assertNull(gs.faithfulness());
            assertNull(gs.answerRelevancy());
            assertFalse(gs.judged());
        }

        @Test
        @DisplayName("样本为 null 时安全")
        void null_sample() {
            GenEvalService.GenScores gs = service.evaluate(null);
            assertNull(gs.faithfulness());
            assertFalse(gs.judged());
        }

        @Test
        @DisplayName("批量空输入安全")
        void empty_batch() {
            GenEvalService.GenScores gs = service.evaluateBatch(List.of());
            assertNull(gs.faithfulness());
            assertFalse(gs.judged());
        }
    }
}
