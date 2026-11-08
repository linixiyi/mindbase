# 评测体系 v2（接生产路径 + 可复核 + 统计门禁） - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：把现有离线评测从"自测工具"升级为可用于发布判断的回归体系——测生产真实链路、报告可被第三方复核、口径与统计能支撑"改了到底有没有变好"的结论。
- **目标用户**：项目作者与评审者；后续接手 RAG 调参与模型切换的执行端。
- **核心价值**：每一次检索/提示词/模型改动都能给出带置信区间的结论，而不是一个孤立的准确率数字。
- **Literal ask**: "把这十个写进包里，然后跟我说我要怎么跟zcode说"
- **Stated purpose**: 让执行端（ZCode）按包独立完成评测改造，并用本机 10 篇真实 PDF 重建评测语料。
- **Inferred outcome**: 评测默认走生产 ReAct 路径，归档带 schemaVersion/commit/alias 与全量 case 明细，指标含 nDCG/引用/忠实度，评委独立且 kappa 门禁生效，本地一条命令可跑 20 题冒烟；confidence: high; evidence: `.runtime/research/04-eval-existing.md`、`05-agent-eval-sota.md` 与 §2.1 逐条复核。
- **Constraints and exclusions**: 不引入 Python 评测框架；不接付费 SaaS；不改 `.github/workflows/**`；不改 R1 范围内的 P0 修复；PDF 语料不入库。
- **Trade-off preference**: quality（结论可信优先于跑得快）

## 2. 假设与待确认

### 2.1 已确认事实
- G1 生成侧脱产：`LlmProviderRouter.buildRagMessages` 全仓只被 `service/eval/RagEvalCaseRunner.java:148` 调用；生产走 `ChatHandler.java:274 buildReActMessages` + 最多 4 轮（`ChatHandler.java:57`）。
- G2 报告不可复核：归档只留 `caseId` 与数字（`docs/eval/reports/README.md:33`）；无 schemaVersion / git commit / ES alias / embedding 版本；`scripts/eval_compare.py:48` 读 `summary`，对归档直接 KeyError。
- G3 口径偏差：`RetrievalMetrics.java:94` `anyMatch` 只要求命中任一 evidence（25/50 题有 ≥2 条）；空 evidence 直接判真（`:90-92`）；召回分母丢弃检索异常例（`EvalReportAggregator.java:41-48` + `RagEvalCaseRunner.java:82-87`）；无 nDCG，K 恒为 5。
- G4 评委与门禁：生成与评委同 provider 同模型；`RagEvalProperties.java:46-50` `minKappa` 默认空（从不检查）、`minComparableCases=5`；固定 seed 42 每轮只抽中同一例，kappa 从未产出。
- G5 无门禁：没有本地可跑的评测回归入口；单线程串行 12–15 分钟/轮；无 token 记账。
- G6 统计缺失：43/50 的 Wilson 95% CI = [73.8, 93.1]；04 轮 vs 05 轮只差 1 例却被写成"回归"。
- G7 数据集结构：`EvalCase` 无 `split/type/difficulty`；`expectedFileMd5s` 0/50；37/50 指向同一篇 `mindbase.pdf`。
- G8 语料已就位：`eval-datasets/corpus/` 有 10 篇真实 PDF（硬链接，不占额外磁盘，`*.pdf` 已被 gitignore），清单见 `eval-datasets/manifest.json`，构成如下：

| 文件 | 页 | 字符/页 | 语言/类型 | 在评测里的作用 |
| --- | --- | --- | --- | --- |
| 01-clip-radford-2021.pdf | 48 | 4719 | en / 双栏论文 | 长文档 + 双栏切分；md5 1fc956711b |
| 02-dinov2-oquab-2024.pdf | 32 | 3299 | en / TMLR 论文 | 单栏模板、公式脚注；md5 cf7a4fe0f3 |
| 03-denoising-vit-yang-2024.pdf | 34 | 2244 | en / 双栏论文 | 图注密集、图文混排；md5 0b4044f036 |
| 04-tactile-force-chen-2025.pdf | 40 | 2269 | en / 双栏论文 | 跨领域检索；md5 3592bb7f2f |
| 05-medical-instrumentation-ch2.pdf | 59 | 1724 | en / 教材章节 | 公式 + 表格 + 小节编号；md5 6d7c633843 |
| 06-drug-discovery-a16z.pdf | 12 | 1588 | en / 长报告 | 叙事型无章节；md5 fcbea2542f |
| 07-os-filesystem-ppt-zh.pdf | 203 | 66 | zh / 扫描型 PPT | **OCR 链路 + 超长文档**；md5 3e969d119b |
| 08-os-review-zh.pdf | 24 | 498 | zh / 问答讲义 | 中文短问短答命中；md5 0f44c50208 |
| 09-bme6144-week6-slides.pdf | 34 | 503 | en / 幻灯片 | 低密度要点式；md5 75fc40341c |
| 10-bme6111-week4-slides.pdf | 40 | 610 | en / 幻灯片 | 含公式与表格的幻灯片；md5 4ca04ccaca |

### 2.2 关键假设
- A1 | 评测可以在不调真实模型的情况下跑门禁 | kind: system | evidence: 现有 12 个 eval 测试类全是 Mockito 单测 | CONFIRMED | if false: 门禁只能手动跑，task_0006 需要降级为 manual
- A2 | 存在第二家模型可用于独立评委 | kind: external | evidence: 项目已支持多 provider 主备配置（`ModelProviderConfigService`） | UNVERIFIED | if false: 回退同族评委，但报告必须标 `SELF_JUDGE` 并把结论降级为"仅趋势"
- A3 | 10 篇 PDF 都能上传并解析，含 07 的 OCR 路径 | kind: system | evidence: G8 语料已就位；07 字符密度 66/页 | UNVERIFIED | if false: 07 从题集剔除，OCR 覆盖另补扫描件，其余 9 篇照常
- A4 | 题目可由执行端从 PDF 生成、再由人工抽检 | kind: system | evidence: 10 篇文档内容自包含、主题明确 | UNVERIFIED | if false: 只保留人工写的 60 题，split 按 40/20 缩小

### 2.3 待确认问题
- [x] 评委换哪家？→ 结论：默认用与被测模型不同族的 provider，配置项 `eval.judge.provider/model`；未配置时回退当前模型但报告标 `SELF_JUDGE`
- [x] 语料能否入库？→ 结论：不能。PDF 只在本地，仓库只提交 `manifest.json` 与题集 JSONL
- [x] 题目规模先做多少？→ 结论：第一版 ≥120 题（dev 90 / test 30）、负样本 ≥15；后续再扩到 180

### 2.4 可选解释与取舍
- 当前选择：先修 harness（可复核 + 走生产路径 + 统计），再重建题集 → 理由：题集是一次性投入，harness 决定后续每一轮能不能得出结论
- 当前选择：Java 自建 harness 保持唯一事实来源，不引入 RAGAS/DeepEval → 理由：指标公式简单，引入 Python 运行时会让部署与 CI 复杂化

### 2.5 已证伪路径
- 无（暂无证伪记录）

<!-- spec:external-facts -->
### 2.6 外部事实
- 无（指标定义与统计方法属于通用知识；工具选型不在本轮）

## 3. 功能范围

### 3.1 核心功能（MVP）
- [ ] 报告 schema v2：版本、来源、全量 case 明细、token/费用
- [ ] 评测默认走生产 ReAct 路径，保留单轮 RAG 对照
- [ ] 检索指标：nDCG@K、多证据覆盖率、检索异常率、K 曲线
- [ ] 生成与引用指标：faithfulness、citation precision/recall、拒答正确率
- [ ] 评委独立化与 kappa 硬门禁（默认 0.6）
- [ ] 统计口径：Wilson CI + McNemar / paired bootstrap + `--fail-if`
- [ ] 本地门禁：`scripts/eval_gate.sh` 跑 20 题离线 fixture
- [ ] 数据集重建：10 篇真实语料、≥120 题、dev/test 划分、gold 改 chunkId

### 3.2 扩展功能
- 旧报告按 Wilson CI 重述（作为交付文档，不阻塞）

### 3.3 不在范围内
- 在线评测与 trace（R4）、失败标签与周度抽检（R5）
- RAG 运行时质量（ReAct 预算、引用校验默认值、分块重叠）：R3
- 前端评测页改造（只保证后端 API 兼容）
- 引入 Python 评测框架、付费 SaaS、GitHub Actions 变更

## 4. 最小实现路径
- 最简单可行方案：先让评测跑生产编排并把每次结果完整落盘，再补指标与统计，最后用真实语料重建题集。
- 暂不引入：独立评测服务、异步任务队列、报告数据库；继续用文件归档。
- 不做的抽象/配置化：不做多租户评测隔离、不做评测集版本管理 UI。
- 布局判断：现有 `service/eval/` 包内聚良好，本轮只做类内改造与新增两个类（`EvalStatistics`、`GenerationMetrics`），不拆包、不迁移目录。

### 4.1 方案阶梯
- B0 | literal ask | 按 §3.1 全做：harness 改造 + 统计 + 真实语料重建；工作量最大但一次到位；风险中等（题目质量依赖人工抽检）；可逆：代码可回滚，题集可重生成
- B1 | minimal change | 只改报告 schema + 走 ReAct 路径，指标与统计不动；能解决"测的不是线上"，但结论仍无置信区间
- B2 | recommended alternative | 保留现有 harness，另起 Python RAGAS 侧车做指标；生态成熟但引入第二运行时，与"Java 唯一事实来源"冲突

Recommendation: B0 because A2 失败时也只是评委降级，不影响其余交付；B1 不能解决"结论不可信"这个根因。Flips if A1 is FALSIFIED（门禁只能手动，需重排 §3.1 优先级）。

## 5. 技术决策
- Workflow: FULL
- 技术栈：Java 17 / Spring Boot 3.4 / JPA；评测报告继续落 JSON 文件
- 证据协议：evidence: runs
- 本轮允许改动：`src/main/java/com/mindbase/service/eval/**`、`config/RagEvalProperties.java`、`service/ChatHandler.java`（仅抽取 pipeline）、新增 `service/ChatPipeline.java`、`scripts/eval_compare.py`、新增 `scripts/eval_gate.sh`、新增 `scripts/eval_restate_reports.py`、`src/main/resources/eval/**`、`src/test/java/com/mindbase/service/eval/**`、`docs/eval/**`
- 本轮不应触碰：R1 的 P0 修复文件、前端交互代码、`.github/workflows/**`、部署脚本、`service/chunk/**` 与 `service/search/**` 的排序逻辑
- Autonomy: medium
- Git integration branch：`spec/2026-11-09_add-eval-harness-v2`（执行前置：R1 归档并合并到 main 后再创建；当前工作树在 R1 分支上时不要执行本包）

### 5.1 验证策略
- 范围级别：package
- UI surface: unchanged
- 变更对象：评测执行入口、报告 schema、指标计算、评委配置与门禁、题集文件、本地门禁脚本
- 快速检查：`cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalReportSchemaV2Test,EvalReActPipelineTest,RetrievalMetricsV2Test,JudgeCalibrationGateTest,EvalDatasetParserV2Test,EvalStatisticsTest,GenerationMetricsTest,EvalSmokeFixtureTest test`
- 集成检查：`cd /Users/linyixi/PaiSmart && python3 scripts/eval_compare.py --self-test && ./scripts/eval_gate.sh`
- 全项目检查：仅在改动共享基础设施、构建/依赖或发布载荷时执行：`cd /Users/linyixi/PaiSmart && ./mvnw -q -B test`
- 升级触发：若抽取 `ChatPipeline` 波及 WS 会话或前端契约，升级为 integration 并记录理由

<!-- spec:correction-log -->
### 5.5 修正日志
- none.

<!-- spec:deviation-log -->
### 5.6 偏离日志
- none.

## 6. 成功标准与验证方式
- [ ] 标准 A：归档 JSON 含 schemaVersion/gitCommit/esAlias/embeddingModel/promptVersion 与全量 case 明细，可被第三方复核 -> verify: task_0001
- [ ] 标准 B：评测默认走与生产一致的 ReAct 编排，可切换 rag 模式做对照 -> verify: task_0002
- [ ] 标准 C：检索指标含 nDCG@5、多证据覆盖率、检索异常率，K 可配 -> verify: task_0003
- [ ] 标准 D：评委与被测模型可不同族，kappa < 0.6 时报告标记不可用于发布 -> verify: task_0004
- [ ] 标准 E：报告带 Wilson CI，配对比较用 McNemar / bootstrap，`--fail-if` 用 CI 下界判定 -> verify: task_0007
- [ ] 标准 F：本地一条命令跑 20 题离线冒烟并通过 -> verify: task_0006
- [ ] 标准 G：新题集基于 10 篇真实语料，≥120 题、含 dev/test 与 ≥15 条负样本 -> verify: task_0010

## 7. 风险与约束
- 风险点：抽取 `ChatPipeline` 会动到 R1 刚改过的 `ChatHandler` -> 缓解：本包必须在 R1 归档后从最新 main 开分支，且只做抽取不做行为变更
- 风险点：真实语料题目质量不稳定 -> 缓解：题目必须带 `expectedEvidence`，抽 20 条人工核对，判为歧义的题目删除
- 风险点：07 是扫描件，OCR 未配置时长期 0 召回 -> 缓解：题集里 07 只出 "能/不能检索到" 的少量题，并在报告里单列 OCR 覆盖率
- 风险点：评委换族后分数不可与历史比较 -> 缓解：报告记录 judge model，历史报告标注"旧口径，不可直接对比"
