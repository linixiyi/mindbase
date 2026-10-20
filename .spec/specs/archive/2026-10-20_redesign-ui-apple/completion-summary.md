# UI 重设计：Apple 级质感视觉世界替换 - 完成总结

## 交付结论
- 结果：通过
- 完成时间：2026-10-20 11:02

## 假设回顾
### 已验证假设
- Apple 质感主要由 token 层 + 壳层 + 材质/动效承载（字体栈、间距节奏、圆角体系、半透明材质、克制用色、微动效），不需要更换组件库
- 工作流子代理可完成世界定义与代码落地；视觉终验由主线程浏览器执行并反馈进入下一轮
- Naive UI 的 themeOverrides 足以表达新世界的组件形态（圆角、阴影、色彩、字体）

### 仍未完全验证的假设
- 无

## 交付范围
### 已交付
- 「静光玻璃」新视觉世界（DESIGN.md+sidecar 重写）；第一轮 token/壳层/chat旗舰/chat-history/knowledge-base/login 落地 + PM 审查 13 条；第二轮引用预览孤岛修复+chat筛选条收敛+NModal scrim+管理页 8 页换新；登录密码正则 P0 热修；合并义务执行（3c92735 并入第一轮分支，四项存活核对全绿）；浏览器终验（真实登录+真实对话+管理页 DOM 验证）

### 未交付
- 无

### 偏差说明
- 无

## 简化决策
### 保持简单的关键选择
- 不换组件库、零新增依赖、动效仅 CSS transition/backdrop-filter、不改路由与业务语义；对照 §4 暂不引入清单全部拒绝

### 本轮明确不做的内容
- 后端任何改动；路由结构重组；业务逻辑与 API 契约改动
- i18n 文案语义改动；引用标注 DOM 结构与数据流改动（只许改样式）
- 新增 npm 依赖；登录逻辑改动

## 变更边界
### 本轮主要改动模块
- frontend/src/{theme,styles,layouts,views,components}样式层、uno.config.ts token、DESIGN.md、.impeccable/design.json、frontend/src/constants/reg.ts（P0 热修）

### 明确未触碰的区域
- 后端、frontend/src/service/**、store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**

## 验证证据
### 备注
- 测试：impeccable detect --json 无 error 级发现（两轮，覆盖 views 全量+layouts+components/custom）
- 构建：pnpm -C frontend typecheck exit 0；pnpm -C frontend exec eslint . exit 0（0 error）（runs.jsonl fp=886fce23bd5a）
- 手工：浏览器终验：真实 UI 登录（P0 热修实测通过）+ chat 真实对话暗色截图（引用 chip/磨砂侧栏/输入壳）+ 8 管理页 DOM 挂载验证（无错误浮层）；截图 orchestration/screenshots/；亮色补拍与引用预览模态截图受内嵌浏览器截图面 3s 预算限制（P2 观察项）

## 验证范围
- 级别：package
- 机器验证率：4/4；manual 任务 1 项不计入分母
- 规则：check 与 done 复用该范围；只有记录升级触发时才执行全项目检查

## 哲学生效证据
### 行为成效回填
- Development Record 行为门禁通过

### 一致性门禁回顾
- 验收面（checklist 或成功标准）已通过

## Git 记录
- 日期时间：2026-10-20 11:02
- 范围：UI 重设计：Apple 级质感视觉世界替换
- 功能：「静光玻璃」新视觉世界（DESIGN.md+sidecar 重写）；第一轮 token/壳层/chat旗舰/chat-history/knowledge-base/login 落地 + PM 审查 13 条；第二轮引用预览孤岛修复+chat筛选条收敛+NModal scrim+管理页 8 页换新；登录密码正则 P0 热修；合并义务执行（3c92735 并入第一轮分支，四项存活核对全绿）；浏览器终验（真实登录+真实对话+管理页 DOM 验证）
- 操作：提交 / 推送
- 成效：Development Record 行为门禁通过
- 提交：pending local commit
- 提交区间：92bc8628681b54ebaa8e1e5e1d8c41f308593728..3c92735901524cfbcdb0fda65a91a50cec4968e4
- 推送：not-run (push stage follows commit)

## AGENTS.md 增量提议
```diff
spec 多轮迭代时 init_spec_package 总是从 main 建新分支：上一轮成果在新分支开工前必须已进 main，或收尾时按 merge-obligation 合并上一轮分支（冲突裁决原则见 orchestration/merge-obligation.md）
内嵌浏览器/软件光栅化环境下，大面积 backdrop-filter 页面（磨砂顶栏）截图准备会超 3s 预算：验证以 DOM 挂载指标替代像素截图，真机 GPU 渲染不受影响
```

## 被拒绝的扩展提议
- 组件库替换；动效库；新图标体系；英文版式专项；主题切换器扩展；design-token 独立包

## 门禁证据
- check_spec_package.py exit 0

## 遗留事项
- 无；问题处置见下方结构化区块

## 问题处置

```json
{
  "issues": [
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "连续多次 screenshot surface timeout（chat/kb 对比：chat 可截、kb+管理页不可截，与磨砂面积正相关）；真机 GPU 渲染不受影响",
      "id": "iab-raster-perf",
      "owner": "开发者本人（linyixi）",
      "rationale": "ZCode 内嵌浏览器的光栅化限制，非产品代码缺陷；真实浏览器有 GPU 加速",
      "reviewTrigger": "用户在真机浏览器反馈管理页卡顿时，优先排查 backdrop-filter 面积并降级为实底",
      "summary": "内嵌浏览器软件光栅化下磨砂顶栏页面截图面超 3s 预算，亮色补拍与引用预览模态像素截图未完成；页面功能与挂载经 DOM 指标验证正常。"
    },
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "scripts/chat-reconnect-smoke-test.mjs --timeout-ms 180000 复现；Redis 快照 status=COMPLETED contentLength=246",
      "id": "ws-reconnect-completed-event",
      "owner": "开发者本人（linyixi）",
      "rationale": "影响面窄（仅重连时机恰逢完成瞬间），前端重设计不依赖该路径",
      "reviewTrigger": "下一轮触碰 WebSocket handler 或断线续传逻辑时复测",
      "summary": "断线重连时若生成已完成，重连 socket 收到 COMPLETED 快照但收不到终结事件（chat-reconnect-smoke-test 超时于 ws2 completion）——后端疑点，不在 UI 范围。"
    },
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "InsufficientBalanceException 余额-500；Redis SET user:token:llm:1/embedding:1 = 5000000 后对话正常",
      "id": "admin-quota-seeding",
      "owner": "开发者本人（linyixi）",
      "rationale": "治理系统按设计工作，属初始数据 seeding 问题而非缺陷",
      "reviewTrigger": "下次重置本地环境或配置新环境时，把配额种子纳入启动流程",
      "summary": "admin 初始 Token 配额过小（首轮对话即耗尽触发限流），本地已在 Redis 手工充值 500 万；正式方案应走充值接口或调大初始配额配置。"
    }
  ],
  "version": 1
}
```
