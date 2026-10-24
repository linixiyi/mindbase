---
name: MindBase
description: 企业级 RAG 知识库 · 晴空玻璃（The Azure Glass · 亮色 airy 淡蓝）——白底大留白的 Apple 质感工作台，默认亮色、一线淡蓝；Quiet Glass 暗色组完整保留为可选主题
colors:
  # ── 亮色组 = 默认主题（normative）；第四轮 Azure：强调色靛蓝→淡蓝，中性面染冷蓝 ──
  accent: "#0071E3"
  accent-hover: "#0058BD"
  accent-pressed: "#004196"
  bg-layout: "#F2F6FC"
  bg-container: "#FFFFFF"
  bg-elevated: "#FFFFFF"
  bg-inverted: "#1C2A3A"
  text-primary: "#1C2A3A"
  text-secondary: "#5E6B7E"
  text-tertiary: "#8493A6"
  hairline: "rgba(28, 42, 58, 0.09)"
  fill-subtle: "rgba(70, 110, 168, 0.09)"
  fill-hover: "rgba(70, 110, 168, 0.14)"
  material-nav: "rgba(255, 255, 255, 0.66)"
  material-overlay: "rgba(255, 255, 255, 0.70)"
  scrim: "rgba(0, 0, 0, 0.30)"
  success: "#34C759"
  warning: "#FF9500"
  error: "#FF3B30"
  # ── 暗色组 = 保留主题（非默认）；第四轮仅主色随淡蓝换血（#5B5BE8→#0071E3），其余自 Quiet Glass 起原样保留，任何一项不得改动 ──
  accent-dark: "#0071E3"
  accent-hover-dark: "#0058BD"
  accent-pressed-dark: "#004196"
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
    backgroundColor: "rgba(0, 113, 227, 0.10)"
    textColor: "{colors.accent}"
    typography: "500 12px/1"
    rounded: "{rounded.capsule}"
    padding: "0 8px"
    height: "20px"
---

# Design System: MindBase

<!-- 世界替换（departure）：按用户 brief 指定的「Apple 高级感」方向全新定义，替换而非精修旧观感；frontmatter token 为规范值（normative），实现落地后须按「Token 落地映射」逐项核对。第三轮（2026-10-20 亮色 airy 结构改）在其上做结构级修订：默认亮色、悬浮浮岛壳层、聊天去盒、排版与留白放大——见 Overview 与 Layout › 轮廓判定。第四轮（2026-10-20 Azure 淡蓝换血）按用户 brief「主题色是淡蓝色」把一线靛换血为一线蓝（#0071E3），亮色中性面染冷蓝，并补齐动效论纲与 delight 时刻——见 Overview 与 Motion & Delight。 -->

## Overview

**Creative North Star: "晴空玻璃（The Azure Glass）"——一间洒着均匀日光的安静工作室：磨砂玻璃做门与隔断，白色台面承载全部工作，只有一根天蓝细线标出"正在进行的事"。第三轮把窗帘完全拉开（默认亮色），并把家具从墙上搬下来（侧栏成为悬浮浮岛）；第四轮把那根靛蓝细线换成了晴天的一线蓝（#0071E3），连地面都透出一点晴空的冷——白天的工作室里，家具是浮岛，台面就是房间本身。**

MindBase 的界面是给企业员工每天提问、给管理员每天治理的工作台，也是给面试官审视的工程门面。它的气质来自三个词：**静**（近单色的冷调中性面，信息本体是唯一的色彩来源）、**磨砂**（导航与浮层用半透明 + backdrop-filter 的真实材质，正文永远坐在实底上）、**一线蓝**（全站唯一强调色是 apple.com 淡蓝 #0071E3，稀用、克制、出现即意味着"焦点与行动"）。

第三轮（2026-10-20 亮色 airy 结构改）是**结构级**修订，不是细节精修：默认主题定为 light（apple.com 气质——白底、大留白、透），侧栏脱离窗口边缘成为带投影的悬浮浮岛，聊天页拆除圆角盒子改为全幅 airy 画布 + 居中内容列，页面标题与正文字号、区块留白整体拉大。验收标准即「轮廓判定」：并排旧版与新版截图，第一眼必须可辨（见 Layout › 轮廓判定）。密度依然从容——控件高度不动，放大的是排版层与留白：airy 靠空间，不靠大控件。

第四轮（2026-10-20 Azure 淡蓝换血）是用户 brief「主题色是淡蓝色」的**色彩级**修订，经 impeccable 八指令（animate/bolder/colorize/delight/layout/overdrive/quieter/typeset）执行：一线靛换血为一线蓝（`#0071E3`，apple.com 官网按钮蓝——比 systemBlue `#007AFF` 深半档换取白字 4.70:1 的 AA 达标），亮色中性面整体染上冷蓝（地面、墨色、发丝线、细填充全部带蓝调），并补齐动效论纲（主题切换 View Transitions morph、呼吸状态、引用微交互）与两个 delight 时刻。第四轮**不是**结构改——第三轮的浮岛、去盒、排版刻度原样保留；也**不是**观感重置——「静」的底色还在，只是晴天来了。

本文件对旧观感的否决持续有效：SoybeanAdmin 模板默认（Vite 默认紫蓝 `#646cff` 混拼 Ant Design 语义色、6px 碎圆角、静态卡片薄投影、灰阶反转暗色、颜色硬编码绕过 token）不得以"兼容"名义回归；第三轮起，前两轮的旧轮廓（贴边通高侧栏、聊天圆角盒、28px 页题与 24px 区块距）同样不得回归；第四轮起，靛蓝 `#5856D6` 及其全部派生阶不得以任何形式残留。

**Key Characteristics:**

- **默认亮色**：白底、大留白、透、冷蓝地面；暗色（Quiet Glass 暗色组）完整保留为可选主题，除主色随淡蓝换血外全部暗色值原样不变
- **悬浮浮岛壳层**：侧栏与窗口边缘脱开（12px 缘距 + 24px 圆角 + 浮岛档投影），顶栏是唯一贴墙的家具
- **聊天无盒**：全幅白画布 + 800px 居中内容列；页面结构最多两层，卡片不嵌卡片
- 近单色冷调中性面 + 单一淡蓝强调色，强调色占任何一屏 ≤10%
- 系统字体栈（macOS 上即 SF Pro + 苹方），排版层放大（页题 32 / 区块题 20 / 正文 16）、控件层不动
- 发丝线（hairline）负责静态分隔；阴影只属于浮层与浮岛
- 150–350ms 标准缓动，状态切换有生命但不喧宾夺主

## Colors

调色策略是**克制（Restrained）**：中性面全部由同一个冷灰蓝族构成（第四轮起带 210° 蓝调），唯一强调色是淡蓝（apple.com 官网按钮蓝的 Web 落法），语义色只在状态出现时进场。**默认主题为 light**——frontmatter 中无后缀的亮色值即开箱值；`-dark` 后缀的暗色组自 Quiet Glass 起保留（非默认），第四轮仅主色随淡蓝换血，其余值不变，主题切换入口保留。

### Primary

- **晴空蓝 Azure Blue** (`#0071E3`；暗色 `accent-dark #0071E3`)：全站唯一强调色。用于：主按钮填充、选中态、链接、聚焦环、引用标注、发送按钮。白字对比 4.70:1（两主题同值），达标 AA；hover `#0058BD`（600 阶）、pressed `#004196`（700 阶）对比更高。作为**文字**出现在暗色容器上时对比仅 3.94:1，必须改用派生亮阶——暗色主题下 `--primary-400-color` = `#2692F0`（对暗色面板 5.24:1），或附加下划线。它是 apple.com 自己的购买按钮蓝：明快、干净、比靛蓝少一分紫调多一分晴天——用户扫过任何一屏，蓝色出现的面积不超过 10%。

### Semantic

- **信号绿 Confirm Green** (`#34C759`；暗色 `#30D158`)：连接正常、处理成功、校验通过。填充与圆点用原值；作为**文字**在浅色容器上用 `-700` 深阶（`#147a34`，5.44:1），暗色用由暗色基础色派生的 `-400`（`#57de74`，9.81:1）。
- **信号橙 Caution Orange** (`#FF9500`；暗色 `#FF9F0A`)：限流、重连中、配额将尽。文字用 `-700`（`#b35c00`，4.72:1）/ 暗色 `-400`（`#ffb433`，9.58:1）。
- **信号红 Alert Red** (`#FF3B30`；暗色 `#FF453A`)：删除、失败、引用待核实。文字用 `-700`（`#b31013`，7.00:1）/ 暗色 `-400`（`#ff7063`，6.28:1）。
- info 跟随主色（现有 `isInfoFollowPrimary: true` 保持）。

深阶说明：`-600`/`-700`/`-400` 等色阶由 `createThemePaletteColors` 从**当前主题的基础色**实时生成（`recommendColor: false` 时 500 阶 = 基础色原值），因此暗色主题下同名变量自动取暗色基础色的派生值——上面暗色括号内的 hex 即真实管线的产物，可直接用于 QA。

### Neutral

冷灰蓝一族（第四轮起色相统一偏向 210°，与淡蓝同族——中性面本身就透着晴空的冷）：

- **页面地面 Layout** (`bg-layout #F2F6FC`；暗色 `#0A0A0C`)：应用最底面。亮色取带蓝调的晴空浅灰（比 Apple 中性浅灰 `#F5F5F7` 更冷半档），暗色取近黑的真黑感。亮色下它是浮岛与窗口之间 12px 呼吸缝的 color。
- **面板 Container** (`bg-container #FFFFFF`；暗色 `#1C1C1E`)：卡片、表格等一切承载内容的实底面板；聊天页它同时是全幅画布本身。
- **浮层面 Elevated** (`bg-elevated #FFFFFF`；暗色 `#2C2C2E`)：弹窗、下拉、命令面板等浮层内容底。暗色下靠它与 Container 的层级差表达深度。
- **主文字 Text Primary** (`text-primary #1C2A3A`；暗色 `#F5F5F7`)：正文与标题墨色（冷墨）。对白底对比 14.9:1。
- **次级文字 Text Secondary** (`text-secondary #5E6B7E`；暗色 `rgba(235,235,245,0.60)`)：说明、占位符、表头。对比 5.41:1。
- **弱文字 Text Tertiary** (`text-tertiary #8493A6`；暗色 `rgba(235,235,245,0.40)`)：时间戳、禁用提示。
- **发丝线 Hairline** (`hairline rgba(28,42,58,0.09)`；暗色 `rgba(235,235,245,0.14)`)：一切静态分隔——卡片描边、分隔线、表格线。
- **细填充 Fill Subtle** (`fill-subtle rgba(70,110,168,0.09)`；暗色 `rgba(120,120,128,0.24)`)：次级按钮底、列表行选中/hover 底、标签底——蓝调水洗灰。hover 加深一档（`fill-hover` 0.14 / 0.32）。
- **反色面 Inverted** (`bg-inverted #1C2A3A`；暗色 `#F5F5F7`)：tooltip、深色菜单等反色场景。

### Named Rules

**色彩主权律。** `settings.ts` 的 `recommendColor` 必须为 `false`。开启推荐调色时，CSS 变量（`--primary-color` 等）被吸附到推荐族的 500 阶——`#0071E3` 会被吸到推荐族的别处，而 Naive 的 `primaryColor` 仍取原值，屏幕上同时出现两种蓝。关闭后调色板 500 阶 = 原值，CSS 变量、Naive、Uno 三方同源；本文件全部色阶与对比度数值按此路径给出（AntD 算法实测：500 `#0071E3` / 600 `#0058BD` / 700 `#004196` / 400 `#2692F0` / 50 `#E6F7FF`）。

**一线蓝律。** 强调色在一屏内的着色面积 ≤10%。它只标记焦点、选中与主行动；凡是"装饰性变蓝"的地方一律降为冷调中性灰。蓝色的稀少正是它的高级——蓝感由中性面的冷调供给，不靠多刷强调色。

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

- **导航材质 nav-material**：亮 `rgba(255,255,255,0.66)` / 暗 `rgba(29,29,31,0.68)`。用于顶栏、两座浮岛侧栏（主导航、会话列表）、移动端抽屉。
- **浮层材质 overlay-material**：亮 `rgba(255,255,255,0.70)` / 暗 `rgba(44,44,46,0.72)`。用于下拉菜单、命令面板、popover。（R7 colorize：亮色两值自 0.72/0.76 各提通透一档，暗色原样。）
- **折射 filter 分层三档**（R12 overdrive）：玻璃折射按浮层高度分三档，**层级越高的浮层越透**——顶栏（bar 档，最沉稳的常驻面）：亮 `saturate(180%) blur(20px) brightness(1.02)` / 暗 `saturate(160%) blur(18px)`；浮岛与 chip（island 档）：亮 `saturate(190%) blur(24px) brightness(1.03)` / 暗 `saturate(170%) blur(21px)`；命令面板与 popover（overlay 档）：亮 `saturate(200%) blur(28px) brightness(1.04)` / 暗 `saturate(180%) blur(24px)`——R8 的通透档整档保留在梯顶。暗色三档均**无 brightness**（暗玻璃不发光，Quiet Glass 保真）。token 单源 `--app-glass-filter-bar / island / overlay`；材质 alpha 双值声明模式不变（nav 0.66 / overlay 0.70，R7 值原样）。
- **遮罩 scrim**：亮 `rgba(0,0,0,0.30)` / 暗 `rgba(0,0,0,0.50)`，模态背后的世界。
- **正文永远实底**：卡片、表格、回答排版不用 backdrop-filter——文字下面的东西必须是不动的。浮岛是壳层不是正文，可用材质。

### Shadow Vocabulary

- **elevation-island 浮岛档**（亮 `0 1px 2px rgba(0,0,0,0.05), 0 16px 48px rgba(0,0,0,0.08)`；暗 `0 1px 2px rgba(0,0,0,0.45), 0 16px 48px rgba(0,0,0,0.50)`）：悬浮浮岛专属。比 popover 的 e1 更宽更软——岛是常驻结构，投影要像日光下的软影，不是悬浮提示的锐影。R8 bolder 把双层比例调为**更宽更淡的 ambient**（12/32 → 16/48 日光池，亮度各降一档）+ **保持紧深的 contact**（1px/2px 贴身）——岛读作有质量的物体晒在日光里，而非悬停提示。同一岛内禁止叠第二层投影。岛自身不再用 inset 发丝线（旧 `tokens.*.boxShadow.sider` 的 inset 值退役——岛已脱离边缘，无需伪造边界）。投影挂在 aside 本体：容器的 overflow-hidden 只裁内容、不裁自身投影；壳层框架 aside 内建的 shadow-2xl 由 sider-class 覆盖为本档。
- **elevation-1**（`box-shadow: 0 1px 4px rgba(0,0,0,0.06), 0 4px 16px rgba(0,0,0,0.08)`；暗色 `0 1px 4px rgba(0,0,0,0.40), 0 4px 16px rgba(0,0,0,0.45)`）：popover、下拉菜单、tooltip、聊天输入壳静置态。
- **elevation-2**（`0 4px 12px rgba(0,0,0,0.08), 0 16px 48px rgba(0,0,0,0.18)`；暗色 `0 4px 12px rgba(0,0,0,0.45), 0 16px 48px rgba(0,0,0,0.55)`）：模态、对话框、抽屉。
- **elevation-3**（`0 8px 24px rgba(0,0,0,0.10), 0 24px 80px rgba(0,0,0,0.24)`；暗色 `0 8px 24px rgba(0,0,0,0.50), 0 24px 80px rgba(0,0,0,0.65)`）：命令面板等最高层浮层。
- **顶栏滚动渐显发丝线**（R12 overdrive）：顶栏底缘 0.5px 线从 shadow 管线退役，成为滚动联动的真实元素——页面静止在顶部时透明（玻璃栏无线悬浮），内容滚动到顶栏线下被裁切时淡入（250ms standard）。线色 token `--app-header-hairline`（亮 `rgba(28,42,58,0.12)` / 暗 `rgba(235,235,245,0.14)`，即 R3 shadow token 的原值）；rAF 轻量监听布局滚动元素（content 滚动模式 `#__SCROLL_EL_ID__`）及与顶栏线齐平的嵌套滚动容器（chat 画布消息列）；reduced-motion 与无 JS 降级为常显（见 §18）。`tokens.*.boxShadow.header` 退役为 `none`。（旧侧栏 inset 发丝线已随浮岛化退役。）

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
- **capsule 9999px**：标签、状态胶囊、引用 chip、开关滑块、进度条、悬浮微控件（R5 §12——浮在画布上的 icon 悬浮钮为圆形胶囊；主行动按钮仍是 10px 圆角矩形，红线不变）。

大圆角属于"浮在空间里的东西"（浮岛、命令面板），不属于"放在地上的东西"——内容面板最高 18px。按钮是 10px 圆角矩形（不是 iOS 胶囊——桌面企业工具要效率感）；标签与状态一律胶囊；用户消息气泡 18px，仅靠发信侧那一角收窄到 6px 形成方向感。控件高度与圆角成对出现：36px 高的控件配 10px 圆角，18px 圆角的面板内边距不小于 28px——圆角越大，呼吸越深。聊天画布不是容器：没有圆角，也没有描边。

## Motion & Delight（第四轮 Azure）

**动效论纲（animate thesis）**：动效只做三件事——**反馈**（操作被收到了）、**状态**（系统正在做什么）、**连续性**（东西从哪来、到哪去）。Operate 模式不排队看开场表演：一切常规切换 ≤250ms，唯一被授权"表演"的时刻是主题切换。

- **焦点时刻（overdrive 授权位）——主题切换 morph**：点击主题切换按钮时，用 View Transitions API（`document.startViewTransition`）从按钮位置做圆形揭示（`clip-path: circle()` 从点击点扩至全屏，350ms emphasized）。暗→亮是"拉开窗帘晒到太阳"，亮→暗是"拉上窗帘"。API 不存在或 `prefers-reduced-motion` 时静默降级为既有 150ms 颜色过渡。只绑主题切换这一处根级入口，不给路由、弹窗、列表套用。
- **呼吸状态（状态反馈）**：流式回答等待/生成中，AI 侧状态点以 1.6s 轻微透明度脉动"呼吸"（1 → 0.4 → 1）；它表达的是真实的连接/生成状态，连接常驻时静止不动。呼吸循环在文档隐藏时暂停（`animation-play-state` 不需要专门处理，但禁止任何脱离状态的常驻循环）。
- **微反馈（150ms 档）**：按钮按压 scale 0.97、输入聚焦光晕、列表行 `fill-hover` 底色、引用 chip 悬停提亮 + 下划线偏移——全部已由 token 化缓动承载，本轮只补齐遗漏点，不加新机制。
- **连续性（既有）**：路由 fade-slide 250ms emphasized、抽屉滑入 350ms、浮层 elevation 过渡 150ms——维持不动。

**Delight 时刻（delight thesis）**：从产品机制派生，第二个百分次使用依然成立。

- **上传成功勾绘**：知识库上传成功的状态图标用 SVG stroke 描绘动画（300ms emphasized，`stroke-dashoffset` 一次性播放）——"文件被可靠接住了"的确认感，对应 Kafka 可靠投递的产品承诺。
- **回答就绪回落**：流式回答完成的一瞬，聊天输入壳从聚焦投影轻轻回落到静置投影（150ms）——一轮问答的收束感。不加音效、不加彩纸、不延迟任何真实状态。

**Named Rules：**

**morph 边界律。** View Transitions 只属于主题切换根入口；其他任何界面变化靠既有 token 动效表达。`startViewTransition` 必须存在性探测，回调内只做状态变更；不支持的浏览器与 reduced-motion 用户走原路径，功能零损失。

**呼吸律。** 任何循环动效必须绑定真实状态（连接中/生成中/处理中），状态结束循环停止；禁止装饰性常驻动画。

### Brand Mark（第四轮）

MindBase 的品牌标志是「**心智基座**」：一支圆头描边的 **M**（知识基座）+ 中谷上方一枚悬浮圆点（心智火花）——"mind over the base"。几何只有一个概念，没有任何渐变与第二色。

- **Mono 版**（`src/assets/svg-icon/logo.svg`，viewBox 48）：M 起点 (10,34) 上行至 (10,16)，经圆角折峰落到中谷 (24,25) 再对称右上，终点 (38,34)；`stroke-width 4.2`、`round cap/join`；火花 `circle(cx 24, cy 8, r 2.9)`。**填充/描边一律 `currentColor`**——侧栏 Logo 吃冷墨、登录 hero 吃淡蓝（组件处已有 `text-primary`/`rgb(var(--primary-color))`），永不写死色值。
- **Favicon 版**（`public/favicon.svg`）：Azure 瓷砖（`rect 48×48 rx 11 fill #0071E3`）+ 白色同形标志（整体下移 3.5 光学居中）。旧 Vite 紫 `#646cff` 标志就此退役，任何场合不得回归。
- **禁区**：不给标志加渐变、投影、描边外框、双色；不改几何比例；最小可用尺寸 16px（favicon 瓷砖版）/ 24px（mono 版）。

## Components

### Buttons

- **Shape:** 10px 圆角（sm），高度 36px（small 28 / large 44），左右内边距 16px，字重 600、14px。
- **Primary:** 淡蓝填充白字（`accent` 上白字 4.70:1，达标 AA）。hover 取基础色深一阶（600 阶 `#0058BD`，两主题同派生），pressed 再深一阶（700 阶 `#004196`），按压同时整体缩放 0.97。
- **Secondary:** 细填充蓝灰底（`fill-subtle`）+ 主文字色；hover 加深一档（`fill-hover`）。
- **Ghost:** 无底透明，淡蓝文字；用于行内次级行动。
- **Hover / Focus:** hover 是颜色/底色的 150ms 过渡；`focus-visible` 一律 2px 淡蓝描边 + 2px 偏移（或 `0 0 0 3px rgba(0, 113, 227, 0.28)` 外圈），永不移除焦点样式。

### Chips / Tags

- **Style:** 胶囊（9999px），`fill-subtle` 底 + 次级文字色，高 22px，内边距 0 10px，12px/500。
- **State:** 选中 = 淡蓝 10% 透明底（亮）/ 16%（暗）+ 淡蓝文字；组织标签权限类标签用语义色深阶文字。

### Cards / Containers

- **Corner Style:** 18px（lg）——内容面板的最高圆角；24px（xl）只归浮岛与命令面板。
- **Background:** `bg-container`，坐在 `bg-layout` 地面上。
- **Shadow Strategy:** 永久零阴影，见「阴影只属浮层律」。
- **Border:** 一圈发丝线（`hairline`）。
- **Internal Padding:** 28px 32px（第三轮放大，旧 20 24）；卡片标题（headline 20px/600）与内容的间距 xl（20px）。卡片与卡片的间距 3xl（32px）。

### Inputs / Fields

- **Style:** `bg-container` 实底 + 发丝线描边 + 10px 圆角，高 36px，文字 15px。聊天输入壳是放大版：18px 圆角、内边距 12px 14px、文字 16px、静置时带 elevation-1 级别的极轻投影+发丝线，聚焦时投影加深为浮层级。
- **Focus:** 发丝线变淡蓝 + `0 0 0 3px rgba(0, 113, 227, 0.20)` 外圈光晕，150ms。
- **Error / Disabled:** 错误描边用 `error` 原值，错误说明文字用 `error-700/-400`；禁用整体 40% 不透明度。

### Navigation

- **浮岛侧栏（主导航）:** nav-material 磨砂浮岛——缘距 12px、圆角 24px、elevation-island 投影、岛内无边框。导航项 36px 高、10px 圆角、14px/500 次级文字色；选中 = `fill-subtle` 底 + 主文字色（不是淡蓝底！导航的淡蓝只给左侧 3px 指示条或图标）；hover = `fill-hover`。
- **顶栏:** 52px 通幅贴顶磨砂材质 + 底缘发丝线投影；工具图标按钮 28px 高、圆角 8px、hover `fill-hover`。
- **会话侧栏（chat 第二浮岛）:** 同浮岛规格（12px 缘距 / 24px 圆角 / nav-material / elevation-island）；与主浮岛之间露出 12px 地面缝。
- **Mobile:** ≤640px 浮岛收回为抽屉 + scrim 遮罩，滑入 350ms emphasized 曲线。

### Chat（signature surface）

- **画布:** 全幅白（`bg-container` 直铺内容区），无圆角、无描边、无边距——画布即房间，消息流直接坐在白底上；消息条目间距 32px，正文 16px/1.75。
- **用户气泡:** 淡蓝填充白字、18px 圆角、右上角 6px、内边距 12px 16px、16px/1.75，右对齐；AI 回答无气泡——直接坐在白画布上的正文排版（16px/1.75），与用户气泡形成"材料 vs 印刷"的对比。
- **引用标注（数据流不动）:** 回答句末的 `(来源#编号: 文件名 | 第X页)` 一律渲染为引用 chip 样式的行内链接：淡蓝 10% 底胶囊、12px/500 淡蓝文字、悬停提亮。"待核实"徽标用 `warning-700/-400` 文字。只许改样式层（linkClass / `.citation-*` CSS），DOM 结构、解析正则与 referenceMappings 数据流零改动。

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
| `accent`（亮） | `src/theme/settings.ts` `themeColor: '#0071E3'`（第四轮 Azure；原 `'#646cff'`，第三轮 `'#5856D6'`），**且 `recommendColor: true` → `false`**（色彩主权律）。原理：`createThemePaletteColors`（shared.ts 85–100 行）把 CSS 变量锚在 `getColorPalette(color, recommendColor).get(500)`——推荐路径下基础色的 500 阶会被吸到推荐族别处，而 Naive `primaryColor` 取原值，两套蓝同屏；关闭后 500 阶 = 原值，三方同源。主题抽屉的推荐色开关（theme-drawer/modules/theme-color.vue 41–42 行）保留但默认关闭 |
| `accent-hover` / `accent-pressed`（两主题） | `getNaiveThemeColors` 的场景派生（shared.ts 213–219 行）：Hover 处理器 `getPaletteColorByNumber(color, 600, false)`；Pressed `(color, 700, false)`。`recommendColor: false` 后 600/700 阶即规范值（两主题同 `#0058BD`/`#004196`），随主题基础色切换自动换值，四个颜色统一生效 |
| `success / warning / error`（亮） | `settings.ts` `otherColor: { info: '#0071E3', success: '#34C759', warning: '#FF9500', error: '#FF3B30' }`（success/warning/error 保留 Apple 系统色不变；info 跟随主色） |
| `accent-dark` 与语义色（暗）——唯一路线 | `src/store/modules/theme/index.ts` 的 `themeColors` computed（现 40–48 行）读取本 store 的 `darkMode`，暗色时返回 `{ primary: '#0071E3', success: '#30D158', warning: '#FF9F0A', error: '#FF453A' }`（第四轮仅 primary 随淡蓝换血）。下游双通道自动生效：`naiveTheme` computed（index.ts 51 行）→ Naive 全组件；`watch(themeColors)` → `setupThemeVarsToGlobal`（index.ts 139–146、190–198 行）→ CSS 变量与 Uno 色阶。这是唯一同时喂 Naive（App.vue 44–47 行经 `:theme-overrides` 注入 JS 对象，不读全局 CSS 变量）与 CSS 变量的单点 |
| 禁用路线 | **禁止**用 `global.scss` 的 `html.dark` 覆盖 `--primary-color` 等三元组：`addThemeVarsToGlobal` 运行时同时写 `:root` 与 `html.dark` 两块（shared.ts 147–157 行，后者同样声明这批变量），而 global.scss 经 `vite.config.ts` 23–26 行 `additionalData` 编译进每个组件——两者同特异性，胜负取决于样式注入顺序，连 CSS 变量层都不稳，更到不了 Naive |
| 深阶（`-700`/`-400` 等） | 无需新文件：`createThemePaletteColors` 已从**当前主题基础色**生成 50–950 全阶 CSS 变量（`--success-700-color` 等，vars.ts 3–13 行）。`recommendColor: false` 后，本文档 Colors 节列出的全部色阶 hex 与对比度即真实管线产物；暗色主题下同名变量自动切换为暗色基础色的派生值 |

### 2. 表面色与文字色

| Token | 落点 |
| --- | --- |
| `bg-container` | `settings.ts` `tokens.light.colors.container: 'rgb(255, 255, 255)'`（不变）；`tokens.dark.colors.container: 'rgb(28, 28, 30)'` |
| `bg-layout` | `tokens.light.colors.layout: 'rgb(242, 246, 252)'`（第四轮 Azure 染冷蓝，原 `rgb(245, 245, 247)`）；`tokens.dark.colors.layout: 'rgb(10, 10, 12)'`（不变） |
| `bg-inverted` | `tokens.light.colors.inverted: 'rgb(28, 42, 58)'`（第四轮冷墨，原 `rgb(29, 29, 31)`）；`tokens.dark.colors.inverted: 'rgb(245, 245, 247)'` |
| `text-primary` | `tokens.light.colors['base-text']: 'rgb(28, 42, 58)'`（第四轮冷墨）；`tokens.dark.colors['base-text']: 'rgb(245, 245, 247)'` |
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
| 壳层发丝线投影 | ~~`settings.ts` `tokens.light.boxShadow.header`~~（R12 §18 起 0.5px 发丝线离开 shadow 管线：`tokens.*.boxShadow.header` 退役为 `'none'`，线由 global-header 的滚动渐显元素承载、色走 `--app-header-hairline`）。`tab: 'none'` 不变；`tokens.*.boxShadow.sider` 仍载浮岛投影，见 §9 |
| `elevation-island` | `settings.ts` `tokens.light.boxShadow.sider: '0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.10)'`；`tokens.dark.boxShadow.sider: '0 1px 2px rgba(0, 0, 0, 0.45), 0 12px 32px rgba(0, 0, 0, 0.55)'`（原 inset 值替换）。消费点：`base-layout/index.vue` sider-class 的 `shadow-sider!` 挂 aside **本体**（aside 自身 of-hidden 不裁自身投影；内层 `global-sider/index.vue` 34 行的 `shadow-sider` 类移除防双影，见 §9）；`global.scss` 另定义 `--app-shadow-island` 同值供作用域样式用 |
| `elevation-1/2/3` | `getNaiveTheme` `common` 增 `boxShadow1/2/3`（对应 popover/modal/最高浮层，键名已核实）；作用域样式用 `global.scss` 的 `--app-shadow-e1/e2/e3` |
| `nav-material / overlay-material / scrim` | `global.scss` `:root`/`html.dark` 定义 `--app-material-nav`、`--app-material-overlay`、`--app-scrim` 与三档折射 token `--app-glass-filter-bar / island / overlay`（R12 §18，替代原单档 `--app-material-filter`——后者保留为 island 档别名，供 uno `glass-nav` shortcut）；消费点：`layouts/modules/global-header/index.vue`（顶栏 bar 档）、两座浮岛侧栏与 chip（island 档）、`layouts/modules/global-search/` 与 Naive popover（overlay 档）、`layouts/modules/theme-drawer/`（抽屉实底不走 filter） |
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

### 10. Azure 淡蓝换血（第四轮，2026-10-20）

以下落点在本次编写时对照源码核实。AntD 算法实测色阶：500 `#0071E3` / 600 `#0058BD` / 700 `#004196` / 400 `#2692F0` / 50 `#E6F7FF`。

| Token | 落点 |
| --- | --- |
| 主色淡蓝（亮） | `src/theme/settings.ts` `themeColor: '#0071E3'`（原 `'#5856D6'`）；`otherColor.info: '#0071E3'`（跟随主色）。`recommendColor: false` 已就位，600/700 阶自动派生 hover/pressed |
| 主色淡蓝（暗） | `src/store/modules/theme/index.ts` `themeColors` 暗色分支 `primary: '#0071E3'`（原 `'#5B5BE8'`）。暗色下作为文字出现的场景必须用派生 `--primary-400-color`（`#2692F0`，5.24:1）或下划线——组件代码中"链接/文字用主色"处按此核对 |
| 亮色中性面染冷蓝 | `settings.ts` `tokens.light.colors.layout: 'rgb(242, 246, 252)'`、`tokens.light.colors['base-text']: 'rgb(28, 42, 58)'`、`tokens.light.colors.inverted: 'rgb(28, 42, 58)'`（原 `rgb(245,245,247)` / `rgb(29,29,31)` / `rgb(29,29,31)`）。发丝线经 `rgb(var(--base-text-color) / 0.09)` 自动变冷；`text-secondary/tertiary` 若走 CSS 变量则同步 `#5E6B7E`/`#8493A6`（global.scss / uno.config 对应别名） |
| 细填充蓝洗 | `global.scss` `:root` `--app-fill-subtle: rgba(70, 110, 168, 0.09)`、`--app-fill-hover: rgba(70, 110, 168, 0.14)`（原 `rgba(120,120,128,0.08/0.12)`）；暗色两值不动 |
| 主题切换 morph | 主题切换入口（`layouts/modules/global-header/` 内的暗色切换按钮组件）将状态变更包进 `document.startViewTransition`（存在性探测），配合 `::view-transition-old(root)/new(root)` 与从按钮圆心扩散的 `clip-path: circle()` 动画（350ms `--app-ease-emphasized`）；`@media (prefers-reduced-motion: reduce)` 与无 API 环境直接调用原切换函数。唯一授权位，不加第二处 |
| 呼吸状态点 | `views/chat/` 生成中状态指示（现有 loading 指示元素）加 1.6s 透明度脉动 keyframes（`--app-dur` 不适用，独立 `@keyframes azure-breathe`）；仅绑定"生成中/等待中"状态类，完成即静止 |
| 上传成功勾绘 | `views/knowledge-base/` 上传成功状态图标：内联 SVG path `stroke-dasharray/dashoffset` 300ms 描绘动画（一次性，`forwards`）；失败/处理中态不加动画 |
| 回答就绪回落 | `views/chat/modules/input-box.vue` 输入壳投影随流式状态切换（聚焦档 → 静置档 150ms 过渡）——已有投影机制上补状态联动，不新增阴影档 |
| 靛蓝残留清零 | 全仓 grep `5856D6\|5B5BE8\|878af5\|靛蓝` 于 `frontend/src` 零命中；引用 chip、选中态、聚焦环全部随 CSS 变量自动换血，无需逐处硬编码排查 |
| 验收 | ①grep 残留零命中；②实页主按钮/选中态/引用 chip/聚焦环全为淡蓝；③主题切换有圆形揭示且 reduced-motion 降级；④暗色抽查主色变蓝、其余 token 不动；⑤`pnpm typecheck && pnpm exec eslint .` 0 error |

### 11. autorun 美化链新词汇（R1 表面/页头 · R2 欢迎区/面板，2026-10-20）

R1/R2（autorun 链，计划 `.spec/plans/ui-beautification.md`）在 Azure 世界内新增的设计词汇，规范值与落点：

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 浏览器表面（craft floor） | `::selection`：主色 22% 底 + 冷墨文字（暗色 `--primary-400-color` 30% 底）；`input/textarea/[contenteditable]` 插入符 `caret-color` 主色。浏览器自有表面必须穿品牌，不得留 UA 默认 | `global.scss`（surface 段） |
| `.page-header` 页头区 | 页题（Display 32）与该页唯一主行动同行 `baseline` 对齐、`space-between`、可换行。适用于主行动为独立按钮的页面（先行：recharge-manage、org-tag）；嵌在 TableHeaderOperation 的行动不强行上提 | `global.scss` + 两视图 |
| 表格空态排版 | 默认 Naive 空态（无自定义 `.table-state` 时）上 28px/下 36px 留白，描述文字 `--app-text-tertiary`；自定义空态块不受影响 | `global.scss`（`:has` 作用域） |
| 聊天欢迎区（delight） | 空会话画布 = azure 8% 圆角徽标（icon 主色；R8 升为玻璃徽标，见 §12）+「开始新对话」+ **产品事实文案**（基于知识库检索、句末引用标注）+「试试这样问」建议问法胶囊（点击经 `chatStore.input` 填充输入框，不自动发送）。入场为一次性 stagger（250ms emphasized，徽标→题→述→chips 各 +60ms），reduced-motion 全局压扁兜底 | `chat-list.vue` |
| 命令面板入场（overdrive） | 全局搜索桌面面板（`top-50px` 变体）首帧 `scale(0.985) translateY(6px)`，150ms emphasized 收拢（`@starting-style`，CSS-only）；只动 transform，不与 NModal 渐显叠加；不支持的浏览器保持 NModal 原渐显 | `global.scss` |
| 档案头身份 tile | personal-center 头像 tile：主色 8% 底 + 主色 icon（原灰底灰 icon 退役）。身份区吃品牌一线 | `personal-center/index.vue` |
| 验收 | detect 全量零 error；欢迎区建议填充真机实测；选区/插入符样式表断言 + 放大截图 | R1/R2 runs.jsonl |

### 12. 液体玻璃词汇（R5 layout 工序 · 用户 brief「苹果高级感 + 液体玻璃」，2026-10-24）

液体玻璃语言在 Azure 世界内落成的材质规范：玻璃分档、边缘高光、工程边界。R3 壳层框架（顶栏 52px 通幅、浮岛 12px 缘距 / 24px 圆角）零改动——本节只在既有轮廓内补材质词汇。（R12 overdrive 起，本节的**对称 rim 对**升级为 §18 的各向异性 rim 环（token 自 `--app-rim-light/under` 更名 `--app-rim-top/side/bottom`）、**单档折射 filter** 升级为 §18 的三档折射梯；玻璃四分档与工程边界全部沿用不变。）

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 玻璃分档 | 玻璃本体分三档：**浮岛玻璃**（两座侧栏，nav-material，24px）、**浮层玻璃**（popover / 命令面板，overlay-material，14–24px）、**chip 玻璃**（悬浮微控件，nav-material，胶囊）。第四类为**实底浮层**：模态与抽屉——姿态浮岛化（软影 + rim light + 浮岛几何）但表面永不透明化，正文永远实底 | global.scss + 各消费组件 |
| 悬浮微控件胶囊 chip | chat 画布两枚悬浮钮（会话列表开关、日期筛选）统一为 **36px 圆形胶囊玻璃 chip**：nav-material + backdrop-filter（含 `-webkit-` 前缀）+ e1 软影 + rim light（R9 quieter：发丝线描边移除，玻璃体边缘只声明一次——见 §15）；缘距 12px（`left-3`/`right-3`/`top-3`，与浮岛律同值）、z-20；同尺寸、同材质、同定位节奏，只以 icon 与左右位置区分。胶囊是悬浮微控件的合法圆角——主行动按钮仍为 10px 圆角矩形（按钮形态红线不破） | `.app-glass-chip`（global.scss）+ `views/chat/index.vue`、`views/chat/modules/chat-list.vue` |
| 边缘高光 rim light | 玻璃本体顶部 1px 内亮边（镜面描边），与软影同一条 `box-shadow` 声明叠放，不另起图层：亮 `inset 0 1px 0 rgba(255,255,255,0.82)`；暗 `inset 0 1px 0 rgba(235,245,255,0.10)`（暗色 rim 只调亮度档，不改任何表面值；R9 quieter 自 0.14 降一档防白边刺眼） | `--app-rim-light`（global.scss `:root`/`html.dark`） |
| **R8：底缘微光 rim-under** | R5 的顶缘受光补齐为**边缘高光对**——光线同样掠过玻璃底缘：亮 `inset 0 -1px 0 rgba(255,255,255,0.30)`；暗 `inset 0 -1px 0 rgba(235,245,255,0.06)`（弱于顶缘，光从上来；R9 自 0.08 降档）。与 rim-light 同链叠放，token 单源 | `--app-rim-under`（global.scss `:root`/`html.dark`） |
| **R8：玻璃本体边缘光全族铺开** | R5 只给 chip 与抽屉 rim；R8 起边缘高光对是**一切玻璃本体的出厂属性**：顶栏（发丝线 token 之后串接 rim 对，inline 材质声明承载）、主浮岛（落在画材质的内层容器——aside 的 inset 会被半透明子层压住读成泥，投影仍在 aside）、会话浮岛（岛影 + rim 对同链）、命令面板（e3 + rim 对，`.top-50px` 规则三类特异性盖过工具类；移动端全幅变体保持平）、popover（e1 + rim 对）、chip、抽屉。静态卡片/表格仍零阴影坐地面——放宽条款只授权玻璃本体 | `global-header/index.vue`、`global-sider/index.vue`、`conversation-sidebar.vue`、`global.scss`（palette/popover/chip/drawer） |
| **R8：折射 filter 分主题提通透** | `--app-material-filter` 亮 `saturate(200%) blur(28px) brightness(1.04)` / 暗 `saturate(180%) blur(24px)`（暗不 brightness——暗玻璃不发光）。此前亮暗共用 180%/20px 单值 | `global.scss` 材质段（见 Material Vocabulary） |
| **R8：浮岛环境影比例** | elevation-island ambient 层更宽更淡（`0 12px 32px` → `0 16px 48px`，亮 0.10→0.08 / 暗 0.55→0.50），contact 保持紧深——日光池更宽、贴身更清楚。settings.ts 与 global.scss 双声明处同步（既有的镜像模式） | `theme/settings.ts` tokens.*.boxShadow.sider + `--app-shadow-island` |
| **R8：欢迎徽标玻璃化** | chat 空会话徽标从 8% 平面 tile 升为 **azure 玻璃徽标**：淡蓝染 10%（暗 16%，对齐选中态胶囊双档）+ rim 对 + e1 软影（R9 quieter：发丝线描边移除——边缘只声明一次，徽标的形由淡蓝染界 + 受光 + 软影给出）；**不加 backdrop-filter**——空态画布是纯白无内容可折射，玻璃性由透明染+边缘+受光表达，少一层合成 | `views/chat/modules/chat-list.vue` |
| **复审（R8 重跑）：浮层玻璃族真实渲染修复** | R8 的四项主体（rim 全族、filter 梯、island 环境影、欢迎徽标）经逐文件读码+grep 实证已在工作树就位，但**浮层玻璃族（popover 一支）从未真实渲染**，三处结构缺陷：①naive 在每个 popover body 上以**行内样式**写入 `--n-color/--n-border-radius/--n-box-shadow`（PopoverBody cssVars，本应用未开 inline-theme-disabled），普通样式表声明在层叠中败给行内——overlay 材质、14px 圆角、e1 软影全部阵亡，body 实为**不透明 naive 白**（不透明底上的 backdrop-filter 是空转，rim 环是白上白）；②选择器 `.n-popover` 漏掉半族——dropdown 菜单走 popover 的 internalRenderBody 分支，body 只带 `n-popover-shared` 一个类（无 `n-popover`），顶栏头像菜单等 NDropdown 全部在族外；③tooltip 反被 `.n-popover` 意外命中——tooltip 是 inverted 反色面（Colors › Neutral › bg-inverted），非玻璃本体，却戴着玻璃 rim 环。修复（global.scss 单点）：族选择器改 **`.n-popover-shared:not(.n-popover--raw):not(.n-tooltip)`**（naive 实际着色且全族共有的类；cssr 自带 position:relative，环锚定成立），三个 `--n-*` 覆写加 `!important` 压行内值；tooltip 就此出族回归 inverted 本体。命令面板同病同修：`.top-50px` 规则钉 `--n-color: overlay !important`（NCard 行内 `--n-color` 与组件行内覆写合并顺序脆弱）+ `border-radius: var(--app-radius-xl)`（`rounded-24px` 工具类在单类平手中败给运行时挂载的 naive cssr 圆角，Shapes「xl 归命令面板」的 24px 此前并不成立）。零新 token、零新词汇，只让已记录的世界为真 | `global.scss`（.n-popover-shared 规则 + .n-modal.n-card.top-50px） |
| 抽屉浮岛姿态 | 主题抽屉从通栏贴边改为浮岛姿态：距视口上/右/下各 12px、四角 24px（xl）、elevation-2 软影 + rim light、`overflow: hidden` 裁圆角、`max-width: calc(100vw - 24px)` 兜底窄屏；表面保持 Naive 面板实底色（本工序只定结构不定色）——抽屉浮在遮罩变暗的页面上，磨砂会读成灰泥，故以姿态而非 blur 入族 | `.n-drawer.theme-drawer`（global.scss）+ `layouts/modules/theme-drawer/index.vue` |
| 暗色浮层软影补齐 | e1/e2/e3 此前只有亮值（暗色下浮层投影过弱），按 Shadow Vocabulary 规范值补暗档：e1 `0 1px 4px rgba(0,0,0,.40), 0 4px 16px rgba(0,0,0,.45)`；e2 `0 4px 12px rgba(0,0,0,.45), 0 16px 48px rgba(0,0,0,.55)`；e3 `0 8px 24px rgba(0,0,0,.50), 0 24px 80px rgba(0,0,0,.65)`。全部暗色表面 token（除主色）原样不动 | global.scss `html.dark` |
| 工程边界 | ①同一视线最多一层 backdrop-filter：chip 只浮在实底画布上，不与浮岛/浮层重叠；②一切 `backdrop-filter` 必须带 `-webkit-` 前缀（Safari），新落点在 `.app-glass-chip`/material helpers 单源供给；③玻璃本体上的正文对比必须 AA——抽屉/模态内容区一律实底；④chip 无新增循环动效，reduced-motion 沿用全局压扁条款；⑤静态卡片零阴影红线不因玻璃放宽条款破防——软影与 rim light 只归浮层与玻璃本体；⑥玻璃体边缘只声明一次（R9，见 §15）：rim 对与描边二选一，玻璃一律留 rim 收描边，静态面反之（hairline + 零阴影） | global.scss（注释即文档） |
| 验收 | detect 全量零 error（绝对路径跑）；typecheck / eslint 双绿；真机验收（chip 对称性、抽屉浮岛姿态、双浏览器 backdrop-filter）待后端可用时按 §11 轮廓判定补——本轮后端 8081 未运行，仅登录页可达 | 本轮 runs.jsonl |

### 13. 图标体系统一与空态两档律（R6 typeset 工序，2026-10-24）

图标是第二种字形：R6 把它收成单一体系，并清空文字层的档外值。全部落点以本轮实测为准。

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 图标体系统一律 | 全站图标唯一家族 = **lucide**（unplugin-icons + 本地 `@iconify/json`，零网络依赖）。SoybeanAdmin 模板遗留五套杂牌图标（mdi / ant-design / ic-round / uil / gridicons，实测 32 处 · 12 文件）全部换为 lucide 同义图标——同义约定：一切刷新/重试 = `rotate-cw`（承接既有 9 处惯例）、确认回车 = `corner-down-left`、新窗口 = `external-link`、关闭 = `x`、放大缩小 = `zoom-in/out`、全屏进出 = `maximize/minimize`。本地 SvgIcon 集只留给文件类型图标与品牌资产（logo / favicon / expectation）。杂牌集合就此全仓退役，任何形式不得回归。**动态字符串追杀**（复审补刀）：模板标签换装清不到「SvgIcon `:icon` 动态字符串」里的三处漏网——`pin-toggler.vue` 的 `mdi-pin/-off` → `lucide:pin/pin-off`、`menu-toggler.vue` 箭头档的 `ph-caret-double-left/right-bold`（Phosphor 第六套）→ `lucide:chevrons-left/right`、`lang-switch.vue` 的 `heroicons:language`（Heroicons 第七套）→ `lucide:languages`；严格分隔符 grep（`mdi[-:]` / `ant-design[-:]` / `ic-round[-:]` / `uil[-:]` / `ph-` / `heroicons` / `gridicons`，剔除自动生成的 components.d.ts 残影）于 src 零命中 | 表格操作条 `table-header-operation`、列设置 `table-column-setting`、顶栏全屏/重载钮、`file-preview`、`pdf-document-viewer`、命令面板 global-search 全链、knowledge-base 检索入口；复审补刀：`pin-toggler` / `menu-toggler` / `lang-switch` |
| 图标字号刻度 | 图标字号只落五档：`text-icon-xs` 14 / `text-icon-small` 16 / `text-icon` 18 / `text-icon-large` 24 / `text-icon-xl` 32（uno.config.ts `fontSize`）；图标组件上禁用任意 px 值。键帽例外：命令面板页脚的 ESC 是**文字键帽**（12px/500——全大写英文缩写徽标合法），其余三键（确认/上下切换）为 lucide 字形 18px，同材质 inset 键帽底 | 全仓图标（chat 模块、layout 壳层、file-preview、global-search；20 处任意值 14/15/16/18/20/24/28/34/36px 已收敛） |
| 空态标题两档律 | 空态标题只落两档：**页面级空态**（页面主内容区整体为空、无卡片容器——chat-history）用 Display 32/600/1.2 + 48px 图标；**区块级空态**（卡片内容区与表格空槽）用 Headline 20/600/1.35 + 40px 图标。空态描述一律 Footnote 14/1.55。28px（旧 Display）与 17px（旧 Headline）就此从空态退役 | history-state 保持 32；page-state（personal-center / recharge / recharge-manage）28→20；table-state（user / org-tag / invite-code）、usage-state、provider-state 17→20；八处 `__desc` 13→14/1.55 |
| Footnote 13→14 清零 | 阅读层 13px（旧 Footnote）全仓清零为 14px——面包屑、表单标签、套餐卡内文、引用预览正文与文本预览、订单号/金额行、聊天作者名等 25 处 · 13 文件。表头 13px 是控件层（Naive `fontSizeSmall`），不在此列（刻度分层律）。区块题 15/17px（usage-monitor limit-title、personal-center h2、model-provider h2、recharge package-name）统一 Headline 20/1.35；look-forward 标题 28（旧 Display）→ 32/600 | 各视图 scoped 样式 |
| 数据大数字例外 | 展示型数据大数字（recharge package-price、personal-center quota-value、usage-monitor summary-value：28px/600 + tabular-nums）是数据展示而非文字层级，28 作为 hero-metric 尺寸保留，不参与排版刻度 | recharge / personal-center / usage-monitor |
| 验收 | grep 杂牌集合（mdi/ant-design/ic-round/uil/gridicons，复审加 ph-/heroicons 严格分隔符模式）零命中；图标任意字号零命中；state/区块题 17/28 零命中、阅读层 13px 零命中；`pnpm typecheck` 0 错、eslint 改动文件 0 错（21 warning 均改前遗留：no-console / unused-prop / R2 注释格式）、detect --json（绝对路径）`[]`。真机验收待后端可用时按 §11 轮廓判定补——本轮后端 8081 未运行，仅登录页可达 | 本轮 runs.jsonl |

### 14. 颜色主权收口与例外页插画家族（R7 colorize 工序，2026-10-24）

颜色是这个世界的主权问题：一切出现在屏幕上的颜色要么来自 token 管线，要么不存在。R7 把最后四股绕过管线的颜色收口，并为例外页立一族新插画。

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 否决色清零 | 启动屏回退色（无 localStorage 时的 `themeColor` 缺省）从旧 Vite 紫 `#646cff` 换为 `#0071E3`——全仓 `src`+`public` grep 证实这是否决紫的最后残留；启动屏四角 pulse 圆点与 logo 随 `--primary-color` 注入自动换血 | `plugins/loading.ts` |
| 文件类型图标 token 化 | 五个文件类型 tile（doc/docx/pdf/txt/dflt）的硬编码色全部改走 CSS 变量（经 `style` 属性——SVG 展示属性不解析 `var()`，内联样式经 `<use>` 阴影树继承 `:root` 变量，双管线 sprite/unplugin 均生效）：doc/docx `rgb(var(--primary-color))`（原 #0072FF，与主题蓝近而不等——主权问题）、pdf `rgb(var(--error-color))`、txt `rgb(var(--warning-color))`（语义色族供色，hue 不变、值归管线）；dflt tile `rgb(var(--base-text-color) / 0.62)` 冷调中性墨 + 标签 `rgb(var(--container-bg-color))`（亮色白字/暗色深字，两主题标签对比同时成立）。tile 内白字标签（DOC/PDF/TXT）保留字面白——与 button-primary 白字、favicon 白标同源的「彩底上的白」 | `assets/svg-icon/{doc,docx,pdf,txt,dflt}.svg`；消费点 file-preview、knowledge-base 表格 |
| 例外页插画家族 | 403/404/500/网络错误四帧插画从 Freepik 人物场景（Material 灰 #263238/#37474f/#455a64 + 暖红 #f28f8f/#b16668，33–75KB/帧）整体重绘为**几何线稿家族**：500 视框、22px 圆头描边（与品牌标 M 同笔性）、hero 一律 `currentColor`（exception-base 以 `text-400px text-primary` 包裹，插画自动吃一线蓝）、辅以 `rgb(var(--primary-color) / 0.07–0.10)` 玻璃水洗与 `rgb(var(--base-text-color) / α)` 中性墨，零硬编码 hex，明暗两主题经变量自动成立（暗色非反转）。四帧各一个概念：403 = 保险库锁、404 = 纸页与放大镜（纸角从 10% 淡蓝镜片水洗后折出——家族的液体玻璃时刻）、500 = 停摆齿轮 + 感叹、网络 = 信号弧与一道安静斜杠。裁决依据：craft floor 禁「SVG 拟真画」，几何线稿是一等公民；Material 灰暖红与 Azure 冷蓝世界断裂 | `assets/svg-icon/{no-permission,not-found,service-error,network-error}.svg`；`exception-base.vue`（DOM 零改动） |
| 死资产处置 | `empty-data.svg`（78KB，同族 Material 灰插画）经 grep 证实全仓零消费方，删除——sprite 只按存在文件注册 symbol，无消费即零影响 | 已删除 |
| **复审：死资产二批** | R7 第二遍复审以精确消费方 grep（`local-icon=` / `localIcon` / `local:` 前缀 / 组件 import 四路）追杀出三枚同族 Freepik/Material 灰死资产与一个死组件：`banner.svg`（19KB，#263238×9 + 暖红 #7f3e3b，零消费）、`avatar.svg`（6.4KB 肤色插画，零消费——global-header/personal-center 的 "avatar" 命中均为 UserAvatar 组件名与 CSS 类名）、`expectation.svg`（72KB，#263238×137 + #455a64×16 + 暖肤色的 Freepik 人物场景）与其唯一消费方 `look-forward.vue`（敬请期待组件，全仓零消费，i18n 键 `common.lookForward` 按文案不动条款保留）。四者删除，sprite 依 `import.meta.glob` 按存在文件注册（utils/icon.ts），零副作用；本地 SvgIcon 集自 §13 所列（文件类型 / logo / favicon / expectation）缩为文件类型 / logo / favicon / no-icon 回退 | 删除 `assets/svg-icon/{banner,avatar,expectation}.svg` + `components/custom/look-forward.vue`；`typings/components.d.ts` 同步摘除 LookForward 条目 |
| **复审：no-icon 回退重绘** | `no-icon.svg` 是 svg-icon.vue 的活回退路径（无 icon/localIcon 时渲染），原资产是 8KB 品红渐变杂色插画（#EC008C/#FF1F50/#AB1F51 等 16 hex + 径向渐变 + feGaussianBlur 滤镜）——若渲染即向 Azure 世界吐杂色。重绘为 lucide `image-off` 原版几何（24 视框、stroke-width 2、圆头描边、fill none），**currentColor 单色**，随消费方文字色两主题自动成立；「缺失图标」语义与 image-off 同构，且与 §13 图标家族律同族 | `assets/svg-icon/no-icon.svg` |
| **复审：inverted 文字 token 收口** | `dark-mode-container.vue` 的 `text-#1f1f1f` 任意值 hex（模板遗留）是 src 代码层最后一处绕过管线的字面色——且在亮色 inverted 侧栏（主题抽屉活设置）上呈深字压深面。收口为 token `--app-inverted-text`：亮 `#F5F5F7` / 暗 `#1C2A3A`，即**对面主题的 bg-inverted 值**（零新色，纯既有 token 重组），两主题配对均 ≈12:1 AA | `global.scss`（token 双主题）+ `components/common/dark-mode-container.vue` |
| **复审：file-preview 图标兜底** | `getFileIcon` 的 supportedIcons 原含 md/jpg/jpeg/png/gif，但 sprite 从未注册这些 symbol——引用即空白 `<use>`。收敛为存在的四资产（pdf/doc/docx/txt），其余扩展名统一落 dflt tile 兜底（dflt 本就是「非已知类型」的语义位） | `components/custom/file-preview.vue` |
| 材质通透一档 | 亮色玻璃材质各提通透一档：nav-material 0.72→0.66、overlay-material 0.76→0.70（液体玻璃 brief 的折射感；浮岛与浮层都坐在应用自有近白表面上，次级文字对比仍 ≥AA）。暗色两值原样保留（Quiet Glass 保真条款），`:root`/`html.dark` 双值声明模式不变 | `global.scss` 材质段 + DESIGN.md frontmatter/Material Vocabulary |
| 验收 | ①grep `646cff\|5856D6\|5B5BE8\|878af5` 于 `frontend/src`+`public` 零命中；②grep `#0072FF\|#FF3E4C\|#FF9908\|#5B5B5B\|#263238\|#37474f\|#455a64\|#f28f8f\|#b16668\|#EC008C\|#1f1f1f` 于 src+svg-icon 资产目录零命中（logo/favicon 的 Azure 白与文件类型 tile 的彩底白字除外）；③`pnpm typecheck` 0 错、eslint 改动文件 0 错、detect --json（绝对路径）`[]`；④真机验收（启动屏、文件列表 tile、404 页）待后端可用时补 | 本轮 runs.jsonl |

### 15. 收侧裁决（R9 quieter 工序，2026-10-24）

液体玻璃是「一放一收」的语言：bolder 放出的光，quieter 负责收住边界，防止玻璃词汇污染静态世界。本节是 R9 的四条裁决与两条巡检记录，全部以本轮实测为准。

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 玻璃边缘只声明一次 | craft floor「elevation 只声明一次」在玻璃体上的裁决：**rim 对与描边二选一，玻璃一律留 rim 收描边**。R5/R8 给玻璃体补 rim 时未清掉既有 1px 发丝线，顶缘同一条边上亮白镜面线压深色发丝线 = ghost ring。四处收口：popover（删自加 border）、chip（删 border，形由软影给出——与浮岛「nav-material 叠白底≈纯白、岛形由投影勾出」同律）、命令面板（`border: none` 压掉 NCard 默认 bordered 描边环）、欢迎徽标（删 border，形由淡蓝染界 + 受光 + 软影给出）。静态面反之：hairline 描边 + 零阴影，边缘也只声明一次 | `global.scss`（.n-popover / .app-glass-chip / .n-modal.n-card.top-50px）+ `chat-list.vue`（徽标） |
| 暗色 rim 降档 | 暗色 rim light 0.14→0.10、rim-under 0.08→0.06：顶栏与抽屉沿视口全长的 1px 亮缝在暗色里读成白边刺眼——specular 留住、glare 收走。**只调亮度档**：三层表面 #0A0A0C/#1C1C1E/#2C2C2E 与全部暗色 token（除主色）原样未动 | `global.scss` `html.dark` |
| 空态图标 ghost 档 | 空态图标透明度全站归一为**唯一 ghost 档** `--app-icon-ghost`（`rgb(var(--base-text-color) / 0.24)`）：9 个视图的 `__icon` 规则从字面值改走 token（值不变），会话侧栏空态图标 0.3 档就地退役归入 0.24。时间戳的档外 0.45 透明度（会话条目时间、聊天消息时间两处）归位到 spec 原有的 Text Tertiary 角色 token（`#8493A6`，对白底 3.13:1，优于原 0.45 混合的 2.62:1）——DESIGN.md › Neutral「弱文字＝时间戳」自始如此 | `global.scss`（token）+ 9 视图 + `conversation-sidebar.vue`、`chat-message.vue` |
| 实底浮层对比裁决 | 抽屉与 NDialog **不走玻璃底**（R5 裁决维持：抽屉浮在遮罩变暗的页面上，磨砂读成灰泥）——正文永远实底，实测对比：亮 #1C2A3A/白 14.57:1、暗 #F5F5F7/#2C2C2E 12.80:1，均远超 4.5:1，无需退路。玻璃本体（popover/命令面板）只承载短标签与控件文字，不动正文 | 本节记录（无代码改动） |
| 静态面巡检记录 | 全部 11 视图 + 共享组件 box-shadow 巡检（grep `shadow-sm\|shadow-md\|…\|hover:shadow` + 字面 box-shadow）：静态卡片/表格零阴影坐地面**未破防**——仅有的字面投影是授权位（会话浮岛、聊天输入壳第三轮 whisper 档、顶栏/浮岛 token 影）；hover 零抬卡（无 hover:translate/scale/shadow）；recharge 套餐磁贴静态 `box-shadow: none`、hover 仅底色、选中 1px 主色环是状态指示（与 focus ring 同族，非 elevation）。**巡检收杂一处**：pdf 页码导航的引用目标标记原为硬编码 AntD 蓝 `rgba(24,144,255,.75)`（#1890FF，绕过 token 管线；同文件的 highlight rect 在第四轮已完成同款迁移、此处漏网）——改走 `rgb(var(--primary-color) / 0.75)`，强度不变、主权归位 | 巡检证据（grep 输出）；修复 `pdf-document-viewer.vue` |
| **复审（R9 quieter 重跑）** | 五条裁决经全仓新鲜 grep + 读码逐项实证已在工作树就位（R12 §18 已把 rim 对升级为各向异性环，暗色降档值由环 token 承接）：①四玻璃体零描边（popover 族规则与 chip 无 border、命令面板 `border: none`、欢迎徽标无 border）；②静态面巡检重跑——views/components/layouts 全量字面 box-shadow 仅剩授权位（两座浮岛影、输入壳 whisper、欢迎徽标 e1、KB 文档预览模态 e3、recharge 状态环、pdf 引用标记/高亮、命令面板键帽 inset），工具类影子仅主浮岛 `shadow-sider!` 一处，hover 零抬卡（translate/scale/shadow 三模式零命中）；③暗色保真——settings.ts 暗色四键（container/layout/inverted/base-text）与岛影逐一对照本文件暗色组原值相符，零漂移；④抽屉与 NDialog 无任何玻璃底覆写（实底裁决维持）；⑤ghost 档 token 全站唯一消费者 = 会话侧栏空态图标，时间戳两处均 `--app-text-tertiary`，动效时长字面值仅剩两处 DESIGN.md 明文授权例外（呼吸 1.6s / 勾绘 300ms）。**追杀出一处漏网**：register 表单提示文字的 `0.66` 字面墨透明度（全站墨色透明度字面值普查 views/components/layouts 仅存两处中的杂档——其余全走 token）收口为唯一次级文字档 `--app-text-secondary`（亮 #5E6B7E / 暗 0.60 白墨，token 双主题自动成立）；另一处 `0.08` 是「待核实」引用 chip 的暗色可见度校准（引用硬区的样式层、亮暗双档各自成立），保留并记录于此 | `views/_builtin/login/modules/register.vue`（.register-form-tip） |
| 验收 | ①grep `border` 于四玻璃体规则 = 零命中、`.top-50px` 含 `border: none`；②grep `base-text-color) / 0.24\|/0.3\]\|/ 0.45` 于 src 零命中（全部走 token）；③`pnpm typecheck` 0 错、eslint 改动文件 0 错、detect --json（绝对路径）`[]`；④真机（popover 无双边、chip/徽标单边缘、暗色 rim 不刺眼）待后端可用时补 | 本轮 runs.jsonl |

### 16. 微反馈落地（R10 animate 工序，2026-10-24）

动效论纲的三件事——反馈、状态、连续性——里「反馈」档的三个承诺项（§Motion › 微反馈）本轮全部兑付。不加新机制、不动既有动效（主题切换 morph、呼吸点、命令面板入场、路由 fade-slide、会话列表 stagger 原样），morph 边界律不破。

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 按钮按压律落地 | 「按钮按压 scale 0.97」自第四轮承诺、全仓 grep 证实零落地，本轮一次兑付给全家族：NButton 全组件经全局规则 `scale(0.97)` @ `--app-dur-fast`/`--app-ease-standard`，`:active` 触发（键盘空格/回车按压同样到达）；disabled/loading 态除外（Naive 原生 transition 不受扰）。**工程要点**：Naive cssr 在运行时挂载（晚于构建样式），规则必须以复合特异性取胜（仓内既有 popover/drawer 覆写同款手法）——Naive 自身的 color/background-color/opacity/border-color 过渡**逐字保留**并追加 transform 一项，`var(--n-bezier, …)` 经 Button.mjs 行内 cssVars 解析、shared.ts 已将其锚到 `--app-ease-standard`。胶囊玻璃 chip（.app-glass-chip 两枚）与会话侧栏分段控件 tab（原生 button，吃不到 .n-button 规则）同律补齐 | `global.scss`（.n-button / .app-glass-chip）+ `conversation-sidebar.vue`（.chat-sidebar__tab） |
| 引用 chip 悬停下划线偏移 | 「悬停提亮 + 下划线偏移」兑付：下划线**几何常驻**（1px @ `text-underline-offset: 3px`，与 --suspicious 虚线变体同刻度），静止时 `text-decoration: underline transparent`、hover 时 `text-decoration-color: currentColor` 淡入（150ms token，`text-decoration-color` 可平滑过渡）——颜色取 currentColor 随两主题文字色自动成立（亮 primary / 暗 primary-400），零硬编码、零暗色覆写、零布局位移。`:active` 深底既有。样式层唯一入口，DOM 结构/解析正则/referenceMappings 数据流零改动（AGENTS.md 硬约束，实测未触碰 script 段） | `chat-message.vue`（.source-file-link 样式块） |
| 玻璃 hover 受光 | 玻璃浮层（chip 悬浮微控件）的 rim light 对指针报到有响应：hover 时顶缘镜面提亮一档，token `--app-rim-light-hover` 单源——亮 0.82→0.95（近镜面白），暗 0.10→0.16（**只调亮度档**，暗 rim 保真条款不破）。150ms token 档、绑 hover 状态、非循环（呼吸律：禁装饰性常驻动画）；阴影两侧均为三条声明，box-shadow 列表可平滑插值。浮岛/popover/命令面板等非交互玻璃体**不接** hover 受光——hover 回应只归控件（与「hover 不抬卡片」同律） | `global.scss`（token + .app-glass-chip:hover） |
| reduced-motion 裁决 | 按压缩放是空间位移：`prefers-reduced-motion: reduce` 下全部 `transform: none`（NButton/chip/tab 三处），按压的颜色与底色反馈保留——反馈语义仍在、动作让位（fewer and gentler, not none）；下划线淡入与 rim 受光是颜色变化，非空间位移，随全局压扁条款自然降为瞬时 | `global.scss` reduce 块 + `conversation-sidebar.vue` 作用域内 |
| **复审（R10 animate 重跑）** | 三项承诺经全仓新鲜 grep+读码逐项实证已在工作树就位：①按压律（global.scss `.n-button`/`.app-glass-chip` + `.chat-sidebar__tab` 三处 + reduce 块 transform:none）；②引用 chip 下划线偏移（`.source-file-link` 几何常驻 1px@3px、hover 淡入 currentColor、150ms token；唯一生产者 chat-message.vue linkClass，样式层唯一入口，script 段零触碰）；③rim hover 受光（@property 注册 `--app-rim-top` + `--app-rim-top-hover` 亮 0.95/暗 0.16，`.app-glass-chip:hover` 内插 150ms，R12 更名后的形态）。既有动效五处（morph/呼吸/@starting-style/fade-slide/stagger）逐项实证完好。**追杀出一处漏网**：欢迎区建议问法胶囊（`chat-welcome__chip`，§11「建议问法胶囊」——胶囊家族原生 button，吃不到全局 .n-button 规则）有 hover 与入场，但缺 `:active` 按压与 `:focus-visible`——补同一记 scale 0.97（150ms token）+ 与 .app-glass-chip 同规格 focus 环 + 作用域内 reduce 兜底（镜像 .chat-sidebar__tab 三件套）。选项类按钮（org-tag-option/provider-option/page-nav-item）经核 hover+focus 态完整，属选择行家族（反馈=hover fill），不接按压律 | `views/chat/modules/chat-list.vue`（.chat-welcome__chip） |
| 验收 | ①grep `scale(0.97)` 于 src = 三处按压律 + 命令面板/路由既有 scale 互不干扰；②`pnpm typecheck` 0 错、eslint 三文件 0 error、detect --json（绝对路径）`[]`；③登录页（唯一可达页）真机按压主按钮验证 0.97 收缩与 150ms 回弹；④chip/引用 chip/分段 tab 真机手感待后端可用时按 §11 轮廓判定补。复审加验：⑤grep `scale(0.97)` = 四处（+chat-welcome__chip）；⑥welcome chip/引用 chip 真机待后端补 | 本轮 runs.jsonl |

### 17. 确认时刻与空态品牌徽标（R11 delight 工序，2026-10-24）

Delight 论纲的第一个承诺（§10 上传成功勾绘，全仓 grep dasharray/dashoffset 零落地）本轮兑付，并立起空态的品牌律：确认时刻从产品机制派生——Kafka 可靠投递写成一笔勾绘；空态从「灰色的缺席」变成「淡蓝的邀请」。（§10 的第二个 delight 承诺「回答就绪回落」仍未落地：input-box 现只有 focus-within 投影联动、无流式完成态绑定，归后续动效工序。）

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 上传成功勾绘 | §10 承诺兑付：状态列「已完成」NTag 的 icon 槽内置 14px（icon-xs 档）authored check——SVG path 实长 ≈13.5，`stroke-dasharray/dashoffset: 14` 起笔前整段藏进 dash 空隙，300ms（§10 明文 delight 档，动效三档之外的授权时刻）emphasized 一次性 `forwards` 从起点绘到笔尾。stroke 吃 `currentColor`，随 NTag success 语义文字色两主题自动成立，零硬编码。播放时机 = 元素挂载一次：表格行有 row-key，重渲染/轮询/WebSocket 推送不打断；NProgress → Completed 翻转时元素新挂，恰好就是确认时刻；失败（上传中断）/处理中（NProgress）态不加动画。reduced-motion 走全局压扁条款瞬时绘完——确认语义在，动作让位 | `views/knowledge-base/index.vue`（renderStatus + `.kb-status-check`） |
| 空态品牌徽标 | 空态裸灰 ghost 图标全站退役（9 视图 · table-state / page-state / history-state / usage-state / provider-state 家族）：图标长进 azure 圆角徽标 tile——chat-welcome 徽标（§12）同律、为静态世界压平。规格单源 `.app-state-badge`：azure wash 亮 8% / 暗 16%（暗档与选中态胶囊、欢迎徽标同层的「近黑上可见淡蓝」值）；glyph 亮 primary / 暗 `--primary-400`（Colors › Primary 的暗色抬阶律）。两档尺寸镜像空态两档律（§13）：区块级默认 64×64 + radius lg 18 + icon-large 24；页面级 `--page` 80×80 + radius xl 24 + icon-xl 32（欢迎徽标同几何）。零阴影零 rim——tile 坐在卡片/表格内的地面上，静态面零阴影红线（§15）不破 | `global.scss`（`.app-state-badge` 家族）+ 10 视图（9 空态视图 + knowledge-base） |
| error 变体语义裁决 | 错误空态保留语义红、共享 tile 几何：`--error` 变体 wash 亮 8% / 暗 16%，glyph 亮 `--error-700` / 暗 `--error-400`（语义深阶律）——tile 给形状，色相给含义；delight 不得抹掉错误的语义信号 | `.app-state-badge--error`（global.scss）+ 各视图 error 空态 |
| knowledge-base 空态补齐 | 旗舰页表格原是全站最后一处默认 NEmpty（灰盒图标）：补 `#empty` 槽与兄弟表格视图（user/org-tag/invite-code）同律——azure 徽标 + 教学文案「上传文档后，解析与向量化状态会汇总到这里」（与表格实际上报的两列状态一致的事实文案）；上传入口在表头 TableHeaderOperation，不在空态重复放按钮 | `views/knowledge-base/index.vue` |
| 边界 | ①徽标 tile 不加 backdrop-filter / rim / 投影——空态坐在实底地面上，玻璃词汇只归浮层与悬浮件；②chat 会话侧栏的紧凑空态（`.chat-sidebar__empty-icon`）保持 ghost 0.24——窄浮岛里 64px tile 过重，`--app-icon-ghost` token 因它保留单源；③一线蓝律：8% wash 是稀疏点缀，每空态一枚，不刷表格整列图标；④勾绘无循环（呼吸律），一次 forwards 后静止 | 本节记录 |
| **复审（R11 delight 重跑）** | 两项主体经全仓新鲜 grep+读码逐项实证已在工作树就位：①勾绘——`renderStatus`「已完成」NTag icon 槽的 authored check（路径实长 √18+√85≈13.46，dash 14 起笔前整段藏笔，300ms emphasized 一次性 forwards；Break/处理中态无动画）；②徽标——`.app-state-badge` 单源（区块档 64/radius-lg/icon-large、页面档 `--page` 80/radius-xl/icon-xl、`--error` 语义红变体、wash 亮 8%/暗 16%、glyph 亮 primary/暗 primary-400）被 10 视图采用，`state__icon` 裸灰图标零残留，会话侧栏空态依边界②保持全站唯一 ghost 档消费者；禁区 favicon.svg/logo.svg 零触碰。**追杀出「全站最后一处默认 NEmpty」陈述失实**——命令面板、KB 检索对话框、usage-monitor ×4（限流配置/告警/LLM 与 Embedding 排行）共 6 处仍是默认灰盒插画，且命令面板 `resultOptions` 初始为空、每次打开未输入即闪灰盒。三处修复：①命令面板空态 `show-icon=false`（Naive 官方纯文字空态配置）——旗舰玻璃面只留一行安静文字，i18n 文案不动；②KB 检索对话框（旗舰检索入口、实底 NModal 身体）空态升为 azure 徽标 tile + `file-search` glyph（本地 @iconify/json 验证存在），教学文案 `emptyDescription` 逐字保留，家族属性齐（14/1.55 次级墨 `--app-muted` + 一次性 250ms standard 入场，与 table-state 全员同律）；③usage-monitor 四处紧凑数据槽同收 `show-icon=false`——窄列里 64px tile 过重（边界②同律的静态版：徽标只归显著空态，紧凑槽只留文字）。**空态三档律就此定形**：显著空态（页面/区块/对话框）= azure 徽标 tile；紧凑数据槽 = 仅描述文字；引用预览等边缘面 = 保留默认（reference-preview-page 的 NEmpty 是引用硬区邻接文件，边缘收益不敌风险纪律，记录为已知残留） | `global-search/components/search-modal.vue`、`knowledge-base/modules/search-dialog.vue`、`usage-monitor/index.vue` |
| 验收 | grep `state__icon` 于 src = 零命中（会话侧栏 empty-icon 不属此族）；`app-state-badge` 于 10 视图采用；`pnpm typecheck` 0 错、eslint 改动文件 0 错、detect --json（绝对路径）`[]`；勾绘动画与 tile 的真机验收（后端 8081 未运行，仅登录页可达）待后端可用时按 §11 轮廓判定补。复审加验：默认灰盒 NEmpty（无 show-icon 覆写）于 src 仅剩 reference-preview-page 一处已知残留；门禁三项复跑全绿 | 本轮 runs.jsonl |

### 18. 光学深水区（R12 overdrive 工序，2026-10-24）

液体玻璃的光学三件套一次落齐：边缘高光有了**方向**（各向异性 rim 环）、折射有了**高度**（三档 filter 梯）、顶栏发丝线有了**状态**（滚动渐显）。全部变量化、全部可降级，静态面与 morph 边界零污染——这是 Operate 工作台的光学，不是表演。

| 词汇 | 规范 | 落点 |
| --- | --- | --- |
| 各向异性边缘高光 rim 环 | R5/R8 的对称 rim 对（顶/底两条等亮描边）升级为**有光源方向的 1px 渐变描边环**：顶缘最亮、沿侧缘 88px 衰减收暗、底缘一抹环境微光——比任何对称 inset 都更接近 Apple Liquid Glass 的受光读法。技法是 mask-composite 渐变描边（`padding: 1px` + `content-box` 双 mask 异或/排除，`-webkit-mask-composite: xor` 与标准 `mask-composite: exclude` 双声明），环随宿主 `border-radius: inherit` 走任何圆角。色值延续 R8/R9 调校：亮 `--app-rim-top 0.82 / side 0.22 / bottom 0.30`，暗 `0.10 / 0.04 / 0.06`（暗只调亮度档）。**每条边只声明一次**（§15 律沿用）：环是玻璃体唯一边缘声明，全部玻璃本体的 inset rim 从 box-shadow 链退役 | `global.scss`（token + 共享环 `.app-glass-rim::before` 等五选择器）；消费者：顶栏、主浮岛、会话浮岛、命令面板、popover、chip、抽屉、欢迎徽标 |
| @property 注册的 rim 色 | 三个 rim 色经 `@property`（`syntax: '<color>'`、`inherits: true`）注册——注册让 chip 悬停受光（R10）从「换一整条 box-shadow」变为「内插一个颜色属性」，150ms 平滑过渡保留；不支持 @property 的浏览器环照常绘制、悬停瞬时换值（状态无损失）。`--app-rim-light-hover` 随之更名 `--app-rim-top-hover`（亮 0.95 / 暗 0.16 值不变） | `global.scss`（@property 三枚 + `.app-glass-chip` transition/hover） |
| 玻璃 blur 三档分层 | 单档 `--app-material-filter` 拆为**三档折射梯**（见 Material Vocabulary › 折射 filter 分层三档）：bar（顶栏，最沉稳）/ island（两座浮岛 + chip + 移动页片）/ overlay（命令面板 + popover，R8 通透档整档留梯顶）——层级越高的浮层越透。旧 token 保留为 island 档**别名**，供 uno.config.ts 的 `glass-nav` shortcut（该文件本轮不可写，shortcut 现无消费者）；src 内全部消费者已迁移，grep 证实零残留 | `global.scss`（token）+ `global-header`（bar）、`global-sider` / `conversation-sidebar` / `.app-glass-chip`（island）、`search-modal` / `.n-popover`（overlay）、`chat/index.vue` 移动页片（bar） |
| 顶栏滚动渐显发丝线 | 顶栏底缘 0.5px 线（色 `--app-header-hairline`）与滚动状态联动：页面静止在顶部时透明——玻璃栏无线悬浮，什么都没被裁切；内容滚动到顶栏线下被裁切时淡入（250ms standard）。信号 = 布局滚动元素（`#__SCROLL_EL_ID__`）scrollTop，外加**与顶栏线齐平的嵌套滚动容器**（chat 画布消息列恰好在该线裁切，±2px 判定）；rAF 节流、document 级 capture 单监听器、路由 afterEach 补一次评估（防静默 resetScroll 留下陈旧态）。**降级三保险**：reduced-motion 不挂监听、无 JS 永不加类、CSS 默认态即常显——分隔线永不依赖脚本 | `global-header/index.vue`（监听 + `.glass-bar` 样式）+ `global.scss`（`--app-header-hairline` 双主题） |
| 顶栏底缘单声明 | 顶栏的底缘归发丝线、不归 rim：`.glass-bar` 局部压 `--app-rim-bottom: transparent`，环只受光顶缘与（屏外的）侧缘——每条边一个声明者，发丝线管分隔、rim 管受光 | `global-header/index.vue` |
| 工程边界 | ①环是纯静态绘制（无动画、无 backdrop-filter），每玻璃体一个 ::before，成本可忽略；②环绘于内容之上但只有边缘 1px——与旧 inset rim 占的是同一像素；③morph 边界律不破：View Transitions 仍只有主题切换一处；④无 SVG 位移折射滤镜（性能禁区）、命令面板 `@starting-style` 入场零改动；⑤静态卡片/表格零阴影红线未触碰——环只挂玻璃本体（`.app-state-badge` 空态徽标仍零 rim 零影，§17 律不变） | 本节记录 |
| **复审（R12 overdrive 重跑）** | 四项主体经全仓新鲜 grep+读码逐项实证已在工作树就位：①各向异性环——`@property` 三枚 + `:root`/`html.dark` 各一组值（亮 0.82/0.22/0.30、暗 0.10/0.04/0.06，与本文规范逐值相符）+ 共享环五选择器（`.app-glass-rim::before`/chip/popover 族/命令面板/抽屉）双 mask-composite（`-webkit- xor` + 标准 `exclude`）；八消费者（顶栏、主浮岛、会话浮岛、命令面板、popover、chip、抽屉、欢迎徽标）逐个核对在位，顶栏 `.glass-bar` 局部压 `--app-rim-bottom: transparent`（底缘单声明者=发丝线）；旧对称 inset rim（`inset 0 ±1px 0`）全仓零残留。②三档梯——六个 token 亮暗逐值与 Material Vocabulary 相符，全部消费者（header bar / 两岛与 chip island / 面板与 popover overlay / chat 移动页片 bar）各就其档，暗色三档无 brightness。③滚动渐显——rAF 节流 + document capture 单监听器 + afterEach 补评估 + reduced-motion 早退（线常显）+ CSS 默认常显，`--app-header-hairline` 亮暗双值 = R3 原 token 值；`tokens.*.boxShadow.header` 两处 `none` 就位，`shadow-header` 工具类零消费者。④禁区——`startViewTransition` 全仓唯一（global-header 主题切换）、`feDisplacementMap/feTurbulence` 零命中、`@starting-style` 仅命令面板一处未动。**追杀出唯二漏网（同一行）**：pdf-document-viewer 渲染纱 `.page-loading-mask` 的 `backdrop-blur-sm` 是全仓唯一梯外 backdrop-filter（且无 `-webkit-` 前缀，工程边界②破防），同行 `bg-white/75`/`text-stone-500` 硬编码暖灰绕过 token 且暗色不换血——收口为 bar 档折射（+前缀补齐）+ `rgb(var(--container-bg-color) / 0.75)` 面 + 主墨字（暗 ≈7:1 / 亮 ≈14:1 AA）。同文件 stage-feedback / toolbar / shell 的 stone 调色板残留是 colorize 域债务（R7 验收 grep 只列 hex 字面值，调色板名漏网），记录为已知残留不越权重绘 | `components/custom/pdf-document-viewer.vue`（.page-loading-mask） |
| 验收 | grep `app-rim-light\|app-rim-under` 于 src = 零命中；`--app-material-filter` 于 src 仅剩两处别名定义（uno shortcut 通道）；`shadow-header` 消费零残留；`pnpm typecheck` 0 错、eslint 改动文件 0 错、detect --json（绝对路径）`[]`、global.scss 与改动 vue 样式块 sass 编译通过。真机验收（滚动渐显、环的双浏览器 mask-composite、chip 悬停内插）待后端可用时按 §11 轮廓判定补——本轮后端 8081 未运行，仅登录页可达。复审加验：grep `backdrop-blur` 于 src 零命中（唯二漏网已收口）、全部 backdrop-filter 均带 `-webkit-` 前缀 | 本轮 runs.jsonl |
