# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- 主用户：开发者本人——日常自用与持续迭代，设计决策优先服务这个人的日常使用体验。
- 作品集受众：潜在雇主与面试官。他们通过代码、演示与文档评估工程能力，因此产品必须始终经得起"这是一家真实企业在用的系统"的审视。
- 产品内角色（模拟的企业场景）：企业普通员工（向知识库提问、查看问答历史）与企业管理员（管理知识库、用户、组织标签、邀请码、模型供应商、配额与用量）。

## Product Purpose

MindBase 是一个企业级 AI 知识库管理系统，围绕 RAG 工作流构建：上传文档后自动解析、分块、向量化并写入 Elasticsearch；用户用自然语言提问，系统基于知识库片段生成带引用标注的中文回答。

它是开发者的求职作品集项目，但按真实产品的标准打造：稳定性和可用性与工程展示同等重要。成功意味着两件事同时成立——开发者本人每天真的能用、好用；面试官看到的是可信的企业级工程深度（数据正确性、权限隔离、调用治理），而不是玩具 demo。

## Positioning

全链路企业级 RAG 工程深度，是同类浅层 RAG demo 无法如实复制的：混合检索（BM25 + KNN 双路召回、RRF 融合、可选重排）、父子检索（small-to-big）、可选上下文检索、强制引用标注与逐句校验、文档冲突检测与人工裁决、离线评测框架（Recall@K / MRR、LLM-as-a-judge、Cohen's kappa）、多租户权限过滤、模型调用治理（熔断、舱壁隔离、主备切换）、Kafka 全链路可靠投递（重试 + 死信队列）、分片断点续传上传、WebSocket 流式对话（主动中断 + 断线续传）。

## Operating Context

- 本地开发运行方式（AGENTS.md 工作约定，后续工作必须遵守）：后端在 IDE 中热部署运行于 localhost:8081，默认不要重启，改 Java 代码用 `mvn -q -DskipTests compile` 触发热加载；前端 Vite dev server 常驻 localhost:9527；浏览器请求经 Vite 代理，路径形如 `/proxy-default/...`。
- 本地基础设施：MySQL 8、Redis、Elasticsearch 8.10、Kafka、MinIO/RustFS。
- 界面语言：中文为主（zh-cn 主语言，en-us 备选）；回答与引用标注均为中文格式。
- 仓库公开在 GitHub：任何被提交的内容都会被发布；生产 IP、SSH 私钥、密钥等绝不进入被跟踪文件（AGENTS.md 硬规则）。
- 验证路径：UI 问题用真实浏览器打开 `http://localhost:9527/#/chat` 与 `/#/chat-history`，查网络请求、响应体与控制台；数据类问题需同时核对浏览器、后端与数据库三层。

## Capabilities and Constraints

已确认功能：

- 知识库管理：分片上传、断点续传、秒传（MD5）；PDF（LiteParse 解析，保留页码，可选 OCR）、Office、文本格式；文档公开/私有 + 组织标签细粒度权限；处理链路走 Kafka，失败自动重试并进入死信队列。
- AI 问答：WebSocket 流式输出，ReAct 风格工具调用循环；回答强制基于检索片段，句末带 `(来源#编号: 文件名 | 第X页)` 引用标注；可选引用逐句校验；检索/重排/工具调用共用请求级时间预算。
- 检索：BM25 + KNN 双路召回、RRF 融合、可选 gte-rerank 重排；父子检索（命中小分块扩展相邻大分块，引用仍指向命中分块）；可选上下文检索（入库时 LLM 为分块生成上下文说明）。
- 企业能力：多租户（组织标签 + 用户/组织关系驱动过滤）；邀请码注册（开放/邀请制/关闭可配）；Redis 令牌桶限流（登录、注册、聊天、LLM、Embedding）；模型治理（按供应商熔断、舱壁隔离、指数退避、LLM 主备切换）；Token 配额与用量统计（按总量或按日重置），可选微信支付充值。
- 管理与可靠性（2026-10-24 PM 链新增）：管理端四治理入口（文档冲突裁决、知识索引重建/回滚、离线评测触发、文件处理死信队列人工重放）；聊天失败恢复（分类错误卡片 + 重试本问 + 失败回合持久化）；用户禁用/启用（防自锁、登录拒绝）；管理端聊天记录服务端分页；「记住我」只存用户名（密码永不落盘）；同 MD5 多用户文档删除隔离。
- 文档冲突检测：向量化完成后异步扫描相似但数字/版本号/日期不一致的分块对，管理员后台裁决保留哪一篇，可选 LLM 二次确认。
- 离线 RAG 评测：检索看 Recall@K / MRR，生成看 LLM-as-a-judge，支持人工抽检与评委一致率（含 Cohen's kappa）。
- 前端页面：chat、chat-history、knowledge-base、invite-code、model-provider、org-tag、personal-center、recharge、recharge-manage、usage-monitor、user。

技术约束：

- 前端：Vue 3.5 + TypeScript + Vite 6；Naive UI + UnoCSS + SCSS；Pinia + Vue Router；基于 SoybeanAdmin（`@sa/*` workspace 包、elegant-router、`sa` CLI）；pnpm workspace。声明了 `color-scheme: light dark`，亮/暗主题可用。
- 后端：Spring Boot 3.4.2（Java 17），Spring Data JPA / Security / WebSocket（WebFlux）。
- 引用映射（reference mappings）必须贯穿持久化与历史渲染，改动聊天历史存储时不得回归引用预览路径（AGENTS.md）。
- Redis 只做短生命周期聊天上下文与会话状态；持久历史必须在 MySQL。
- 多租户过滤可能由用户身份、组织标签、角色或显式查询参数驱动，改查询/管理视图时必须核实过滤来源。

## Brand Commitments

- 产品权威名称：MindBase（用户确认）。仓库目录名 PaiSmart 不是产品名，不得进入任何产品界面或文案。
- 应用标题：MindBase（zh-cn / en-us locale 中的 `title`）；站点图标 `frontend/public/favicon.svg`。
- 语言承诺：中文是唯一界面语言（2026-10-24 PM 裁决：收敛为仅中文，语言切换入口保持关闭；i18n 基础设施保留供未来需要时启用，启用前不承诺英文）。

## Evidence on Hand

- `README.md`：完整的功能与架构文档（中文）。
- `src/main/resources/eval/mindbase-eval-v1.jsonl`：内置 50 条评测集。
- `docs/eval/reports/`：历史评测报告。
- `docs/databases/`：DDL 与迁移脚本。
- `docs/interview.md`、`docs/resume.md`、`docs/interview-upgrade-plan.md`：开发者个人求职材料（面试问答与简历表述），可作为功能声明的交叉证据，但不是产品文档。
- 本地可运行环境：前端 localhost:9527，后端 localhost:8081。
- 必须如实说明的缺失：没有真实客户、用户证言、生产部署数据或商业化数据——任何设计与文案不得虚构这些。

## Product Principles

1. 真实可用优先：这是开发者每天在用的工具，任何界面改动都要在真实数据与真实流程下端到端可用，不为展示效果牺牲可用性。
2. 企业级可信：稳定性、权限与治理体现在行为里；给面试官演示时不需要任何"这里其实没做完"的补丁说明。
3. 工程深度可感知：检索质量、引用证据、可靠性机制应让使用者直接看到——引用标注贯穿历史渲染、冲突裁决、用量与配额可见。
4. 中文优先：中文是唯一界面语言（不提供半成品英文；i18n 基础设施保留但切换入口关闭）。
5. 最小端到端修改：遵循 AGENTS.md——优先最小、可验证的端到端修复，不做投机性重构；改了行为就要在真实页面验证。
