# UI Azure 收口轮：复审反馈五项验证闭环 - 验收清单

- [x] 登录页新 logo hero + lucide 前缀图标 + azure 主按钮，注册页图标正常；重登成功 -> verify: task-v1
- [x] 真实流式：呼吸点可见、流式增量、引用 chip 正常、完成后静止 -> verify: task-v2
- [x] reduced-motion 三层降级路径代码在位（全局压扁 / morph 分支 / 呼吸压扁） -> verify: task-v3
- [x] 暗色 10/10 视图 DOM 指标达标（html.dark、主色蓝、暗容器），关键页截图无回归 -> verify: task-v4
- [x] favicon 服务内容为新 Azure 瓷砖 -> verify: task-v5
- [x] typecheck + eslint 0 error（仅发生修复时执行并记录） -> verify: task-v3
- [x] 收口复审文件产出：五项结论 + 缺陷计数 + 收敛决策 -> verify: task-closeout

## 验收证据

- 验证范围：package
- 脚本验证：`spec_verify.py run --all`（机器任务 runs.jsonl 全 OK）；task-v5 curl 断言
- 旧新对比：第四轮归档截图为本轮走查的对照基线
- 差异边界：本轮预期零代码改动；若修复记录于任务 boundary
- 行为成效：五项走查 DOM 指标与截图（orchestration/screenshots/）

**验收结果**：通过
