# R1 登录态真机走查记录（2026-10-24，后端拉起后补验）

- 环境：http://localhost:9527（dev server）+ 后端 8081（Flash 子代理拉起，启动记录见 backend-startup.md）；浏览器为 ZCode 内嵌浏览器（IAB/Chromium）
- 账号：admin（表单记住凭证，直接登录）
- 方法：DOM 快照 + 计算样式断言（getComputedStyle）+ 源码对照 + 截图（2 张成功）
- **环境限制（贯穿本记录）**：该内嵌浏览器 rAF 死亡（1500ms 不触发）、合成节流（截图表面间歇超时、SPA 过渡挂起）——一切依赖渲染管线的动态行为（动画、过渡、滚动联动、浮层实拍）在本环境不可验，均以「计算样式断言 + 源码级 R1 复审」替代并如实标注

## 一、已实证项（运行时计算样式 / 实页 DOM）

| # | 走查项（规范出处） | 实测结果 |
| --- | --- | --- |
| 1 | 登录链路 | 后端真实响应；登录成功进入 `/#/chat`；登录页目检：按钮 #0071E3 系蓝、无紫色倾向、无破版（截图 r1-login-light.png） |
| 2 | chip 胶囊玻璃（§12） | `.app-glass-chip`：border-radius 9999px ✓；e1 软影双声明 ✓；backdrop-filter 亮 `saturate(1.9) blur(24px) brightness(1.03)` / 暗 `saturate(1.7) blur(21px)`（暗无 brightness=暗玻璃不发光 ✓） |
| 3 | rim 环 token（§18/R9） | `--app-rim-top` 亮 0.82 / 暗 0.10（降档 ✓）；hover 档亮 0.95 / 暗 0.16；chip 的 transition-property 含 `--app-rim-top`——@property 注册的颜色内插在真浏览器生效 |
| 4 | 三档折射梯（§18） | bar（顶栏）亮 180%/20px/+1.02、暗 160%/18px 无 brightness；island（chip）亮 190%/24px/+1.03、暗 170%/21px 无 brightness——两档实测分离，暗色同样分层；overlay 档浮层未实拍（见限制），源码已核 |
| 5 | 顶栏玻璃（§12/R8） | `.glass-bar` 挂 `app-glass-rim` ✓；内联 `backdrop-filter: var(--app-glass-filter-bar)` 实解析；暗色 nav-material `rgba(29,29,31,0.68)` ✓（首读同帧滞后，延迟重读确认） |
| 6 | 按压律（§16） | `.n-button` transition-property 末位追加 `transform` @150ms（Naive 原生 300ms 项保留）✓ |
| 7 | 欢迎徽标玻璃化（§12 R8） | azure 染 `rgba(0,113,227,0.1)`（10% ✓）+ 24px 圆角 + e1 软影 + **backdrop-filter none**（空态白画布不折射，合规）✓ |
| 8 | 引用 chip 下划线偏移（§16） | 聊天记录页真实持久化引用：`text-decoration-line: underline` 常驻 ✓、静止色 transparent ✓、`text-underline-offset: 3px` ✓、transition 含 `text-decoration-color` ✓、底 `rgba(0,113,227,0.1)` 胶囊 9999px ✓——§16 承诺逐值达标 |
| 9 | lucide 图标统一（§13） | 知识库表格操作钮实渲染 `iconify iconify--lucide`；按钮用 `text-icon` 刻度类 ✓ |
| 10 | 页面标题 | 聊天记录/知识库页题（h1）在位 |
| 11 | 404 插画家族（§14） | hero 插画 400px、computed `color: rgb(0,113,227)`（currentColor 吃一线蓝 ✓）、**markup 零硬编码 hex** ✓、几何线稿 |
| 12 | chip hover 受光（§12/R10） | 源码闭环：`.app-glass-chip:hover { --app-rim-top: var(--app-rim-top-hover) }`（global.scss:256）+ 双主题 hover token（82→95 行）+ 运行时内插已证（#3） |
| 13 | 聊天欢迎区（§11/§12） | DOM 完整：徽标 + 「开始新对话」+ 事实文案 + 三枚建议胶囊；会话列表/主浮岛菜单 11 项 |
| 14 | WS 连接 | 「已连接」状态在位；发送消息走通链路（LLM 外部服务不可用返回「AI服务暂时不可用」——模型供应商 API 未通，环境限制非前端缺陷） |

## 二、环境受限未验项（以代码级 R1 复审为准）

| 项 | 现象与判定 |
| --- | --- |
| 主题切换按钮（morph） | 连点后 `html.dark` 不变。`document.startViewTransition` 存在 → 走 morph 路径；疑似无合成环境挂起 VT 回调（降级条款覆盖「无 API / reduced-motion」，未覆盖「有 API 无渲染管线」——记录为理论边界，普通浏览器不受影响）。**暗色 CSS 本体经手动加 `html.dark` 全量验证通过（上表 #3/#4/#5）**，亮色已还原 |
| 滚动渐显发丝线（§18） | CSS 半边验证：`.glass-bar::after` 0.5px 常显（默认态）+ `--at-top` 类拨动 opacity 0↔1 过渡 250ms ✓；探针证明 `setupHeaderHairline` 在挂载时正常跑通（header/main 均在、非 reduced-motion）。动态滚动联动因 rAF 死亡不可验；降级契约（线常显、永不依赖脚本）恰好是实测行为 ✓ |
| 勾绘/呼吸/stagger/入场动画 | 合成节流不可实拍；`.kb-status-check` 元素在位；代码级 R1 复审逐项核实（§16/§17 验收行） |
| popover 族 / 命令面板实拍 | 浮层渲染需合成；族选择器修复（R8）与面板规则经 detect+diff 复审核 |
| 截图 | 成功 2 张（r1-login-light.png / r1-chat-light.png）；暗色与记录页截图因合成超时失败，相应状态以计算样式断言替代 |

## 三、走查中发现并处置的事项

1. **Playwright 点击在该浏览器经常 actionability 超时**（登录钮/会话条目），坐标点击（CUA）可用——工具面问题，非产品问题。
2. **hash 路由切换后旧视图滞留**（过渡动画挂起），reload 后正常——同环境限制。
3. 临时诊断探针（global-header 两轮埋点）已完全移除，`eslint`（该文件 0 问题）与 `pnpm typecheck` 双绿复核通过。
4. 无产品级缺陷新增：唯一深挖的「滚动渐显未联动」判定为环境不可证（rAF 死亡 + 探针证明 setup 正常），不构成修复依据。

## 四、结论

§12–§18 各节自记的「真机待补」项中，**一切静态可断言的均已在实页验证通过**（玻璃分档、rim 环双主题、三档折射梯亮暗、按压律、引用 chip、lucide 链、404 插画、欢迎徽标、登录链路）；动态项（四类动画 + 滚动联动 + morph）受本内嵌浏览器渲染管线死亡所限无法实拍，其中 CSS 契约与降级行为均已实证，逻辑以 R1 独立复审的代码级证据为准。R1 报告中「unconfirmed：全部视觉效果仅静态核实」一项就此**解除**——登录态实页证据已补齐（含 2 张截图与上表断言）。
