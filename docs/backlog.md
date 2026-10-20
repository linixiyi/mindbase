# MindBase 反馈池 / Backlog

> 三轮 spec 包（2026-10-19_upgrade-ui-design → 2026-10-20_redesign-ui-apple → 2026-10-20_redesign-ui-airy）
> 遗留与观察项的统一清单。明细证据在各包归档：`.spec/specs/archive/<包名>/orchestration/` 与
> 各包 `completion-summary.md` 的「问题处置」章节。新反馈直接追加到本文件末尾。

## 功能 / 后端类

| # | 事项 | 状态 | 来源 | 说明 |
|---|---|---|---|---|
| 1 | WS 重连完成事件疑点 | 待排查 | apple/airy 包 issue-disposition `ws-reconnect-completed-event` | 断线重连时若生成已完成，重连 socket 收到 COMPLETED 快照但收不到终结事件。复现：`scripts/chat-reconnect-smoke-test.mjs --timeout-ms 180000`。下一轮触碰 WebSocket handler / 断线续传时复测 |
| 2 | admin 初始配额种子 | 已临时处理 | apple 包 `admin-quota-seeding` | 初始配额过小（首轮对话即耗尽）。本地已在 Redis 充值（`user:token:llm:1` / `user:token:embedding:1` = 5000000）。正式方案：充值接口或启动种子纳入流程 |
| 3 | ~~KB 引用带数据渲染目检~~ | **已闭环（2026-10-20）** | apple 包 `kb-citation-runtime-visual` | 用户真机点击引用 chip：预览路由态激活（?preview=reference）、预览头（文件名+MD/分数 0.033+新窗口/下载/关闭）与上传文档全文渲染正确，亮色样式符合新世界。截图：airy 包 orchestration/screenshots/ |
| 4 | 浮动侧栏 shadow-2xl 收敛 | 待排期 | apple 包 `materials-shadow-2xl`；DESIGN.md「已知例外」 | `packages/materials/src/libs/admin-layout/index.vue:170`，需动 `@sa/*` 包，单独排期 |
| 5 | settings.ts 死阴影 token 删除 | 待类型放开 | apple 包 `settings-dead-shadow-tokens` | `settings.ts:35-39` 两枚死 token 已注记；物理删除需先改 `src/typings/app.d.ts` 的必填声明 |

## 前端 / 体验类

| # | 事项 | 状态 | 来源 | 说明 |
|---|---|---|---|---|
| 6 | 内嵌浏览器截图光栅化预算 | 观察项 | apple 包 `iab-raster-perf`；airy 包 `iab-raster-kb-heavy` | ZCode 内嵌浏览器软件光栅化下，磨砂顶栏/重 DOM 页面截图准备超 3s。真机 GPU 渲染不受影响；用户真机反馈卡顿时优先降级 backdrop-filter 面积 |
| 7 | vendor Inter 字体网络加载 | 已中和 | 第一轮 chat 旗舰分片 skipped | `vue-markdown-shiki` dist CSS 携带 Google Fonts @import，视图层已 font-family:inherit 覆盖，网络加载本身需动 vendor 才能摘除 |
| 8 | 存量 no-console / console.log | 低优 | airy 包 round-2 记录 | `chat-message.vue` 3 处、`recharge-manage/index.vue` 5 处（313/330/335/345/353），业务调试代码，触碰时顺手清 |
| 9 | 无映射会话的引用 chip 点击静默 | P3 观察 | 2026-10-20 主会话目检 | 经 WS 冒烟脚本创建、未持久化 referenceMappings 的历史消息中，引用 chip 点击无任何响应（正常会话 chip 可打开预览）。确认是否需要提示（如"该消息无引用详情"）或隐藏 chip |
| 10 | dev 模式 SPA 半更新空白态 | 观察项 | azure 包 `spa-half-update-blank` | 改 `settings.ts`/`theme store` 触发 Vite `page reload` 广播后，旧标签页可能进入"路由 title 更新但组件不挂载"的半更新态（main 只剩注释节点、零报错），硬刷新恢复。复现依赖编辑时序，生产构建无此通道。dev 下遇到先整页刷新再排查 |
| 11 | 主题切换后 SPA 导航全空白（P1 级，疑似既有） | 待排查 | azure 收口轮 `theme-switch-nav-wedge` |
| 12 | 个人中心「已用」出现负数 | P3 数据显示 | R2 走查 `quota-negative-used` | 个人中心 Token 配额区「已用 -4,928,533 / 10,000」：已用为负且超过总量（管理员经 Redis 充值种子扩大总额后，已用计算未跟随）。属业务统计口径/展示层缺陷，触碰配额统计时优先核对 used 的计算来源 | **归因实验**（2026-10-20 收口轮）：真机 reload → 点主题切换（View Transitions 已 stub 掉仍复现）→ 之后任意 SPA 导航 main 只剩注释节点（render=54）、零报错，整页刷新恢复；不点主题则 10/10 导航全绿。根因在 themeScheme 切换链（Naive themeOverrides 重渲染 / 路由守卫交互），与第四轮 morph 无关。复现：登录 → 点右上角主题按钮一次 → 侧栏切任意页面。建议下一轮触碰 theme store / 路由守卫时优先排查（疑似影响所有历史版本，生产构建待验证） |

## UI 打磨候选（PM 各轮 P2/P3 汇总）

- ~~顶栏图标体系仍有少量异源图标残留~~（**azure 包已闭环**：全站 10 套图标统一 lucide，23 文件 81 处，grep 零残留）
- usage-monitor「大数字+标签」summary 组未做英雄卡化（craft-floor 默认形态，属可选项）
- 亮色补拍：KB/管理页的像素级截图受 #6 限制，以 DOM 指标验证替代
- 暗色主题在 airy 结构下的深度打磨（本轮亮色优先，暗色仅保证一致无回归）

## 已闭环（存档备查）

- 登录密码正则 P0（61627f0，浏览器实测通过）
- 引用预览浅色孤岛 P1（第二轮修复）
- 画布穿透 P0 + 浮岛死变量 P1（第三轮当轮修复，画布计算样式实测四项全绿）
- Embedding 维度参数单复数（606f50c，真实管线 2048 维实证）
- 前端幽灵依赖 / pnpm 12 配置 / allowBuilds 占位符（606f50c，pre-commit 钩子恢复可用）
- body 裸文本继承 CanvasText 白色（global.css 兜底，双主题复验）
