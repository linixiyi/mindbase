# P1d - 任务拆解

- [x] 三项单路 + 门禁 + 复审（工作流）
  - id: task-p1d-all
  - boundary: spec §5 允许清单；不改产品行为
  - verify: `test -s /Users/linyixi/PaiSmart/scripts/cleanup-test-data.sql && test -x /Users/linyixi/PaiSmart/scripts/cleanup-test-data.sh && ! grep -q "待补充" /Users/linyixi/PaiSmart/README.md`
- [x] 主会话抽验（一致性抽查复核）
  - id: task-p1d-accept
  - depends-on: task-p1d-all
  - boundary: 只读
  - verify: manual: evidence/p1d-accept.md
