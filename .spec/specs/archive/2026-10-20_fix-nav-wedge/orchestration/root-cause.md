# 根因定位 · backlog#11 SPA 导航空白

## 实验证据链（真机仪器化取证，2026-10-20）

| # | 实验 | 结果 | 结论 |
| --- | --- | --- | --- |
| 1 | wedged 态读 `appStore.reloadFlag` | 恒为 true | GlobalContent 的 `v-if="reloadFlag"` 无辜 |
| 2 | wedged 态读 `router.getRoutes().length` | 恒 35，无增减 | 无路由记录被移除 |
| 3 | wedged 态读 `router.currentRoute.value.fullPath` | === 目标路由（如 /model-provider） | router 已完整处理导航 |
| 4 | wedged 态读 `currentRoute.matched` | 两层 [layout(无名), 'model-provider']，components.default 为对象 | 匹配链完整 |
| 5 | stub 掉 `document.startViewTransition` 后复现 | 仍空白 | 第四轮 morph 无辜 |
| 6 | KeepAlive 实例 subTree 解剖 | **子节点 = COMMENT vnode，无组件实例** | 渲染层在该帧拿到了"空"的 slot 结果 |
| 7 | unhandledrejection 捕获 | `request is not defined`（伴随出现） | 陈旧模块编译产物共现症状（Vite transform 陈旧，与 logo.svg 缓存事件同类），非空白根因 |
| 8 | morph/导航压测 14 轮 | 前 5 轮正常、第 6 轮起空白（两次独立压测同位复现）；另一会话 10/10 全绿 | 间歇性、与过渡状态机时序相关 |

## 根因结论

**机制**：GlobalContent 的 `RouterView > Transition(mode=out-in) > KeepAlive > component(:is + v-if)` 组合中，某一帧 slot 渲染拿到瞬态 falsy（Component=undefined 或 v-if 瞬时 false），KeepAlive/Transition 将该注释节点定格后**再无任何响应式变更触发重渲染**——页面永久停留在空白帧。瞬态 falsy 的精确触发源位于 Vue Transition/KeepAlive 状态机与 Naive 全量重渲染（themeOverrides 身份变更等）的交互内部，不在本项目代码行上（证据 #3/#4 排除本项目路由层，#1 排除本项目渲染条件）。

## 修复策略（边界自愈）

既然无法在第三方状态机内部阻断瞬态，就在**渲染边界自愈**：`router.afterEach` 后给进入帧 400ms，若内容容器仍无元素子节点，翻转 `appStore.reloadFlag`（应用自身的 remount 通道，reloadPage(50)）强制重渲染当前视图。健康导航在 ~250ms 完成进入帧，检查点不触发；wedged 帧在 ~450ms 内自动恢复。

## 修复后验证（同口径压测）

- 14 轮（主题点击+导航）压测：**0 瞬态空白、0 最终空白**（含原必现的第 6 轮 model-provider）
- 「新对话」路径：点击后欢迎区/输入框正常出现（原必现空白点）
- 回归：morph（主题切换揭示）正常、暗色导航正常、欢迎区/亮暗主题无回归
- 压测记录：fix-verify.md
