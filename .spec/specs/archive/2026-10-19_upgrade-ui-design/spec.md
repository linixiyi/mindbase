# UI 升级：impeccable 设计体系落地与界面打磨 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：把 MindBase 前端界面在现有 SoybeanAdmin/Naive UI 体系内做一轮设计体系化升级——先用 impeccable document 把现状沉淀为 DESIGN.md，再按 critique → polish 流程把界面打磨到与「企业级 RAG 作品集」定位相称的观感
- **目标用户**：开发者本人（日常自用，设计决策第一优先级）；面试官/雇主（作品集受众，界面必须经得起审视）
- **核心价值**：界面有一份可执行的视觉规范（DESIGN.md + sidecar），后续 live/polish/critique 命令都能跑；关键页面观感明显提升且不回归可用性

## 2. 假设与待确认

### 2.1 已确认事实
- PRODUCT.md 已存在于仓库根（init 轮已确认产品定位、用户、品牌承诺）
- 用户已确认视觉方向：创意北极星「静谧指挥台」、阴影哲学「平面为主」、组件气质「克制精密」
- 现有体系：SoyadminAdmin 脚手架（`@sa/*` workspace 包）+ Naive UI + UnoCSS + SCSS，主色 `#646cff`（靛紫），亮/暗双主题
- 前端 dev server 可运行在 http://localhost:9527（本包预检中修复幽灵依赖后已验证启动成功）
- 幽灵依赖已修复（三项收口）：`@iconify/utils@2.3.0`、`@playwright/test` 补声明进 devDependencies；pnpm 12 不再读 `.npmrc` 的 shamefully-hoist，已在 `pnpm-workspace.yaml` 加 `shamefullyHoist: true` 并强制重链；typecheck 从 7 个模块解析错误恢复全绿
- impeccable live 模式已配置（`frontend/.impeccable/live/config.json`），项目无 CSP
- spec workflow-runner 槽形状路由判定 `workflowRecommended=false`（score 0）；执行段按用户点名使用宿主原生动态工作流，编排资产落本包 `orchestration/`

### 2.2 关键假设
- SoyadminAdmin 的主题设置（`src/theme/settings.ts`、uno.config.ts、Naive UI themeOverrides）足以承载本轮精修，不需要引入新样式基础设施
- 工作流子代理在没有浏览器的条件下可以完成代码层 critique 与 polish；视觉验收由主线程浏览器截图兜底
- 界面文案（i18n）只做必要的微调或不动，文案体系不重构

### 2.3 待确认问题
- 无（北极星、阴影哲学、组件气质均已由用户确认）

### 2.4 可选解释与取舍
- 当前选择：在现有体系内精修而非重设计 -> 用户诉求是「升级好看点」而非换皮；保留 incumbent 身份是 impeccable refinement 的默认契约
- 当前选择：浏览器截图验收放在主线程而非工作流内 -> 浏览器控制需要长会话与人工比对，交给编排子代理不可靠

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] DESIGN.md + `.impeccable/design.json` sidecar 落地（impeccable document scan 模式，含用户确认的定性语言）
- [x] 关键页面 critique：chat、chat-history、knowledge-base、全局壳层（布局/侧边栏/主题），产出带严重度分级的发现清单
- [x] polish 实施修复：按发现清单分片执行，typecheck 必须通过
- [x] 视觉验收：impeccable 设计检测器无 error 级发现 + 浏览器截图目检（亮/暗主题、引用标注渲染完整）

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 后端任何改动（Java、配置、数据库）
- 路由结构、页面信息架构重组
- 业务逻辑与 API 契约改动（含聊天引用映射链路——只保证不回归）
- 新字体、新图标体系、动效库引入
- 登录页之外的新增页面
- Git push（本轮只做包内提交，不合并不推送）

## 4. 最小实现路径
- 最简单可行方案：token 级 + 组件细节精修——统一主色运用密度、卡片/边框/圆角节奏、排版层级、暗色主题一致性；优先用 Naive UI themeOverrides 与 UnoCSS token，而非散落的 `:deep` 覆盖
- 暂不引入：动效编排（只允许 CSS transition 微调）、新字体栈、组件库替换、设计 token 独立 npm 包
- 不做的抽象/配置化：不重构 `packages/@sa/*`、不做主题可视化配置器、不把页面组件重写为新的抽象层；理由——本轮目标是观感提升，结构性重构会放大回归面且与「最小端到端修改」原则冲突

## 5. 技术决策
- 技术栈：沿用 Vue 3 + Naive UI + UnoCSS + SCSS；不引入新运行时依赖（task_0001 的 `@iconify/utils` 是对既有幽灵依赖的补声明，版本与锁文件一致）
- 证据协议：evidence: runs（机器任务 verify 命令由 scripts/spec_verify.py run 真实执行并记录 runs.jsonl；无命令任务写 `verify: manual: <理由>`；verify 命令不得自引用本包 check 门禁）
- 本轮允许改动：`frontend/src` 下视图组件的模板/样式层与主题 token 文件（`src/theme/`、`uno.config.ts` 的 token 部分）、`frontend/package.json` 与 `pnpm-lock.yaml`（依赖补声明）、仓库根 `DESIGN.md`、`.impeccable/design.json`、本包文档
- 本轮不应触碰：`src/main/java/**`、`frontend/src/service/**`（API 层）、`frontend/src/store/**` 的业务语义、i18n 文案语义、聊天引用映射的持久化与渲染链路、`frontend/packages/@sa/**`
- Git integration branch：`spec/2026-10-19_upgrade-ui-design`

### 5.1 验证策略
- 范围级别：package
- 变更对象：frontend 视图组件样式层、主题 token、DESIGN.md/sidecar
- 快速检查：`cd frontend && pnpm typecheck`；改动文件过 `pnpm exec eslint <files>`
- 集成检查：浏览器目检 chat 渲染与 chat-history 引用标注（task_0006 手工取证，覆盖唯一受影响的用户路径）
- 全项目检查：适用外：本轮不涉及共享基础设施、跨模块契约或发布载荷；由发布门禁统一执行
- 升级触发：只有改动共享基础设施、跨模块契约、构建/依赖、发布载荷，或 package/integration 检查暴露跨边界回归时，才把范围升级并记录理由（task_0001 动了 package.json，已在此记录：仅补声明既有使用，无版本变化）

### 5.4 编排策略
- route: review
- 路由依据：scripts/route_decision.py 机判（结果存 orchestration/route-decision.json；风险标签为 spec 文档 token 字样触发的凭据误报，实际边界为前端样式层）
- 执行段：宿主原生动态工作流（用户点名「工作流」；spec workflow-runner 槽路由判 false 不启用）
- 所有权分片互斥：document 单代理；critique 按页面只读并行；polish 按（chat+chat-history / knowledge-base / 全局主题壳层）三片互斥源码所有权；verify 单代理
- 主会话保留：合并、浏览器截图验收、check/done 门禁

## 6. 成功标准与验证方式
- [x] dev server 可正常启动（幽灵依赖修复落地） -> verify: task-env-fix
- [x] DESIGN.md 存在且含 YAML frontmatter 与规范 8 节结构，sidecar 为合法 JSON -> verify: task-design-doc
- [x] critique 发现清单覆盖 chat / chat-history / knowledge-base / 全局壳层且带严重度分级 -> verify: task-critique
- [x] polish 改动后 frontend typecheck 通过 -> verify: task-polish
- [x] impeccable 设计检测器对本轮改动 UI 目标无 error 级发现 -> verify: task-detect
- [x] 浏览器目检：chat / chat-history（含引用标注渲染链路）/ knowledge-base 在亮暗两主题下无布局回归 -> verify: task-visual
- [x] 对照 §4「暂不引入」清单在验收记录中列出被拒绝的扩展点或确认无 -> verify: task-polish

## 7. 风险与约束
- 风险点：并行子代理编辑共享文件冲突 -> 缓解：所有权分片互斥（全局主题片单独一个代理，页面片不碰共享文件）
- 风险点：样式改动回归聊天引用标注渲染 -> 缓解：boundary 禁改业务逻辑；task_0006 浏览器目检引用标注
- 风险点：Naive UI 组件 `:deep` 覆盖在版本升级或暗色下脆弱 -> 缓解：优先 themeOverrides 与 CSS 变量，`:deep` 仅限无法用 token 表达的细节
- 风险点：工作流子代理偏离 impeccable 质量底线 -> 缓解：prompts 内嵌 craft-floor 关键禁令并要求先读 craft-floor.md
