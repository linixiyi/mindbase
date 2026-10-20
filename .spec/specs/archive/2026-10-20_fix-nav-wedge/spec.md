# P1 修复：主题切换/新对话后 SPA 导航空白（backlog#11） - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：定位并最小修复 backlog#11——SPA 导航间歇性渲染空白（router-view 仅剩注释节点），恢复「主题切换/新对话/普通导航」的全链路可用；修复后移除临时调试钩子
- **目标用户**：全部用户（主题切换与新建对话是核心动作）
- **核心价值**：核心交互零空白态；同时为项目消除一个长期的静默可用性地雷

## 2. 假设与待确认

### 2.1 已确认事实（本包前期排查证据，浏览器实测）
- 复现为间歇性：压测中约 1/6 次（点击主题→导航）后进入 wedged 态；「新对话」点击后同样出现
- wedged 态症状：`main` 仅剩 `<!--teleport-->` 与两个 `<!---->` 注释节点，`document.title` 已更新，控制台零 error/warn（含 vue-router 无 match 警告），整页刷新恢复
- **已排除**：①`appStore.reloadFlag` 恒为 true（GlobalContent 的 v-if 无辜）；②路由表恒定 35 条（无 removeRoute）；③`router.currentRoute.value.fullPath === 目标路由`（**router 已完整处理导航**）；④View Transitions morph（stub 后仍复现）
- 残余嫌疑集中在**渲染层**：RouterView 的 slot（Component 解析/子组件渲染）在 wedged 态静默失败，或 Transition(out-in) 状态机卡死
- GlobalContent 结构：`RouterView > Transition(mode=out-in) > KeepAlive(include/exclude) > component :is + v-if=reloadFlag + :key=tabStore.getTabIdByRoute(route)`
- App.vue 已有 dev-only 调试钩子 `window.__mbDebug`（appStore/themeStore/routeStore/router）

### 2.2 关键假设
- wedged 态可通过 `main` 元素的 `__vueParentComponent`（dev 构建保留实例）读到 GlobalContent 的 setupState/subTree，从而区分「Component=undefined」与「子组件渲染抛错被 errorHandler 吞掉」
- 根因可能位于 Transition(out-in) + KeepAlive + 动态组件的组合状态机，或 Naive DiscreteAPI/teleport 与过渡的交互

### 2.3 待确认问题
- [x] 修复策略 → 以最小侵入为原则：优先修 GlobalContent/过渡层的组合缺陷；若根因在 Naive 内部，则在其边界上做防御（如导航后强制重渲染），并如实记录

### 2.4 可选解释与取舍
- 当前选择：先仪器化（全量 console 捕获 + Vue 实例检查）再动手 -> 拒绝盲改

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] 根因定位：wedged 态仪器化取证（Vue 实例/错误捕获/恢复行为）
- [x] 最小修复落地
- [x] 压力验证：主题点击×2 scheme 循环 + 导航 ≥20 次零空白；「新对话」路径正常；morph/reduced-motion/暗色不回归
- [x] 移除临时调试钩子（App.vue）
- [x] backlog#11 状态更新 + 门禁

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 主题持久化策略（resetCacheStrategy）、其他 backlog 项

## 4. 最小实现路径
- 最简单可行方案：定位到具体失效层后做点状修复；禁用 out-in 过渡是最后手段（属可感知行为变化，需记录）
- 暂不引入：无
- 不做的抽象/配置化：无

## 5. 技术决策
- 技术栈：沿用；零新增依赖
- 证据协议：evidence: runs（机器任务 verify 由 spec_verify.py 执行；手工任务写 `verify: manual: <理由>`）
- 本轮允许改动：`frontend/src/layouts/**`、`frontend/src/App.vue`（移除钩子）、`frontend/src/store/**` 若根因指向、`frontend/src/router/**`、docs/backlog.md
- 本轮不应触碰：后端、业务 service、packages/@sa/**（若必须触碰则停下回写升级）
- Git integration branch：`spec/2026-10-20_fix-nav-wedge`

### 5.1 验证策略
- 范围级别：package
- 变更对象：前端导航/渲染链路
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：真浏览器压测（见任务）
- 全项目检查：适用外：单缺陷修复轮
- 升级触发：根因指向 packages/@sa/** 的 admin-layout 时，评估就地修复并记录（该包已有一处既往就地修改先例）

## 6. 成功标准与验证方式
- [x] 根因有实验证据支撑 -> verify: task-rootcause
- [x] 修复后压测零空白 -> verify: task-fix
- [x] 功能不回归（morph/暗色/新对话） -> verify: task-fix
- [x] 调试钩子移除、门禁通过 -> verify: task-cleanup
- [x] backlog#11 关闭或降级 -> verify: task-backlog

## 7. 风险与约束
- 风险点：间歇性缺陷难以稳定复现 -> 压测循环至复现（历史复现率 ~1/6），修复后同口径压测对照
- 风险点：根因在第三方库深处 -> 在边界做防御性修复并如实标注
