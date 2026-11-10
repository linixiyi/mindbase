# 人工复核导出/回填（eval_human_review.py）

一条命令行链路完成人工复核的三步：从评测报告导出待复核用例 → 人工填写 → 回填到评测运行 → 按 κ 阈值判定。
此前人工复核只有 Java 端点（`RagEvalAdminController` 的 `POST/GET /api/v1/admin/eval/runs/{id}/human-reviews`）
与前端页面，命令行无法批量操作，κ 校准一直缺样本（spec 2026-11-10 task-0013 / C13）。

动手前先读
[`.spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md`](../../.spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md)
（服务启动顺序、token 三级获取、评测端点、常见失败）。

## 流程

```bash
# ① 导出：过滤报告里 needsHumanReview=true 的用例，verdict 留空
python3 -B scripts/eval_human_review.py export --report eval-reports/<ts>_<label>_<runId>.json --out review.json

# ② 人工在 review.json 里逐条填 verdict（true/false），可选 score（1-5 整数）与 note（≤500 字）

# ③ 回填：POST /api/v1/admin/eval/runs/{runId}/human-reviews（管理员 token，三级自动获取）
python3 -B scripts/eval_human_review.py import --run <runId> --file review.json --reviewer 张三

# ④ 判定：打印 humanAgreement，κ 低于 0.6 退出 1
python3 -B scripts/eval_human_review.py agreement --report <状态或报告 JSON> [--min-kappa 0.6]
```

`import` 可加 `--dry-run`：只校验复核文件并打印计划（目标 URL、条数、token 来源），**不发网络、不要求 token**。
`python3 -B scripts/eval_human_review.py --self-test` 跑内置 fixture 自检，同样不发网络。

## ① export

| 参数 | 说明 |
| --- | --- |
| `--report` | 评测报告 JSON（落盘报告、`GET /runs/{id}/report` 响应、状态 JSON 均可，接口的 `{code,data}` 包裹会自动展开） |
| `--out` | 复核文件输出路径（父目录不存在会自动创建） |

过滤条件：`cases[].needsHumanReview is true`。评委双采样（task-0011）产生的 `judge-disagree` / `judge-unstable`
用例都会命中：`judgeScore=null`，两次采样的结论写在 `judgeReason`（形如 `A=4/true; B=3/false`）。

导出文件每条用例固定 7 个字段（顺序固定）：

| 字段 | 含义 | 人工动作 |
| --- | --- | --- |
| `caseId` | 用例 id（绑定该轮评委标签） | 不改 |
| `question` | 题目 | 参考 |
| `answer` | 被评的生成回答 | 参考 |
| `judgeScore` | 评委 1-5 分（缺失为 null） | 参考 |
| `judgeReason` | 评委理由 / 双采样 A=B= 记录 | 参考 |
| `verdict` | 人工结论 | **必填 true/false** |
| `note` | 备注 | 可选，≤500 字 |

可另加 `score` 字段（1-5 整数，与评委同一量表），import 时一并提交。

## ② import

```bash
python3 -B scripts/eval_human_review.py import --run <runId> --file review.json --reviewer 张三
```

提交 `POST /api/v1/admin/eval/runs/{runId}/human-reviews`，body 为
`{"reviews": [{"caseId", "correct", "score", "note"}, ...]}`。字段契约以后端
`EvalHumanReviewService.HumanReviewInput`（`EvalHumanReviewService.java:149`）为准：

| 复核文件字段 | → payload 字段 | 后端校验（`EvalHumanReviewService.validate`，:117-147） |
| --- | --- | --- |
| `caseId` | `caseId` | 必填非空；必须是该轮报告里存在的用例 |
| `verdict` | `correct` | 必填布尔 true/false |
| `score` | `score` | 可选，1-5 整数 |
| `note` | `note` | 可选，≤500 字（`MAX_NOTE_CHARS`，:30）；空串后端记 null |

脚本在本地先做同样的校验（缺 verdict、score 非 1-5、note 超 500、单次超过 500 条都会退出 2 并列出问题 caseId），
**不会带着坏 payload 打后端**。复核文件 `runId` 与 `--run` 不一致直接退出 2——人工判定绑定该轮评委标签，
跨轮回填会被后端按未知用例拒绝。单次上限 500 条（`eval.max-cases` / `RagEvalProperties.maxCases` 默认 500），
更多请分批。

两点如实说明：

- `--reviewer` 只是本地输出标注；**后端实际以管理员 token 的用户名记录 reviewer**
  （`RagEvalAdminController.java:130` 从 JWT 提取），请求体里没有 reviewer 字段。
- 同一 caseId 重复提交以最后一次为准（后端 merge 语义）。

### 管理员 token（与 runbook 完全一致的三级顺序）

1. 环境变量 `EVAL_ADMIN_TOKEN`；
2. 环境变量 `EVAL_ADMIN_USERNAME` + `EVAL_ADMIN_PASSWORD`（`POST /api/v1/users/login`，读 `data.token`）；
3. 仓库根 `.env` 的 `ADMIN_BOOTSTRAP_USERNAME` + `ADMIN_BOOTSTRAP_PASSWORD`（脚本自读，支持成对引号，值永不回显）。

三级都拿不到 → 退出 2，逐级列出缺哪个变量并指向 runbook。提交遇 401 时若有账密可用则**重新登录一次再重试一次**，
仍 401 或无账密 → 退出 1 并指向 runbook。token 只进请求头，不写文件、不进日志。

## ③ agreement

```bash
python3 -B scripts/eval_human_review.py agreement --report <json> [--min-kappa 0.6]
```

`--report` 接受三种文件，按优先级取第一个找到的 `humanAgreement`（来源会打印出来）：

| 优先级 | 位置 | 文件来源 |
| --- | --- | --- |
| 1 | 顶层 `humanAgreement` | `GET /api/v1/admin/eval/runs/{id}` 状态 JSON（`RagEvalService.RunStatus`） |
| 2 | `humanReview.agreement` | `GET /runs/{id}/report` 报告接口附带（`withHumanReview`） |
| 3 | 顶层 `agreement` | 报告旁路文件 `{报告名}.human-reviews.json`（`HumanReviewStore`） |

注意：**落盘评测报告（`EvalReport`）本身没有 `humanAgreement` 字段**，其 `config.kappa` 在构建时通常为 null
（`EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE`）——三处都没有就是"未计算"，脚本打印提示并**退出 2，不伪造数字**。

判定规则（κ 定义见 `JudgeHumanAgreement.kappa`，:191-200）：

- `cohensKappa >= --min-kappa`（默认 0.6，与 `RagEvalProperties.HumanAgreement.minKappa` 同源）→ 退出 0；
- `cohensKappa < --min-kappa` → 退出 1，提示看 `disagreementCaseIds`；
- `cohensKappa` 为 null（可比样本为 0，或两边判对比例乘积为 1 导致 κ 无定义）或整个 humanAgreement 缺失 → 退出 2 "未计算"；
- κ 恰好等于阈值不算低于。

## 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 成功（export 完成 / agreement 达标 / import 成功 / dry-run 计划可行 / self-test 通过） |
| 1 | 操作性失败（agreement κ 低于阈值；import 登录失败、服务端拒绝、401 重登后仍失败） |
| 2 | 环境/用法错误（文件不可读或非法 JSON、报告缺 runId/cases、复核文件缺 verdict 或字段不合法、三级凭据都拿不到、后端不可达、humanAgreement 未计算） |

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `EVAL_BASE_URL` | `http://localhost:8081` | 后端地址 |
| `EVAL_ADMIN_TOKEN` | （空） | 一级凭据：现成的管理员 token |
| `EVAL_ADMIN_USERNAME` / `EVAL_ADMIN_PASSWORD` | （空） | 二级凭据：登录取 token |
| `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD`（仓库根 `.env`） | — | 三级凭据；脚本自读，不回显值 |
| `EVAL_ENV_FILE` | `<仓库根>/.env` | 三级凭据来源文件（便于在别的目录测试） |

## 失败排查

| 现象 | 退出码 | 原因与处理 |
| --- | --- | --- |
| `这些用例还没填 verdict: …` | 2 | 复核文件里 verdict 还是 null；逐条填 true/false 后再 import |
| `复核文件 runId=… 与 --run … 不一致` | 2 | 用错了轮次的复核文件；人工判定绑定该轮评委标签，换轮要重新 export |
| `score=…，须为 1-5 整数` | 2 | score 写成了 0/6/小数/字符串/布尔；改成 1-5 整数 |
| `拿不到管理员凭据，缺: …` | 2 | 按提示补三级之一；取 token 流程见 runbook 第 2 节 |
| `后端不可达` | 2 | 后端未启动；确认 8081 或设 `EVAL_BASE_URL` |
| `服务端返回 HTTP 401` | 1 | token 过期且无账密可重登，或账号非 ADMIN；见 runbook 第 5 节 |
| `服务端返回 HTTP 400 报告中不存在这些用例` | 1 | 复核文件的 caseId 不在该轮报告里（多半是拿错轮次文件） |
| `登录失败 HTTP 429` | 1 | 登录接口按 IP 限流，等窗口过去，不要连续重试 |
| `humanAgreement 未计算` | 2 | 还没有人工判定（κ 本就是 null）；先走完 export → import，再用状态 JSON 或旁路文件判定 |
| κ 低于阈值 | 1 | 评委需重新校准：看 `disagreementCaseIds` 补人工复核、调评分标准或换评委模型 |

## 口径说明

- `agreementRate` 与 κ 都来自后端 `JudgeHumanAgreement.compute`：只比较评委与人工**都**给出结论的用例
  （`comparableCases`），评委失败或双采样置空的用例算 `humanOnlyCases`，不进可比分母。
- 后端还有一致率下限（默认 0.8）与最小可比样本数（默认 5）两道门（`RagEvalProperties.HumanAgreement`），
  status 为 `INSUFFICIENT_SAMPLE` / `NEEDS_RECALIBRATION` 时会带 warning；本脚本只对 κ 阈值负责，
  发布判定以报告 `releaseEligible` 与 `eval_compare.py` 为准。
- 自检：`python3 -B scripts/eval_human_review.py --self-test`，内置 fixture 断言导出条数（6 条报告导出 3 条）、
  payload 字段序（与 `HumanReviewInput` 分量一致）、三处取数位置与 κ 判定（0.71→0、0.42→1、null/缺失→2），
  全程不发网络请求。
