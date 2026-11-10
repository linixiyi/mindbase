# 真机评测冒烟（eval_real_smoke.sh）

一条命令对真机发起一次 RAG 评测并按阈值判定。离线门禁（`scripts/eval_gate.sh`）跑的是 Mockito 桩数据，
抓不到真实检索退化；本脚本是对真机的冒烟补充。动手前先读
[`.spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md`](../../.spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md)
（服务启动顺序、token 三级获取、评测端点、常见失败）与 [docs/runbook.md](../runbook.md)。

## 两种模式

```bash
bash scripts/eval_real_smoke.sh --dry-run   # 默认：只读静态检查，不需要服务在跑、不要求 token
bash scripts/eval_real_smoke.sh --run       # 真跑：提交评测、轮询到终态、断言阈值
```

### --dry-run（默认）

只做只读静态检查，任何一项缺失即退出 2 并指明缺哪个：

| 检查项 | 说明 |
| --- | --- |
| curl 存在 | 真跑模式调用后端 API 依赖 |
| python3 存在 | JSON 解析与阈值断言依赖 |
| 数据集可读 | 默认 `classpath:eval/eval-v2.jsonl`，本地文件为 `src/main/resources/eval/eval-v2.jsonl` |
| 报告目录可创建 | 默认 `eval-reports/`，后端首次写报告时自动创建（`RagEvalService.writeReport` 的 `Files.createDirectories`） |
| `/actuator/health` 软检查 | 不可达只 WARN 不失败（dry-run 允许在无服务环境独立退出 0） |

`EVAL_BASE_URL` 未设置时缺省 `http://localhost:8081`，只提示不算缺失。

### --run（真跑）

1. **取管理员 token**（三级顺序，token 只进内存，不写文件、不进日志）：
   1. 环境变量 `EVAL_ADMIN_TOKEN`；
   2. 环境变量 `EVAL_ADMIN_USERNAME` + `EVAL_ADMIN_PASSWORD`（`POST /api/v1/users/login`，读 `data.token`）；
   3. 仓库根 `.env` 的 `ADMIN_BOOTSTRAP_USERNAME` + `ADMIN_BOOTSTRAP_PASSWORD`。
   都拿不到 → 退出 2，打印缺的是哪个变量，指向本文件与 runbook。
2. **提交**：`POST /api/v1/admin/eval/run`，body `{"datasetPath":"classpath:eval/eval-v2.jsonl","topK":5,"label":"real-smoke-<ts>"}`。
3. **轮询**：`GET /api/v1/admin/eval/runs/{id}`，默认每 15s 一次、30 分钟超时；`SUCCEEDED`/`COMPLETED` 为成功终态，`FAILED`/`CANCELLED` 为失败终态（后端 `RunState` 枚举实际是 `RUNNING/SUCCEEDED/FAILED`，脚本两种写法都接受）。
4. **断言**：取 `GET /runs/{id}/report`，要求 `summary.retrievalErrorRate <= 0.1`（`EVAL_MAX_ERROR_RATE` 可覆盖）且 `summary.fullCoverageHitRate >= 0.5`（`EVAL_MIN_COVERAGE` 可覆盖）；任一不满足（含指标为 null 无法判定）退出 1。

退出码：`0` 通过；`1` 真跑失败（登录失败、提交失败、终态失败、轮询超时、阈值不满足）；`2` 前置缺失（静态项或凭据）。

401 时脚本会重新登录一次并重试；仍 401 或根本没有用户名/密码可登录时退出 1 并指向 runbook。

## 前置条件

- 后端从仓库根启动（`.env` 按 CWD 读取）：`mvn spring-boot:run -DskipTests`
- Elasticsearch `localhost:9200`、MinIO `localhost:19000`（bucket `uploads` 已建）、Kafka、Redis、MySQL——启动方式见 docs/runbook.md
- 管理员账号 `role=ADMIN`；密码 ≥ 12 位；`ADMIN_BOOTSTRAP_ENABLED=true` 只在首次建号时打开，建完改回 `false`
- token 是约 10 分钟的短效 access token；脚本每次运行都现取，不缓存

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `EVAL_BASE_URL` | `http://localhost:8081` | 后端地址 |
| `EVAL_ADMIN_TOKEN` | （空） | 一级凭据：现成的管理员 token |
| `EVAL_ADMIN_USERNAME` / `EVAL_ADMIN_PASSWORD` | （空） | 二级凭据：登录取 token |
| `ADMIN_BOOTSTRAP_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD`（仓库根 `.env`） | — | 三级凭据；脚本自读，不回显值 |
| `EVAL_MAX_ERROR_RATE` | `0.1` | retrievalErrorRate 上限 |
| `EVAL_MIN_COVERAGE` | `0.5` | fullCoverageHitRate 下限 |
| `EVAL_DATASET_PATH` | `classpath:eval/eval-v2.jsonl` | 提交的 datasetPath；裸文件名按 `eval-datasets/` 下解析 |
| `EVAL_TOP_K` | `5` | 提交的 topK |
| `EVAL_POLL_INTERVAL_SECONDS` | `15` | 轮询间隔 |
| `EVAL_POLL_TIMEOUT_SECONDS` | `1800` | 轮询超时（评测整轮 12–20 分钟，别设太小） |
| `EVAL_OUTPUT_DIR` | `eval-reports` | 与 `application.yml` 的 `eval.output-dir` 同源，用于 dry-run 可写检查 |
| `EVAL_ENV_FILE` | `<仓库根>/.env` | 三级凭据来源文件（便于在别的目录测试） |

## datasetPath 的坑（与后端代码对齐）

`RagEvalService.loadDataset` 只把 **`classpath:eval/` 前缀**的路径当 classpath 资源
（`CLASSPATH_PREFIX = "classpath:eval/"`），裸文件名一律解析到 `eval.dataset-dir`（默认 `eval-datasets/`）下。
`eval-datasets/eval-v2.jsonl` 并不存在，所以写 `"datasetPath":"eval-v2.jsonl"` 会提交 404；
脚本默认提交 `classpath:eval/eval-v2.jsonl`（对应 `src/main/resources/eval/eval-v2.jsonl`）。

## 失败排查

| 现象 | 退出码 | 原因与处理 |
| --- | --- | --- |
| `缺少静态项: curl/python3/数据集/报告目录` | 2 | 按提示补齐；数据集路径注意 classpath 前缀（见上节） |
| `缺少管理员凭据` + 变量清单 | 2 | 三级顺序逐一检查；`.env` 只支持 `KEY=VALUE` 与成对引号，复杂值建议直接用 `EVAL_ADMIN_USERNAME/PASSWORD` |
| `登录失败 HTTP 401` | 1 | 用户名或密码不对；确认账号存在且 `role=ADMIN` |
| `登录失败 HTTP 429` | 1 | 登录接口按 IP 限流，等窗口过去，不要连续重试 |
| `提交评测 403` | 1 | 用了普通用户 token；`/api/v1/admin/**` 要求 `ROLE_ADMIN` |
| `提交评测失败 HTTP 400` | 1 | 检查 datasetPath（404 时也走这里）与 topK 范围（≤ `eval.max-top-k`=20） |
| `评测终态 FAILED` | 1 | 看 status message；常见：ES 未启动、MinIO/bucket 缺失、模型 key 无效 |
| `轮询超时` | 1 | 126 题整轮 12–20 分钟；确认 `EVAL_POLL_TIMEOUT_SECONDS` 足够，再手动查 `GET /runs/{id}` |
| `retrievalErrorRate/fullCoverageHitRate 为 null` | 1 | 报告没有可计算的样本（如数据集没标 expectedEvidence）；冒烟不允许不可判定的通过 |
| 检索全空（error rate 1.0 或 coverage 0） | 1 | 先看 `/actuator/health`，再查报告 `retrievalMode` 与逐例 `score`；ES 未启动/文档未索引/阈值过滤 |

## 已知口径说明

- 07 扫描件的 6 题因 OCR 未配置是 `blocked`，不计入分母；报告会显式标注，不会从分母里悄悄消失（见 F3 决议）。
- 判定用点估计阈值；报告本身还带 Wilson 区间与 `releaseEligible`，发布判定以 `eval_compare.py`/报告结论为准，本脚本只做冒烟。
