# 评测报告 v2 口径重述（Wilson 95% CI）

[README.md](README.md) 所述的 7 份 v1 归档报告（数据集 50 题，2026-10-10 ~ 2026-10-11）当时只有点估计。
v2 统计口径（`EvalStatistics`，spec `2026-11-09_add-eval-harness-v2` task_0007）要求比例指标给出 Wilson 95% 区间。
本页**不改归档 JSON**，由 `scripts/eval_restate_reports.py` 从归档逐例 `judgeCorrect` 重算。

复核命令（脚本断言下表与重算一致，并断言 04/05 的改写结论在归档上成立）：

```bash
python3 scripts/eval_restate_reports.py --check
```

## 口径

- successes = `cases[]` 中 `judgeCorrect=true` 的条数；trials = `judgeCorrect` 可判（非空）的条数。
- 输出失败（`failed=true`）的用例没有评委判定，不进分母——与归档 `overall.judgeAccuracy` 的口径一致；
  脚本逐轮断言重算点估计与归档汇总吻合（7 份全部一致，偏差为 0）。
- Wilson 数字一律由 Java `EvalStatistics` CLI 计算（唯一事实源，Python 侧只做编排与表格比对，不自产数字）。
- 归档字段缺失的轮次记「该轮无判别口径」，不编造数字。
- 43/50 的精确区间是 [0.738138, 0.930492]，本表按 1 位小数写作 [73.8%, 93.0%]；
  task_0007 文中的 ≈[73.8, 93.1] 是同一数值在 ±0.2pp 浮点容差下的写法。

## 重述表

下表由 `python3 scripts/eval_restate_reports.py` 的输出直接生成，勿手改数字：

| 轮次 | 判对/可判 | 点估计 | Wilson 95% CI | 失败 |
| --- | --- | --- | --- | --- |
| `01_文件级_阈值0.2_基线_e8e69002588c.json` | 32/49 | 65.3% | [51.3%, 77.1%] | 1 |
| `02_文件级_阈值0.1_7cf00f87d108.json` | 43/50 | 86.0% | [73.8%, 93.0%] | 0 |
| `03_文件级_阈值0.0_8ab887fbf642.json` | 42/49 | 85.7% | [73.3%, 92.9%] | 1 |
| `04_文件级_阈值0.1_默认值回归_071d191adcb6.json` | 42/47 | 89.4% | [77.4%, 95.4%] | 3 |
| `05_分块级_阈值0.1_第1轮_45d445a4b5e9.json` | 43/50 | 86.0% | [73.8%, 93.0%] | 0 |
| `06_分块级_阈值0.1_第2轮_a61b7dc9321a.json` | 43/50 | 86.0% | [73.8%, 93.0%] | 0 |
| `07_分块级_阈值0.1_第3轮_8e3988461180.json` | 43/50 | 86.0% | [73.8%, 93.0%] | 0 |

## 「04 回归 / 05 修复」重述

旧读法把 04（默认值改成 0.1 后的回归，3 条失败）到 05（加空输出重试，失败 0）描述成修复带来的提升。
按判别口径重述，这个说法不成立：04 判对 42/47（89.4%，Wilson 95% CI [77.4%, 95.4%]），
05 判对 43/50（86.0%，[73.8%, 93.0%]）——判对数差 1 例，区间重叠，不可区分
（重叠部分 [77.4%, 93.0%]，两区间几乎完全互相覆盖）。
04 的 3 条失败是推理模型思考 token 耗尽输出预算（空输出），不是判错率变化；05 的重试消除的是
失败用例，不是把「判错」改成「判对」。两轮的点估计方向（89.4% vs 86.0%）也与「修复提升」相反，
正是分母不同（47 vs 50）造成的假象。

顺带说明：01 与 02 的区间也有少量重叠（01 上界 77.1% 高于 02 下界 73.8%），约 3.3pp；
与 04/05 的几乎完全包含不同，这一对仍是 7 轮里效应量最大的改动（点估计差 20.7pp），
但严格按 95% Wilson 区间论，没有一对轮次能达到「区间分离」。

## 语料说明（task_0010：基于 10 篇真实 PDF 重建题集）

上面 7 轮报告用的还是 v1 的 50 题自指语料（mindbase.pdf / rustfs-rag.txt / 候选人简历）。
v2 评测数据集 `src/main/resources/eval/eval-v2.jsonl` 换成 `eval-datasets/corpus/` 下 10 篇真实 PDF
（清单 `eval-datasets/manifest.json`，选型理由见 `eval-datasets/corpus/README.md`；PDF 仅本地使用，不入库）。

- 结构：126 条 = **120 条计数题（dev 90 / test 30）** + 6 条 `status="blocked"` 题；负样本（`expectedRefusal=true`）**17 条**。
- 覆盖：10 篇里 9 篇进入计数分母，每篇 ≥6 题（01→14，02→15，03→16，04→16，05→16，06→16，08→10，09→9，10→8）；
  07 的 6 条题不计入分母（见下）。门禁 `EvalDatasetIntegrityTest` 固化以上口径。
- **07 为何 blocked**：`07-os-filesystem-ppt-zh.pdf` 是中文扫描/图片型 PPT（203 页），
  文本抽取只得约 49 字符/页的图示碎片；且本机 OCR 链路未配置（无 `lit` CLI、无 tesseract、
  `.env` 无任何 `ALIYUN_OCR_*` / `FILE_PARSING_LITEPARSE_*` 配置）。它的题全部标注 `ocr=true`、
  `status="blocked"`，不计入题量/split/覆盖/负样本任何分母；OCR 配置后解除 blocked。
- 证据标注：每题的 `expectedEvidence` 是答案分块中会出现的关键短语；除 07 blocked 题外，
  全部短语已用与 `RetrievalMetrics.normalizeText` 相同口径（NFKC + 去空白 + 小写）对照 PDF 抽取文本**逐字命中核验**。
- type 词表：`fact` 单点事实 / `para` 同义改写 / `multi` 多跳与跨条目 / `neg` 应拒答 / `ocr` 扫描件占位（必 blocked）。
  id 规则 `<type>-<文档编号>-<slug>`；`expectedFileNames` 与评测知识库上传文件名一致。

| 语料 | 计数题 | 语料 | 计数题 |
| --- | --- | --- | --- |
| 01-clip-radford-2021 | 14 | 06-drug-discovery-a16z | 16 |
| 02-dinov2-oquab-2024 | 15 | 08-os-review-zh | 10 |
| 03-denoising-vit-yang-2024 | 16 | 09-bme6144-week6-slides | 9 |
| 04-tactile-force-chen-2025 | 16 | 10-bme6111-week4-slides | 8 |
| 05-medical-instrumentation-ch2 | 16 | 07-os-filesystem-ppt-zh | 0（6 条 blocked，不计入） |

复核命令（数据集结构门禁）：

```bash
cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=EvalDatasetIntegrityTest test
```

## 压缩摘要口径（eval-real-run task-0007：`scripts/eval_report_summary.py`）

报告全文（126 题的 question/answer/retrievedText/judgeReason）留在 git 忽略的 `eval-reports/`；
交付里可审计的证据是本目录下的**压缩摘要** JSON（`summarySchemaVersion=1`，<30KB），
由 `scripts/eval_report_summary.py` 产出与校验。该脚本只读文件（报告与 sidecar 冻结不改），
**Python 侧不自产数字**：指标一律原样引用报告，counting 块只记录剔除事实。

### 计数口径（spec D9，2026-11-13 裁决）

后端跑全量数据集：`EvalDatasetParser` 不读数据集的 `status` 字段（`EvalCase` 无该字段），
`eval-v2.jsonl` 的 126 行全部执行——其中 6 行 `status="blocked"` 的 07 扫描件 OCR 题
**也会被执行**，所以真机报告 `summary.totalCases=126`。摘要按"计数口径"呈现三个数：

| 字段 | 口径值（数据集结构性，非实测） | 说明 |
| --- | --- | --- |
| `counting.reportTotalCases` | 126 | 报告原样（含 6 条 blocked 执行题） |
| `counting.blockedExcluded` | 6 | caseId 命中数据集 `status="blocked"` id 集，**不进计数** |
| `counting.effectiveCases` | 120 | 剔除后，必须 `==120` 否则校验失败并列差集 |

blocked id 集按报告 `datasetSource` 定位数据集文件解析（镜像 `RagEvalService.loadDataset` 的
解析规则：`classpath:eval/<名>` → `src/main/resources/eval/<名>`，其余按 `eval-datasets/`
相对、再按工作树根相对）；id 对 id 精确匹配，不猜测。数据集文件定位不到时报
"无法核对计数口径"退出 2（fail-closed）；数据集没有 blocked 题时按同口径核对
（有效题数=全部用例数，与 120 不符如实报）。`blockedCaseIds` 清单入摘要，剔除不悄悄消失；
指标数字仍是报告原值（分母含 blocked 执行题），`metrics.metricsNote` 已注明。

### 命令

```bash
# 产出摘要：报告缺省自动取 eval-reports/ 最新一份 *.json（*.human-reviews.json 除外）；
# EVAL_MAX_CASES=20 预热报告会因有效题数 !=120 被拒，防止把预热轮当正式轮归档
python3 -B scripts/eval_report_summary.py --write docs/eval/reports/v2-first-summary.json

# 校验已有摘要（task-0008 的 verify 就是这一条；自包含，不要求报告/数据集还在）
python3 -B scripts/eval_report_summary.py --check docs/eval/reports/v2-first-summary.json

python3 -B scripts/eval_report_summary.py --self-test   # 离线 fixture 自检
```

退出码：0 通过；1 校验失败（不是 v2 报告 / 题数不符 / 覆盖率或错误率为 null / gate 键缺失 /
摘要含正文 / 超 30KB）；2 环境错误（文件缺失或非法 JSON、数据集无法定位、--write 目标撞上
报告或 sidecar）。报告缺 `schemaVersion` 时明确报"不是 v2 报告"，不回退猜测。

### 摘要内容与防正文

内容：runId、label、startedAt/finishedAt、datasetSource、topK、generation/judge 模型
（报告 `config.llm/judge` 原值；没有激活主供应商时 `config.llm=null`，摘要如实记 null）、
config 关键项（judge/judgeIndependence/rrf/rerank/parentContext）、summary 指标子集
（33 个指标 + 检索模式/诊断计数）、counting 三数与 blocked id 清单、报告原始 release gate
（judgeIndependence/releaseEligible/releaseBlockedReason 冻结值）、sidecar 的 κ 与
effective release gate（复用 `scripts/eval_release_preflight.py` 同一套纯函数，
"报告 + sidecar"重算，与 task-0006 预检同口径）、cost（含 tokenUsage）。

防正文：摘要不含 question/answer/retrievedText 等（`--check` 深扫全部键与字符串，
禁止键命中或任何字符串 >500 字符判为疑似正文，一律拒绝）。`--self-test` fixture 覆盖：
合格（126=120+6 blocked，三数正确且报告只读）、上游已过滤（正好 120 题）、题数不符
（预热 20 题）、blocked 剔除后 ≠120（差集列出）、数据集无 blocked（126≠120 如实报）、
覆盖率/错误率 null、缺 schemaVersion、gate 键缺失、数据集无法定位、摘要含正文注入
（必须拒绝）与 sidecar κ 翻盘/绑定。

### smoke fixture 报告为何产不出摘要

`target/eval-smoke/*.json`（`EvalSmokeFixtureTest` 产出）的 `datasetSource` 是
`inline-fixture-20.jsonl`——20 题 fixture 走 inline 提交、没有落盘数据集文件，计数口径无法
核对（退出 2 "无法核对计数口径"），且 20 题本来就不满足 120 计数口径。这是预期行为：
摘要脚本只归档 120 题真机报告；已实测该路径输出不含报告任何正文片段（60 条
question/answer/retrievedText 等提取片段 0 命中），也不落盘任何文件。

### 指标口径修订（2026-11-14）

首份真机摘要 `v2-first-summary.json` 里的 `refusalAccuracy=0.6984` 与 `faithfulness=0.0638` 是**旧实现**的数字，已撤回，不作为质量口径：

- 拒答：旧实现用标记词扫全文，34 条正常回答被误判为拒答；新实现只看第一条主张，`refusalAccuracy` 按 neg 的 `judgeCorrect` 计算（拒答/纠正错误前提都算对）。
- faithfulness：旧实现对中文回答与英文证据做词面覆盖率；新实现去 markdown/元话语，并把跨语言 claim 标 `unverified` 单列，不进入分母。

2026-11-14 第一次重跑因本地模型网关 502（53/126 生成失败、0 条被评委判定）作废（`eval-reports/INVALID-...json`）；网关恢复后 19:09 重跑成功，报告 `1e50c36b212f`，摘要 `v2-second-summary.json`：refusalAccuracy=**0.9412**（16/17）、faithfulness=**0.1807**、accuracy=0.8852、judged=122/126，取代首跑这两个旧数字。该报告仍因 `fullCoverageHitRate=0.4954 < 0.5` 未过冒烟覆盖率阈值，且 `releaseEligible=false` 不变。

冒烟与摘要脚本已增加生成失败率（默认 ≤2%）/可评率（≥90%）门禁，防止“检索阈值通过但生成全坏”的报告被归档。
