# UI 升级：impeccable 设计体系落地与界面打磨 - 完成总结

## 交付结论
- 结果：通过
- 完成时间：2026-10-19 23:39

## 假设回顾
### 已验证假设
- SoyadminAdmin 的主题设置（`src/theme/settings.ts`、uno.config.ts、Naive UI themeOverrides）足以承载本轮精修，不需要引入新样式基础设施
- 工作流子代理在没有浏览器的条件下可以完成代码层 critique 与 polish；视觉验收由主线程浏览器截图兜底
- 界面文案（i18n）只做必要的微调或不动，文案体系不重构

### 仍未完全验证的假设
- 无

## 交付范围
### 已交付
- DESIGN.md 设计体系规范 + sidecar（北极星「静谧指挥台」）；三页（chat/chat-history/knowledge-base）与全局壳层共 36 项界面修复；前端依赖环境修复（shamefullyHoist、@iconify/utils、@playwright/test、allowBuilds）；浏览器亮暗双主题目检通过（6 张截图留证）

### 未交付
- 无

### 偏差说明
- 无

## 简化决策
### 保持简单的关键选择
- 在现有 SoybeanAdmin/Naive UI 体系内 token 级精修，不换组件库、不引入动效库与新字体、不重构 @sa/* 包；对照 §4「暂不引入」清单，以上扩展点全部拒绝，无新增

### 本轮明确不做的内容
- 后端任何改动（Java、配置、数据库）
- 路由结构、页面信息架构重组
- 业务逻辑与 API 契约改动（含聊天引用映射链路——只保证不回归）
- 新字体、新图标体系、动效库引入
- 登录页之外的新增页面
- Git push（本轮只做包内提交，不合并不推送）

## 变更边界
### 本轮主要改动模块
- frontend/src/views/{chat,chat-history,knowledge-base}、frontend/src/layouts、frontend/src/theme、frontend/src/styles/css/global.css、frontend/{package.json,pnpm-lock.yaml,pnpm-workspace.yaml}、仓库根 DESIGN.md 与 .impeccable/design.json

### 明确未触碰的区域
- 后端 src/main/java/**、frontend/src/service/**、frontend/src/store/** 业务语义、i18n 文案语义、路由结构、聊天引用映射数据流

## 验证证据
### 备注
- 测试：impeccable detect --json（views/chat、views/chat-history、views/knowledge-base、layouts）无 error 级发现
- 构建：pnpm -C frontend typecheck exit 0（runs.jsonl fp=6dbabefe257d）；pnpm install --frozen-lockfile exit 0
- 手工：浏览器目检 6 张截图（亮/暗 × 三页）：orchestration/screenshots/；目检发现并修复 body 裸文本继承 CanvasText 白色问题后复验通过

## 验证范围
- 级别：package
- 机器验证率：5/5；manual 任务 1 项不计入分母
- 规则：check 与 done 复用该范围；只有记录升级触发时才执行全项目检查

## 哲学生效证据
### 行为成效回填
- Development Record 行为门禁通过

### 一致性门禁回顾
- 验收面（checklist 或成功标准）已通过

## Git 记录
- 日期时间：2026-10-19 23:39
- 范围：UI 升级：impeccable 设计体系落地与界面打磨
- 功能：DESIGN.md 设计体系规范 + sidecar（北极星「静谧指挥台」）；三页（chat/chat-history/knowledge-base）与全局壳层共 36 项界面修复；前端依赖环境修复（shamefullyHoist、@iconify/utils、@playwright/test、allowBuilds）；浏览器亮暗双主题目检通过（6 张截图留证）
- 操作：提交 / 推送
- 成效：Development Record 行为门禁通过
- 提交：pending local commit
- 提交区间：92bc8628681b54ebaa8e1e5e1d8c41f308593728..92bc8628681b54ebaa8e1e5e1d8c41f308593728
- 推送：not-run (push stage follows commit)

## AGENTS.md 增量提议
```diff
pnpm 12 不再读 .npmrc 的 shamefully-hoist 等包管理器配置，需写在 frontend/pnpm-workspace.yaml；allowBuilds 四项需真实布尔值，占位符文本会使 pnpm install --frozen-lockfile 以 ERR_PNPM_IGNORED_BUILDS 退出
本仓库 body 曾未设置显式文字色，裸文本继承 UA CanvasText（系统暗色时为白色）；global.css 的 body color 兜底已加，新增裸文本元素仍需注意
```

## 被拒绝的扩展提议
- 动效编排（仅保留 CSS transition 微调）；新字体栈；组件库替换；设计 token 独立 npm 包；主题可视化配置器；@sa/* 重构

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
      "dependency": "用户本机的 MindBase 后端（IDE 热部署）恢复运行并完成登录",
      "disposition": "external_blocked",
      "evidence": "curl 8081 exit 7、lsof 无 Java 监听；orchestration/screenshots/ 六张截图为无数据态",
      "id": "kb-citation-runtime-visual",
      "owner": "开发者本人（linyixi）",
      "retryTrigger": "下次真实后端可用、进入 #/chat 聊天页看到带引用标注的回答时，补一轮带数据目检",
      "summary": "带真实数据的引用标注渲染目检未完成：真实后端 8081 未运行（按 AGENTS.md 不代启后端），mock 桩只覆盖守卫与错误/空态。改动仅 token 置换（reference-preview-page.vue 文字/背景色）、未动 DOM 与数据流，已有 typecheck + 编译产物核查 + Vite 在线编译 200 覆盖。"
    },
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "DESIGN.md「已知例外」已记录条目与收敛方向（替换为壳层分隔级细影或去影）",
      "id": "materials-shadow-2xl",
      "owner": "开发者本人（linyixi）",
      "rationale": "边界纪律优先于单项收敛：改 @sa/* 包会放大回归面且与「最小端到端修改」原则冲突",
      "reviewTrigger": "下一轮涉及 packages/@sa/* 的包被立项时，把该条目并入其范围",
      "summary": "packages/materials admin-layout 浮动侧栏 shadow-2xl 静止重影未收敛：@sa/* 包在本轮 spec.md §5 明确列为不应触碰边界。"
    },
    {
      "actionable": true,
      "disposition": "accepted_risk",
      "evidence": "settings.ts:35-39 注记；DESIGN.md 阴影词汇表已同步死 token 说明",
      "id": "settings-dead-shadow-tokens",
      "owner": "开发者本人（linyixi）",
      "rationale": "两枚 token 是零成本死代码，注记已消除误导；物理删除需连动类型文件，超出本轮边界",
      "reviewTrigger": "下次编辑 frontend/src/typings/app.d.ts 时顺手删除两枚 token 并同步 DESIGN.md",
      "summary": "settings.ts 两枚死阴影 token（header/sider box-shadow）仅注记死因而未物理删除：ThemeSettingTokenBoxShadow 类型必填三键，删键即类型错误，src/typings 在本轮边界外。"
    }
  ],
  "version": 1
}
```
