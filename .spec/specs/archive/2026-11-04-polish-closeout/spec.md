# 打磨轮 H7：收口 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：打磨轮收口——H1–H6 全旅程体验复审（无头走查新能力）、README 能力清单刷新、链完成声明、完成摘要、统一 push（用户此前已授权 push 场景延续：介绍页发布时明确要求 push，本轮成果随链尾推送）
- **目标用户**：维护者与受众
- **核心价值**：链可审计闭环
## 2. 假设与待确认
### 2.1 已确认事实
- H1–H6 六包全绿收敛；backlog 32 项全部落地或记录；push 曾获用户明确授权（介绍页）且用户要求「继续」的既有模式
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 收口由主会话执行（11/11 journey + README 刷新 + 链声明 + push）
### 5. 技术决策
- 技术栈：文档轮 + 无头走查
- 证据协议：evidence: manual
- 本轮允许改动：README.md、.spec 文档
- Git integration branch：`spec/2026-11-04-polish-closeout`
### 5.1 验证策略
- 范围级别：package
- 变更对象：README 与链状态文档
- 快速检查：grep 断言
- 集成检查：无头全旅程走查
- 全项目检查：适用外
- 升级触发：适用外（纯文档收口轮）
### 5.4 编排策略
- route: local
## 6. 成功标准与验证方式
- [x] 收口完成 -> verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/archive/2026-11-04-polish-closeout/evidence/journey.md && ! grep -q "待补充" /Users/linyixi/PaiSmart/README.md`
## 7. 风险与约束
- push 前出站扫描（AGENTS.md）
