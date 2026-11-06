# P1c：可观测性起步 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：actuator 健康端点（liveness/readiness 分离探活 + 现有依赖探活如实上报）+ trace id 贯穿（WS→Service→Kafka 生产链同一请求 ID，日志可串联）——可观测性的最小可信起步
- **核心价值**：排障能力从「翻日志文件」升级为「按 trace 串联」
## 2. 假设与待确认
### 2.1 已确认事实
- Spring Boot 3.4 自带 actuator；logback 已配置（./logs/ 滚动）；评估报告可观测性 P1 项
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 两项由工作流落地与验证（dwfrun-f5f065fd + CWE-117 当轮修复）
### 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 本轮允许改动：pom.xml（actuator 为 spring 官方 starter，允许——属基础设施依赖非功能依赖，偏离「不加依赖」红线一次并记录）、src/main/resources/**（application*.yml/logback）、src/main/java/**（trace filter/adapter）、src/test/**
- Git integration branch：`spec/2026-11-07_p1c-observability`
### 5.1 验证策略
- 范围级别：integration
- 快速检查：mvn compile；curl actuator 端点
- 集成检查：mvn test 全量；health 匿名可达仅返回 UP/DOWN 不泄配置（安全约束：health info 收敛）；trace id 同一 WS 请求多行日志同值断言
- 全项目检查：适用外
- 升级触发：无
### 5.4 编排策略
- route: build
- 执行段：动态工作流：单路 → 门禁 → 修理 → 复审
## 6. 成功标准与验证方式
- [x] 两项 -> verify: task-p1c-all
## 7. 风险与约束
- 健康端点暴露面：/actuator/health 只开 health（show-details: never 匿名），management 端口与主端口一致但路径收敛；认证策略与 SecurityConfig 协调（health 匿名放行仅 UP/DOWN）
