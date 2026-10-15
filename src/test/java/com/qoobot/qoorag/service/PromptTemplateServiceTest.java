package com.qoobot.qoorag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 模板渲染单测（RAG-040）。
 * <p>只测纯渲染逻辑（无 DB），不依赖 Mockito。
 */
class PromptTemplateServiceTest {

    private static String render(String template, Map<String, String> vars) {
        return PromptTemplateService.render(template, vars);
    }

    private static Map<String, String> vars(String context, String query) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("context", context);
        m.put("query", query);
        return m;
    }

    @Test
    @DisplayName("默认模板与改造前硬编码 Prompt 行为一致")
    void default_template_matches_legacy() {
        String legacy = """
                你是一个基于知识库的智能问答助手。请严格根据以下参考资料回答用户问题。

                规则：
                1. 仅使用【参考资料】中的信息作答，不要编造。
                2. 如果参考资料不足以回答，请如实告知"参考资料中未包含相关信息"。
                3. 回答简洁准确，必要时标注引用序号。
                4. 使用中文回答。

                """;
        String rendered = render(PromptTemplateService.DEFAULT_TEMPLATE, vars("参考资料内容", "问题"));
        assertTrue(rendered.startsWith(legacy), "默认模板前缀必须与改造前完全一致（回归安全）");
        assertTrue(rendered.contains("参考资料内容"), "上下文应被注入");
    }

    @Test
    @DisplayName("占位符替换：context / query 均被替换")
    void placeholders_replaced() {
        String tpl = "上下文：{{context}}\n问题：{{query}}";
        String out = render(tpl, vars("CTX", "Q"));
        assertEquals("上下文：CTX\n问题：Q", out);
    }

    @Test
    @DisplayName("未提供的变量替换为空串，不残留占位符")
    void unknown_variable_becomes_empty() {
        String out = render("a={{unknown}}|b={{context}}", vars("CTX", "Q"));
        assertEquals("a=|b=CTX", out);
    }

    @Test
    @DisplayName("占位符允许内部空白：{{ context }}")
    void placeholder_with_spaces() {
        String out = render("[{{ context }}]", vars("CTX", "Q"));
        assertEquals("[CTX]", out);
    }

    @Test
    @DisplayName("变量值中的 $ 与 \\ 不被当作替换语法")
    void special_chars_in_value_not_interpreted() {
        String out = render("{{context}}", vars("价格 $100 和 \\ 路径", "Q"));
        assertEquals("价格 $100 和 \\ 路径", out);
    }

    @Test
    @DisplayName("模板为 null 或空时返回空串")
    void null_template() {
        assertEquals("", render(null, vars("a", "b")));
        assertEquals("", render("", vars("a", "b")));
    }

    @Test
    @DisplayName("自定义模板可完整定制行为")
    void custom_template() {
        String tpl = """
                你是客服助手。仅依据资料回答，不知道就说"不清楚"。
                资料：
                {{context}}
                用户：{{query}}
                """;
        String out = render(tpl, vars("【参考资料 1】退货政策", "怎么退货"));
        assertTrue(out.contains("退货政策"));
        assertTrue(out.contains("怎么退货"));
        assertTrue(out.contains("不清楚"));
    }
}
