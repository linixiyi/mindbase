# UI 升级：impeccable 设计体系落地与界面打磨 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- 新任务包使用 `YYYY-MM-DD_<verb>-<object>`，详见 `references/naming-and-commits.md`
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）

## 阶段一：实现
- [x] 修复前端幽灵依赖并验证 dev server 可启动（已收口：补声明 `@iconify/utils@2.3.0`、`@playwright/test`；pnpm 12 不再读 .npmrc 的 shamefully-hoist，已在 `pnpm-workspace.yaml` 加 `shamefullyHoist: true` 并强制重链；typecheck 恢复全绿）
  - id: task-env-fix
  - boundary: 只允许改 `frontend/package.json`、`frontend/pnpm-lock.yaml`、`frontend/pnpm-workspace.yaml`（补声明既有使用的依赖与包管理器配置，无版本升级）；不改任何源码
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm install --frozen-lockfile >/dev/null 2>&1 && test -f node_modules/@iconify/utils/package.json && pnpm typecheck >/dev/null 2>&1 && curl -sf http://localhost:9527 | grep -q "id=\"app\""`
    - 运行目录：命令已自 cd
    - 前置条件：dev server 已在 9527 运行（本包预检时已启动）
    - 成功判定：exit 0（依赖可解析、typecheck 全绿、dev server 响应含应用挂载点）
    - 失败处理：若 curl 失败先查 9527 端口进程与 dev server 日志；若依赖缺失重跑对应 `pnpm add -D`；若出现新 TS2307 幽灵依赖，按同样方式补声明并回写本包
- [x] 生成 DESIGN.md 与 `.impeccable/design.json` sidecar（impeccable document，scan 模式）
  - id: task-design-doc
  - depends-on: task-env-fix
  - boundary: 只新建仓库根 `DESIGN.md` 与 `.impeccable/design.json` 两个文件；不改任何源码、不动 PRODUCT.md；定性语言以用户确认的三项为准（北极星「静谧指挥台」、阴影「平面为主」、组件「克制精密」）
  - verify: `cd /Users/linyixi/PaiSmart && test -s DESIGN.md && grep -q "^name:" DESIGN.md && grep -q "^## Components" DESIGN.md && grep -q "^## Do's and Don'ts" DESIGN.md && python3 -c "import json; json.load(open('.impeccable/design.json'))"`
    - 运行目录：命令已自 cd
    - 前置条件：task-env-fix 完成（不依赖 dev server）
    - 成功判定：exit 0（DESIGN.md 含 frontmatter name 与规范章节，sidecar 可解析）
    - 失败处理：缺失章节按 impeccable document.md 规范补齐后重验
- [x] 关键页面 critique：chat / chat-history / knowledge-base / 全局壳层，产出带严重度分级的发现清单
  - id: task-critique
  - depends-on: task-design-doc
  - boundary: 只读（源码 + DESIGN.md + craft-floor.md），产出唯一文件 `.spec/specs/2026-10-19_upgrade-ui-design/orchestration/critique-findings.md`；不改任何源码
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-19_upgrade-ui-design/orchestration/critique-findings.md && grep -q "P0" /Users/linyixi/PaiSmart/.spec/specs/2026-10-19_upgrade-ui-design/orchestration/critique-findings.md && grep -q "chat" /Users/linyixi/PaiSmart/.spec/specs/2026-10-19_upgrade-ui-design/orchestration/critique-findings.md`
    - 运行目录：任意（绝对路径）
    - 前置条件：task-design-doc 完成（critique 以 DESIGN.md 为评判标准）
    - 成功判定：exit 0（清单存在、含 P0 级分级与 chat 页面条目）
    - 失败处理：清单缺面则补跑对应页面的 critique 分片后合并
- [x] polish 实施修复：按发现清单在三个互斥所有权分片内执行
  - id: task-polish
  - depends-on: task-critique
  - boundary: 只改 `frontend/src/views/{chat,chat-history,knowledge-base}` 相关组件与 `frontend/src/theme/`、`uno.config.ts` token、全局壳层样式；禁改 `src/service/**`、`src/store/**` 业务语义、i18n 语义、路由结构、`packages/@sa/**`、后端
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck`
    - 运行目录：命令已自 cd
    - 前置条件：task-critique 的发现清单已合并去重
    - 成功判定：exit 0 且无类型错误输出
    - 失败处理：按 typecheck 报错定位到分片内修复；跨分片冲突回退该文件改动并回写本包
- [x] impeccable 设计检测器对本轮改动 UI 目标无 error 级发现
  - id: task-detect
  - depends-on: task-polish
  - boundary: 只读检测；如发现 error 级问题允许回到对应分片修复后重跑
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/chat /Users/linyixi/PaiSmart/frontend/src/views/chat-history /Users/linyixi/PaiSmart/frontend/src/views/knowledge-base`
    - 运行目录：仓库根（路径为绝对）
    - 前置条件：task-polish 完成
    - 成功判定：JSON 输出中无 error 级 finding（warning 允许，记录到验收证据）
    - 失败处理：按 finding 定位文件修复后重跑本命令
- [x] 浏览器目检：chat / chat-history（含引用标注渲染链路）/ knowledge-base 亮暗两主题无布局回归
  - id: task-visual
  - depends-on: task-detect
  - boundary: 只在真实浏览器中目检与截图，不改代码；发现问题回写本包并由对应分片修复
  - verify: manual: 需要真实浏览器会话截图并与改前基线人工比对（引用标注渲染、亮暗主题、布局对齐），由主会话执行，截图落 `.spec/specs/2026-10-19_upgrade-ui-design/orchestration/screenshots/` 作为证据
  - 执行记录（2026-10-19，主会话）：真实后端未运行（8081 无监听，按 AGENTS.md 不代启后端），用临时 mock 桩
    （仅应答 /users/me，数据接口全部返回业务错误码，验证后已删除）通过路由守卫完成目检。
    六张截图落 orchestration/screenshots/（chat、chat-history、knowledge-base × 亮/暗）。
    结论：三页亮暗两主题无布局回归，新错误态/空态按 DESIGN.md 渲染；目检发现并修复 1 条新问题
    （body 裸文本继承 UA CanvasText 白色，亮色下会话侧栏标题不可读，已修 global.css 并复验）。
    限制：引用标注的带数据渲染无法在无后端环境目检，已由代码审查（改动仅 token 置换、未动 DOM 结构）
    + typecheck + 编译产物核查覆盖。

---

**当前进度**：由脚本计算，无需手动维护
