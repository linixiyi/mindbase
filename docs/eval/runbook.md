# 真机评测 Runbook（eval-real-run 包执行用）

> 服务启动顺序、管理员 token 获取、评测端点与常见失败。服务级启动细节与 MinIO 镜像坑见 [docs/runbook.md](../runbook.md)。
> 本文件只写变量名，**不写任何真实密码、token、API key**。

## 1. 需要哪些服务

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| 后端 | `http://localhost:8081` | 必须从**仓库根**（工作树根）启动：`./mvnw spring-boot:run -DskipTests`，`.env` 按 CWD 读取 |
| Elasticsearch | `http://localhost:9200` | 本地容器是 http（`.env` 须有 `SPRING_PROFILES_ACTIVE=dev`，否则按 base 配置走 https+自签）；脚本直连按 `ELASTICSEARCH_*` 环境变量（缺省 `elastic`/空），`https` 时允许不校验自签证书且只读 |
| MinIO | `http://localhost:19000` | bucket `uploads` 需已存在 |
| Kafka | `localhost:9092` | 合并后经 outbox 发 Kafka 触发解析 |
| Redis | `localhost:6379` | 缓存/会话 |
| MySQL | `localhost:3306` | 本机原生（brew `mysql@8.0`），库 `PaiSmart` |
| 前端 | — | 评测不需要 |

## 2. 工作树注意（eval-real-run 包新增）

本包在 `.spec/worktrees/2026-11-12_eval-real-run` 工作树执行：

- 后端必须从**工作树根**启动（`.env` 按 CWD 读取，工作树根需有 `.env` 副本，从主工作区拷贝）。
- 语料 PDF 不入库（`.gitignore` 忽略 `*.pdf`）：新工作树的 `eval-datasets/corpus/` 只有 README，需先从本地原始位置把 10 篇 PDF 硬链接回来，再跑 `scripts/eval_corpus_ingest.py --dry-run` 校验（见 [corpus-ingest.md](corpus-ingest.md)）。
- ES/MinIO/Kafka/Redis 与主工作区共享同一套 docker 实例；MySQL 共享本机实例。
- 入库与评测产生的报告写在**工作树**的 `eval-reports/`（git 忽略）。

## 3. 管理员 token 从哪来

评测接口统一在 `/api/v1/admin/eval/**`，上传接口在 `/api/v1/upload/**`，按以下优先级自动获取，**不要求用户手工粘贴**：

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
- access token 短效 **1 小时**（`JwtUtils.EXPIRATION_TIME = 3600000`），入库 + 评测整轮可能跨有效期——`eval_real_smoke.sh` / `eval_corpus_ingest.py` 已内置 401 自动重登一次；手工操作时重新登录现取即可，不要长期缓存。
- token 只放 shell 变量或进程内存，不写文件、不进日志、不打印到终端历史。

## 4. 上传与评测端点

- 分片上传：`POST /api/v1/upload/chunk`（5MB 分片，字段 fileMd5/chunkIndex/totalSize/fileName/totalChunks/isPublic=true）→ `POST /api/v1/upload/merge`（JSON `{fileMd5,fileName}`），合并后经 outbox 发 Kafka 异步解析。逐篇上传/等待入库由 `scripts/eval_corpus_ingest.py` 完成（dry-run/续传/单篇重试见 [corpus-ingest.md](corpus-ingest.md)）；脚本等入库判据是 ES `POST {index}/_count`（`term fileMd5` >0），索引默认别名 `knowledge_base`（`EVAL_ES_INDEX` 可覆盖）。
- 提交评测：`POST /api/v1/admin/eval/run`，body `{"datasetPath":"eval-v2.jsonl","topK":5,"label":"..."}`（`datasetPath` 与 `jsonl` 只给一个）。
- 轮询：`GET /api/v1/admin/eval/runs/{id}` 到 `COMPLETED/FAILED/CANCELLED`；报告：`GET /runs/{id}/report`，也可直接读 `eval-reports/` 下 JSON（`reportPath` 在 run status 里返回）。
- 小跑预热（可选）：启动后端时设 `EVAL_MAX_CASES=20`，完整跑前**去掉该变量重启后端**再跑 120 题。

## 5. 通过标准（脚本默认值，可用环境变量覆盖）

- `retrievalErrorRate <= 0.1`（`EVAL_MAX_ERROR_RATE`）
- `fullCoverageHitRate >= 0.5`（`EVAL_MIN_COVERAGE`）
- 报告写入 `eval-reports/`（git 忽略）；压缩摘要才进 `docs/eval/reports/`。
- 07 扫描件的 6 题 OCR blocked，不计入分母；本包 task-0004 后 neg 题同样退出检索指标分母。
- `releaseEligible=false` 是首跑必然结果（SELF_JUDGE + 无人工复核 + 未配价）；发布结论以"报告 + sidecar"有效口径计算（见 `scripts/eval_release_preflight.py`）。

## 6. 常见失败

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 401 | token 过期或不是管理员 | 重新登录；确认用户 `role=ADMIN` |
| 403 | 用了普通用户 token | 换管理员账号 |
| 429 | 登录接口按 IP 限流 | 等窗口过去，别连续重试 |
| 提交 400 | `datasetPath` 与 `jsonl` 同时给或都没给 | 只给一个 |
| 检索全空 | ES 未启动 / 文档未索引 / 阈值过滤 | 先看 `/actuator/health`，再跑 `scripts/eval_corpus_readiness.py --check` |
| 上传失败 | MinIO 未起或 `uploads` bucket 不存在 | 按 docs/runbook.md 建 bucket |
| 后端起不来 | admin 密码弱口令 / `.env` 缺失 / 端口占用 | 看启动日志；确认从工作树根启动 |
