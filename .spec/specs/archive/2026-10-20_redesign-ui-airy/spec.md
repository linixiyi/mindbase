# UI 重设计第三轮：亮色 airy 结构级大改 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：用户对前两轮的反馈是"第一眼像没改"——本轮执行**亮色 airy + 结构级大改**：亮色为默认主题（暗色保留完整支持），布局骨架结构级重做（悬浮侧栏浮岛、去套盒感、大留白、字号层级拉大），让第一眼就与旧观感肉眼可辨地不同
- **目标用户**：开发者本人 + 作品集受众（第一印象直接决定观感评价）
- **核心价值**：apple.com 气质的 airy 观感，同时保持全部功能可用与零回归

## 2. 假设与待确认

### 2.1 已确认事实
- 用户确认方向：亮色 airy（推荐项）+ 结构级大改（推荐项）
- 用户看当前暗色界面的原话反馈："这个界面不是没什么改动吗，升级在哪"——两轮 token 级精修的可见度不足是本轮的直接动因
- 基底：main 分支（含全部前两轮成果），Quiet Glass 的 token 体系与暗色主题保留可用
- 服务运行中：前端 9527、后端 8081、容器四件套 healthy

### 2.2 关键假设
- 亮色 airy 的结构改写（悬浮侧栏、去套盒、大字号）在 Naive UI themeOverrides + UnoCSS 内可实现
- 默认主题切到 light 后，暗色仍可经右上角切换完整使用

### 2.3 待确认问题
- 无（方向与幅度均由用户确认）

### 2.4 可选解释与取舍
- 当前选择：默认 themeScheme 改为 light -> Apple 气质的主体是亮色 airy；暗色保留完整支持
- 当前选择：chat 页日期筛选条默认不显示（收进图标触发的弹层）-> 前两轮它始终占据回答区顶部注意力

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] DESIGN.md 更新：亮色 airy 结构规范（悬浮侧栏几何、去套盒规则、字号刻度拉大、留白节奏、默认主题=light）
- [x] 壳层结构大改：侧栏改悬浮浮岛（与窗口边缘脱开、圆角+投影）、顶栏融入浮岛或透明化、默认主题 light
- [x] chat 页结构大改：去对话画布套盒、内容列居中加大留白、字号层级拉大、日期筛选条默认隐藏
- [x] 其余页面 airy 化：chat-history / knowledge-base / 管理页组跟随新结构
- [x] 门禁：typecheck + eslint 0 error + 检测器无 error 级
- [x] 浏览器终验：亮色为主截全量对比图，暗色抽查无回归

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 后端、service/store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**、新增依赖

## 4. 最小实现路径
- 最简单可行方案：沿用 Quiet Glass 的 token 体系，把表面几何与布局重写——侧栏浮岛化、套盒拆除、字号/留白拉大、默认主题 light
- 暂不引入：新组件库、动效库、新字体
- 不做的抽象/配置化：不做主题配置器、不动 packages/@sa/**

## 5. 技术决策
- 技术栈：沿用 Vue 3 + Naive UI + UnoCSS + SCSS；零新增依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`；verify 不得自引用本包 check 门禁）
- 本轮允许改动：`frontend/src/theme/**`、`frontend/src/styles/**`、`frontend/uno.config.ts`、`frontend/src/layouts/**`、`frontend/src/views/**`、`frontend/src/components/**` 样式层、`frontend/src/store/modules/theme/` 的 themeScheme 默认值与主题值、DESIGN.md、.impeccable/design.json
- 本轮不应触碰：后端、service、store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**、.env
- Git integration branch：`spec/2026-10-20_redesign-ui-airy`

### 5.1 验证策略
- 范围级别：package
- 变更对象：前端样式/主题/布局层
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：浏览器目检亮色为主全页面对比 + 暗色抽查（主线程）
- 全项目检查：适用外：纯前端样式层
- 升级触发：触碰 store 业务语义或路由结构时升级并记录

### 5.4 编排策略
- route: build
- 路由依据：scripts/route_decision.py 机判（orchestration/route-decision.json）
- 执行段：宿主原生动态工作流第三轮（AmendWorkflow 增量）；分片互斥：世界更新单代理 → 壳层结构单代理 → 页面分片互斥 → 门禁 → PM 复审
- 主会话保留：浏览器终验（本轮必须产出亮暗对比截图）、收敛判断

## 6. 成功标准与验证方式
- [x] DESIGN.md 更新含亮色 airy 结构规范与默认主题决策 -> verify: task-world
- [x] 壳层悬浮浮岛 + 去套盒 + chat 结构大改 + 默认 light 落地，typecheck/eslint 0 error -> verify: task-implement
- [x] 检测器无 error 级发现 -> verify: task-gate
- [x] PM 复审：与旧观感并排对比"第一眼可辨"、无 P0/P1 -> verify: task-pm
- [x] 主线程浏览器亮暗对比截图验收 -> verify: task-browser

## 7. 风险与约束
- 风险点：结构大改引入回归 -> 缓解：分片互斥 + 三重门禁 + 浏览器逐页对比
- 风险点：亮色下磨砂材质失去对比 -> 缓解：亮色用浅灰桌面 + 白浮岛 + 发丝线，材质只给壳层
- 风险点：暗色主题被改坏 -> 缓解：暗色 token 组保留，暗色抽查进终验
