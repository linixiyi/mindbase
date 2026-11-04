# 硬化轮 R3/G7 - 任务拆解

- [x] docs/index.html 产出 + 无头验证 + detect + 事实复审（工作流）
  - id: task-g7-page
  - boundary: 只新增 docs/**；事实仅出自 PRODUCT.md/README
  - verify: `test -s /Users/linyixi/PaiSmart/docs/index.html && cd /Users/linyixi/PaiSmart/frontend && node /tmp/g7-verify.mjs`
- [ ] 主会话发布：合并 → push → Pages API 开启 → 公网 200
  - id: task-g7-publish
  - depends-on: task-g7-page
  - boundary: git push main + Pages API（凭据不回显）
  - verify: manual: https://linixiyi.github.io/mindbase/ 返回 200（DNS/构建传播最多等几分钟）
