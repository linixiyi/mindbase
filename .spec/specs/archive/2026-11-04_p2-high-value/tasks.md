# 硬化轮 R2/G6 - 任务拆解

- [x] 三项并行落地 + 冒烟门禁 + 复审（工作流）
  - id: task-g6-all
  - boundary: spec §5 允许清单；引用路径零改动；不加依赖
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && node /tmp/g6-smoke.mjs`
- [x] 主会话抽验：Ctrl+K 画面 + 术语抽查 + 控制台零错
  - id: task-g6-accept
  - depends-on: task-g6-all
  - boundary: 只读
  - verify: manual: evidence/g6-accept.md
