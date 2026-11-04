# 打磨轮 H1 - 任务拆解

- [x] H1 五项三路并行 + 门禁（含 mvn test 全量）+ 复审（工作流）
  - id: task-h1-all
  - boundary: spec §5 允许清单；引用路径零改动；不加依赖
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" test 2>&1 | tail -5 | grep -q "BUILD SUCCESS"`
- [x] 主会话抽验（README 链接可达 + 键帽平台态）
  - id: task-h1-accept
  - depends-on: task-h1-all
  - boundary: 只读
  - verify: manual: evidence/h1-accept.md
