# UI 美化 R2：聊天空态欢迎 + 签名面信念度 - 验收清单

- [x] 聊天欢迎区：产品文案 + 三条建议问法；点击 chip 输入框填充实值验证通过 -> verify: task-welcome
- [x] stagger 入场动画在位（chat-welcome-rise，reduced-motion 全局压扁兜底） -> verify: task-welcome
- [x] 搜索面板 @starting-style 入场规则生效、面板功能无破坏 -> verify: task-palette
- [x] 档案头身份 tile azure 化（bg rgba(0,113,227,0.08) + 主色 icon 实测） -> verify: task-palette
- [x] typecheck + eslint 0 error；detect 零 error 级 -> verify: task-gate
- [x] 真机走查截图落 orchestration/screenshots/（chat-welcome-after / search-palette / profile-avatar-after） -> verify: task-browser
- [x] backlog#12 登记（已用负数显示）；计划文档勾选 4/5 -> verify: task-plan-sync / task-backlog

## 验收证据

- 验证范围：package
- 脚本验证：`spec_verify.py run --all`（机器任务 runs.jsonl 全 OK）
- 旧新对比：chat-empty-before.png（指向侧栏的空态）vs chat-welcome-after.png（欢迎区+建议问法）；profile-avatar 灰底 → azure tile
- 差异边界：改动仅 chat-list.vue / personal-center 两行样式 / global.scss 一条规则 + 包文档
- 行为成效：真机 chip 点击后 textarea.value="MindBase 的引用标注如何做到可溯源？" 实测；搜索面板开启正常

**验收结果**：通过
