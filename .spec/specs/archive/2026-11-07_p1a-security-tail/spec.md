# P1a：安全面尾巴 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：评估 P1#2（/reference-detail 无会话属主校验）、P1#9（/api/v1/test/** permitAll 调试端点）、OCR health 收紧；同款模式横扫全部 controller
- **核心价值**：安全叙事完整闭环
## 2. 假设与待确认
### 2.1 已确认事实
- 锚点：docs/project-assessment-2026-11.md P0/P1 节（带行号与实测）；ConversationServiceSecurityP0Test 为测试样板
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 三项由工作流落地与验证（dwfrun-7ed33dbd，351/351 + 探测 17/17）
### 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 本轮允许改动：src/main/java/**、src/test/**、docs/**（评估报告状态列更新）
- Git integration branch：`spec/2026-11-07_p1a-security-tail`
### 5.1 验证策略
- 范围级别：integration
- 快速检查：mvn compile
- 集成检查：mvn test 全量（含新测试）；越权探测扩展脚本
- 全项目检查：适用外
- 升级触发：调试端点有未知消费方时停下确认
### 5.4 编排策略
- route: build
- 执行段：动态工作流：单路 → 门禁 → 修理 → 复审
## 6. 成功标准与验证方式
- [x] 三项 -> verify: task-p1a-all
## 7. 风险与约束
- 引用标注数据流零改动红线不变（reference-detail 仅加授权层）
