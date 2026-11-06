# 安全 P0 - 任务拆解

- [x] S1-S4 单路 + 门禁 + 复审（工作流）
  - id: task-s-all
  - boundary: spec §5 允许清单；引用路径零改动；不加依赖
  - verify: `cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" test 2>&1 | tail -5 | grep -q "BUILD SUCCESS"`
- [x] 主会话抽验（双用户越权实测 + 属主正常路径）
  - id: task-s-accept
  - depends-on: task-s-all
  - boundary: 只读
  - verify: manual: evidence/s-accept.md
