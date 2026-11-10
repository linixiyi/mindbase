# Tasks：评测通电与 RAG 运行时质量收口

格式说明（给执行端）：

- 每张卡按 `boundary → verify → expect` 执行；红基线意味着现在跑 verify 必须失败，改完之后必须绿。
- `boundary` 之外的改动视为越界；确需扩边请在 Decisions 记录并向用户确认。
- 所有 verify 命令都在仓库根执行，已显式 `cd /Users/linyixi/PaiSmart`。
- 新增测试类一律 `new:` 前缀声明，测试必须能在无外部服务时跑（mock/fixture）。
- 涉及 `ChatPipeline` 的三张卡（0006、0007、0010）必须按依赖串行，不得并行改同一文件。

## 任务

- [x] task-0001 评委独立性与 κ 门禁生效
  - id: task-0001
  - boundary: src/main/java/com/mindbase/service/eval/EvalReport.java, src/main/java/com/mindbase/service/eval/EvalReportAggregator.java, src/main/java/com/mindbase/service/eval/RagEvalCaseRunner.java, src/main/resources/application.yml, scripts/eval_compare.py, new:src/test/java/com/mindbase/service/eval/JudgeIndependenceGateTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=JudgeIndependenceGateTest test`
  - expect: red
  - 背景：R2 只把 `minKappa` 加进 properties，但 `application.yml` 的 `EVAL_HUMAN_AGREEMENT_MIN_KAPPA:` 默认空串会覆盖默认值；报告也没有"这份分数能不能用于发布判定"的结论。评委在未配置 `eval.judge.provider` 时与生成同模型，属于自评。
  - 交付：`application.yml` 的 `min-kappa` 默认改为 `0.6`（env 仍可覆盖为空，空表示不启用 κ 检查但不代表可发布）；报告 `config` 增加 `judgeIndependence`（INDEPENDENT 或 SELF_JUDGE）与 `releaseEligible`（布尔），并新增 `releaseBlockedReason`；判定规则：SELF_JUDGE → false；kappa 可计算且低于 minKappa → false；可比样本少于 minComparableCases → false；`scripts/eval_compare.py` 在 `--fail-if` 涉及 accuracy 或 judgeScore 且报告 `releaseEligible=false` 时打印原因并退出 2，新增 `--allow-self-judge` 才放行并打印降级说明。
  - 测试：`JudgeIndependenceGateTest` 用内存构造的报告对象覆盖 self、independent+kappa 0.7、independent+样本不足三种，断言 flags 与阻断原因；脚本部分用 `subprocess` 对临时 fixture 断言退出码 2 与 0。
  - 成功判定：`./mvnw -q -B -Dtest=JudgeIndependenceGateTest test` 绿；self 报告配 `--fail-if accuracy>=0.8` 退出 2，independent 且 kappa 0.7 退出 0。
  - 失败处理：没有人工复核数据时不得伪造 kappa；只按 SELF_JUDGE 判定并在报告里写明"kappa 未计算"。

- [x] task-0002 评测成本与 token 记账
  - id: task-0002
  - boundary: src/main/java/com/mindbase/service/eval/EvalReport.java, src/main/java/com/mindbase/service/eval/EvalCaseResult.java, src/main/java/com/mindbase/service/eval/RagEvalCaseRunner.java, src/main/java/com/mindbase/service/eval/RagEvalService.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/service/eval/EvalCostAccountingTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalCostAccountingTest test`
  - expect: red
  - depends-on: task-0001
  - 背景：`RagEvalService.java:303` 固定写 `EvalReport.Cost.unpriced()`，报告没有任何金额；生成侧已经有 token 用量对象，但逐例没合并，评委调用的 token 也没记录。
  - 交付：`RagEvalProperties` 增 `pricing`（`inputPer1k`、`outputPer1k`、`currency`，默认 null）；`RagEvalCaseRunner` 把评委返回的 usage 与生成 usage 合并写入 case 的 `tokenUsage`；`EvalReport.Cost` 由 `promptTokens/1000*inputPer1k + completionTokens/1000*outputPer1k` 计算，四舍五入到 6 位；未配价时保持 null 并在 `note` 写 `unpriced: set eval.pricing.*`；报告 `config` 记录单价与来源。
  - 测试：`EvalCostAccountingTest` 覆盖配价 1/2、1000 prompt + 500 completion → 2.0；未配价 → null；评委 usage 缺失 → 只计生成侧且 note 标 partial。
  - 成功判定：测试绿；金额与手算一致。
  - 失败处理：评委 usage 拿不到时只计生成侧并在 note 标 partial，禁止用字符数估算 token。

- [x] task-0003 gold 文件级 md5 与分块级 id
  - id: task-0003
  - boundary: src/main/java/com/mindbase/service/eval/EvalCase.java, src/main/java/com/mindbase/service/eval/EvalDatasetParser.java, src/main/java/com/mindbase/service/eval/RetrievalMetrics.java, src/main/resources/eval/eval-v2.jsonl, eval-datasets/manifest.json, new:scripts/eval_backfill_gold.py, new:src/test/java/com/mindbase/service/eval/EvalGoldFieldsTest.java
  - verify: `cd /Users/linyixi/PaiSmart && python3 scripts/eval_backfill_gold.py --check && ./mvnw -q -B -Dtest=EvalGoldFieldsTest test`
  - expect: red
  - 背景：`eval-v2.jsonl` 120 条可执行题里 `expectedFileMd5s` 一条都没有，`RetrievalMetrics.java:158` 仍用短语 anyMatch 判覆盖，且同文件名下的任意分块都算命中；manifest 已有 10 篇 PDF 的 md5。
  - 交付：`EvalCase` 增 `expectedChunkIds`（可空列表）；`EvalDatasetParser` 接受 `expectedFileMd5s` 与 `expectedChunkIds`，长度与 `expectedFileNames` 不一致时 warn 但不崩；新脚本 `scripts/eval_backfill_gold.py` 读数据集与 manifest，按 `expectedFileNames` 补 `expectedFileMd5s`，`--check` 幂等（已补则只校验），`--write` 落盘，缺映射打印缺失文件名并非 0；`RetrievalMetrics` 在有 chunkId 标注时按 chunkId 判命中，未标注或未索引回退短语判定并在 case 标 `goldFallback=phrase`。
  - 测试：`EvalGoldFieldsTest` 断言 md5/chunkId 解析、回填后覆盖率 100%、chunkId 优先于短语。
  - 成功判定：`--check` 退出 0 且测试绿。
  - 失败处理：manifest 缺 md5 的文档写 null 并在 `--check` 报出，不得猜测或用文件名哈希顶替。

- [x] task-0004 真机冒烟门禁
  - id: task-0004
  - boundary: scripts/eval_gate.sh, new:scripts/eval_real_smoke.sh, new:docs/eval/real-smoke.md
  - verify: `cd /Users/linyixi/PaiSmart && bash scripts/eval_real_smoke.sh --dry-run`
  - expect: red
  - depends-on: task-0003
  - 背景：`scripts/eval_gate.sh` 只跑 `EvalSmokeFixtureTest` 桩数据，真实检索退化抓不到；仓库里没有任何一条命令能对真机发起一次评测。
  - 交付：执行前先读同目录 `runbook.md`（服务启动顺序、管理员 token 获取、评测端点、常见失败）。脚本支持 `--dry-run`（默认）与真跑两种模式。dry-run 只做只读静态检查：curl 存在、`EVAL_BASE_URL` 可缺省为 `http://localhost:8081`、数据集与报告目录可读写、`/actuator/health` 软检查（不可达只 warn），**不要求 token**；静态项缺失时退出 2 并打印缺哪个。真跑模式按 `EVAL_ADMIN_TOKEN` → `EVAL_ADMIN_USERNAME`+`EVAL_ADMIN_PASSWORD` → 仓库根 `.env` 的 `ADMIN_BOOTSTRAP_USERNAME`+`ADMIN_BOOTSTRAP_PASSWORD` 顺序自动取管理员 token（调 `POST /api/v1/users/login`，读 `data.token`），都拿不到才退出 2 并指向 runbook；提交 `POST /api/v1/admin/eval/run`，body 为 `{"datasetPath":"eval-v2.jsonl","topK":5,"label":"real-smoke-<ts>"}`，轮询 `GET /api/v1/admin/eval/runs/{id}` 到 `COMPLETED/FAILED/CANCELLED`，取 `/report` 断言 `retrievalErrorRate<=0.1`、`fullCoverageHitRate>=0.5`（可用 `EVAL_MAX_ERROR_RATE`、`EVAL_MIN_COVERAGE` 覆盖），不满足退出 1。`eval_gate.sh` 末尾追加一行提示真机命令，默认仍跑离线门禁。`docs/eval/real-smoke.md` 写前置条件、环境变量与失败排查（可链接 runbook）。
  - 成功判定：`--dry-run` 在无服务、无 token 环境下退出 0；真跑缺凭据时退出 2 并指明缺哪个变量。
  - 失败处理：本机没有 ES/MinIO 时不得让 dry-run 失败；真跑 401 时重新登录一次再重试，仍失败则退出并指向 runbook，不吞异常。

- [x] task-0005 阈值曲线脚本
  - id: task-0005
  - boundary: new:scripts/eval_threshold_curve.py, scripts/eval_compare.py
  - verify: `cd /Users/linyixi/PaiSmart && python3 scripts/eval_threshold_curve.py --self-test`
  - expect: red
  - depends-on: task-0002, task-0003
  - 背景：v2 报告逐例记了 `retrievalMode` 和分块 `score`，但没有工具回答"0.1 的命中里有多少是 0.2 会塌掉的"。v1 已知 0.2→0.1 有 13 题从空召回变命中。
  - 交付：新脚本读报告 `cases[].retrieved[].score` 与 `relevant`，对 0.05 到 0.35 的阈值网格输出 markdown 表：chunkR@5、全命中率、空召回数、平均命中数、每档相对上一档翻转题数；`--self-test` 内置 fixture 断言 0.1→0.2 的 13 题塌陷与翻转计数；报告缺 score 时输出"该报告无分数字段"并退出 2。与 `eval_compare.py` 共用最小解析函数，不引第三方库。
  - 成功判定：`--self-test` 退出 0。
  - 失败处理：旧 v1 报告没有逐例分数时明确报错，不做兼容猜测。

- [x] task-0006 ReAct 上下文预算与引用去重
  - id: task-0006
  - boundary: src/main/java/com/mindbase/service/ChatPipeline.java, src/main/java/com/mindbase/service/LlmProviderRouter.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/service/ChatContextBudgetTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=ChatContextBudgetTest test`
  - expect: red
  - 背景：`LlmProviderRouter.estimateObjectMessagesTokens` 只用于计费；`ChatPipeline` 在 4 轮 ReAct 里把每次工具结果都追加进消息，引用编号按现有 max+1 递增，同一 `(fileMd5, chunkId)` 会被重复编号，父块也会重复入 prompt。
  - 交付：新增 `chat.react.max-context-tokens` 默认 6000；工具结果拼入消息前用 `estimateObjectMessagesTokens` 估算并裁剪，保持顺序：首条命中始终保留，其余按分数从低到高丢弃；裁剪数量写入 `reactTrace.droppedChunks`；引用编号按 `(fileMd5, chunkId)` 去重，重复分块复用首个编号。
  - 测试：`ChatContextBudgetTest` 构造超预算片段断言总估算 ≤ 预算且首条保留；同一分块出现两次断言编号数=1。
  - 成功判定：测试绿。
  - 失败处理：估算返回 0 或抛异常时退回字符数上限（tokens×4）并记 warn，不得静默放行。

- [x] task-0007 引用校验语义
  - id: task-0007
  - boundary: src/main/java/com/mindbase/service/citation/CitationVerificationService.java, src/main/java/com/mindbase/service/ChatPipeline.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/service/citation/CitationVerificationSemanticsTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=CitationVerificationSemanticsTest test`
  - expect: red
  - depends-on: task-0006
  - 背景：`CitationVerificationService.java:65-88` 在 claims 超过 `max-claims` 时静默丢弃多余部分；整体校验失败返回空 Map，下游无法区分"全部通过"和"没校验"；summary 通路不产出 `来源#N`，却仍走同一返回。
  - 交付：新增状态枚举（PASSED/TRUNCATED/UNVERIFIED/NO_CITATIONS）；超上限时返回 TRUNCATED 且保留已校验部分；整体异常返回 UNVERIFIED 而不是空 Map；回答不含 `来源#N` 时返回 NO_CITATIONS；`citation-verification.enabled` 默认改 true，失败不阻断回答且 1 秒超时降级为 UNVERIFIED；状态写入回答元数据并打日志。
  - 测试：`CitationVerificationSemanticsTest` 覆盖 13 claims 对 max 12 的 TRUNCATED、客户端抛异常的 UNVERIFIED、无引用的 NO_CITATIONS。
  - 成功判定：测试绿。
  - 失败处理：默认开启若让现有测试超时，保留"生产默认开、评测默认开"并在 Decisions 记录，不得退回默认关闭。

- [x] task-0008 分块重叠不进正文
  - id: task-0008
  - boundary: src/main/java/com/mindbase/service/ParseService.java, src/main/java/com/mindbase/service/chunk/ChunkTextMerger.java, src/main/java/com/mindbase/service/search/ParentContextExpander.java, new:src/test/java/com/mindbase/service/ChunkOverlapMetadataTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=ChunkOverlapMetadataTest test`
  - expect: red
  - 背景：`ParseService.java:596-605 addSemanticOverlap` 把上一块至多 100 字以双换行前置进本块 `textContent`，导致 BM25 词频虚高、embedding 被稀释；`ChunkTextMerger` 靠"12 字相同"启发式去重父块。
  - 交付：`addSemanticOverlap` 不再改写 `textContent`，只记录重叠长度到分块元数据（没有字段就先写 ES 文档字段与日志，不加 DB 列）；邻块上下文统一由 `ParentContextExpander` 的 `neighbour-chunks` 负责；`ChunkTextMerger` 去掉 12 字启发式，按 `chunkIndex` 邻接合并。
  - 测试：`ChunkOverlapMetadataTest` 断言相邻块正文互不包含、父块扩展仍能取到邻块、合并结果与旧版对同一 fixture 等价。
  - 成功判定：测试绿。
  - 失败处理：若 ES 文档结构没有元数据位，允许只做"正文不重叠 + 邻接合并"，在报告里记录 overlap 信息丢失。

- [x] task-0009 Kafka 白名单与取文件收口
  - id: task-0009
  - boundary: src/main/resources/application.yml, src/main/java/com/mindbase/config/KafkaConfig.java, src/main/java/com/mindbase/consumer/FileProcessingConsumer.java, new:src/test/java/com/mindbase/consumer/KafkaTrustAndFetchGuardTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=KafkaTrustAndFetchGuardTest test`
  - expect: red
  - 背景：`application.yml` 的 `spring.json.trusted.packages: "*"`；`FileProcessingConsumer` 用任务里的 `filePath` 直接 `new URL(...).openConnection()`，任意消息可驱动服务端访问内网地址。
  - 交付：白名单收窄到 `com.mindbase.model`，确需时只加 `java.util`、`java.lang` 这类最小集；下载分块只允许 `http/https` 且 host 命中新增配置 `mindbase.storage.allowed-hosts`（默认从 `minio.endpoint` 派生），其他 scheme/host 直接失败并写任务 error，不发起连接。
  - 测试：`KafkaTrustAndFetchGuardTest` 断言 yml 不含通配符、`file://` 与 `169.254.169.254` 与外部 host 被拒、MinIO host 放行。
  - 成功判定：测试绿。
  - 失败处理：历史消息类型不在白名单导致反序列化失败时，把该类包补进白名单并在测试固化清单，不得回退 `*`。

- [x] task-0010 生产与评测共用 ChatPipeline 装配
  - id: task-0010
  - boundary: src/main/java/com/mindbase/service/ChatPipeline.java, src/main/java/com/mindbase/service/ChatHandler.java, src/main/java/com/mindbase/service/eval/RagEvalCaseRunner.java, new:src/test/java/com/mindbase/service/ChatPipelineParityTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=ChatPipelineParityTest test`
  - expect: red
  - depends-on: task-0006, task-0007
  - 背景：`RagEvalCaseRunner.java:130` 用 `new ChatPipeline(null, hybridSearchService, llmProviderRouter, null, null, null, null, agentToolRegistry, null, objectMapper, null, new ChatDeadlineProperties())` 造 headless 实例，生产走 Spring 注入；两条链路的工具集、轮数、提示词只靠人肉保持一致。
  - 交付：抽 `ChatPipelineFactory` 统一装配工具注册表、最大轮数、提示词、模型路由、截止时间；`ChatHandler` 与 `RagEvalCaseRunner` 都改用它，去掉 null 占位；构造参数确实过多时保留一个 `@Deprecated` 重载，但生产和评测不得再走它。
  - 测试：`ChatPipelineParityTest` 断言两条链路拿到的 maxRounds、工具名集合、模型 provider、system prompt 哈希一致，且 headless 构造不依赖 WebSocketSession。
  - 成功判定：测试绿。
  - 失败处理：若发现两边本来就不同（例如评测故意关引用校验），把差异显式建模为 factory 参数并在测试固化，不允许用 null 隐式表达。

## 执行顺序

```
task-0001 → task-0002
task-0003 → task-0004
task-0002, task-0003 → task-0005
task-0006 → task-0007 → task-0010
task-0008, task-0009 可并行
```
# Tasks 追加（2026-11-11）

格式与 `tasks.md` 一致：boundary → verify → expect；boundary 之外不改；verify 当前必须红。
本文件由 `plan-amend` 追加，任务编号从 task-0011 起。

## 任务

- [ ] task-0011 评委双采样与一致性判定
  - id: task-0011
  - boundary: src/main/java/com/mindbase/service/eval/RagEvalCaseRunner.java, src/main/java/com/mindbase/config/RagEvalProperties.java, src/main/java/com/mindbase/service/eval/EvalReport.java, src/main/java/com/mindbase/service/eval/EvalReportAggregator.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/service/eval/JudgeRepeatConsistencyTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=JudgeRepeatConsistencyTest test`
  - expect: red
  - depends-on: task-0001, task-0002
  - 背景：评委只调一次，同一模型既生成又打分；历史上 17/50 例分数跨轮抖动，但 correct 是否稳定没有度量。单答案评测不存在 A/B 位置偏差，真正要测的是评委自身重复性。
  - 交付：`RagEvalProperties.Judge` 增 `repeat`（默认 true）。`RagEvalCaseRunner.judge` 在 repeat 时调用两次（同 prompt、同模型）。合并规则：两次都 parseFailed → failed；一次失败 → `judgeCorrect=null`、`judgeScore=null`、`needsHumanReview=true`、`reviewReason=judge-unstable`；两次 correct 不同 → 同样置空并 `reviewReason=judge-disagree`，`judgeReason` 写 `A=<score>/<correct>; B=<score>/<correct>`；两次一致 → 取分数均值（1 位小数）与一致结论。`EvalSummary` 增 `judgeRepeatEnabled`、`judgeAgreementCases`、`judgeDisagreementCases`、`judgeAgreementRate`（分母为两次都成功解析的例数）；`application.yml` 增 `eval.judge.repeat: \${EVAL_JUDGE_REPEAT:true}`。
  - 测试：`JudgeRepeatConsistencyTest` mock 两次返回一致 / 结论相反 / 一次解析失败三种，断言 case 字段、needsHumanReview 与 summary 三个计数。
  - 成功判定：测试绿；repeat=false 时报告标 `judgeRepeatEnabled=false` 且不产生一致性数字。
  - 失败处理：不得把两次失败当一致；不得用空一致性冒充稳定。

- [ ] task-0012 评测数据集审计脚本
  - id: task-0012
  - boundary: new:scripts/eval_dataset_audit.py
  - verify: `cd /Users/linyixi/PaiSmart && python3 -B scripts/eval_dataset_audit.py --self-test`
  - expect: red
  - depends-on: task-0003
  - 背景：126 题从未做结构审计；重复题、空 evidence、未知文件名、md5 缺口只能等运行到那一题才炸。
  - 交付：脚本支持 `--check`（默认）与 `--self-test`；读 `src/main/resources/eval/eval-v2.jsonl` 与 `eval-datasets/manifest.json`，检查：id 与问题（NFKC 归一化去空白后）重复、非 neg 题 expectedEvidence 为空、expectedFileNames 为空或不在 manifest、expectedFileMd5s 缺失、split 不在 {dev,test}、type 不在已知集合、非 neg 题 answer 为空、neg 题带 evidence。输出按 error/warn 分级的 markdown 表与计数；error>0 退出 1，仅 warn 退出 0。`--self-test` 用内置 clean/dirty 两份 fixture 断言 findings 数量与退出码。
  - 测试：脚本内 fixture，不依赖外部服务。
  - 成功判定：`--self-test` 退出 0；对当前数据集 `--check` 能跑通（允许 warn）。
  - 失败处理：manifest 缺条目时报出而不是跳过。

- [ ] task-0013 人工复核导出/回填 CLI
  - id: task-0013
  - boundary: new:scripts/eval_human_review.py, new:docs/eval/human-review.md
  - verify: `cd /Users/linyixi/PaiSmart && python3 -B scripts/eval_human_review.py --self-test`
  - expect: red
  - depends-on: task-0001
  - 背景：人工复核只有 Java 端点与前端，命令行无法批量导出/回填，κ 校准一直缺样本。
  - 交付：三个子命令：`export --report <json> --out <json>` 过滤 needsHumanReview，写出 `{runId, cases:[{caseId, question, answer, judgeScore, judgeReason, verdict:null, note:""}]}`；`import --run <id> --file <json> --reviewer <name>` 复用 runbook 的 token 三级顺序，POST `/api/v1/admin/eval/runs/{id}/human-reviews`，字段以 `EvalHumanReviewService.HumanReviewInput` 为准（实现前先读该类）；`agreement --report <json> [--min-kappa 0.6]` 打印 humanAgreement，低于阈值退出 1。`--self-test` 用内置报告 fixture 断言导出条数、payload 形状与 kappa 解析，不发网络请求。`docs/eval/human-review.md` 写流程、字段与 κ 判定。
  - 成功判定：`--self-test` 退出 0。
  - 失败处理：报告缺 humanAgreement 时 agreement 打印“未计算”退出 2，不伪造数字。

- [ ] task-0014 RRF 分路权重
  - id: task-0014
  - boundary: src/main/java/com/mindbase/service/search/RrfFusion.java, src/main/java/com/mindbase/config/HybridSearchProperties.java, src/main/java/com/mindbase/service/HybridSearchService.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/service/search/RrfWeightedFusionTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=RrfWeightedFusionTest test`
  - expect: red
  - 背景：RRF 两路等权，BM25 与 KNN 质量不同时无法调；两路分数不可比，但名次贡献可以加权。
  - 交付：新增 `fuse(double[] weights, int k, Function<T,String> keyFn, List<T>... lists)`：weights 长度必须等于 lists 长度、每项 >=0 且有限、和 >0，否则 IllegalArgumentException；score += weights[i]/(k+rank)。原 `fuse(int k, ...)` 保留并委托等权（V6 不破坏调用方）。`HybridSearchProperties` 增 `bm25Weight=1.0`、`knnWeight=1.0`；`HybridSearchService` 改调新重载；`application.yml` 增 `search.hybrid.bm25-weight` 与 `search.hybrid.knn-weight`（env 默认 1.0）。
  - 测试：`RrfWeightedFusionTest` 断言等权结果与旧实现逐条一致、权重 0 时该路不贡献、2.0 权重能翻转构造用例顺序、长度不符/负权重抛异常。
  - 成功判定：测试绿。
  - 失败处理：不改变 RRF 的 k 语义与同分稳定排序。

- [ ] task-0015 RerankClient WebClient 复用
  - id: task-0015
  - boundary: src/main/java/com/mindbase/client/RerankClient.java, new:src/test/java/com/mindbase/client/RerankClientReuseTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=RerankClientReuseTest test`
  - expect: red
  - 背景：`RerankClient.java:91` 与 `:151` 每次请求都 `WebClient.builder()...build()`，连接池与线程资源无法复用，rerank 是每请求热路径。
  - 交付：新增 package-private `WebClient webClient()`，volatile + 双重检查锁懒加载单例；`testConnection` 与 `rerank` 都改用它；超时与异常语义保持不变。
  - 测试：`RerankClientReuseTest` 用 mock 依赖构造实例，断言 `assertSame(client.webClient(), client.webClient())`，并断言两次取到的不是 null。
  - 成功判定：测试绿。
  - 失败处理：不引入新依赖，不改 rerank 请求/响应结构。

- [ ] task-0016 JWT 日志脱敏
  - id: task-0016
  - boundary: src/main/java/com/mindbase/utils/JwtUtils.java, new:src/main/java/com/mindbase/utils/TokenRedactor.java, new:src/test/java/com/mindbase/utils/JwtLogRedactionTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=JwtLogRedactionTest test`
  - expect: red
  - 背景：`JwtUtils.java:150,163,176,189,202` 五处 ERROR 日志把完整 token 写进日志，泄露即可重放。
  - 交付：新增 `TokenRedactor.fingerprint(String)`：null/blank 返回 `empty`；否则 `sha256:` + SHA-256 前 8 位十六进制 + `…len=` + 长度；JwtUtils 五处 `logger.error(... token ...)` 全部替换为 fingerprint，异常对象保留。
  - 测试：Logback `ListAppender` 挂到 JwtUtils logger；用 ReflectionTestUtils 注入 base64 密钥，用非法 token 依次触发 5 个 extract 方法，断言日志不含原文、含 `sha256:`；null 断言 `empty`。
  - 成功判定：测试绿。
  - 失败处理：不得为了脱敏吞掉异常类型与堆栈。

- [ ] task-0017 清库密钥外部化
  - id: task-0017
  - boundary: src/main/java/com/mindbase/controller/AdminController.java, new:src/main/java/com/mindbase/security/AdminKeyVerifier.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/security/AdminKeyVerifierTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=AdminKeyVerifierTest test && ! grep -q 'CLEAR_ALL_2024' src/main/java/com/mindbase/controller/AdminController.java`
  - expect: red
  - 背景：`AdminController.java:1179` 硬编码 `"CLEAR_ALL_2024"`，公开仓库里等于没有密钥。
  - 交付：新增 `AdminKeyVerifier.matches(configured, provided)`：任一 blank → false；否则 `MessageDigest.isEqual` 常量时间比较。`AdminController` 用 `@Value("\${admin.clear-all-key:}")` 注入，配置为空返回 503、密钥不符返回 403，日志只记“未配置/密钥不符”，不回显密钥；`application.yml` 增 `admin.clear-all-key: \${ADMIN_CLEAR_ALL_KEY:}`；删除硬编码字面量。
  - 测试：`AdminKeyVerifierTest` 覆盖 null/blank/相等/不等；verify 用 grep 确认源码无旧字面量。
  - 成功判定：测试绿且 grep 无命中。
  - 失败处理：空配置时拒绝执行，不得回退到默认密钥。

- [ ] task-0018 上传文件签名校验
  - id: task-0018
  - boundary: src/main/java/com/mindbase/controller/UploadController.java, new:src/main/java/com/mindbase/service/FileSignatureValidator.java, new:src/test/java/com/mindbase/service/FileSignatureValidatorTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=FileSignatureValidatorTest test`
  - expect: red
  - 背景：只按扩展名放行（`UploadController.java:90-92`），改扩展名即可把任意二进制送进 Tika/OCR 链路。
  - 交付：新增 `FileSignatureValidator.validate(String fileName, byte[] head)`（head 至少 8 字节才校验签名）：pdf `%PDF-`；docx/xlsx/pptx/odt/ods/odp/epub `PK\x03\x04`；doc/xls/ppt OLE `D0 CF 11 E0 A1 B1 1A E1`；rtf `{\\rtf`；txt/md/csv/json/html/htm/xml 不做二进制签名但拒绝含 NUL 字节；不匹配抛 `CustomException(..., HttpStatus.BAD_REQUEST)`，消息含期望类型与扩展名，不含文件内容。`UploadController` 在 chunkIndex==0 且扩展名校验通过后，用 `file.getInputStream().readNBytes(8)` 调用；后续分片不重复校验。
  - 测试：`FileSignatureValidatorTest` 覆盖合法/非法 pdf、docx、doc、rtf、短文件、txt 含 NUL 共 7 组。
  - 成功判定：测试绿；合法 PDF 上传路径不被误杀。
  - 失败处理：读不到字节时按扩展名放行但记 warn，不得 500。

- [ ] task-0019 客户端 IP 信任策略
  - id: task-0019
  - boundary: new:src/main/java/com/mindbase/security/ClientIpResolver.java, src/main/java/com/mindbase/utils/HttpRequestUtil.java, src/main/java/com/mindbase/controller/UserController.java, src/main/resources/application.yml, new:src/test/java/com/mindbase/security/ClientIpResolverTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=ClientIpResolverTest test`
  - expect: red
  - 背景：`HttpRequestUtil.java:54-78` 与 `UserController.java:164` 两处重复实现无条件信任 CF-Connecting-IP / XFF，攻击者可伪造头绕过登录限流。
  - 交付：新增 `ClientIpResolver`（@Component，`security.trusted-proxies` 逗号分隔 CIDR，默认空）：remoteAddr 不受信 → 直接返回 remoteAddr；受信 → 从 XFF 右向左跳过受信代理取第一个不受信地址；无 XFF 时才看 X-Real-IP/CF-Connecting-IP。支持 IPv4 CIDR 与 IPv6 精确匹配，不支持的形式记 warn 并跳过。`HttpRequestUtil.resolveClientIp` 委托同一实现（供 WxPay 使用）；`UserController` 删除私有副本并注入 resolver；`application.yml` 增 `security.trusted-proxies: \${SECURITY_TRUSTED_PROXIES:}`。
  - 测试：`ClientIpResolverTest` 覆盖未受信 remote + 伪造 CF/XFF → remoteAddr；受信代理 + `client, proxy` → client；全受信链 → 最左；非法头回落 remoteAddr。
  - 成功判定：测试绿。
  - 失败处理：解析失败一律回落 remoteAddr，不得返回攻击者可控值。

- [ ] task-0020 优雅停机配置
  - id: task-0020
  - boundary: src/main/resources/application.yml, new:src/test/java/com/mindbase/config/GracefulShutdownConfigTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=GracefulShutdownConfigTest test`
  - expect: red
  - 背景：没有 `server.shutdown`，SIGTERM 时 Kafka 消费、WS 与生成态被硬切，滚动发布必掉请求。
  - 交付：`application.yml` 增 `server.shutdown: graceful` 与 `spring.lifecycle.timeout-per-shutdown-phase: 30s`；若已有 server 段则合并，不新建重复键。
  - 测试：`GracefulShutdownConfigTest` 用 SnakeYAML 读 classpath `application.yml`，断言两值以及 `spring.lifecycle` 未覆盖其他键。
  - 成功判定：测试绿。
  - 失败处理：不改 profile 文件；不引入新依赖。

- [ ] task-0021 ES mapping 显式 public 字段与审计脚本
  - id: task-0021
  - boundary: src/main/resources/es-mappings/knowledge_base.json, new:scripts/es_mapping_audit.py
  - verify: `cd /Users/linyixi/PaiSmart && python3 -B scripts/es_mapping_audit.py --self-test`
  - expect: red
  - 背景：mapping 声明 `isPublic`（`knowledge_base.json:42`），检索过滤用 `public`（`HybridSearchService.java:271`），现在靠动态 mapping 才不炸；换集群/重建索引可能过滤失效或报错。
  - 交付：mapping 增显式 `"public": {"type":"boolean"}`（保留 `isPublic` 兼容旧数据）；`scripts/es_mapping_audit.py` 内置必需字段清单（以 HybridSearchService 查询与 EsDocument 实际字段为准，实现前 grep 确认，不猜），`--self-test` 用两份 fixture（好/缺 public）断言 findings；`--url` 真查 `GET {url}/{index}/_mapping`，缺字段退出 1；无 --url 时只跑 self-test。
  - 成功判定：`--self-test` 退出 0。
  - 失败处理：ES 不可达时只报“未连接”，不把网络失败当字段缺失。

- [ ] task-0022 OCR 可用性预检
  - id: task-0022
  - boundary: new:scripts/eval_ocr_preflight.py, new:docs/eval/ocr-preflight.md
  - verify: `cd /Users/linyixi/PaiSmart && python3 -B scripts/eval_ocr_preflight.py --self-test`
  - expect: red
  - 背景：07 的 6 题因 OCR 未配置被标 blocked 且不计入任何分母，跑评测的人直到看报告才知道。
  - 交付：脚本检查两条路径：阿里云（`ALIYUN_OCR_ENABLED=true` 且 ACCESS_KEY_ID/SECRET 非空）、本地 LiteParse（`FILE_PARSING_LITEPARSE_OCR_ENABLED=true` 且 `tesseract` 在 PATH）。输出 JSON 与 markdown 表：可用引擎、缺失项、受影响题数（读 eval-v2.jsonl 统计 type=ocr）、建议命令；`--check` 无引擎时退出 0 但标 blocked，`--require-ocr` 退出 2。`--self-test` 用注入的 env/which 桩断言 3 种组合。文档写清两套配置与 blocked 题为何不能进分母。
  - 成功判定：`--self-test` 退出 0。
  - 失败处理：不联网探测阿里云，只看配置与本地二进制。
  - origin: emergent

- [ ] task-0023 旧分块重叠断言迁移新语义
  - id: task-0023
  - boundary: src/test/java/com/mindbase/service/ParseServiceUnitTest.java
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=ParseServiceUnitTest test`
  - expect: red
  - depends-on: task-0008
  - 背景：task-0008 移除 addSemanticOverlap 对 textContent 的改写后，既有用例 testSplitTextIntoChunksWithSemantics_AddsSentenceOverlap 仍断言本块以重叠前缀开头（ParseServiceUnitTest.java:173），与新语义互斥；不改则终树全量门禁必红。
  - 交付：把该用例的断言更新为新语义（正文不带重叠前缀、正文等于纯本块内容），不删除测试、不改同文件其他用例、不改生产代码。
  - 测试：ParseServiceUnitTest 自身。
  - 成功判定：ParseServiceUnitTest 全绿。
  - 失败处理：同文件其他用例若也依赖旧重叠语义，同样迁移断言并记录，不得删测试。
  - origin: emergent
