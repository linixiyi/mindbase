# UI 美化 R1：浏览器表面收口 + 管理页页头层级 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：autorun 链 R1（特性：colorize+quieter、layout+typeset）——①浏览器自有表面（选区/插入符/焦点环）从未主题化到全部走 Azure token（craft-floor「built not assembled」最廉价信号）；②管理页页头区层级（页题+主行动同行）与表格空态排版
- **目标用户**：开发者本人 + 作品集受众
- **核心价值**：细节完成度——用户选中一段文字、点击输入框时也能看到品牌；管理页扫读路径（页题 → 主行动 → 表格）更清晰

## 2. 假设与待确认

### 2.1 已确认事实
- autorun 链计划文档 `.spec/plans/ui-beautification.md` 已建立（5 特性），本轮覆盖前两项
- `::selection` 与 `caret-color` 全仓零主题化（grep 零命中）；scrollbar 已主题化（scrollbar.scss）
- recharge-manage 有独立「新增套餐」主行动钮；org-tag 的 @add 嵌在 TableHeaderOperation（可改 `:addable="false"` 上提）；user 视图 addable=false 本就无 add
- 基底：main（含 Azure 第四/五轮全部成果）；前端 9527 / 后端 8081 运行中

### 2.2 关键假设
- ::selection 用主色 10%–20% 透明底 + 深墨文字即可两主题通吃（选区底淡时墨字对比足够）
- 页头主行动槽复用既有 handler，不新增业务逻辑；TableHeaderOperation 改 `:addable="false"` 不破坏列设置/刷新

### 2.3 待确认问题
- [x] 页头主行动槽是否全站推广 → 否；仅应用于主行动为独立按钮的视图（recharge-manage、org-tag），嵌在 TableHeaderOperation 的保持不动（不破坏共享组件契约），记录为模式先行

### 2.4 可选解释与取舍
- 当前选择：表格空态排版走 global.scss 一条全局规则（`:deep(.n-data-table-empty)` 类层级）→ 全站生效零逐页改动
- 当前选择：caret-color 只作用于文本输入（input/textarea/[contenteditable]）→ 不干扰按钮/链接

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] 浏览器表面主题化：::selection（亮/暗两主题）、输入 caret-color、focus-visible 走查修正
- [x] `.page-header` 页头区模式（页题 + 右侧主行动槽，baseline 对齐）落地 recharge-manage 与 org-tag
- [x] 表格空态全局排版（tertiary 文字、留白节奏）
- [x] 门禁：typecheck + eslint + impeccable detect 零 error
- [x] 真机走查：选区/插入符/页头/空态亮暗各验一遍 + 截图
- [x] plan-sync：勾选计划文档前两项特性 + chain.json 更新

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 其余视图的页头改造、NDataTable 逐页 empty 插槽、后端/业务语义/数据流、packages/@sa/**

## 4. 最小实现路径
- 最简单可行方案：global.scss 增三条表面规则 + 一个 .page-header 区块模式（flex baseline）；两个视图模板小改（按钮上提，handler 复用）
- 暂不引入：新组件、新依赖
- 不做的抽象/配置化：不抽 PageHeader 组件（两处样例先行，出现第三处再抽）

## 5. 技术决策
- 技术栈：沿用；零新增依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`；verify 不得自引用本包 check 门禁）
- 本轮允许改动：`frontend/src/styles/**`、`frontend/src/views/recharge-manage/**`、`frontend/src/views/org-tag/**`、`frontend/uno.config.ts`、计划文档、`.spec/autorun/**`
- 本轮不应触碰：其他视图、后端、业务语义、数据流、packages/@sa/**
- Git integration branch：`spec/2026-10-20_beautify-ui-surface`

### 5.1 验证策略
- 范围级别：package
- 变更对象：全局样式层 + 两个视图模板
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：真浏览器走查（选区/输入符/页头/空态）
- 全项目检查：适用外：纯样式轮
- 升级触发：页头改造若需动 TableHeaderOperation 组件本体则停下回写升级

## 6. 成功标准与验证方式
- [x] 选区/插入符/焦点环全走 token 且亮暗成立 -> verify: task-surface
- [x] recharge-manage/org-tag 页头主行动槽落地、原 TableHeaderOperation 契约不破坏 -> verify: task-page-header
- [x] 表格空态全局排版生效 -> verify: task-empty-state
- [x] 门禁与真机走查通过 -> verify: task-gate / task-browser
- [x] 计划文档同步勾选 -> verify: task-plan-sync

## 7. 风险与约束
- 风险点：::selection 在暗色下过艳 -> 用主色低透明底 + 亮墨，亮暗分别定义
- 风险点：org-tag 上提后 TableHeaderOperation 仅剩列设置/刷新 -> `:addable="false"` 原生支持，功能零损失
