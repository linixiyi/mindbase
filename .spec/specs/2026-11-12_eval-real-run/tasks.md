# Tasks：首次真机评测

格式说明（给执行端）：

- 每张卡按 `boundary → verify → expect` 执行；红基线意味着现在跑 verify 必须失败，改完之后必须绿。
- `boundary` 之外的改动视为越界；确需扩边请在 Decisions 记录并向用户确认。
- 所有 verify 命令都在工作树根执行，已显式 `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run`（plan-go 工作树 relocation，D5）。
- 新增脚本/测试一律 `new:` 前缀声明；脚本只用 Python 3 标准库与 curl，不新增依赖。
- 凭据只从环境变量或仓库根 `.env` 读取，不打印、不写文件、不进报告；token 只进内存。
- 0001/0002/0005/0008 依赖本机 ES、MinIO、MySQL、Kafka、Redis；后端未启动时先按 [runbook](docs/eval/runbook.md) 启动。

## 任务

- [x] task-0001 语料入库脚本：分片上传、合并、等待入库
  - id: task-0001
  - boundary: new:scripts/eval_corpus_ingest.py, new:docs/eval/corpus-ingest.md, new:docs/eval/runbook.md
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_corpus_ingest.py --self-test`
  - expect: red
  - 背景：10 篇 PDF 从未入库（`file_upload` 只有 1 行旧测试文档，ES 只有 2 个分块）；现有文档只讲怎么"提交评测"，没讲怎么把语料灌进知识库。上传接口是 5MB 分片 + merge 后经 outbox 发 Kafka，手工用 curl 拼 multipart 不现实。
  - 交付：新脚本读 `eval-datasets/manifest.json` 与 `eval-datasets/corpus/`，先校验本地文件大小与 md5；`--dry-run` 只打印"文件 → md5 → 分片数"并校验 manifest；`--run` 按 `EVAL_ADMIN_TOKEN` → `EVAL_ADMIN_USERNAME/PASSWORD` → `.env` 的 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD` 三级取 token（复用 [eval_real_smoke.sh](scripts/eval_real_smoke.sh) 的口径），逐文件 `POST /api/v1/upload/chunk`（5MB 分片、最多 2 并发、带 fileMd5/chunkIndex/totalSize/fileName/totalChunks/isPublic=true）→ `POST /api/v1/upload/merge` JSON `{fileMd5,fileName}` → 轮询 ES 该 md5 的分块数 >0；`--resume` 跳过已完成文件，`--only <name>` 单篇重试；401 重登一次；任何 token/密码不打印。`docs/eval/corpus-ingest.md` 写前置、命令、断点续传、失败排查（MinIO/ES/OCR/权限）。
  - 测试：`--self-test` 覆盖 md5 校验、5MB 分片边界（整倍数/余数/空文件）、manifest 缺文件、merge 响应解析、ES count 轮询状态机（用注入的 fake HTTP 函数），不联网。
  - 成功判定：`--self-test` 绿；`--dry-run` 对 10 篇输出 10 行且 md5 与 manifest 一致。
  - 失败处理：单篇上传失败不删其他文件；重跑必须幂等（同 md5 已 COMPLETED 直接跳过）；不把 token 写进日志。

- [x] task-0002 语料就绪检查：manifest ↔ ES 分块数
  - id: task-0002
  - boundary: new:scripts/eval_corpus_readiness.py
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_corpus_readiness.py --check`
  - expect: red
  - depends-on: task-0001
  - 背景：没有"语料是否真的入库"的判据，真机评测只能在空召回后才知道。需要把 manifest 的 10 个 md5 与 ES 分块数对上，并给出可直接执行的补救命令。
  - 交付：脚本读 manifest，按 `ELASTICSEARCH_*` 环境变量（缺省 `elastic`/空、`http://localhost:9200`，支持 https 自签）对每个 md5 查 `_count`，输出 markdown 表与 `--json`：文件名、md5、期望页数、实际分块数、状态；`--check` 任一文件 0 分块即退出 1；`--min-chunks 1` 可调；`--self-test` 用 fixture JSON 覆盖"全齐/缺一篇/全空/ES 不可达"四种。
  - 测试：`--self-test`；真机 `--check` 在 0001 入库后转绿。
  - 成功判定：`--check` 退出 0 且 10/10 文件分块数 ≥1；`--self-test` 绿。
  - 失败处理：ES 不可达报"连接失败"而不是"分块为 0"；凭据缺失时给出环境变量名，不猜默认密码。

- [ ] task-0003 真机冒烟接语料就绪前置
  - id: task-0003
  - boundary: scripts/eval_real_smoke.sh, docs/eval/real-smoke.md
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && bash scripts/eval_real_smoke.sh --require-corpus`
  - expect: red
  - depends-on: task-0001, task-0002
  - 背景：现在 `eval_real_smoke.sh --run` 在语料未入库时也会提交评测，跑 20 分钟后才看到全空召回，浪费模型 token。
  - 交付：新增 `--require-corpus`（等价于 dry-run + 就绪检查）：调用 `eval_corpus_readiness.py --check`，未就绪退出 2 并打印缺哪几篇；`--run --require-corpus` 在提交前强制执行同一检查（可用 `EVAL_SKIP_CORPUS_CHECK=1` 显式跳过，仅限调试），失败即退出 2 不提交。`--help` 与 [real-smoke.md](docs/eval/real-smoke.md) 同步补说明与语料入库链接。
  - 测试：`--dry-run --require-corpus` 在语料就绪时退出 0；把 readiness 脚本临时指向空 fixture 时退出 2（脚本内自检或文档示例）。
  - 成功判定：verify 绿；未就绪时 curl 不发出 `/admin/eval/run`。
  - 失败处理：readiness 脚本不存在时不得静默跳过，报"缺就绪检查"退出 2。

- [x] task-0004 neg 题退出检索指标分母
  - id: task-0004
  - boundary: src/main/java/com/mindbase/service/eval/EvalReportAggregator.java, src/main/java/com/mindbase/service/eval/EvalReport.java, scripts/eval_compare.py, new:src/test/java/com/mindbase/service/eval/NegRetrievalMetricScopeTest.java, src/test/java/com/mindbase/service/eval/EvalSmokeFixtureTest.java
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && ./mvnw -q -B -Dtest=NegRetrievalMetricScopeTest test`
  - expect: red
  - 背景：17 条 `expectedRefusal=true` 的题带 `expectedEvidence`（用于定位"论文没提这个信息"的锚点），[EvalReportAggregator.java:90](src/main/java/com/mindbase/service/eval/EvalReportAggregator.java#L90) 按"有 evidence"计入 `evidenceLabeled`，neg 因此进入 recall/docRecall/coverage/nDCG 分母；审计脚本对 17 条报 warn。生成侧 accuracy 仍应包含 neg（拒答正确率是生成指标）。
  - 交付：聚合器按 `expectedRefusal=true`（或 type=neg）把用例从检索指标分母排除：`evidenceLabeled`、`recallAtK`、`docRecallAtK`、`meanExpectedDocCoverage`、nDCG 全部分母只含非 neg；summary 增 `retrievalCases` 与 `negExcludedCases`（additive，V6）；neg 检索结果单独计数但不算命中率。`scripts/eval_compare.py` 识别新字段（旧报告缺字段时回退旧口径并打印说明），阈值断言不受影响。`NegRetrievalMetricScopeTest` 构造 1 正 1 neg 的用例集，断言分母=1、neg 不影响 recall/coverage/nDCG、accuracy 仍按 2 例计算、summary 两个计数正确。
  - 测试：新测试类 + 既有 `EvalReportAggregatorTest` 不回归（必要时同步 fixture）。
  - 成功判定：测试绿；smoke fixture 报告的 `retrievalCases` 小于 `totalCases`。
  - 失败处理：不得把 neg 从 accuracy/refusal 指标里一并排除；不得改报告 schemaVersion（保持 2.0，只加字段）。

- [ ] task-0005 按证据短语回填 expectedChunkIds
  - id: task-0005
  - boundary: new:scripts/eval_backfill_chunk_ids.py, src/main/resources/eval/eval-v2.jsonl, src/test/java/com/mindbase/service/eval/EvalGoldFieldsTest.java
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_backfill_chunk_ids.py --check --min-coverage 0.9`
  - expect: red
  - depends-on: task-0002
  - 背景：`expectedChunkIds` 0/126；没有分块 id，`RetrievalMetrics.isRelevant` 只能回退"分块文本包含证据短语"的近似判定，分块级命中会被同文件的无关分块污染。
  - 交付：脚本读数据集与 ES（按 fileMd5 拉全部分块的 `chunkId/textContent`），用与 `RetrievalMetrics.normalizeText` 相同口径（NFKC + 去空白 + 小写）把每条 `expectedEvidence` 匹配到分块，写回 `expectedChunkIds`（与 evidence 一一对应，匹配不到写空并报告）；`--check` 只读统计覆盖率，低于 `--min-coverage` 退出 1 并列出未匹配用例；`--write` 幂等写回数据集（保持 JSONL 字段顺序与中文不转义）；`--self-test` 用 fixture 覆盖精确匹配、跨块短语、大小写/全半角差异、缺失文件；`--dry-run` 打印前 10 条匹配结果。
  - 测试：`--self-test`；真机 `--check` 在 0002 入库后 ≥0.9。
  - 成功判定：verify 绿；写回后数据集仍能被 `EvalDatasetParser` 解析（`mvn -Dtest=EvalGoldFieldsTest test` 通过）。
  - 失败处理：匹配到多个分块时取包含完整短语且 chunkId 最小的一个并在报告里标 ambiguous；不得用文件名哈希或页码猜 id。

- [x] task-0006 发布条件预检：独立评委、κ、定价
  - id: task-0006
  - boundary: new:scripts/eval_release_preflight.py, new:docs/eval/release-checklist.md, README.md
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_release_preflight.py --self-test`
  - expect: red
  - 背景：报告里 `releaseEligible=false` 是必然的，但没人知道缺哪一项；独立评委需要用户提供第二家 provider key，κ 需要至少 5 条人工判定，定价需要 `EVAL_PRICING_*`。缺的是"一条命令告诉我还差什么"。
  - 交付：脚本接受 `--report <v2.json>`、可选 `--sidecar <*.human-reviews.json>`（缺省按报告文件名自动找同目录 sidecar）与可选 `--env-file`，先合并"报告 config + sidecar agreement"再检查：①`config.judgeIndependence` 是否为 INDEPENDENT（SELF_JUDGE 时打印配置 `eval.judge.provider/model` 的步骤）；②sidecar 的 `agreement.comparableCases/sampleSize` ≥ `eval.human-agreement.min-comparable-cases` 且 `cohensKappa` ≥ `min-kappa`（缺 sidecar 时提示用 `scripts/eval_human_review.py export/import` 收 ≥5 条）；③`cost.amount` 非 null（提示 `EVAL_PRICING_INPUT_PER_1K/OUTPUT_PER_1K/CURRENCY`）；④输出 effective `releaseEligible` 与命中原因，并说明"原始报告冻结、以 sidecar 重算为准"。不打印密钥值，不改报告文件。`--self-test` 用 4 份 fixture（SELF_JUDGE/κ 不足/未配价/全通过）断言退出码与原因。`docs/eval/release-checklist.md` 写清"谁能做哪一步"（用户提供 key、用户做 5 条判定、zcode 只跑校验），并把 README 的评测段落从"50 条 v1"更新为"126 条 v2 + 首份真机报告状态"。
  - 测试：`--self-test` 覆盖有/无 sidecar、κ 达标/不足；对现有 smoke fixture 报告运行，应判 FAIL 且原因含 SELF_JUDGE/无人工判定/未配价。
  - 成功判定：`--self-test` 绿；失败输出能让用户直接照做。
  - 失败处理：报告缺字段时报"该报告不能用于发布判定"，不得默认 PASS；不得自动生成人工标签。

- [x] task-0007 v2 报告校验与压缩摘要
  - id: task-0007
  - boundary: new:scripts/eval_report_summary.py, docs/eval/reports/README-v2.md
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_report_summary.py --self-test`
  - expect: red
  - 背景：报告全文在 `eval-reports/`（git 忽略），交付里没有任何可审计的"这份报告真的跑了 120 题"的证据；直接把 126 题回答正文放进公开仓库又太大。
  - 交付：脚本读 v2 报告（可选自动读取同目录 sidecar），校验 `schemaVersion=2.0`、`summary.totalCases=120`、`retrievalCases>0`、`retrievalErrorRate/fullCoverageHitRate` 非 null、`config.judgeIndependence` 与 `releaseEligible/releaseBlockedReason` 存在；产出压缩摘要 JSON（runId、label、startedAt/finishedAt、datasetSource、topK、generation/judge 模型、config 的 judge/rrf/rerank/parentContext 关键项、summary 指标子集、报告原始 release gate、sidecar 的 κ 与 effective release gate、cost），**不含 question/answer/retrievedText**；`--write <path>` 落盘，`--check <path>` 校验已有摘要，`--self-test` 用 fixture 覆盖合格/题数不符/覆盖率 null/摘要含正文（必须拒绝）。
  - 测试：`--self-test`；对 `target/eval-smoke/*.json` 生成摘要并断言无正文。
  - 成功判定：`--self-test` 绿；摘要文件 <30KB。
  - 失败处理：报告缺 schemaVersion 时明确报"不是 v2 报告"，不回退猜测；不得把 120 题以外的 blocked 题算进计数。

- [ ] task-0008 真机跑通并产出首份 v2 摘要
  - id: task-0008
  - boundary: new:docs/eval/reports/v2-first-summary.json, new:docs/eval/first-real-run.md
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && python3 -B scripts/eval_report_summary.py --check docs/eval/reports/v2-first-summary.json`
  - expect: red
  - depends-on: task-0001, task-0002, task-0003, task-0004, task-0005, task-0006, task-0007
  - 背景：整条链的最后一步：后端起、语料入库、回填 chunkId、跑一次 120 题真机评测、把压缩摘要落盘。没有这一步，前 7 张卡只是工具。
  - 交付：`docs/eval/first-real-run.md` 写执行顺序与验收：①按 [runbook](docs/eval/runbook.md) 启动后端并确认 `/actuator/health`；②`python3 scripts/eval_corpus_ingest.py --run`；③`python3 scripts/eval_corpus_readiness.py --check`；④`python3 scripts/eval_backfill_chunk_ids.py --write`；⑤`bash scripts/eval_real_smoke.sh --run`（如需先小跑，启动后端时设 `EVAL_MAX_CASES=20`，小跑后重启后端去掉该变量再跑完整 120 题）；⑥`python3 scripts/eval_report_summary.py --write docs/eval/reports/v2-first-summary.json`。摘要记录真实指标、`judgeIndependence`、`releaseEligible` 与阻断原因（首跑大概率是 SELF_JUDGE，如实记录，不伪装可发布）。
  - 测试：verify 只校验摘要文件（不重复触发评测）；摘要必须通过 0007 的 `--check`。
  - 成功判定：摘要存在且 `totalCases=120`、`retrievalCases>0`、`fullCoverageHitRate` 非 null、`retrievalErrorRate<=0.1`；`docs/eval/first-real-run.md` 的记录与实际命令一致。
  - 失败处理：后端起不来、模型 key 失效、入库卡住时停下并报告具体阶段与日志位置，不得手写或伪造摘要；首跑失败先看 readiness 与报告 `retrievalMode`，不要连续重跑烧 token。

- [x] task-0009 Tika/PDFBox 回退解析引擎
  - id: task-0009
  - boundary: src/main/java/com/mindbase/service/ParseService.java, new:src/test/java/com/mindbase/service/ParseTikaFallbackTest.java
  - verify: `cd /Users/linyixi/PaiSmart/.spec/worktrees/2026-11-12_eval-real-run && ./mvnw -q -B -Dtest=ParseTikaFallbackTest test`
  - expect: red
  - depends-on: task-0001
  - 背景：本机未安装 LiteParse（lit 命令缺失、tesseract 缺失），后端唯一 PDF 引擎不可用，4 篇已上传 PDF 解析失败进死信（vectorization_status=FAILED，Exec failed error 2）。Apache Tika/pdbox 已在 classpath（tika-parsers-standard-package → pdfbox 2.0.29），零新依赖。裁决见 Decisions D10。
  - 交付：ParseService 增 engine=tika 分支——parsePdfWithPdfBox(InputStream) 用 PDFBox Loader 逐页 PDFTextStripper 抽文本，经 normalizeLiteParseText 同口径归一后构造 LiteParsePage(pageNumber, text)；两处调用点（parsePdfAndSave/estimatePdfEmbeddingUsage）改经统一分发器按 pdfParsingEngine 路由（liteparse 走原路径，tika 走新分支，其他值保持原有报错语义）；LiteParse 默认引擎不变，不改 application.yml，后端以 FILE_PARSING_PDF_ENGINE=tika 环境变量启动。
  - 测试：ParseTikaFallbackTest 用 PDFBox 在测试内构造 2 页 PDF，断言页号 1/2 与两页文本被抽取出（归一后包含所植关键词）；反射调用私有分支方法，ReflectionTestUtils 设 engine=tika；无外部服务。
  - 成功判定：测试绿；LiteParse 路径行为不变（既有 ParseServiceUnitTest 不回归）。
  - 失败处理：PDFBox 解不出文本层的扫描页返回空文本由调用方跳过（与 07 OCR blocked 既有口径一致）；不得为了绕过而删除 LiteParse 路径。
  - origin: emergent
