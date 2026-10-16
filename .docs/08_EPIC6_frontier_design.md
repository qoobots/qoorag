# EPIC-6 前沿能力 · 技术方案设计（RAG-060~067，设计态完成）

> 背景：M3 体验与成本已收口（RAG-022/025/026/053/054/055/056 完成）。EPIC-6 为 2026 设计最强 RAG 的 8 个前沿方向。
> 本文件为**设计态交付**：给出每项的目标、业界成熟方案、与现有 Qoorag 架构的接入点、关键取舍、验收/验证计划、依赖与风险。
> 完整落地需引入 Graph DB / VLM / OCR / 重排服务 等基建，建议按优先级排期，而非一次性全做。
> 关联任务清单：`.docs/07_RAG升级改造产品需求任务清单.md`（EPIC-6 章节）。

---

## RAG-060 多路召回与自适应检索（Self-RAG / CRAG）　优先级 P0
- **目标**：根据问题类型动态选择检索策略（向量 / 词法 / 混合 / 不检索），并在生成中自检相关性（Self-RAG 的 `Retrieve/IsRel` 判断）。
- **业界方案**：Self-RAG（检索必要性 + 相关性打分器）、CRAG（retrieval evaluator + 知识精炼）、Adaptive RAG（用轻量分类器路由）。
- **接入点**：在 `RetrieveService` 前加 `RetrievalRouter`：用一个小模型/规则判定 `need_retrieve` 与策略；相关性打分可复用 `RerankService`（qwen3-rerank）对 top 结果做 `isRel`。
- **取舍**：自训打分器成本高 → 先用「规则 + rerank 分数阈值」近似；Self-RAG 的反思 token 需模型支持 function/特殊 token。
- **验收**：在 `eval` 集上对比「全量检索 vs 自适应」的 HitRate/延迟；噪声 query（闲聊）应跳过检索。
- **依赖**：rerank 服务可用；可选一个小分类模型。

## RAG-061 知识图谱 / GraphRAG（LightRAG 风格）　优先级 P1
- **目标**：实体-关系抽取构建图谱，用图遍历 + 社区摘要增强多跳/全局问答。
- **业界方案**：Microsoft GraphRAG（社区检测 + 社区摘要）、LightRAG（双层图 + 多模式检索）、Neo4j/PG 图存储。
- **接入点**：新增 `GraphIngestService`（LLM 抽取 entity/relation → 落图库）+ `GraphRetriever`（向量召回实体 + 图遍历 + 社区摘要拼接入 `ContextAssembler`）。可复用现有 `Chunk`/`Document` 作为文本底座。
- **取舍**：图存储选型（Neo4j vs PostgreSQL Apache AGE vs 内存图）；抽取成本高 → 仅对高价值文档做图化；全局问答用社区摘要、局部问答走向量。
- **验收**：在含多跳关系的语料上，对比纯向量 RAG 的 多跳问答准确率。
- **依赖**：图数据库（或 AGE 扩展）；抽取用的 LLM 配额。

## RAG-062 RAPTOR 层次化摘要索引　优先级 P1
- **目标**：对 chunk 聚类→递归生成摘要树，检索时自底向上聚合多粒度上下文，提升综述/全局问答。
- **业界方案**：RAPTOR（高斯混合聚类 + 树状摘要），与现有「父子块（RAG-022）」互补（父子是固定分组，RAPTOR 是语义聚类）。
- **接入点**：在 `IngestService` 切块后新增 `HierarchicalIndexer`：对子块做软聚类（如 UMAP+HDBSCAN 或嵌入 KMeans），每层用 LLM 生成摘要块（chunk_type=summary，parent_chunk_id 链接下层），检索时按相似度回溯多层。
- **取舍**：聚类与多层摘要的 LLM 成本；可与 RAG-022 父子块并存（摘要块额外建向量）。
- **验收**：综述类 query 的答案完整性优于仅用叶子块。
- **依赖**：聚类库（如 scikit-learn / 轻量实现）、摘要 LLM 配额。

## RAG-063 复杂查询分解 / 子问题引擎　优先级 P2
- **目标**：把多跳/对比/聚合类问题拆成子问题，分别检索后合并答案。
- **业界方案**：Query Decomposition（问题分解为子查询）、Multi-Query Retrieval（改写多问并行检索）、Step-Back Prompting。
- **接入点**：在 `ChatService` 编排：先 `Decompose` 生成子问题列表 → 对每个子问题走现有 `RetrieveService`+rerank → 汇聚 chunks 入 `ContextAssembler`（去重+预算截断）→ 最终合成。复用多轮 `QueryTransformService` 的指代消解能力。
- **取舍**：分解错误会放大；先做「并行多 query 检索 + 合并」这种低风险变体。
- **验收**：复杂/对比类 query 的 Answer Faithfulness 提升。
- **依赖**：分解用的 LLM 调用（可并入主对话模型）。

## RAG-064 Matryoshka / 嵌套向量表示　优先级 P2
- **目标**：用 Matryoshka Representation 训练/选用嵌入，使同一向量可截断到 64/128/256 维仍保语义，降低存储与延迟。
- **业界方案**：OpenAI text-embedding-3 自带 MRL；开源 bge-m3、e5-mistral 支持；检索时对前 k 维做近似、精排用全维。
- **接入点**：`BailianConfig.embeddingModel` 切换为 MRL 模型；`vector_data.embedding` 维度可变 → 需放宽建表维度约束（当前固定 1536，迁移改为按模型维度或存变长）；粗排用截断维度、精排用全维。
- **取舍**：依赖模型是否支持 MRL；维度可变需改 schema 与 `EmbeddingService` 维度校验。
- **验收**：截断到 128 维的 Recall@k 与全维差距 < 2%，存储/延迟下降 > 40%。
- **依赖**：支持 MRL 的嵌入模型。

## RAG-065 Listwise / 强重排器集成　优先级 P1
- **目标**：用 listwise 重排（一次对候选列表整体打分排序）替代当前 pointwise/rrf，提升top精度。
- **业界方案**：RankGPT（LLM listwise）、Cohere rerank、Alibaba gte-rerank / qwen3-rerank（已接入 `RerankService`）。
- **接入点**：`RerankService` 已支持 qwen3-rerank（pointwise/紧邻）。扩展为 listwise：把候选拼接成带序号的列表让模型整体重排；需模型支持 listwise prompt。可与现有 `rerank-mode` 配置并存（新增 `listwise` 模式）。
- **取舍**：listwise 对长候选成本高、延迟大 → 仅对 top-N(如20) 做 listwise。
- **验收**：在 eval 集上对比 rrf vs listwise 的 NDCG@k / 答案质量。
- **依赖**：支持 listwise 的 rerank 端点。

## RAG-066 ColPali / 视觉文档检索　优先级 P3
- **目标**：对扫描件/图表/版式复杂 PDF 做「页面图像→多向量」检索，突破纯文本抽取（RAG-026 的局限）。
- **业界方案**：ColPali / ColQwen2（视觉语言模型把页面编码为 patch 向量）、ColPali 索引（Late Interaction）。
- **接入点**：新增 `ImageIngestService`（PDF 每页渲染为图 → ColPali 编码为多向量）+ `ImageRetriever`（查询文本 vs 页面 patch 向量 Late Interaction）。与现有文本检索并行，结果在 `ContextAssembler` 合并。
- **取舍**：需 VLM 推理（GPU/推理服务）、存储多向量/页；仅对「文本抽取质量差」的文档启用（如扫描件）。
- **验收**：扫描件/表格图问答准确率显著优于文本路径。
- **依赖**：ColPali 推理服务 + GPU；图像存储。

## RAG-067 在线反馈学习闭环　优先级 P2
- **目标**：收集用户对答案/引用的反馈（👍/👎、纠偏），反哺检索与生成（难负例挖掘、提示优化、知识库修订）。
- **业界方案**：用户反馈日志 + 难负例重训/重排、**RLHF/DPO** 微调（重）、RAG 自改进（self-correct）。
- **接入点**：`QaTrace`（RAG-042 已有 query/answer/sources/score）扩展 `feedback` 字段；新增 `FeedbackService` 汇聚反馈 → ① 难负例注入 eval 回归集 ② 高频被踩的 chunk/文档触发质检 ③ 反馈聚合看板（复用 RAG-053 报表）。
- **取舍**：直接微调成本高 → 先做「反馈驱动的数据闭环与评估」，不直接动模型权重。
- **验收**：反馈流入 eval 集，回归拦截质量退化；被踩 chunk 可被定位与修订。
- **依赖**：反馈前端埋点（管理端/对话 UI）。

---

## 落地建议（排期）
- **P0**：RAG-060（自适应检索，复用 rerank，性价比最高）
- **P1**：RAG-061（图谱）、RAG-062（RAPTOR）、RAG-065（listwise 重排）—— 直接增强检索质量
- **P2**：RAG-063（分解）、RAG-064（MRL）、RAG-067（反馈闭环）—— 需配合模型/数据
- **P3**：RAG-066（ColPali）—— 依赖 VLM 基建，单独专项

> 以上均为**设计态交付**：未改动运行时代码，待基建就绪后按优先级转为实现任务。
