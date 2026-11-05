# 打磨轮 H5 - 任务拆解

- [x] 两项单路 + 门禁 + 复审（工作流）
  - id: task-h5-all
  - boundary: spec §5 允许清单；引用路径零改动；不加依赖；不造假功能
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" test 2>&1 | tail -5 | grep -q "BUILD SUCCESS" && ! /Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json frontend/src | grep -q '"error"'`
- [x] 主会话抽验（注册流无头走查）
  - id: task-h5-accept
  - depends-on: task-h5-all
  - boundary: 只读
  - verify: manual: evidence/h5-accept.md
