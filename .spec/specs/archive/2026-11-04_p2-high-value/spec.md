# 硬化轮 R2/G6：P2 高价值（toggle bug + 命令面板 + 术语 IA）- 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：G5 体验产出的三项——①新 bug：登录→chat 导航 3 次 `PAGEERROR: Cannot read properties of undefined (reading 'toggle')` 修复；②pm-014 命令面板 Ctrl/Cmd+K 全局绑定 + 内容升级（菜单 + 知识库检索，复用既有 KB 检索端点）；③pm-029/030/031 术语与 IA 统一（模型供应商称呼、租户概念、菜单 order 与命名）
- **目标用户**：全部用户
- **核心价值**：可发现性与专业感——快捷键/全局搜索到位、术语不再三名并存、零控制台错误
## 2. 假设与待确认
### 2.1 已确认事实
- G5 实测：Ctrl+K 无绑定；面板搜「混合检索」无数据；toggle PAGEERROR ×3（evidence/g5-experience.md）
### 2.2 关键假设
- KB 检索对话框已有端点可复用进命令面板；术语统一纯 i18n/文案层（路由与数据契约不动）
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] 三项由工作流落地与验证（dwfrun-55605592）
### 3.3 不在范围内
- G7 Pages；后端新端点（复用既有）；push
## 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash start plan）
- 证据协议：evidence: runs
- 本轮允许改动：frontend/src/**（含 i18n、global-search、layouts）；不改路由 path 与后端
- Git integration branch：`spec/2026-11-04_p2-high-value`
### 5.1 验证策略
- 范围级别：package
- 变更对象：global-search 模块、layouts、i18n 文案、toggle 缺陷点
- 快速检查：pnpm typecheck；eslint 改动文件 0/0
- 集成检查：无头 Playwright 冒烟（登录→chat 零 PAGEERROR；Ctrl+K 开面板；关键词命中菜单+KB）；typecheck/eslint 全量
- 全项目检查：适用外
- 升级触发：术语统一牵出数据契约时停下记录
### 5.4 编排策略
- route: build
- 执行段：动态工作流（Flash）：三路并行 → 冒烟脚本门禁 + 六门禁 → 修理 → 独立复审
## 6. 成功标准与验证方式
- [x] 三项落地 -> verify: task-g6-all
## 7. 风险与约束
- toggle bug 根因未知——诊断优先，修不动就升级带证据
- i18n 键名改动影响面——只改 value 不改 key（除非零消费）
