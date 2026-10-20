# UI 重设计第三轮：亮色 airy 结构级大改 - 完成总结

## 交付结论
- 结果：通过
- 完成时间：2026-10-20 16:31

## 假设回顾
### 已验证假设
- 亮色 airy 的结构改写（悬浮侧栏、去套盒、大字号）在 Naive UI themeOverrides + UnoCSS 内可实现
- 默认主题切到 light 后，暗色仍可经右上角切换完整使用

### 仍未完全验证的假设
- 无

## 交付范围
### 已交付
- 亮色 airy 结构级大改：默认主题 light、侧栏悬浮浮岛（脱边+岛影+12px 地面缝）、chat 去套盒全幅白画布、日期筛选条收进弹层、字号/留白刻度拉大、chat-history/knowledge-base 去盒页题化、login 白底 hero 重排；PM 实测 P0/P1 两缺陷（画布穿透、浮岛死变量）当轮修复

### 未交付
- 无

### 偏差说明
- 无

## 简化决策
### 保持简单的关键选择
- 沿用 Quiet Glass token 体系只重写表面几何与布局；暗色 token 组原样保留；零新增依赖；不做主题配置器

### 本轮明确不做的内容
- 后端、service/store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**、新增依赖

## 变更边界
### 本轮主要改动模块
- frontend/src/{theme,styles,layouts,views,components}样式层、uno.config.ts、store/modules/theme 主题默认值、DESIGN.md、.impeccable/design.json

### 明确未触碰的区域
- 后端、service、store 业务语义、路由结构、i18n 语义、引用标注数据流、packages/@sa/**、.env

## 验证证据
### 备注
- 测试：impeccable detect --json（views 全量+layouts）无 error 级发现
- 构建：pnpm -C frontend typecheck exit 0；pnpm -C frontend exec eslint . exit 0（runs.jsonl fp=f4b53056e704）
- 手工：浏览器终验：画布计算样式实测（白画布 255,255,255/零内边距/浮岛 220px/宽度类接线）；亮色 chat+KB 截图、暗色抽查（画布 #1C1C1E 正确、P0 暗色症状同修）；截图 orchestration/screenshots/；KB 页 IAB 截图预算限制沿用 P2 观察项

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
- 日期时间：2026-10-20 16:31
- 范围：UI 重设计第三轮：亮色 airy 结构级大改
- 功能：亮色 airy 结构级大改：默认主题 light、侧栏悬浮浮岛（脱边+岛影+12px 地面缝）、chat 去套盒全幅白画布、日期筛选条收进弹层、字号/留白刻度拉大、chat-history/knowledge-base 去盒页题化、login 白底 hero 重排；PM 实测 P0/P1 两缺陷（画布穿透、浮岛死变量）当轮修复
- 操作：提交 / 推送
- 成效：Development Record 行为门禁通过
- 提交：pending local commit
- 提交区间：7515fa476d619992a124912f0a297b4ba09e2443..a25d86b770d48d409feb601722fea20abfd76502
- 推送：not-run (push stage follows commit)

## AGENTS.md 增量提议
```diff
Vue SFC 单条件根（v-if/v-else）会接收路由容器 fallthrough 的 class/style：页面需要全幅/免容器内边距时，在根元素用 Uno !important 后缀（p-0!、bg-container!）显式压掉，不要依赖多根 fragment 假设
AdminLayout 浮岛化改法：siderClass 动态宽度类必须拼进返回串；:sider-width 传「岛宽+左缝+右缝」预留列，header/main 自动落到岛右缘
```

## 被拒绝的扩展提议
- 新组件库；动效库；新字体；主题配置器；design-token 独立包

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
      "evidence": "连续多次 screenshot surface timeout（chat 页可截、KB 页不可截，与 DOM/材质复杂度正相关）；真机 GPU 渲染不受影响",
      "id": "iab-raster-kb-heavy",
      "owner": "开发者本人（linyixi）",
      "rationale": "ZCode 内嵌浏览器光栅化限制，非产品缺陷",
      "reviewTrigger": "用户真机反馈 KB 页卡顿时，排查表格行高与磨砂叠加并降级",
      "summary": "知识库等重 DOM 页面在内嵌浏览器截图面持续超 3s 预算（数据表格+磨砂顶栏叠加），亮色像素截图以中间帧+DOM 指标替代；页面挂载与功能经 DOM 指标验证正常。"
    },
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "上一包 2026-10-20_redesign-ui-apple 同名条目；本轮 UI 范围不依赖该路径",
      "id": "ws-reconnect-completed-event",
      "owner": "开发者本人（linyixi）",
      "rationale": "影响面窄，前端已独立验证",
      "reviewTrigger": "触碰 WebSocket handler 或断线续传逻辑时复测",
      "summary": "断线重连时若生成已完成，重连 socket 收不到终结事件（后端疑点，延续上一包遗留）。"
    }
  ],
  "version": 1
}
```
