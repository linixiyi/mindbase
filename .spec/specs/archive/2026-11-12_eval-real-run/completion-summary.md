# 2026-11-12_eval-real-run - Completion Summary

## Delivery
- task-0001 语料入库脚本：分片上传、合并、等待入库
- task-0002 语料就绪检查：manifest ↔ ES 分块数
- task-0003 真机冒烟接语料就绪前置
- task-0004 neg 题退出检索指标分母
- task-0005 按证据短语回填 expectedChunkIds
- task-0006 发布条件预检：独立评委、κ、定价
- task-0007 v2 报告校验与压缩摘要
- task-0008 真机跑通并产出首份 v2 摘要
- task-0009 Tika/PDFBox 回退解析引擎

## Evidence
- Final-tree verifies passed; see runs.jsonl.

## Decisions
- D1 |  不新增依赖：脚本只用 Python 3 标准库与 curl（V5）。
- D2 |  入库脚本不打印、不落盘任何 token 或密码；凭据复用 `EVAL_ADMIN_TOKEN` → 用户名/密码 → `.env` 三级顺序（V1）。
- D3 |  评测报告全文留在 `eval-reports/`（git 忽略）；只把压缩摘要写进 `docs/eval/reports/`，避免 126 题回答正文进公开仓库。
- D4 |  人工复核不由脚本伪造；预检只校验 `humanAgreement` 是否已达到可用样本与 κ 阈值（F4）。
- D5 |  包经 plan-go 从主工作区移入工作树 .spec/worktrees/2026-11-12_eval-real-run 后，卡面 verify 的 `cd /Users/linyixi/PaiSmart` 会打到主工作区错误树——tasks.md 全部 cd 路径已重写到工作树根（含表头说明），属 relocation 机械勘误非扩权 | by: V2 | reversal: checkpoint revert
- D6 |  卡面 0001/0002/0005/0008 引用的 [runbook](docs/eval/runbook.md) 此前不存在（实际内容散在 docs/runbook.md 与 R3a 归档 runbook）——已在 task-0001 建立该文件（服务表、工作树注意、token 三级、上传/评测端点、通过标准、常见失败），boundary 归 task-0001 | by: V2 | reversal: checkpoint revert
- D7 |  task-0004 卡面测试行明文"必要时同步 fixture"，但 boundary 未列 EvalSmokeFixtureTest.java——neg 退出检索分母使 20 题 fixture 的旧口径精确断言必然失败（实测 labeledCases 19→18、recallAtK 0.9474→1.0 等七项，数字取自实际产出的新报告而非手算）；授权把该文件断言与 4 处口径注释同步到新口径（eval_gate.sh 七条阈值实测不受影响，不改 gate），按交付补入 boundary，非扩权 | by: V2 | reversal: checkpoint revert
- D8 |  task-0005 卡面成功判定明文"写回后 mvn -Dtest=EvalGoldFieldsTest test 通过"，但 boundary 未列该测试文件——EvalGoldFieldsTest:127-128 是写回前过渡态断言（"当前数据集未标注 chunkIds…全部应为 goldFallback=phrase"），任何符合解析器语义的写回（chunkIds 非空→goldFallback=null）都必然打破它，故意写错长度则触发 parser warn 打破 EvalDatasetIntegrityTest，冲突结构性无两全；授权迁移该断言为"chunkIds 非空→goldFallback 为 null（分块级 gold 可用）；为空→仍 phrase（短语回退）"并同步类 javadoc 与消息文本，按交付补入 boundary，非扩权 | by: V2 | reversal: checkpoint revert
- D9 |  task-0007 "totalCases=120" 口径裁决：后端全量跑 126 行（EvalDatasetParser 不读 status、runAll 全跑），严格 ==120 会使真机报告必败且需越界改后端；裁决为计数口径——--check 接受 126，按 report.datasetSource 定位数据集、取 status=blocked 的 id 集（实测 6 条 ocr-07-*），用报告 case.caseId 精确匹配剔除后要求有效题数==120，摘要显式暴露 reportTotalCases/blockedExcluded；rate 指标原样引用报告（Python 侧不自产数字，与 README-v2 一致）；与 F3"blocked 不进分母"既定意图一致 | by: V2 | reversal: checkpoint revert
- D10 |  LiteParse（lit 命令）与 tesseract 本机均未安装，后端唯一 PDF 引擎不可用致入库死信；AskUserQuestion 裁决窗口未获响应，按自主合同与预标推荐项执行：授权 Tika/PDFBox 回退引擎（Tika 已在 classpath，V5 无新依赖）——engine=tika 经环境变量 FILE_PARSING_PDF_ENGINE 启用，LiteParse 保持默认引擎不动、不改 application.yml；扫描件无文本层时页为空被跳过（与 07 OCR blocked 既定口径一致）；由 emergent task-0009 承接 ParseService.java | by: V2 | reversal: checkpoint revert
- D11 |  task-0005 卡面"expectedChunkIds 与 evidence 一一对应"与解析器契约不符：expectedChunkIds 与 expectedFileNames 平行、每文件单值（EvalCase.java:19-22/99-100），按 evidence 平行写回会让 55 行多证据题触发 parser 长度 warn 且分块级 gold 失效；实现按解析器契约落地（写回后失配 0/126、hasUsableChunkGold 126/126），实测覆盖率 1.0000 | by: V2 | reversal: checkpoint revert

## Parked

## Emergent tasks
- task-0009 Tika/PDFBox 回退解析引擎

## Lessons
- No retained lessons.
- Archived: 2026-11-12T21:49:30+00:00

## Git
- Commit interval: 46f2d0c698791d53ca2800da8d57a5a901048104
