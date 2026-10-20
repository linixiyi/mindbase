---
name: MindBase
description: 企业级 RAG 知识库 · 静光玻璃（The Quiet Glass）——Apple 质感的近单色工作台，亮暗双主题同等公民
colors:
  accent: "#5856D6"
  accent-hover: "#3E3FB0"
  accent-pressed: "#292E8A"
  accent-dark: "#5B5BE8"
  accent-hover-dark: "#4642C2"
  accent-pressed-dark: "#342D9C"
  bg-layout: "#F5F5F7"
  bg-layout-dark: "#0A0A0C"
  bg-container: "#FFFFFF"
  bg-container-dark: "#1C1C1E"
  bg-elevated: "#FFFFFF"
  bg-elevated-dark: "#2C2C2E"
  bg-inverted: "#1D1D1F"
  bg-inverted-dark: "#F5F5F7"
  text-primary: "#1D1D1F"
  text-primary-dark: "#F5F5F7"
  text-secondary: "#6E6E73"
  text-secondary-dark: "rgba(235, 235, 245, 0.60)"
  text-tertiary: "#86868B"
  text-tertiary-dark: "rgba(235, 235, 245, 0.40)"
  hairline: "rgba(29, 29, 31, 0.09)"
  hairline-dark: "rgba(235, 235, 245, 0.14)"
  fill-subtle: "rgba(120, 120, 128, 0.08)"
  fill-subtle-dark: "rgba(120, 120, 128, 0.24)"
  fill-hover: "rgba(120, 120, 128, 0.12)"
  fill-hover-dark: "rgba(120, 120, 128, 0.32)"
  material-nav: "rgba(255, 255, 255, 0.72)"
  material-nav-dark: "rgba(29, 29, 31, 0.68)"
  material-overlay: "rgba(255, 255, 255, 0.76)"
  material-overlay-dark: "rgba(44, 44, 46, 0.72)"
  scrim: "rgba(0, 0, 0, 0.30)"
  scrim-dark: "rgba(0, 0, 0, 0.50)"
  success: "#34C759"
  success-dark: "#30D158"
  warning: "#FF9500"
  warning-dark: "#FF9F0A"
  error: "#FF3B30"
  error-dark: "#FF453A"
typography:
  display:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "normal"
  headline:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "normal"
  title:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.45
    letterSpacing: "normal"
  body:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.65
    letterSpacing: "normal"
  footnote:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  caption:
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  mono:
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace'
    fontSize: "13px"
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
    padding: "20px 24px"
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
    typography: "400 15px/1.65"
    rounded: "{rounded.lg}"
    padding: "10px 14px"
  citation-chip:
    backgroundColor: "rgba(88, 86, 214, 0.10)"
    textColor: "{colors.accent}"
    typography: "500 12px/1"
    rounded: "{rounded.capsule}"
    padding: "0 8px"
    height: "20px"
---

# Design System: MindBase

<!-- 世界替换（departure）：按用户 brief 指定的「Apple 高级感」方向全新定义，替换而非精修旧观感；frontmatter token 为规范值（normative），实现落地后须按「Token 落地映射」逐项核对。 -->

## Overview

**Creative North Star: "静光玻璃（The Quiet Glass）"——一间洒着均匀日光的安静工作室：磨砂玻璃做门与隔断，白色台面承载全部工作，只有一根靛蓝细线标出"正在进行的事"。**

MindBase 的界面是给企业员工每天提问、给管理员每天治理的工作台，也是给面试官审视的工程门面。它的气质来自三个词：**静**（近单色的中性面，信息本体是唯一的色彩来源）、**磨砂**（导航与浮层用半透明 + backdrop-filter 的真实材质，正文永远坐在实底上）、**一线靛**（全站唯一强调色是 Apple systemIndigo，稀用、克制、出现即意味着"焦点与行动"）。密度从容：控件高度、列表行高与留白都按 4pt 网格放宽，中文正文行高不低于 1.6。暗色不是反转：页面近黑（#0A0A0C）、面板 #1C1C1E、浮层 #2C2C2E 三层真实分层，材质在暗色下加深而非变灰。

本文件是**替换**而非精修：旧观感（SoybeanAdmin 模板默认——Vite 默认紫蓝 `#646cff` 混拼 Ant Design 语义色、6px 碎圆角、静态卡片薄投影、灰阶反转暗色、颜色硬编码绕过 token）被用户明确否决，其中任何一项不得以"兼容"名义回归。

**Key Characteristics:**

- 近单色中性面 + 单一靛蓝强调色，强调色占任何一屏 ≤10%
- 系统字体栈（macOS 上即 SF Pro + 苹方），层级靠字重与字号对比，不靠颜色
- 发丝线（hairline）负责静态分隔，阴影只属于浮层
- 导航与浮层是磨砂材质（`backdrop-filter: saturate(180%) blur(20px)`），正文实底
- 圆角刻度连续放大：10 / 14 / 18 / 24 / 胶囊
- 150–350ms 标准缓动，状态切换有生命但不喧宾夺主

## Colors

调色策略是**克制（Restrained）**：中性面全部由同一个冷灰族构成，唯一强调色是靛蓝（Apple systemIndigo 的 Web 落法），语义色只在状态出现时进场。以下除特别注明外均给出亮/暗两套值；`-dark` 后缀即暗色主题的规范值。

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

- **页面地面 Layout** (`bg-layout #F5F5F7`；暗色 `#0A0A0C`)：应用最底面。亮色取 Apple 标志性浅灰，暗色取近黑的真黑感。
- **面板 Container** (`bg-container #FFFFFF`；暗色 `#1C1C1E`)：卡片、聊台画布、表格等一切承载内容的实底面板。
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

## Typography

**Display/Body Font:** 系统字体栈 `-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Segoe UI", "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif`（macOS 即 SF Pro + 苹方，天然成立；Windows 回退 Segoe UI + 微软雅黑）
**Mono Font:** `ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace`（聊天回答里的代码块、MD5、模型参数）

**Character:** 不引入任何外部字体——Apple 质感的一半来自对系统字的绝对信任。层级只靠三件事：字重（400/500/600 三档，600 即"加粗"）、字号对比、留白；不靠颜色与装饰。中文与西文同栈混排，西文走 SF Pro/Segoe，中文走苹方/雅黑，基线自然协调。

### Hierarchy

- **Display** (600, 28px, 1.25)：页面大标题（知识库、使用监控）与空态大字。中文标题不做负字距；仅纯西文/数字的展示行可用 `-0.02em`。
- **Headline** (600, 17px, 1.4)：卡片标题、区块标题、弹窗标题。这是"一眼可见的层级主角"。
- **Title** (600, 15px, 1.45)：列表行主文字、会话列表条目标题。
- **Body** (400, 15px, 1.65)：正文、表格单元格、AI 回答。中文长文行高 1.65 起步，段距 ≥ 一个行高的一半。
- **Footnote** (400, 13px, 1.5)：辅助说明、表头、面包屑、引用预览正文。
- **Caption** (400, 12px, 1.4)：状态微标签（已连接/重连中）、角标、时间戳。
- **Mono** (400, 13px, 1.6)：代码、MD5、日志。

### Named Rules

**中文行高律。** 凡中文正文（含聊天回答、引用预览），行高 ≥1.6；UI 标签 1.4 起步。行高 1.5 以下的中文正文视为缺陷。

**数字等宽律。** 一切数据展示（配额、用量、表格数值、Token 计数）使用 `font-variant-numeric: tabular-nums`，滚动刷新时数字不跳动。

**负字距禁区。** `letter-spacing` 只允许出现在纯西文/数字的 Display 行（-0.02em）；中文任何场景字距为 0；全大写仅限英文缩写徽标（如 "RAG"），中文标签永不模拟大写（不用加宽字距冒充小标题）。

## Layout

4pt 网格（xs 4 / sm 8 / md 12 / lg 16 / xl 20 / 2xl 24 / 3xl 32 / 4xl 48），任何间距都是 4 的倍数。

- **壳层**：顶栏高 52px，磨砂材质悬浮于内容之上；侧栏宽 220px（折叠 64px）。顶栏与侧栏同页面之间用发丝线分隔，不用阴影。
- **页面节奏**：内容区左右留白 2xl（24px，移动端 lg 16px）；管理页卡片间距 2xl；卡片内边距 xl~2xl（20px 24px）；"空间大于标题"——标题上方留白 > 标题下方。
- **阅读列**：聊天阅读列与输入框同轨，最大 800px 居中；中文一行约 50 字，不追一行更长。
- **聊台画布**：聊天页整块是一张 24px 圆角的画布，四周距窗口 lg（16px），像一块放在灰桌面上的白玻璃板。
- **密度**：按钮/输入框高 36px（小 28 / 大 44），列表行 44px，表格行 48px，菜单项 36px。宁可留白，不挤一行。
- **响应式**：UnoCSS presetWind3 断点（sm 640 / md 768 / lg 1024 / xl 1280 / 2xl 1536）。≤640px：侧栏改为抽屉 + 遮罩（scrim `rgba(0,0,0,0.30)`），卡片内边距降为 lg，表格横向滚动，触控目标 ≥44px。

## Elevation & Depth

**发丝线优先，阴影只属于浮层。** 静态面板（卡片、表格、聊台画布）零阴影，靠 Container 与 Layout 的表面色差 + 一圈发丝线站住；阴影是"离开页面"的专属语言，只给浮层。暗色不是反转而是**真分层**：地面 #0A0A0C → 面板 #1C1C1E → 浮层 #2C2C2E，阴影在暗色下更重，但主要深度信号仍是层级差与发丝线。

### Material Vocabulary

- **导航材质 nav-material**：亮 `rgba(255,255,255,0.72)` / 暗 `rgba(29,29,31,0.68)`，配 `backdrop-filter: saturate(180%) blur(20px)`。用于顶栏、侧栏、聊天会话侧栏、移动端抽屉。
- **浮层材质 overlay-material**：亮 `rgba(255,255,255,0.76)` / 暗 `rgba(44,44,46,0.72)`，同款 filter。用于下拉菜单、命令面板、popover。
- **遮罩 scrim**：亮 `rgba(0,0,0,0.30)` / 暗 `rgba(0,0,0,0.50)`，模态背后的世界。
- **正文永远实底**：卡片、聊台、表格、回答气泡不用 backdrop-filter——文字下面的东西必须是不动的。

### Shadow Vocabulary

- **elevation-1**（`box-shadow: 0 1px 4px rgba(0,0,0,0.06), 0 4px 16px rgba(0,0,0,0.08)`；暗色 `0 1px 4px rgba(0,0,0,0.40), 0 4px 16px rgba(0,0,0,0.45)`）：popover、下拉菜单、tooltip。
- **elevation-2**（`0 4px 12px rgba(0,0,0,0.08), 0 16px 48px rgba(0,0,0,0.18)`；暗色 `0 4px 12px rgba(0,0,0,0.45), 0 16px 48px rgba(0,0,0,0.55)`）：模态、对话框、抽屉。
- **elevation-3**（`0 8px 24px rgba(0,0,0,0.10), 0 24px 80px rgba(0,0,0,0.24)`；暗色 `0 8px 24px rgba(0,0,0,0.50), 0 24px 80px rgba(0,0,0,0.65)`）：命令面板等最高层浮层。
- **发丝线阴影**（顶栏 `0 0.5px 0 rgba(29,29,31,0.12)`；侧栏 `inset -0.5px 0 0 rgba(29,29,31,0.12)`；暗色均换 `rgba(235,235,245,0.14)`）：壳层用 0.5px 投影伪造物理发丝线，不是"阴影"。

### Named Rules

**阴影只属浮层律。** 静态卡片出现 `box-shadow` 即为缺陷；唯一的例外是壳层发丝线投影。hover 不抬卡片——用 fill-hover 一层底色回应即可。

**暗色真分层律。** 暗色主题禁止把亮色值简单取反或整体调灰；必须使用本节的三层表面值。暗色下的强调色与语义色使用各自的 `-dark` 变体（切换经 theme store 的 `themeColors` 按 darkMode 返回，见 Token 落地映射 §1——Naive 与 CSS 变量同源换血）。

## Shapes

圆角是这个世界最重要的轮廓语言：**连续放大、只有五档**。

- **sm 10px**：一切控件——按钮、输入框、下拉框、菜单项、列表行、开关轨道。
- **md 14px**：输入框内嵌套件、分段控件、卡片内的小面板、图片缩略块。
- **lg 18px**：卡片、对话框、聊台输入壳、用户气泡。
- **xl 24px**：聊天画布、命令面板、大面积浮层与"画板"级容器。
- **capsule 9999px**：标签、状态胶囊、引用 chip、开关滑块、进度条。

按钮是 10px 圆角矩形（不是 iOS 胶囊——桌面企业工具要效率感）；标签与状态一律胶囊；用户消息气泡 18px，仅靠发信侧那一角收窄到 6px 形成方向感。控件高度与圆角成对出现：36px 高的控件配 10px 圆角，18px 圆角的面板内边距不小于 20px——圆角越大，呼吸越深。

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

- **Corner Style:** 18px（lg）；聊台画布与命令面板 24px（xl）。
- **Background:** `bg-container`，坐在 `bg-layout` 地面上。
- **Shadow Strategy:** 永久零阴影，见「阴影只属浮层律」。
- **Border:** 一圈发丝线（`hairline`）。
- **Internal Padding:** 20px 24px（xl 2xl）；卡片标题（headline 17px/600）与内容的间距 lg（16px）。

### Inputs / Fields

- **Style:** `bg-container` 实底 + 发丝线描边 + 10px 圆角，高 36px，文字 15px。聊天输入壳是放大版：18px 圆角、内边距 12px 14px、静置时带 elevation-1 级别的极轻投影+发丝线，聚焦时投影加深为浮层级。
- **Focus:** 发丝线变靛蓝 + `0 0 0 3px rgba(88,86,214,0.20)` 外圈光晕，150ms。
- **Error / Disabled:** 错误描边用 `error` 原值，错误说明文字用 `error-700/-400`；禁用整体 40% 不透明度。

### Navigation

- **Style:** 侧栏导航项 36px 高、10px 圆角、14px/500 次级文字色；选中 = `fill-subtle` 底 + 主文字色（不是靛蓝底！导航的靛蓝只给左侧 3px 指示条或图标）；hover = `fill-hover`。
- **顶栏:** 52px 磨砂材质，工具图标按钮 28px 高、圆角 8px、hover `fill-hover`。
- **Mobile:** 侧栏变抽屉（scrim 遮罩），滑入 350ms emphasized 曲线。

### Chat（signature surface）

- **用户气泡:** 靛蓝填充白字、18px 圆角、右上角 6px，右对齐；AI 回答无气泡——直接坐在画布实底上的正文排版（15px/1.65），与用户气泡形成"材料 vs 印刷"的对比。
- **引用标注（数据流不动）:** 回答句末的 `(来源#编号: 文件名 | 第X页)` 一律渲染为引用 chip 样式的行内链接：靛蓝 10% 底胶囊、12px/500 靛蓝文字、悬停提亮。"待核实"徽标用 `warning-700/-400` 文字。只许改样式层（linkClass / `.citation-*` CSS），DOM 结构、解析正则与 referenceMappings 数据流零改动。

### Tables

- 表头 13px/500 次级文字色、无背景色、下缘发丝线；行高 48px、行 hover `fill-subtle`、数值列 `tabular-nums`；表格圆角 10px；斑马纹禁用。

## Do's and Don'ts

### Do:

- **Do** 用发丝线（`hairline`，0.5–1px 的 9%–14% 墨色）做一切静态分隔；分隔的第一反应是线，不是影。
- **Do** 强调色只花在：主按钮、选中态、链接、聚焦环、引用 chip、发送键——每屏 ≤10%。
- **Do** 中文正文行高 ≥1.6，数据一律 `tabular-nums`，间距全部落在 4pt 网格上。
- **Do** 暗色使用三层表面（#0A0A0C / #1C1C1E / #2C2C2E）与各自的 `-dark` 强调/语义变体，与亮色同等打磨。
- **Do** 状态与浮层动效只用三档时长（150/250/350ms）与三条缓动曲线，`prefers-reduced-motion` 时全部降到接近 0。

### Don't:

- **Don't** 硬编码任何十六进制色值（包括 `bg-white`、`dark:bg-[#1c1c1c]` 这类任意值）绕过 token——所有颜色经 settings.ts、CSS 变量或 Uno token 流转。
- **Don't** 给静态卡片、表格、面板加阴影或 hover 上浮；阴影只属于浮层。
- **Don't** 使用第二强调色、渐变、霓虹、玻璃拟态渐层、彩色大色块——这个世界的色彩预算只有一线靛加语义色。
- **Don't** 新控件圆角小于 10px 或 invent 第六档圆角；中文正文与标题使用负字距。
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
| Naive 侧字体 | `getNaiveTheme`（`src/store/modules/theme/shared.ts` 242–259 行）`common` 增：`fontFamily`（同栈）、`fontFamilyMono`、`fontWeight: '400'`、`fontWeightStrong: '600'`、`lineHeight: '1.65'`、`fontSize: '14px'`、`fontSizeSmall: '13px'`、`heightMedium: '36px'`、`heightSmall: '28px'`、`heightLarge: '44px'`（键名已对照 naive-ui `ThemeCommonVars` 类型核实） |
| 层级（display/headline/…） | 全局样式经 `global.scss` 工具类/组件样式落（`.page-section` 卡片标题 18px→17px/600 等）；数字等宽律在 `global.scss` 对 `.n-data-table` 与数据组件加 `font-variant-numeric: tabular-nums` |

### 4. 圆角刻度

| Token | 落点 |
| --- | --- |
| `sm 10px`（Naive 基准） | `shared.ts` 248 行 `borderRadius: '6px'` → `'10px'`；同处增 `borderRadiusSmall: '6px'` |
| `capsule` | `shared.ts` 253–255 行 `Tag.borderRadius: '6px'` → `'9999px'` |
| `lg 18px` | `getNaiveTheme` 返回对象增 `Card: { borderRadius: '18px' }`、`Dialog: { borderRadius: '18px' }` |
| `xl 24px` | `views/chat/index.vue` 聊台画布 `rounded-xl` → `rounded-24px`；命令面板（global-search）同理 |
| 作用域样式 | `global.scss` `:root` 定义 `--app-radius-sm/md/lg/xl`（10/14/18/24px）；现 `.page-section.n-card` 的 `border-radius: 12px` 与 `--n-border-radius: 8px` 分别改 18px / 10px |
| Uno | `uno.config.ts` 26 行 `card-wrapper: 'rd-3 shadow-none'` → `'rounded-18px border border-solid border-hairline shadow-none'` |

### 5. 阴影与材质

| Token | 落点 |
| --- | --- |
| 壳层发丝线投影 | `settings.ts` `tokens.light.boxShadow`: `header: '0 0.5px 0 rgba(29, 29, 31, 0.12)'`、`sider: 'inset -0.5px 0 0 rgba(29, 29, 31, 0.12)'`、`tab: 'none'`；新增 `tokens.dark.boxShadow` 同形（色换 `rgba(235, 235, 245, 0.14)`）。经 vars.ts → `--header-box-shadow` / `--sider-box-shadow` / `--tab-box-shadow` 自动生效 |
| `elevation-1/2/3` | `getNaiveTheme` `common` 增 `boxShadow1/2/3`（对应 popover/modal/最高浮层，键名已核实）；作用域样式用 `global.scss` 的 `--app-shadow-e1/e2/e3` |
| `nav-material / overlay-material / scrim` | `global.scss` `:root`/`html.dark` 定义 `--app-material-nav`、`--app-material-overlay`、`--app-material-filter: saturate(180%) blur(20px)`、`--app-scrim`；消费点：`layouts/modules/global-header/index.vue`（顶栏毛玻璃）、`views/chat/modules/conversation-sidebar.vue`、`layouts/modules/global-search/`、`layouts/modules/theme-drawer/`、Naive popover（`global.scss` 对 `.n-popover` 覆盖 `background: var(--app-material-overlay); backdrop-filter: var(--app-material-filter)`） |
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
| 顶栏 52px | `settings.ts` `header.height: 56` → `52` |
| 侧栏 220/64 | `settings.ts` `sider.width: 180` → `220`（`collapsedWidth: 64` 不变） |
| 阅读列 800px | `views/chat/modules/input-box.vue` 442/465 行 `max-w-[960px]` → `max-w-[800px]`；聊天回答列同轨 |
| 页面节奏 | `global.scss` `.workspace-page { gap: 24px }` 保持；`.page-section.n-card` 内边距 `24px 28px` → `20px 24px`；移动端 640px 断点规则保持 |

### 8. 落地顺序建议

1. settings.ts（`recommendColor: false` + 颜色四键 + 语义色 + 阴影 + 布局尺寸）→ 全局立刻换血；
2. shared.ts `getNaiveTheme`（圆角/字体/高度/浮层阴影/缓动，及 §1 的 hover/pressed 派生改 600/700 阶）与 theme store `themeColors` 的暗色分支 → Naive 全组件生效；
3. global.scss（材质、fill、elevated、radius 变量、动效变量、reduced-motion、表格 tabular-nums）+ uno.config.ts（hairline/fill/elevated 别名、card-wrapper、glass-nav shortcut）；
4. reset.css 字体栈；
5. 壳层与旗舰页（header 毛玻璃、chat 画布 24px/800px 列、input 壳、引用 chip 样式、conversation-sidebar 去硬编码）。
