# P1 修复：主题切换/新对话后 SPA 导航空白（backlog#11） - 验收清单

- [x] root-cause.md 产出：根因有实验证据（Vue 实例/错误捕获/恢复行为三选以上） -> verify: task-rootcause
- [x] 修复后压测 ≥20 次（点击+导航）零空白；新对话路径正常 -> verify: task-fix
- [x] morph/暗色/欢迎区不回归 -> verify: task-fix
- [x] __mbDebug 钩子移除；typecheck + eslint + detect 0 error -> verify: task-cleanup
- [x] backlog#11 状态更新（resolved_current 或带证据的降级） -> verify: task-cleanup

## 验收证据

- 验证范围：package
- 脚本验证：`spec_verify.py run --all`；压测记录 orchestration/fix-verify.md
- 差异边界：修复限定在 §5 允许清单
- 行为成效：修复前压测 ~1/6 空白；修复后 ≥20 次零空白

**验收结果**：通过
