# UI 美化 R2：聊天空态欢迎 + 签名面信念度 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：实现
- [x] 聊天欢迎区：文案 + 建议问法胶囊（chatStore.input 填充）+ stagger 入场
  - id: task-welcome
  - boundary: 只改 `frontend/src/views/chat/modules/chat-list.vue`
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "chat-welcome" src/views/chat/modules/chat-list.vue && grep -q "chatStore.input" src/views/chat/modules/chat-list.vue && pnpm typecheck && pnpm exec eslint src/views/chat`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：按报错修复后重验
- [x] 全局搜索命令面板 @starting-style 入场 + personal-center 档案头 tile 品牌化
  - id: task-palette
  - depends-on: task-welcome
  - boundary: 只改 `frontend/src/styles/scss/global.scss`（@starting-style）、`frontend/src/views/personal-center/index.vue`（avatar 两行样式）
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "@starting-style" src/styles/scss/global.scss && grep -q "R2 bolder" src/views/personal-center/index.vue && pnpm typecheck`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：按报错修复后重验
- [x] 门禁：detect 零 error
  - id: task-gate
  - depends-on: task-palette
  - boundary: 只读检测
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/chat /Users/linyixi/PaiSmart/frontend/src/views/personal-center /Users/linyixi/PaiSmart/frontend/src/layouts/modules/global-search`
    - 运行目录：任意（绝对路径）
    - 成功判定：JSON 输出无 error 级 finding
    - 失败处理：按 finding 修复后重跑
- [x] 主线程真机走查：欢迎区/建议填充/搜索面板/档案头 + 截图
  - id: task-browser
  - depends-on: task-gate
  - boundary: 只读走查 + 截图
  - verify: manual: 主线程执行；走查记录 chip 点击填充 textarea 实值、搜索面板开启、档案头样式值；截图落 orchestration/screenshots/
- [x] plan-sync：勾选计划文档 animate+delight、bolder+overdrive + chain.json
  - id: task-plan-sync
  - depends-on: task-browser
  - boundary: 只改 `.spec/plans/ui-beautification.md` 与 `.spec/autorun/chain.json`
  - verify: `cd /Users/linyixi/PaiSmart && grep -c "^- \[x\]" .spec/plans/ui-beautification.md | grep -q "^4$"`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：补勾选后重验
- [x] 走查发现登记：personal-center「已用」负数显示（backlog#12）
  - id: task-backlog
  - boundary: 只改 docs/backlog.md
  - verify: `cd /Users/linyixi/PaiSmart && grep -q "quota-negative-used" docs/backlog.md`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：补登记后重验

---

**当前进度**：由脚本计算，无需手动维护
