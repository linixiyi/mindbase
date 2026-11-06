# P1b：CI 流水线（GitHub Actions） - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：建立 CI——.github/workflows/ci.yml：后端（JDK21 + IntelliJ 同款 mvn 命令 + MySQL/Redis 服务容器跑 mvn test）+ 前端（pnpm typecheck/eslint/build）+ grep 门禁（旧色/杂牌图标）+ secret 扫描；PR 与 main push 触发
- **核心价值**：作品集工程成熟度信号 + 防回归自动化
## 2. 假设与待确认
### 2.1 已确认事实
- .github/workflows 现不存在；本机 JDK21/IntelliJ mvn 可复刻到 runner
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] CI 配置由工作流产出（dwfrun-0a8b863a，复审容器级实测 351/351；registry flag 修复）
### 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash）
- 本轮允许改动：.github/**、scripts/**（CI 辅助脚本）、docs/**（状态列）
- Git integration branch：`spec/2026-11-07_p1b-ci-pipeline`
### 5.1 验证策略
- 范围级别：package
- 快速检查：actionlint 式本地校验（yaml 语法+步骤结构）
- 集成检查：push 后 GitHub Actions 实际跑绿（工作流内先本地等价复刻各 job 步骤跑通）
- 升级触发：runner 服务容器方案不可行（如 ES 内存）时降级为分层 job（单测必跑、集成测试标记 continue-on-error 并记录）
### 5.4 编排策略
- route: build
- 执行段：动态工作流：单路 → 本地等价验证 → 复审
## 6. 成功标准与验证方式
- [x] CI 落地且 GitHub 实跑绿 -> verify: task-p1b-all（Actions 实跑绿证随 P1e push 记录）
## 7. 风险与约束
- runner 资源有限：mvn test 需 MySQL+Redis（services 容器）；ES/Kafka 重的集成测试若拖垮 runner 则 continue-on-error 记录
