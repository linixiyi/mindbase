# UI 美化 R1：浏览器表面收口 + 管理页页头层级 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：实现
- [x] 浏览器表面主题化：::selection（亮/暗）、input/textarea caret-color、focus-visible 走查
  - id: task-surface
  - boundary: 只改 `frontend/src/styles/scss/global.scss`（或新增同目录样式文件经 global 引入）；零 JS
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "::selection" src/styles/scss/global.scss && grep -q "caret-color" src/styles/scss/global.scss && pnpm typecheck && pnpm exec eslint src/styles`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：按报错修复后重验
- [x] `.page-header` 页头区模式落地 recharge-manage 与 org-tag（页题 + 主行动槽；org-tag 的 TableHeaderOperation 改 `:addable="false"`）
  - id: task-page-header
  - depends-on: task-surface
  - boundary: 只改 `frontend/src/styles/scss/global.scss`（.page-header 模式）、`frontend/src/views/recharge-manage/index.vue`、`frontend/src/views/org-tag/index.vue`；handler 原样复用
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "page-header" src/styles/scss/global.scss && grep -q "page-header" src/views/recharge-manage/index.vue && grep -q "page-header" src/views/org-tag/index.vue && grep -q "addable=\"false\"" src/views/org-tag/index.vue && pnpm typecheck && pnpm exec eslint src/views/recharge-manage src/views/org-tag`
    - 运行目录：命令已自 cd
    - 前置条件：task-surface 完成
    - 成功判定：exit 0
    - 失败处理：按报错回改后重验
- [x] 表格空态全局排版（n-data-table-empty 的留白与 tertiary 文字层级）
  - id: task-empty-state
  - depends-on: task-surface
  - boundary: 只改 `frontend/src/styles/scss/global.scss`
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "n-data-table-empty" src/styles/scss/global.scss && pnpm typecheck`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：按报错修复后重验
- [x] 门禁：检测器零 error + design.json 有效性
  - id: task-gate
  - depends-on: task-page-header
  - boundary: 只读检测
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/recharge-manage /Users/linyixi/PaiSmart/frontend/src/views/org-tag /Users/linyixi/PaiSmart/frontend/src/styles`
    - 运行目录：任意（绝对路径）
    - 成功判定：JSON 输出无 error 级 finding
    - 失败处理：按 finding 修复后重跑
- [x] 主线程真机走查：选区选中一段文字（azure 选区底）、输入框插入符、recharge-manage/org-tag 页头、表格空态；亮暗各一遍 + 截图
  - id: task-browser
  - depends-on: task-gate
  - boundary: 只读走查 + 截图；发现回写本包
  - verify: manual: 主线程执行，截图落 orchestration/screenshots/（selection/header/empty 各一张，暗色抽查）
- [x] plan-sync：勾选计划文档 colorize+quieter、layout+typeset 两项 + chain.json 轮次状态更新
  - id: task-plan-sync
  - depends-on: task-browser
  - boundary: 只改 `.spec/plans/ui-beautification.md` 与 `.spec/autorun/chain.json`
  - verify: `cd /Users/linyixi/PaiSmart && grep -c "^- \[x\]" .spec/plans/ui-beautification.md | grep -q "^2$" && grep -q "beautify-ui-surface" .spec/autorun/chain.json`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：补勾选后重验

---

**当前进度**：由脚本计算，无需手动维护
