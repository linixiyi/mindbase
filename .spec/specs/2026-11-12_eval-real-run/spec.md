# 首次真机评测：语料入库、指标口径与首份 v2 报告

<!-- spec-format: 3 -->

## Goal
- goal: 把"126 题评测只能在 fixture 上跑"推进到"10 篇真实 PDF 入库、检索指标口径正确、首份 v2 报告落盘且可复核"。
- literal ask: "补语料入库脚本 + 真机跑一轮……配置独立评委 + 收 5 条以上人工复核……修 neg 的分母口径 + 入库后回填 expectedChunkIds……这些能不能建 /spec 包 让 zcode 改"（m-2026-11-12）
- done means: 8 张卡全部勾选；语料入库脚本、就绪检查、chunkId 回填、neg 口径、发布预检、报告摘要脚本全部由测试守护；zcode 在本机跑通一次真机评测并把压缩摘要写进 `docs/eval/reports/v2-first-summary.json`；不把 SELF_JUDGE 的分数说成发布结论。

## Context
2026-11-12 复核确认：工具链已完成（R3a 25/25、R4 13/13 已合并），但评测目标没有闭环。

1. **语料从未入库**：MySQL `file_upload` 只有 1 行（2026-10-20 的 `mindbase-e2e-test.md`，1.8KB），`chunk_info` 为 0；ES 里只有这份旧文档的 2 个分块；`eval-reports/` 不存在。
2. **没有入库脚本**：`scripts/eval_real_smoke.sh` 只负责提交评测，[real-smoke.md](docs/eval/real-smoke.md) 的失败表写了"文档未索引"，但没有任何步骤或脚本说明怎么把 [corpus](eval-datasets/corpus/README.md) 的 10 篇 PDF 上传、合并、等待解析完成。
3. **neg 污染检索分母**：数据集 17 条 `expectedRefusal=true` 的题带 `expectedEvidence`（用于定位"论文没提这个信息"的锚点），[EvalReportAggregator.java:90](src/main/java/com/mindbase/service/eval/EvalReportAggregator.java#L90) 按"有 evidence"计入 `evidenceLabeled`，没有排除 neg；`eval_dataset_audit.py` 自己也对这 17 条报 warn。
4. **分块级 gold 为空**：`expectedChunkIds` 0/126；task-0003 只补了文件 md5，分块级命中仍回退短语匹配。
5. **报告不可发布**：`eval.judge.provider` 未配置（SELF_JUDGE），没有人工复核（κ 未计算），`eval.pricing.*` 未配置（cost=null）；`releaseEligible=false` 是必然结果。人工判定按设计写在报告旁的 sidecar（`*.human-reviews.json`），**报告文件本身冻结不改**；因此发布结论必须按"报告 + sidecar"的有效口径计算，不能只看报告里的旧字段。
6. **缺报告验收工具**：没有脚本校验"这份 v2 报告是否真的跑了 120 题、覆盖率是否非零、release 阻断原因是否记录"。

## Charter
- V1 priority: quality > maintainability > speed > cost | charter.md
- V2 scope: adjacent-fixes | charter.md
- V3 verification: package | charter.md
- V4 finish: merge | charter.md
- V5 dependencies: none-new | charter.md
- V6 compatibility: keep-public-api | charter.md
- V7 blocked: park-and-continue | charter.md

## Scope
- in: scripts/**, docs/eval/**, eval-datasets/**, src/main/java/com/mindbase/service/eval/**, src/test/java/com/mindbase/service/eval/**, src/main/resources/eval/**, README.md
- out: frontend/**, .github/**, pom.xml, src/main/resources/application*.yml, .env 与任何密钥、.spec/**（包目录除外）、生产部署脚本

## Assumptions
- A1 | 知识库里只有 1 个旧测试文档 | probe: `mysql --protocol=tcp -uroot PaiSmart -e 'select count(*) from file_upload'` | CONFIRMED
- A2 | ES 只有 2 个分块 | probe: `curl -s -u "$ELASTICSEARCH_USERNAME:$ELASTICSEARCH_PASSWORD" 'http://localhost:9200/knowledge_base_*/_count'` | CONFIRMED
- A3 | 数据集的 expectedChunkIds 为 0 | probe: `python3 -c "import json;rows=[json.loads(l) for l in open('src/main/resources/eval/eval-v2.jsonl') if l.strip() and not l.startswith('#')];print(sum(1 for r in rows if r.get('expectedChunkIds')),'/',len(rows))"` | CONFIRMED
- A4 | 17 条 neg 题带 expectedEvidence | probe: `python3 scripts/eval_dataset_audit.py --check`（warn 17） | CONFIRMED
- A5 | eval.judge.provider 未配置，评委是 SELF_JUDGE | probe: `grep -n 'judge:' -A4 src/main/resources/application.yml` | CONFIRMED
- A6 | 没有真机 v2 报告 | probe: `ls eval-reports/*.json 2>/dev/null | wc -l` | CONFIRMED
- A7 | 后端当前未启动 | probe: `curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health`（000） | CONFIRMED
- A8 | 上传是 5MB 分片 + `POST /api/v1/upload/merge`，合并后经 outbox 发 Kafka | probe: `grep -n 'DEFAULT_CHUNK_SIZE_BYTES\|enqueueVectorization' src/main/java/com/mindbase/controller/UploadController.java src/main/java/com/mindbase/service/UploadService.java` | CONFIRMED
- A9 | 独立评委需要用户提供第二家 provider 的 key（无法从仓库推断） | UNVERIFIED | if false: task-0006 的预检只报 SELF_JUDGE 与所需配置，不伪造独立评委，task-0008 的摘要如实记录 releaseEligible=false

## Forks
- F1 | when: neg 题的 expectedEvidence 与检索指标冲突 | a) 把 `expectedRefusal=true` 的题从检索分母排除，另设 neg 计数 b) 保持现状并只在文档说明 | pick: a | by: V1 | reversal: reversible
- F2 | when: 分块级 gold 缺失 | a) 入库后按 expectedEvidence 与 ES 分块正文归一化匹配，写回 chunkId，匹配不到的报告出来 b) 继续只用短语 anyMatch | pick: a | by: V1 | reversal: reversible
- F3 | when: 真机评测耗时且依赖服务 | a) 把"跑一轮"作为独立的运行时验收任务，verify 只校验产出的压缩摘要（不重复触发评测） b) 把评测命令直接塞进 verify | pick: a | by: V3 | reversal: reversible
- F4 | when: 人工复核需要人的判断 | a) 包只生成复核包与校验脚本，5 条判定由用户完成 b) 用 LLM 自动生成人工标签 | pick: a | by: V1 | reversal: reversible

## Criteria
- C1 | 一条命令按 manifest 上传 10 篇 PDF、合并并等待入库，支持 dry-run/断点续传/自检 | task-0001
- C2 | 就绪检查能把 manifest 的 10 个 md5 对上 ES 分块数，缺失时非 0 退出并列出缺哪篇 | task-0002
- C3 | 真机冒烟在语料未就绪时提前失败，不再提交注定空召回的评测 | task-0003
- C4 | neg 题不再进入检索指标分母，报告暴露检索题数与 neg 排除数，compare 脚本兼容 | task-0004
- C5 | 入库后可按证据短语回填 expectedChunkIds，覆盖率非零且未匹配项被列出 | task-0005
- C6 | 发布预检能合并报告与 sidecar，识别 SELF_JUDGE、κ 样本不足、未配价，并给出可执行修复步骤 | task-0006
- C7 | 报告摘要脚本校验 v2 报告结构、120 题与非零覆盖率，产出不含答案正文的压缩摘要 | task-0007
- C8 | 首份真机 v2 摘要落盘，记录 retrievalErrorRate、fullCoverageHitRate、judgeIndependence、releaseEligible 及阻断原因 | task-0008

## Decisions
- D1 | 不新增依赖：脚本只用 Python 3 标准库与 curl（V5）。
- D2 | 入库脚本不打印、不落盘任何 token 或密码；凭据复用 `EVAL_ADMIN_TOKEN` → 用户名/密码 → `.env` 三级顺序（V1）。
- D3 | 评测报告全文留在 `eval-reports/`（git 忽略）；只把压缩摘要写进 `docs/eval/reports/`，避免 126 题回答正文进公开仓库。
- D4 | 人工复核不由脚本伪造；预检只校验 `humanAgreement` 是否已达到可用样本与 κ 阈值（F4）。
- D5 | 包经 plan-go 从主工作区移入工作树 .spec/worktrees/2026-11-12_eval-real-run 后，卡面 verify 的 `cd /Users/linyixi/PaiSmart` 会打到主工作区错误树——tasks.md 全部 cd 路径已重写到工作树根（含表头说明），属 relocation 机械勘误非扩权 | by: V2 | reversal: checkpoint revert
- D6 | 卡面 0001/0002/0005/0008 引用的 [runbook](docs/eval/runbook.md) 此前不存在（实际内容散在 docs/runbook.md 与 R3a 归档 runbook）——已在 task-0001 建立该文件（服务表、工作树注意、token 三级、上传/评测端点、通过标准、常见失败），boundary 归 task-0001 | by: V2 | reversal: checkpoint revert
- D7 | task-0004 卡面测试行明文"必要时同步 fixture"，但 boundary 未列 EvalSmokeFixtureTest.java——neg 退出检索分母使 20 题 fixture 的旧口径精确断言必然失败（实测 labeledCases 19→18、recallAtK 0.9474→1.0 等七项，数字取自实际产出的新报告而非手算）；授权把该文件断言与 4 处口径注释同步到新口径（eval_gate.sh 七条阈值实测不受影响，不改 gate），按交付补入 boundary，非扩权 | by: V2 | reversal: checkpoint revert
- D8 | task-0005 卡面成功判定明文"写回后 mvn -Dtest=EvalGoldFieldsTest test 通过"，但 boundary 未列该测试文件——EvalGoldFieldsTest:127-128 是写回前过渡态断言（"当前数据集未标注 chunkIds…全部应为 goldFallback=phrase"），任何符合解析器语义的写回（chunkIds 非空→goldFallback=null）都必然打破它，故意写错长度则触发 parser warn 打破 EvalDatasetIntegrityTest，冲突结构性无两全；授权迁移该断言为"chunkIds 非空→goldFallback 为 null（分块级 gold 可用）；为空→仍 phrase（短语回退）"并同步类 javadoc 与消息文本，按交付补入 boundary，非扩权 | by: V2 | reversal: checkpoint revert
- D9 | task-0007 "totalCases=120" 口径裁决：后端全量跑 126 行（EvalDatasetParser 不读 status、runAll 全跑），严格 ==120 会使真机报告必败且需越界改后端；裁决为计数口径——--check 接受 126，按 report.datasetSource 定位数据集、取 status=blocked 的 id 集（实测 6 条 ocr-07-*），用报告 case.caseId 精确匹配剔除后要求有效题数==120，摘要显式暴露 reportTotalCases/blockedExcluded；rate 指标原样引用报告（Python 侧不自产数字，与 README-v2 一致）；与 F3"blocked 不进分母"既定意图一致 | by: V2 | reversal: checkpoint revert

## Parked

## Deferred
- 独立评委的 provider key 与 5 条人工判定：需要用户提供/判断，包只准备工具与校验。
- 07 扫描件的 OCR：仍 blocked，6 题不进分母；等 OCR 环境就绪后单独立卡。
- 多轮评测基线与阈值调参：首份报告之后再做。

## Risks
- 10 篇 PDF 上传 + 解析约 100MB，入库可能跑 10–30 分钟；脚本必须可重入，失败重跑不重复建文档。
- 真机评测 120 题 + 双采样评委预计 20–40 分钟、消耗真实模型 token；task-0008 只跑一次，失败先看报告与 health，不要反复重试。
- ES 是 https + 自签证书（`insecure-trust-all-certificates=true`），脚本查询必须允许不校验证书且只读。
- 若模型 key 失效或后端起不来，task-0008 按 V7 停下并报告，不得伪造摘要或把 SELF_JUDGE 当发布结论。

## Binding
- branch: `spec/2026-11-12_eval-real-run`
- frozen: 2026-11-12T16:04:59+00:00
