# 评测体系 v2（接生产路径 + 可复核 + 统计门禁） - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- 新任务包使用 `YYYY-MM-DD_<verb>-<object>`，详见 `references/naming-and-commits.md`
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）
- 多会话/多 agent 共享时，任务可选加一行 `claim: <认领者> <ISO 时间>` 声明在做者；纯信息约定，非锁、无机器键、不进门禁，勾选即失效，归档保留

## 阶段一：可复核与真实链路

- [x] task_0001 报告 schema v2 与全量明细
  - boundary: 只改 `service/eval/EvalReport.java`、`EvalCaseResult.java`、`EvalReportAggregator.java`、`RagEvalService.java`；新增 `src/test/java/com/mindbase/service/eval/EvalReportSchemaV2Test.java`。报告新增 `schemaVersion/gitCommit/esAlias/embeddingModel/promptVersion/tokenUsage/cost`，`cases[]` 保留 question/answer/retrievedText/judgeReason/claimVerdicts；写文件改为"临时文件 + rename"
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalReportSchemaV2Test test`
  - 成功判定：测试断言 schemaVersion 存在、cases 明细字段非空、写入中断不会留下半份 JSON、token/费用字段非空
  - 失败处理：若 git commit 在测试环境不存在（无 .git），允许注入 `unknown` 但仍保留字段；不要为此跳过字段

- [x] task_0002 评测接生产 ReAct 路径
  - depends-on: task_0001
  - boundary: 新增 `service/ChatPipeline.java`（与 `WebSocketSession` 无关的编排：ReAct 轮次、工具调用、引用装配），`service/ChatHandler.java` 改为适配器（只把 session 发送委托给 pipeline，不改行为）；`service/eval/RagEvalCaseRunner.java` 增加 `mode`（默认 `react`，保留 `rag`）；`config/RagEvalProperties.java` 增加 `mode`；新增 `EvalReActPipelineTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalReActPipelineTest test`
  - 成功判定：react 模式下工具调用轮数、引用编号可在报告里读到；生产 `ChatHandler` 的现有测试仍全绿；rag 模式结果与改造前一致
  - 失败处理：若抽取导致 WS 行为变化，回退到"pipeline 只服务评测、生产暂不切"并记录偏离，但必须在报告里标注两条链路的差异

## 阶段二：指标与评委

- [x] task_0003 检索指标改造
  - depends-on: task_0001
  - boundary: `service/eval/RetrievalMetrics.java`、`EvalReportAggregator.java`、`config/RagEvalProperties.java`；新增 `RetrievalMetricsV2Test.java`。新增 nDCG@K；多证据改 coverage；检索异常单列 `retrievalErrorRate`；K 支持 3/5/10
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=RetrievalMetricsV2Test test`
  - 成功判定：单证据题命中时 nDCG=1；两证据题只命中一条时 coverage=0.5 且不再算"全命中"；异常用例计入 `retrievalErrorRate` 而不是从分母消失
  - 失败处理：历史报告字段名冲突时，新字段并列输出，不覆盖旧字段

- [x] task_0004 评委独立化与 kappa 门禁
  - depends-on: task_0003
  - boundary: `config/RagEvalProperties.java`（`judge.provider/model`、`minKappa` 默认 0.6）、`service/eval/RagEvalCaseRunner.java`（评委走独立 provider，未配置时标 `SELF_JUDGE`）、`service/eval/JudgeHumanAgreement.java`（kappa 不达标即 `NEEDS_RECALIBRATION`）、新增 `src/main/resources/eval/calibration-v1.jsonl`（≥30 条人工标注）、新增 `JudgeCalibrationGateTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=JudgeCalibrationGateTest test`
  - 成功判定：评委不存在 provider 配置时报 `SELF_JUDGE`；kappa=0.4 时状态为 NEEDS_RECALIBRATION；kappa=0.7 时为 OK；校准集样本数 ≥30
  - 失败处理：没有第二家 key 时测试用 mock provider，不联网；真实评测由执行端在配置后跑

## 阶段三：数据集与门禁

- [x] task_0005 数据集 schema 扩展
  - boundary: `service/eval/EvalCase.java`、`EvalDatasetParser.java`、`src/main/resources/eval/mindbase-eval-v1.jsonl`、`sample-eval.jsonl`；新增 `EvalDatasetParserV2Test.java`。字段：`split(dev|test)`、`type`、`difficulty(1-3)`、`expectedRefusal`；parser 拒绝重复 id 与非法 split
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalDatasetParserV2Test test`
  - 成功判定：缺 split 的旧数据按 `dev` 处理并 warn；重复 id 解析失败；非法 split 解析失败
  - 失败处理：若旧数据集需要保持兼容，允许缺省值但必须在报告里标注 `legacyDataset=true`

- [x] task_0006 本地门禁与对比脚本
  - depends-on: task_0001, task_0002, task_0003
  - boundary: `scripts/eval_compare.py`（兼容两套 schema、支持 `--fail-if`、`--self-test`）、新增 `scripts/eval_gate.sh`（一条命令跑 20 题 fixture）、新增 `EvalSmokeFixtureTest.java`；不改 `.github/workflows/**`，不联网
  - verify: `cd /Users/linyixi/PaiSmart && python3 scripts/eval_compare.py --self-test && ./scripts/eval_gate.sh`
  - 成功判定：`--self-test` 用内置两份 fixture 报告跑通新旧 schema；`eval_gate.sh` 在无网络、无 ES 的情况下跑完 20 题并以 0 退出；任一指标低于阈值时以非 0 退出
  - 失败处理：fixture 需真实报告结构，可从 `docs/eval/reports/` 取一份脱敏样本改造，不要手写假字段

- [x] task_0007 统计口径
  - depends-on: task_0003, task_0006
  - boundary: 新增 `service/eval/EvalStatistics.java`（Wilson CI、McNemar、paired bootstrap）、`EvalReportAggregator.java`、`scripts/eval_gate.sh`；新增 `EvalStatisticsTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalStatisticsTest test`
  - 成功判定：43/50 → Wilson 95% CI ≈ [73.8, 93.1]；10 例单向翻转 → McNemar p≈0.002；同批题重复采样 → bootstrap CI 收敛
  - 失败处理：浮点误差允许 ±0.2pp；不得用正态近似替代 Wilson

- [x] task_0008 旧报告按 CI 重述
  - depends-on: task_0007
  - boundary: 新增 `scripts/eval_restate_reports.py`、新增 `docs/eval/reports/README-v2.md`；不改 7 份归档 JSON
  - verify: `cd /Users/linyixi/PaiSmart && python3 scripts/eval_restate_reports.py --check`
  - 成功判定：脚本从归档重算 Wilson CI 并断言与文档表格一致；"04 回归 / 05 修复"改写为"差 1 例，区间重叠，不可区分"
  - 失败处理：归档字段缺失时输出"该轮无判别口径"而不是编造数字

- [x] task_0009 生成与引用指标
  - depends-on: task_0001, task_0003
  - boundary: 新增 `service/eval/GenerationMetrics.java`（faithfulness、citation precision/recall、拒答正确率）、`RagEvalCaseRunner.java`、`RagEvalService.java`；数据集补 ≥3 条负向权限用例；新增 `GenerationMetricsTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=GenerationMetricsTest test`
  - 成功判定：无据 claim 计入 unfaithful；引用指向错误 chunk 时 citation precision 下降；应拒答题拒答则 refusal=1
  - 失败处理：claim 切分依赖模型时，测试用固定 claim 列表注入，避免联网

- [x] task_0010 基于 10 篇真实语料重建题集
  - depends-on: task_0005
  - boundary: 新增 `src/main/resources/eval/eval-v2.jsonl`（≥120 题，dev 90 / test 30，负样本 ≥15，≥8 篇文档被覆盖）、`docs/eval/reports/README-v2.md` 的语料说明；使用 `eval-datasets/corpus/` 下 10 篇 PDF（清单 `eval-datasets/manifest.json`）；PDF 不入库；新增 `EvalDatasetIntegrityTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalDatasetIntegrityTest test`
  - 成功判定：题量 ≥120、split 计数正确、id 唯一、每篇文档 ≥6 题、07 的题不超过 10 条且标注 `ocr=true`、负样本 ≥15、每题都有 `expectedEvidence`
  - 失败处理：若 07 无法解析（OCR 未配置），把它的题标 `blocked` 但不计入分母；其余 9 篇必须满足覆盖要求

---

**当前进度**：由脚本计算，无需手动维护

## 轮内修正（复审缺口当轮修复，2026-11-09）
- [x] retrievedText 接入真实执行链路（Draft.build 第 24 参不再硬编码 null，recordRetrieval 收集片段正文）
  - id: task-followup-retrievedtext
  - boundary: 只改 RagEvalCaseRunner（复用既有 RetrievedText record 与 null 参数位）
  - verify: manual: 复审发现①闭环（mvn compile+全量 452/452；真实归档 case[0].retrievedText 非 null 由下次真实评测自然覆盖——fixture 层已有非空断言）
- [x] 评委身份落盘（configSnapshot 新增 judge 键：SELF_JUDGE 或 provider[/model]）
  - id: task-followup-judge
  - boundary: 只改 RagEvalService.configSnapshot（复用既有 judgeLlmDescription）
  - verify: manual: 复审发现②闭环（grep judge 于 configSnapshot 命中；测试 452/452）
- [x] legacyDataset 接线（parse 改走 parseWithDiagnostics，诊断写实例字段并进 configSnapshot + warn 日志）
  - id: task-followup-legacy
  - boundary: 只改 RagEvalService.parse 与 configSnapshot
  - verify: manual: 复审发现③闭环（当前捆绑数据集均 v2 无实际影响，路径已通）
