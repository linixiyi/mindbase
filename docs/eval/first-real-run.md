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
| refusalAccuracy | 0.6984（17 条 neg 题另列，D7 口径不入检索分母，negExcludedCases=17） |
| meanJudgeScore | 4.5323 |
| needsHumanReviewCount | 9 |

## 5. 过程偏差（如实记录）

1. **解析引擎**：LiteParse 缺失 → Tika/PDFBox（D10，task-0009）。页码信息保留（PDFBox 逐页抽取），无 OCR。
2. **rerank 降级**：评测运行中 rerank 服务 401，按既有降级逻辑走 RRF（`retrievalModeCounts={"HYBRID_RRF":126}`）。本报告检索指标是 RRF 口径，不是 hybrid+rerank 口径。
3. **轮询超时**：冒烟脚本 1800s 轮询上限短于全量 126 题实际耗时；脚本 exit 2 但后端继续执行至终态。脚本在语料未就绪时 fail-closed 的设计（task-0003）已先正确拦截过一次空提交。
4. **中间失败已清理**：首批 4 篇 LiteParse 死信记录（file_upload FAILED 行）已删除后全量重跑，最终 file_upload 10/10 无 FAILED。

## 6. 发布判定（如实）

- `judgeIndependence=SELF_JUDGE`：评委与生成共用同一模型（glm-5.3-flash），**自评分数不能用于发布判定**。
- 无人工复核 sidecar：κ 未计算（可比样本 0）。
- 未配价：`cost.amount=null`（prompt 1,834,249 + completion 54,346 = 1,888,595 tokens 已记录，费用未折算）。
- **effective releaseEligible=false。** 本报告是"链路真机通电"的工程验证，不是质量发布依据。
- 下一步：按 [release-checklist.md](release-checklist.md)——用户提供第二家评委 key、完成 ≥5 条人工判定（`scripts/eval_human_review.py export/import`）、配置 `EVAL_PRICING_*`，然后以"报告 + sidecar"重算 effective gate。
