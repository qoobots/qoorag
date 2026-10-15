package com.qoobot.qoorag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider 角色回退链单测（密钥整理配套）。
 *
 * <p>覆盖的核心规则：
 * <ul>
 *   <li>Chat：chat-* &gt; 共享 &gt; 旧版百炼（ALI-API-KEY）</li>
 *   <li>Embedding：embedding-* &gt; <b>旧版百炼</b> &gt; 共享 —— 与 Chat 顺序不同，
 *       因为 Embedding 历来用 ALI-API-KEY，不能反过来继承已切到别的 Provider 的 Chat key</li>
 *   <li>空串视为"未配置"，继续向下回退（Spring 的 ${A:${B:}} 做不到这点）</li>
 * </ul>
 */
class BailianConfigTest {

    private static final String CHAT_KEY = "sk-chat-provider-key";
    private static final String EMBED_KEY = "sk-embed-provider-key";
    private static final String LEGACY_KEY = "sk-legacy-bailian-key";
    private static final String SHARED_KEY = "sk-shared-key";

    private static final String CHAT_BASE = "https://api.deepseek.com";
    private static final String EMBED_BASE = "https://api.openai.com/v1";
    private static final String LEGACY_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String SHARED_BASE = "https://shared.example.com";

    @Nested
    @DisplayName("Embedding 回退链")
    class EmbeddingChain {

        @Test
        @DisplayName("显式配置 embedding 专用密钥时优先用它")
        void explicit_embedding_key_wins() {
            BailianConfig c = new BailianConfig();
            c.setEmbeddingApiKey(EMBED_KEY);
            c.setLegacyApiKey(LEGACY_KEY);
            c.setApiKey(SHARED_KEY);
            assertEquals(EMBED_KEY, c.getEmbeddingApiKey());
        }

        @Test
        @DisplayName("embedding 留空时回退到旧版百炼 ALI-API-KEY（历史默认）")
        void empty_falls_back_to_legacy() {
            BailianConfig c = new BailianConfig();
            c.setEmbeddingApiKey("");           // .env 中留空 = 未配置
            c.setLegacyApiKey(LEGACY_KEY);
            c.setApiKey(SHARED_KEY);
            assertEquals(LEGACY_KEY, c.getEmbeddingApiKey(),
                    "空串必须继续回退，不能停在空值上");
        }

        @Test
        @DisplayName("embedding 与 legacy 都空时才回退到共享 key")
        void both_empty_falls_back_to_shared() {
            BailianConfig c = new BailianConfig();
            c.setEmbeddingApiKey("");
            c.setLegacyApiKey("  ");
            c.setApiKey(SHARED_KEY);
            assertEquals(SHARED_KEY, c.getEmbeddingApiKey());
        }

        @Test
        @DisplayName("全空时返回 null（由就绪检查报★未配置★）")
        void all_empty_returns_null() {
            BailianConfig c = new BailianConfig();
            c.setEmbeddingApiKey("");
            c.setLegacyApiKey("");
            c.setApiKey("");
            assertNull(c.getEmbeddingApiKey());
            assertFalse(c.hasEmbeddingKey());
        }

        @Test
        @DisplayName("embedding base-url 同样按 embedding > legacy > 共享 回退")
        void base_url_chain() {
            BailianConfig c = new BailianConfig();
            c.setEmbeddingBaseUrl(EMBED_BASE);
            c.setLegacyBaseUrl(LEGACY_BASE);
            c.setBaseUrl(SHARED_BASE);
            assertEquals(EMBED_BASE, c.getEmbeddingBaseUrl());

            BailianConfig c2 = new BailianConfig();
            c2.setEmbeddingBaseUrl("");
            c2.setLegacyBaseUrl(LEGACY_BASE);
            c2.setBaseUrl(SHARED_BASE);
            assertEquals(LEGACY_BASE, c2.getEmbeddingBaseUrl(), "空 embedding base-url 应回退到百炼");
        }
    }

    @Nested
    @DisplayName("Chat 回退链")
    class ChatChain {

        @Test
        @DisplayName("显式配置 chat 专用密钥时优先用它")
        void explicit_chat_key_wins() {
            BailianConfig c = new BailianConfig();
            c.setChatApiKey(CHAT_KEY);
            c.setApiKey(SHARED_KEY);
            c.setLegacyApiKey(LEGACY_KEY);
            assertEquals(CHAT_KEY, c.getChatApiKey());
        }

        @Test
        @DisplayName("chat 留空时回退到共享 key")
        void empty_falls_back_to_shared() {
            BailianConfig c = new BailianConfig();
            c.setChatApiKey("");
            c.setApiKey(SHARED_KEY);
            assertEquals(SHARED_KEY, c.getChatApiKey());
        }

        @Test
        @DisplayName("chat 与共享都空时才回退到旧版百炼")
        void falls_back_to_legacy_last() {
            BailianConfig c = new BailianConfig();
            c.setChatApiKey("");
            c.setApiKey("");
            c.setLegacyApiKey(LEGACY_KEY);
            assertEquals(LEGACY_KEY, c.getChatApiKey());
        }

        @Test
        @DisplayName("chat model 缺省为 qwen-plus")
        void default_chat_model() {
            assertEquals("qwen-plus", new BailianConfig().getChatModel());
        }

        @Test
        @DisplayName("chat base-url 缺省为共享，再缺省为旧版百炼")
        void base_url_chain() {
            BailianConfig c = new BailianConfig();
            c.setChatBaseUrl("");
            c.setBaseUrl(SHARED_BASE);
            c.setLegacyBaseUrl(LEGACY_BASE);
            assertEquals(SHARED_BASE, c.getChatBaseUrl());

            BailianConfig c2 = new BailianConfig();
            c2.setChatBaseUrl("");
            c2.setBaseUrl("");
            c2.setLegacyBaseUrl(LEGACY_BASE);
            assertEquals(LEGACY_BASE, c2.getChatBaseUrl());
        }
    }

    @Nested
    @DisplayName("角色隔离（本次拆分的核心目的）")
    class RoleIsolation {

        @Test
        @DisplayName("Chat 切到 DeepSeek 不影响 Embedding 继续用百炼")
        void chat_switch_does_not_break_embedding() {
            BailianConfig c = new BailianConfig();
            // 典型场景：chat 用 DeepSeek，embedding 留空走 ALI-API-KEY（百炼）
            c.setChatBaseUrl(CHAT_BASE);
            c.setChatApiKey(CHAT_KEY);
            c.setEmbeddingBaseUrl("");
            c.setEmbeddingApiKey("");
            c.setLegacyBaseUrl(LEGACY_BASE);
            c.setLegacyApiKey(LEGACY_KEY);

            assertEquals(CHAT_BASE, c.getChatBaseUrl());
            assertEquals(CHAT_KEY, c.getChatApiKey());
            assertEquals(LEGACY_BASE, c.getEmbeddingBaseUrl(),
                    "Embedding 必须独立解析到百炼，不被 Chat 的 DeepSeek 配置污染");
            assertEquals(LEGACY_KEY, c.getEmbeddingApiKey());
            assertTrue(c.hasChatKey());
            assertTrue(c.hasEmbeddingKey());
        }

        @Test
        @DisplayName("两者都未配置时就绪检查能识别出来")
        void nothing_configured() {
            BailianConfig c = new BailianConfig();
            c.setChatApiKey("");
            c.setEmbeddingApiKey("");
            c.setLegacyApiKey("");
            c.setApiKey("");
            assertFalse(c.hasChatKey());
            assertFalse(c.hasEmbeddingKey());
        }

        @Test
        @DisplayName("共享 key 与 embedding 显式 key 相同时不算回退（供就绪检查判断）")
        void shared_equals_embedding() {
            BailianConfig c = new BailianConfig();
            c.setApiKey(SHARED_KEY);
            c.setEmbeddingApiKey(SHARED_KEY);
            assertEquals(SHARED_KEY, c.getEmbeddingApiKey());
        }
    }
}
