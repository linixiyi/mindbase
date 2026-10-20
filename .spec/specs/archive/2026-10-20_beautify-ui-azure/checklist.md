# UI 美化第四轮：淡蓝主题 + impeccable 八指令 - 验收清单

- [x] 强调色全站淡蓝（#0071E3），无靛蓝残留（grep 零命中）；白字对比 ≥4.5:1 -> verify: task-colorize
- [x] 亮色中性面染冷蓝（地面/发丝线/fill 蓝调），暗色 token 数值除主色外零改动 -> verify: task-colorize
- [x] 10/10 管理视图含 `.page-title` 页题 -> verify: task-typeset-layout
- [x] 动效：主题切换 morph（无 API 优雅降级）、聊天/引用微动效、上传成功时刻；`prefers-reduced-motion` 兜底存在 -> verify: task-motion
- [x] 审计：强调色面积 ≤10%、无装饰性阴影/hover 上浮回归 -> verify: task-quieter-bolder
- [x] typecheck + eslint 全仓 0 error -> verify: task-gate
- [x] impeccable detect 全目标无 error 级 finding -> verify: task-gate
- [x] `.impeccable/design.json` 合法且与 DESIGN.md 同步 -> verify: task-gate
- [x] 真机登入走查：chat + 10 管理视图（亮）+ 暗色抽查，引用 chip 渲染与预览不回归；截图落 orchestration/screenshots/ -> verify: task-browser
- [x] orchestration/pm-review.md 产出：八指令逐项结论 + P0/P1 计数 + 下一轮建议 -> verify: task-pm
- [x] 品牌标志换新：logo.svg 单色版（currentColor）+ favicon.svg Azure 瓷砖版（旧 #646cff 清零）；DESIGN.md 品牌节成文 -> verify: task-logo
- [x] 图标统一 lucide：非 lucide iconify 引用零残留；主题切换单发（无双发跳档）-> verify: task-icons

## 验收证据

- 验证范围：package
- 脚本验证：`cd frontend && pnpm typecheck && pnpm exec eslint .`（exit 0，0 error）；`impeccable detect --json <chat|chat-history|knowledge-base|layouts>` 输出 `[]`；`spec_verify.py run --all` 9 个机器任务 OK（runs.jsonl，worktree fp=8c502b2634d5）
- 旧新对比：orchestration/screenshots/（chat-light、usage-monitor 亮/暗、logo-sider-new、login-new-logo、9 管理视图）——旧靛蓝 #5856D6 一线 vs 新 Azure #0071E3 一线、旧 10 套混拼图标 vs 纯 lucide、旧 Vite 紫 favicon vs Azure 瓷砖
- 差异边界：git 变更全部落在允许清单（theme/styles/layouts/views/components 样式与图标串层 + routes.ts 图标串【用户追加项引入，已补录允许清单】+ DESIGN.md/design.json/backlog）；后端、packages/@sa/**、.env、引用数据流零触碰
- 行为成效：真机 DOM 计数——10/10 管理视图 page-title 32px/600；整页 iconify 图标 `lucide` 15/15；主题切换 vt delta=1/次、morph 从按钮位扩散（morphX/R 实测）；`--primary-color` 实测 `0 113 227`、地面 `242 246 252`、冷墨 `28 42 58`；暗色主色同蓝、其余暗色 token 原值

**验收结果**：通过
