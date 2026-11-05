# 打磨轮 H6：债务包 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：①死代码删除（sendRateLimitMessage/submitCompletionMonitor）；②邀请码创建响应裁剪 createdBy（H5 复审新发现，admin bcrypt 哈希泄漏）；③pm-002 存量明文迁移增强（应用启动时也清一次）；④G7 页面小修（双语标签字距/移动端导航可达）；⑤DLQ 重放 E2E（造一条可控死信实测列表+重放全链）
- **目标用户**：维护者与安全
- **核心价值**：债务清零、响应卫生、DLQ 可信验证
## 2. 假设与待确认
### 2.1 已确认事实
- 锚点在各轮复审记录；DLQ 端点/视图已在（F5），缺真实死信
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 五项由工作流落地与验证（dwfrun-7d205079，DLQ E2E 真链路走通）
### 3.3 不在范围内
- 其他 P2；push
## 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/**（docs/index.html 含）、src/main/java/**、src/test/**
- Git integration branch：`spec/2026-11-04-debt-pack`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：ChatHandler 死代码、AdminController 响应、登录迁移、docs 页、DLQ 全链
- 快速检查：typecheck；mvn compile
- 集成检查：eslint 0/0；mvn test 全量；detect；DLQ E2E 无头+API 实测
- 全项目检查：适用外
- 升级触发：造死信需破坏 Kafka 链路时改用 API 层注入死信表（最小路径）
### 5.4 编排策略
- route: build
- 执行段：动态工作流：两路并行（代码债务 / DLQ E2E+G7）→ 门禁 → 修理 → 复审
## 6. 成功标准与验证方式
- [x] 五项 -> verify: task-h6-all
## 7. 风险与约束
- 造死信最小路径：优先 Kafka 发一条毒消息触发 DLT 落库；不可行则直接向死信表 INSERT 合法载荷行（重放语义不变）
