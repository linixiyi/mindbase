# P1b - 任务拆解

- [x] CI 配置产出 + 本地等价验证 + push 后 Actions 实跑绿（工作流+主会话）
  - id: task-p1b-all
  - boundary: spec §5 允许清单；不改产品代码
  - verify: `test -s /Users/linyixi/PaiSmart/.github/workflows/ci.yml`
- [x] 主会话 push + Actions 绿证
  - id: task-p1b-publish
  - depends-on: task-p1b-all
  - boundary: push + Actions run URL 记录
  - verify: manual: evidence/p1b-publish.md（run URL + 绿勾截图/JSON）
