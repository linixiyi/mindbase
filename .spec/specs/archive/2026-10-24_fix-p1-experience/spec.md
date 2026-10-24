# PM 链 F5：P1 十项落地（Flash 实现）- 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：落地 backlog 全部 P1 项（.spec/plans/pm-experience-upgrade-backlog.md 的 P1 节，10 项）——冷启动引导、IME 防护、日期筛选死功能、admin 删除修复、聊天记录分页、四治理能力界面化、邀请码死链、限流面板防护、用户禁用
- **目标用户**：全部用户与管理员
- **核心价值**：P0 之上把「明显摩擦与重要缺失」清零
## 2. 假设与待确认
### 2.1 已确认事实
- backlog P1 各项含验收判据与证据锚点；四治理能力后端 REST 已在（controller 存在，前端零调用）
### 2.2 关键假设
- 三路文件集互斥可并行（chat 前端体验 / 新管理视图+user / 后端+chat-history）
### 2.3 待确认问题
- [x] P1 全量一轮落地 → 若复审暴露体量问题，未竟项回写 F5 追加轮
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] P1 全部落地（三路并行 + DLQ 追补轮，清单以 backlog P1 节为准；pm-013 按 backlog 规则以 PM 裁决 B 线记录收口）
### 3.3 不在范围内
- P2 打磨项；视觉重设计；发布
## 5. 技术决策
- 技术栈：实现工作流 GLM-5.3-Flash（分工约定）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**（含新增视图与路由）、src/main/java/com/mindbase/**（AdminController/DocumentController/DocumentService/UserService 等）；不加依赖、不改表结构
- Git integration branch：`spec/2026-10-24_fix-p1-experience`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：chat 体验三处、管理视图新增、user 视图、文档删除与分页后端、用户状态端点
- 快速检查：pnpm typecheck + eslint；mvn -q -DskipTests compile
- 集成检查：独立复审逐项对 backlog P1 验收 + 主会话真机抽查
- 全项目检查：适用外（新增视图走既有路由/菜单注册模式，不动构建）
- 升级触发：需改表结构或依赖时停下记录
### 5.4 编排策略
- route: build
- 执行段：动态工作流（Flash）三路并行 → 双栈门禁 → 修理 ≤2 → 独立复审
## 6. 成功标准与验证方式
- [x] P1 落地 -> verify: task-p1-all
- [x] 真机抽查 -> verify: task-p1-browser
## 7. 风险与约束
- 新视图体量大 → 复用既有 workspace-page/表格/空态模式；菜单路由按既有 elegant-router 模式注册
- 并行文件冲突 → 任务书钉死文件集
