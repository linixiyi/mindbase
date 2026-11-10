# 真机评测 Runbook（task-0004 自读）

> 执行 `task-0004` 前先读这一页。目标：不靠人工临时 export，也能把真机评测跑起来。
> 本文件只写变量名，**不写任何真实密码、token、API key**。

## 1. 需要哪些服务

| 组件 | 地址 | 启动方式 |
| --- | --- | --- |
| 后端 | `http://localhost:8081` | 仓库根执行 `mvn spring-boot:run -DskipTests`（必须从仓库根启动，`.env` 按 CWD 读取） |
| Elasticsearch | `http://localhost:9200` | `cd docs && docker compose up -d es` |
| MinIO | `http://localhost:19000` | 同上，把服务名换成 `minio`；bucket `uploads` 需已存在 |
| Kafka | `localhost:9092` | 同上，服务名 `kafka` |
| Redis | `localhost:6379` | 同上，服务名 `redis` |
| MySQL | `localhost:3306` | 本机原生（brew `mysql@8.0`），库 `PaiSmart` |
| 前端 | `http://localhost:9527` | 评测不需要，只在人工看页面时启动 |

更完整的启动说明、MinIO 镜像坑、admin 密码长度要求见仓库已有的 [docs/runbook.md](../../../../docs/runbook.md)。

## 2. 管理员 token 从哪来

评测接口统一在 `/api/v1/admin/eval/**`，SecurityConfig 要求 `ROLE_ADMIN`，所以必须用管理员 token。

按以下优先级自动获取，**不要要求用户手工粘贴**：

1. 环境变量 `EVAL_ADMIN_TOKEN`（已有人工准备好的 token 时直接用）。
2. 环境变量 `EVAL_ADMIN_USERNAME` + `EVAL_ADMIN_PASSWORD`。
3. 仓库根 `.env` 里的 `ADMIN_BOOTSTRAP_USERNAME` + `ADMIN_BOOTSTRAP_PASSWORD`（自读，不要 echo 值）。

登录接口是 `POST /api/v1/users/login`（不是 `/api/v1/auth/login`，后者只有 refresh）：

```bash
BASE="${EVAL_BASE_URL:-http://localhost:8081}"
TOKEN=$(curl -s -X POST "$BASE/api/v1/users/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${EVAL_ADMIN_USERNAME}\",\"password\":\"${EVAL_ADMIN_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
```

注意事项：

- `ADMIN_BOOTSTRAP_ENABLED=true` 只在首次建号时打开，建完要改回 `false`；管理员已存在时重复打开不会重建。
- 管理员密码要求 ≥ 12 位，弱口令会让后端启动失败。
- token 是 10 分钟左右的短效 access token，评测整轮 12–20 分钟，**提交前现取**，不要提前缓存。
- token 只放 shell 变量或进程内存，不写文件、不进日志、不打印到终端历史。

## 3. 提交与轮询

数据集用 classpath 名即可（`eval-v2.jsonl` 对应 `src/main/resources/eval/eval-v2.jsonl`）：

```bash
RUN_ID=$(curl -s -X POST "$BASE/api/v1/admin/eval/run" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"datasetPath":"eval-v2.jsonl","topK":5,"label":"real-smoke-'"$(date +%s)"'"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["runId"])')

while :; do
  STATE=$(curl -s "$BASE/api/v1/admin/eval/runs/$RUN_ID" -H "Authorization: Bearer $TOKEN" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["state"])')
  case "$STATE" in COMPLETED|FAILED|CANCELLED) break;; esac
  sleep 15
done
curl -s "$BASE/api/v1/admin/eval/runs/$RUN_ID/report" -H "Authorization: Bearer $TOKEN" > "/tmp/eval-$RUN_ID.json"
```

相关端点：`GET /runs`、`GET /runs/latest`、`GET /runs/{id}`、`GET /runs/{id}/report`、人工判定 `POST/GET /runs/{id}/human-reviews`。

## 4. 通过标准（脚本默认值，可用环境变量覆盖）

- `retrievalErrorRate <= 0.1`（`EVAL_MAX_ERROR_RATE`）
- `fullCoverageHitRate >= 0.5`（`EVAL_MIN_COVERAGE`）
- 报告写入仓库 `eval-reports/`；`reportPath` 在 run status 里返回。
- 07 扫描件的 6 题当前因 OCR 未配置是 `blocked`，不计入分母，报告里必须显式标注。

## 5. 常见失败

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 401 | token 过期或不是管理员 | 重新登录；确认用户 `role=ADMIN` |
| 403 | 用了普通用户 token | 换管理员账号 |
| 429 | 登录接口按 IP 限流 | 等窗口过去，别连续重试 |
| 提交 400 | `datasetPath` 与 `jsonl` 同时给或都没给 | 只给一个 |
| 检索全空 | ES 未启动 / 文档未索引 / 阈值过滤 | 先看 `/actuator/health`，再查报告 `retrievalMode` 与 `score` |
| 上传失败 | MinIO 未起或 `uploads` bucket 不存在 | 按 docs/runbook.md 建 bucket |

## 6. 脚本没有环境时怎么办

`bash scripts/eval_real_smoke.sh --dry-run` 只做静态检查，不需要真机、不需要 token，必须能独立退出 0。
真跑模式拿不到 token 时退出 2，并打印缺的是 `EVAL_ADMIN_TOKEN` 还是账号密码变量，同时指向本文件。
