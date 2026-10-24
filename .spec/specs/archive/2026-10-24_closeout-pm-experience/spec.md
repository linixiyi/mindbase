# PM 链 F6：链收口（文档同步+收敛声明）- 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：链收口——PRODUCT.md 语言承诺 B 线修订与能力清单刷新、backlog 状态标注、DESIGN.md 一致性核查结论、计划 F6 勾选、chain 完成声明、完成摘要
- **目标用户**：开发者本人 + 作品集受众
- **核心价值**：产品事实文件与实现同源；链可审计闭环
## 2. 假设与待确认
### 2.1 已确认事实
- F1-F5 已完成并合并本地 main（5 轮包归档）；F4/F5 真机验收记录在各自包 evidence
### 2.2 关键假设
- F4/F5 改动未引入新设计词汇（新视图复用 workspace-page/table-state/app-state-badge 既有模式）——DESIGN.md 无需变更，记录核查结论即可
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 文档同步与链收口（本包内完成）
### 3.3 不在范围内
- 代码改动（零）；push（未授权）
## 5. 技术决策
- 技术栈：文档轮
- 证据协议：evidence: manual（文档任务）
- 本轮允许改动：PRODUCT.md、.spec/plans/**、本包文档
- 本轮不应触碰：一切代码
- Git integration branch：`spec/2026-10-24_closeout-pm-experience`
### 5.1 验证策略
- 范围级别：package
- 变更对象：PRODUCT.md 与链状态文档
- 快速检查：grep 断言（语言承诺无英文完整备选字样）
- 集成检查：适用外
- 全项目检查：适用外
- 升级触发：适用外（纯文档轮，无触发条件）
### 5.4 编排策略
- route: local
## 6. 成功标准与验证方式
- [ ] 文档同步与收口 -> verify: task-doc-sync
## 7. 风险与约束
- 无
