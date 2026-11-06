# 安全 P0：两个越权 IDOR + OCR 匿名可达 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：修复评估实测复现的三个 P0——S1 会话历史读取 IDOR（ConversationController:54-55 直通无属主过滤）、S2 archive/unarchive IDOR（漏 validateConversationOwner）、S3 内部 OCR 端点匿名可达（permitAll + token fail-open）；S4 三场景回归测试防复发
- **目标用户**：全部用户（隐私与完整性）
- **核心价值**：恢复「权限隔离」核心卖点的可信度
## 2. 假设与待确认
### 2.1 已确认事实
- 评估报告 docs/project-assessment-2026-11.md 带实测复现步骤与行号；validateConversationOwner 已存在（rename/delete/WS 在用）
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] S1-S4 由工作流落地与验证（dwfrun-cad1a498 + 复扫第四洞 switch 当轮修复，346/346）
### 3.3 不在范围内
- 评估路线图的 P1/P2（后续链）；push（收口时说）
## 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 证据协议：evidence: runs
- 本轮允许改动：src/main/java/**（Conversation 系/SecurityConfig/AliyunOcrService/InternalOcrController）、src/test/**
- Git integration branch：`spec/2026-11-07_fix-security-p0`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：会话属主校验链与内部端点访问控制
- 快速检查：mvn compile
- 集成检查：mvn test 全量（含新 IDOR 测试）；越权探测脚本双用户实测 403/404；OCR 匿名探活 401/404
- 全项目检查：适用外
- 升级触发：适用外（三个最小修复 + 测试，无结构变更）
### 5.4 编排策略
- route: build
- 执行段：动态工作流：单路（三修耦合在同一资源族）→ 门禁 → 修理 → 复审
## 6. 成功标准与验证方式
- [x] S1-S4 -> verify: task-s-all
## 7. 风险与约束
- 越权修复不得破坏正常属主路径（本人读取/归档仍 200）——测试双面断言
