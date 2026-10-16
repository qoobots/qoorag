package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RAG-015：查询改写（Query Rewrite）。
 * <p>
 * 在检索前将用户原始提问改写为更适合向量检索的查询：展开代词/省略、对复合长问拆分为子问、
 * 保留关键实体与术语。默认关闭；开启后对单次（非多轮）提问生效——
 * 多轮场景由 {@code ChatService.resolveQuery}（RAG-044 指代消解）负责，避免重复 LLM 调用。
 * 改写失败时 fail-soft 回退原 query。
 */
@Service
public class QueryTransformService {

    private static final Logger log = LoggerFactory.getLogger(QueryTransformService.class);

    private static final String REWRITE_SYSTEM = "你是一个检索查询改写助手。给定用户的原始提问，请改写为更适合向量检索的查询："
            + "1) 展开代词与省略（如'它''这个''前者'，若脱离上下文无法消解则保留原意）；"
            + "2) 若问题较长或含多个子问，拆分为 1-3 个独立子查询，用换行分隔；"
            + "3) 保留关键实体与专业术语，去掉问候语与冗余修饰。"
            + "只输出改写后的查询文本，不要解释、不要加引号或前缀。";

    private final ChatService chatService;

    public QueryTransformService(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 改写查询；空/失败时回退原 query */
    public String rewrite(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String rewritten = chatService.complete(REWRITE_SYSTEM, query);
        if (rewritten == null || rewritten.isBlank()) {
            return query;
        }
        return rewritten.trim();
    }
}
