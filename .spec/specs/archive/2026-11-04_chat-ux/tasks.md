# 打磨轮 H2 - 任务拆解

- [x] 五项三路并行 + 门禁 + 复审（工作流）
  - id: task-h2-all
  - boundary: spec §5 允许清单；引用路径零改动；不加依赖
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" test 2>&1 | tail -5 | grep -q "BUILD SUCCESS" && ! /Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json frontend/src | grep -q '"error"'`
- [x] 主会话抽验（无头：重命名/删除/刷新恢复/localStorage 有界）
  - id: task-h2-accept
  - depends-on: task-h2-all
  - boundary: 只读
  - verify: manual: evidence/h2-accept.md
