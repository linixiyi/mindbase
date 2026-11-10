# 前端质量收口：流式体验、可访问性与工程门禁

<!-- spec-format: 3 -->

## Goal
- goal: 把前端从"体验打磨不错但工程基线薄"推进到"关键交互有纯函数测试、错误与停止语义可见、无障碍可用、本地有可执行门禁"。
- literal ask: "我要怎么跟zcode说，你现在起草前端包"（m00811）
- done means: 13 张卡全部勾选且最终树 verify 通过；`pnpm typecheck`、`pnpm lint --max-warnings 0`、`pnpm test:unit`、`bash scripts/frontend_gate.sh` 全绿；`bash scripts/frontend_a11y_gate.sh --check`、`bash scripts/i18n_gate.sh --check` 绿；不新增运行时依赖。

## Context
本轮输入是前端审计（`.runtime/research/03-frontend-ux.md`）与 2026-11-11 的现状复核。R1 已用 DOMPurify 收口 XSS（`frontend/src/utils/markdown.ts:103`，`chat-message.vue:188` 转义文件名），所以本包不再重复消毒，而是补真正缺的层：

1. 流式归约逻辑（start/chunk/completion/stop/error/重连快照）全部内联在 `input-box.vue`，无测试；`assistant.content += payload.chunk`（:171）逐帧追加，每条 chunk 触发整段 Markdown 重渲染。
2. 生成中按 Enter 会走 stop 分支（:363-381），停止失败静默；停止与发送共用一个图标按钮（:483-487），停止后状态仍是 `finished`，与正常完成不可区分。
3. 历史消息有两个加载器：`store/modules/chat/index.ts:253` 与 `chat-list.vue:217`，切会话发两次同参 GET；请求层无 `AbortController`，旧响应可能覆盖新会话。
4. 无障碍缺失：`aria-live` 全仓 0 个，会话行是 `div @click`，操作按钮 `group-hover` 才可见，发送/停止按钮无 `aria-label`。
5. i18n 装饰性：`fallbackLocale: 'en'`（`src/locales/index.ts:8`），产品页约 1,182 行中文硬编码，缺 key 时中英混排。
6. 请求层残留模板垃圾：`src/service/request/index.ts:25` 硬编码 `apifoxToken`，多处调试 `console.log` 留在生产路径。
7. WS 帧在两处手写 `Record<string, any>` 解析（`input-box.vue`、`knowledge-base/index.vue:635`），`typings/api.d.ts` 的类型没用上。
8. 列表用 `:key="index"`（`chat-list.vue:333`、`chat-history/index.vue:164`），组件内 `scrollToBottom` 赋给 store 后卸载不清理（`chat-list.vue:244`）。
9. 引用链接构造（`chat-message.vue:167-194`）已转义但无回归测试，且 840 行组件仍在膨胀。
10. 前端只有 1 个 vitest 文件（`markdown.spec.ts`）、1 个硬编码端口的裸 Playwright 脚本，没有 `playwright.config.ts`，也没有本地门禁脚本。

## Charter
- V1 priority: quality > maintainability > speed > cost | charter.md
- V2 scope: adjacent-fixes | charter.md
- V3 verification: package | charter.md
- V4 finish: merge | charter.md
- V5 dependencies: none-new | charter.md
- V6 compatibility: keep-public-api | charter.md
- V7 blocked: park-and-continue | charter.md

## Scope
- in: frontend/**, scripts/frontend_*.sh, scripts/i18n_gate.sh, docs/frontend-quality.md
- out: src/main/java/**, src/main/resources/**, .github/workflows/**, pom.xml, frontend/package.json 的依赖版本、后端 WS 协议

## Assumptions
- A1 | 流式内容仍是逐帧字符串累加 | probe: `grep -n 'assistant.content += payload.chunk' frontend/src/views/chat/modules/input-box.vue` | CONFIRMED
- A2 | 历史消息有两套加载器 | probe: `grep -n 'async function loadMessages' frontend/src/store/modules/chat/index.ts; grep -n 'async function getList' frontend/src/views/chat/modules/chat-list.vue` | CONFIRMED
- A3 | 请求头带硬编码 apifoxToken | probe: `grep -n 'apifoxToken' frontend/src/service/request/index.ts` | CONFIRMED
- A4 | 全仓没有 aria-live | probe: `grep -rn 'aria-live' frontend/src | wc -l` | CONFIRMED
- A5 | fallbackLocale 是 en | probe: `grep -n 'fallbackLocale' frontend/src/locales/index.ts` | CONFIRMED
- A6 | 消息列表 key 用 index | probe: `grep -n ':key="index"' frontend/src/views/chat/modules/chat-list.vue frontend/src/views/chat-history/index.vue` | CONFIRMED
- A7 | 没有 playwright.config.ts | probe: `ls frontend/playwright.config.*` | CONFIRMED
- A8 | 引用链接仍在 chat-message.vue 内联 | probe: `grep -n 'function createSourceLink' frontend/src/views/chat/modules/chat-message.vue` | CONFIRMED
- A9 | 前端只有一个 vitest 文件 | probe: `find frontend/src -name '*.spec.ts' | wc -l` | CONFIRMED
- A10 | 没有前端本地门禁脚本 | probe: `ls scripts/frontend_gate.sh` | CONFIRMED
- A11 | 本机已缓存 Playwright 浏览器（chromium/ffmpeg 9 项） | probe: `ls ~/Library/Caches/ms-playwright | wc -l` | CONFIRMED

## Forks
- F1 | when: 停止与发送的按钮布局 | a) 拆成两个显式按钮（发送/停止） b) 保持单按钮长按停止 | pick: a | by: V1 | reversal: reversible
- F2 | when: 生成中用户按 Enter | a) 阻止发送并提示"用停止按钮中断" b) 入队，生成完自动发送 | pick: a | by: V1 | reversal: reversible
- F3 | when: i18n 迁移节奏 | a) 先冻结基线，中文行只降不升 b) 一次性迁移 1,182 行 | pick: a | by: V1 | reversal: reversible
- F4 | when: 入口 chunk 1.5MB | a) manualChunks 拆 shiki/markdown 并设预算门禁 b) 只报告不阻断 | pick: a | by: V3 | reversal: reversible

## Criteria
- C1 | 流式归约是纯函数并有 7 类帧的 vitest 覆盖，input-box 只做适配 | task-0001
- C2 | 同一窗口内多条 chunk 合并为一次内容更新，完成时 flush 且不丢字符 | task-0002
- C3 | 切会话只发一次历史请求，旧请求被取消，失败有可见错误态 | task-0003
- C4 | 停止是独立按钮且有 aria-label；停止失败可见；停止后状态为 stopped | task-0004
- C5 | 会话列表键盘可达，消息区有 aria-live，发送/停止有可读名称，门禁脚本可复跑 | task-0005
- C6 | fallbackLocale 为 zh-CN，中文硬编码行数被基线门禁锁定 | task-0006
- C7 | apifoxToken 与生产路径调试 console.log 消失，刷新逻辑不变 | task-0007
- C8 | 聊天相关请求收口到 service/api/chat.ts，WS 帧有判别联合与统一解析器 | task-0008
- C9 | 消息列表 key 稳定，scrollToBottom 在卸载时清理 | task-0009
- C10 | 引用链接构造抽为纯函数，恶意文件名/属性逃逸有测试 | task-0010
- C11 | Playwright 有 config、可 `--list`、不依赖硬编码端口与 token | task-0011
- C12 | 本地门禁脚本串联 typecheck/lint/unit/build，文档可照做 | task-0012
- C13 | 构建产物入口 chunk 低于预算，超限时脚本非 0 | task-0013

## Decisions
- D1 | 不新增依赖：测试只用已装的 vitest + jsdom，e2e 只用已装的 @playwright/test（V5）。
- D2 | 不改后端 WS 协议：0001 的帧类型只描述前端已消费的字段；seq/去重留给后端轮次。
- D3 | 0005 与 0009 的 a11y/键位门禁用 grep 脚本而不是引入 eslint 插件，避免依赖变更。
- D4 | 0006 只锁定基线，不做全量翻译；`--update-baseline` 需人工在 PR 说明里写理由。
- D5 | 包定义勘误（同评测包 D9 先例）：0002 交付与成功判定明文要求改 chat-list.vue 滚动 watch、0008 交付④明文要求 typings 增帧类型（实际路径 frontend/src/typings/api.d.ts）、0011 交付③明文要求旧 spec 移到 e2e/kb-column.spec.ts，三者均未列于各自 boundary 清单——按交付补入对应卡 boundary，非扩权 | by: V2 | reversal: checkpoint revert

## Deferred
- 虚拟滚动/历史分页：需要后端分页参数，当前一次拉全量，单独立卡。
- WS 帧 seq/幂等：需要后端加字段，放在 R3 之后。
- pdf-document-viewer（1,655 行）与 model-provider（1,058 行）拆分：改动面大，等 0012 门禁稳定后单独立包。
- 部署层 CSP/HSTS：需要 nginx 与域名配置，归部署轮。

## Risks
- R1 | e2e 真跑依赖 dev server 与登录态；0011 的 verify 只用 `--list`，真跑由 0012 文档标注为可选，不纳入默认门禁。
- R2 | 中文行数基线会随正常开发波动；0006 的脚本只拦"增长"，删注释导致的下降允许。
- R3 | 0002 的批量 flush 可能改变"首字出现时间"的观感；以 50ms 为上限并在测试里断言顺序与最终内容不变。

## Binding
- branch: `spec/2026-11-11_frontend-quality`
- frozen: 2026-11-10T22:23:18+00:00
