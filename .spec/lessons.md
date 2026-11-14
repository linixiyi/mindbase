# Lessons（Spec Harness）

- 在受保护分支（main）上 plan-go 会把包移进 `.spec/worktrees/<slug>`；卡面 verify 里的 `cd` 必须指向工作树根，否则会拿主工作区的旧树做红绿判定（2026-11-12 包 D5）。
- 盘点"数据是否真的入库"要同时看三处：MySQL `file_upload`、ES 分块数、报告里的 `retrievalMode`。只看其中一处会把"上传成功但解析失败"误判为就绪。
- 评测指标要有"语言/数据边界"：中文回答对英文证据做词面 faithfulness 会得到假 unsupported（首份真机报告 6.38%）；拒答检测扫全文会把"资料中没有 X，但 Y"误判为拒答（34/109 假阳性）。指标口径必须写进注释与测试。
- 运行时验收任务的 verify 只校验压缩摘要，不重复触发 20–40 分钟的评测；评测本身作为一次人工/脚本执行记录进 `docs/eval/first-real-run.md`。
- 人工复核 sidecar 不改原报告；发布判定必须按"报告 + sidecar"重算，不能只读报告里的冻结 `releaseEligible`。
