# UI 美化 R1：浏览器表面收口 + 管理页页头层级 - 验收清单

- [x] 文字选区呈 Azure 低透明底（亮/暗各自成立），输入插入符为主色 -> verify: task-surface
- [x] recharge-manage / org-tag 页头区：页题 + 主行动同行 baseline 对齐；列设置/刷新功能无损 -> verify: task-page-header
- [x] 表格空态为 tertiary 文字 + 舒展留白 -> verify: task-empty-state
- [x] typecheck + eslint 0 error；detect 零 error 级 -> verify: task-gate
- [x] 真机亮暗走查通过，截图落 orchestration/screenshots/ -> verify: task-browser
- [x] 计划文档勾选 2/5，chain.json 记录 R1 -> verify: task-plan-sync

## 验收证据

- 验证范围：package
- 脚本验证：`spec_verify.py run --all`（机器任务 runs.jsonl 全 OK）
- 旧新对比：选区/插入符（浏览器默认蓝黑 vs Azure token）、页头（页题独行 vs 页题+主行动同行）
- 差异边界：改动仅 global.scss + 两视图模板 + 计划文档/链状态
- 行为成效：真机走查 DOM/截图（orchestration/screenshots/）

**验收结果**：通过
