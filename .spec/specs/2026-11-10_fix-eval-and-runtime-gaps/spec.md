# 评测通电与 RAG 运行时质量收口

<!-- spec-format: 3 -->

## Goal
- goal: 让离线评测从"只能跑 mock 门禁"变成"能在真实环境跑出可复核结论"，并修掉审计确认的 RAG 运行时质量缺陷与两个高危安全面。
- literal ask: "把这些要做的写进相关文件中，我会在 zcode 中调用 /spec 来做"
- done means: 10 个任务全部勾选且最终树 verify 通过；`./mvnw -q -B test` 与 `bash scripts/eval_gate.sh` 全绿；报告能读出评委独立性、成本与阈值曲线，ReAct 上下文预算与引用语义有测试守护。

## Context
本轮输入是 2026-11-10 对 R1/R2 交付的复审结论（`.runtime/research/01-rag-backend.md`、`04-eval-existing.md` 与本轮全量测试）。已确认的缺口按优先级：

1. 评测从没在真机上跑过：`docs/eval/reports/` 7 份全是 v1，`eval-reports/` 为空；126 题只过 `EvalDatasetIntegrityTest` 结构门禁。`scripts/eval_gate.sh` 跑的是 `EvalSmokeFixtureTest` 桩数据。
2. 评委默认自评：`eval.judge.provider` 未配置时评委与生成同模型；`application.yml` 的 `EVAL_HUMAN_AGREEMENT_MIN_KAPPA:` 默认为空，覆盖掉 `RagEvalProperties.minKappa=0.6`，κ 门禁不生效。
3. 成本字段永远 `unpriced`：`RagEvalService.java:303` 固定传 `EvalReport.Cost.unpriced()`；token 用量已存在但没折算费用。
4. gold 没有文件 md5 与分块 id：`eval-v2.jsonl` 只有 `expectedFileNames`+`expectedEvidence`；`RetrievalMetrics` 仍按短语 anyMatch 判覆盖。
5. 阈值证据缺失：`score-threshold` 默认 0.1 是在同一批 50 题上调出来的；报告逐例已记 `retrievalMode` 与分块 `score`，但没有曲线脚本，无法判断命中是不是过滤伪影。
6. ReAct 上下文无预算：`LlmProviderRouter.estimateObjectMessagesTokens` 只用于计费；引用编号按 max+1 追加，不按 `fileMd5+chunkId` 去重，同一分块会拿到多个编号。
7. 引用校验语义不完整：`citation-verification` 默认关闭；超过 `max-claims` 静默丢弃；整体失败返回空 Map，没有 UNVERIFIED 标记；summary 通路不产出引用。
8. 分块重叠写进正文：`ParseService.addSemanticOverlap` 把上一块至多 100 字前置进本块 `textContent`，抬高 BM25 词频、稀释 embedding。
9. 安全面：`spring.json.trusted.packages: "*"`；`FileProcessingConsumer` 对任务里的 filePath 直接 `new URL(...).openConnection()`，构成 SSRF。
10. 契约缺口：生产 WS 链路与评测 headless 链路靠 `new ChatPipeline(null, ...)` 的 null 占位区分，没有契约测试，重构必然踩坏一边。

## Charter
- V1 priority: quality > maintainability > speed > cost | charter.md
- V2 scope: adjacent-fixes | charter.md
- V3 verification: package | charter.md
- V4 finish: merge | charter.md
- V5 dependencies: none-new | charter.md
- V6 compatibility: keep-public-api | charter.md
- V7 blocked: park-and-continue | charter.md

## Scope
- in: src/main/java/com/mindbase/**, src/main/resources/**, src/test/java/com/mindbase/**, scripts/**, docs/eval/**, eval-datasets/**
- out: frontend/**, .github/workflows/**, pom.xml, .spec/**, deploy scripts

## Assumptions
- A1 | `eval-v2.jsonl` 当前没有任何 `expectedFileMd5s` 字段 | probe: `grep -c "expectedFileMd5s" src/main/resources/eval/eval-v2.jsonl` | CONFIRMED
- A2 | κ 门禁被空环境变量覆盖 | probe: `grep -n "MIN_KAPPA" src/main/resources/application.yml` | CONFIRMED
- A3 | 成本恒为 unpriced | probe: `grep -n "Cost.unpriced" src/main/java/com/mindbase/service/eval/RagEvalService.java` | CONFIRMED
- A4 | Kafka 反序列化白名单是通配符 | probe: `grep -n "trusted.packages" src/main/resources/application.yml` | CONFIRMED
- A5 | 引用校验默认关闭 | probe: `grep -n "citation-verification" -A4 src/main/resources/application.yml` | CONFIRMED
- A6 | 07 扫描件的 6 题因 OCR 未配置被标 blocked | probe: `grep -c "blocked" docs/eval/reports/README-v2.md` | CONFIRMED
- A7 | 本机没有真实 v2 报告 | probe: `ls eval-reports/*.json 2>/dev/null | wc -l` | CONFIRMED
- A8 | 可用的第二家评委 provider 取决于账号与 key | UNVERIFIED | if false: task-0001 只落 SELF_JUDGE 判定与 release 门禁，独立评委保留配置位
- A9 | 本机没有 tesseract/阿里 OCR 凭据，真机冒烟的可跑范围不含 07 | UNVERIFIED | if false: task-0004 把 07 用例纳入真机冒烟并在报告单列 OCR 覆盖率

## Forks
- F1 | when: 第二家评委 provider 不可用 | a) 保持 SELF_JUDGE 并禁止据此做发布判定 b) 临时用同族评委并人工复核 | pick: a | by: V1 | reversal: reversible
- F2 | when: 引用校验默认开启后单次回答变慢或误杀 | a) 默认开启、失败降级 UNVERIFIED 且 1s 超时跳过 b) 保持默认关闭、只在评测开启 | pick: a | by: V1 | reversal: reversible
- F3 | when: 本机 OCR 环境仍不可用 | a) 07 的 6 题继续标 blocked、不计入分母，真机冒烟只跑其余 9 篇 b) 阻塞整个评测包 | pick: a | by: V3 | reversal: reversible
- F4 | when: Kafka 白名单收紧导致历史消息类型反序列化失败 | a) 把缺失的消息类补进白名单并固化清单，保持最小集 b) 回退通配符 | pick: a | by: V1 | reversal: reversible

## Amendment 2026-11-11
用户要求在不改变 charter 的前提下把夜间执行预算用满（m00718）。追加 task-0011..0022，全部属于 V2 adjacent-fixes 的已确认缺口；边界仍落在既有 Scope.in，不新增依赖、不改前端与 workflow。追加后共 22 张卡，典型 8–14M token，保守 18–22M；3 亿是平台上限而非可达目标，剩余预算只能另开 R4 前端包或重复评测。新增卡与既有卡在 application.yml / EvalReport 上有文件重叠，执行端必须串行。

## Criteria
- C1 | 未配置独立评委时报告标 SELF_JUDGE 且 releaseEligible=false，配置独立评委与 κ≥0.6 时门禁放行 | task-0001
- C2 | 报告 Cost 由可配置单价与真实 token 用量的乘积得出，未配价时保留 null 并说明原因 | task-0002
- C3 | 126 条 gold 全部带文件 md5，解析器接受分块 id，指标优先按分块 id 判定 | task-0003
- C4 | 一条命令完成真机冒烟前置检查，并在有环境变量时能提交 10 题 react 评测并校验阈值 | task-0004
- C5 | 阈值曲线脚本能从报告逐例分数重算 chunkR@5、全命中与空召回，且自带自检 | task-0005
- C6 | ReAct 工具结果按 token 预算裁剪且首条命中保留，同一分块只占一个引用编号 | task-0006
- C7 | 引用校验超过上限返回 TRUNCATED、整体失败返回 UNVERIFIED、无引用返回 NO_CITATIONS | task-0007
- C8 | 相邻分块正文不再互相包含，父块扩展改用邻块窗口，合并结果与旧版一致 | task-0008
- C9 | Kafka 反序列化白名单收窄到项目消息包，消费端只允许受信 scheme/host | task-0009
- C10 | 生产 WS 与评测 headless 共用同一 ChatPipeline 装配，契约测试可断言两边一致 | task-0010
- C11 | 评委双采样一致时给分，不一致时置空并强制人工复核，报告给出重复一致率 | task-0011
- C12 | 数据集审计脚本能发现重复题、空证据、未知文件与 md5 缺口，并自带自检 | task-0012
- C13 | 人工复核可从报告导出、回填到运行记录，并按 κ 阈值判定 | task-0013
- C14 | RRF 支持可配置分路权重且旧调用结果不变 | task-0014
- C15 | RerankClient 复用同一 WebClient，连续调用不重建 | task-0015
- C16 | JWT 解析失败日志只输出指纹，不输出原文 | task-0016
- C17 | 清库密钥来自配置，源码无硬编码，空配置拒绝执行 | task-0017
- C18 | 上传首片按文件签名校验，扩展名伪装被 400 拒绝 | task-0018
- C19 | 仅在受信代理来源时采信转发头，登录限流不可用伪造头绕过 | task-0019
- C20 | 配置优雅停机与 30s 停机超时 | task-0020
- C21 | ES mapping 显式声明检索用权限字段，审计脚本可发现缺字段 | task-0021
- C22 | OCR 预检脚本能报告可用引擎与受影响题数，并可要求 OCR 才通过 | task-0022
- C23 | 旧分块重叠断言迁移到新语义，ParseServiceUnitTest 全绿，终树全量门禁无旧重叠断言失败 | task-0023
- C24 | token 用量断言迁移合并口径，EvalReActPipelineTest 与 EvalSmokeFixtureTest 全绿，全量门禁无旧口径失败 | task-0024

## Decisions
- D1 | 不引入 Python 评测框架与付费 SaaS，指标与统计继续用 Java 自建，Python 只做离线脚本 | by: V1 | reversal: checkpoint revert
- D2 | 本轮不改前端、不改 CI workflow、不新增运行时依赖 | by: V5 | reversal: checkpoint revert
- D3 | task-0008 交付（正文不再带重叠前缀）与既有断言 ParseServiceUnitTest.testSplitTextIntoChunksWithSemantics_AddsSentenceOverlap:173 互斥；授权迁移该单测断言到新语义，扩边由 emergent task-0023 承接该文件 | by: V2 | reversal: checkpoint revert
- D4 | task-0002 交付（case 级 tokenUsage 改为生成 + 评委合并口径，EvalCaseResult.TokenUsage.merged + RagEvalCaseRunner 合并写入）与既有断言 EvalReActPipelineTest.java:146/:193、EvalSmokeFixtureTest.java:219-222（旧口径"评委调用不计入"）互斥；授权按新口径迁移这三处断言（case 级含评委：reactModeRunsProductionLoop 33/18/51、reactModeAccumulates 33/23、冒烟首例 51，报告级 19 题重算 627/342/969），扩边由 emergent task-0024 承接 EvalReActPipelineTest 与 EvalSmokeFixtureTest 两个测试文件 | by: V2 | reversal: checkpoint revert
- D5 | eval_gate.sh 离线门禁对 fixture 报告（SELF_JUDGE/releaseEligible=false）加 --allow-self-judge 放行：沿用 task-0001 既有显式降级通道，定位为离线回归检测而非发布判定；A/B 实测确认 7 条 --fail-if 阈值与 Wilson 门禁未放宽（不加旗标则 done 标准"eval_gate.sh 全绿"在桩评委前提下无解） | by: V2 | reversal: checkpoint revert

## Parked

## Deferred (R3.5)
以下项已确认但不在本包：`ddl-auto` 改 Flyway、WS token 移出 URL、Micrometer/Prometheus、多实例 WS resume、Kafka 分区与消费并发、会话历史分页、上传配额。ES 权限字段、Admin 硬编码密钥、graceful shutdown 已分别由 task-0021 / task-0017 / task-0020 收口。

## Risks
- `ChatPipeline` 同时被 task-0006、task-0007、task-0010 修改，必须按依赖串行，不得并行。
- 真机冒烟依赖本机 ES/MinIO/Kafka 与模型 key；没有环境时只保证 dry-run 与离线门禁。
- 收紧 Kafka 白名单可能暴露历史消息类型缺失，按 F4 处理，不得回退通配符。
- 07 扫描件的 OCR 覆盖在本包仍是 0，报告必须显式标注，不能从分母里悄悄消失。

## Binding
- branch: `spec/2026-11-10_fix-eval-and-runtime-gaps`
- frozen: 2026-11-10T15:30:20+00:00
