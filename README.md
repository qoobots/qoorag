# qoorag

开源 RAG 检索增强生成引擎：多租户知识库、混合检索（向量 + 词法 CJK）、结构感知切块、重排、可追溯问答与可观测性。

## 快速开始（一键体验）

### 方式一：Docker 一条命令起全栈（推荐首次体验）

```bash
# 1. 准备密钥（复制模板后填写百炼 LLM / Embedding / Rerank 密钥）
cp .env.example .env
#   编辑 .env：至少填 LLM_API_KEY / EMBEDDING_API_KEY（EMBEDDING_MODEL 默认 text-embedding-v2）

# 2. 启动 PostgreSQL + Redis + 应用（应用默认在 profile=app 中）
docker compose --profile app up -d

# 3. 等待健康
docker compose ps          # 三个服务均 healthy
curl http://localhost:8080/actuator/health

# 4. 登录拿 token（默认管理员 admin / 见 .env 的 ADMIN_DEFAULT_PASSWORD）
#    首次使用请修改默认密码
```

> 端口说明：compose 刻意用 `5433 / 6380 / 8080`，避免与本机其它项目冲突，`.env` 中的 `DB_PORT / REDIS_PORT` 需与之匹配。

### 方式二：本地开发（改代码更顺手）

```bash
# 前置：本地 PostgreSQL(5433) + Redis(6380) 已起（docker compose up -d 即可）
mvn spring-boot:run
# 应用读取 .env（或系统环境变量）中的 DB / LLM / Embedding 配置
```

## 配置要点（.env）

| 变量 | 说明 |
|------|------|
| `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_CHAT_MODEL` | 对话大模型（百炼 qwen 系列） |
| `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` | 向量化（默认 text-embedding-v2） |
| `RERANK_ENABLED` / `RERANK_API_KEY` / `QOORAG_RERANK_ENDPOINT` | 精排开关与密钥（默认关闭） |
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | 数据库（容器间用服务名 `postgres:5432`） |
| `ADMIN_DEFAULT_PASSWORD` | 管理后台默认密码（**首次部署务必修改**） |

## 核心能力

- **摄入侧**：TXT/MD/PDF/DOCX/HTML 解析（RAG-025）、结构感知切块（RAG-021）、父子块 Small-to-Big（RAG-022）、PDF 表格识别（RAG-026）、Contextual Chunking（RAG-023）、并发向量化与 Redis 缓存（RAG-050/052）。
- **检索侧**：混合检索（向量 + 词法 CJK tsvector，RAG-011）、MMR/重排（RAG-018）、Lost-in-the-Middle 缓解、可配置候选池（RAG-051）。
- **问答侧**：多轮对话与指代消解（RAG-043/044）、流式输出、句子级引用溯源（RAG-042）。
- **可观测**：用量与成本埋点（RAG-053）、Prometheus 指标与告警模板（RAG-054）、大规模向量压测脚本（RAG-055）。

## 压测与容量

大规模向量性能验证见 [`docs/performance/large_scale_benchmark.md`](docs/performance/large_scale_benchmark.md)，脚本见 [`scripts/perf/benchmark_pgvector.sql`](scripts/perf/benchmark_pgvector.sql)。

## 任务清单

完整 RAG 升级改造路线图与进度见 [`.docs/07_RAG升级改造产品需求任务清单.md`](.docs/07_RAG升级改造产品需求任务清单.md)。
