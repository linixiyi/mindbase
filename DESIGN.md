---
name: MindBase
description: 企业级 RAG 知识库管理系统 —— 「静谧指挥台」：精确、克制、可扫描的控制面视觉系统
colors:
  primary: "#646cff"
  primary-pressed: "#342cd8"
  link-blue: "#1890ff"
  success: "#52c41a"
  warning: "#faad14"
  error: "#f5222d"
  container: "#ffffff"
  layout: "#f7fafc"
  base-text: "#1f1f1f"
  inverted: "#001428"
  dark-container: "#1c1c1c"
  dark-layout: "#121212"
  dark-base-text: "#e0e0e0"
typography:
  headline:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.5
  title:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.6
  body-large:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.5
  micro:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.5
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace"
    fontSize: "12px"
    fontWeight: 400
rounded:
  sm: "4px"
  md: "6px"
  lg: "8px"
  xl: "12px"
  2xl: "16px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  2xl: "28px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    typography: "{typography.body}"
  button-primary-pressed:
    backgroundColor: "{colors.primary-pressed}"
  button-primary-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
  button-quaternary:
    backgroundColor: "transparent"
    textColor: "{colors.base-text}"
    rounded: "{rounded.md}"
  card-section:
    backgroundColor: "{colors.container}"
    rounded: "{rounded.xl}"
    padding: "24px 28px"
  input-chat:
    backgroundColor: "{colors.container}"
    textColor: "{colors.base-text}"
    rounded: "{rounded.2xl}"
    padding: "10px 14px"
  tag-status:
    textColor: "{colors.base-text}"
    rounded: "{rounded.md}"
  table-data:
    rounded: "{rounded.lg}"
---

# Design System: MindBase

## Overview

**创意北极星：「静谧指挥台」**

MindBase 的界面是一张指挥台：信息的密度是均匀的，控制是精确的，一切装饰性的噪音都被移除。白色工作面板安静地浮在冷灰的布局底色上，靛紫主色 `#646cff` 是台面上唯一亮起的指示灯——它只为主操作、选中态和引用证据点亮。用户（企业员工与企业管理员）在这里提问、审阅引用、裁决冲突、管理配额；界面必须像仪表盘一样可扫描，而不是像海报一样可欣赏。

层级不靠装饰，靠三件事：排版（字号 × 字重从中递减）、中性表面的透明度阶梯（正文 100% → 次要 68% → 边框 10% → 微弱底色 5.5%）、以及白色卡片与 `#f7fafc` 布局底色之间那一级安静的对比。阴影在这个系统里是稀缺资源，只出现在浮层与悬浮态；静止的表面用留白和细边框完成分层。

组件气质「克制精密」：6px 基准圆角、28px 控件高度、精确到像素的间距节奏。每一个状态（hover、focus、选中、处理中）都有明确且一致的反馈，但没有一个状态是喧哗的。

**Key Characteristics:**

- 均匀密度、可扫描性优先的控制面气质，无营销式大色块与渐变横幅
- 靛紫 `#646cff` 是唯一强调色（info 色跟随主色）
- 静止表面零阴影：层级来自留白、细边框与中性透明度阶梯
- 小圆角语言：组件 6px、卡片 12px，唯一例外是对话输入壳（16px）
- 排版即层级：18/600 → 15/600 → 14 → 12/500 → 11px 的中文字号阶梯
- 亮 / 暗双主题（`color-scheme: light dark`），暗色下阴影退场、边界接管

## Colors

克制的功能色板：一组中性表面承载全部信息层级，一组语义色只报告状态，一支靛紫作为唯一的声音。

### Primary

- **指挥靛紫** (#646cff)：主操作按钮、选中态文字与图标、对话行选中洗色（`rgb(var(--primary-color) / 0.08~0.12)`）、聚焦边框（38% 透明）、品牌头像、加载条。派生态：hover 取色阶 500（与主色同值），按压取色阶 700（**#342cd8**），active 态为主色 10% 透明。Naive UI 的 info 色跟随主色（`isInfoFollowPrimary: true`），因此系统中实际只有一支冷强调色。
- **引用蓝** (#1890ff)：引用与文档证据的专用链接色（回答中「来源#n」链接、PDF 预览的页码指示）。hover #40a9ff、active #096dd9。它不参与主操作，只标记「可溯源的证据」。

### Secondary

无独立次强调色。`info` 跟随主色；工具执行条目与行内状态文本中的语义色（成功 #18a058、失败 #d03050、处理中 `sky-700` #0369a1）是状态报告，不是装饰色相。

### Semantic（状态）

- **语义绿** (#52c41a)：成功、已完成、公开标签、用户头像（`NAvatar bg-success`）。
- **语义琥珀** (#faad14)：警告、私有标签、引用待核实的徽标边框（文字用深琥珀 #d48806）。
- **语义红** (#f5222d)：错误、删除、上传中断、失败工具事件（文字态 #d03050）。
- **连接与向量化状态（Tailwind 色板工具类，独立于上述语义 token）**：连接点在线 `green-500` #22c55e、连接中 `yellow-500` #eab308（脉冲）、离线 `red-400` #f87171；向量化状态文本 `emerald-700` #047857（已完成）、`sky-700` #0369a1（处理中）、`amber-600` #d97706（暂无结果）、`rose-600` #e11d48（失败）。

### Neutral

- **纯白面板** (#ffffff)：亮色主题下的卡片、聊天主面、输入壳表面（`container`）。
- **冷雾底色** (#f7fafc)：布局底色（`layout`），内容页 `bg-layout`——白卡片「浮」在它上面，这一级对比就是页面分层。
- **墨黑正文** (#1f1f1f)：基准文字（`base-text`）。次级文字用其 68% 透明（`--app-muted`），微弱底色用 5.5%（`--app-subtle`），细边框用 10%（`--app-border`）。
- **深夜反色** (#001428)：侧栏反色（inverted）背景，仅用于深色菜单模式，常态不可见。
- **暗色表面** (#1c1c1c / #121212)：暗色主题的容器与布局底色；暗色文字 #e0e0e0。暗色下的微弱底色改用白色低透明（#FFFFFF08~14）。
- **次级灰阶**（#666 / #999 / #aaa）：未选中分段标签、时间戳、空状态提示——只用灰阶，不引入新色相。

### Named Rules

**唯一强调色规则。** 靛紫 #646cff 之外不存在第二支强调色；引用蓝 #1890ff 是证据链接的专属色，不得挪作通用主色。一屏之内，靛紫永远是少数派。

**中性分层规则。** 层级用白色面板、#f7fafc 底色和文本透明度（68% / 10% / 5.5%）表达；需要新层级时先动透明度，不动色相。

## Typography

**Display / Body Font:** 系统无衬线栈 —— `ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'Noto Sans', sans-serif`（含 emoji 回退），html 基准行高 1.5，Naive UI 组件基准行高 1.6。
**Mono Font:** `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace` —— 用于 MD5、会话 ID、模型参数等机器标识。

**Character:** 不引入任何品牌字体——中英文都交给系统栈，让界面在任何机器上保持原生的精确与安静。层级完全由「字号 × 字重」两个变量撑起，没有第三种把戏。

### Hierarchy

- **Headline**（600，18px，1.5）：页面标题与卡片标题（`.page-section .n-card-header__main`）。一个工作区只应有一个。
- **Title**（600，15px）：面板标题（如对话列表「对话列表」）、空状态主文案（500）。
- **Body-large**（400，16px，1.6）：聊天消息正文；发送者用户名与「MindBase」标识在此基础上加粗至 700（`font-bold`）。
- **Body**（400，14px，1.6）：全局基准——表单、按钮、表格、聊天输入框。
- **Label**（500，12px，1.5）：分段标签、表头、工具执行条目（行高 18px）、状态徽标。
- **Micro**（400，11px，1.5）：时间戳、连接状态、快捷键提示、引用角标（行高 16px）。
- **Mono**（400，12px）：MD5 / ID / 密钥等机器数据的唯一字体。

### Named Rules

**排版做层级规则。** 层级只来自字号与字重的递减（18/600 → 15/600 → 14 → 12/500 → 11px），灰阶表达从属；禁止用新色相或大字号制造层级。

## Layout

应用壳层是经典的「侧栏 + 顶栏 + 内容」垂直布局：侧栏宽 180px（折叠 64px），顶栏高 56px（透明背景，内容右对齐：全局搜索、全屏、主题切换、头像），标签页栏高 44px 但默认隐藏，页脚默认隐藏。内容区背景 `bg-layout`（#f7fafc），内边距 16px，≥640px 时 24px（`p-4 sm:p-6`）。

管理页面遵循 **workspace-page 模式**：纵向 flex 列、区块间距 24px（移动端 16px）、最小高 500px；每个区块是一张 `.page-section` 白卡片（卡片头内边距 24px 28px 20px，内容区 0 28px 28px），筛选表单行间距 `12px 24px`、下边距 24px。对话页则遵循 **960px 对话走廊**：消息流与输入壳居中于 `max-w-[960px]` 列，左侧对话侧栏 260px（边界 1px #eef0f4，底色 #f8f9fb）。

响应式断点取 Tailwind 标准（VueUse `breakpointsTailwind`）：**sm 640px**（`isMobile` 判定线，侧栏折叠为浮层 + 30% 黑色遮罩）、md 768px、lg 1024px、xl 1280px、2xl 1536px。640px 以下：卡片内边距收缩为 20px、筛选表单纵向堆叠、表格单元格内边距收缩为 12px、对话侧栏变为绝对定位抽屉。

页面切换使用 fade-slide 过渡（0.3s，位移 30px）；滚动条统一为 7px 细圆角样式。

## Elevation & Depth

**平面为主。** 静止表面零阴影：`.page-section` 卡片显式声明 `box-shadow: none; border: 0`，`card-wrapper` 快捷类是 `rd-3 shadow-none`。深度由三层静态手段表达——白面板 vs #f7fafc 底色、1px 低透明边框（文本色 10%）、留白节奏。阴影只属于两种场合：壳层对内容区的切割，和浮层/悬浮态的出现。

### Shadow Vocabulary

- **壳层分隔（header / tab）**（`box-shadow: 0 1px 2px rgb(0, 21, 41, 0.08)`）：**死 token（已注记）**——仓库内无 `shadow-header` 的实际引用；壳层与内容的分隔实际由 1px 边框与底色差承担。token 仍留在 settings.ts，因类型要求，待 `App.Theme.ThemeSettingTokenBoxShadow` 放开后删除。
- **壳层分隔（sider）**（`box-shadow: 2px 0 8px 0 rgb(29, 35, 41, 0.05)`）：**死 token（已注记）**——浮动侧栏 `aside` 的 `overflow-hidden` 把它整体裁剪，可见的影子是 aside 自带的 `shadow-2xl`（见下方「已知例外」）。
- **输入壳静态微影**（`box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 6px 18px rgba(15, 23, 42, 0.04)`）：对话输入壳静息态，全系统唯一「静息带影」的表面——它是用户视线的主舞台。
- **输入壳聚焦微影**（`box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 8px 24px rgba(15, 23, 42, 0.06)`）：focus-within 时阴影略微抬升，同时边框转为主色 38% 透明。
- **激活分段微影**（`box-shadow: 0 1px 2px 0 rgb(0 0 0 / 0.05)`，即 `shadow-sm`）：分段控件当前段的白底浮起。
- **模态深影**（`box-shadow: 0 36px 120px rgba(15, 23, 42, 0.28)`）：全屏文档预览模态的唯一重影——浮层升得越高，影越深。
- **登录氛围影**（`box-shadow: 0 18px 40px rgb(var(--primary-color) / 0.08)`）：登录/注册卡片的靛紫低透明氛围影。
- **Naive UI 浮层默认**：下拉、气泡、对话框未覆盖阴影 token，沿用 Naive UI 默认浮层阴影——不自定义、不叠加。

> **已知例外（待处理）**：`packages/materials/src/libs/admin-layout/index.vue` 的浮动侧栏仍带 Tailwind `shadow-2xl`（静止表面重影），属 `@sa/*` 包范围，本轮边界不改；收敛时它应替换为上方「壳层分隔」级细影或直接去影。

### Named Rules

**平面为主规则（The Flat-By-Default Rule）。** 静止表面不携带阴影（卡片 `shadow-none` 是显式声明）；阴影是对状态的回应——聚焦、悬浮、弹出——而不是对层级的装饰。

**黑暗无影规则。** 暗色主题下输入壳阴影显式移除（`box-shadow: none`），表面边界改用 1px 白色低透明边（10% 内）承担；亮色阴影不得原样带入暗色。

## Shapes

小圆角、清晰细边框的精密语言。圆角刻度六级：**4px**（引用待核实徽标等行内小元素）、**6px**（组件基准——Naive UI `borderRadius` 覆写为 6px，按钮、标签、工具条目、行内分段 active 药丸均在此档）、**8px**（列表行、数据表格 `--n-border-radius: 8px`）、**12px**（卡片与聊天主面——`rounded-xl` / `rd-3` 同为 0.75rem）、**16px**（对话输入壳，全系统唯一的大圆角签名）、**full**（发送按钮、连接点、头像）。

边框只有一种配方：1px 实线。亮色下是文本色 10%（`--app-border`）或输入壳的 rgba(15, 23, 42, 0.12)；暗色下是白色 6%~10%。侧栏边界用更浅的 #eef0f4。表格与页面卡片显式去边框（`:bordered="false"`、`border: 0`）——边框留给真正需要切割的区域（输入壳、工具条目、分段容器），卡片靠底色差分层。

裁剪纪律：媒体与预览容器用 `overflow: hidden` + 圆角一次成型（文档预览壳）。滚动条是 7px 半透明圆角细轨，是这套语言里少数「看得见的机械部件」。

## Components

### Buttons

- **Shape:** 6px 圆角。尺寸沿用 Naive UI 三档：工具栏与表格行内操作以 `size="small"`（高 28px）为主（TableHeaderOperation、知识库行操作）；弹窗底部按钮、筛选区的查询/重置与邀请码工具栏保持默认 medium（高 34px）；登录提交按钮为 `size="large" round block`。仅发送按钮为圆形（full）。
- **Primary:** 指挥靛紫底 + 白字；hover 同色（色阶 500），按压 #342cd8（色阶 700），active 主色 10% 透明。
- **Ghost（工具栏主操作）:** 透明底 + 靛紫字 + `1px solid #646cff` 描边（Naive ghost：hover/焦点描边跟随主色，按压描边 #342cd8）；用于「新增」「检索知识库」「批量删除」（错误色变体）。
- **Quaternary / Text（静默操作）:** 透明底无边框且常驻显形——表格行内操作（预览/续传/删除）、消息工具（复制/点赞/点踩）、折叠按钮；hover 以微弱底色（`--app-subtle` 一级）与主色文字回应。唯一「hover 才显形」的例外是对话列表行的归档操作（`opacity-0 group-hover:opacity-100`）。破坏性操作一律套 NPopconfirm。
- **状态按钮:** 发送按钮发送中切换为 warning 型 + 停止图标（circle）；点赞/点踩激活后分别转 primary / error 型。

### Chips / Tags

- **状态标签:** `NTag bordered={false}`，6px 圆角，success / warning / error 三型报告文件状态（已完成 / 私有 / 上传中断）——无边框的淡色底是唯一形态。
- **工具执行条目:** 1px `--app-border` 边框 + `--app-subtle` 底 + 6px 圆角的行内条，12px/18px 文字；成功态边框转绿 25% 透明（文字 #18a058），失败态边框转红 25% 透明（文字 #d03050），执行中带三点加载动画。
- **引用徽标:** 「待核实」徽标——1px #faad14 边框、4px 圆角、11px/16px、深琥珀 #d48806 文字。

### Cards / Containers

- **Corner Style:** 12px（`page-section` / `card-wrapper` / 聊天主面 `rounded-xl`）。
- **Background:** 纯白 #ffffff，直接坐落 `bg-layout` 上。
- **Shadow Strategy:** 静止零阴影零边框（见 Elevation & Depth）。
- **Internal Padding:** 卡片头 24px 28px 20px（标题 18px/600），内容 0 28px 28px；移动端整体收缩为 20px。

### Inputs / Fields

- **管理表单字段:** Naive UI 默认形态（6px 圆角、34px 中高、14px 字号），置于 `page-filters` 栅格（列间距 24px、行间距 12px）；640px 以下字段全宽。
- **对话输入壳（签名组件）:** 16px 圆角白壳，1px rgba(15, 23, 42, 0.12) 边框 + 静态微影；内部无边框 textarea（14px，placeholder 用 `--app-muted`，光标靛紫），右侧圆形发送按钮；**focus-within** 边框转 `rgb(var(--primary-color) / 0.38)`、微影抬升，过渡 0.18s ease；输入壳下方一条 11px 状态行（连接圆点 + 快捷键提示）。
- **Error / Disabled:** 错误经全局 message 弹吐（success/warning/error），输入区本身不携带错误态；连接断开时仅禁用发送。

### Navigation

- **侧栏菜单:** Naive UI 菜单渲染于 180px 侧栏（折叠 64px），亮色白底，反色模式 #001428；选中态由主色系表达。
- **对话列表行:** 8px 圆角、12px 水平 10px 垂直内边距；选中态背景 `rgb(var(--primary-color) / 0.08)` + 标题与图标砖转靛紫（图标砖 28px、底色 12% 主色）；未选中 hover 转 `--app-subtle`；归档等行内操作 hover 显形。
- **分段控件（活跃 / 已归档）:** `--app-subtle` 底 + 8px 圆角容器（内衬 2px），活动段白底 6px 圆角 + `shadow-sm` + 靛紫 12px/500 文字。
- **顶栏:** 透明背景（56px 高），操作全部右对齐（`justify-end`）：全局搜索为图标按钮（点击弹出 630px 宽搜索弹窗，桌面端定位于顶栏下方），其后是全屏、主题切换、头像；移动端追加菜单切换按钮。

### 引用证据系统（Signature）

回答中的「来源#n: 文件名 | 第X页」是 MindBase 的视觉签名：链接渲染为引用蓝 #1890ff 下划线文字，hover 转 #40a9ff 且去下划线、active #096dd9；被引用校验判为可疑的来源降级为主文字色 + **虚线**下划线 + 70% 透明，并追加「待核实」徽标。点击打开文档预览模态（`min(96vw, 1320px)`、深影浮层），定位到命中页码。引用映射必须贯穿持久化与历史渲染，不得回归。

## Do's and Don'ts

### Do:

- **Do** 用主色洗色做选中态：`rgb(var(--primary-color) / 0.08)`（行背景）与 `/0.12`（图标砖），配合靛紫文字——这是全系统统一的「当前」语言。
- **Do** 保持控件小而密：表格行内与工具栏操作用 `size="small"`（28px）、表格 `size="small"`；弹窗底部与筛选提交按钮保持默认 medium（34px）。表头 12px 16px 内边距 500 字重、单元格 14px 16px。
- **Do** 状态标签用 `NTag bordered={false}` + success/warning/error；处理中的任务用 `NProgress processing`，不用红色。
- **Do** 机器标识（MD5、ID、密钥）一律等宽字体（12px），可点击复制的给出 hover 转靛紫 + 成功 toast。
- **Do** 每条可追溯的证据都标注来源——引用链接、页码、引用校验状态是产品承诺的一部分。
- **Do** 微交互收敛在 0.18~0.3s、ease 系缓动：链接变色 0.2s、输入壳 0.18s、侧栏宽度 0.2s ease-out、页面切换 fade-slide 0.3s。
- **Do** 空状态用 80px 主色 8% 圆角砖 + 图标、15px 主文案、12px 次级提示的固定组合。

### Don't:

- **Don't** 给静止的卡片或区块加阴影或可见边框——`page-section` 的 `box-shadow: none; border: 0` 是显式声明，不是默认值。
- **Don't** 引入第二支强调色相；#1890ff 只属于引用与文档证据链接。
- **Don't** 使用超过 16px 的组件圆角；16px 是输入壳专属签名，卡片停在 12px。
- **Don't** 在暗色主题复用亮色阴影——暗色下阴影退场、边界接管。
- **Don't** 用渐变横幅、大色块头图、插画式装饰填充管理页面——指挥台上没有海报位。
- **Don't** 把对话列表行归档操作的 hover 显形模式（`opacity-0 group-hover:opacity-100`）外推到表格行与消息工具——后者常驻显形；破坏性操作必须经 Popconfirm 确认。
