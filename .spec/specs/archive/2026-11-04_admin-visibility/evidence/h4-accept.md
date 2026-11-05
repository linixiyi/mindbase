# H4 主会话抽验（2026-11-06，无头 Playwright）

- 四视图实机走查：模型供应商（rerank 卡在位，h1「模型供应商」）、充值管理（订单列表）、组织标签（2 根标签 + 「新增下级」操作；树形展开图标仅在有子级时出现，当前 0 子级属正常）、邀请码管理（有效期列）——全部可达功能在位。
- 采信复审实测：rerank 测试连接真实外呼（成功 1312ms 对本地代理 / DashScope 401 诚实暴露现存配置问题）、主备链 3 行含熔断 CLOSED、订单分页+轮询、22 根标签跨页级联、邀请码双向+注册链端到端、5 新端点非 admin 全 403。
- mvn test 339/339（复审实跑）；spec_verify 五门禁主会话复跑（runs.jsonl）。
- 遗留：复审权限测试用户 rv_reg_mw3tinc4（已停用留痕，无删除端点）——清理 SQL 记录：DELETE FROM users WHERE username='rv_reg_mw3tinc4'。


## 追记（2026-11-06）：spec_verify 首跑 exit=1 说明

首跑 16.4s 即失败，为 mvn test 撞上 H4 工作流重启后端的并发窗口（瞬时态）；主会话随后逐段复测（typecheck/eslint/mvn compile/mvn test 339 全绿）并重跑 spec_verify 记录成功。非产品缺陷。
