# 硬化轮 R3/G7：GitHub Pages 介绍页 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：为 MindBase 建公开介绍页（GitHub Pages，源 main/docs）——Azure 淡蓝玻璃设计语言、中文、事实全部出自 PRODUCT.md/README（不虚构客户/数据/证言）
- **目标用户**：作品集受众（面试官/访客）
- **核心价值**：仓库的门面；公开 URL 可直接分享
## 2. 假设与待确认
### 2.1 已确认事实
- gh CLI 未登录；发布走 git 凭据令牌调 API（不回显）；用户指令「最后可以建个 GitHub page介绍页」即发布授权
### 2.2 关键假设
- 单文件自包含页面（内联 CSS/系统字体栈/无外链依赖）足以承载介绍；GitHub Pages 源用 main 分支 /docs
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] docs/index.html 由工作流产出并验证（dwfrun-18445ae8）
- [x] 主会话发布：合并 main → push → Pages 开启 → URL 200（发布执行中，结果回填 evidence）
### 3.3 不在范围内
- 多页站/构建链/CDN；产品代码改动
## 5. 技术决策
- 技术栈：动态工作流（GLM-5.3-Flash individual）；设计遵循 DESIGN.md Azure 玻璃语言 + impeccable Persuade 模式
- 证据协议：evidence: runs（无头验证脚本）
- 本轮允许改动：docs/**（新增）；.spec 文档
- 本轮不应触碰：frontend/src、backend、.env
- Git integration branch：`spec/2026-11-04-github-pages`
### 5.1 验证策略
- 范围级别：package
- 变更对象：docs/index.html
- 快速检查：node 无头脚本（加载、章节齐、零控制台错误、375px 不破版）
- 集成检查：impeccable detect 无 error 级
- 全项目检查：适用外
- 升级触发：适用外（纯新增静态页）
### 5.4 编排策略
- route: build
- 执行段：动态工作流（Flash）：设计 → 无头验证+detect 门禁 → 修理 → 独立复审（事实核对）
## 6. 成功标准与验证方式
- [x] 页面产出并验证 -> verify: task-g7-page
- [x] 发布成功 -> verify: task-g7-publish（https://linixiyi.github.io/mindbase/ 200，2026-11-04）
## 7. 风险与约束
- 公开仓库+公开页：内容涉密扫描零容忍；不出现任何 .env 值/生产 IP
