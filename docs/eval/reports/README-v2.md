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
