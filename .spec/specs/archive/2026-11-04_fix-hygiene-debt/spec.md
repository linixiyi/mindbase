# 硬化轮 R1：仓库卫生 + 禁用持久化 + 错误呈现 + 债务清理 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：落地计划 G1–G4——.bak 停止跟踪与测试数据清理（G1）、users.enabled 持久化禁用（G2，本轮授权一次迁移）、pm-015 异常原文渲染 + pm-022 错误中文化与 429 retryAfter 兑现（G3）、pm-003 TOCTOU 收口 + pdf-viewer stone 清理 + eslint warning 清零（G4）
- **目标用户**：全部用户与仓库受众
- **核心价值**：安全债务与技术债显性清零；用户可见错误可信
## 2. 假设与待确认
### 2.1 已确认事实
- 问题锚点与验收判据在 backlog pm-015/022/012/003 与 F4/F5 复审记录；环境由并行工作流拉起
### 2.2 关键假设
- 四路文件集互斥可并行（backend-user / 错误呈现前后端 / DocumentService+pdf-viewer / 仓库卫生）
### 2.3 待确认问题
- [x] 迁移模式 → 以仓库既有 docs/databases 或启动自检模式为准，工作流内读后决定，异常即升级
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] G1–G4 由工作流 dwfrun-8dbe215a 实现与验证（六门禁全绿 + 复审 converged + 主会话断电语义实测）
### 3.3 不在范围内
- G5 体验轮与 G6 P2 项（下一包）；push（未授权）
## 5. 技术决策
- 技术栈：动态工作流，子代理 GLM-5.3-Flash（start plan）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**、src/main/java/**、src/test/**、docs/databases/**（仅 users.enabled 迁移）；**改表授权仅此一项**
- 本轮不应触碰：其他表结构、依赖、引用标注数据流、DESIGN.md；不 commit（staging 允许）
- Git integration branch：`spec/2026-11-04_fix-hygiene-debt`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：用户状态链、聊天错误呈现链、文档删除链、pdf-viewer、lint 基线
- 快速检查：pnpm typecheck；mvn -q -DskipTests compile
- 集成检查：eslint . **0 error 0 warning**；mvn -q test-compile；impeccable detect 无 error 级；git ls-files '*.bak' 空；grep stone- 于 pdf-viewer 零命中；spec_verify 实测
- 全项目检查：适用外
- 升级触发：G2 迁移在仓库既有模式内无法表达时停下记录
### 5.4 编排策略
- route: build
- 执行段：动态工作流（Flash）：四路并行 → eslint 清零 → 门禁（六项）→ 修理 ≤2 → 独立复审 → 测试数据清理
## 6. 成功标准与验证方式
- [x] G1–G4 落地 -> verify: task-g-all
- [x] 门禁六项全绿 -> verify: task-g-all（含在命令内）
## 7. 风险与约束
- eslint 清零与并行道改动冲突 → 清零放在并行道之后串行执行
- G2 涉及测试签名 → 门禁含 test-compile（F4 教训）
