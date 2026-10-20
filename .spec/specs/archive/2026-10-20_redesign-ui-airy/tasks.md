# UI 重设计第三轮：亮色 airy 结构级大改 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：实现
- [x] 更新 DESIGN.md 为亮色 airy 结构规范（悬浮侧栏几何、去套盒规则、字号刻度、默认主题 light 决策）
  - id: task-world
  - boundary: 只改仓库根 `DESIGN.md` 与 `.impeccable/design.json`；不改源码；Quiet Glass 的暗色 token 组保留
  - verify: `cd /Users/linyixi/PaiSmart && grep -q "airy\|悬浮浮岛" DESIGN.md && grep -qi "light" DESIGN.md && python3 -c "import json; json.load(open('.impeccable/design.json'))"`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：按 document.md 规范补齐后重验
- [x] 结构级落地：壳层悬浮浮岛 + chat 去套盒 + 默认 light + 页面 airy 化
  - id: task-implement
  - depends-on: task-world
  - boundary: 只改 `frontend/src/theme/**`、`frontend/src/styles/**`、`frontend/uno.config.ts`、`frontend/src/layouts/**`、`frontend/src/views/**`、`frontend/src/components/**` 样式层、`frontend/src/store/modules/theme/` 的 themeScheme 默认值与主题值；禁改 service/store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**；零新增依赖
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint .`
    - 运行目录：命令已自 cd
    - 前置条件：task-world 完成
    - 成功判定：exit 0（typecheck 与全仓 eslint 均无错误）
    - 失败处理：按报错回分片修复；跨片冲突回退并回写本包
- [x] 门禁：检测器对本轮改动 UI 目标无 error 级发现
  - id: task-gate
  - depends-on: task-implement
  - boundary: 只读检测；error 级发现回分片修复后重跑
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/chat /Users/linyixi/PaiSmart/frontend/src/views/chat-history /Users/linyixi/PaiSmart/frontend/src/views/knowledge-base /Users/linyixi/PaiSmart/frontend/src/views/_builtin/login /Users/linyixi/PaiSmart/frontend/src/layouts`
    - 运行目录：任意（绝对路径）
    - 成功判定：JSON 输出无 error 级 finding
    - 失败处理：按 finding 修复后重跑
- [x] PM 复审：与旧观感并排对比"第一眼可辨"、全页走查无 P0/P1
  - id: task-pm
  - depends-on: task-gate
  - boundary: 只读审查，产出唯一文件 `.spec/specs/2026-10-20_redesign-ui-airy/orchestration/pm-review.md`
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_redesign-ui-airy/orchestration/pm-review.md && grep -q "第一眼" /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_redesign-ui-airy/orchestration/pm-review.md`
    - 运行目录：任意（绝对路径）
    - 成功判定：exit 0
    - 失败处理：补走查后重验
- [x] 主线程浏览器亮暗对比截图验收
  - id: task-browser
  - depends-on: task-pm
  - boundary: 真实浏览器目检截图，不改代码；发现回写本包进下一轮
  - verify: manual: 主线程执行，亮色为主截全量对比图 + 暗色抽查，落 orchestration/screenshots/

---

**当前进度**：由脚本计算，无需手动维护
