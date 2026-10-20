# PM 复审 · 第三轮（亮色 airy + 结构级大改）

- 评审人：PM复审员第三轮（只读评审 + 真实浏览器实测）
- 日期：2026-10-20
- 对比材料：旧观感截图 `.spec/specs/archive/2026-10-20_redesign-ui-apple/orchestration/screenshots/r1-chat-dark.png`（第一轮后暗色 chat）+ 本轮全部 git diff（16 文件，+598/-383）
- 验证环境：`http://localhost:9527`（dev，真实登录会话），后端 8081 活跃

---

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| 「第一眼可辨」判定 | **可辨**（亮暗明度反转 + 侧栏形态 + 去套盒 + 字号尺度，均为轮廓级差异） |
| 结构级大改是否完整落地 | **否** —— 两处结构承诺在真实渲染中未发生（见 P0/P1） |
| 四条用户故事 | 登录 / 提问 / 引用 / 知识库 **全部走通，零功能回归** |
| 暗色主题 | token 组完好，新结构在暗色下成立 |
| 门禁（spec 成功标准：第一眼可辨 + 无 P0/P1） | **不通过** —— 存在 1×P0、1×P1，须修复后复审 |

---

## 1. 「第一眼可辨」判定

**结论：可辨。** 依据代码与实测重建的第一眼轮廓对比：

| 维度 | 旧（r1-chat-dark.png） | 新（实测 @1440×900） |
| --- | --- | --- |
| 色彩明度 | 全暗（#0A0A0C 地面 + #1C1C1E 盒） | 默认亮（白岛 + #F5F5F7 地面），`html.dark` 不存在（themeScheme='light' 生效，settings.ts:4） |
| 壳层侧栏形态 | 贴边通高、直角、右侧发丝线 | 悬浮浮岛：`x=12, y=64, bottom=12`，24px 圆角，岛影 `0 1px 2px .04 + 0 12px 32px .10`（aside computed style 实测） |
| chat 表面几何 | 对话列表+画布包在一个 24px 圆角描边盒内（bg-layout 上的套盒） | 套盒已拆除：无 `.chat-surface`、无描边无圆角；对话列表成为画布左缘第二座浮岛（`margin 12px、radius 24px、岛影`，`.chat-sidebar` computed 实测） |
| 字号尺度 | 正文 15px/1.65、卡头 17px | 正文 16px/1.75（chat-message.vue 实测 16px/28px）、卡头 20px、新增 Display 32 页题（chat-history/knowledge-base `.page-title` 实测 32px/600） |
| 登录页 | 暗色 NCard 盒 | 白底去卡全幅 hero（`.login-page` bg=纯白、`.n-card` 数量 0、标题 32px、主按钮 44px 靛蓝，全部实测） |

**判定理由与保留意见**：
1. 旧截图是暗色、新默认是亮色——仅凭主题默认值切换就"必然可辨"。为排除这一捷径，我做了暗色抽查（§4）：即便同在暗色下，侧栏形态（贴边→浮岛）、套盒拆除、字号拉大依然构成轮廓级差异。**结构级差异真实存在，可辨不依赖换肤。**
2. 但 DESIGN.md:270 承诺的「全幅白画布（bg-container 直铺 main，无边距）」与「global 浮岛与画布之间 12px 地面缝（x∈[232,244]）」在实测渲染中**均未发生**（P0/P1，见 §5）。chat 页实际渲染为「灰底地面 + 白岛」，不是设计文档中的白画布。
3. 一句话判定：**第一眼可辨 = YES；结构级大改完整落地 = NO。** 按本轮门禁标准（可辨且无 P0/P1），不通过。

---

## 2. 四条用户故事走查（真实浏览器，登录会话）

| 故事 | 结果 | 证据 |
| --- | --- | --- |
| 登录 | ✅ 走通 | `#/login` 渲染白底去卡新结构；.env 引导凭据提交后跳转 `#/chat`。第二轮归档评审的登录 P0（前端 pwd 正则拦截引导密码）在本轮构建不复现 |
| 提问 | ✅ 走通 | 输入框提交后输入框清空、用户新气泡入列（`.chat-user-bubble` 计数 +1），流式回答完成（156 字、含「来源#1」标注），输入壳 18px 圆角实测 |
| 引用 | ✅ 走通 | 回答内引用 chip `.source-file-link`（靛蓝 rgb(88,86,214)、pointer）；点击后经 `window.open` 新开 `#/chat?preview=reference&previewKey=...`，预览页正常渲染（文件标题 mindbase-e2e-test.md + 新窗口/下载/关闭）。引用数据流零回归 |
| 知识库 | ✅ 走通 | `#/knowledge-base`：Display 32 页题（y=100，顶部 48px 留白）+ 单张白卡（18px 圆角、发丝线、零阴影、卡头 20px）+ 表格 2 行正常 |

附：聊天记录页 ✅（32px 页题、无卡片、筛选行发丝线、10 条消息落地渲染）；chat 日期筛选浮层 ✅（`NPopover`+`NDatePicker` 挂载并显示，range→params 数据流未改动）。

---

## 3. 暗色主题抽查

**结论：token 组未被改坏，新结构在暗色下成立。** 实测（`html.dark` 下 computed style）：

- 壳层岛：`rgba(29,29,31,0.68)` 磨砂 + 暗岛影 `0 1px 2px .45 / 0 12px 32px .55`（settings.ts:53 → vars.ts:32 → `--sider-box-shadow` 链路实测到达）✓
- 会话岛：同材质同影、24px 圆角 ✓
- header：暗磨砂 + `rgba(235,235,245,.14)` 发丝线 ✓
- 画布：#0A0A0C（暗 layout 地面）✓；输入壳 #2C2C2E + 暗发丝线 ✓；正文 #F5F5F7 16px ✓
- Quiet Glass 暗色 token（global.scss:56-68）全部保留，本轮仅新增 `--app-shadow-island` 暗值 ✓

暗色下同样存在 P0/P1 的两个结构问题（数值相同），不属于暗色回归。

---

## 4. 旧观感残留清点

| 残留项 | 状态 |
| --- | --- |
| 套盒（chat 圆角描边盒） | 已拆除（chat/index.vue diff 删除 `.chat-surface`；实测无描边无圆角）✓ |
| 贴边通高侧栏 | 已岛化（除 P1 的宽度/右缝问题）✓ |
| 旧 `.chat-date-filter` 工具行 | 已删净，全库 grep 无残留引用 ✓ |
| 默认灰底 | 管理页 #F5F5F7 地面 = 设计内 airy ground（DESIGN.md 去套盒规范），非残留 ✓；但 chat 页灰底是 P0 的产物（设计要白画布）✗ |
| 页题层级 | 仅 chat-history / knowledge-base 落地 Display 32；其余管理页组（用户/组织标签/模型配置/邀请码/用量监控/充值/个人中心）无页题，仍以 20px 卡头充当页标题（/user 实测 `hasPageTitle=false`）→ P2 |
| 卡头/内边距旧密度 | 已被 global.scss static-card 覆盖全局拉大（17→20px、24→32px padding），管理页受益 ✓ |

---

## 5. 发现清单（severity + suggestion）

### P0 · chat 全幅白画布未落地：GlobalContent 类 fallthrough 覆盖了画布
- **位置**：`frontend/src/views/chat/index.vue:26`（画布根）、`chat/index.vue:52-53`（错误注释）、`frontend/src/layouts/modules/global-content/index.vue:50-51`
- **事实**：chat/index.vue 编译产物是**单一条件根**（我用 `@vue/compiler-sfc` compileTemplate 实证：render 返回 `(cond) ? block : block` 三元，非双根 fragment），实现注释「双根 fragment 不会 fallthrough」判断错误。实测画布根 class 为 `chat-canvas ... bg-container p-4 sm:p-6 xl:px-40px ... bg-layout`，计算样式 `background-color: rgb(245,245,247)`（bg-layout 胜出）、`padding: 24px 40px`。**亮暗两色同理**（暗色画布实测 #0A0A0C=layout 而非 #1C1C1E=container）。
- **影响**：DESIGN.md:270「全幅白画布直铺 main、无边距」+「12px 地面缝」的核心结构承诺未发生；chat 页仍是「灰底地面」而非「白画布即房间」。本轮 KPI 的主页面未按设计渲染。
- **建议**：不要依赖 fallthrough 行为——在 chat 分支根上显式写 `!` 覆盖（如 `bg-container! p-0!`），或给 GlobalContent 传 `:show-padding="false"`（chat-history 等地面页另走路由 meta 控制），并删除错误注释；修复后必须浏览器复验 bg-container 与零边距真实生效。

### P1 · 壳层浮岛宽度钉死失效：`widthClass` 是死变量，岛右地面缝消失、岛叠入内容区 12px
- **位置**：`frontend/src/layouts/base-layout/index.vue:110-137`（`islandWidthClass`/`widthClass` 计算后**未拼入** siderClass 返回串）
- **事实**：aside 实测完整 class 无 `w-220px!`；`width: 244px`（来自 css-module `--soy-sider-width`=220+24）。岛 spans x∈[12,256]，而内容区自 x=244 起——**岛与内容区重叠 12px**，设计中的 12px 地面缝（DESIGN.md:270 x∈[232,244]）不存在。折叠态同理（应为 64，实为 88）。
- **影响**：「与窗口边缘脱开」只在上/左/下成立；右侧贴死并压过画布左缘，暗色岛影直接叠在画布上；AdminLayout 的 +24 预留列契约与实际几何自相矛盾。
- **建议**：`return (widthClass + ' ') + 'top-64px! ...'`（或模板内并列绑定）；修复后实测岛宽 220/64、岛右缘 232、缝 [232,244]。

### P2 · Display 32 页题只覆盖 2/N 页，页间层级节奏不一致
- **位置**：`frontend/src/views/chat-history/index.vue`、`frontend/src/views/knowledge-base/index.vue`（已加 `.page-title`）；其余管理页组（`views/user`、`org-tag`、`model-provider`、`invite-code`、`usage-monitor`、`recharge`、`recharge-manage`、`personal-center`）未加
- **事实**：`/user` 实测无 `.page-title`/h1，唯一标题是卡内「用户列表」20px；卡片顶 y=76，与知识库页（页题 y=100 + 卡片 y=170）的留白节奏明显不同。
- **影响**：翻页时页题忽大忽无，「页面标题与正文字号层级拉大」只在一部分页面成立——正是上一轮「像没改」的碎片化观感的温和重演。
- **建议**：管理页组统一补 `.page-title`（可抽成小组件或在 global.scss 提供约定类），或本轮明确降级承诺并在 DESIGN.md 标注哪些页面暂缓。

### P3 · 聊天输入壳投影亮暗共用一组硬编码字面值
- **位置**：`frontend/src/views/chat/modules/input-box.vue:493-494`（`0 1px 2px rgba(0,0,0,.04), 0 2px 8px rgba(0,0,0,.04)` 及 focus-within 同）
- **事实**：未走 `--app-shadow-*` token；实测暗色下 inputShell 投影仍是 0.04 亮值，白底上「低语级」的投影在近黑画布上几乎不可见（输入壳只靠发丝线浮起）。
- **建议**：仿照 `--app-shadow-island` 的做法在 global.scss 增设亮/暗双值 token（暗值加重），或直接复用 e1。

### P3 · 每次发版都会把用户主题选择重置回 light 一次
- **位置**：`frontend/src/theme/settings.ts:65-76` + `frontend/src/store/modules/theme/shared.ts:24-30`
- **事实**：`overrideThemeSettings.themeScheme='light'` 挂在 `overrideThemeFlag === BUILD_TIME` 上——每个新 BUILD_TIME 都会重放一次 override。用户在新版本里主动切到 dark 后，下次发版会被再次重置为 light（首次加载后 `beforeunload` 会缓存合并结果，机制本身成立，不与用户选择长期打架）。
- **建议**：迁移标记与 BUILD_TIME 解耦（独立语义版本 flag，或仅当缓存值仍是旧默认 'auto' 时才强推 light）。

### 环境伪影（不计入发现，避免误导后续修复）
- 会话列表「暂无对话记录」空态与会话条目并存：实测空态节点卡在 `session-list-leave-from/-leave-active`——自动化页签 rAF 被冻结（实测 2.5s 无帧）导致 Vue 过渡冻结，真实前台不会发生；代码上空态 v-if 与 v-for 数据互斥（conversation-sidebar.vue:103）。
- 路由切换视图冻结、Playwright 点击 actionability 超时、截图 surface 超时：同源（rAF 冻结/窗口被遮挡）。走查通过「带 hash 重载」完成；日期筛选浮层经元素 `click()` 验证可打开。**真实鼠标点击本按钮未能在此环境复验**——代码路径与合成点击均通过，风险低。

---

## 6. 验证方法声明

- 代码证据：`git diff`（16 文件全量）、`@vue/compiler-sfc` compileTemplate 实编译 chat/index.vue、token 链路逐级核对（settings.ts → vars.ts:32 → shared.ts getCssVarByTokens → `shadow-sider`）、AdminLayout（packages/materials）sider-class/css-module 核对。
- 运行时证据：真实登录会话内 7 组 computed-style 测量（登录页/亮色 chat/暗色 chat/聊天记录/知识库/用户管理/aside 完整类名）、路由枚举、发消息→流式回答→引用 chip→预览页新页签全链路。
- 未能执行：旧截图与新页面的像素级并排截图对比（本环境截图 surface 持续超时）——判定改以「旧截图读图 + 新页面逐项实测值」重建轮廓完成；全量 typecheck/eslint 由门禁任务负责，本轮未重复执行。
