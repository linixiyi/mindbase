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
