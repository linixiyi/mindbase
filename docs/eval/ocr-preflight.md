# OCR 可用性预检（eval_ocr_preflight.py）

跑评测**之前**回答一个问题：这台机器的 OCR 链路能不能用？
07 扫描件的 6 题（`type=ocr`）此前因 OCR 未配置被标 `blocked` 且不计入任何分母
（见 [`src/main/resources/eval/eval-v2.jsonl`](../../src/main/resources/eval/eval-v2.jsonl)
头注释），但只有等评测报告出来才能发现；本脚本把这件事前移到提交评测之前
（spec 2026-11-10_fix-eval-and-runtime-gaps task-0022 / C22）。

本脚本**只读**配置与本地二进制：读进程环境变量、仓库根 `.env`（只解析 `KEY=VALUE`，
值一律不回显、不写日志）和 `which tesseract`；**不联网探测阿里云**，不要求 token，
不启动任何服务（真机评测的 token 三级获取见
[runbook](../../.spec/specs/2026-11-10_fix-eval-and-runtime-gaps/runbook.md) 第 2 节，
由 `eval_real_smoke.sh` 处理，与本脚本无关）。

## 用法与退出码

```bash
python3 scripts/eval_ocr_preflight.py                 # 等价 --check：输出 markdown 报告
python3 scripts/eval_ocr_preflight.py --check         # 同上；无可用引擎退出 0 但标 blocked
python3 scripts/eval_ocr_preflight.py --require-ocr   # 评测门禁：无可用 OCR 引擎退出 2
python3 scripts/eval_ocr_preflight.py --json          # 输出纯 JSON（可与 --check/--require-ocr 组合）
python3 scripts/eval_ocr_preflight.py --self-test     # 注入 env/which 桩断言 3 种组合；不读仓库数据文件
```

| 退出码 | 含义 |
| --- | --- |
| `0` | `--check` 正常完成（无引擎也算完成，结论里标 **blocked**）；或 `--self-test` 通过 |
| `2` | `--require-ocr` 且无可用引擎；或数据集文件级错误（不可读 / 行不可解析 / 零条用例，stderr 以 `eval_ocr_preflight:` 开头） |

`--dataset <path>` 可覆盖数据集路径（默认 `src/main/resources/eval/eval-v2.jsonl`）。

输出（markdown 与 JSON 两种形态，内容同源）：

- **可用引擎**：`aliyun` / `liteparse` 各自的可用性与判定条件；
- **缺失项**：逐条列出还差哪个变量/二进制（布尔开关值仅回显布尔样式值，防误配泄密）；
- **受影响题数**：数据集 `type=ocr` 的用例数（任务卡约定口径），并对照 `status=blocked`
  条数——两者不一致时在 notes 里报出（数据集头注释约定 ocr 必为 blocked）；
- **建议命令**：按缺失项给出修复命令，密钥一律用 `<...>` 占位符，真实值不进脚本与文档。

## 两套 OCR 配置

### 路径 1：阿里云 OCR

| 环境变量 | 语义 | 默认 |
| --- | --- | --- |
| `ALIYUN_OCR_ENABLED` | 总开关，`true` 才启用 | `false`（[application.yml:243](../../src/main/resources/application.yml)） |
| `ALIYUN_OCR_ACCESS_KEY_ID` | AccessKey ID，非空 | 无；回退 `ALIBABA_CLOUD_ACCESS_KEY_ID`（application.yml:245） |
| `ALIYUN_OCR_ACCESS_KEY_SECRET` | AccessKey Secret，非空 | 无；回退 `ALIBABA_CLOUD_ACCESS_KEY_SECRET`（application.yml:246） |

预检判定与后端一致：可用 = `enabled` 且 AccessKey ID/Secret 均非空
（对应 `AliyunOcrService.isEnabled()/isConfigured()`，`AliyunOcrService.java:123-129`）。
预检只看配置，不发请求验证密钥有效性——密钥错了要等真机评测才会暴露。

### 路径 2：本地 LiteParse（tesseract）

| 环境变量 / 二进制 | 语义 | 默认 |
| --- | --- | --- |
| `FILE_PARSING_LITEPARSE_OCR_ENABLED` | LiteParse OCR 开关 | `true`（application.yml:233；未设置按默认视为开启，预检不把它算缺失项，只在 notes 说明） |
| `tesseract` 在 PATH | 本地 OCR 引擎（阿里云未启用时 `lit` 走本地 OCR） | 必须安装 |
| `chi_sim` 语言包 | 后端默认 `ocr-language: chi_sim+eng`（application.yml:234） | 必须安装 |

链路依据：`ParseService.java:409-417` —— `ocr-enabled=false` 时给 `lit` 传 `--no-ocr`；
`aliyun.ocr.enabled=true` 时通过 `--ocr-server-url` 把 OCR 回调指到后端内部接口；
否则由 `lit` 在本地做 OCR（即依赖 tesseract）。

安装（按平台二选一，预检的建议命令会自动给出对应提示）：

```bash
# macOS
brew install tesseract tesseract-lang          # tesseract-lang 含 chi_sim
# Linux(Debian/Ubuntu)
sudo apt-get install tesseract-ocr tesseract-ocr-chi-sim
# 验证中文包
tesseract --list-langs | grep chi_sim
```

配置来源优先级（两条路径相同）：进程环境变量 → 仓库根 `.env`；每层取 strip 后非空的值，
空串与未设置同等对待；`"true"` 仅接受大小写不敏感的字面 `true`。
预检默认读仓库根 `.env`（脚本按自身位置推导，无需传参），值永不回显。

## blocked 题为什么不能进分母

数据集头注释与 F3 决策（pick a）：07（`07-os-filesystem-ppt-zh.pdf`，中文扫描 PPT）
在无 OCR 链路时抽取仅得约 49 字符/页的图示碎片，无法稳定分块检索。如果把这 6 题
计入分母：

1. 检索没有可用文本，6 题必然空召回/低覆盖——覆盖率与错误率的数字是 **OCR 缺配置**
   的投影，不是 RAG 质量的度量；
2. 它们会与"真检索退化"混在一起，阈值门禁（如 real-smoke 的
   `fullCoverageHitRate >= 0.5`）无法区分两者，任何一侧的退化都会被稀释；
3. 分母口径（126 = 120 计数 + 6 blocked）就不再成立，与历史轮次不可比。

因此数据集给这 6 题显式标 `type=ocr` + `status=blocked`，评测不计入任何分母，报告必须
显式标注（runbook 第 4 节），不允许从分母里悄悄消失。OCR 配好后由数据集/产品决策
解除 blocked 标注再跑全量；本脚本只报告预检状态，不改数据集。

## 与评测链路的关系

- `scripts/eval_gate.sh`（离线门禁）：桩数据，不受本机 OCR 影响，无需预检；
- `bash scripts/eval_real_smoke.sh --dry-run`（真机前置静态检查）：不校验 OCR，
  建议真跑前先跑 `python3 scripts/eval_ocr_preflight.py`；
- `--require-ocr` 适合接进门禁：当前机器两引擎均不可用时退出 2；
- `--self-test` 只用注入的 env/which 桩与临时 fixture，不读仓库数据文件、不读真实
  `.env`、不发网络，可在任何环境独立跑。
