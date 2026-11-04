# 打磨轮 H1：卫生包 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：补验证债（mvn test 全量跑通）+ 门面（README 挂介绍页链接）+ 复审小修（键帽分平台/触发钮死区/模型配置插值子串/后端旧称呼/refreshToken 500→401）
- **目标用户**：开发者本人 + 作品集受众
- **核心价值**：测试基线可信；门面完整；复审债务清零
## 2. 假设与待确认
### 2.1 已确认事实
- 后端测试此前只跑过 UserServiceTest 21/21；全量状态未知
- 介绍页已上线 https://linixiyi.github.io/mindbase/
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] H1 五项由工作流落地与验证（dwfrun-8f5b530e，门禁全绿含 mvn test 329/329 + 复审基线对照）
### 3.3 不在范围内
- H2/H3 功能项；push（未要求）
## 5. 技术决策
- 技术栈：动态工作流（start plan Flash，配额尽回退 individual Flash）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**、src/main/java/**、src/test/**、README.md
- Git integration branch：`spec/2026-11-04_polish-hygiene`
### 5.1 验证策略
- 范围级别：integration
- 变更对象：测试套件、README、global-search/model-provider/AuthController 小修
- 快速检查：pnpm typecheck；mvn -q -DskipTests compile
- 集成检查：mvn test 全量 exit 0；eslint 0/0；detect 无 error
- 全项目检查：适用外
- 升级触发：测试暴露设计级缺陷（非表面修复可解）时停下记录
### 5.4 编排策略
- route: build
- 执行段：动态工作流：三路并行 → 门禁（含 mvn test 全量）→ 修理 → 复审
## 6. 成功标准与验证方式
- [x] H1 五项 -> verify: task-h1-all
## 7. 风险与约束
- 全量测试未知深水：与近期改动相关的失败必修；疑似远古失败修不动则升级带证据，不硬编跳过
