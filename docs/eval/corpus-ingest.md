# 语料入库：eval_corpus_ingest.py 使用说明

> 把 [manifest](../../eval-datasets/manifest.json) 里的 10 篇评测 PDF 上传进知识库：
> 5MB 分片上传 → merge → 轮询 ES 等分块数 >0。服务启动、管理员凭据、端点总览见
> [runbook](runbook.md)；本文只讲入库脚本本身。**任何 token/密码只进环境变量与进程内存，不写进文件、不进日志。**

## 1. 前置

1. **服务**：按 [runbook](runbook.md) 启动后端（从仓库/工作树根启动，`.env` 按 CWD 读取），
   `curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health` 返回 200；
   MinIO（bucket `uploads` 必须已建）、Kafka、Redis、ES 均在跑。合并后经 outbox 发 Kafka 异步解析，缺一不可。
2. **语料 PDF 就位**：`eval-datasets/corpus/` 的 PDF 不入库（`.gitignore` 忽略 `*.pdf`），
   新 clone/新工作树里该目录只有 README——需要按 [corpus README](../../eval-datasets/corpus/README.md)
   从本地原始位置把 10 篇 PDF 硬链接进来（`ln <原始路径>/<文件>.pdf eval-datasets/corpus/`）。
   先跑 `--dry-run` 确认 10 篇的 md5/大小与 manifest 一致。
3. **管理员凭据**（三级顺序，脚本自动取，与 `eval_real_smoke.sh` 同口径）：
   1. 环境变量 `EVAL_ADMIN_TOKEN`（现成 token 直接用）；
   2. 环境变量 `EVAL_ADMIN_USERNAME` + `EVAL_ADMIN_PASSWORD`；
   3. 仓库/工作树根 `.env` 的 `ADMIN_BOOTSTRAP_USERNAME` + `ADMIN_BOOTSTRAP_PASSWORD`（脚本自读，不回显）。
   三级都不可用时报缺哪个变量名并退出 2。账号必须 `role=ADMIN`（非管理员有组织上传大小限制）。
4. **ES 直连**（轮询分块数用，进程环境优先、`.env` 兜底）：
   `ELASTICSEARCH_SCHEME/HOST/PORT/USERNAME/PASSWORD`，缺省 `http`/`localhost`/`9200`/`elastic`/空；
   `https` 时按自签证书处理（不校验证书，只读 `_count`）。索引/别名默认 `knowledge_base`，可用 `EVAL_ES_INDEX` 覆盖。

## 2. 命令

```bash
cd <仓库/工作树根>

# 0) 离线自检（不联网；md5 校验、5MB 分片边界、manifest 缺文件、merge 解析、ES 轮询状态机）
python3 -B scripts/eval_corpus_ingest.py --self-test

# 1) 只读校验：打印「文件 → md5 → 分片数」，校验 manifest 与本地文件（不联网、不要求 token）
python3 -B scripts/eval_corpus_ingest.py --dry-run

# 2) 真跑入库（10 篇约 100MB；解析 10–30 分钟，脚本会逐篇等到 ES 有分块才继续）
python3 -B scripts/eval_corpus_ingest.py --run

# 3) 断点续传：已入库的自动跳过；未入库的只补传缺失分片
python3 -B scripts/eval_corpus_ingest.py --run --resume

# 4) 单篇重试（精确文件名或唯一前缀）
python3 -B scripts/eval_corpus_ingest.py --run --resume --only 04-tactile-force-chen-2025.pdf
```

退出码：`0` 成功；`1` 操作失败（登录失败、分片/merge 失败、等待入库超时，失败文件逐条列出）；
`2` 静态/配置问题（manifest 校验失败、`--only` 无匹配、缺凭据、ES 配置非法）。

可选参数与环境变量：

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `--manifest` | `eval-datasets/manifest.json` | 清单路径 |
| `--corpus-dir` | `eval-datasets/corpus` | 语料目录（工作树里先放 PDF 再跑） |
| `--env-file` | `EVAL_ENV_FILE` 或仓库根 `.env` | 三级凭据与 ELASTICSEARCH_* 的兜底来源 |
| `--base-url` | `EVAL_BASE_URL` 或 `http://localhost:8081` | 后端地址 |
| `EVAL_ES_INDEX` | `knowledge_base` | 轮询用索引/别名（后端业务别名，见 `KnowledgeIndexNames.ALIAS`） |
| `EVAL_ES_POLL_INTERVAL_SECONDS` | `5` | ES 轮询间隔 |
| `EVAL_ES_POLL_TIMEOUT_SECONDS` | `900` | 单篇等待入库上限（大 PDF/Kafka 积压时可调大） |

## 3. 幂等与断点续传语义

- **已入库直接跳过（总是生效）**：`--run` 每篇先查 ES 该 md5 分块数，>0 即视为已入库，跳过不重灌。
  重跑同一命令是安全的（「同 md5 已入库不重灌」）。
- **`--resume`**：对未入库的文件先查 `GET /api/v1/upload/status?file_md5=<md5>`，
  只补传缺失分片；不加 `--resume` 时全量重传——后端对重复分片按幂等成功处理（Redis bitmap + chunk_info + MinIO 三者齐全即跳过），结果一致，只是多耗带宽。
- **合并幂等**：`POST /api/v1/upload/merge` 对已 `COMPLETED` 的文件返回 200「文件已完成合并」；对
  `MERGING` 返回 409（脚本自动重试 3 次）。
- **COMPLETED 后再传分片**：后端拒绝并返回 409「文件已完成合并，不允许继续上传分片」——脚本把它识别为
  幂等信号，直接走 merge → ES 轮询。
- **单篇失败不影响其他文件**：失败不删除任何数据，继续下一篇，最后统一列出失败清单；重跑加 `--resume`
  只补缺，单篇重试加 `--only <文件名>`。
- **401 重登一次**：token 短效 1 小时（`JwtUtils.EXPIRATION_TIME`），整轮入库可能跨有效期；任何请求 401
  时脚本用三级凭据里的用户名/密码重登一次再重试。token 直接来自 `EVAL_ADMIN_TOKEN`（无密码）时无法重登，
  报错退出。

## 4. 失败排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `--dry-run` 报「本地缺文件」 | corpus 目录是 git 忽略的，新工作树没放 PDF | 按 §1.2 硬链接回来 |
| `--dry-run` 报 md5/大小不一致 | 语料文件被改动/损坏 | 恢复原始 PDF，勿改 manifest 凑数 |
| 报「缺少管理员凭据」 | 三级凭据都没配 | 按 §1.3 配任一级；报错里列了缺哪个变量名 |
| 登录 401 | 用户名/密码不对或 `role!=ADMIN` | 确认管理员账号；见 [runbook §6](runbook.md) |
| 登录 429 | 登录接口按 IP 限流 | 等窗口过去再跑，不要连续重试 |
| 分片 400「文件类型验证失败」 | 文件名/类型不在白名单 | 语料全是 `.pdf`，一般不出现；检查 `--corpus-dir` 是否指错目录 |
| 分片/merge 连接失败 | 后端没起或端口不对 | `/actuator/health` 先行；`--base-url`/`EVAL_BASE_URL` 对齐 |
| merge 400「文件分片未全部上传」 | 有分片传输失败未补齐 | `--run --resume` 补传缺失分片后再 merge |
| 等待入库超时且 ES 分块一直 0 | Kafka/解析 worker 停了，或 ES 数据被清 | 看后端日志 `file-processing` topic 消费；若后端记录 COMPLETED 但 ES 为 0（脚本会在失败原因里提示），属数据层不一致，人工处理，不要反复重灌 |
| 07 扫描件长时间不入库或分块极少 | 07 是图片型 PPT，几乎无可提取文本，走 OCR 链路 | 属已知限制（见 [ocr-preflight](ocr-preflight.md)）；该篇失败不影响其他 9 篇 |
| ES 连续异常/索引不存在 | ES 未启动、别名不对或凭据缺失 | `EVAL_ES_INDEX` 检查别名；`ELASTICSEARCH_USERNAME/PASSWORD` 缺失时先补（脚本默认 `elastic`/空，不猜密码） |

入库是否真的完成的独立判据是 task-0002 的就绪检查：

```bash
python3 -B scripts/eval_corpus_readiness.py --check   # 10/10 文件 ES 分块数 ≥1 才退出 0
```
