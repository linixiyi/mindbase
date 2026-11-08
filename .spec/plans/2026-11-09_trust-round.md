# 信任与评测轮（2026-11-09，源自五路只读审计 + 12 周落地路线）

审计产物（本会话，未入库）：`.runtime/research/01-rag-backend.md`、`02-platform-ops.md`、`03-frontend-ux.md`、`04-eval-existing.md`、`05-agent-eval-sota.md`。
本计划把审计结论与 12 周路线拆成 5 个可独立验收的轮次；每轮一个 `spec/` 分支、一个任务包，**串行执行，不并行**。

## 12 周路线 → 轮次覆盖对照

| 周 | 原定产出 | 落在 |
| --- | --- | --- |
| 1 | 评测跑 ReAct 路径；报告加 schemaVersion/commit/alias/promptVersion；跑通 eval_compare.py；7 份旧报告按 Wilson CI 重述 | R2 task_0002 / task_0001 / task_0006 / task_0008 |
| 2 | 数据重建：≥10 篇文档、120 dev / 60 test、gold 改 chunkId、负样本 15 题 | R2 task_0005（schema）+ task_0010（重建，依赖 owner 提供文档） |
| 3–4 | 指标改造：nDCG@10、去掉 anyMatch、异常单列、citation precision/recall、faithfulness | R2 task_0003（检索）+ task_0009（生成/引用）+ R3 task_0004（阈值曲线与分桶） |
| 5 | 评委：换模型 + 100 条校准集 + kappa 出数 + minKappa=0.6 生效 | R2 task_0004 |
| 6 | 门禁：20 题 smoke + McNemar / paired bootstrap + 报告归档 | R2 task_0006（本地门禁）+ task_0007（统计口径） |
| 7–8 | 打 P0（跨租户、refresh、XSS、空召回语义）+ 补 trace span | R1（P0，**已 active**）+ R4 task_0001（trace） |
| 9–10 | Langfuse 接入、feedback 持久化、在线 5 个信号看板 | R4 task_0001~0004 |
| 11 | 失败 trace 标签体系 + 周度人工抽检 | R5 task_0001 ~ task_0002 |
| 12 | 全量跑一次 + 写一份「评测方法论」文档 | R5 task_0003 + task_0004 |

未覆盖项（有意排除）：不做 150 题的合成题灌水；不引入付费 SaaS（Langfuse 自托管、promptfoo 本地 CLI）。

## 轮次总览

| 轮次 | slug | 主题 | 任务数 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | `2026-11-09_fix-p0-isolation` | 跨租户隔离 / 令牌撤销 / 渲染安全 / 空结果语义 | 5 | **active**（分支 `spec/2026-11-09_fix-p0-isolation`） |
| R2 | `2026-11-09_add-eval-harness-v2` | 评测接生产路径、报告可复核、指标与统计、评委校准、本地门禁 | 10 | planned |
| R3 | `2026-11-09_fix-rag-runtime-quality` | ReAct 预算、引用校验、分块重叠、阈值曲线、ES 字段、可观测性 | 6 | planned |
| R4 | `2026-11-09_add-eval-observability` | trace span、feedback 持久化、在线 5 信号、看板 | 4 | planned |
| R5 | `2026-11-09_add-eval-closeout` | 失败 trace 标签、周度人工抽检、全量基线、评测方法论 | 4 | planned |
| R6（可选） | `2026-11-09_fix-chat-ux` | Enter/停止分离、双历史加载器、流式渲染 O(n²)、前端测试 | 4 | planned |

执行约定：
- 每轮开始前从最新 `main` 建分支：`git switch -c spec/<slug> main`；`done` 归档合并后再开下一轮。
- 轮内任务按 `depends-on` 顺序；每个任务只在 `verify` 真跑通过后勾选。
- 不 push，除非用户当轮明确要求；不新增或修改 `.github/workflows/**`，所有门禁走本地命令。
- 物化成包时：把"背景证据"搬进 `spec.md` §2.1，把任务表搬进 `tasks.md`（每行即一条 `boundary`/`verify`）。

---

## R1 · P0 隔离与故障语义（active，见 `.spec/specs/2026-11-09_fix-p0-isolation/`）

对应路线周 7–8 的 P0 部分：跨租户 MD5 冲突、refresh 轮换与撤销、聊天渲染 XSS、空结果三分类。

---

## R2 · 评测基线 v2（`2026-11-09_add-eval-harness-v2`）

### 背景证据（写进 spec.md §2.1）
- G1 评测生成侧脱产：`LlmProviderRouter.buildRagMessages` 全仓只被 `service/eval/RagEvalCaseRunner.java:148` 调用；生产走 `ChatHandler.java:274 buildReActMessages` + 最多 4 轮（`ChatHandler.java:57`）。
- G2 报告不可复核：归档只留 `caseId` 与数字（`docs/eval/reports/README.md:33`）；无 schemaVersion / git commit / ES alias / embedding 版本；`scripts/eval_compare.py:48` 读 `summary` 对归档直接 KeyError。
- G3 口径偏差：`RetrievalMetrics.java:94` `anyMatch` 只要求命中任一 evidence（25/50 题有 ≥2 条）；空 evidence 直接判真（`:90-92`）；召回分母丢弃检索异常例（`EvalReportAggregator.java:41-48` + `RagEvalCaseRunner.java:82-87`）；无 nDCG、K 恒为 5。
- G4 评委不独立且门禁失效：生成与评委同 provider 同模型；`RagEvalProperties.java:46-50` `minKappa` 默认空（从不检查）、`minComparableCases=5`、固定 seed 42 恒定抽 1 例 → kappa 从未产出。
- G5 无门禁：没有本地可跑的评测回归入口；单线程串行 12–15 分钟/轮；无 token 记账。
- G6 统计缺失：7 份归档无 CI/显著性；43/50 的 Wilson 95% CI = [73.8, 93.1]；04 轮 vs 05 轮差 1 例被写成"回归"。
- G7 数据集结构：`EvalCase` 无 `split/type/difficulty`，题型只存在于 caseId 前缀（仅 `scripts/eval_compare.py:9,15` 解码）；`expectedFileMd5s` 0/50；37/50 指向同一篇 `mindbase.pdf`。

### 任务
| # | 任务 | depends-on | boundary | verify |
| --- | --- | --- | --- | --- |
| 1 | 报告 schema v2：`schemaVersion`、`gitCommit`、`esAlias`、`embeddingModel`、`promptVersion`、token/费用、全量 case 明细（问题/检索块文本/回答/评委理由/逐 claim 校验），写入改"临时文件 + rename" | — | `service/eval/EvalReport.java`、`EvalCaseResult.java`、`RagEvalService.java`、`EvalReportAggregator.java` + 新测试 | `./mvnw -q -B -Dtest=EvalReportSchemaV2Test test` |
| 2 | 评测接生产 ReAct 路径：把 `ChatHandler` 的编排抽成与 `WebSocketSession` 无关的 `ChatPipeline`，`ChatHandler` 改适配器；评测新增 `mode=react`（默认）保留 `mode=rag` 对照 | 1 | `service/ChatHandler.java`、新增 `service/ChatPipeline.java`、`service/eval/RagEvalCaseRunner.java`、`RagEvalProperties.java` + 新测试 | `./mvnw -q -B -Dtest=EvalReActPipelineTest test` |
| 3 | 检索指标改造：nDCG@K、多证据 coverage（替代 `anyMatch`）、`retrievalErrorRate` 单列、K=3/5/10 曲线 | 1 | `service/eval/RetrievalMetrics.java`、`EvalReportAggregator.java`、`RagEvalProperties.java` + 新测试 | `./mvnw -q -B -Dtest=RetrievalMetricsV2Test test` |
| 4 | 评委独立与校准门禁：judge 独立 provider/model（默认与被测模型不同族）、`minKappa` 默认 0.6 且生效、30–100 条校准集与 kappa 出数 | 3 | `config/RagEvalProperties.java`、`service/eval/JudgeHumanAgreement.java`、`RagEvalCaseRunner.java`、新增 `src/main/resources/eval/calibration-v1.jsonl` + 新测试 | `./mvnw -q -B -Dtest=JudgeCalibrationGateTest test` |
| 5 | 数据集 schema：`EvalCase` 增 `split/type/difficulty/expectedRefusal`，parser 校验并拒绝重复 id；报告按 split/type 分组 | — | `service/eval/EvalCase.java`、`EvalDatasetParser.java`、`src/main/resources/eval/mindbase-eval-v1.jsonl`、`sample-eval.jsonl` + 新测试 | `./mvnw -q -B -Dtest=EvalDatasetParserV2Test test` |
| 6 | 本地门禁：修 `scripts/eval_compare.py` 兼容两套 schema 并支持 `--fail-if`；新增 `scripts/eval_gate.sh` 一条命令跑 20 例离线 fixture（mock LLM/检索，不联网） | 1,2,3 | `scripts/eval_compare.py`、新增 `scripts/eval_gate.sh`、新增 `EvalSmokeFixtureTest.java`；不改 `.github/workflows/**` | `cd /Users/linyixi/PaiSmart && python3 scripts/eval_compare.py --self-test && ./mvnw -q -B -Dtest=EvalSmokeFixtureTest test` |
| 7 | 统计口径：所有比例带 Wilson CI、同题集配对 McNemar / paired bootstrap、`--fail-if` 用 CI 下界判定 | 3,6 | `service/eval/EvalStatistics.java`（新）、`EvalReportAggregator.java`、`scripts/eval_gate.sh` + 新测试 | `./mvnw -q -B -Dtest=EvalStatisticsTest test` |
| 8 | 旧报告重述：写脚本从 7 份归档重算 Wilson CI，生成 `docs/eval/reports/README-v2.md`，把"04 回归/05 修复"改为"差 1 例不可区分" | 7 | 新增 `scripts/eval_restate_reports.py`、新增 `docs/eval/reports/README-v2.md`；不改归档 JSON | `cd /Users/linyixi/PaiSmart && python3 scripts/eval_restate_reports.py --check` |
| 9 | 生成与引用指标：faithfulness（claim 级）、citation precision / recall、拒答正确率；负向权限用例（越权文档不应被召回）≥3 条 | 1,3 | `service/eval/GenerationMetrics.java`（新）、`RagEvalCaseRunner.java`、`RagEvalService.java`、数据集 + 新测试 | `./mvnw -q -B -Dtest=GenerationMetricsTest test` |
| 10 | 数据重建：用 `eval-datasets/corpus/` 的 10 篇真实文档（清单 `eval-datasets/manifest.json`）建评测知识库、≥120 dev / 60 test、gold 改 chunkId、负样本 ≥15 条、按题型配比 | 5 | `src/main/resources/eval/eval-v2.jsonl`（新）、`docs/eval/reports/README-v2.md`；语料已就位，PDF 不入库 | `./mvnw -q -B -Dtest=EvalDatasetIntegrityTest test` |

### 验收标准
- 归档可被第三方复核：打开 JSON 能看到问题、检索文本、回答、评委理由、逐 claim 结论。
- 评测默认走与生产同一套 ReAct 编排，报告可见工具轮数与引用编号。
- 报告带 nDCG@5、检索异常率、多证据覆盖率、faithfulness、citation precision/recall。
- 评委与被测模型不同族；kappa < 0.6 时报告显式标记"不可用于发布判断"。
- 本地一条命令（`scripts/eval_gate.sh`）可跑 20 例离线冒烟；统计口径带 CI，`--fail-if` 用 CI 下界判断。

### 明确不做
- 不做合成题灌水（真实语料由 owner 提供；未提供则 task_0010 保持 blocked，其余任务照常）。
- 不引入 Python 评测框架；Java 自建 harness 是唯一事实来源。
- 不接付费 SaaS；Langfuse 放在 R4 且自托管。

---

## R3 · RAG 运行时质量（`2026-11-09_fix-rag-runtime-quality`）

### 背景证据
- H1 ReAct 无上下文预算：`LlmProviderRouter.java:575-602` 的 token 估算只用于计费（`:218/:258`）；父块 1500 字 × 5 命中 × 最多 8 次工具调用无裁剪；`ChatHandler.java:430-465` 引用编号 `max+1` 无 `fileMd5+chunkId` 去重。
- H2 引用校验默认关：`application.yml:444-446`；超过 `max-claims=12` 静默丢弃、整体失败返回空 Map 无"未校验"标记；`generate_summary`（`DeepSeekClient.java:276-289`）不产出 `来源#N`。
- H3 分块重叠写进正文：`ParseService.java:596-605` 把上一块最多 100 字以 `\n\n` 前置进 `textContent`。
- H4 阈值硬编码且贴着正确分块下沿：`application.yml:348-351`（0.1 vs 0.14~0.19），无启动校验、无按 `retrievalMode` 分桶统计。
- H5 ES 权限字段靠巧合自洽：查询 `public`（`HybridSearchService.java:271`）vs mapping `isPublic`。
- H6 可观测性：无 `micrometer-registry-prometheus`；`application.yml:382-393` 只暴露 health；logback 渲染 `%X{traceId}` 而 REST 写的是 `requestId`。

### 任务
| # | 任务 | depends-on | boundary | verify |
| --- | --- | --- | --- | --- |
| 1 | ReAct 上下文预算与引用去重：按 token 预算裁剪旧 tool 消息（父块降级 + 截断），按 `fileMd5+chunkId` 复用引用编号 | — | `service/ChatHandler.java`（或 R2 的 `ChatPipeline`）、`service/LlmProviderRouter.java`、`service/AgentToolRegistry.java` + 新测试 | `./mvnw -q -B -Dtest=ChatContextBudgetTest test` |
| 2 | 引用校验语义：默认开启、超 `max-claims` 标 `UNVERIFIED`、整体失败标 `UNVERIFIED`、summary 标"无引用摘要" | 1 | `service/citation/CitationVerificationService.java`、`handler/ChatWebSocketHandler.java`、`application.yml`、`service/DeepSeekClient.java` + 新测试 | `./mvnw -q -B -Dtest=CitationVerificationSemanticsTest test` |
| 3 | 分块重叠不入正文：正文只存原文，重叠长度存 `overlapPrefixLen`，父块合并按长度裁 | — | `service/ParseService.java`、`service/chunk/ChunkTextMerger.java`、chunk 元数据 + 新测试 | `./mvnw -q -B -Dtest=ChunkOverlapMetadataTest test` |
| 4 | 阈值曲线与分桶：阈值可配 + 启动校验；按 `retrievalMode` 记录滤除数/得分分布；评测侧输出阈值-召回曲线 | 3 | `service/HybridSearchService.java`、`config/HybridSearchProperties.java`、`application.yml`、`service/eval/RetrievalMetrics.java` + 新测试 | `./mvnw -q -B -Dtest=RerankThresholdCurveTest test` |
| 5 | ES 权限字段显式化与启动自检：`@JsonProperty` 固定字段名，mapping 与查询同源；字段缺失拒绝启动 | — | `entity/EsDocument.java`、`es-mappings/knowledge_base.json`、`service/index/KnowledgeIndexManager.java` + 新测试 | `./mvnw -q -B -Dtest=EsPermissionFieldContractTest test` |
| 6 | 可观测性最小集：`micrometer-registry-prometheus`、暴露 `health,info,metrics,prometheus`、`requestId` 进 logback pattern、关键 Timer | — | `pom.xml`、`application.yml`、`logback-spring.xml`、`config/LoggingInterceptor.java`、`utils/LogUtils.java` + 新测试 | `./mvnw -q -B -Dtest=ObservabilityContractTest test` |

### 验收标准
- 长会话（>10 轮、每轮 5 命中）单轮 prompt token 有上界，不丢最近 2 轮对话。
- 引用校验开启后，超出上限的引用显示"未校验"而不是"已通过"。
- 分块正文不再含上一块尾部重叠；父块合并仍拿到重叠上下文。
- 阈值可配、启动校验、评测能出阈值-召回曲线；ES 字段缺失时启动失败。

---

## R4 · 在线评测与可观测（`2026-11-09_add-eval-observability`）

### 背景证据
- I1 反馈死胡同：`submit_feedback` 写 Redis hash `feedback:{userId}`（`AgentToolRegistry.java:151-163`），`ChatHandler.java:722-745` 只把它拼进 prompt 做风格引导；无 messageId/traceId 关联、无持久化、无聚合。
- I2 无 trace：回答链路没有 span，检索/rerank/工具调用/生成/token 无法按单次回答回放。
- I3 无在线指标：空召回率、引用展开率、追问率、P95、每问成本均未采集。
- I4 工具可选：Langfuse 自托管（MIT 核心 + OTLP）可做 trace 可视化；不引入付费 SaaS。

### 任务
| # | 任务 | depends-on | boundary | verify |
| --- | --- | --- | --- | --- |
| 1 | trace 落库：每次回答一个 `answer_trace`（turn/工具/检索/rerank/生成/token/引用校验各 span），先落 MySQL + JSONL，OTLP sink 可插拔（Langfuse 可选） | — | 新增 `service/trace/`、`entity/AnswerTrace.java`、`repository/AnswerTraceRepository.java`、`handler/ChatWebSocketHandler.java` 挂载点 + 新测试 | `./mvnw -q -B -Dtest=AnswerTraceRecorderTest test` |
| 2 | feedback 持久化：从 Redis hash 改为 `message_feedback` 表（messageId/traceId/rating/reason/createdAt），保留 prompt 引导读最近 5 条 | 1 | `service/AgentToolRegistry.java`、新增 `entity/MessageFeedback.java`、`repository/MessageFeedbackRepository.java`、`ChatHandler.java` + 新测试 | `./mvnw -q -B -Dtest=FeedbackPersistenceTest test` |
| 3 | 在线 5 信号：空召回率、引用展开率、追问率、停止/重试率、P95 延迟 + 每问 token/费用；按天聚合 | 1,2 | 新增 `service/metrics/OnlineSignalsService.java`、定时聚合任务、`controller` 查询端点 + 新测试 | `./mvnw -q -B -Dtest=OnlineSignalsServiceTest test` |
| 4 | 看板：管理端"在线质量"页展示 5 信号趋势与 Top 失败会话入口 | 3 | `frontend/src/views/online-quality/`（新）、路由与 `service/api` + 前端测试 | `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec vitest run src/views/online-quality/__tests__/signals.spec.ts` |

### 验收标准
- 任取一条回答，能从 trace 看到它走了几轮、调了哪些工具、检索命中什么、token 多少、引用校验结果。
- 点踩样本 100% 可追溯到 messageId + traceId，并进入人工队列。
- 管理端能看到 5 个在线信号按天趋势。
- 不引入付费 SaaS；Langfuse 若接入则自托管，且不接入也能工作。

---

## R5 · 评测收口（`2026-11-09_add-eval-closeout`）

| # | 任务 | depends-on | boundary | verify |
| --- | --- | --- | --- | --- |
| 1 | 失败 trace 错误标签：`RETRIEVAL_MISS / WRONG_DOC / RERANK_KILL / CONTEXT_TRUNCATED / HALLUCINATION / CITATION_MISMATCH / REFUSAL_FAIL / TOOL_MISUSE`，LLM 只打标签不下结论 | R4 | 新增 `service/eval/TraceErrorLabeler.java`、标签落库 + 新测试 | `./mvnw -q -B -Dtest=TraceErrorTaxonomyTest test` |
| 2 | 周度人工抽检：按标签分层抽样 ≥20 条，双人盲评（遮住 judge 标签），加权 kappa + 分数 MAE | 1 | `service/eval/HumanReviewSampler.java`、`EvalHumanReviewService.java`、管理端录入 + 新测试 | `./mvnw -q -B -Dtest=HumanReviewBatchTest test` |
| 3 | 评测方法论文档：指标定义、数据集口径、评委校准、统计与门禁、数据集维护流程 | — | 新增 `docs/eval/methodology.md` | `test -s /Users/linyixi/PaiSmart/docs/eval/methodology.md && grep -q "kappa" /Users/linyixi/PaiSmart/docs/eval/methodology.md` |
| 4 | 全量基线与归档：跑一次全量（react 模式、3 次重复），归档并写进方法论文档的基线表 | 1,2,3；需模型 key 与 ES | 只写 `docs/eval/reports/` 与 `docs/eval/methodology.md` | `verify: manual: 全量评测依赖真实 ES 与模型 key，命令为 `./scripts/eval_gate.sh --full`；通过标准是归档 JSON 含 schemaVersion、repeated=3、CI 字段非空` |

---

## R6（可选）· 聊天体验（`2026-11-09_fix-chat-ux`）

| # | 任务 | verify |
| --- | --- | --- |
| 1 | 生成中 Enter 入队而非停止；停止独立按钮并显示"已停止" | `cd frontend && pnpm exec vitest run src/store/__tests__/chat-stream.spec.ts` |
| 2 | 合并两条历史加载器，请求层支持 `AbortController` | 同上 |
| 3 | 流式渲染改"纯文本增量 + 完成后 markdown"，chunk 按 rAF 批处理 | `pnpm typecheck && pnpm exec vitest run src/views/chat/__tests__/stream-render.spec.ts` |
| 4 | 长会话分页 / 虚拟滚动 | `pnpm typecheck` |

---

## 全局约束

- 不改部署脚本与密钥；不引入新中间件（Prometheus registry 除外，属于依赖）。
- 所有新增测试默认走 Mockito/切片测试，不依赖 ES/Kafka/MinIO；需要 H2 的用 `@DataJpaTest`。
- 每轮 `done` 前跑一遍全量 `./mvnw -q -B test`（本机等价，不依赖 GitHub Actions），失败即视为未收敛。
- 本计划不新增或修改 `.github/workflows/**`，所有门禁以本地命令交付；GitHub Actions 额度与 billing 不在范围内。
- 不 push，除非用户当轮明确要求。
- 前置依赖：R2 task_0010 的语料已就位——`eval-datasets/corpus/` 10 篇真实 PDF（12–203 页，中英混合，含 1 篇接近纯扫描的 OCR 用例），清单与选型理由见 `eval-datasets/manifest.json`；PDF 由 `.gitignore` 排除，不入库。
