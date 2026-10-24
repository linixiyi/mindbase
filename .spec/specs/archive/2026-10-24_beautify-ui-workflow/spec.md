# UI 美化工作流链：spec 驱动 impeccable 八指令迭代 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：以本 spec 任务包为跟踪、以 ZCode 动态工作流（CreateWorkflow）为执行引擎，逐轮调用 impeccable 八指令（layout / typeset / colorize / bolder / quieter / animate / delight / overdrive）对 MindBase 前端做新一轮 UI 与图标美化。用户本轮明确授权结构可以重新设计，主题色锁定淡蓝（Azure #0071E3 家族，即 DESIGN.md「晴空玻璃」世界）；**用户 2026-10-24 追加美学 brief：苹果高级感 + 液体玻璃（Liquid Glass）**——材质层升级为液态玻璃语言（通透半透明与折射感、边缘高光、胶囊控件、玻璃浮层软影），在淡蓝一线的克制内实现。每轮按工作流复审反馈决定是否拉起下一轮，不限轮数，直至收敛。
- **目标用户**：开发者本人（日常自用）+ 作品集受众（面试官）
- **核心价值**：视觉与交互质量在既有 Azure 世界之上再上一档；过程可审计（每轮报告落 evidence/）、可回滚（集成分支）、设计与实现同源（DESIGN.md 随结构变化回写）

## 2. 假设与待确认

### 2.1 已确认事实
- 用户 2026-10-24 追加美学 brief「苹果高级感 + 液体玻璃（Liquid Glass）」：R1 工作流已按 brief-wins 原则经 AmendWorkflow 并入（勘察加液体玻璃专项盘点、工序 persona 与硬约束携带玻璃语言、复审增加美学判定与玻璃边界检查）；工程边界：同一视线最多一层 backdrop-filter、玻璃面文字对比 AA、禁 SVG 位移滤镜大面积铺、「阴影只属浮层」放宽为「玻璃本体允许软影与边缘高光，静态卡片仍零阴影」
- 基线门禁（2026-10-24 采集，分支创建后）：`pnpm typecheck` 绿（~20s）；`pnpm exec eslint .` 0 error / 31 warning（存量 warning）；`impeccable detect --json frontend/src` = `[]`（零 finding）
- 上一轮链遗留残留 1 处：`frontend/src/plugins/loading.ts:10` 回退值 `'#646cff'`（旧 Vite 紫，DESIGN.md 永久否决色）——本轮必须清零
- 前端 dev server 已由本轮拉起（localhost:9527，HTTP 200）；后端 8081 未运行，且 Redis/ES/Kafka/MinIO 均未启动 → 后端无法从 CLI 拉起，真机验证仅能覆盖未登录面（登录/注册页）
- 上一轮链（archive/2026-10-20_beautify-ui-closeout，autorun 5/5）已收口；本轮是其后的一轮全新全量美化（第四轮 Azure + R1–R3 的成果是本轮的起点与"不得无理由倒退"的基线）

### 2.2 关键假设
- 八指令按 layout→typeset→colorize→bolder→quieter→animate→delight→overdrive 串行执行、由勘察分工限定各自聚焦面，可在单轮工作流内完成而不互相覆灭（后道工序能看到前道产出摘要）
- 后端不可用不影响本轮改动面：纯前端样式/结构/组件，不触碰 API 契约与数据流

### 2.3 待确认问题
- [x] 收敛判据 → 独立复审员 converged=true（零 P0/P1 且无明确回归），或主会话判定连续两轮仅边际收益
- [x] 每轮产物 → 工作流返回报告 + 主会话回写 `evidence/round-N-report.md`

### 2.4 可选解释与取舍
- 当前选择：工作流内以确定性门禁（typecheck / eslint 0 error / detect 无 error 级 finding / 旧色残留 grep 零命中）+ 独立复审员代替真机全量走查 -> 后端不可用，登录后视图无法实页验证；此局限按 AGENTS.md 如实披露，登录页可达面由主会话真机抽查
- 当前选择：每轮一个独立工作流（而非单个超长循环工作流）-> 用户明确要求"根据工作流的反馈再拉起新的工作流"；每轮聚焦上一轮 nextRoundFocus，报告独立可审计

### 2.5 已证伪路径
- 子代理 bash 大输出直跑（stdout >256KB，如 cat 大 JSON / 整仓裸 diff / node_modules 全量 grep）会以 world.run 超限拒绝并中断整条工作流 -> 证伪依据：R1 首跑（dwfrun-8ecbee04）typeset 工序中断，stdout 1.27MB 超限；2026-10-24。对策：剩余工序 ask 织入输出纪律（tail/head 截断、分文件 diff、禁 cat 大 JSON）

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] R1 工作流：勘察分工 → 八指令串行执行 → 确定性门禁 → 独立复审 → 报告
- [x] `loading.ts` `#646cff` 残留清零（并入 R1 分工）
- [x] 按反馈拉起 R2+（不限轮数，直至收敛判据满足）——复审 R1 即 converged，零追加轮
- [x] 收敛后：DESIGN.md 与实现一致性核查（结构级变化逐节回写）

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 后端任何改动、API 契约变更；引用标注 DOM 结构 / 解析正则 / referenceMappings 数据流；构建与依赖变更；commit / push（用户未要求，AGENTS.md 硬规则）

## 4. 最小实现路径
- 最简单可行方案：每轮一个动态工作流（勘察 → 八指令 → 门禁 → 复审 → 报告），主会话读报告决定下一轮聚焦或宣布收敛
- 暂不引入：新依赖、新构建步骤、主题架构重构
- 不做的抽象/配置化：不做设计 token 体系重构（沿用既有 settings.ts + CSS 变量 + Uno 别名管线）

## 5. 技术决策
- 技术栈：Vue 3.5 + TS + Naive UI + UnoCSS（既有）；执行引擎 ZCode CreateWorkflow（子代理串行执行 impeccable 指令）；设计规范 DESIGN.md（Azure 晴空玻璃世界）
- 证据协议：evidence: runs（机器任务 verify 命令由 scripts/spec_verify.py 真实执行并记录 runs.jsonl；工作流轮次产物回写 `evidence/round-N-report.md`；纯判定任务写 `verify: manual: <理由>`）
- 本轮允许改动：`frontend/src/**`、`frontend/public/**`、`DESIGN.md`（结构变化回写）、本包文档
- 本轮不应触碰：backend、`.env*`、`frontend/package.json` 依赖、引用标注数据流
- Git integration branch：`spec/2026-10-24_beautify-ui-workflow`

### 5.1 验证策略
- 范围级别：package
- 变更对象：前端样式 / 结构 / 组件与 DESIGN.md
- 快速检查：`cd frontend && pnpm typecheck && pnpm exec eslint .`
- 集成检查：`impeccable detect --json frontend/src` 无 error 级 finding；`grep -rE "5856D6|5B5BE8|646cff" frontend/src` 零命中
- 全项目检查：适用外（纯前端视觉轮，发布门禁统一执行）
- 升级触发：若改动需要动构建 / 依赖 / API 契约，停下升级范围并记录理由

### 5.4 编排策略
- route: build
- 路由依据：实现类（执行段委派给 ZCode 动态工作流的子代理，每轮工作流 = 勘察 1 + 工序 8 + 门禁修理 ≤2 + 独立复审 1）
- 主会话保留：路由、验收、收敛判定、Git 授权；工作流永不执行 commit/push
- 委派包五字段由每轮工作流的分工 JSON 携带（goal=指令聚焦面 / scope=文件清单 / excluded=后端与数据流 / output=StepResult / verification=typecheck+eslint 自验 + 脚本终局门禁）

## 6. 成功标准与验证方式
- [x] R1 八指令全部执行且终局门禁四项全绿 -> verify: task-r1
- [x] 旧色残留清零（loading.ts #646cff 等）-> verify: task-r1（门禁第四项）
- [x] 每轮报告落盘 `evidence/round-N-report.md`（R1 先行）-> verify: task-r1-report
- [x] 收敛判定成文（converged 结论或收敛路径说明，写在最后一份轮报告）-> verify: manual: 由主会话依据各轮复审 verdict 汇总，写入末轮报告与完成摘要
- [x] DESIGN.md 与实现一致（结构级变化已回写）-> verify: task-design-sync

## 7. 风险与约束
- 后端不可用 → 登录后视图无真机证据：以 detect + diff 复审代替并在报告披露；登录页由主会话实页抽查
- 八指令串行中后道覆灭前道 → 勘察分工限定聚焦面；后道工序可见前道产出摘要；独立复审逐工序核对 diff
- 公开仓库 → 任何改动不得引入敏感值；本轮全程不 commit / 不 push（未获用户明确要求）
- 工作流子代理越界（试图改依赖/后端）→ ask 内硬约束 + persona 升级条款；主会话对升级问题逐一裁决
