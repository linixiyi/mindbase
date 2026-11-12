# 发布条件预检与清单（eval_release_preflight.py）

真机评测报告的 `releaseEligible=false` 是首跑必然结果（SELF_JUDGE + 无人工判定 + 未配价），
但"还差哪几项、每项谁来做"此前没有一处说清。本页补齐：三道门禁是什么、谁能做哪一步、
一条命令怎么跑。

口径先行：**评测报告文件冻结不改**（评测当时的原始结果），人工判定写在报告旁的旁路文件
`*.human-reviews.json`（`HumanReviewStore`，HumanReviewStore.java:15-21）。因此发布结论
不读报告里的旧 `config.releaseEligible`，而是按「报告 config + sidecar agreement」合并重算
——这条重算命令就是预检脚本，输出 **effective releaseEligible**：

```bash
python3 -B scripts/eval_release_preflight.py --report eval-reports/<ts>_<label>_<runId>.json
# sidecar 缺省自动找同目录 <报告名>.human-reviews.json（HumanReviewStore.sidecarPath 口径）；
# 也可 --sidecar 显式指定，--env-file 指定阈值/配价环境变量来源（缺省 $EVAL_ENV_FILE → 仓库根 .env）
```

## 三道门禁

| # | 门禁 | 检查什么 | 数据来源 | 通过判据 |
| --- | --- | --- | --- | --- |
| ① | 评委独立性 | `config.judgeIndependence` | 报告 config（冻结） | 字面 `INDEPENDENT`（任何其他值宁可误拦不可放行） |
| ② | 人工一致性 | `comparableCases`（缺键回退 `sampleSize`）与 `cohensKappa` | sidecar 顶层 `agreement`（回退报告内嵌 `humanReview.agreement` / `humanAgreement`） | 可比样本 ≥ min-comparable-cases **且** κ ≥ min-kappa（κ 检查被显式停用时只查样本量，但如实标注"不代表可发布"） |
| ③ | 费用折算 | `cost.amount` | 报告 cost（冻结） | 非 null（0.0 是合法值）；null 按未配价拦截 |

阈值来源（脚本会打印出处）：sidecar agreement 记录值（`minKappa` / `minComparableCases`，
后端算 κ 时的快照）> 报告 config 记录值 > 进程环境 / env 文件（`EVAL_HUMAN_AGREEMENT_MIN_KAPPA`
/ `EVAL_HUMAN_AGREEMENT_MIN_CASES`，缺省 0.6 / 5，同 application.yml:342-349）。记录值优先于
环境：发布判定不追认事后放宽——想换阈值就重新 import 重算 agreement，不改旧文件。env 值置空
= 停用 κ 检查（yml 注释口径），其余门禁照常。

## 谁能做哪一步

核心约束：**provider key 与人工判断只在用户手里**（spec A9 / F4：zcode 不持有 key，
也绝不自动生成人工标签）；zcode/脚本只负责生成复核包、回填、跑校验。

| 步骤 | 谁 | 做什么 | 命令 / 动作 |
| --- | --- | --- | --- |
| 1. 独立评委 | **用户** | 在管理后台「模型供应商」用第二家供应商配一个 llm scope 的供应商编码（不要求激活），把 provider key 配进去；然后设 `eval.judge.provider=<编码>`（或环境变量 `EVAL_JUDGE_PROVIDER`），可选 `eval.judge.model` / `EVAL_JUDGE_MODEL` | 管理后台 + application.yml / .env |
| 2. 配价 | **用户** | `EVAL_PRICING_INPUT_PER_1K` 与 `EVAL_PRICING_OUTPUT_PER_1K` **同时**配置（每 1000 tokens 单价），可选 `EVAL_PRICING_CURRENCY` | .env |
| 3. 导出复核包 | zcode/脚本 | 过滤报告里 `needsHumanReview=true` 的用例，verdict 留空 | `python3 -B scripts/eval_human_review.py export --report <报告> --out review.json` |
| 4. 人工判定 | **用户** | 在 review.json 逐条填 `verdict`（true/false），至少覆盖到可比样本 ≥5 条；可加 `score`（1-5）与 `note`（≤500 字） | 人工编辑，无命令 |
| 5. 回填判定 | zcode/脚本 | POST 回评测运行；判定落在报告旁 `*.human-reviews.json`，报告本身不动 | `python3 -B scripts/eval_human_review.py import --run <runId> --file review.json --reviewer <复核人>` |
| 6. 发布预检 | zcode/脚本 | 合并报告 + sidecar 重算三道门禁，列 effective 结论与剩余修复步骤 | `python3 -B scripts/eval_release_preflight.py --report <报告>` |
| 7. 发布决定 | **用户** | 看 effective releaseEligible 与命中原因拍板；zcode 只报数不拍板 | — |

步骤 1/2 改完后端配置要**重启后端并重跑评测**：报告冻结，旧报告的 SELF_JUDGE 与
cost.amount=null 不会因为配置变化而改变，重跑才有新报告可判。

## 退出码

| 码 | 含义 |
| --- | --- |
| 0 | effective releaseEligible=true（三道门禁全过；报告冻结记录仍是原值，不回写） |
| 1 | effective releaseEligible=false——输出逐项 FAIL、命中原因与每项的可执行修复步骤 |
| 2 | 用法/环境错误，或报告结构性缺字段（缺 `config` / `config.judgeIndependence` / `cost`，含拿 v1 报告来判的情形）：打印"**该报告不能用于发布判定**"，不默认 PASS |

`--self-test` 跑内置 fixture 自检（SELF_JUDGE / κ 不足 / 未配价 / 全通过四组场景 + 结构缺字段
与阈值来源断言），离线、不读真实报告、不联网。

## 行为细节（诚实口径）

- **缺字段不默认 PASS**：材料不齐时退出 2 说"不能用于发布判定"，绝不把"查不了"当"通过"。
- **不打印密钥值**：环境变量只报"已设置/未设置（来源，值不打印）"；任何 token/密码/key 不落盘、不进输出。
- **只读**：报告与 sidecar 文件一律不改；sidecar 与报告 `runId` 不一致判 FAIL（人工判定绑定
  该轮评委标签，κ 不能跨轮合并）；`reportFile` 改名只警告不拦（按 runId 绑定为准）。
- **κ 恰好等于阈值不算低于**；`cohensKappa=null`（可比样本为 0 或两边判对比例乘积为 1 导致
  κ 无定义）在 κ 检查启用时按 FAIL 处理，不冒充通过。
- **sidecar 通过 ≠ 报告变绿**：effective 结论是重算视图；报告里的 `config.releaseEligible=false`
  会原样显示在"原始报告冻结记录"里，方便对账。

## 常见失败

| 现象 | 退出码 | 处理 |
| --- | --- | --- |
| `该报告不能用于发布判定` | 2 | 传错了文件（v1 报告 / 状态 JSON / 非 JSON）——预检要 v2 落盘报告；压缩摘要校验用 `scripts/eval_report_summary.py` |
| `无人工判定：没有 sidecar…` | 1 | 走上表步骤 3→4→5，收 ≥5 条可比判定后重跑预检（[human-review.md](human-review.md)） |
| `κ 0.42x 低于下限 0.6` | 1 | 看 `disagreementCaseIds` 补人工复核、调评分标准或换评委模型 |
| `可比样本 X 条少于下限 5 条` | 1 | 多判定几条再 import；样本量阈值来自 agreement 记录值，改阈值需重新 import 重算 |
| `未配价：cost.amount=null` | 1 | 配 `EVAL_PRICING_*` 并重启后端重跑评测（报告冻结，旧报告补不上金额） |
| `SELF_JUDGE：评委与生成共用同一模型` | 1 | 用户配第二家 provider（上表步骤 1）后重跑评测 |
