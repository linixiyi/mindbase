# UI 美化 R2：聊天空态欢迎 + 签名面信念度 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：autorun 链 R2（特性：animate+delight、bolder+overdrive）——聊天空态从"提示去左侧"升级为直接引导提问的欢迎区（建议问法一键填入、一次性 stagger 入场）；全局搜索命令面板获得 @starting-style 即时入场；个人中心档案头身份 tile 品牌化
- **目标用户**：开发者本人 + 作品集受众
- **核心价值**：新对话的第一屏就教会"怎么问"；搜索面板手感更快；品牌一线落进身份区

## 2. 假设与待确认

### 2.1 已确认事实
- 聊天真空态已有基础款（图标 +「开始新对话」+ 指向侧栏的提示），缺"直接提问"的引导与建议问法
- 输入草稿存于 chat store（`chatStore.input = { message, conversationId? }`），建议问法可直接写入，无需新增事件通道
- 「新对话」点击会触发 backlog#11 的 SPA 导航空白（刷新恢复），验证路径采用"点击后刷新"规避
- personal-center 档案头头像 tile 为无姓名灰底；全局搜索为 NModal 命令面板（630px / elevation-3 / 磨砂）

### 2.2 关键假设
- 建议问法三条均为本会话真实回答过的产品能力（引用溯源/混合检索/知识库总结），非虚构宣传
- @starting-style 仅做 transform 首帧，不与 NModal 自带 opacity 渐显冲突；不支持的浏览器保持原状

### 2.3 待确认问题
- [x] recharge 余额区是否本轮强化 → 否：套餐列表为空、购买流不可视，记录为套餐数据就绪后的候选；bolder 落点改为档案头身份 tile（已具备验证条件）

### 2.4 可选解释与取舍
- 当前选择：建议问法点击只填充输入框不自动发送 -> 保留用户修改权（delight 不劫持任务）
- 当前选择：欢迎区文案陈述产品事实（基于知识库检索、句末引用标注）-> 不虚构能力

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] 聊天欢迎区：产品文案 + 三条建议问法胶囊（点击填充输入框）+ 一次性 stagger 入场
- [x] 全局搜索命令面板 @starting-style 入场（desktop top-50px 变体，150ms emphasized）
- [x] personal-center 档案头身份 tile 品牌化（azure 8% 底 + 主色 icon）
- [x] 门禁：typecheck + eslint + detect 零 error
- [x] 真机走查：欢迎区/建议填充/搜索面板/档案头 + 截图
- [x] plan-sync：勾选计划文档 animate+delight、bolder+overdrive 两项 + chain.json

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- recharge 余额区强化（套餐数据就绪后候选）、backlog#11/#12 修复、后端/数据流

## 4. 最小实现路径
- 最简单可行方案：chat-list 空态模板块重写（复用 chatStore.input）；global.scss 一条 @starting-style 规则；personal-center 头像样式两行
- 暂不引入：无
- 不做的抽象/配置化：建议问法为静态常量（非配置化接口）

## 5. 技术决策
- 技术栈：沿用；零新增依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`）
- 本轮允许改动：`frontend/src/views/chat/modules/chat-list.vue`、`frontend/src/views/personal-center/index.vue`、`frontend/src/styles/scss/global.scss`、计划文档、`.spec/autorun/**`、docs/backlog.md（#12 登记）
- 本轮不应触碰：input-box 组件接口、后端、数据流、packages/@sa/**
- Git integration branch：`spec/2026-10-20_beautify-ui-experience`

### 5.1 验证策略
- 范围级别：package
- 变更对象：chat 空态/档案头样式/搜索面板入场
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint src/views/chat src/views/personal-center src/styles`
- 集成检查：真浏览器走查（建议填充/面板/档案头）
- 全项目检查：适用外：纯样式轮
- 升级触发：本轮无升级触发；若后续触碰 theme store / 路由守卫（backlog#11 修复）则按包机制升级验证范围

## 6. 成功标准与验证方式
- [x] 欢迎区上线、建议点击填充输入框 -> verify: task-welcome
- [x] 搜索面板 @starting-style 规则生效且无视觉破坏 -> verify: task-palette
- [x] 档案头 tile 品牌化 -> verify: task-profile
- [x] 门禁与真机走查通过 -> verify: task-gate / task-browser
- [x] 计划文档同步 4/5 -> verify: task-plan-sync

## 7. 风险与约束
- 风险点：@starting-style 与 NModal 过渡叠加 -> 只动 transform，规避 opacity 双重动画
- 风险点：建议问法宽度过宽换行 -> max-width 560px + flex-wrap 已约束
