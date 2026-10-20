# UI 美化 R3：autorun 链收口 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：收口
- [x] DESIGN.md §11：R1/R2 新词汇落点表回写（page-header/selection+caret/欢迎区/@starting-style/档案头/空态）
  - id: task-doc
  - boundary: 只改仓库根 `DESIGN.md`
  - verify: `cd /Users/linyixi/PaiSmart && grep -q "page-header" DESIGN.md && grep -q "starting-style" DESIGN.md && grep -q "::selection" DESIGN.md`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：补写后重验
- [x] 全量 detect 门禁
  - id: task-detect
  - depends-on: task-doc
  - boundary: 只读检测
  - verify: `/Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json /Users/linyixi/PaiSmart/frontend/src/views/chat /Users/linyixi/PaiSmart/frontend/src/views/personal-center /Users/linyixi/PaiSmart/frontend/src/views/recharge-manage /Users/linyixi/PaiSmart/frontend/src/views/org-tag /Users/linyixi/PaiSmart/frontend/src/layouts/modules/global-search /Users/linyixi/PaiSmart/frontend/src/styles`
    - 运行目录：任意（绝对路径）
    - 成功判定：JSON 输出无 error 级 finding
    - 失败处理：按 finding 修复后重跑
- [x] 主线程真机抽查：欢迎区建议填充 / 页头区 / 选区（引用 R1/R2 证据合并）
  - id: task-browser
  - depends-on: task-doc
  - boundary: 只读走查
  - verify: manual: 主线程执行；R1/R2 当轮已取证（selection-zoom/org-tag-header/chat-welcome-after 等），本轮复核欢迎区可用性并记录
- [x] 收口复审：宣布链完成（5/5）+ 完成面板
  - id: task-closeout
  - depends-on: task-detect
  - boundary: 只改 `.spec/plans/ui-beautification.md`（勾选收口特性）、`.spec/autorun/**`、orchestration/ 复审文件
  - verify: `cd /Users/linyixi/PaiSmart && grep -c "^- \[x\]" .spec/plans/ui-beautification.md | grep -q "^5$" && test -s .spec/specs/2026-10-20_beautify-ui-closeout/orchestration/chain-closeout.md && grep -q "链完成" .spec/specs/2026-10-20_beautify-ui-closeout/orchestration/chain-closeout.md`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0
    - 失败处理：补齐后重验

---

**当前进度**：由脚本计算，无需手动维护
