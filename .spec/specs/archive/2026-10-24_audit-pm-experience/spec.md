# PM 体验升级链 R1：LLM 接入补证 + 真人体验模拟 + PM 审计 backlog - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：完成链计划 F1–F3——LLM 本地代理接入补证（F1）、真人模拟使用体验日志（F2）、五位 PM 分视角审计产出分级 backlog（F3，`.spec/plans/pm-experience-upgrade-backlog.md`）
- **目标用户**：开发者本人 + 作品集受众（面试官）
- **核心价值**：从"能用的工程作品"到"经得起产品经理审视的产品"——体验债显性化、分级、可执行

## 2. 假设与待确认
### 2.1 已确认事实
- LLM 代理（localhost:20128）已配置（.env 三键齐、key 与用户提供一致）、后端已重启、**E2E 已恢复**：真实流式回答 + 「检索知识库→已完成」工具链 + 引用标注（2026-10-24 16:56 实测，见 evidence/round-1-sim.md）
- 模型 `cbcn/glm-5.3-flash` 为推理模型（思考计入 max_tokens，应用已有空输出重试翻倍机制，yml:305）
- 体验基线：主会话两轮浏览器会话（登录/聊天/历史/知识库/404/暗色 token 断言）
- DB model_provider_configs 表为空（供应商走 env 默认）
- 工具限制：内嵌浏览器无文件选择器（上传不可实操）、rAF/合成节流（动画不可实拍）
### 2.2 关键假设
- 五视角（上手/问答/后台/文案架构/可靠性）覆盖产品体验主要面；backlog 分级判据可被 F4/F5 执行
### 2.3 待确认问题
- [x] backlog 产出形式 → `.spec/plans/pm-experience-upgrade-backlog.md`，每项含验收判据
### 2.4 可选解释与取舍
- 当前选择：PM 审计用会话模型（GLM-5.3，规划分工）多代理并行 → 用户明确的分工约定；实现轮（F4+）才用 Flash
## 3. 功能范围
### 3.1 核心功能（MVP）
- [x] F1 LLM 接入恢复 E2E（链前完成，本轮补证落盘）
- [x] F2 体验日志（主会话真人走查汇总落盘）
- [x] F3 PM 五视角审计 + backlog 产出（工作流）
### 3.3 不在范围内
- 任何代码改动（实现属 F4/F5 轮）；commit/push（未授权）
## 4. 最小实现路径
- 日志落盘 → 审计工作流（读文档+代码+日志）→ 汇总分级 → backlog 文件
## 5. 技术决策
- 技术栈：ZCode 动态工作流（规划模型=会话 GLM-5.3，per 分工约定）
- 证据协议：evidence: manual（审计轮无机器验证命令；backlog 存在性用 test -s）
- 本轮允许改动：`.spec/plans/pm-experience-upgrade-backlog.md`、本包文档
- 本轮不应触碰：一切产品代码
- Git integration branch：`spec/2026-10-24_audit-pm-experience`
### 5.4 编排策略
- route: build
- 执行段：动态工作流五镜头并行 + 汇总代理（全部规划模型，不写产品代码，仅汇总代理写 backlog 文件）
## 6. 成功标准与验证方式
- [x] F1 补证 -> verify: manual（evidence/round-1-sim.md 含 E2E 实录）
- [x] F2 体验日志 -> verify: `test -s /Users/linyixi/PaiSmart/.spec/specs/2026-10-24_audit-pm-experience/evidence/round-1-sim.md`
- [x] F3 backlog -> verify: `test -s /Users/linyixi/PaiSmart/.spec/plans/pm-experience-upgrade-backlog.md /Users/linyixi/PaiSmart/.spec/plans/pm-experience-upgrade-backlog.md`
## 7. 风险与约束
- 审计代理虚构不存在功能 → 要求 evidence 引到文件/行为，读代码核实
- backlog 过宽 → P0 上限 5 项，聚焦阻断核心价值/数据风险
