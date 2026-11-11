# Tasks：前端质量收口

格式说明（给执行端）：

- 每张卡按 `boundary → verify → expect` 执行；红基线意味着现在跑 verify 必须失败，改完之后必须绿。
- `boundary` 之外的改动视为越界；确需扩边请在 Decisions 记录并向用户确认。
- 所有 verify 命令都在仓库根执行，已显式 `cd /Users/linyixi/PaiSmart`。
- 新增测试文件一律 `new:` 前缀声明；测试必须能在无后端、无外部服务时跑。
- 不新增依赖：只用已装的 vitest、jsdom、@playwright/test；不改 `package.json` 的 dependencies。
- 多张卡会改 `input-box.vue` / `chat-list.vue` / `request/index.ts` / store，必须按 `depends-on` 串行。

## 任务

- [x] task-0001 流式归约抽纯函数并补 7 类帧测试
  - id: task-0001
  - boundary: frontend/src/views/chat/modules/input-box.vue, new:frontend/src/views/chat/stream/reducer.ts, new:frontend/src/views/chat/stream/__tests__/reducer.spec.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/views/chat/stream/__tests__/reducer.spec.ts`
  - expect: red
  - 背景：start/chunk/completion/stop/error/重连快照全部内联在 `input-box.vue`（`handleStartPayload`、`handleChunkPayload` 等），没有任何测试；`assistant.content += payload.chunk` 只在组件里可验证。
  - 交付：新建 `stream/reducer.ts`，导出 `reduceStreamFrame(state, frame)` 纯函数与 `ChatStreamFrame` 判别联合（至少 start/chunk/completion/stop/error/snapshot 六类，字段按现有后端帧命名）；`input-box.vue` 的五个 handle* 改为调用 reducer 并只保留副作用（滚动、消息提示、timer）。`handleStopPayload` 在本卡保持 `finished`，停止语义留给 0004。
  - 测试：`reducer.spec.ts` 覆盖 ①start 建消息并带 generationId ②chunk 累加 ③completion finished + referenceMappings ④stop 不改内容 ⑤error 带 errorCategory ⑥重复 start 不新建 ⑦乱序 chunk 按到达顺序拼接。断言 reducer 不修改入参。
  - 成功判定：vitest 7 用例全绿；`grep -c "content += payload.chunk" input-box.vue` 为 0。
  - 失败处理：后端帧字段拿不准时以现有 `input-box.vue` 消费的字段为准，不得改后端协议。

- [x] task-0002 chunk 批量 flush，消除逐帧全量重渲染
  - id: task-0002
  - boundary: frontend/src/views/chat/modules/input-box.vue, frontend/src/views/chat/modules/chat-list.vue, new:frontend/src/views/chat/stream/reducer.ts, new:frontend/src/views/chat/stream/buffer.ts, new:frontend/src/views/chat/stream/__tests__/buffer.spec.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/views/chat/stream/__tests__/buffer.spec.ts`
  - expect: red
  - depends-on: task-0001
  - 背景：每条 WS chunk 立即改 `assistant.content`，VueMarkdownIt 随即整段重渲染，长答案 O(n²)；`chat-list.vue:65` 还在每个 chunk 复制整个数组。
  - 交付：新建 `stream/buffer.ts`，实现 `createStreamBuffer({ flushIntervalMs = 50, onFlush })`：`push(generationId, text)` 累积、同窗口合并、`flush()` 立即输出并清空、`dispose()` 保证尾部不丢；completion/stop/error 前必须 flush。`input-box.vue` 改为 buffer → 一次 reducer 更新；`chat-list.vue` 的滚动 watch 改为监听最后一条消息的 `content.length` 而非 `[...list.value]`。
  - 测试：fake timers 下 ①同窗口 10 条 chunk 只触发 1 次 onFlush 且内容为完整拼接 ②跨窗口按时间顺序 flush ③completion 前 flush 剩余 ④dispose 清理定时器 ⑤flushIntervalMs=0 时退化为逐条（可测）。
  - 成功判定：vitest 绿；`grep -c "content += payload.chunk" input-box.vue` 为 0；`grep -c "\[...list.value\]" chat-list.vue` 为 0。
  - 失败处理：若 50ms 合并导致首字延迟可感知，降到 16ms 但不得回到逐条渲染；测试断言内容顺序与最终长度不变。

- [x] task-0003 历史加载单源 + 在途请求取消
  - id: task-0003
  - boundary: frontend/src/store/modules/chat/index.ts, frontend/src/views/chat/modules/chat-list.vue, frontend/src/service/request/index.ts, new:frontend/src/store/modules/chat/historyLoader.ts, new:frontend/src/store/modules/chat/__tests__/historyLoader.spec.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/store/modules/chat/__tests__/historyLoader.spec.ts`
  - expect: red
  - 背景：`store.loadMessages`（:253）与 `chat-list.getList`（:217）各有一套守卫，切会话会发两次同参 GET；request 层无 `signal`，旧响应可能覆盖新会话；加载失败没有可见错误。
  - 交付：新建 `historyLoader.ts`，实现 `createHistoryLoader(fetcher)`：同 conversationId 在途去重、切换时 abort 上一笔、只有最新 cid 的结果可提交、返回 `{ data, error, aborted }`；store 的 `loadMessages` 改用它并暴露 `historyError`；删除 `chat-list.vue` 的 `getList` 与 `historyRequestId`，只订阅 store；`request` 选项透传 `signal`；`chat-list.vue` 在 `historyError` 时渲染可见错误与重试按钮。
  - 测试：注入 fake fetcher ①并发两次同 cid 只调用 1 次 ②切 cid 后旧请求被 abort 且旧结果不提交 ③fetcher reject 时 error 可见且不覆盖现有消息 ④abort 不算错误。
  - 成功判定：vitest 绿；`grep -c "historyRequestId" chat-list.vue` 为 0；`grep -c "url: 'users/conversation'" chat-list.vue` 为 0。
  - 失败处理：`@sa/axios` 不支持 signal 时在 request 包装层用 `AbortController` + `axios.CancelToken` 等价实现，不改调用方语义。

- [x] task-0004 停止语义与独立停止按钮
  - id: task-0004
  - boundary: frontend/src/views/chat/modules/input-box.vue, frontend/src/views/chat/modules/chat-message.vue, new:frontend/src/views/chat/stream/reducer.ts, new:frontend/src/views/chat/stream/__tests__/stop.spec.ts, frontend/src/views/chat/stream/__tests__/reducer.spec.ts（D6：:85 过渡断言迁移 stopped）, frontend/src/typings/api.d.ts（D6：status 联合仅追加 'stopped'）
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/views/chat/stream/__tests__/stop.spec.ts`
  - expect: red
  - depends-on: task-0001
  - 背景：生成中按 Enter 直接走 stop（`input-box.vue:363-381`），获取 cmd token 失败时静默 return；停止与发送共用一个按钮（:483-487），停止后状态写成 `finished`，与正常完成不可区分。
  - 交付：①发送与停止拆成两个按钮，停止按钮仅生成中显示，带 `aria-label="停止生成"`；②生成中 Enter 不再中断，改为提示"生成中，点击停止按钮可中断"并且不发送；③停止请求失败时 `window.$message.error` 可见提示并保留 loading；④reducer 增 `stopped` 状态，`handleStopPayload` 改为 `stopped`，`chat-message.vue` 对 `stopped` 渲染"已停止"标记且保留已生成内容。
  - 测试：`stop.spec.ts` 覆盖 ①生成中 stop 帧 → status stopped 且 content 不变 ②正常 completion → finished ③error 帧不被 stop 覆盖 ④stopped 后重复 completion 不改成 finished。
  - 成功判定：vitest 绿；`grep -c "已停止" chat-message.vue` ≥1；`grep -c "aria-label=\"停止生成\"" input-box.vue` =1。
  - 失败处理：后端停止失败时不得本地假装 stopped；保持 loading 并提示重试。

- [x] task-0005 无障碍：键盘可达、aria-live、按钮名称
  - id: task-0005
  - boundary: frontend/src/views/chat/modules/conversation-sidebar.vue, frontend/src/views/chat/modules/input-box.vue, frontend/src/views/chat/modules/chat-list.vue, new:scripts/frontend_a11y_gate.sh
  - verify: `cd /Users/linyixi/PaiSmart && bash scripts/frontend_a11y_gate.sh --check`
  - expect: red
  - depends-on: task-0004
  - 背景：全仓 `aria-live` 0 个；会话行是 `<div @click>`（`conversation-sidebar.vue:141-147`），操作按钮 `opacity-0 group-hover:opacity-100`（:177）键盘不可见；textarea 只有 placeholder，发送/停止按钮无名称。
  - 交付：①会话行改 `role="option"` + `tabindex="0"` + Enter/Space 触发，列表容器 `role="listbox"`；②操作按钮加 `focus-within:opacity-100` 并常显于键盘焦点；③textarea 加 `aria-label="输入消息"`；④发送按钮 `aria-label="发送"`；⑤消息列表容器加 `aria-live="polite"` 与 `aria-busy`（生成中 true）；⑥新建 `scripts/frontend_a11y_gate.sh --check`，检查上述模式存在、`conversation-sidebar.vue` 不再有 `div @click`、chat 模块 `aria-live` ≥1，失败打印缺失项并非 0。
  - 测试：脚本本身即门禁；追加 `--self-test` 用临时 fixture 验证"缺 aria-live 必失败"。
  - 成功判定：`bash scripts/frontend_a11y_gate.sh --check` 退出 0 且 `--self-test` 通过。
  - 失败处理：NButton 不透传 aria-label 时改用 `aria-label` 属性透传或外层 `<span role="button">` 包装，不改组件库。

- [x] task-0006 i18n 止血：fallback 改 zh-CN + 中文硬编码基线门禁
  - id: task-0006
  - boundary: frontend/src/locales/index.ts, new:frontend/i18n-baseline.txt, new:scripts/i18n_gate.sh
  - verify: `cd /Users/linyixi/PaiSmart && bash scripts/i18n_gate.sh --check`
  - expect: red
  - 背景：`fallbackLocale: 'en'`（`locales/index.ts:8`），产品页约 1,182 行中文硬编码、30 个视图文件，缺 key 时中英混排；短期无法全量迁移。
  - 交付：①fallback 改 `zh-CN`；②`i18n-baseline.txt` 记录每个 `.vue` 的中文行数（格式 `path count`），由脚本 `--update-baseline` 生成一次并提交；③`scripts/i18n_gate.sh --check` 逐文件比较，任一文件超出基线即失败并打印差值，文件减少时允许并提示可 `--update-baseline`；④脚本忽略注释行与 `console.` 调试行。
  - 测试：`--self-test` 用临时目录验证"超基线失败、减行通过、缺文件失败"。
  - 成功判定：`bash scripts/i18n_gate.sh --check` 退出 0；`grep -n "fallbackLocale" frontend/src/locales/index.ts` 为 zh-CN。
  - 失败处理：基线文件必须与当前树一致；首次生成后不得手改数字，只能通过 `--update-baseline` 并说明原因。

- [x] task-0007 请求层清污：移除模板 token 与生产调试日志
  - id: task-0007
  - boundary: frontend/src/service/request/index.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && ! grep -q "apifoxToken" src/service/request/index.ts && ! grep -q "console.log(" src/service/request/index.ts`
  - expect: red
  - depends-on: task-0003
  - 背景：`request/index.ts:25` 把 `apifoxToken` 硬编码进所有请求头；`:40`、`:49` 的调试 `console.log` 留在生产路径（含刷新 token 的成功日志）。
  - 交付：删除 `headers.apifoxToken`；所有 `console.log` 改为 `if (import.meta.env.DEV) console.debug(...)` 或删除；token 自动刷新逻辑、错误码分支、`errMsgStack` 行为保持不变；不引入新依赖。
  - 测试：verify 的 grep 即门禁；再跑 `pnpm typecheck` 确认类型未破。
  - 成功判定：verify 退出 0；`pnpm typecheck` 绿。
  - 失败处理：若某条日志对排障必要，改为 DEV 下 `console.debug`，不得保留 `console.log`。

- [x] task-0008 聊天请求收口 service/api + WS 帧判别联合
  - id: task-0008
  - boundary: new:frontend/src/service/api/chat.ts, new:frontend/src/service/api/__tests__/streamFrame.spec.ts, frontend/src/store/modules/chat/index.ts, frontend/src/views/chat/modules/input-box.vue, frontend/src/views/knowledge-base/index.vue, frontend/src/typings/api.d.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/service/api/__tests__/streamFrame.spec.ts`
  - expect: red
  - depends-on: task-0003, task-0007
  - 背景：聊天相关请求散在 store 与视图（20 处内联 `request({url})`），WS 帧在两处手写 `Record<string, any>` 解析（`input-box.vue:308-354`、`knowledge-base/index.vue:635`），`typings/api.d.ts` 的类型没用上。
  - 交付：①`service/api/chat.ts` 导出 `fetchConversationMessages`、`switchConversation`、`archiveConversation`、`sendFeedback`、`fetchWebsocketToken`，store 与 chat 视图改调 service；②`parseStreamFrame(raw: string): ChatStreamFrame | { type: 'unknown'; raw }` 处理 JSON 解析失败、缺 type、未知 type，不抛异常；③`input-box.vue` 与 `knowledge-base/index.vue` 统一用它；④`typings/api.d.ts` 增帧类型（若已有则复用）。
  - 测试：`streamFrame.spec.ts` 覆盖 ①合法 start/chunk/completion ②未知 type 返回 unknown ③非法 JSON 返回 unknown 且带 raw ④缺 generationId 的 chunk 不崩。
  - 成功判定：vitest 绿；`grep -rn "url: 'users/conversation'" src/store src/views` 为 0；`grep -rn "JSON.parse" src/views/chat/modules/input-box.vue` 为 0。
  - 失败处理：新 service 只做参数与返回类型包装，不改后端 URL、方法、错误码约定。

- [x] task-0009 列表稳定 key 与滚动回调清理
  - id: task-0009
  - boundary: frontend/src/views/chat/modules/chat-list.vue, frontend/src/views/chat-history/index.vue, frontend/src/store/modules/chat/index.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && ! grep -q ':key="index"' src/views/chat/modules/chat-list.vue && ! grep -q ':key="index"' src/views/chat-history/index.vue && grep -q "onUnmounted" src/views/chat/modules/chat-list.vue`
  - expect: red
  - depends-on: task-0005
  - 背景：`:key="index"`（`chat-list.vue:333`、`chat-history/index.vue:164`）导致重生成/替换消息时组件实例复用；`chat-list.vue:244` 把 `scrollToBottom` 赋给 store 后卸载不清理，store 持有已销毁闭包。
  - 交付：①key 改为 `msg.generationId || \`\${msg.role}-\${msg.timestamp}-\${index}\`` 的稳定键（index 仅作兜底）；②`onUnmounted` 中若 `chatStore.scrollToBottom === scrollToBottom` 则置 null；③管理端问答记录同样用稳定键。
  - 测试：verify 的 grep 门禁；`pnpm typecheck` 绿。
  - 成功判定：verify 退出 0。
  - 失败处理：无 generationId 的历史消息允许回退到 timestamp+index，但不得直接用 index 作为唯一键。

- [x] task-0010 引用链接抽纯函数并补转义回归测试
  - id: task-0010
  - boundary: frontend/src/views/chat/modules/chat-message.vue, new:frontend/src/views/chat/citation.ts, new:frontend/src/views/chat/__tests__/citation.spec.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec vitest run src/views/chat/__tests__/citation.spec.ts`
  - expect: red
  - 背景：R1 已加 `escapeHtml` 与 DOMPurify（`chat-message.vue:188`、`utils/markdown.ts:103`），但 `createSourceLink`/`processSourceLinks` 仍内联在 840 行组件里，且没有回归测试守护；恶意文件名、属性逃逸、`javascript:` 只靠人工验。
  - 交付：把 `createSourceLink`、`processSourceLinks`、正则与 `escapeHtml` 移到 `views/chat/citation.ts`，导出 `renderCitationLinks(text, { sources, mappings })` 纯函数；`chat-message.vue` 只调用并负责把返回的 `sourceFiles` 写入本地状态；行为（来源#N、页码、md5、SUSPICIOUS 标记）不变。
  - 测试：`citation.spec.ts` 覆盖 ①普通 `来源#1: a.pdf` ②文件名含 `<img onerror=alert(1)>` 不得产生标签 ③文件名含 `"` 不得逃逸 `data-file-id` ④`javascript:alert(1)` 链接被中和 ⑤页码变体 ⑥md5 变体 ⑦SUSPICIOUS 渲染"待核实"。
  - 成功判定：vitest 7 用例绿；`chat-message.vue` 行数下降且 `grep -c "function createSourceLink" chat-message.vue` 为 0。
  - 失败处理：DOMPurify 仍保留在 markdown 渲染链，不得因为抽函数而移除。

- [x] task-0011 Playwright 冒烟：config、去硬编码、2 条用例
  - id: task-0011
  - boundary: new:frontend/playwright.config.ts, new:frontend/e2e/layout-smoke.spec.ts, frontend/playwright-kb-column.spec.ts, new:frontend/e2e/kb-column.spec.ts
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm exec playwright test --list e2e`
  - expect: red
  - 背景：现有 `playwright-kb-column.spec.ts` 在根目录、硬编码 `http://127.0.0.1:9529`、token 读 `PAI_TOKEN`，没有 `playwright.config.*`，无法稳定复跑；CI 不接 e2e（本轮也不接）。
  - 交付：①`playwright.config.ts`：`testDir: './e2e'`，`baseURL: process.env.E2E_BASE_URL || 'http://localhost:9527'`，`webServer` 用 `pnpm dev` 且 `reuseExistingServer: true`，超时 60s；②`e2e/layout-smoke.spec.ts` 两条：登录页标题/输入框可见；未登录访问 `/#/chat` 被重定向到登录（断言 URL 或登录按钮）；③旧 spec 移到 `e2e/kb-column.spec.ts` 并改读 `E2E_BASE_URL`/`E2E_TOKEN`，缺 token 时 `test.skip`；④不新增 npm script、不改 `package.json` 与依赖，命令统一写进 0012 的文档。
  - 测试：verify 的 `--list` 必须列出 3 条用例且退出 0；真跑浏览器为可选（文档标注），本卡不改 `package.json`。
  - 成功判定：verify 绿；`grep -c "9529" frontend/e2e/*.spec.ts` 为 0。
  - 失败处理：本机未装浏览器时只保证 `--list` 与 typecheck，不把 e2e 真跑纳入 0012 的默认门禁。

- [x] task-0012 前端本地门禁脚本与执行文档
  - id: task-0012
  - boundary: new:scripts/frontend_gate.sh, new:docs/frontend-quality.md
  - verify: `cd /Users/linyixi/PaiSmart && bash scripts/frontend_gate.sh --check`
  - expect: red
  - depends-on: task-0001, task-0002, task-0003, task-0004, task-0005, task-0007, task-0008, task-0010, task-0011
  - 背景：前端门禁只有各人手动 `typecheck`/`lint`/`build`，没有一条可复跑命令；测试刚建立，容易被绕过。
  - 交付：①`scripts/frontend_gate.sh`：`--check` 依次跑 `pnpm typecheck`、`pnpm exec eslint . --max-warnings 0`、`pnpm test:unit`；`--full` 追加 `pnpm build`；任一失败即非 0 并打印阶段名；缺 `node_modules` 时提示 `pnpm install` 而非静默失败；②`docs/frontend-quality.md` 写清四条命令、e2e 可选前提、常见失败（类型/中文基线/构建内存）与修复动作；③不改 `.github/workflows/**`。
  - 测试：脚本 `--self-test` 可在临时目录模拟阶段失败并断言退出码。
  - 成功判定：`bash scripts/frontend_gate.sh --check` 退出 0；文档步骤照做可复现。
  - 失败处理：若 lint 因历史 warning 失败，先修或加精确 `eslint-disable-next-line` 并注释原因，不得放宽 `--max-warnings`。

- [x] task-0013 构建产物分包与体积预算
  - id: task-0013
  - boundary: frontend/vite.config.ts, new:scripts/frontend_bundle_check.sh
  - verify: `cd /Users/linyixi/PaiSmart && bash scripts/frontend_bundle_check.sh`
  - expect: red
  - depends-on: task-0012
  - 背景：入口 chunk 约 1.5MB（未压缩），echarts 另 639KB；路由已懒加载，但 markdown/shiki 与编辑器类依赖仍可能进首屏，且没有预算门禁。
  - 交付：①`vite.config.ts` 的 `build.rollupOptions.output.manualChunks` 拆出 `markdown`（markdown-it/shiki/dompurify）与 `editor`（如 wangeditor）等大依赖，保持现有 echarts 分包；②`scripts/frontend_bundle_check.sh`：跑 `pnpm build`，读取 `frontend/dist/assets`，打印 top5 chunk 大小，入口 chunk 超过 900KB（未压缩）即非 0；③阈值与理由写进脚本注释。
  - 测试：脚本自身输出 top5 与阈值判定；`--report-only` 只打印不失败，便于首次测量。
  - 成功判定：脚本退出 0 且入口 chunk < 900KB；页面路由懒加载行为不变。
  - 失败处理：若拆包导致循环依赖或首屏报错，回退该 manualChunks 项并在脚本注释记录，不得提高阈值掩盖。
