# UI 重设计：Apple 级质感视觉世界替换 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：用户明确要求"不希望还有以前界面的样子，要有苹果的高级感"——按 impeccable 重设计（departure）流程，把 MindBase 前端从 SoybeanAdmin 默认观感替换为 Apple 级质感的全新视觉世界（保留产品事实、功能、路由、数据流），并以 PM 视角 + 模拟真人场景审视体验，多轮工作流迭代
- **目标用户**：开发者本人（日常自用）；面试官/雇主（作品集受众，观感直接决定第一印象）
- **核心价值**：界面具备 Apple 级高级感（材质、留白、排版、动效、暗色一致性），且真实可用——服务已全部拉起（后端 8081 + 前端 9527 + 容器化基础设施），可真实登录与对话验证

## 2. 假设与待确认

### 2.1 已确认事实
- 用户 brief：拒绝旧观感、要"苹果的高级感"——departure 模式由用户明确授权（impeccable 重设计规则：保留产品事实，旧视觉只作证据）
- 上一轮（2026-10-19_upgrade-ui-design）产出的 DESIGN.md 将被新世界**替换**（非精修），这是本轮的核心动作
- 服务状态：后端 8081 运行中（dev profile）、前端 9527 运行中、Redis/Kafka/ES/MinIO 容器 healthy、admin 账号可登录、LLM 网关（20128）与 SiliconFlow embedding 连通
- 前端技术底座：Naive UI（themeOverrides 可深度定制）+ UnoCSS + SCSS；系统字体栈在 macOS 上即 SF Pro——Apple 质感的排版有天然基础
- 上一轮已验证的能力继续有效：craft-floor 质量底线、引用标注 DOM/数据流不可破坏、亮暗双主题必须同时成立

### 2.2 关键假设
- Apple 质感主要由 token 层 + 壳层 + 材质/动效承载（字体栈、间距节奏、圆角体系、半透明材质、克制用色、微动效），不需要更换组件库
- 工作流子代理可完成世界定义与代码落地；视觉终验由主线程浏览器执行并反馈进入下一轮
- Naive UI 的 themeOverrides 足以表达新世界的组件形态（圆角、阴影、色彩、字体）

### 2.3 待确认问题
- 无（方向已由用户 brief 钉死：Apple 高级感、替换旧观感）

### 2.4 可选解释与取舍
- 当前选择：多轮小步迭代（本轮=世界定义+token/壳层+旗舰页；反馈后下一轮=其余页面+打磨）-> 大爆炸式全量重写回归面太大且无法逐轮吸收反馈
- 当前选择：PM 视角审查 + 真人场景模拟进工作流，浏览器实操验收留在主线程 -> 浏览器交互长会话不适合无监督子代理

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] 新视觉世界定义：重写 DESIGN.md + sidecar（Apple 级质感世界的完整 token 体系与落地映射）
- [x] token/壳层落地：theme settings、themeOverrides、全局 CSS 变量、壳层（侧栏/顶栏/菜单）换新世界
- [x] 旗舰页落地：chat 页换新世界（含引用标注样式适配、不破坏数据流）
- [x] 门禁通过：typecheck + 全仓 eslint 0 error + impeccable 设计检测器无 error 级发现
- [x] PM 视角审查 + 真人场景模拟（登录→提问→引用→知识库四条用户故事）产出体验发现清单

### 3.2 扩展功能
- [x] 其余页面（chat-history / knowledge-base / personal-center / login 等）换新世界——下一轮按反馈执行

### 3.3 不在范围内
- 后端任何改动；路由结构重组；业务逻辑与 API 契约改动
- i18n 文案语义改动；引用标注 DOM 结构与数据流改动（只许改样式）
- 新增 npm 依赖；登录逻辑改动

## 4. 最小实现路径
- 最简单可行方案：以 token 层（字体栈/间距/圆角/阴影/色彩/材质/动效时长）+ Naive UI themeOverrides 为主战场，壳层与旗舰页做结构性精修，其余页面下一轮跟进
- 暂不引入：新组件库、CSS 框架、动效库（只允许 CSS transition/animation 与 backdrop-filter）、新图标体系（沿用现有 iconify 集）、英文版式专项
- 不做的抽象/配置化：不做主题切换器扩展、不抽独立 design-token 包、不改 packages/@sa/**

## 5. 技术决策
- 技术栈：沿用 Vue 3 + Naive UI + UnoCSS + SCSS；零新增依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 真实执行记录 runs.jsonl；手工任务写 `verify: manual: <理由>`；verify 不得自引用本包 check 门禁）
- 本轮允许改动：`frontend/src/theme/**`、`frontend/src/styles/**`、`frontend/uno.config.ts` token 部分、`frontend/src/layouts/**`、`frontend/src/views/{chat,chat-history,knowledge-base,login}`、`frontend/src/components/**`（样式层）、仓库根 `DESIGN.md`、`.impeccable/design.json`
- 本轮不应触碰：后端、`frontend/src/service/**`、`frontend/src/store/**` 业务语义、i18n 语义、路由结构、引用标注数据流、`frontend/packages/@sa/**`、`.env`、启动脚本
- Git integration branch：`spec/2026-10-20_redesign-ui-apple`

### 5.1 验证策略
- 范围级别：package
- 变更对象：前端样式层/主题层/壳层与 DESIGN.md/sidecar
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：浏览器目检登录→chat→引用→知识库四条用户故事（主线程执行，亮暗双主题截图）
- 全项目检查：适用外：纯前端样式层，无跨模块契约与发布载荷变化
- 升级触发：触碰 store 业务语义、路由结构或共享基础设施时升级范围并记录理由

### 5.4 编排策略
- route: build
- 路由依据：scripts/route_decision.py 机判（orchestration/route-decision.json）
- 执行段：宿主原生动态工作流（用户点名工作流与多轮迭代）；spec workflow-runner 槽不启用（形状路由判 false）
- 分片互斥：世界定义单代理 → 独立复核 → token/壳层单代理 → 页面分片互斥（chat 旗舰 / knowledge-base+chat-history）→ 门禁单代理 → PM 审查单代理
- 主会话保留：浏览器实操验收、反馈汇总、多轮工作流修订（AmendWorkflow）、check/done 门禁

## 6. 成功标准与验证方式
- [x] 新世界 DESIGN.md + sidecar 落地且通过独立复核 -> verify: task-world
- [x] token/壳层/旗舰页换新世界后 typecheck 与全仓 eslint 0 error -> verify: task-implement
- [x] impeccable 设计检测器无 error 级发现 -> verify: task-gate
- [x] PM 视角 + 真人场景模拟发现清单落盘 orchestration/ -> verify: task-pm
- [x] 主线程浏览器目检四条用户故事（亮暗双主题）通过 -> verify: task-browser
- [x] 对照 §4「暂不引入」清单在验收记录中列出被拒绝的扩展点或确认无 -> verify: task-world

## 7. 风险与约束
- 风险点：重设计破坏引用标注渲染链路 -> 缓解：boundary 禁改数据流；主线程浏览器目检带真实对话
- 风险点：新世界在暗色主题下失准 -> 缓解：世界定义必须同时给出亮暗双主题 token；门禁含检测器
- 风险点：半透明材质（backdrop-filter）性能与可读性 -> 缓解：仅用于壳层与浮层，正文区保持实底
- 风险点：工作流子代理回摆到旧观感 -> 缓解：prompts 明确 departure 已获用户授权、旧 DESIGN.md 仅作反参考
