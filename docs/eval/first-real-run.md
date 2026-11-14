# 首次真机 v2 评测执行记录（2026-11-13）

> 本文按实际发生过的执行重写。所有数字与 `docs/eval/reports/v2-first-summary.json`（源报告 `eval-reports/20261113-045306_real-smoke-1794516786_abe90a03bd59.json`，1.17MB，git 忽略）逐一对应，无编造。
> **发布结论（如实）：releaseEligible=false，本报告不可用于发布判定。** 阻断原因见 §6。

## 1. 执行环境

- 执行位置：工作树 `.spec/worktrees/2026-11-12_eval-real-run`（分支 `spec/2026-11-12_eval-real-run`）。
- 服务：docker 四件套（es/minio/kafka/redis，已在线）+ 本机 MySQL `PaiSmart`。
- 后端：工作树根 `./mvnw spring-boot:run -DskipTests` 启动，`/actuator/health` UP。
- **解析引擎偏差（Decisions D10）**：本机未安装 LiteParse（`lit`）与 tesseract，首批 4 篇解析失败进死信后，以 `FILE_PARSING_PDF_ENGINE=tika` 重启后端改用 PDFBox 文本层抽取（无 OCR；扫描页无文本层即空页跳过）。LiteParse 仍是默认引擎，application.yml 未改。

## 2. 执行顺序与结果（对应 runbook 六步）

| 步骤 | 命令（工作树根执行） | 结果 |
| --- | --- | --- |
| ① 后端 | `./mvnw spring-boot:run -DskipTests`（`FILE_PARSING_PDF_ENGINE=tika`） | health UP |
| ② 入库 | `python3 -B scripts/eval_corpus_ingest.py --run` | 10/10 上传+合并+解析入库 |
| ③ 就绪 | `python3 -B scripts/eval_corpus_readiness.py --check` | PASS，10/10（各文件分块 34–67 不等） |
| ④ 回填 | `python3 -B scripts/eval_backfill_chunk_ids.py --write` | 126/126 命中，覆盖率 **1.0000**；ambiguous 59（标志位）；`expectedChunkIds` 与 `expectedFileNames` 平行（D11） |
| ⑤ 评测 | `bash scripts/eval_real_smoke.sh --run --require-corpus` | 提交 runId=`abe90a03bd59`（04:53:06），脚本 1800s 轮询超时退出（当时 76/126），**后端继续执行至终态 SUCCEEDED**（21:40:54Z），报告落盘 |
| ⑥ 摘要 | `python3 -B scripts/eval_report_summary.py --write docs/eval/reports/v2-first-summary.json` | 4554 bytes（<30KB），无 question/answer/retrievedText |

## 3. 计数口径（Decisions D9）

- `reportTotalCases=126`（全量执行）／`blockedExcluded=6`（07 OCR blocked）／`effectiveCases=120`
- `unexpectedCaseIds=[]`、`missingCountingIds=[]`（--check 已核对）

## 4. 关键指标（原样引用报告，Python 侧不自产数字）

| 指标 | 值 |
| --- | --- |
| retrievalErrorRate | **0.0** |
| fullCoverageHitRate | **0.4954**（低于 smoke 默认阈值 0.5 约 0.46pp；评测期间 rerank 401 降级 RRF，见 §5） |
| recallAtK | 0.5688 |
| docRecallAtK | 0.9083 |
| meanExpectedDocCoverage | 0.5329 |
| nDCG@3 / @5 / @10 | 0.3938 / 0.4233 / 0.4233 |
| accuracy（生成，含 neg） | 0.871（judgedCases=124，correctCases=108） |
| refusalAccuracy | 0.6984（旧标记词口径，已撤回；新口径见 §7。17 条 neg 题另列，negExcludedCases=17） |
| meanJudgeScore | 4.5323 |
| needsHumanReviewCount | 9 |

## 5. 过程偏差（如实记录）

1. **解析引擎**：LiteParse 缺失 → Tika/PDFBox（D10，task-0009）。页码信息保留（PDFBox 逐页抽取），无 OCR。
2. **rerank 降级**：评测运行中 rerank 服务 401，按既有降级逻辑走 RRF（`retrievalModeCounts={"HYBRID_RRF":126}`）。本报告检索指标是 RRF 口径，不是 hybrid+rerank 口径。
3. **轮询超时**：冒烟脚本默认 1800s 轮询上限短于全量 126 题实际耗时（约 47 分钟）；已把默认值提高到 3600s（本次运行仍按当时默认 1800s，脚本 exit 2 但后端继续执行至终态）。脚本在语料未就绪时 fail-closed 的设计（task-0003）已先正确拦截过一次空提交。
4. **中间失败已清理**：首批 4 篇 LiteParse 死信记录（file_upload FAILED 行）已删除后全量重跑，最终 file_upload 10/10 无 FAILED。

## 6. 发布判定（如实）

- `judgeIndependence=SELF_JUDGE`：评委与生成共用同一模型（glm-5.3-flash），**自评分数不能用于发布判定**。
- 无人工复核 sidecar：κ 未计算（可比样本 0）。
- 未配价：`cost.amount=null`（prompt 1,834,249 + completion 54,346 = 1,888,595 tokens 已记录，费用未折算）。
- **effective releaseEligible=false。** 本报告是"链路真机通电"的工程验证，不是质量发布依据。
- 下一步：按 [release-checklist.md](release-checklist.md)——用户提供第二家评委 key、完成 ≥5 条人工判定（`scripts/eval_human_review.py export/import`）、配置 `EVAL_PRICING_*`，然后以"报告 + sidecar"重算 effective gate。

## 7. 指标口径修订与 2026-11-14 复核（重要）

首跑报告暴露了两个**指标实现缺陷**，2026-11-14 已修（`GenerationMetrics.java`），首跑数字保留为历史但不作为质量口径：

1. **refusalAccuracy 旧口径撤回**：旧实现用标记词扫全文，34 条非 neg 的正常回答（正文里出现“资料中没有提到 X，但 Y”）被误判为拒答，0.6984 不可用。新口径：只看第一条主张；`refusalAccuracy` 只按 neg（`expectedRefusal=true`）的 `judgeCorrect` 计算——拒答或纠正错误前提都算处理正确；`refusedAsExpected` 只作行为诊断。
2. **faithfulness 旧口径撤回**：旧实现直接对中文回答与英文证据做 token 覆盖率，482/529 条 claim 被判 unsupported，0.0638 主要是“跨语言词面不可比”而非生成不忠实。新口径：去 markdown/标题/元话语；中文 claim 对英文证据（或反之）标 `unverified`，单列且不进 faithfulness 分母。

按新口径对首跑报告 `cases` 只读重算（不改报告、不改摘要）：17 条 neg 中 `judgeCorrect=true` 14 条 → **refusalAccuracy=0.8235**；旧 0.6984 撤回。faithfulness 的旧 claim 判定已写入报告，无法离线重算，等网关恢复后重跑。

**2026-11-14 重跑失败（不产生新结论）**：本地模型网关 `localhost:20128` 返回 502，126 题里 53 条生成失败、0 条被评委判定；该报告已改名为 `eval-reports/INVALID-20261114-183904_real-smoke-1794652744_a1d1ccfa803b.json`，不生成摘要、不进入质量对比。

**门禁修复**（防止这种“检索阈值通过但生成全坏”的报告再次被当成结论）：

- `scripts/eval_real_smoke.sh` 新增 `EVAL_MAX_FAILED_RATE`（默认 0）与 `EVAL_MIN_JUDGED_RATE`（默认 0.9），并新增 `--check-report <file>` 只校验已有报告；
- `scripts/eval_report_summary.py` 拒绝 `judgedCases=0`、生成失败率 >10%、`accuracy=null` 的报告；
- 2026-11-14 用 `--check-report` 实测：首跑报告生成有效性通过（failed=0，judged=124/126），但 `fullCoverageHitRate=0.4954 < 0.5` 仍判不通过（与第 4 节一致）；坏报告在生成失败率/可评率上被拒（failed=53/126，judged=0）。

**下一步**：恢复 `localhost:20128` 网关后重跑 `bash scripts/eval_real_smoke.sh --run --require-corpus`，再生成 `v2-second-summary.json`；在新报告出来前，首份 v2 摘要仍是唯一有效的真机基线，且 `releaseEligible=false` 的结论不变。

## 8. 2026-11-14 19:09 网关恢复后重跑（有效）

`localhost:20128` 恢复后重启后端（清熔断器）重跑，报告 `1e50c36b212f`（`eval-reports/20261114-190936_real-smoke-1794654575_1e50c36b212f.json`），压缩摘要 `docs/eval/reports/v2-second-summary.json`。

| 指标 | 值 |
| --- | --- |
| 执行 / 有效 | 126 / 120（6 blocked 剔除） |
| judged / failed | 122 / **1**（JUDGE_FAILED） |
| recall@5 / MRR / docRecall | **0.4312** / 0.2904 / **0.9083** |
| coverage / fullCoverage | 0.5329 / **0.4954** |
| nDCG@3 / 5 / 10 | 0.3152 / 0.3262 / 0.3262 |
| accuracy / meanJudgeScore | **0.8852**（108/122） / 4.5902 |
| refusalAccuracy（新口径） | **0.9412**（16/17 neg 由评委判对） |
| refusedAsExpected（行为诊断） | 12/17 |
| faithfulness（跨语言已剔除） | 0.1807 |
| citation precision / recall | 0.3491 / 0.9077 |
| 评委双采样一致率 | 0.9683（122 可比，4 条不一致） |
| release gate | **false**（SELF_JUDGE / 无人工判定 / 未配价） |

**冒烟判定**：`--check-report` 除 `fullCoverageHitRate=0.4954 < 0.5` 外全部通过；failed=1/126 在 2% 容差内，judged=122/126 在 90% 下限内。即“报告生成有效、质量门禁仍差 0.46pp 覆盖率”。

**结论**：这是目前唯一有效的完整真机基线；它证明链路已通电、指标可用，但检索覆盖率未达 0.5 门禁，且发布仍被 SELF_JUDGE/κ/定价阻断。下一步是检索调优（或明确调整 0.5 阈值）与人工复核，不是再重跑一次就能变绿。
