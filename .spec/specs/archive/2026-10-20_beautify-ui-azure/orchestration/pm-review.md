# PM 复审走查 · UI 美化第四轮（Azure 淡蓝 + impeccable 八指令 + logo/图标追加）

- 复审人：主会话（spec 编排者）
- 日期：2026-10-20
- 证据：`orchestration/screenshots/`（login-new-logo / chat-light / usage-monitor / usage-monitor-dark / usage-monitor-lucide / logo-sider-new / 9 个管理视图亮色截图）；runs.jsonl（9 个机器任务 OK）；`impeccable detect --json` 全目标 = `[]`（零发现）

## 一、八指令逐项结论

| 指令 | 落地 | 结论 |
| --- | --- | --- |
| colorize | 一线靛 `#5856D6`→一线蓝 `#0071E3`（apple.com 蓝，白字 4.70:1 AA）；亮色中性面染冷蓝（地面 `#F2F6FC`、冷墨 `#1C2A3A`、蓝洗 fill）；PDF 引用高亮从硬编码 AntD 蓝 token 化到主色；旧 Vite 紫 favicon 一并退役 | ✅ 达标，管线实测值与 DESIGN.md 完全一致（500/600/400 阶 `#0071E3/#0058BD/#2692F0`） |
| typeset | `.page-title` 32px 页题从 2/10 补齐到 **10/10** 管理视图（真机 DOM 逐一核对标题+字号+字重） | ✅ 达标，第三轮遗留缺口清零 |
| layout | 三轮 airy 结构保持（浮岛/去盒/800px 阅读列）；10 视图走查无破版；页题上 24px + GlobalContent 24px 叠成 4xl | ✅ 无回归 |
| animate | 动效论纲成文（DESIGN.md › Motion & Delight）；主题切换 View Transitions 圆形揭示 morph（真机验证：从按钮位置扩散、morphX/R 随点击更新）；流式等待呼吸状态点（1.6s，绑定真实状态）；reduced-motion 全局兜底在位 | ✅ 达标；morph 双发缺陷发现并修复（见 §三） |
| delight | 呼吸状态点（流式等待）+ 引用 chip 微交互（既有样式达标保留）；上传勾绘动画经评估**否决**（表格 tsx 逐行重渲染会重复播放，正确性优先） | ✅ 克制且有据 |
| overdrive | 授权位唯一：主题切换 morph（Operate 模式"手感"型 extraordinary）；否决粒子/虚拟滚动（语境不符） | ✅ 边界纪律成立 |
| quieter | 靛蓝措辞残留清零（grep 0）；装饰性阴影零回归（shadow-sider 仅为浮岛档）；强调色 ≤10% 目检通过 | ✅ 达标 |
| bolder | 签名面信念度：32px 页题全员落位；登录/侧栏品牌标换新提气；聊天画布白底+蓝气泡对比强 | ✅ 达标 |

## 二、追加任务（用户中途指令）

- **品牌 logo**：「心智基座」M + 心智火花 SVG——mono 版（currentColor，侧栏冷墨/登录 hero 淡蓝）+ favicon Azure 瓷砖版。真机：侧栏 32px、favicon 服务内容、DESIGN.md › Brand Mark 成文。**✅**
- **UI 图标**：10 套（solar/mdi/material-symbols/ant-design/ph/eos-icons/majesticons/line-md/mynaui/ion）→ **纯 lucide**，23 文件 81 处，真机整页 `iconify--lucide` 计 15/15。backlog「异源图标残留」候选项闭环。**✅**

## 三、缺陷处置（本轮闭环）

- **P1（已修复）主题切换双发**：NTooltip/NButton 事件链每次点击投递两次 → morph 双跑、scheme 跳两档。修复：global-header 400ms 闩锁。真机复验 vt delta=1/次、scheme 单步移动。
- **P2（已修复）PDF 引用高亮硬编码**：`rgba(64,169,255→24,144,255)` AntD 蓝绕过 token → 换血为主色 token。
- **观察项（已登记 backlog#10）**：dev 模式下 `page reload` 广播后旧标签页可进入"路由 title 更新但组件不挂载"半更新态，硬刷新恢复；非本轮代码回归（三进程并存 + 旧进程缓存加剧），生产无此通道。

**P0 = 0；P1 = 0（本轮内修复闭环）；P2 = 0（本轮内修复闭环）。**

## 四、遗留与下一轮建议（工作流反馈 → 新一轮输入）

1. **P2｜登录页整页目检**：已登录守卫重定向，新 logo 登录 hero 仅组件级验证；下轮登出走一遍登录/注册页（含 lucide 前缀图标、morph 降级路径）。
2. **P2｜真实流式呼吸态目检**：呼吸点代码在位但未在真实生成流中目检；可用 `scripts/chat-reconnect-smoke-test.mjs` 或真发一条消息观察。
3. **P3｜reduced-motion 实测**：`prefers-reduced-motion` 全局压扁在位（代码级），建议浏览器 emulate media 实测 morph 降级路径。
4. **P3｜暗色一轮全量走查**：本轮暗色仅抽查（usage-monitor + chat）；下轮 10 视图暗色过一遍确认对比度。
5. **P3｜favicon 浏览器标签页实视**：服务内容已验新瓷砖，标签页 16px 实视未目检。

## 五、下一轮决策

反馈中 5 项均为可执行的小额打磨（无方向性争议、无需用户裁决），满足"根据工作流的反馈再拉起新的工作流"的启动条件 → **建议拉起第五轮（azure 收口轮）**，范围 = 上节 5 项；全部闭环且复审零发现时收敛停止。
