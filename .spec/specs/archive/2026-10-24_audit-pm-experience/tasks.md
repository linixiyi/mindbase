# PM 体验升级链 R1 - 任务拆解

## 使用规则
- 每个任务写清 boundary 与 verify；机器任务 verify 首个反引号 span 为命令

## 阶段一：补证与审计
- [x] F1 LLM 接入 E2E 补证（.env 核验 + 后端重启 + 真实问答恢复）
  - id: task-llm-e2e
  - boundary: 主会话基础设施操作（.env 已由用户配置，本轮仅核验与重启），不改产品代码
  - verify: manual: evidence/round-1-sim.md「旅程实录」含 2026-10-24 16:56 真实流式回答 + 引用标注实录
- [x] F2 体验日志落盘
  - id: task-sim-log
  - boundary: 只写本包 evidence/
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-24_audit-pm-experience/evidence/round-1-sim.md`
- [x] F3 PM 五视角审计工作流 + backlog 产出
  - id: task-pm-audit
  - depends-on: task-sim-log
  - boundary: 审计代理只读；汇总代理只写 .spec/plans/pm-experience-upgrade-backlog.md
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/plans/pm-experience-upgrade-backlog.md`
- [x] plan-sync：勾选计划 F1/F2/F3 + chain.json 状态
  - id: task-plan-sync
  - depends-on: task-pm-audit
  - boundary: 只改 .spec/plans/pm-experience-upgrade.md 与 .spec/autorun/chain.json
  - verify: manual: F1-F3 勾选且 chain round1 status=done
