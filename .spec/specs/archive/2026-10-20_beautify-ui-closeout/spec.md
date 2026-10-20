# UI 美化 R3：autorun 链收口 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：autorun 链收口（特性：收口验证）——R1/R2 引入的新设计词汇回写 DESIGN.md（与实现一致性），全量 detect 门禁，真机抽查，收口复审宣布链完成（5/5）
- **目标用户**：开发者本人 + 作品集受众
- **核心价值**：设计规范与实现同源；链可审计闭环

## 2. 假设与待确认

### 2.1 已确认事实
- R1（表面收口+页头层级）、R2（欢迎区+面板入场+档案头）已归档合并，计划 4/5
- 新词汇需回写 DESIGN.md：.page-header 页头区、::selection/caret 表面规则、聊天欢迎区（建议问法/stagger）、搜索面板 @starting-style、档案头 tile、表格空态排版

### 2.2 关键假设
- detect 对 R1/R2 改动目标保持零 error（各自轮内已过，收口复核全量）

### 2.3 待确认问题
- [x] 链完成标准 → 计划文档 5/5 勾选 + 收口复审零 P0/P1

### 2.4 可选解释与取舍
- 当前选择：DESIGN.md 以 §11 落点表形式回写（沿用第四轮 §10 体例）-> 保持规范文件体例一致

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] DESIGN.md §11：R1/R2 新词汇落点表回写
- [x] 全量 detect 门禁（chat/personal-center/global-search/styles/两个页头视图）
- [x] 真机抽查：欢迎区建议填充 + 页头区 + 选区（合并引用 R1/R2 证据）
- [x] 收口复审文件：宣布链完成（5/5）+ 完成面板

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 一切新观感方向；backlog#11/#12 修复

## 4. 最小实现路径
- 最简单可行方案：文档回写 + 门禁复核 + 复审文件
- 暂不引入：无
- 不做的抽象/配置化：无

## 5. 技术决策
- 技术栈：文档轮
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`）
- 本轮允许改动：DESIGN.md、计划文档、`.spec/autorun/**`
- 本轮不应触碰：产品代码
- Git integration branch：`spec/2026-10-20_beautify-ui-closeout`

### 5.1 验证策略
- 范围级别：package
- 变更对象：DESIGN.md 与链状态文档
- 快速检查：`python3 -c "import json; json.load(open('.spec/autorun/chain.json'))"`
- 集成检查：detect 全量 + 真机抽查
- 全项目检查：适用外：文档收口轮
- 升级触发：detect 暴露 error 级则按所属轮 boundary 回修

## 6. 成功标准与验证方式
- [x] DESIGN.md §11 成文且与实现一致 -> verify: task-doc
- [x] detect 全量零 error -> verify: task-detect
- [x] 真机抽查通过 -> verify: task-browser
- [x] 收口复审产出并宣布链完成 -> verify: task-closeout

## 7. 风险与约束
- 风险点：detect 对新规则（:has、@starting-style）误报 -> 属检测器能力边界时按 finding 语义评估，不盲改
