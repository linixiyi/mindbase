# PM 硬化轮计划（2026-11-04）

用户 brief：继续前面链的遗留问题（卫生+债务先行），工作流执行（子代理=GLM-5.3-Flash start plan），按反馈滚动新一轮；主会话自身体验产品驱动决策；收尾建 GitHub Pages 介绍页。

- [x] G1 仓库卫生：pm-034 .bak 备份文件停止跟踪并删除；测试数据清理（e2e-pm-user / E2E-PM-2026）
- [x] G2 pm-012 用户禁用持久化：users.enabled 列 + 迁移（本轮授权改表）+ 服务改造（登录拒绝/踢会话语义保持）+ 测试
- [x] G3 错误呈现硬化：pm-015 生成快照不再把异常原文当正文渲染；pm-022 用户可见错误全部中文化分层 + 429 retryAfterSeconds 在前端兑现
- [x] G4 债务清理：pm-003 删除 TOCTOU 收口；pdf-document-viewer stone 色板 token 化；eslint 存量 warning 清零
- [x] G5 产品自体验一轮（主会话/体验员）：核心旅程 + 新治理面，产出下一轮聚焦
- [ ] G6 P2 高价值项落地（体验后定稿，候选：pm-014 命令面板 Ctrl+K+内容搜索、pm-029/030/031 术语与 IA 统一）
- [ ] G7 GitHub Pages 介绍页：设计（impeccable Persuade 模式）+ gh-pages 发布

## 约定
- 特性行自首轮执行起冻结；细化落各包文档
- 实现轮执行引擎：动态工作流，子代理 account:bigmodel-start-plan/GLM-5.3-Flash
- 门禁：pnpm typecheck / eslint 0 error 0 warning（G4 起） / mvn -q -DskipTests compile + test-compile / impeccable detect 无 error 级 / .bak 零跟踪
- 改表授权：仅限 G2 的 users.enabled（一次迁移）
