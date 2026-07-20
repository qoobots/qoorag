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
-- 向量（pgvector；维度 1536，按实际 embedding 模型调整）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_data (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    BIGINT NOT NULL REFERENCES chunk(id),
    kb_id       BIGINT NOT NULL REFERENCES knowledge_base(id),
    tenant_id   BIGINT NOT NULL REFERENCES tenant(id),
    embedding   vector(1536),
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
-- 默认【不启用】，以保证骨架可直接运行。启用步骤：
--   1) 应用需在每次请求/事务开始时执行：SET LOCAL app.current_tenant = '<tenantId>';
--      （可在 AuthInterceptor 中通过当前会话的 tenantId 实现，例如借用
--       DataSource 的 Connection 或 JPA 的 @Transactional 事件设置会话变量）
--   2) 取消下方注释，执行 ALTER TABLE ... ENABLE ROW LEVEL SECURITY。
-- 策略逻辑：仅允许访问 tenant_id = current_setting('app.current_tenant') 的行。
-- ===========================================================================
-- CREATE OR REPLACE FUNCTION qoorag_current_tenant() RETURNS BIGINT AS $$
--     SELECT COALESCE(NULLIF(current_setting('app.current_tenant', true), ''), '0')::BIGINT;
-- $$ LANGUAGE sql STABLE;
--
-- CREATE POLICY tenant_isolation ON knowledge_base
--     USING (tenant_id = qoorag_current_tenant());
-- ALTER TABLE knowledge_base ENABLE ROW LEVEL SECURITY;
-- （其余带 tenant_id 的表按相同模式追加策略与 ENABLE）
