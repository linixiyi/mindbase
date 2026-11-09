# 评测体系 v2（接生产路径 + 可复核 + 统计门禁） - 完成总结

## 交付结论
- 结果：完成
- 完成时间：2026-11-09 09:54

## 假设回顾
### 已验证假设
- 待补充

### 仍未完全验证的假设
- Kafka header 端到端同值未直接观测（测试路径旁证）；旁路线程池 MDC 快照（前轮记录）

## 交付范围
### 已交付
- R2 评测体系 v2 十任务全落地：报告 schema v2+全量明细（临时文件+rename）；ChatPipeline 抽取接生产 ReAct（mode=react 默认/rag 对照）；nDCG/coverage/retrievalErrorRate/K 曲线；评委独立+kappa 0.6 门禁+30 条校准集；数据集 schema 扩展+parser 校验；eval_compare 兼容+eval_gate.sh 离线 20 题；Wilson/McNemar/bootstrap；旧报告重述；生成与引用指标；126 题新数据集（10 篇语料，dev90/test30+6blocked，负样本 17）。轮内修复复审三缺口：retrievedText 接真实链路、评委身份落盘、legacyDataset 接线。全量 452/452

### 未交付
- 无

### 偏差说明
- 无

## 简化决策
### 保持简单的关键选择
- 待补充

### 本轮明确不做的内容
- 待补充

## 变更边界
### 本轮主要改动模块
- 待补充

### 明确未触碰的区域
- 待补充

## 验证证据
### 备注
- 测试：mvn test 452/452（79 套）；eval_gate.sh 离线 20 题 exit 0；Wilson/McNemar 独立 Python 重算一致；eval-v2 抽 10 题对 PDF 逐字命中
- 构建：check 已收敛；十任务 verify+spec_verify 双绿（runs.jsonl 13 条）
- 手工：复审 10/10 实测（含 CLIP 深度对照 1 题）

## 验证范围
- 级别：package
- 机器验证率：10/10；manual 任务 3 项不计入分母
- 规则：check 与 done 复用该范围；只有记录升级触发时才执行全项目检查

## 哲学生效证据
### 行为成效回填
- 待补充

### 一致性门禁回顾
- 待补充

## 失误方向统计
- DEFERENCE：0
- OVERREACH：0
- KNOWLEDGE：0
- PROCESS：0
- 只统计，不自动调整 Autonomy。

## Git 记录
- 日期时间：2026-11-09 09:54
- 范围：评测体系 v2（接生产路径 + 可复核 + 统计门禁）
- 功能：R2 评测体系 v2 十任务全落地：报告 schema v2+全量明细（临时文件+rename）；ChatPipeline 抽取接生产 ReAct（mode=react 默认/rag 对照）；nDCG/coverage/retrievalErrorRate/K 曲线；评委独立+kappa 0.6 门禁+30 条校准集；数据集 schema 扩展+parser 校验；eval_compare 兼容+eval_gate.sh 离线 20 题；Wilson/McNemar/bootstrap；旧报告重述；生成与引用指标；126 题新数据集（10 篇语料，dev90/test30+6blocked，负样本 17）。轮内修复复审三缺口：retrievedText 接真实链路、评委身份落盘、legacyDataset 接线。全量 452/452
- 操作：提交 / 推送
- 成效：待补充
- 提交：pending local commit
- 提交区间：de089b934ad402f1155bed8b04573ec694d1a25e..f0c116e93cff1c4c03571115a1be69f7d61885c2
- 推送：not-run (push stage follows commit)

## AGENTS.md 增量提议
无

## 被拒绝的扩展提议
- 无

## 门禁证据
- 无

## 遗留事项
- 无；问题处置见下方结构化区块

## 问题处置

```json
{
  "issues": [],
  "version": 1
}
```
