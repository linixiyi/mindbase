# MindBase 反馈池 / Backlog

> 三轮 spec 包（2026-10-19_upgrade-ui-design → 2026-10-20_redesign-ui-apple → 2026-10-20_redesign-ui-airy）
> 遗留与观察项的统一清单。明细证据在各包归档：`.spec/specs/archive/<包名>/orchestration/` 与
> 各包 `completion-summary.md` 的「问题处置」章节。新反馈直接追加到本文件末尾。

## 功能 / 后端类

| # | 事项 | 状态 | 来源 | 说明 |
|---|---|---|---|---|
| 1 | WS 重连完成事件疑点 | 待排查 | apple/airy 包 issue-disposition `ws-reconnect-completed-event` | 断线重连时若生成已完成，重连 socket 收到 COMPLETED 快照但收不到终结事件。复现：`scripts/chat-reconnect-smoke-test.mjs --timeout-ms 180000`。下一轮触碰 WebSocket handler / 断线续传时复测 |
| 2 | admin 初始配额种子 | 已临时处理 | apple 包 `admin-quota-seeding` | 初始配额过小（首轮对话即耗尽）。本地已在 Redis 充值（`user:token:llm:1` / `user:token:embedding:1` = 5000000）。正式方案：充值接口或启动种子纳入流程 |
| 3 | KB 引用带数据渲染目检 | 可复核 | apple 包 `kb-citation-runtime-visual` | 当时被"后端未启动"阻塞。现在后端已在跑且带真实数据（mindbase-e2e-test.md），打开任意带引用的对话点 chip 即可闭环此目检 |
| 4 | 浮动侧栏 shadow-2xl 收敛 | 待排期 | apple 包 `materials-shadow-2xl`；DESIGN.md「已知例外」 | `packages/materials/src/libs/admin-layout/index.vue:170`，需动 `@sa/*` 包，单独排期 |
| 5 | settings.ts 死阴影 token 删除 | 待类型放开 | apple 包 `settings-dead-shadow-tokens` | `settings.ts:35-39` 两枚死 token 已注记；物理删除需先改 `src/typings/app.d.ts` 的必填声明 |

## 前端 / 体验类

| # | 事项 | 状态 | 来源 | 说明 |
|---|---|---|---|---|
| 6 | 内嵌浏览器截图光栅化预算 | 观察项 | apple 包 `iab-raster-perf`；airy 包 `iab-raster-kb-heavy` | ZCode 内嵌浏览器软件光栅化下，磨砂顶栏/重 DOM 页面截图准备超 3s。真机 GPU 渲染不受影响；用户真机反馈卡顿时优先降级 backdrop-filter 面积 |
| 7 | vendor Inter 字体网络加载 | 已中和 | 第一轮 chat 旗舰分片 skipped | `vue-markdown-shiki` dist CSS 携带 Google Fonts @import，视图层已 font-family:inherit 覆盖，网络加载本身需动 vendor 才能摘除 |
| 8 | 存量 no-console / console.log | 低优 | airy 包 round-2 记录 | `chat-message.vue` 3 处、`recharge-manage/index.vue` 5 处（313/330/335/345/353），业务调试代码，触碰时顺手清 |

## UI 打磨候选（PM 各轮 P2/P3 汇总）

- 顶栏图标体系仍有少量异源图标残留（第一轮已统一大部分，user-avatar 深层菜单待查）
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
