# PM 体验升级链计划（2026-10-24）

用户 brief：模拟真人使用体验；以产品经理视角审视项目，进行功能升级与完善、使用体验优化。分工约定：GLM-5.3 只做规划，GLM-5.3-Flash 写代码。链模式：spec:autorun，逐包推进直至全部特性勾选。

- [x] F1 LLM 本地代理接入并恢复端到端真实问答（含引用标注）
- [x] F2 真人模拟使用体验：核心旅程走查并产出体验日志（登录/问答/历史/知识库/管理页）
- [x] F3 PM 审视：多视角审计产出分级 backlog（P0–P2，功能升级 + 体验优化，含验收判据）
- [x] F4 backlog P0 项全部落地（Flash 实现 + 四门禁 + 真机验证）
- [x] F5 backlog P1 项落地或记录明确排期/取舍理由
- [x] F6 链收口：文档与 DESIGN.md 同步（核查结论：F4/F5 未引入新设计词汇，新视图全部复用 §11–§18 既有模式，DESIGN.md 无需变更）、收敛复审、完成摘要

## 约定

- 特性文本自首个执行轮起冻结；细化内容落在 `.spec/plans/pm-experience-upgrade-backlog.md`（F3 产出），不回改本文件特性行
- 实现轮执行引擎：ZCode 动态工作流，子代理模型 `account:bigmodel-start-plan/GLM-5.3-Flash`；规划/审计轮用会话模型（GLM-5.3）
- 门禁沿用：typecheck / eslint 0 error / impeccable detect 无 error 级 / 旧色残留零命中；涉后端加 `mvn -q -DskipTests compile`
