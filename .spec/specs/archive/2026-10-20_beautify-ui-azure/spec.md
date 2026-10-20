# UI 美化第四轮：淡蓝主题 + impeccable 八指令 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：用户 brief——「美化 UI，主题色是淡蓝色」，经 impeccable 的 animate / bolder / colorize / delight / layout / overdrive / quieter / typeset 八指令执行一轮完整美化：强调色从靛蓝 `#5856D6` 换血为淡蓝（apple.com 蓝 `#0071E3`），亮色中性面染上冷蓝，动效/排版/布局按已提交的「静光玻璃」世界观做一轮「该放大的放大、该收敛的收敛」，并根据本轮 review 反馈继续拉起新轮次（不限轮数，由主会话在 done 后评估）
- **目标用户**：开发者本人 + 作品集受众；Operate 模式（企业知识库工作台，可扫读、一致、原生预期优先，品牌活在精确细节里）
- **核心价值**：淡蓝主题带来的清爽第一印象 + 八指令叠加后的完成度（动效有目的、层级无歧义、噪音更低），同时全部功能零回归

## 2. 假设与待确认

### 2.1 已确认事实
- 用户 brief 明确：主题色淡蓝色；八指令全集；「根据工作流的反馈再拉起新的工作流（不限轮数）」= 预授权多轮自主迭代
- 基底：main（含前三轮「静光玻璃」成果：亮色 airy、浮岛壳层、聊天去盒、排版放大）
- 前三轮的靛蓝 `#5856D6` 与本轮「淡蓝色」brief 冲突处，brief 优先（impeccable：The brief wins）；world 其余语言（白底、浮岛、发丝线、五档圆角、系统字体栈）保留
- 服务状态：基础设施（MySQL/Redis/ES/Kafka）运行中；前端 9527 已由本会话拉起；后端 8081 已由本会话经 IDEA 内置 maven 拉起
- 第三轮落地缺口实锤：`.page-title` 页题只落在 2/10 个管理视图（chat-history、knowledge-base），DESIGN.md §9 要求 10/10——本轮 typeset/layout 的确定性工作项
- `.impeccable/design.json` sidecar 落后于 DESIGN.md（context 报 design-sidecar-stale，severity=mention）——本轮 task-world 一并刷新

### 2.2 关键假设
- 换强调色走既有管线零风险：`recommendColor: false` 下 500 阶 = 基础色原值，hover/pressed 由 600/700 阶自动派生，暗色经 theme store `themeColors` 单点换血
- `#0071E3` 白字对比 4.70:1（≥4.5 AA 达标，本会话已手算核验）；暗色同用 `#0071E3`（暗色下作填充色，文字用自动派生的亮阶 `-400`）
- View Transitions API（Chrome 同文档支持）做主题切换 morph 零依赖可行，不支持时优雅降级为现有 150ms 过渡

### 2.3 待确认问题
- [x] overdrive 提案环节 → 按用户预授权自主取舍（brief 即授权，记录于 §2.4），后续轮次如需高风险方向再行请示
- [x] 执行中途用户追加 logo 换新 + 图标换 lucide → 已并入本轮任务（task-logo/task-icons），boundary 补录完成
- [x] 主题切换按钮事件链双发（每次点击跳两档 scheme）→ 轮内以 400ms 闩锁修复，机器验证 vt delta=1/次

### 2.4 可选解释与取舍
- 当前选择：淡蓝锚定为 **apple.com 蓝 `#0071E3`**（而非 iOS systemBlue `#007AFF`）-> `#007AFF` 白字 4.02:1 不达 AA；`#0071E3` 是 Apple 官网真实按钮蓝，观感同为明快淡蓝且 4.70:1 达标
- 当前选择：亮色中性面整体染冷蓝（地面 `#F2F6FC`、冷墨色阶、蓝色调 fill）-> colorize 的「Tint neutrals when the brand hue creates cohesion」；只染亮色组，暗色组数值原样保留（沿用第三轮「暗色零改动」约定，限制爆炸半径）
- 当前选择：overdrive 方向 = **主题切换 View Transitions 圆形揭示 morph**（Operate 模式的 extraordinary 在「手感」而非视觉奇观）+ 聊天流式/引用的微动效；否决粒子/着色器（运营工具语境下是尴尬而非惊艳）、否决虚拟滚动表格（无十万行数据规模）
- 当前选择：delight 时刻 = 流式回答「呼吸」状态点 + 知识库上传成功勾绘动画；从产品机制（流式等待、上传处理链路）派生，不做无来由的彩蛋

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] DESIGN.md 第四轮修订：Azure 世界规范（淡蓝强调、冷蓝中性面、动效论纲、delight 时刻清单）；`.impeccable/design.json` 刷新
- [x] colorize 换血：settings.ts / theme store / global.scss / uno.config 的强调色与亮色中性面切淡蓝，语义色管线核验（对比度）
- [x] typeset+layout 收口：`.page-title` 补齐 8 个缺失管理页；排版/留白走查修复（detector + 实页）
- [x] animate+delight+overdrive 落地：主题切换 morph、聊天与引用微动效、上传成功时刻、`prefers-reduced-motion` 兜底；零新增依赖
- [x] quieter+bolder 审计：换血后强调色面积 ≤10% 核验、装饰性残留清理、签名面（页题/画布）信念度拉满
- [x] 门禁：typecheck + eslint 0 error + impeccable detect 无 error 级
- [x] 浏览器终验：真机登录亮色全量走查 10 视图 + chat，暗色抽查无回归；截图落 orchestration/screenshots/
- [x] 【用户中途追加】品牌 logo 换新（「心智基座」M+火花 SVG）与 UI 图标统一换 lucide（10 套→1 套、零新依赖）
- [x] PM 复审走查文件：orchestration/pm-review.md（P0/P1 计数 + 下一轮建议），作为「工作流反馈」决定是否拉起新轮次

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 后端、service/store 业务语义、路由结构、i18n 语义、引用标注 DOM/正则/referenceMappings 数据流、packages/@sa/**、新增依赖、暗色主题 token 数值

## 4. 最小实现路径
- 最简单可行方案：全部改动落在既有 token 管线（settings.ts themeColor/otherColor、theme store themeColors、global.scss 变量、uno.config 别名）+ 视图样式层；动效用 CSS View Transitions + 既有 150/250/350ms token，零 JS 动效库
- 暂不引入：动效库（motion/GSAP）、新字体、新组件库、WebGL
- 不做的抽象/配置化：不做主题配置器、不改 packages/@sa/**

## 5. 技术决策
- 技术栈：沿用 Vue 3 + Naive UI + UnoCSS + SCSS；View Transitions API 为浏览器原生能力，非依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`；verify 不得自引用本包 check 门禁）
- 本轮允许改动：`frontend/src/theme/**`、`frontend/src/styles/**`、`frontend/uno.config.ts`、`frontend/src/layouts/**`、`frontend/src/views/**`、`frontend/src/components/**` 样式层、`frontend/src/store/modules/theme/` 主题值、DESIGN.md、.impeccable/design.json；【用户追加项补录】`frontend/src/router/elegant/routes.ts` 与其余 `frontend/src/**` 的图标名字符串（task-icons）、`frontend/src/assets/svg-icon/logo.svg` 与 `frontend/public/favicon.svg`（task-logo）
- 本轮不应触碰：后端、service/store 业务语义、路由结构、i18n 语义、引用标注数据流（只许改 linkClass/`.citation-*` CSS）、packages/@sa/**、.env、暗色 token 数值
- Git integration branch：`spec/2026-10-20_beautify-ui-azure`

### 5.1 验证策略
- 范围级别：package
- 变更对象：前端主题/样式/视图层 + DESIGN.md 规范文件
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：真浏览器登入走查（chat + 10 管理视图）+ impeccable detect --json 全目标——引用 chip 渲染路径必须实页核对（引用预览不得回归）
- 全项目检查：适用外：本轮不涉及共享基础设施、跨模块契约或发布载荷；由发布门禁统一执行
- 升级触发：若 View Transitions 需要触碰 packages/@sa/** 或路由层，则停下来回写本包并升级评审

## 6. 成功标准与验证方式
- [x] 强调色淡蓝换血且语义管线对比度达标 -> verify: task-colorize
- [x] 页题 10/10 落地、排版/布局 detector 无 error -> verify: task-typeset-layout / task-gate
- [x] 动效论纲落地且 reduced-motion 兜底有效 -> verify: task-motion
- [x] 噪音审计通过（强调色面积 ≤10%、无装饰性阴影回归）-> verify: task-quieter-bolder
- [x] 真机亮暗走查零 P0/P1、截图证据落盘 -> verify: task-browser
- [x] 复审文件产出并给出下一轮决策 -> verify: task-pm

## 7. 风险与约束
- 风险点：染冷蓝的中性面让 fill-subtle/hover 透明度观感变化 -> 走查次级按钮/列表 hover，必要时微调 alpha 而非换色相
- 风险点：View Transitions 在 Naive 组件挂载的 DOM 上失效或不触发 -> 只绑根级主题切换入口，`document.startViewTransition` 存在性探测 + 无 API 直接走旧路径
- 风险点：新蓝与语义色（success 绿/error 红）同屏关系 -> 语义色只在状态出现时进场，走查覆盖错误态/成功态
- 约束：引用标注只许 CSS 层改动，改后必须实页验证 chip 渲染与预览
