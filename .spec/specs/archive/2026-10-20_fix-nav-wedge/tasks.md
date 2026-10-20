# P1 修复：主题切换/新对话后 SPA 导航空白（backlog#11） - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：定位
- [x] wedged 态仪器化取证：压测至复现，读取 GlobalContent Vue 实例（setupState/subTree）、错误捕获、恢复行为
  - id: task-rootcause
  - boundary: 只读 + 前期已落的 App.vue 调试钩子；取证产物写 orchestration/root-cause.md
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_fix-nav-wedge/orchestration/root-cause.md && grep -q "根因" /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_fix-nav-wedge/orchestration/root-cause.md`
    - 运行目录：任意（绝对路径）
    - 成功判定：exit 0
    - 失败处理：继续取证直到根因有实验证据

## 阶段二：修复与验证
- [x] 最小修复 + 压测验证（≥20 次点击+导航零空白；新对话；morph/暗色/欢迎区不回归）
  - id: task-fix
  - depends-on: task-rootcause
  - boundary: 见 spec.md §5 允许清单
  - verify: manual: 主线程真浏览器压测执行（间歇性缺陷无法用单次命令断言），压测记录（次数/结果）写 orchestration/fix-verify.md 并随包归档
- [x] 移除 App.vue 临时调试钩子 + 门禁（typecheck/eslint/detect 零 error）+ backlog#11 状态更新
  - id: task-cleanup
  - depends-on: task-fix
  - boundary: `frontend/src/App.vue`、docs/backlog.md
  - verify: `cd /Users/linyixi/PaiSmart/frontend && ! grep -q "__mbDebug" src/App.vue && pnpm typecheck && pnpm exec eslint src/App.vue src/layouts && cd .. && grep -q "11" docs/backlog.md`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：清理后重验

---

**当前进度**：由脚本计算，无需手动维护
