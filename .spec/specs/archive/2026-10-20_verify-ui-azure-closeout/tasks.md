# UI Azure 收口轮：复审反馈五项验证闭环 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- verify 命令不得自引用本包 check 门禁

## 阶段一：验证
- [x] V1 登录/注册页整页目检：登出 → 登录页新 logo hero / lucide 前缀图标 / azure 主按钮 → 注册页图标 → 重新登录
  - id: task-v1
  - boundary: 只读走查 + 截图；缺陷才回写修复
  - verify: manual: 主线程真浏览器执行（登出/登录为会话态操作，无法脚本断言视觉目检结论），截图落 orchestration/screenshots/login-v1*.png，DOM 指标（logo viewBox 48 + circle、按钮主色）随走查记录
- [x] V2 真实流式呼吸态：发送一条真实消息，观察 azure-breathe 呼吸点、流式文本增量、引用 chip、完成后静止
  - id: task-v2
  - depends-on: task-v1
  - boundary: 只读走查；单条短消息；缺陷才回写修复
  - verify: manual: 主线程真浏览器执行（流式时序为运行时行为），关键帧截图落 orchestration/screenshots/chat-streaming-v2.png，走查记录呼吸动画类名与完成静止
- [x] V3 reduced-motion 断言：全局压扁规则在位；morph 存在性探测 + reduced-motion 分支核验；azure-breathe 会随全局压扁规则失效
  - id: task-v3
  - boundary: 只读核验
  - verify: `cd /Users/linyixi/PaiSmart/frontend && grep -q "prefers-reduced-motion" src/styles/scss/global.scss && grep -q "prefers-reduced-motion: reduce" src/layouts/modules/global-header/index.vue && grep -q "azure-breathe" src/views/chat/modules/chat-message.vue && pnpm typecheck`
    - 运行目录：命令已自 cd
    - 成功判定：exit 0（三层降级路径代码在位）
    - 失败处理：补齐缺失降级路径后重验
- [x] V4 暗色 10 视图全量走查：切换暗色 → 10 视图 DOM 主题指标（html.dark、主色 0 113 227、容器 rgb(28,28,30)）+ usage-monitor/knowledge-base 截图 → 切回亮色
  - id: task-v4
  - depends-on: task-v1
  - boundary: 只读走查 + 截图
  - verify: manual: 主线程真浏览器执行（视觉走查），DOM 指标随走查记录，截图落 orchestration/screenshots/*-dark-v4.png
- [x] V5 favicon 标签页实视：浏览器标签页图标为新 Azure 瓷砖
  - id: task-v5
  - depends-on: task-v1
  - boundary: 只读走查
  - verify: `curl -s http://localhost:9527/favicon.svg | grep -c "0071E3" | grep -q "^1"`
    - 运行目录：任意
    - 成功判定：exit 0（服务内容为新瓷砖；标签页视觉随 V1 截图一并目检）
    - 失败处理：检查 index.html 引用后重验
- [x] PM 收口复审：五项结论 + 缺陷计数 + 迭代收敛/继续决策
  - id: task-closeout
  - depends-on: task-v2
  - boundary: 只读审查，产出唯一文件 `.spec/specs/2026-10-20_verify-ui-azure-closeout/orchestration/closeout-review.md`
  - verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_verify-ui-azure-closeout/orchestration/closeout-review.md && grep -q "收敛" /Users/linyixi/PaiSmart/.spec/specs/2026-10-20_verify-ui-azure-closeout/orchestration/closeout-review.md`
    - 运行目录：任意（绝对路径）
    - 成功判定：exit 0
    - 失败处理：补走查后重验

---

**当前进度**：由脚本计算，无需手动维护
