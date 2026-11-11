# 前端质量门禁与执行文档

> 来源：spec `2026-11-11_frontend-quality` / task-0012 / Criteria C12。
> 本文是前端质量门禁的唯一入口：四条基础命令、一条串联门禁、e2e 可选前提、常见失败与修复动作。
> 所有命令均已在本文档对应的卡片交付时实测通过；没有标注"可选"的步骤都是默认门禁。

## 1. 四条基础命令

统一在 `frontend/` 目录执行（包管理 pnpm，要求 >= 8.7）：

| # | 命令 | 作用 | package.json 里的实现 |
|---|------|------|------------------------|
| 1 | `pnpm typecheck` | 类型检查 | `vue-tsc --noEmit --skipLibCheck` |
| 2 | `pnpm exec eslint . --max-warnings 0` | Lint，0 警告容忍 | eslint 9 flat config（`frontend/eslint.config.js`，基于 `@soybeanjs/eslint-config`） |
| 3 | `pnpm test:unit` | 单元测试 | `vitest run`；全部用例必须在无后端、无外部服务时通过 |
| 4 | `pnpm build` | 生产构建 | `vite build --mode prod`，产物在 `frontend/dist/` |

两点注意：

- `pnpm lint` 等价于 `eslint . --fix`（自动改文件），**不是门禁命令**；门禁永远用第 2 条——显式 `--max-warnings 0`、不自动修复、不允许任何 warning。
- 顺序有意义：类型与 lint 先失败先拦截，避免带着类型错误跑构建。门禁脚本就是按这个顺序串的。

## 2. 一条命令串联：scripts/frontend_gate.sh

```bash
# 在仓库根执行（脚本会自己定位 frontend/，从任意目录调用均可）
bash scripts/frontend_gate.sh --check      # 阶段 1→2→3；verify 命令，日常改动至少跑这条
bash scripts/frontend_gate.sh --full       # 阶段 1→2→3→4；提交 / 发布前跑
bash scripts/frontend_gate.sh --self-test  # 门禁自身自检：临时目录模拟阶段失败并断言退出码
```

语义：

- **fail-fast**：任一阶段失败即打印 `[失败] 阶段 '<名称>'` 并以退出码 1 结束，后续阶段不再执行。
- **缺依赖不静默失败**：`frontend/node_modules` 不存在时直接提示 `cd frontend && pnpm install` 后重跑。
- **阶段输出透传**：底层命令（vue-tsc / eslint / vitest / vite）的原始输出直接可见，用于定位错误。
- git pre-commit 钩子（simple-git-hooks）已自动跑 `pnpm typecheck && pnpm lint && git diff --exit-code`；门禁脚本用于提交前的主动复跑与不在该钩子覆盖面的场景。
- 本门禁是**本地闸门**，不改 `.github/workflows/**`；本轮 CI 不接 e2e（spec R1），不要往工作流里加这些命令。

## 3. e2e（可选，不进默认门禁）

冒烟用例在 `frontend/e2e/`，配置在 `frontend/playwright.config.ts`（task-0011）。分两档：

### 3.1 最低要求（不需要浏览器、不需要登录态、不需要 dev server）

```bash
cd frontend && pnpm exec playwright test --list e2e
```

`--list` 只解析 spec 与配置、列出用例，不会启动 `webServer`、不会拉起浏览器。这是 e2e 的唯一默认要求。

### 3.2 真跑浏览器（可选）

前提依次检查：

1. **Playwright 浏览器已安装**：`pnpm exec playwright install chromium`。本机已有缓存（`~/Library/Caches/ms-playwright`），通常免装。
2. **dev server**：config 的 `webServer` 会在真跑时自动执行 `pnpm dev` 并复用已在跑的实例（`reuseExistingServer: true`），默认地址 `http://localhost:9527`，可用环境变量 `E2E_BASE_URL` 覆盖（webServer 启动超时 60s）。
3. **登录态**：`e2e/kb-column.spec.ts` 需要 `E2E_TOKEN`（会写入 localStorage 的 `token`），缺省时该用例自动 `test.skip`；`e2e/layout-smoke.spec.ts` 不需要登录态（未登录重定向是纯前端路由守卫行为）。

```bash
cd frontend && pnpm exec playwright test e2e
```

真跑失败时的排查顺序：先确认 `webServer` 是否起来（9527 端口是否被占用、是否 60s 超时），再确认浏览器是否安装，最后才是用例本身。

## 4. 常见失败与修复动作

### 4.1 类型失败（阶段 1：pnpm typecheck）

报错形如 `error TS2367` / `error TS2532`，前缀带文件与行列。

修复动作：

- 按报错位置修类型本体。不要用 `as` 强转或局部拓宽类型绕过；若是因为状态联合缺成员（如消息 status），先把联合类型补全（spec 决策 D6 的先例）。
- 涉及后端响应结构时，先确认 `frontend/src/typings/api.d.ts` 里的字段定义，不猜字段、不改后端协议。
- 修完复跑：`pnpm typecheck`。

### 4.2 Lint 失败（阶段 2：--max-warnings 0）

报错形如 `src/xxx.vue: 12:5  warning  Some rule  ...`，末尾汇总 `✖ n problems (0 errors, n warnings)` 后非 0 退出。

修复动作（按优先级）：

1. **修问题本体**（排序、未用变量、命名、`vue/component-name-in-template-casing` 等）。配置里有几条规则刻意是 `warn` 级，`--max-warnings 0` 会把它们当失败项，这是有意的。
2. 历史代码确需豁免：加**精确**的 `// eslint-disable-next-line <rule>`，并在同一行或上一行注释写明原因。禁止整文件 `eslint-disable`。
3. **不得放宽**：不许把 `--max-warnings 0` 改大、不许把规则全局改成 off（spec task-0012 失败处理红线）。
4. `pnpm lint`（`--fix`）可以用于批量自动修复，但跑完必须 review diff——它会改文件，且 pre-commit 钩子里的 `git diff --exit-code` 依赖修复不产生未提交改动。
- 修完复跑：`pnpm exec eslint . --max-warnings 0`。

### 4.3 中文基线失败（scripts/i18n_gate.sh，task-0006）

报错形如 `xxx.vue 基线 42，当前 45，超出 3 行`（任一 `.vue` 的中文硬编码行数超过 `frontend/i18n-baseline.txt` 登记）。

修复动作：

- **新增了中文硬编码**：优先把文案迁进 `frontend/src/locales/`（fallbackLocale 已是 zh-CN）或改用 `$t`。产品页现存约千余行中文属已知现状（spec 背景 5），基线只拦"增长"不要求清零。
- **有意豁免 / 文件删除或改名**：`bash scripts/i18n_gate.sh --update-baseline` 重新生成基线，并在 PR / 提交说明里写明原因（spec 决策 D4）。不得手改 `frontend/i18n-baseline.txt` 的数字。
- 行数**下降**会直接通过，并提示可 `--update-baseline` 收紧基线；择机收紧即可。
- 修完复跑：`bash scripts/i18n_gate.sh --check`。

### 4.4 构建内存失败（阶段 4：pnpm build，仅 --full）

报错形如 `FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory`，或长时间卡在 chunk 生成阶段。

修复动作：

- **临时缓解**：`NODE_OPTIONS=--max-old-space-size=4096 pnpm build`（4GB 还不够再上 8192）。这只是把上限推后，不是修复。
- **根因排查**：构建后 `ls -S frontend/dist/assets | head` 看最大的 chunk；入口 chunk 过大说明首屏依赖图过大。拆包（manualChunks 拆 markdown/shiki/editor 等大依赖）与体积预算门禁由同一 spec 的 task-0013（`scripts/frontend_bundle_check.sh`，随该卡落地）承接，方向是拆依赖而不是无限加内存。
- 修完复跑：`pnpm build`（参考量级：当前树全量构建约 10~25s 量级）。

### 4.5 其他

- `frontend_gate: 缺少 .../node_modules`：`cd frontend && pnpm install` 后重跑门禁。
- a11y 门禁失败（`bash scripts/frontend_a11y_gate.sh --check`，task-0005）：输出会逐条点名缺失项（role/listbox、tabindex、@keydown、aria-label、aria-live、focus-within），按项补齐即可；规则语义见脚本头部注释。
- vitest 用例失败：所有用例必须在无后端、无外部服务时通过；如果某个用例失败是因为依赖了后端在线，那是用例本身的 bug，修用例而不是起后端来"配合"它。

## 5. 提交前清单

- [ ] `bash scripts/frontend_gate.sh --check` 退出 0（最低要求）
- [ ] 改动涉及构建产物 / 依赖图时，`bash scripts/frontend_gate.sh --full` 退出 0
- [ ] 涉及聊天交互界面时 `bash scripts/frontend_a11y_gate.sh --check` 退出 0；涉及 `.vue` 文案时 `bash scripts/i18n_gate.sh --check` 退出 0
- [ ] 涉及 e2e 时至少 `cd frontend && pnpm exec playwright test --list e2e` 退出 0
- [ ] 提交前扫描暂存 diff，不带入生产敏感值（见 AGENTS.md 硬规则）
