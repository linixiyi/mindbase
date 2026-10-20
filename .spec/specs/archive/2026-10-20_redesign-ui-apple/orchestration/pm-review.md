# PM 审查：Apple 重设计后四条用户故事走查（task-pm）

- 审查人：产品经理审查员（只读走查，未改任何源码）
- 日期：2026-10-20
- 环境（已实测确认）：前端 dev server `http://localhost:9527`（node, LISTEN）、后端 `http://localhost:8081`（java, LISTEN）；`curl http://localhost:8081/api/v1/users/login` 用 `.env` 引导凭据返回 `HTTP 200 / Login successful`；`GET /api/v1/search/hybrid?query=检索&topK=10` 返回 2 条命中（HYBRID_RRF, score 0.0325）。
- 方法：真实浏览器（ZCode IAB, 1440×900）按用户故事操作 + 关键点读源码定位。截图为会话内目检证据（路径见各节）。亮色主题目检受限（见「未竟事项」）。

---

## 用户故事 1：登录（打开 9527 → 登录页 → 提交 → 工作台）

### 走查记录
1. 打开 `http://localhost:9527/` → 重定向 `#/login`。暗色（themeScheme 'auto' 跟随系统）：页面 #0A0A0C 地面 + #1C1C1E 卡片 + 发丝线，靛蓝主按钮「登录账号」、次级「注册账号」、用户协议/隐私政策文字链。观感符合新世界（DESIGN.md「静光玻璃」），无 SoybeanAdmin 旧观感。截图：`call_0759228d0dcb4147bbfdb9b3-tool-result-eb07acc4-797c-474e-ab4c-ab8a1b87e92e.png`
2. 空表单点「登录账号」：两输入框红描边 + 逐字段「请输入用户名/请输入密码」→ 校验反馈完整。截图：`call_7fcb749050814039addc30c5-tool-result-de53d83b-a0a8-495b-84f1-0136fb1e80a3.png`
3. 填入 `.env` 的 ADMIN_BOOTSTRAP_USERNAME/PASSWORD 提交：**表单拦截「密码格式不正确，6-18位字符，必须包含字母和数字」，请求未发出，停留在登录页**。截图：`call_29a87ad8b82c464487802870-tool-result-4c9bfa37-47fd-4b4a-86e4-85ee4f4a2b35.png`
4. 交叉验证（终端）：同一凭据 `curl -X POST /api/v1/users/login` → `HTTP 200 / Login successful`（token 已脱敏，即取即用未落盘）。凭据本体有效，**唯前端校验拦截**。
5. 后续故事以「真实 API 登录 + localStorage 预置 token」作为环境准备（web-gui-tester Phase Two 允许），整页 reload 后 header 正确显示「admin」，聊天/知识库全部接口正常——**预置方式未替代任何被测行为**（登录表单本身已按上一步实测）。

### 发现
- **[F1][P0] 文档化引导管理员凭据无法通过登录表单校验（任务阻断）**
  - where：`frontend/src/constants/reg.ts:12`（`REG_PWD = /^(?=.*[A-Za-z])(?=.*\d).{6,18}$/`）→ `frontend/src/hooks/common/form.ts:19-22,40`（formRules.pwd）→ `frontend/src/views/_builtin/login/modules/pwd-login.vue:40`
  - problem：`.env` 引导密码实为 19 位含特殊字符（实测统计：length 19 / hasLetter / hasDigit / hasSpecial），被前端正则拦截，后端同凭据 HTTP 200——持有文档凭据的管理员从 UI 永远登录不了。
  - suggestion：前端 pwd 规则与后端对齐（如 8 位以上、允许特殊字符、去掉 18 位上限），或至少为 admin/引导账号放开；同时把「首次启动改回 false」的运维路径与前端规则联动说明。
- **[F2][P2] 首访启动闪屏回退旧世界紫 `#646cff` 且按亮色渲染**
  - where：`frontend/src/plugins/loading.ts:10`（`localStg.get('themeColor') || '#646cff'`；`darkMode: localStg.get('darkMode') || false`）
  - problem：首次访问/清存储后（新演示机、面试官首开场景），启动闪屏是旧 Vite 紫，且暗色系统下先亮紫屏再闪入暗色应用；DESIGN.md 明令旧色不得以兼容名义回归。
  - suggestion：闪屏颜色/主题改为从 `src/theme/settings.ts` 常量直读（应用内已有 `themeColors` immediate watch 会回写 localStorage，仅首访缺口）。
- 正面确认：登录卡 18px 圆角、发丝线、靛蓝主按钮、焦点光环、暗色对比度均达标；主题/语言切换在卡片右上可用（`login/index.vue:59-71`）。

---

## 用户故事 2：提问（chat 发问 → 流式 → 工具调用 → 停止/重发）

### 走查记录（暗色）
1. 发送「帮我总结知识库里的内容」：用户气泡靛蓝 18px/右上 6px 收窄、右对齐；回复占位为靛蓝三点流式指示；发送键变橙色停止键。截图：`call_c315f795555747b9afa01f84-tool-result-30672bd4-8dfc-42aa-b6cb-5dea193f9a82.png`
2. 流式中途：回答 15px/1.65 无气泡坐在画布实底上（DOM 实测 `.chat-answer`/父容器 backgroundColor 透明，符合设计）。截图：`call_7f27a18140654b77b40f2fc8-tool-result-493d7099-3fcd-465e-a9c0-d3c37559e466.png`
3. 工具调用胶囊：「✓ 检索知识库 已完成」（绿 check + 语义深阶文字）实时出现，完成后保留（DOM：`toolEvents: ["检索知识库已完成","生成知识摘要已完成"]`）。截图：`call_0c0af68c82b141589672541d-tool-result-9d58a77d-0910-8372-e228cc0be84d.png`
4. 停止：发送「详细介绍一下Kafka…」流式中途点停止键（点击时按钮 class 实测 `n-button--warning-type`）→ 部分回答保留、流式终止、按钮复原。截图：`call_4424c9baef48465d9ff9d01b-tool-result-326c533b-bb06-4189-9ee7-502db3ea3f7c.png`
5. 重发：全仓检索无「重新生成/重试」入口（grep `重新生成|regenerate|retry` 仅命中限流文案）；错误回答（"服务器繁忙，请稍后再试"）同样只能整段重打（input-box.vue:394 发送即清空输入）。

### 发现
- **[F3][P2] 无「重新生成/重试」入口**
  - where：`frontend/src/views/chat/modules/chat-message.vue:442-476`（actions 仅 复制/点赞/点踩）；`frontend/src/views/chat/modules/input-box.vue:344-395`
  - problem：停止后的半截回答与失败回答都没有一键重试，用户必须凭记忆重新输入整个问题，长问题场景伤害明显。
  - suggestion：在 assistant 消息 actions 增「重新生成」（复用上一条 user 消息重发），错误态文案旁给「重试」按钮。
- **[F4][P2] 停止后的回答无「已停止」标记**
  - where：`frontend/src/views/chat/modules/input-box.vue:351-368`（stop 分支仅置 `status='finished'`）
  - problem：截断回答与完整回答视觉无差，Kafka 实测例（"我先在知识库中检索 Kafka 相关内容。"）读起来像完整回答，用户可能误信残缺内容。
  - suggestion：停止后在消息 meta 区标注「已停止生成」胶囊（warning 深阶文字）。
- **[F5][P2] 触发检索的回答没有引用标注**
  - where：前端渲染链 `chat-message.vue:170-207`（正则依赖后端输出「(来源#N: …)」格式）；实测「帮我总结知识库里的内容」「MinIO」「Kafka」三条回答 `.source-file-link` 数量均为 0，而「检索知识库 已完成」胶囊存在
  - problem：RAG 产品「有据可查」的核心承诺在总结类问答中落空；检索执行了但用户看不到任何溯源（可能是模型/prompt 未输出标注，前端是唯一可见面，需产品跟进闭环）。
  - suggestion：后端在工具检索后强制输出引用映射；前端在「有检索事件但零引用」时给提示条（如「本次回答未命中可引用片段」），避免静默无溯源。
- 正面确认：流式指示、工具胶囊状态色（success/error 深阶）、限流倒计时（input-box.vue:141）、连接状态点（绿/橙脉冲/红，input-box.vue:463-471）齐全。

---

## 用户故事 3：引用（标注 → 悬停/点击 → 引用预览 → 返回）

### 走查记录（暗色）
1. 引用 chip：靛蓝胶囊内联在句末「(来源#1: mindbase-e2e-test.md)」，暗色下 0.16 底 + primary-400 文字。悬停实测背景 0.10→0.16（计算样式 `rgba(91,91,232,0.16)`）且 cursor:pointer。截图：`call_b0a2fffceb6046269971a793-tool-result-5f90c55a-9a13-4aff-9fe7-6751ee8b60b3.png`
2. 点击 chip → `window.open` 新窗口 `/chat?preview=reference&previewKey=…`（chat-message.vue:249-261）；预览页渲染：左栏 insight-rail（文件名、MD / 分数 0.033、新窗口[禁用]/下载/关闭、概览/检索问题/线索）+ 右侧原文（DOM 全量核实；独立预览窗口在 IAB 后台无法截图，颜色证据以计算样式为准）。
3. 在主标签页整页重载同一预览 URL → 渲染正常（证明非数据问题；SPA hash 直跳不挂载属边缘路径）。
4. 「关闭」→ `router.back()` → 回到 `#/chat`，对话完整（4 问 4 答、输入内容保留）。
5. **暗色关键实测**：`html.dark=true` 下 `.file-preview-container`/`.preview-content` 背景为 `rgb(255,255,255)` 纯白，继承文字色 `rgb(245,245,247)`；卡内文字靠 stone 系硬编码深色（`rgb(68,64,60)`/`rgb(120,113,108)`/`rgb(41,37,36)`）才可读。

### 发现
- **[F6][P1] 引用详情预览（file-preview）是未换新的浅色孤岛，暗色主题下失效**
  - where：`frontend/src/components/custom/file-preview.vue:489`（root `bg-white`）、`:502-643`（stone-50/100/200/500/600/700/800/900 全套硬编码 + `shadow-sm` 静态卡阴影 + `rounded-12px/16px` 圈外圆角）
  - problem：暗色主题（产品必备场景）下打开引用预览，近黑壳层中央嵌一整块刺眼白板；且部分文字继承浅色 `#F5F5F7`，未被子规则覆盖处存在白底浅字不可读风险。同时违反 DESIGN.md 三条戒律（禁硬编码色值、静态卡零阴影、禁发明第六档圆角）。引用预览恰是本产品「可溯源」的招牌动线。
  - suggestion：整组件迁移到主题 token（bg-container/bg-elevated/hairline/fill-subtle + primary/0.1 高亮），删除 stone 系与 shadow-sm，圆角归入 10/14/18/24 刻度；QA 暗色下走一遍 chip→预览 全链。
- **[F7][P2] PDF 查看器残留旧世界 AntD 蓝渐变**
  - where：`frontend/src/components/custom/pdf-document-viewer.vue:1584`（`linear-gradient(180deg, rgba(64,169,255,0.12) 0%, rgba(24,144,255,0.28) 100%)`）；同文件 `:1508/:1536` stone 系 + 8/12px 圆角
  - problem：PDF 引用预览的搜索高亮/局部 UI 仍是旧 AntD 蓝与硬编码灰，与新世界「一线靛」直接冲突，肉眼可辨。
  - suggestion：高亮改 `rgb(var(--primary-color)/0.12~0.22)` 双层（暗色 400 阶），stone 灰迁移到 fill-subtle/text 深阶 token。
- **[F8][P3] 预览页「新窗口」按钮恒禁用且无解释**
  - where：`reference-preview-page.vue` 渲染的 FilePreview 头部（DOM 实测 `button "新窗口" [disabled]`）
  - problem：点击引用本就开在新窗口，此按钮永远点不了，无 tooltip 说明，显得像坏了。
  - suggestion：独立窗口场景直接隐藏该按钮，或 disabled + tooltip「已在新窗口」。
- **[F9][P3] 独立预览窗口里「关闭」不关窗**
  - where：`frontend/src/views/chat/modules/reference-preview-page.vue:136-143`（`handleBack` → `router.back()/push('/chat')`）
  - problem：预览窗（window.open 打开）点「关闭」不会关窗，而是把整个工作台又渲染进这个窗，与按钮语义相反。
  - suggestion：`!window.opener` 时改 `window.close()`。
- **[F10][P3] 每次点引用新开一个标签页 + previewKey 永不清理**
  - where：`chat-message.vue:249-261`（`window.open` + `localStorage.setItem(previewKey)`，全链无 `removeItem`）
  - problem：连续核对 3 条引用 = 3 个标签页；localStorage 预览载荷无限累积。
  - suggestion：`window.open(同命名窗口)` 复用单一预览窗（或应用内抽屉），payload 读取后清理。
- 正面确认：chip 样式与解析完全符合「只改样式层」硬约束（DOM 结构/data-file-id/正则未动）；关闭返回后对话状态零丢失。

---

## 用户故事 4：知识库（上传 → 检索弹窗 → 预览命中）

### 走查记录（暗色）
1. 列表页：#1C1C1E 实底卡片 + 发丝线 + 「已完成」success 胶囊 + 预估/实际向量化双列（Tokens/切片数，tabular-nums）+ 分页。观感符合新世界。截图：`call_73d1c694d9714a1c88e04223-tool-result-29ea588a-2ffc-46fb-9ee2-f09c9ce7f685.png`
2. 「新增」上传弹窗：所属空间（带说明与失败/为空提示，upload-dialog.vue:136-140）、是否公开、上传文件（max=1 + accept 白名单）。**空文件点「保存」→「上传文件 不能为空」内联错误（实测）**；非管理员有大小限制预估提示（:39-46）。
3. 上传进度：弹窗提交即关，进度在表格「上传状态」列（NProgress processing，index.vue:291-305），由 WebSocket `document_status` 推送驱动（:455-489）+ 断线降级 5s 轮询（:492-514）——链路完整但弹窗内零反馈。
4. 检索弹窗交互 **未能执行**（自动化环境输入管线中途失效，cua/playwright/dom_cua 三路输入均不达页面，重开标签页无效；详见「未竟事项」）。补偿验证：`search-dialog.vue` 代码核对（高亮类走 primary/0.1 与 dark 0.16 token、双空态文案、Score 标签 tabular-nums）+ `GET /search/hybrid` 实测 2 命中（fileName/score/matchedChunkText/HYBRID_RRF 齐全）。
5. 预览：文件名与「预览」按钮同走 FilePreview；弹窗外壳已用新世界 token（`.document-preview-modal-shell`：`--app-radius-xl` + `--app-shadow-e3`，index.vue:649-653），内容区即 F6 的浅色孤岛（混合态）。

### 发现
- **[F11][P3] 上传过程中弹窗内零反馈**
  - where：`frontend/src/views/knowledge-base/modules/upload-dialog.vue:57-65`（`enqueueUpload` 后立即 `close()`）
  - problem：点保存后弹窗瞬间消失，新手不确定「是否已经开始上传/去哪看进度」。
  - suggestion：关闭前给「已加入上传队列，可在列表查看进度」toast，或弹窗内展示队列态。
- **[F12][P3] 检索/预览的原始 RRF 分数裸奔**
  - where：`search-dialog.vue:141`（`Score: {{ item.score }}`）；file-preview「MD / 分数 0.033」
  - problem：0.0325 这类 RRF 分数对普通用户无意义，无法判断「命得好不好」。
  - suggestion：按分数段映射「相关度 高/中/低」或在问号 tooltip 里解释。
- 正面确认：上传错误态（校验/大小限制/组织加载失败）状态完整且暗色适配（form-hint dark 分支）；检索 API 数据闭环；表格/弹窗外壳均为新世界。

---

## 旧观感残留清扫（专项回答：是否还有肉眼可混的残留？）

**结论：壳层（侧栏菜单/顶栏/聊台/登录卡/表格/上传与预览弹窗外壳/工具胶囊）已无 SoybeanAdmin 旧观感；但存在 3 处肉眼可辨的未换新残留，集中在「引用预览组件族」与「首访闪屏」。**

| # | 残留点 | 位置 | 症状 | 定级 |
|---|---|---|---|---|
| R1 | FilePreview 整体 | `components/custom/file-preview.vue:489-643` | bg-white + 全套 Tailwind stone 硬编码 + shadow-sm 静态卡阴影 + 12/16px 圈外圆角；暗色下白色孤岛 | 计入 F6（P1） |
| R2 | PDF 查看器局部 | `components/custom/pdf-document-viewer.vue:1508/1536/1584` | AntD 蓝渐变高亮 + stone 灰 + 8/12px 圆角 | 计入 F7（P2） |
| R3 | 启动闪屏 | `plugins/loading.ts:10` | 首访回退旧紫 `#646cff`，暗色系统下先亮后暗 | 计入 F2（P2） |
| R4 | 会话图标圆角 | `views/chat/modules/conversation-sidebar.vue:119` | `rounded-8px`（低于 10px 最小刻度） | P3，随手改 |
| — | 壳层/表格/弹窗/菜单 | 全局扫描 | 无 `#1890ff` 系/`shadow-sider` 装饰滥用（header/sider 的 shadow 为发丝线投影，系 DESIGN 规范值）；`uno.config.ts:37` card-wrapper=`rounded-18px+hairline+shadow-none` ✓；`settings.ts` `recommendColor:false` ✓ | 通过 |

---

## 未竟事项（如实声明）

1. **亮色主题未目检**：dev 模式主题方案为 `themeScheme:'auto'`（settings.ts:3）跟随系统偏好，IAB 无色彩模拟接口；曾尝试 localStorage 预置，被 `shared.ts:13`（`if (!isProd) return themeSettings`）忽略。亮色仅做了静态核对（settings.ts/global.scss 双主题 token 定义齐全）。建议主线程 task-browser 按 spec 用真机外观切换补亮色截图。
2. **检索弹窗、KB 预览按钮的交互未跑**：IAB 输入管线在会话中途失效（mask 卡 `fade-in-transition-leave-from`、三路合成输入失效、重开标签页无效——自动化环境缺陷，非产品行为；上传弹窗「取消」看似无效即此因）。两处已用代码核对 + API 实测补偿，弹窗内视觉（含 F12 分数展示）留待 task-browser。
3. 独立预览窗口无法截图（IAB 后台窗口合成器不渲染），预览页视觉结论基于 DOM + 计算样式（`html.dark` 下白底实测），置信度高但无图像证据。
4. 「待核实」引用徽标（citation-suspicious-badge）无实测数据命中，仅代码核对（chat-message.vue:694-724）。

## 分级汇总

| 级别 | 数量 | 编号 |
|---|---|---|
| P0 | 1 | F1 |
| P1 | 1 | F6 |
| P2 | 4 | F2 F3 F4 F5 F7（含残留 R2/R3） |
| P3 | 6 | F8 F9 F10 F11 F12 R4 |

> 走查轮次结论：新世界在壳层与 chat 旗舰页已成立且质感在线；阻断项（F1）与最伤体验项（F6）都不在「视觉世界」本体，而在边界组件与校验规则——下一轮修复面小而集中。

---

# 第二轮复审（2026-10-20）

- 审查人：产品经理审查员（只读走查，未改任何源码）
- 环境（实测确认）：前端 `http://localhost:9527` HTTP 200；后端 `http://localhost:8081` 有响应。工作区新增改动 = 第二轮铺开的管理页组（git status 12 个文件，+1152/-391）。
- 方法：代码走查（git diff 全量 + 逐页读源）+ 真实浏览器（IAB 1440×900，暗色）。浏览器证据类型：DOM 快照 + 只读计算样式取证（「暗色亮岛探测器」：扫描 main 下所有可见元素，凡计算背景为不透明近白者即记违规）。
- **如实声明（本轮无图像证据）**：本会话 IAB 截图管线从头到尾超时（`screenshot surface preparation timed out`），输入管线在登录成功后逐步失效（playwright click 超时 → CUA 坐标点击两次成功后失效 → dom_cua 滚动超时），与首轮末段缺陷一致，属自动化环境问题非产品行为。登录 P0 用 dom_cua 真实点击完成端到端验证；管理页以「路由直跳 + reload」作为环境准备使页面可达（同首轮「预置 token」先例），页面内容本身全部为黑盒只读取证。

## 1. 上一轮修复验证

### P0 登录密码校验拦截——**已修复（端到端实测通过）**
- 代码：`frontend/src/constants/reg.ts:12` `REG_PWD = /^(?=.*[A-Za-z])(?=.*\d).{6,64}$/`（commit 61627f0）。
- 实测（node）：`.env` 引导密码 length=19 / letters=true / digits=true / special=true / **regex pass=true**（值未落盘未外显）。
- 后端交叉：`UserService.java:244-254` `authenticateUser` 只做 `PasswordUtil.matches` 哈希比对、不校验格式 → 登录路径无后端阻碍。
- 浏览器端到端：登录表单填入引导凭据后**无任何「密码格式不正确」提示**（首轮同表单同凭据被拦截），dom_cua 点击「登录账号」→ URL `#/login` → `#/chat`，工作台正常渲染。
- ⚠️ 但修复引入新错位，见下方 SF1。

### P1 file-preview 浅色孤岛——**代码级已修复（点击链路受环境阻塞，已如实披露）**
- 代码走查：`file-preview.vue` 全量迁移 token——`bg-white`→`bg-container`、stone 全系→`var(--app-text/-secondary/-tertiary/fill-subtle/border)`、`shadow-sm` 全部移除（回归「阴影只属浮层」）、12/16px 圆角→`--app-radius-md/lg/xl`、错误态 rose 系→`--error-700/-400` 语义深阶（遵守语义深阶律）；`grep -cE "stone-|bg-white|shadow"` 结果 **0**。
- 变量存在性：新引用的 `--app-surface/--app-text/--app-text-secondary/--app-text-tertiary/--app-border/--app-fill-subtle` 在 `global.scss:10,11,12,16,19,20,24` 定义、`:55-61` 有 html.dark 暗色分支；`--app-radius-sm/md/lg/xl`（:35-38）与 `--app-shadow-e1/e2/e3`（:41-43）齐全，无静默失效。
- 配套：`global.scss:108-114` 新增 `.n-modal-container .n-modal-mask` → `var(--app-scrim)`，预览弹窗遮罩随主题；KB 预览弹窗外壳 `document-preview-modal-shell`（knowledge-base/index.vue:649-653）xl 圆角 + e3 阴影合规；独立预览页 `reference-preview-page.vue` 容器走 bg-container + 发丝线。
- 间接佐证：暗色下 8 个管理页「亮岛探测器」计数全为 0，token 管线在真实页面生效。
- **未竟**：chip→预览 的现场点击链路因输入管线失效无法重跑；第一轮的白底证据为 DOM 计算样式，本轮修复以代码级证据 + 间接佐证判定通过，建议 task-browser 补一次暗色 chip→预览 截图。

### 顺带观察
- 上轮 F5（总结类回答零引用）：本轮快照中「帮我总结知识库里的内容」回答已带多个 `(来源#1: mindbase-e2e-test.md)` 引用 chip（对话时间 2026-10-20 03:19），首轮症状未复现。
- 上轮 F2（闪屏旧紫 `loading.ts:10` 仍 `#646cff`）、F7（`pdf-document-viewer.vue:1472-1584` 仍全套 stone + bg-white + AntD 蓝渐变）、F3/F4/F8/F9/F10：**均未动**（相关文件不在本轮改动列表）。注意 F7 在 file-preview 已 token 化后更显眼：PDF 预览的旧观感孤岛如今坐在新壳层里。

## 2. 管理页组走查（代码 + 暗色实测）

| 页面 | 表格秩序 | 表单体验 | 空态/错误态 | 暗色实测 | 旧观感残留 |
|---|---|---|---|---|---|
| user | 9 列有序、th 透明底+次级文字、td tabular-nums ✓ | 筛选草稿制（查询/重置分离）✓ | 空态区分「筛选空/真空」+ 清除动作 ✓ | 卡 #1C1C1E/18px/零阴影，亮岛 0 | 无 |
| personal-center | Token 记录 9 列、带 ± 号与前后余额 ✓ | — | 用量/记录/标签三路错误态+重试、空态文案齐 ✓ | 亮岛 0 | 无（主标签弹窗见 SF7） |
| recharge | 订单 8 列、状态胶囊映射完整 ✓ | 自定义金额校验+提示 ✓ | 套餐/记录双空态、筛选空态给「查看全部」动作、错误态+重试 ✓ | 亮岛 0；磁贴 md 圆角/fill-subtle/靛蓝描边选中合规 | 无（QR 黑白为扫码硬性条件，有注释说明） |
| recharge-manage | 10 列、操作列 fixed-right、删除带 Popconfirm ✓ | 元/分、万/枚换算清晰+一键文案 ✓ | 空态引导「新增套餐」、错误态+重试 ✓ | 亮岛 0 | console.log 残留（SF5） |
| usage-monitor | 汇总 5 卡 tabular-nums、排行/告警列表发丝线分隔 ✓ | 限流 4 卡分组网格 ✓ | 用量错误态+重试、各 NEmpty ✓ | 亮岛 0；图表色走 themeVars 且 echarts hook 内建 dark 主题（echarts.ts:156,224-226） | 限流静默回落默认值（SF2） |
| invite-code | 10 列、批量删除带部分失败报告、末行删除回退页码 ✓ | 编辑/创建均 restoreValidation、批量/自定义互斥校验 ✓ | 空态区分筛选/真空 + 主行动按钮 ✓ | 亮岛 0 | 复制链接为占位域名（SF3） |
| model-provider | —（表单页） | 服务商侧栏导航 aria+focus-visible、Key 脱敏回显、留空保留语义 ✓ | 加载失败/为空双态+重试、错误图标语义深阶 ✓ | 亮岛 0 | 无 |
| org-tag | 4 列有序 ✓ | — | 空态说明用途+新增按钮 ✓ | 亮岛 0 | 无 |

暗色主题总检：`html.dark=true` 下 `--primary-color: 91 91 232`（#5B5BE8 暗色靛）、container `28 28 30`、layout `10 10 12`——三层表面与 DESIGN.md 逐值一致；8 页「亮岛」计数全部为 0。

chat 日期筛选条（chat-list.vue:126-160）：已收敛为 800px 阅读列内右对齐工具行、label 12px 弱文字、选择器静置无框、hover 才现发丝线、焦点一线靛——快照确认渲染于消息列顶端，不再横贯整幅；移动端保留 56px 侧栏让位。**收敛达成**。

## 3. 第二轮新发现

- **[S1][P2] 密码规则修复只放宽了前端，注册/建管理员仍会被后端 6-18 旧规则拒绝**
  - where：`frontend/src/constants/reg.ts:12`（6-64）vs `src/main/java/com/mindbase/service/UserService.java:57-58`（`PASSWORD_PATTERN = .{6,18}`）、`:97/:221`（registerUser/createAdminUser 调用 validatePassword）、`:230-235`
  - problem：19-64 位密码前端放行、后端 400「密码格式不正确，6-18位字符」；`reg.ts:10` 注释「后端要求 ≥12」与实际不符（≥12 只是 `AdminUserInitializer.java:89` 的引导账号检查）。登录不受影响（authenticateUser 不校验格式），故仅注册/改密路径错位。
  - suggestion：把后端 PASSWORD_PATTERN 同步放宽为 6-64（或前后端共同收敛为一个值），并修正 reg.ts 注释；避免下一个 19 位密码用户在注册末步踩坑。
- **[S2][P2] 限流配置加载失败静默回落内置默认值，可能诱导管理员覆盖线上配置**
  - where：`frontend/src/views/usage-monitor/index.vue:107-119`（`else if (!rateLimits.value) rateLimits.value = createDefaultRateLimitSettings()`，无任何错误提示）
  - problem：接口失败时页面安静地显示硬编码默认值（30 次/120000 Token 等），管理员无从分辨「现网配置」与「兜底假数据」，点「保存配置」即把默认值写穿线上限流。
  - suggestion：失败改为显式错误态（同页 getOverview 的样式），未加载真实配置前禁用「保存配置」。
- **[S3][P2] 邀请码「复制链接/复制话术」复制的是占位域名，新成员拿到的是死链**
  - where：`frontend/src/views/invite-code/index.vue:105`（`https://your-domain.example/#/login/register`）→ `:139`（复制链接）、`:149`（复制话术）
  - problem：管理员的两个核心分发动作产出 `your-domain.example` 开头的不可用链接。存量问题（git log -S 定位为初始提交 3c5f4d8 引入），本轮页面翻新未处理。
  - suggestion：改 `window.location.origin + '/#/login/register'` 或读运行时配置的环境变量。
- **[S4][P3] 路由切换后视图卡在旧页（本次会话复现 3+ 次，reload 恢复）**
  - where：SPA 路由层（hash 已变、侧栏选中态已更新、`main` 内容仍渲染上一页；实验输出：`#/org-tag` 下 header 仍为「用户列表」，reload 后恢复「组织标签」；`#/model-provider` 同样卡「组织标签」）
  - problem：点击菜单/hash 直跳后 URL 与内容不一致，普通用户感知为「点了没反应/页面错了」。首次进入正常、后续切换卡滞，怀疑与 dev 懒加载 chunk 或 IAB 环境叠加有关，**需真机复核后再定是否产品缺陷**。
  - suggestion：真机浏览器复测菜单切换；若复现，检查路由懒加载失败兜底与过渡动画挂起路径。
- **[S5][P3] recharge-manage 调试残留与校验状态泄漏**
  - where：`frontend/src/views/recharge-manage/index.vue:330/335/345/353`（4 处 console.log 含提交数据）；`:281-294` handleEdit 未调 restoreValidation（对照 invite-code `:315-326` 两路径均调）
  - problem：生产控制台噪音+表单数据外泄通道；「新增→校验失败→关闭→改点编辑」时上次的校验红字可能残留到编辑弹窗。
  - suggestion：删 console.log；handleEdit 补 restoreValidation。
- **[S6][P3] 管理页时间列格式两派并存**
  - where：`recharge/index.vue:232,238`、`personal-center/index.vue:211` 用 `toLocaleString('zh-CN')`（2026/10/20 03:56:04）；`user/index.vue:71`、`recharge-manage/index.vue:227`、`invite-code/index.vue:215` 用 dayjs `YYYY-MM-DD HH:mm:ss`
  - problem：同类信息两种格式，跨页对比订单/记录时有观感毛刺，违背后「层级靠统一刻度」的秩序感。
  - suggestion：统一封装一个时间格式化工具（dayjs YYYY-MM-DD HH:mm:ss），两处 toLocaleString 收编。
- **[S7][P3] 主标签确认弹窗不回显标签名**
  - where：`frontend/src/views/personal-center/index.vue:332-342`（content=「确定将当前标签设置为主标签吗？」）
  - problem：多标签用户无法确认在设置哪个标签为「主」；org-tag 磁贴可点击换主标签也无「点击设为主标签」可供性提示，首次使用者未必敢点。
  - suggestion：弹窗文案插值标签名（`确定将「${tag.name}」设为主标签吗？`），磁贴 hover 给提示或副文案。

## 4. 分级汇总（第二轮）

| 级别 | 数量 | 编号 |
|---|---|---|
| P0 | 0 | —（上轮 F1 已验证修复） |
| P1 | 0 | —（上轮 F6 代码级验证修复，现场点击链路受环境阻塞已声明） |
| P2 | 3 | S1 S2 S3 |
| P3 | 4 | S4 S5 S6 S7 |

未清残留（上轮已登记、本轮未动，不新增定级）：F2（P2 闪屏旧紫）、F3/F4/F7（P2，其中 F7 在新壳层内更显眼）、F8/F9/F10（P3）。

## 5. 收敛判断

**建议收敛。** 依据：① 上轮唯二 P0/P1 均已验证修复（P0 端到端实测，P1 代码级+暗色零亮岛佐证）；② 本轮 8 个管理页（含对话框）代码层 grep 零硬编码色/零静态阴影/圆角全部落在 10/14/18/24 刻度，暗色实测「亮岛」计数全部为 0，无旧观感残留；③ chat 日期筛选条已收敛。新增 S1-S3（P2）为功能性跟进项、S4-S7（P3）为打磨项，均不在「视觉世界」本体，不阻塞本 spec 收敛；建议随下一迭代修复，且 F2/F7 两条上轮 P2 残留（闪屏、PDF 查看器）仍挂在旧账上，收敛后应排期清偿。
