package com.qoobot.qoorag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RAG 升级 schema 迁移（RAG-011 / RAG-020 / RAG-030 / RAG-040 / RAG-001）。
 * <p>
 * 项目采用 {@code ddl-auto=none}，表结构由 {@code sql/init.sql} 管理；但 init.sql 只在
 * 库首次初始化时执行，存量库不会重跑。本组件在每次启动时幂等地补齐 RAG 升级所需的
 * 表/列/索引/函数，所有步骤相互独立、失败互不影响（只告警不阻断启动）。
 * <p>
 * 覆盖内容：
 * <ol>
 *   <li>CJK 全文检索分词函数 {@code qoorag_cjk_tsvector} / {@code qoorag_cjk_tsquery}；</li>
 *   <li>{@code chunk} 元数据列（标题路径/页码/字符区间/块类型/token 数/父块）+ {@code tsv} 生成列 + GIN 索引；</li>
 *   <li>{@code document} 元数据列（来源 URI/内容哈希/MIME/大小/标题/页数/解析器）；</li>
 *   <li>{@code ingest_task} 异步入库任务表；</li>
 *   <li>{@code prompt_template} 外置 Prompt 模板表；</li>
 *   <li>{@code eval_dataset} / {@code eval_dataset_item} 评测集表；</li>
 *   <li>{@code qa_trace} 全链路 trace 列。</li>
 * </ol>
 */
@Component
@Order(6)
public class RagSchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagSchemaMigrator.class);

    /**
     * CJK 全文检索分词器。
     * <p>
     * PostgreSQL 默认分词器把整段 CJK 视为单个 token（实测
     * {@code to_tsvector('simple','企业级知识库问答系统')} → {@code '企业级知识库问答系统'}），
     * 无法用于中文检索。镜像中 {@code pg_jieba} / {@code zhparser} 不可用（需自行编译安装），
     * 因此采用业界在 PG 上做 CJK 全文检索的标准折中：<b>ASCII 词 + CJK 字符二元组（bigram）</b>。
     * 查询端使用同一分词器，保证索引与查询口径一致。
     * <p>
     * 升级路径：若后续引入 {@code pg_jieba}/{@code zhparser} 或外置 ES，只需替换本函数实现，
     * 上层调用方（{@code LexicalSearchService}）无需改动。
     */
    private static final String SQL_CJK_FUNCTIONS = """
            CREATE OR REPLACE FUNCTION qoorag_cjk_tsvector(t text) RETURNS tsvector AS $$
              SELECT to_tsvector('simple', coalesce(string_agg(tok, ' '), ''))
              FROM (
                SELECT m[1] AS tok FROM regexp_matches(lower(coalesce(t, '')), '[a-z0-9_]+', 'g') AS m
                UNION ALL
                SELECT substr(cjk, i, 2)
                FROM regexp_matches(lower(coalesce(t, '')), '[一-鿿]+', 'g') AS mm
                CROSS JOIN LATERAL (SELECT mm[1] AS cjk) x
                CROSS JOIN generate_series(1, greatest(length(cjk) - 1, 0)) AS i
              ) toks
              WHERE tok IS NOT NULL AND length(tok) > 0
            $$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

            CREATE OR REPLACE FUNCTION qoorag_cjk_tsquery(t text) RETURNS tsquery AS $$
              SELECT to_tsquery('simple', coalesce(string_agg(tok, ' | '), ''))
              FROM unnest(tsvector_to_array(qoorag_cjk_tsvector(t))) AS tok
              WHERE tok ~ '^[a-z0-9_]+$' OR tok ~ '^[一-鿿]+$'
            $$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;
            """;

    private final JdbcTemplate jdbcTemplate;

    public RagSchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        step("CJK 全文检索分词函数", SQL_CJK_FUNCTIONS);
        step("chunk 元数据列 + tsv 生成列 + GIN 索引", """
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS heading_path    TEXT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS page_no         INT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS char_start      INT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS char_end        INT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS chunk_type      VARCHAR(32) DEFAULT 'paragraph';
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS token_count     INT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS parent_chunk_id BIGINT;
                ALTER TABLE chunk ADD COLUMN IF NOT EXISTS tsv tsvector
                    GENERATED ALWAYS AS (qoorag_cjk_tsvector(content)) STORED;
                CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON chunk USING gin (tsv);
                """);
        step("document 元数据列", """
                ALTER TABLE document ADD COLUMN IF NOT EXISTS source_uri    VARCHAR(512);
                ALTER TABLE document ADD COLUMN IF NOT EXISTS content_hash  VARCHAR(64);
                ALTER TABLE document ADD COLUMN IF NOT EXISTS mime_type     VARCHAR(128);
                ALTER TABLE document ADD COLUMN IF NOT EXISTS size          BIGINT;
                ALTER TABLE document ADD COLUMN IF NOT EXISTS doc_title     VARCHAR(512);
                ALTER TABLE document ADD COLUMN IF NOT EXISTS page_count    INT;
                ALTER TABLE document ADD COLUMN IF NOT EXISTS parser        VARCHAR(64);
                ALTER TABLE document ADD COLUMN IF NOT EXISTS parser_version VARCHAR(32);
                CREATE INDEX IF NOT EXISTS idx_document_hash ON document(content_hash);
                """);
        step("ingest_task 异步入库任务表", """
                CREATE TABLE IF NOT EXISTS ingest_task (
                    id           BIGSERIAL PRIMARY KEY,
                    kb_id        BIGINT NOT NULL,
                    tenant_id    BIGINT NOT NULL,
                    document_id  BIGINT,
                    filename     VARCHAR(512),
                    status       VARCHAR(24) DEFAULT 'QUEUED',
                    stage        VARCHAR(32),
                    progress     INT DEFAULT 0,
                    error        TEXT,
                    retry_count  INT DEFAULT 0,
                    created_by   BIGINT,
                    started_at   TIMESTAMP,
                    finished_at  TIMESTAMP,
                    created_at   TIMESTAMP DEFAULT now(),
                    updated_at   TIMESTAMP DEFAULT now()
                );
                CREATE INDEX IF NOT EXISTS idx_ingest_task_kb ON ingest_task(kb_id, tenant_id);
                CREATE INDEX IF NOT EXISTS idx_ingest_task_status ON ingest_task(status);
                """);
        step("prompt_template 外置 Prompt 模板表", """
                CREATE TABLE IF NOT EXISTS prompt_template (
                    id          BIGSERIAL PRIMARY KEY,
                    kb_id       BIGINT,
                    scene       VARCHAR(64) NOT NULL DEFAULT 'chat',
                    name        VARCHAR(128) NOT NULL,
                    content     TEXT NOT NULL,
                    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
                    version     INT NOT NULL DEFAULT 1,
                    created_at  TIMESTAMP DEFAULT now(),
                    updated_at  TIMESTAMP DEFAULT now()
                );
                CREATE INDEX IF NOT EXISTS idx_prompt_template_scene ON prompt_template(scene, enabled);
                CREATE INDEX IF NOT EXISTS idx_prompt_template_kb ON prompt_template(kb_id);
                """);
        step("eval_dataset 评测集表", """
                CREATE TABLE IF NOT EXISTS eval_dataset (
                    id          BIGSERIAL PRIMARY KEY,
                    kb_id       BIGINT NOT NULL,
                    tenant_id   BIGINT NOT NULL,
                    name        VARCHAR(255) NOT NULL,
                    source      VARCHAR(32) DEFAULT 'MANUAL',
                    version     INT NOT NULL DEFAULT 1,
                    item_count  INT DEFAULT 0,
                    created_by  BIGINT,
                    created_at  TIMESTAMP DEFAULT now()
                );
                CREATE TABLE IF NOT EXISTS eval_dataset_item (
                    id          BIGSERIAL PRIMARY KEY,
                    dataset_id  BIGINT NOT NULL,
                    query       TEXT NOT NULL,
                    relevant_chunk_ids TEXT,
                    relevant_doc_ids   TEXT,
                    origin_query       TEXT,
                    created_at  TIMESTAMP DEFAULT now()
                );
                CREATE INDEX IF NOT EXISTS idx_eval_item_dataset ON eval_dataset_item(dataset_id);
                CREATE INDEX IF NOT EXISTS idx_eval_dataset_kb ON eval_dataset(kb_id, tenant_id);
                """);
        step("qa_trace 全链路 trace 列", """
                ALTER TABLE qa_trace ADD COLUMN IF NOT EXISTS trace_json TEXT;
                """);
        step("eval_run 评测运行记录表", """
                CREATE TABLE IF NOT EXISTS eval_run (
                    id                 BIGSERIAL PRIMARY KEY,
                    dataset_id         BIGINT,
                    kb_id              BIGINT NOT NULL,
                    tenant_id          BIGINT NOT NULL,
                    status             VARCHAR(64) NOT NULL DEFAULT 'SUCCESS',
                    top_k              INT,
                    recall_at_k        DOUBLE PRECISION,
                    precision_at_k     DOUBLE PRECISION,
                    hit_rate           DOUBLE PRECISION,
                    mrr                DOUBLE PRECISION,
                    faithfulness       DOUBLE PRECISION,
                    answer_relevancy   DOUBLE PRECISION,
                    context_precision  DOUBLE PRECISION,
                    context_recall     DOUBLE PRECISION,
                    item_count         INT,
                    loop_suspected_count INT,
                    trustworthy        BOOLEAN,
                    embedding_model    VARCHAR(128),
                    chat_model         VARCHAR(128),
                    rerank_model       VARCHAR(128),
                    rerank_enabled     BOOLEAN,
                    strategy_snapshot  VARCHAR(512),
                    notes              TEXT,
                    duration_ms        BIGINT,
                    created_by         BIGINT,
                    created_at         TIMESTAMP DEFAULT now()
                );
                CREATE INDEX IF NOT EXISTS idx_eval_run_kb ON eval_run(kb_id, tenant_id);
                CREATE INDEX IF NOT EXISTS idx_eval_run_dataset ON eval_run(dataset_id);
                """);
    }

    /** 单步执行，失败只告警不阻断启动 */
    private void step(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.info("RAG schema 迁移完成：{}", name);
        } catch (Exception e) {
            log.warn("RAG schema 迁移跳过（{}）: {}", name, e.getMessage());
        }
    }
}
