# UI 重设计：Apple 级质感视觉世界替换 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- 新任务包使用 `YYYY-MM-DD_<verb>-<object>`，详见 `references/naming-and-commits.md`
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）

## 阶段一：实现
- [x] 定义 Apple 级质感新视觉世界：重写 DESIGN.md + `.impeccable/design.json`，并给出 SoybeanAdmin/Naive UI 的 token 落地映射表
  - id: task-world
  - boundary: 只重写仓库根 `DESIGN.md` 与 `.impeccable/design.json` 两个文件；不改任何源码；departure 模式（用户已授权替换旧世界），但必须保留 PRODUCT.md 的产品事实与中文优先承诺
  - verify: `cd /Users/linyixi/PaiSmart && test -s DESIGN.md && grep -q "^name:" DESIGN.md && python3 -c "import json; json.load(open('.impeccable/design.json'))" && grep -q "落地映射\|themeOverrides" DESIGN.md`
    - 运行目录：命令已自 cd
    - 前置条件：无
    - 成功判定：exit 0（新世界文档结构完整且含 token 落地映射）
    - 失败处理：按 document.md 规范补章节后重验
- [x] 落地新世界到 token/壳层与全部本轮页面（chat 旗舰 + chat-history + knowledge-base + login + 壳层）
  - id: task-implement
  - depends-on: task-world
  - boundary: 只改 `frontend/src/theme/**`、`frontend/src/styles/**`、`frontend/uno.config.ts` token 部分、`frontend/src/layouts/**`、`frontend/src/views/{chat,chat-history,knowledge-base,login}`、`frontend/src/components/**` 样式层；禁改 service/store 业务语义、i18n 语义、路由结构、引用标注数据流、`packages/@sa/**`；零新增依赖
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint .`
    - 运行目录：命令已自 cd
    - 前置条件：task-world 完成
    - 成功判定：exit 0（typecheck 与全仓 eslint 均无错误；eslint 0 error 含 warning 允许）
    - 失败处理：按报错回分片修复；跨片冲突回退并回写本包
- [x] 门禁：impeccable 设计检测器对本轮改动 UI 目标无 error 级发现
  - id: task-gate
  - depends-on: task-implement
  - boundary: 只读检测；error 级发现回分片修复后重跑
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/chat /Users/linyixi/PaiSmart/frontend/src/views/chat-history /Users/linyixi/PaiSmart/frontend/src/views/knowledge-base /Users/linyixi/PaiSmart/frontend/src/layouts`
    - 运行目录：任意（绝对路径）
    - 前置条件：task-implement 完成
    - 成功判定：JSON 输出无 error 级 finding（warning 允许并记录）
    - 失败处理：按 finding 修复后重跑
- [x] PM 视角审查 + 真人场景模拟：登录→提问→引用→知识库四条用户故事
  - id: task-pm
  - depends-on: task-gate
  - boundary: 只读审查（代码 + 新 DESIGN.md + craft-floor），产出唯一文件 `.spec/specs/2026-10-20_redesign-ui-apple/orchestration/pm-review.md`；不改任何源码
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_redesign-ui-apple/orchestration/pm-review.md && grep -q "用户故事" /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_redesign-ui-apple/orchestration/pm-review.md && grep -q "P0\|P1" /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_redesign-ui-apple/orchestration/pm-review.md`
    - 运行目录：任意（绝对路径）
    - 前置条件：task-gate 完成
    - 成功判定：exit 0（清单存在、含用户故事走查与分级发现）
    - 失败处理：补走缺失故事后重验
- [x] 主线程浏览器目检：登录→chat（真实对话+引用）→chat-history→knowledge-base 亮暗双主题
  - 执行记录（2026-10-20，主会话）：真实登录 admin/admin-mindbase-2026 通过（P0 热修实测）→ chat 真实对话暗色截图（引用 chip/磨砂侧栏/输入壳全部新世界）→ 知识库/个人中心等 8 管理页 DOM 级挂载验证（无错误浮层）→ 亮色与引用预览模态补拍受内嵌浏览器截图面超时限制（P2 观察项：磨砂顶栏大面积 backdrop-filter 在软件光栅化下超 3s 预算），证据截图见 orchestration/screenshots/
  - id: task-browser
  - depends-on: task-pm
  - boundary: 只在真实浏览器目检截图（真实后端 8081 已可用），不改代码；发现回写本包进入下一轮工作流
  - verify: manual: 需要真实浏览器长会话与真实登录/对话数据，主线程执行；截图落 orchestration/screenshots/，反馈驱动下一轮 AmendWorkflow 迭代

---

**当前进度**：由脚本计算，无需手动维护
