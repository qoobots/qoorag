-- ============================================================================
-- qoorag 初始化 SQL（PostgreSQL + pgvector）
-- 执行顺序：建扩展 -> 建表 -> 索引 -> 种子数据 -> （可选）RLS 策略
-- 说明：ddl-auto=none，所有表结构由此文件管理。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- 租户（企业内部对应部门 / 组织单元；单库行级隔离的隔离键）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    username    VARCHAR(64) NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    display_name VARCHAR(128),
    status      VARCHAR(16) DEFAULT 'ACTIVE',
    created_at  TIMESTAMP DEFAULT now(),
    deleted_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant_id);

-- ---------------------------------------------------------------------------
-- 角色
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255)
);

-- ---------------------------------------------------------------------------
-- 用户-角色关联
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id),
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------------
-- 知识库（4.2 / 4.11）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenant(id),
    owner_id            BIGINT REFERENCES users(id),
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    data_classification VARCHAR(32) DEFAULT 'INTERNAL',  -- 数据分级（4.8）
    status              VARCHAR(16) DEFAULT 'ACTIVE',
    created_at          TIMESTAMP DEFAULT now(),
    deleted_at          TIMESTAMP                                   -- 软删除（4.11）
);
CREATE INDEX IF NOT EXISTS idx_kb_tenant ON knowledge_base(tenant_id);

-- ---------------------------------------------------------------------------
-- 知识库检索权限（4.2 RBAC 授权：ROLE / USER / EXTERNAL）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_permission (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    target_type VARCHAR(16) NOT NULL,   -- ROLE / USER / EXTERNAL
    target_id   VARCHAR(64) NOT NULL,
    permission  VARCHAR(16) DEFAULT 'RETRIEVE',
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_perm_kb ON kb_permission(kb_id);

-- ---------------------------------------------------------------------------
-- 文档
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    name        VARCHAR(255),
    status      VARCHAR(16) DEFAULT 'PENDING',
    created_at  TIMESTAMP DEFAULT now(),
    deleted_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON document(kb_id);

-- ---------------------------------------------------------------------------
-- 文本分块
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunk (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id),
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    content     TEXT,
    seq         INT,
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON chunk(kb_id);

-- ---------------------------------------------------------------------------
-- 向量（pgvector；维度 1024，匹配 text-embedding-v3/v4；切换模型时由启动期
--       VectorDimensionReconciler 自动 ALTER 对齐，或手动改为 vector(1536)）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_data (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    BIGINT NOT NULL REFERENCES chunk(id),
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    embedding   vector(1024),
    created_at  TIMESTAMP DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_vector_kb ON vector_data(kb_id);
CREATE INDEX IF NOT EXISTS idx_vector_tenant ON vector_data(tenant_id);

-- ANN 近似最近邻索引（IVFFlat；检索加速）
-- 说明：IVFFlat 索引需在数据写入后创建；lists 按表行数取 sqrt(rows) 以内
--       当前表为空时先跳过，待有数据后执行下方 SQL：
-- CREATE INDEX IF NOT EXISTS idx_vector_embedding_ivfflat
--     ON vector_data USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
-- 如后续改用 HNSW（pgvector 0.5+），替换为：
-- CREATE INDEX IF NOT EXISTS idx_vector_embedding_hnsw
--     ON vector_data USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------------
-- API Key（4.10，按知识库签发；key_hash 为 SHA-256 十六进制）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_key (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    name        VARCHAR(128),
    key_hash    VARCHAR(255) NOT NULL,
    status      VARCHAR(16) DEFAULT 'ACTIVE',
    rate_limit  INT DEFAULT 60,
    created_at  TIMESTAMP DEFAULT now(),
    deleted_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_apikey_kb ON api_key(kb_id);

-- ---------------------------------------------------------------------------
-- 审计日志（4.8；独立于业务数据，删库不删除）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT REFERENCES tenant(id),
    actor_id    BIGINT REFERENCES users(id),
    action      VARCHAR(64) NOT NULL,
    object_type VARCHAR(64),
    object_id   VARCHAR(64),
    before_value TEXT,
    after_value  TEXT,
    created_at  TIMESTAMP DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 问答留痕（4.8；独立于业务数据，删库不删除）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qa_trace (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT REFERENCES knowledge_base(id),
    tenant_id   BIGINT REFERENCES tenant(id),
    user_id     BIGINT REFERENCES users(id),
    question    TEXT,
    answer      TEXT,
    model       VARCHAR(128),
    params      TEXT,
    sources     TEXT,
    created_at  TIMESTAMP DEFAULT now()
);

-- ===========================================================================
-- 种子数据：默认租户 + 两个角色（管理员账号由 SeedService 写入，密码 BCrypt）
-- ===========================================================================
INSERT INTO tenant (id, name) VALUES (1, 'DEFAULT_TENANT')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO roles (id, name, description) VALUES
    (1, '系统管理员', '平台级治理：用户/角色/资源池'),
    (2, '知识管理员', '知识库与检索链路运营')
    ON CONFLICT (id) DO NOTHING;

-- ===========================================================================
-- 数据库层行级安全（RLS）策略 —— 4.9 的兜底隔离
-- ---------------------------------------------------------------------------
-- 启用方式：应用每次借出 DB 连接时由 TenantAwareDataSource 写入会话级
-- GUC app.current_tenant（值取自请求租户）；后台任务（种子/调度）未设置租户时
-- 写入空值，qoorag_current_tenant() 返回 NULL，策略视为旁路（全量访问）。
-- 代码层 tenant_id 过滤仍保留，RLS 作为双保险。
-- ===========================================================================
CREATE OR REPLACE FUNCTION qoorag_current_tenant() RETURNS BIGINT AS $$
    SELECT NULLIF(current_setting('app.current_tenant', true), '')::BIGINT;
$$ LANGUAGE sql STABLE;

-- 统一租户隔离策略：未设置租户（NULL）时旁路，否则仅可见本租户行
CREATE POLICY tenant_isolation ON users
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON knowledge_base
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON kb_permission
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON document
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON chunk
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON vector_data
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON api_key
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON audit_log
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());
CREATE POLICY tenant_isolation ON qa_trace
    USING (qoorag_current_tenant() IS NULL OR tenant_id = qoorag_current_tenant());

ALTER TABLE users             ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_base    ENABLE ROW LEVEL SECURITY;
ALTER TABLE kb_permission     ENABLE ROW LEVEL SECURITY;
ALTER TABLE document          ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunk             ENABLE ROW LEVEL SECURITY;
ALTER TABLE vector_data       ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key           ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log         ENABLE ROW LEVEL SECURITY;
ALTER TABLE qa_trace          ENABLE ROW LEVEL SECURITY;

-- ===========================================================================
-- 资源池配置（#12；平台级全局配置，不绑定租户，不启用 RLS）
-- 仅作为用户对 application.yml 默认值的「显式覆盖层」：
--   config_value 为空 -> 启动加载时不覆盖 yml；用户在 UI 填写后下次启动生效。
--   masked=true 的项（如 api_key）在管理界面脱敏展示。
-- ===========================================================================
CREATE TABLE IF NOT EXISTS resource_pool_config (
    id           BIGSERIAL PRIMARY KEY,
    category     VARCHAR(32)  NOT NULL,                      -- LLM / EMBEDDING / VECTOR
    config_key   VARCHAR(64)  NOT NULL,
    config_value TEXT,
    masked       BOOLEAN      NOT NULL DEFAULT FALSE,
    description  VARCHAR(255),
    updated_at   TIMESTAMP    DEFAULT now(),
    UNIQUE (category, config_key)
);

-- 种子：仅建立 key 占位 + 描述 + 脱敏标记，value 留空（不覆盖 yml 默认值）。
-- 用户在资源池管理界面填写后，重启应用即从 DB 加载生效。
INSERT INTO resource_pool_config (category, config_key, config_value, masked, description) VALUES
    ('LLM',        'base_url',            '', false, '百炼兼容模式 Base URL（OpenAI 兼容）'),
    ('LLM',        'api_key',             '', true,  '百炼 API Key（环境变量 ALI-API-KEY 注入；填写后覆盖）'),
    ('LLM',        'chat_model',          '', false, '对话生成模型名'),
    ('EMBEDDING',  'embedding_model',     '', false, '向量化模型名'),
    ('EMBEDDING',  'embedding_batch_size', '', false, '向量化批大小（单次 API 调用文本条数）'),
    ('VECTOR',     'type',                '', false, '向量库类型（当前固定 pgvector）'),
    ('VECTOR',     'dimension',           '', false, '向量维度（留空则按 embedding 模型自动推导：v3/v4=1024，v1/v2/async=1536；也可显式指定）')
    ON CONFLICT (category, config_key) DO NOTHING;
