# PM 链 F4：P0 三项修复（聊天失败恢复/记住我安全/删除隔离）- 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：落地 backlog 三条 P0——pm-001 聊天失败死胡同（错误分层+重试+失败回合持久化）、pm-002 记住我明文密码落 localStorage、pm-003 删除文档按 MD5 全局清理跨用户连带清除
- **目标用户**：全部产品用户（安全与可靠性）
- **核心价值**：核心旅程失败可恢复、凭据不出明文、多用户数据隔离
## 2. 假设与待确认
### 2.1 已确认事实
- backlog 与验收判据在 .spec/plans/pm-experience-upgrade-backlog.md（证据锚点已复核）
- 三项改动面互不重叠：pm-001 触 chat 前后端、pm-002 触登录模块、pm-003 触 DocumentService 删除链
### 2.2 关键假设
- 失败回合持久化可在现有 ConversationService 落库路径扩展（成功收尾 persistConversation 已有）
- WebSocket 错误消息向后兼容（新增字段不破坏现有前端）
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] pm-001 聊天失败恢复（错误码分层/前端错误卡片+重试/失败回合落库刷新可见）
- [x] pm-002 记住我只存用户名（存量明文密码清理）
- [x] pm-003 删除隔离（scoped 删除，最后持有者才全局清理）
### 3.3 不在范围内
- P1 项（admin 删他人文档 404 等，F5 轮）；UI 视觉改动；推送/发布
## 5. 技术决策
- 技术栈：实现工作流跑 GLM-5.3-Flash（用户分工约定），规划由主会话 GLM-5.3 完成
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**（chat 模块/login 模块/store）、src/main/java/com/mindbase/**（ChatHandler/DocumentService/ConversationService 相关）
- 本轮不应触碰：引用标注 DOM/正则/referenceMappings 数据流；DESIGN.md 视觉规范；package.json/pom 依赖
- Git integration branch：`spec/2026-10-24_fix-p0-experience`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：chat 失败链路（前后端）、记住我存储、文档删除链
- 快速检查：pnpm typecheck + eslint；mvn -q -DskipTests compile（IntelliJ 捆绑 mvn，PATH 无 mvn）
- 集成检查：acceptance grep（重试入口/无明文密码/scoped 删除调用）+ 独立复审 diff + 主会话真机 E2E（正常问答回归 + rememberedLogin 无密码）
- 全项目检查：适用外（改动限 chat/login/DocumentService 三链路，无共享基础设施/发布载荷变更）
- 升级触发：若修复需改 WebSocket 协议语义（破坏兼容）或表结构，停下升级范围并记录
### 5.4 编排策略
- route: build
- 执行段：动态工作流（Flash）：三修复员并行 → 门禁（前端四项+后端编译）→ 修理 ≤2 轮 → 独立复审员
## 6. 成功标准与验证方式
- [x] pm-001 -> verify: task-pm-001
- [x] pm-002 -> verify: task-pm-002
- [x] pm-003 -> verify: task-pm-003
## 7. 风险与约束
- 并行改动后端两文件不同链路，冲突面低；复审逐项对 acceptance
- 失败回合落库改动会话表结构吗——不允许改表结构（用现有字段承载状态），越界即升级
