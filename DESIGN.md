---
name: MindBase
description: 企业级 RAG 知识库 · 静光玻璃（The Quiet Glass · 亮色 airy）——白底大留白的 Apple 质感工作台，默认亮色；Quiet Glass 暗色组完整保留为可选主题
colors:
  # ── 亮色组 = 默认主题（normative）──
  accent: "#5856D6"
  accent-hover: "#3E3FB0"
  accent-pressed: "#292E8A"
  bg-layout: "#F5F5F7"
  bg-container: "#FFFFFF"
  bg-elevated: "#FFFFFF"
  bg-inverted: "#1D1D1F"
  text-primary: "#1D1D1F"
  text-secondary: "#6E6E73"
  text-tertiary: "#86868B"
  hairline: "rgba(29, 29, 31, 0.09)"
  fill-subtle: "rgba(120, 120, 128, 0.08)"
  fill-hover: "rgba(120, 120, 128, 0.12)"
  material-nav: "rgba(255, 255, 255, 0.72)"
  material-overlay: "rgba(255, 255, 255, 0.76)"
  scrim: "rgba(0, 0, 0, 0.30)"
  success: "#34C759"
  warning: "#FF9500"
  error: "#FF3B30"
  # ── 暗色组 = 保留主题（非默认）；自 Quiet Glass 起原样保留，任何一项不得改动 ──
  accent-dark: "#5B5BE8"
  accent-hover-dark: "#4642C2"
  accent-pressed-dark: "#342D9C"
  bg-layout-dark: "#0A0A0C"
  bg-container-dark: "#1C1C1E"
  bg-elevated-dark: "#2C2C2E"
  bg-inverted-dark: "#F5F5F7"
  text-primary-dark: "#F5F5F7"
  text-secondary-dark: "rgba(235, 235, 245, 0.60)"
  text-tertiary-dark: "rgba(235, 235, 245, 0.40)"
  hairline-dark: "rgba(235, 235, 245, 0.14)"
  fill-subtle-dark: "rgba(120, 120, 128, 0.24)"
  fill-hover-dark: "rgba(120, 120, 128, 0.32)"
  material-nav-dark: "rgba(29, 29, 31, 0.68)"
  material-overlay-dark: "rgba(44, 44, 46, 0.72)"
  scrim-dark: "rgba(0, 0, 0, 0.50)"
  success-dark: "#30D158"
  warning-dark: "#FF9F0A"
  error-dark: "#FF453A"
typography:
  display:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "32px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "normal"
  headline:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.35
    letterSpacing: "normal"
  title:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "normal"
  body:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.75
    letterSpacing: "normal"
  footnote:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: "normal"
  caption:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  mono:
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "normal"
rounded:
  sm: "10px"
  md: "14px"
  lg: "18px"
  xl: "24px"
  capsule: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  2xl: "24px"
  3xl: "32px"
  4xl: "48px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "#FFFFFF"
    typography: "600 14px/1.4"
    rounded: "{rounded.sm}"
    padding: "0 16px"
    height: "36px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-pressed:
    backgroundColor: "{colors.accent-pressed}"
  button-secondary:
    backgroundColor: "{colors.fill-subtle}"
    textColor: "{colors.text-primary}"
    typography: "600 14px/1.4"
    rounded: "{rounded.sm}"
    padding: "0 16px"
    height: "36px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.accent}"
    typography: "600 14px/1.4"
    rounded: "{rounded.sm}"
    padding: "0 12px"
    height: "36px"
  input-field:
    backgroundColor: "{colors.bg-container}"
    textColor: "{colors.text-primary}"
    typography: "400 15px/1.4"
    rounded: "{rounded.sm}"
    padding: "0 12px"
    height: "36px"
  card:
    backgroundColor: "{colors.bg-container}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
    padding: "28px 32px"
  tag-capsule:
    backgroundColor: "{colors.fill-subtle}"
    textColor: "{colors.text-secondary}"
    typography: "500 12px/1"
    rounded: "{rounded.capsule}"
    padding: "0 10px"
    height: "22px"
  nav-item:
    backgroundColor: "transparent"
    textColor: "{colors.text-secondary}"
    typography: "500 14px/1.4"
    rounded: "{rounded.sm}"
    padding: "0 10px"
    height: "36px"
  chat-bubble-user:
    backgroundColor: "{colors.accent}"
    textColor: "#FFFFFF"
    typography: "400 16px/1.75"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  citation-chip:
    backgroundColor: "rgba(88, 86, 214, 0.10)"
    textColor: "{colors.accent}"
    typography: "500 12px/1"
    rounded: "{rounded.capsule}"
    padding: "0 8px"
    height: "20px"
---

# Design System: MindBase

<!-- 世界替换（departure）：按用户 brief 指定的「Apple 高级感」方向全新定义，替换而非精修旧观感；frontmatter token 为规范值（normative），实现落地后须按「Token 落地映射」逐项核对。第三轮（2026-10-20 亮色 airy 结构改）在其上做结构级修订：默认亮色、悬浮浮岛壳层、聊天去盒、排版与留白放大——见 Overview 与 Layout › 轮廓判定。 -->

## Overview

**Creative North Star: "静光玻璃（The Quiet Glass）"——一间洒着均匀日光的安静工作室：磨砂玻璃做门与隔断，白色台面承载全部工作，只有一根靛蓝细线标出"正在进行的事"。第三轮把窗帘完全拉开（默认亮色），并把家具从墙上搬下来（侧栏成为悬浮浮岛）——白天的工作室里，家具是浮岛，台面就是房间本身。**

MindBase 的界面是给企业员工每天提问、给管理员每天治理的工作台，也是给面试官审视的工程门面。它的气质来自三个词：**静**（近单色的中性面，信息本体是唯一的色彩来源）、**磨砂**（导航与浮层用半透明 + backdrop-filter 的真实材质，正文永远坐在实底上）、**一线靛**（全站唯一强调色是 Apple systemIndigo，稀用、克制、出现即意味着"焦点与行动"）。

第三轮（2026-10-20 亮色 airy 结构改）是**结构级**修订，不是细节精修：默认主题定为 light（apple.com 气质——白底、大留白、透），侧栏脱离窗口边缘成为带投影的悬浮浮岛，聊天页拆除圆角盒子改为全幅 airy 画布 + 居中内容列，页面标题与正文字号、区块留白整体拉大。验收标准即「轮廓判定」：并排旧版与新版截图，第一眼必须可辨（见 Layout › 轮廓判定）。密度依然从容——控件高度不动，放大的是排版层与留白：airy 靠空间，不靠大控件。

本文件对旧观感的否决持续有效：SoybeanAdmin 模板默认（Vite 默认紫蓝 `#646cff` 混拼 Ant Design 语义色、6px 碎圆角、静态卡片薄投影、灰阶反转暗色、颜色硬编码绕过 token）不得以"兼容"名义回归；第三轮起，前两轮的旧轮廓（贴边通高侧栏、聊天圆角盒、28px 页题与 24px 区块距）同样不得回归。

**Key Characteristics:**

- **默认亮色**：白底、大留白、透；暗色（Quiet Glass 暗色组）完整保留为可选主题，全部暗色值原样不变
- **悬浮浮岛壳层**：侧栏与窗口边缘脱开（12px 缘距 + 24px 圆角 + 浮岛档投影），顶栏是唯一贴墙的家具
- **聊天无盒**：全幅白画布 + 800px 居中内容列；页面结构最多两层，卡片不嵌卡片
- 近单色中性面 + 单一靛蓝强调色，强调色占任何一屏 ≤10%
- 系统字体栈（macOS 上即 SF Pro + 苹方），排版层放大（页题 32 / 区块题 20 / 正文 16）、控件层不动
- 发丝线（hairline）负责静态分隔；阴影只属于浮层与浮岛
- 150–350ms 标准缓动，状态切换有生命但不喧宾夺主

## Colors

调色策略是**克制（Restrained）**：中性面全部由同一个冷灰族构成，唯一强调色是靛蓝（Apple systemIndigo 的 Web 落法），语义色只在状态出现时进场。**默认主题为 light**——frontmatter 中无后缀的亮色值即开箱值；`-dark` 后缀的暗色组自 Quiet Glass 起原样保留（非默认、值不变），主题切换入口保留。

### Primary

- **静光靛 Quiet Indigo** (`#5856D6`；暗色 `accent-dark #5B5BE8`)：全站唯一强调色。用于：主按钮填充、选中态、链接、聚焦环、引用标注、发送按钮。白字对比 5.65:1（暗色 5.11:1），达标 AA。作为**文字**出现在暗色容器上时对比仅 3.33:1，必须改用深阶——暗色主题下基础色切为 `#5B5BE8`，深阶变量随之由暗色基础色派生（`--primary-400-color` = `#878af5`，5.66:1），或附加下划线。它是 Apple 自己的靛紫系统色：延续产品旧标识的紫调血统，但饱和度与使用密度都大幅降低——用户扫过任何一屏，靛蓝出现的面积不超过 10%。

### Semantic

- **信号绿 Confirm Green** (`#34C759`；暗色 `#30D158`)：连接正常、处理成功、校验通过。填充与圆点用原值；作为**文字**在浅色容器上用 `-700` 深阶（`#147a34`，5.44:1），暗色用由暗色基础色派生的 `-400`（`#57de74`，9.81:1）。
- **信号橙 Caution Orange** (`#FF9500`；暗色 `#FF9F0A`)：限流、重连中、配额将尽。文字用 `-700`（`#b35c00`，4.72:1）/ 暗色 `-400`（`#ffb433`，9.58:1）。
- **信号红 Alert Red** (`#FF3B30`；暗色 `#FF453A`)：删除、失败、引用待核实。文字用 `-700`（`#b31013`，7.00:1）/ 暗色 `-400`（`#ff7063`，6.28:1）。
- info 跟随主色（现有 `isInfoFollowPrimary: true` 保持）。

深阶说明：`-600`/`-700`/`-400` 等色阶由 `createThemePaletteColors` 从**当前主题的基础色**实时生成（`recommendColor: false` 时 500 阶 = 基础色原值），因此暗色主题下同名变量自动取暗色基础色的派生值——上面暗色括号内的 hex 即真实管线的产物，可直接用于 QA。

### Neutral

冷灰一族，色相统一偏向 260°，与靛蓝同族：

- **页面地面 Layout** (`bg-layout #F5F5F7`；暗色 `#0A0A0C`)：应用最底面。亮色取 Apple 标志性浅灰，暗色取近黑的真黑感。亮色下它是浮岛与窗口之间 12px 呼吸缝的 color。
- **面板 Container** (`bg-container #FFFFFF`；暗色 `#1C1C1E`)：卡片、表格等一切承载内容的实底面板；聊天页它同时是全幅画布本身。
- **浮层面 Elevated** (`bg-elevated #FFFFFF`；暗色 `#2C2C2E`)：弹窗、下拉、命令面板等浮层内容底。暗色下靠它与 Container 的层级差表达深度。
- **主文字 Text Primary** (`text-primary #1D1D1F`；暗色 `#F5F5F7`)：正文与标题墨色。对比 15.46:1（暗色 18.17:1）。
- **次级文字 Text Secondary** (`text-secondary #6E6E73`；暗色 `rgba(235,235,245,0.60)`)：说明、占位符、表头。对比 5.07:1。
- **弱文字 Text Tertiary** (`text-tertiary #86868B`；暗色 `rgba(235,235,245,0.40)`)：时间戳、禁用提示。
- **发丝线 Hairline** (`hairline rgba(29,29,31,0.09)`；暗色 `rgba(235,235,245,0.14)`)：一切静态分隔——卡片描边、分隔线、表格线。
- **细填充 Fill Subtle** (`fill-subtle rgba(120,120,128,0.08)`；暗色 `0.24`)：次级按钮底、列表行选中/hover 底、标签底。hover 加深一档（`fill-hover` 0.12 / 0.32）。
- **反色面 Inverted** (`bg-inverted #1D1D1F`；暗色 `#F5F5F7`)：tooltip、深色菜单等反色场景。

### Named Rules

**色彩主权律。** `settings.ts` 的 `recommendColor` 必须为 `false`。开启推荐调色时，CSS 变量（`--primary-color` 等）被吸附到推荐族的 500 阶——`#5856D6` 会被吸到 `#7177e4`（其在推荐族中的真实位置是 600），而 Naive 的 `primaryColor` 仍取原值，屏幕上同时出现两种靛蓝（现状 `#646cff` 恰好是自身推荐族的 500，所以分歧今天不可见）。关闭后调色板 500 阶 = 原值，CSS 变量、Naive、Uno 三方同源；本文件全部色阶与对比度数值按此路径给出。

**一线靛律。** 强调色在一屏内的着色面积 ≤10%。它只标记焦点、选中与主行动；凡是"装饰性变蓝"的地方一律降为中性灰。靛蓝的稀少正是它的高级。

**语义深阶律。** 语义色原值（-500）只做填充、圆点、描边；作为文字出现在地面上必须取深阶——浅色主题 `-700`、暗色主题 `-400`（浅色 `-600` 中 success `#22a146` 3.36:1、warning `#d97700` 3.19:1 不达标；error `-600` `#d9211e` 5.02:1 虽达标，仍统一收进 `-700` 以保持规则单一）。深阶变量随主题基础色切换自动换值，QA 以 Colors › Semantic 列出的实测值为准。

**默认亮色律。** 默认主题为 light：`themeScheme` 定为 `'light'`，不再跟随系统 auto。暗色（Quiet Glass 暗色组）完整保留——全部暗色 token、三层表面、材质加深规则原样可用，切换入口不动；它只是不再是开箱状态，且暗色适配不得反向污染亮色默认值。

## Typography

**Display/Body Font:** 系统字体栈 `-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif`（macOS 即 SF Pro + 苹方，天然成立；Windows 回退 Segoe UI + 微软雅黑）
**Mono Font:** `ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace`（聊天回答里的代码块、MD5、模型参数）

**Character:** 不引入任何外部字体——Apple 质感的一半来自对系统字的绝对信任。层级只靠三件事：字重（400/500/600 三档，600 即"加粗"）、字号对比、留白；不靠颜色与装饰。中文与西文同栈混排，西文走 SF Pro/Segoe，中文走苹方/雅黑，基线自然协调。

### Hierarchy

第三轮把**页面排版层**整体拉大一档（旧值括注）——大字号 + 大留白是 airy 气质的第一载体：

- **Display** (600, 32px, 1.2)（旧 28）：页面大标题与空态大字。**落地目标**：管理页（workspace-page 的 10 个视图）在首个区块之前插入 `.page-title` 页题元素（文案取路由标题）——当前 views 无任何页题元素（grep `page-title|text-32px|<h1` 零命中），类与元素一并落地，见 Token 落地映射 §9。中文标题不做负字距；仅纯西文/数字的展示行可用 `-0.02em`。
- **Headline** (600, 20px, 1.35)（旧 17）：区块标题、卡片标题、弹窗标题。这是"一眼可见的层级主角"。
- **Title** (600, 16px, 1.4)（旧 15）：列表行主文字、会话列表条目标题。
- **Body** (400, 16px, 1.75)（旧 15/1.65）：阅读正文——AI 回答、说明长文、气泡内文。中文长文行高 1.75 起步（中文行高律的 ≥1.6 仍是不变下限），段距 ≥ 一个行高的一半。
- **Footnote** (400, 14px, 1.55)（旧 13）：辅助说明、面包屑、引用预览正文、表单说明。
- **Caption** (400, 12px, 1.4)：状态微标签（已连接/重连中）、角标、时间戳。唯一不参与放大的文字。
- **Mono** (400, 14px, 1.6)（旧 13）：代码、MD5、日志。

### Named Rules

**刻度分层律。** 放大只作用于「页面排版层」（Display/Headline/Title/Body/Footnote）；**控件内部文字不放大**——按钮 14、输入框 15、标签 12、表头 13、表格单元格 15、Naive `common.fontSize` 14。控件发胖会让密度失控，airy 变成臃肿；留白替控件呼吸，表格行高随之放宽到 52px。

**中文行高律。** 凡中文正文（含聊天回答、引用预览），行高 ≥1.6；UI 标签 1.4 起步。行高 1.5 以下的中文正文视为缺陷。

**数字等宽律。** 一切数据展示（配额、用量、表格数值、Token 计数）使用 `font-variant-numeric: tabular-nums`，滚动刷新时数字不跳动。

**负字距禁区。** `letter-spacing` 只允许出现在纯西文/数字的 Display 行（-0.02em）；中文任何场景字距为 0；全大写仅限英文缩写徽标（如 "RAG"），中文标签永不模拟大写（不用加宽字距冒充小标题）。

## Layout

4pt 网格（xs 4 / sm 8 / md 12 / lg 16 / xl 20 / 2xl 24 / 3xl 32 / 4xl 48），任何间距都是 4 的倍数。

- **壳层与浮岛**：顶栏高 52px、**通幅贴顶**、磨砂材质 + 底缘发丝线投影——它是房间里唯一贴墙的家具（apple.com 全局导航的姿态）。**通幅是硬要求**：壳层框架（AdminLayout）在 vertical 模式默认给顶栏让出岛宽的左内边距（left-gap），浮岛化后必须清零，否则岛顶让出的左上条带露出裸地面、发丝线在岛宽处生硬截断（落点见 Token 落地映射 §9）。主导航侧栏是**悬浮浮岛**：宽 220px（折叠 64px），nav-material 磨砂，**缘距 12px**（左、下各 12px，顶部与顶栏下缘再脱开 12px，岛自视口 y=64px 起），**圆角 24px（xl）**，投影用**浮岛档 elevation-island**（见 Elevation），且**投影挂 aside 本体**——aside 自身的 overflow-hidden 只裁内容、不裁自身投影。岛不与顶栏相接、不贴窗口边缘、不通高——窗口左缘与下缘永远露出 12px 地面缝。**内容区左缘 = 岛右缘 + 12px 缝**：展开态岛 x∈[12,232]、内容自 x=244 起；折叠态岛 x∈[12,76]、内容自 x=88 起，缝里透出 bg-layout 地面色（App 根部 RouterView bg-layout）。折叠态 64px 宽的岛同为 24px 圆角（近似胶囊，是合法浮岛形态）。
- **去套盒（De-boxing）**：页面结构最多两层——地面（或全幅画布）与直接落在其上的区块。三条禁令：① 聊天消息流禁止装进圆角盒子（见下「聊台画布」）；② 禁止「页中页」——内容区不得再铺一层通栏圆角面板把整个页面包起来；③ 禁止卡片嵌卡片——卡片内的分组用留白 + 发丝线表达，不长出新盒子。表格可坐卡片内（数据区块），但仅此一层。
- **聊台画布**：聊天页**没有盒子**。内容区是一整幅全幅白画布（`bg-container` 直铺 main 区域——贴顶栏下缘、窗口右/下缘与 x=244 的内容左缘，无圆角、无描边、无边距）——「画布即房间，不是桌上的板子」。消息流与输入壳同轨 800px 居中，直接坐在白底上。会话列表是画布左缘的**第二座浮岛**（nav-material、12px 缘距、24px 圆角、浮岛投影）；它浮在白画布上、**不铺灰缝**——nav-material 亮值叠白底≈纯白，岛形由 elevation-island 投影勾出（暗色同理）。global 浮岛与画布之间的 12px 地面缝在 main 左缘之外（x∈[232,244]），透出地面色，自然可见。
- **页面节奏**：内容区左右留白 40px（≥1280px 视口）/ 24px（1024–1279px）/ 16px（≤640px）；区块（卡片）间距 **3xl 32px**（旧 24）；卡片内边距 **28px 32px**（旧 20 24）；卡片标题与内容间距 xl（20px）。页面标题（Display 32）上方留白 4xl（48px）、与首个区块间距 3xl（32px）；「空间大于标题」强化：标题上方留白 ≥ 标题下方 ×1.5。
- **阅读列**：聊天阅读列与输入壳同轨，最大 800px 居中；中文一行约 50 字，不追一行更长。管理页不限列宽（数据工作台），靠区块间距呼吸。
- **密度**：按钮/输入框高 36px（小 28 / 大 44），列表行 44px，**表格行 52px**（旧 48），菜单项 36px。控件尺寸不放大（刻度分层律）。
- **响应式**：UnoCSS presetWind3 断点（sm 640 / md 768 / lg 1024 / xl 1280 / 2xl 1536）。≤640px：浮岛收回为抽屉 + 遮罩（scrim `rgba(0,0,0,0.30)`——岛形态只在桌面存在），卡片内边距降为 lg（16px），表格横向滚动，触控目标 ≥44px。
- **轮廓判定（本轮验收）**：并排旧版与新版截图，三处轮廓差异必须第一眼可辨——①窗口边缘出现 12px 缝隙与浮岛投影（侧栏脱开，岛 x∈[12,232]、内容自 244 起）；②聊天页白画布无盒子描边；③管理页出现 32px 页题（`.page-title`）+ 20px 区块题 + 32px 区块距 + 40px 页边距。任一不可辨即视为本轮未落地。

## Elevation & Depth

**发丝线优先，阴影只属于浮层与浮岛。** 静态面板（卡片、表格）零阴影不变，靠 Container 与 Layout 的表面色差 + 一圈发丝线站住；阴影是"离开页面"的专属语言，只给两类存在：离开页面的浮层（popover、模态、命令面板），与**结构上离开地面的浮岛**（导航侧栏）。暗色不是反转而是**真分层**：地面 #0A0A0C → 面板 #1C1C1E → 浮层 #2C2C2E，阴影在暗色下更重，但主要深度信号仍是层级差与发丝线。

### Material Vocabulary

- **导航材质 nav-material**：亮 `rgba(255,255,255,0.72)` / 暗 `rgba(29,29,31,0.68)`，配 `backdrop-filter: saturate(180%) blur(20px)`。用于顶栏、两座浮岛侧栏（主导航、会话列表）、移动端抽屉。
- **浮层材质 overlay-material**：亮 `rgba(255,255,255,0.76)` / 暗 `rgba(44,44,46,0.72)`，同款 filter。用于下拉菜单、命令面板、popover。
- **遮罩 scrim**：亮 `rgba(0,0,0,0.30)` / 暗 `rgba(0,0,0,0.50)`，模态背后的世界。
- **正文永远实底**：卡片、表格、回答排版不用 backdrop-filter——文字下面的东西必须是不动的。浮岛是壳层不是正文，可用材质。

### Shadow Vocabulary

- **elevation-island 浮岛档**（亮 `0 1px 2px rgba(0,0,0,0.04), 0 12px 32px rgba(0,0,0,0.10)`；暗 `0 1px 2px rgba(0,0,0,0.45), 0 12px 32px rgba(0,0,0,0.55)`）：悬浮浮岛专属。比 popover 的 e1 更宽更软——岛是常驻结构，投影要像日光下的软影，不是悬浮提示的锐影；同一岛内禁止叠第二层投影。岛自身不再用 inset 发丝线（旧 `tokens.*.boxShadow.sider` 的 inset 值退役——岛已脱离边缘，无需伪造边界）。投影挂在 aside 本体：容器的 overflow-hidden 只裁内容、不裁自身投影；壳层框架 aside 内建的 shadow-2xl 由 sider-class 覆盖为本档。
- **elevation-1**（`box-shadow: 0 1px 4px rgba(0,0,0,0.06), 0 4px 16px rgba(0,0,0,0.08)`；暗色 `0 1px 4px rgba(0,0,0,0.40), 0 4px 16px rgba(0,0,0,0.45)`）：popover、下拉菜单、tooltip、聊天输入壳静置态。
- **elevation-2**（`0 4px 12px rgba(0,0,0,0.08), 0 16px 48px rgba(0,0,0,0.18)`；暗色 `0 4px 12px rgba(0,0,0,0.45), 0 16px 48px rgba(0,0,0,0.55)`）：模态、对话框、抽屉。
- **elevation-3**（`0 8px 24px rgba(0,0,0,0.10), 0 24px 80px rgba(0,0,0,0.24)`；暗色 `0 8px 24px rgba(0,0,0,0.50), 0 24px 80px rgba(0,0,0,0.65)`）：命令面板等最高层浮层。
- **发丝线阴影**（顶栏 `0 0.5px 0 rgba(29,29,31,0.12)`；暗色换 `rgba(235,235,245,0.14)`）：顶栏用 0.5px 投影伪造物理发丝线，不是"阴影"。（旧侧栏 inset 发丝线已随浮岛化退役。）

### Named Rules

**阴影只属浮层律。** 静态卡片出现 `box-shadow` 即为缺陷；例外仅两处——壳层顶栏发丝线投影、浮岛档（岛在结构上是浮层）。hover 不抬卡片——用 fill-hover 一层底色回应即可。

**浮岛律。** 浮岛只属于壳层：主导航侧栏与会话侧栏是仅有的两座岛。内容面板永不浮岛化——卡片坐在地面上，靠发丝线站住；把内容卡片做成浮岛是第三轮轮廓的倒置。

**暗色真分层律。** 暗色主题禁止把亮色值简单取反或整体调灰；必须使用本节的三层表面值。暗色下的强调色与语义色使用各自的 `-dark` 变体（切换经 theme store 的 `themeColors` 按 darkMode 返回，见 Token 落地映射 §1——Naive 与 CSS 变量同源换血）。

## Shapes

圆角是这个世界最重要的轮廓语言：**连续放大、只有五档**。

- **sm 10px**：一切控件——按钮、输入框、下拉框、菜单项、列表行、开关轨道。
- **md 14px**：输入框内嵌套件、分段控件、卡片内的小面板、图片缩略块。
- **lg 18px**：卡片、对话框、聊台输入壳、用户气泡。
- **xl 24px**：悬浮浮岛（两座侧栏）、命令面板、大面积浮层与"画板"级容器。
- **capsule 9999px**：标签、状态胶囊、引用 chip、开关滑块、进度条。

大圆角属于"浮在空间里的东西"（浮岛、命令面板），不属于"放在地上的东西"——内容面板最高 18px。按钮是 10px 圆角矩形（不是 iOS 胶囊——桌面企业工具要效率感）；标签与状态一律胶囊；用户消息气泡 18px，仅靠发信侧那一角收窄到 6px 形成方向感。控件高度与圆角成对出现：36px 高的控件配 10px 圆角，18px 圆角的面板内边距不小于 28px——圆角越大，呼吸越深。聊天画布不是容器：没有圆角，也没有描边。

## Components

### Buttons

- **Shape:** 10px 圆角（sm），高度 36px（small 28 / large 44），左右内边距 16px，字重 600、14px。
- **Primary:** 靛蓝填充白字（`accent` 上白字 5.65:1）。hover 取基础色深一阶（亮 `accent-hover` `#3E3FB0` / 暗 `accent-hover-dark` `#4642C2`，暗色按 Apple 惯例改为提亮），pressed 再深一阶（`accent-pressed` `#292E8A` / `accent-pressed-dark` `#342D9C`），按压同时整体缩放 0.97。
- **Secondary:** 细填充灰底（`fill-subtle`）+ 主文字色；hover 加深一档（`fill-hover`）。
- **Ghost:** 无底透明，靛蓝文字；用于行内次级行动。
- **Hover / Focus:** hover 是颜色/底色的 150ms 过渡；`focus-visible` 一律 2px 靛蓝描边 + 2px 偏移（或 `0 0 0 3px rgba(88,86,214,0.28)` 外圈），永不移除焦点样式。

### Chips / Tags

- **Style:** 胶囊（9999px），`fill-subtle` 底 + 次级文字色，高 22px，内边距 0 10px，12px/500。
- **State:** 选中 = 靛蓝 10% 透明底（亮）/ 16%（暗）+ 靛蓝文字；组织标签权限类标签用语义色深阶文字。

### Cards / Containers

- **Corner Style:** 18px（lg）——内容面板的最高圆角；24px（xl）只归浮岛与命令面板。
- **Background:** `bg-container`，坐在 `bg-layout` 地面上。
- **Shadow Strategy:** 永久零阴影，见「阴影只属浮层律」。
- **Border:** 一圈发丝线（`hairline`）。
- **Internal Padding:** 28px 32px（第三轮放大，旧 20 24）；卡片标题（headline 20px/600）与内容的间距 xl（20px）。卡片与卡片的间距 3xl（32px）。

### Inputs / Fields

- **Style:** `bg-container` 实底 + 发丝线描边 + 10px 圆角，高 36px，文字 15px。聊天输入壳是放大版：18px 圆角、内边距 12px 14px、文字 16px、静置时带 elevation-1 级别的极轻投影+发丝线，聚焦时投影加深为浮层级。
- **Focus:** 发丝线变靛蓝 + `0 0 0 3px rgba(88,86,214,0.20)` 外圈光晕，150ms。
- **Error / Disabled:** 错误描边用 `error` 原值，错误说明文字用 `error-700/-400`；禁用整体 40% 不透明度。

### Navigation

- **浮岛侧栏（主导航）:** nav-material 磨砂浮岛——缘距 12px、圆角 24px、elevation-island 投影、岛内无边框。导航项 36px 高、10px 圆角、14px/500 次级文字色；选中 = `fill-subtle` 底 + 主文字色（不是靛蓝底！导航的靛蓝只给左侧 3px 指示条或图标）；hover = `fill-hover`。
- **顶栏:** 52px 通幅贴顶磨砂材质 + 底缘发丝线投影；工具图标按钮 28px 高、圆角 8px、hover `fill-hover`。
- **会话侧栏（chat 第二浮岛）:** 同浮岛规格（12px 缘距 / 24px 圆角 / nav-material / elevation-island）；与主浮岛之间露出 12px 地面缝。
- **Mobile:** ≤640px 浮岛收回为抽屉 + scrim 遮罩，滑入 350ms emphasized 曲线。

### Chat（signature surface）

- **画布:** 全幅白（`bg-container` 直铺内容区），无圆角、无描边、无边距——画布即房间，消息流直接坐在白底上；消息条目间距 32px，正文 16px/1.75。
- **用户气泡:** 靛蓝填充白字、18px 圆角、右上角 6px、内边距 12px 16px、16px/1.75，右对齐；AI 回答无气泡——直接坐在白画布上的正文排版（16px/1.75），与用户气泡形成"材料 vs 印刷"的对比。
- **引用标注（数据流不动）:** 回答句末的 `(来源#编号: 文件名 | 第X页)` 一律渲染为引用 chip 样式的行内链接：靛蓝 10% 底胶囊、12px/500 靛蓝文字、悬停提亮。"待核实"徽标用 `warning-700/-400` 文字。只许改样式层（linkClass / `.citation-*` CSS），DOM 结构、解析正则与 referenceMappings 数据流零改动。

### Tables

- 表头 13px/500 次级文字色、无背景色、下缘发丝线；行高 52px、行 hover `fill-subtle`、数值列 `tabular-nums`；表格圆角 10px；斑马纹禁用。

## Do's and Don'ts

### Do:

- **Do** 默认亮色（`themeScheme: 'light'`），白底大留白；暗色组完整保留、原值切换。
- **Do** 壳层浮岛：12px 缘距、24px 圆角、nav-material、elevation-island 投影；≤640px 收回抽屉——浮岛只属于壳层，内容面板永远坐在地面上。
- **Do** 聊天页全幅白画布 + 800px 居中内容列；页面结构最多两层，分组靠留白与发丝线。
- **Do** 用发丝线（`hairline`，0.5–1px 的 9%–14% 墨色）做一切静态分隔；分隔的第一反应是线，不是影。
- **Do** 强调色只花在：主按钮、选中态、链接、聚焦环、引用 chip、发送键——每屏 ≤10%。
- **Do** 中文正文行高 ≥1.6（本轮正文 16px/1.75），数据一律 `tabular-nums`，间距全部落在 4pt 网格上。
- **Do** 暗色使用三层表面（#0A0A0C / #1C1C1E / #2C2C2E）与各自的 `-dark` 强调/语义变体，与亮色同等打磨。
- **Do** 状态与浮层动效只用三档时长（150/250/350ms）与三条缓动曲线，`prefers-reduced-motion` 时全部降到接近 0。

### Don't:

- **Don't** 硬编码任何十六进制色值（包括 `bg-white`、`dark:bg-[#1c1c1c]` 这类任意值）绕过 token——所有颜色经 settings.ts、CSS 变量或 Uno token 流转。
- **Don't** 把聊天消息流装回圆角盒子（带描边与 24px 圆角的 `.chat-surface` 已退役），也不要卡片嵌卡片或铺「页中页」通栏面板。
- **Don't** 给静态卡片、表格、面板加阴影或 hover 上浮；阴影只属于浮层与浮岛（elevation-island）。
- **Don't** 使用第二强调色、渐变、霓虹、玻璃拟态渐层、彩色大色块——这个世界的色彩预算只有一线靛加语义色。
- **Don't** 新控件圆角小于 10px 或 invent 第六档圆角；中文正文与标题使用负字距；控件内部文字跟随排版层放大。
- **Don't** 改动引用标注的 DOM 结构、解析正则或 referenceMappings 数据流（AGENTS.md 硬约束）；样式只能经 CSS 层表达。

## Token 落地映射

每个 token 落到哪个文件、哪个字段/键。文件路径均相对 `frontend/`。

### 1. 强调色与语义色

| Token | 落点 |
| --- | --- |
| `accent`（亮） | `src/theme/settings.ts` `themeColor: '#5856D6'`（原 `'#646cff'`），**且 `recommendColor: true` → `false`**（settings.ts 第 6 行，色彩主权律）。原理：`createThemePaletteColors`（shared.ts 85–100 行）把 CSS 变量锚在 `getColorPalette(color, recommendColor).get(500)`——推荐路径下 `#5856D6` 的 500 阶是 `#7177e4`（其实际位置是推荐族 600），而 Naive `primaryColor` 取原值，两套靛蓝同屏；关闭后 500 阶 = 原值，三方同源。主题抽屉的推荐色开关（theme-drawer/modules/theme-color.vue 41–42 行）保留但默认关闭 |
| `accent-hover` / `accent-pressed`（两主题） | `getNaiveThemeColors` 的场景派生（shared.ts 213–219 行）：Hover 处理器 `getPaletteColorByNumber(color, 500, recommended)` 改为 `(color, 600, false)`；Pressed 保持 700 但 `recommended` 实参改 `false`（即 `(color, 700, false)`）。理由：`recommendColor: false` 后 500 阶 = 基础色，hover 将失去反馈；600/700 阶即规范值（亮 `#3E3FB0`/`#292E8A`，暗 `#4642C2`/`#342D9C`），且随暗色基础色切换自动换值，四个颜色统一生效 |
| `success / warning / error`（亮） | `settings.ts` `otherColor: { info: '#5856D6', success: '#34C759', warning: '#FF9500', error: '#FF3B30' }`（原 Ant Design 值） |
| `accent-dark` 与语义色（暗）——唯一路线 | `src/store/modules/theme/index.ts` 的 `themeColors` computed（现 40–48 行）读取本 store 的 `darkMode`，暗色时返回 `{ primary: '#5B5BE8', success: '#30D158', warning: '#FF9F0A', error: '#FF453A' }`。下游双通道自动生效：`naiveTheme` computed（index.ts 51 行）→ Naive 全组件；`watch(themeColors)` → `setupThemeVarsToGlobal`（index.ts 139–146、190–198 行）→ CSS 变量与 Uno 色阶。这是唯一同时喂 Naive（App.vue 44–47 行经 `:theme-overrides` 注入 JS 对象，不读全局 CSS 变量）与 CSS 变量的单点 |
| 禁用路线 | **禁止**用 `global.scss` 的 `html.dark` 覆盖 `--primary-color` 等三元组：`addThemeVarsToGlobal` 运行时同时写 `:root` 与 `html.dark` 两块（shared.ts 147–157 行，后者同样声明这批变量），而 global.scss 经 `vite.config.ts` 23–26 行 `additionalData` 编译进每个组件——两者同特异性，胜负取决于样式注入顺序，连 CSS 变量层都不稳，更到不了 Naive |
| 深阶（`-700`/`-400` 等） | 无需新文件：`createThemePaletteColors` 已从**当前主题基础色**生成 50–950 全阶 CSS 变量（`--success-700-color` 等，vars.ts 3–13 行）。`recommendColor: false` 后，本文档 Colors 节列出的全部色阶 hex 与对比度即真实管线产物；暗色主题下同名变量自动切换为暗色基础色的派生值 |

### 2. 表面色与文字色

| Token | 落点 |
| --- | --- |
| `bg-container` | `settings.ts` `tokens.light.colors.container: 'rgb(255, 255, 255)'`（不变）；`tokens.dark.colors.container: 'rgb(28, 28, 30)'` |
| `bg-layout` | `tokens.light.colors.layout: 'rgb(245, 245, 247)'`；`tokens.dark.colors.layout: 'rgb(10, 10, 12)'` |
| `bg-inverted` | `tokens.light.colors.inverted: 'rgb(29, 29, 31)'`；`tokens.dark.colors.inverted: 'rgb(245, 245, 247)'` |
| `text-primary` | `tokens.light.colors['base-text']: 'rgb(29, 29, 31)'`；`tokens.dark.colors['base-text']: 'rgb(245, 245, 247)'` |
| 上述四键的 CSS 变量与 Uno 工具类 | 经 `src/theme/vars.ts`（`--container-bg-color` 等）→ `addThemeVarsToGlobal` → Uno `bg-container / bg-layout / bg-inverted / text-base-text` 自动生效，无额外文件 |
| `bg-elevated`（不在四键管线） | `global.scss` `:root { --app-elevated: #FFFFFF }` + `html.dark { --app-elevated: #2C2C2E }`；`uno.config.ts` `theme.colors` 增 `elevated: 'var(--app-elevated)'` 得到 `bg-elevated` 工具类 |
| `hairline` | `global.scss` 现有 `--app-border` 改为 `:root { --app-border: rgb(var(--base-text-color) / 0.09) }` + `html.dark { --app-border: rgb(var(--base-text-color) / 0.14) }`；`uno.config.ts` `theme.colors` 增 `hairline: 'var(--app-border)'` 得到 `border-hairline` |
| `fill-subtle / fill-hover` | `global.scss` `:root`/`html.dark` 定义 `--app-fill-subtle`、`--app-fill-hover`；`uno.config.ts` `theme.colors` 增同名别名 |

### 3. 字体栈与文字层级

| Token | 落点 |
| --- | --- |
| 字体栈 | `src/styles/css/reset.css` 31–40 行 `html { font-family }` 整体替换为本文件的栈 |
| Naive 侧字体 | `getNaiveTheme`（`src/store/modules/theme/shared.ts` 242–259 行）`common` 增：`fontFamily`（同栈）、`fontFamilyMono`、`fontWeight: '400'`、`fontWeightStrong: '600'`、`lineHeight: '1.75'`、`fontSize: '14px'`、`fontSizeSmall: '13px'`、`heightMedium: '36px'`、`heightSmall: '28px'`、`heightLarge: '44px'`（键名已对照 naive-ui `ThemeCommonVars` 类型核实；`common.fontSize` 14px 是控件层，不随排版层放大——刻度分层律） |
| 层级（display/headline/…） | 全局样式经 `global.scss` 工具类/组件样式落：页题类 `.page-title` 32px/1.2、区块标题 Headline 20px/1.35、正文 Body 16px/1.75、说明 Footnote 14px；`.page-section` 卡片标题现为 20px/600（`src/styles/scss/global.scss` 132 行）；页题元素落在全部 workspace-page 视图首个区块前（§9）；数字等宽律在 `global.scss` 对 `.n-data-table` 与数据组件加 `font-variant-numeric: tabular-nums` |

### 4. 圆角刻度

| Token | 落点 |
| --- | --- |
| `sm 10px`（Naive 基准） | `shared.ts` 248 行 `borderRadius: '6px'` → `'10px'`；同处增 `borderRadiusSmall: '6px'` |
| `capsule` | `shared.ts` 253–255 行 `Tag.borderRadius: '6px'` → `'9999px'` |
| `lg 18px` | `getNaiveTheme` 返回对象增 `Card: { borderRadius: '18px' }`、`Dialog: { borderRadius: '18px' }` |
| `xl 24px` | **第三轮归浮岛**：两座侧栏（见 §9 浮岛行）与命令面板（global-search）用 24px；聊天画布已去盒，不再使用 xl |
| 作用域样式 | `global.scss` `:root` 定义 `--app-radius-sm/md/lg/xl`（10/14/18/24px）；`.page-section.n-card` 的 `border-radius` 用 `--app-radius-lg` |
| Uno | `uno.config.ts` 26 行 `card-wrapper: 'rd-3 shadow-none'` → `'rounded-18px border border-solid border-hairline shadow-none'` |

### 5. 阴影与材质

| Token | 落点 |
| --- | --- |
| 壳层发丝线投影 | `settings.ts` `tokens.light.boxShadow.header: '0 0.5px 0 rgba(29, 29, 31, 0.12)'`、`tab: 'none'`；`tokens.dark.boxShadow.header` 同形（色换 `rgba(235, 235, 245, 0.14)`）。经 vars.ts → `--header-box-shadow` 自动生效。**侧栏 inset 发丝线自第三轮起退役**——`tokens.*.boxShadow.sider` 改载浮岛投影，见 §9 |
| `elevation-island` | `settings.ts` `tokens.light.boxShadow.sider: '0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.10)'`；`tokens.dark.boxShadow.sider: '0 1px 2px rgba(0, 0, 0, 0.45), 0 12px 32px rgba(0, 0, 0, 0.55)'`（原 inset 值替换）。消费点：`base-layout/index.vue` sider-class 的 `shadow-sider!` 挂 aside **本体**（aside 自身 of-hidden 不裁自身投影；内层 `global-sider/index.vue` 34 行的 `shadow-sider` 类移除防双影，见 §9）；`global.scss` 另定义 `--app-shadow-island` 同值供作用域样式用 |
| `elevation-1/2/3` | `getNaiveTheme` `common` 增 `boxShadow1/2/3`（对应 popover/modal/最高浮层，键名已核实）；作用域样式用 `global.scss` 的 `--app-shadow-e1/e2/e3` |
| `nav-material / overlay-material / scrim` | `global.scss` `:root`/`html.dark` 定义 `--app-material-nav`、`--app-material-overlay`、`--app-material-filter: saturate(180%) blur(20px)`、`--app-scrim`；消费点：`layouts/modules/global-header/index.vue`（顶栏毛玻璃）、两座浮岛侧栏、`layouts/modules/global-search/`、`layouts/modules/theme-drawer/`、Naive popover（`global.scss` 对 `.n-popover` 覆盖 `background: var(--app-material-overlay); backdrop-filter: var(--app-material-filter)`） |
| 静态卡零阴影 | 删除各处 `shadow-sm`/`shadow-sider` 类的装饰性用法（如 `conversation-sidebar.vue` 分段控件的 `shadow-sm`） |

### 6. 动效

| Token | 落点 |
| --- | --- |
| 时长与缓动 | `global.scss` `:root` 定义 `--app-dur-fast: 150ms; --app-dur-base: 250ms; --app-dur-slow: 350ms; --app-ease-standard: cubic-bezier(0.4, 0, 0.2, 1); --app-ease-emphasized: cubic-bezier(0.32, 0.72, 0, 1); --app-ease-exit: cubic-bezier(0.4, 0, 1, 1)` |
| Naive 内部动效 | `getNaiveTheme` `common` 增 `cubicBezierEaseInOut: 'var(--app-ease-standard)'`、`cubicBezierEaseOut: 'var(--app-ease-emphasized)'`、`cubicBezierEaseIn: 'var(--app-ease-exit)'`（键名已核实） |
| 页面过渡 | `src/styles/css/transition.css` 各过渡改绑上述变量（fade-slide 0.3s → 250ms emphasized 等） |
| 无障碍兜底 | `global.scss` 增 `@media (prefers-reduced-motion: reduce) { *, ::before, ::after { transition-duration: 0.01ms !important; animation-duration: 0.01ms !important; } }` |
| 迁移点 | 硬编码时长改为 token：如 `views/chat/modules/input-box.vue` 498 行 `box-shadow … 0.18s ease` → `var(--app-dur-fast) var(--app-ease-standard)` |

### 7. 布局与几何

| Token | 落点 |
| --- | --- |
| 顶栏 52px | `settings.ts` `header.height: 52`（已落地） |
| 侧栏 220/64 | `settings.ts` `sider.width: 220`（已落地；`collapsedWidth: 64` 不变） |
| 阅读列 800px | `views/chat/modules/input-box.vue` 442/465 行 `max-w-[960px]` → `max-w-[800px]`；聊天回答列同轨 |
| 页面节奏 | `global.scss` `.workspace-page { gap }` 第三轮 24px → 32px；卡片内边距第三轮 → 28px 32px（详见 §9，本行旧值作废） |

### 8. 落地顺序建议

1. settings.ts（`recommendColor: false` + 颜色四键 + 语义色 + 阴影 + 布局尺寸）→ 全局立刻换血；
2. shared.ts `getNaiveTheme`（圆角/字体/高度/浮层阴影/缓动，及 §1 的 hover/pressed 派生改 600/700 阶）与 theme store `themeColors` 的暗色分支 → Naive 全组件生效；
3. global.scss（材质、fill、elevated、radius 变量、动效变量、reduced-motion、表格 tabular-nums）+ uno.config.ts（hairline/fill/elevated 别名、card-wrapper、glass-nav shortcut）；
4. reset.css 字体栈；
5. 壳层与旗舰页（header 毛玻璃、input 壳、引用 chip 样式、conversation-sidebar 去硬编码）；
6. **第三轮亮色 airy 结构（§9）**：`themeScheme: 'light'`（settings.ts + overrideThemeSettings 双落点）→ 顶栏通幅（清 left-gap）→ 浮岛侧栏（left-gap +24 / aside 本体投影）→ 聊天去盒 → 排版与留白刻度（含 `.page-title` 页题元素）→ 按「轮廓判定」逐条验收。

### 9. 亮色 airy 结构改（第三轮，2026-10-20）

以下落点均在本次编写时对照源码核实过行号与现值。

| Token | 落点 |
| --- | --- |
| 默认主题 light | `src/theme/settings.ts` 3 行 `themeScheme: 'auto'` → `'light'`（不再跟随系统），**且** 61–69 行 `overrideThemeSettings` 增 `themeScheme: 'light'`——dev 直读 themeSettings 恒见新值（shared.ts 15 行），prod 存量会话 `defu(localSettings, themeSettings)` 缓存优先（shared.ts 22 行），只改默认值对老用户不生效，必须走 override 通道（shared.ts 24–30 行按 BUILD_TIME 触发强推）。暗色完整保留：theme-drawer 切换入口、theme store `themeColors` 暗色分支与全部 `-dark` token 不动 |
| 顶栏通幅 | `src/layouts/base-layout/index.vue` 125 行 `header-class` 增 `pl-0!`：清掉 AdminLayout 在 vertical 模式给 header 的 left-gap（`packages/materials/src/libs/admin-layout/index.vue` 83/131 行 + index.module.css 49–51 行 `padding-left: var(--soy-sider-width)`），顶栏材质与发丝线投影（global-header/index.vue 39 行内层 DarkModeContainer）随之铺满全幅 x∈[0,W]。不清零则岛顶让出的左上 220×52 条带露出裸地面、发丝线在岛宽处截断。顶栏内容自 16px 起（岛在顶栏下方 y≥64px，无重叠） |
| 浮岛侧栏几何 | 壳层 aside 的内建默认本就是浮岛姿态（admin-layout/index.vue 170 行 `absolute left-4 top-4 … rd-4 shadow-2xl`），现被 `src/layouts/base-layout/index.vue` 126 行 `sider-class="top-0! left-0! h-full! rounded-none! shadow-none!"` 压平。改为 computed sider-class（随 `appStore.siderCollapse` 切宽度）：展开 `top-64px! left-12px! bottom-12px! h-auto! w-220px! rounded-24px! shadow-sider!`，折叠同形 `w-64px!`。`shadow-sider!`（Uno 动态类 = `var(--sider-box-shadow)`，vars.ts 30–33 行）挂 aside 本体——aside 自身 `of-hidden`（admin-layout 170 行）只裁内容不裁自身投影；内层 `global-sider/index.vue` 34 行的 `shadow-sider` 类移除防双影，DarkModeContainer 改 `h-full rounded-24px overflow-hidden`（材质随圆角裁切）。**左缝对齐**：`:sider-width="siderWidth + 24"`、`:sider-collapsed-width="siderCollapsedWidth + 24"`（+24 = 左缘 12px + 右缝 12px），left-gap 的 `--soy-sider-width`/`--soy-sider-collapsed-width` 随之变 244/88（index.module.css 49–55 行），header/main/tab/footer 左距自动落到岛右缘 + 12px；aside 实宽由 sider-class 的 `w-220px!/w-64px!` 钉住。127 行 `mobile-sider-class` 保留抽屉形态 |
| elevation-island | `settings.ts` 36 行 `tokens.light.boxShadow.sider` 的 `inset -0.5px …` → `'0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.10)'`；50 行 `tokens.dark.boxShadow.sider` → `'0 1px 2px rgba(0, 0, 0, 0.45), 0 12px 32px rgba(0, 0, 0, 0.55)'`。消费点：`base-layout/index.vue` sider-class 的 `shadow-sider!` 挂 aside 本体（见上行）；`global.scss` 定义 `--app-shadow-island` 同值，供会话侧栏等作用域样式引用 |
| 聊天画布去盒 | `src/views/chat/index.vue`：26 行外层 `h-full bg-layout p-4` 的灰框与内边距移除；56–58 行 `.chat-surface` 的 `border: 1px solid var(--app-border)` 与 `border-radius: 24px` 删除（flex 布局职责保留），画布改为全幅 `bg-container` 直铺 main（左缘 x=244，贴顶栏下缘与窗口右/下缘）。chat 路由是 `layout.base$view.chat`（`src/router/elegant/routes.ts` 45 行），GlobalContent 给路由页统一加 `p-4 sm:p-6` + `bg-layout`（global-content/index.vue 50–51 行），聊天路由需覆盖 `!p-0` 方能全幅。main 左缘之外 x∈[232,244] 的地面缝透出 App 根部地面色（App.vue 52 行 `<RouterView class="bg-layout">`）。会话岛浮在白画布上、不铺灰缝——nav-material 叠白底≈纯白，岛形由 elevation-island 投影勾出。消息列 800px 居中（§7 阅读列） |
| 会话侧栏浮岛化 | `src/views/chat/modules/conversation-sidebar.vue`：从 `chat-surface` 内嵌位改为画布左缘的浮岛——12px 缘距、24px 圆角、nav-material（组件已有材质样式）、`box-shadow: var(--app-shadow-island)`；移动端保持抽屉 + scrim（`chat/index.vue` 79–93 行现有逻辑不动） |
| 排版层字号 | `src/styles/scss/global.scss` 132 行卡片标题 `17px` → `20px`（行高 1.35）；global.scss 增 `.page-title { font-size: 32px; font-weight: 600; line-height: 1.2; }`；页题元素落在全部 workspace-page 视图首个 `.page-section` 之前（knowledge-base、usage-monitor、user、org-tag、model-provider、invite-code、recharge、recharge-manage、chat-history、personal-center 共 10 页——grep `page-title|text-32px|<h1` 当前零命中，文案取路由标题）；聊天正文（AI 回答、用户气泡、输入壳内文）16px/1.75；控件层（Naive `common.fontSize` 14、按钮 14、输入 15、标签 12、表头 13）不动 |
| 区块留白 | `global.scss` 116 行 `.workspace-page gap: 24px` → `32px`；127/139/142 行卡片内边距 `20px 24px 16px` / `0 24px 24px` / `20px 24px 24px` → `28px 32px 20px` / `0 32px 32px` / `28px 32px 32px`；表格行高 48 → 52px（158–160 行 `.n-data-table-td` 竖向 padding `14px` → `16px`）；页面左右留白经 `global-content/index.vue` 50 行 `p-4 sm:p-6` → `p-4 sm:p-6 xl:px-40px`（≥1280px 视口横向 40px，1024–1279px 维持 24px，≤640px 16px） |
| 验收 | Layout › 轮廓判定三条逐条过：①窗口 12px 缝隙 + 岛投影（岛 x∈[12,232]、内容自 244 起）；②聊天白画布无描边；③管理页 32px 页题 + 20px 区块题 + 32px 区块距 + 40px 页边距。在 `http://localhost:9527` 实页与暗色切换各验一遍（暗色仅验证浮岛/去盒/通幅结构同样成立，暗色 token 值零改动） |
