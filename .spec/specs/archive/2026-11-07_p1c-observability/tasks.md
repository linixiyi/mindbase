# P1c - 任务拆解

- [x] 两项单路 + 门禁 + 复审（工作流）
  - id: task-p1c-all
  - boundary: spec §5 允许清单（pom.xml 仅加 actuator starter 并记录）；引用路径零改动
  - verify: `cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" test 2>&1 | tail -5 | grep -q "BUILD SUCCESS"`
- [x] 主会话抽验（curl actuator + trace 日志断言）
  - id: task-p1c-accept
  - depends-on: task-p1c-all
  - boundary: 只读
  - verify: manual: evidence/p1c-accept.md
