# 打磨轮 H4：管理后台组 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：pm-028 rerank 供应商与 LLM 故障转移链可见可测；pm-027 管理员全量充值订单视图+支付状态自动刷新；pm-026 组织标签层级可见+全量级联；pm-025 邀请码启用/禁用双向+有效期暴露
- **目标用户**：管理员
- **核心价值**：企业治理面完整可信
## 2. 假设与待确认
### 2.1 已确认事实
- 锚点判据在 backlog 四条目；模型供应商配置表/邀请码有效期等后端能力部分已存在
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 四项由工作流落地与验证（dwfrun-90bfd6e5，复审实测 + 主会话四视图走查）
### 3.3 不在范围内
- 其他轮项；push
## 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**（model-provider/recharge-manage/org-tag/invite-code 视图）、src/main/java/**（如需最小端点/字段暴露）、src/test/**
- Git integration branch：`spec/2026-11-04_admin-visibility`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：四个管理视图与其后端支撑
- 快速检查：typecheck；mvn compile
- 集成检查：eslint 0/0；mvn test 全量；detect；无头冒烟（四视图可达可操作）
- 全项目检查：适用外
- 升级触发：rerank 可测需真实外呼时停下记录（mock 或跳过实测）
### 5.4 编排策略
- route: build
- 执行段：动态工作流：两路并行（模型链可见 / 治理三视图）→ 门禁 → 修理 → 复审
## 6. 成功标准与验证方式
- [x] 四项 -> verify: task-h4-all
## 7. 风险与约束
- pm-028「可测」以配置生效链可见为界（不强制真实 rerank 外呼）
