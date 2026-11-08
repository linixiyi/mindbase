# P0 隔离与故障语义修复 - 项目范围

<!-- spec-format: 2 -->

## 1. 问题定义
- **项目目标**：修复四个会在多租户与演示场景暴露的 P0 缺陷——跨租户文件内容寻址冲突、refresh token 不可撤销、聊天渲染存储型 XSS、检索失败与"没有资料"共用一个出口。
- **目标用户**：项目的作者与评审者；部署到多租户环境后的知识库管理员与普通用户。
- **核心价值**：让"企业级多租户隔离"和"可验证的引用可信度"从声称变成可回归测试的事实。
- **Literal ask**: "根据 /spec 把要改的写成包，我在 zcode 让里面的 ai 来改"
- **Stated purpose**: 产出一个可交给执行端（ZCode）独立实施的修复包，执行端不需要本会话的上下文。
- **Inferred outcome**: 四个 P0 各有自动化回归测试，执行端只需跑 verify 命令即可证明完成；confidence: high; evidence: 本会话审计 `.runtime/research/01-rag-backend.md`、`02-platform-ops.md`、`03-frontend-ux.md`，关键行已在本文件 §2.1 复核。
- **Constraints and exclusions**: Java 17 + Spring Boot 3.4 现状；允许改数据库 schema 与迁移脚本；不改变任何现有 REST 契约路径；不引入新中间件；不重构目录。
- **Trade-off preference**: quality（正确性与可回归性优先，接受一次性迁移成本）

## 2. 假设与待确认

### 2.1 已确认事实
- F1 跨租户写冲突（后端，已复核）：合并对象键 `UploadService.java:577` `"merged/" + fileMd5` 无租户；ES 文档 ID `VectorizationService.java:164` `fileMd5 + "_" + chunkId` 无租户；向量表唯一键 `model/DocumentVector.java:17` `(file_md5, chunk_id)` 无租户；每个处理任务先全局清理 `FileProcessingConsumer.java:91` → `DocumentService.java:332-336` → `ElasticsearchService.java:81-87`（term `fileMd5`）+ `DocumentVectorRepository.java:35-36`（`DELETE ... WHERE file_md5 = ?1`）；重试取全库第一条 `DocumentService.java:340-357`；上传去重是 per-(md5,userId)（`UploadService.java:473`、`:825`），**不阻止**第二个用户上传同字节文件。
- F2 令牌生命周期（后端，已复核）：`SecurityConfig.java:45-78` 未放行 `/api/v1/auth/**`，而 `AuthController.java:14` 的 `@RequestMapping` 是 `/api/v1/auth`、`:24` 是 `/refreshToken`；`AuthController.java:38-52` 换发后不失效旧 refresh；`TokenCacheService.java:61-75` 只写缓存，全仓无删除 `jwt:refresh:` 的代码（`:176` 删 access、`:204` 删 userTokenKey）；`JwtUtils.java:33` refresh TTL 7 天，`:248` 另有隐式刷新入口。
- F3 渲染安全（前端，已复核）：`chat-message.vue:472` 直接渲染 `<VueMarkdownIt :content>`；所依赖的 markdown 渲染开启了 HTML 且无消毒插件；`chat-message.vue:151-175` 的 `createSourceLink` 用字符串拼接把文件名写进 `data-file-id` 与标签文本；全仓无 DOMPurify、无 CSP；`chat-history/index.vue:163-164` 复用同一组件渲染管理员问答记录。
- F4 空结果语义（后端，已复核）：权限解析异常 `HybridSearchService.java:94-109`、text-only 兜底异常 `:282-288`、rerank 阈值全滤 `:163-195` 三种失败都返回空列表，`AgentToolRegistry.java:271-273` 统一输出"未检索到相关知识库片段。"，提示词规则（`application.yml:430-439`）把"零片段"映射为"暂无相关资料"。阈值 0.1 与正确分块得分 0.14~0.19 只差 0.04（`application.yml:348-351`）。
- F5 测试命令可用：`./mvnw -q -B -Dtest=RetrievalMetricsTest test` 已实测 exit 0，纯 Mockito 单测不需要 ES/Kafka/MinIO。`pom.xml` 含 h2 与 spring-security-test，可用于 @DataJpaTest / MockMvc 切片。
- F6 前端测试现状：`frontend/package.json` devDependencies 含 `@playwright/test`，**不含** vitest；无 vitest 配置文件；现有 GitHub Actions 前端 job 只跑 typecheck/lint/build（仅记录现状，本包所有验证走本地命令，不依赖 Actions 额度）。
- F7 迁移脚本约定：`docs/databases/migrations/` 下按 `YYYY-MM-DD-主题.sql` 命名，历史脚本人工执行；当前无 Flyway（本轮不引入）。
- F8 现有安全修复为真：`ConversationService.java:192` 的 `validateConversationOwner` 已在六处调用；`SecurityConfig.java:59-60` 已收窄 OCR 回调。本包不重复这些工作。

### 2.2 关键假设
- A1 | 隔离最小维度取上传者 `userId` | kind: system | evidence: F1 中上传去重与 ES 文档均已有 `userId` 字段（`EsDocument.java:31`） | CONFIRMED | if false: 需改为组织维度并同步改权限过滤
- A2 | 历史数据存在跨用户同 MD5 记录 | kind: system | evidence: 去重按 (md5,userId)，同字节文件在多用户间必然重复上传 | UNVERIFIED | if false: 迁移脚本的 dry-run 输出为空即可跳过修复分支
- A3 | refresh 端点可改为匿名可达，仅凭 refresh 自证 | kind: system | evidence: `AuthController.java:36` 已做 `validateRefreshToken`（含 Redis 校验） | CONFIRMED | if false: 保留 access 保护则轮换无意义
- A4 | 前端可新增 vitest 依赖 | kind: external | evidence: F6，`frontend/pnpm-lock.yaml` 存在，本地 `pnpm install` 可用 | UNVERIFIED | if false: 改用已有 `tsx` 跑纯函数断言脚本，verify 命令同步替换

### 2.3 待确认问题
- [x] 历史数据是否需要一次性修复脚本？→ 结论：task_0001 交付迁移脚本 + 只读核对 SQL；执行端先跑核对语句，无输出则跳过修复，不阻塞
- [x] refresh 轮换是否允许新增 Redis key 前缀 `jwt:refresh:family:`？→ 结论：允许，仅新增 key，不改现有 key 语义

### 2.4 可选解释与取舍
- 当前选择：先修隔离与安全，再动评测与 RAG 质量 → 理由：前三项是"会给错误答案/泄露数据"的确定性缺陷，后两项是度量与体验，先让地基可信
- 当前选择：向量表唯一键直接改 `(user_id, file_md5, chunk_id)` 而不是新增影子表 → 理由：单实例、数据量小、可停机迁移，A2 失败时成本也只是空跑一次迁移

### 2.5 已证伪路径
- 无（暂无证伪记录）

<!-- spec:external-facts -->
### 2.6 外部事实
- 无（本轮不依赖外部版本、定价或 API 行为；前端库为已锁定版本）

## 3. 功能范围

### 3.1 核心功能（MVP）
- [x] 跨租户文件隔离：合并对象、ES 文档、向量表、清理与重试全部带上传者维度
- [x] refresh token 轮换与撤销：过期后可用、换发即失效、logout-all 生效
- [x] 聊天渲染安全：模型输出与文件名不再能变成活 DOM
- [x] 空结果语义：区分无权限命中 / 召回失败 / 阈值过滤，阈值全滤时降级返回 RRF 结果

### 3.2 扩展功能
- 无

### 3.3 不在范围内
- 前端交互问题（生成中按 Enter 触发停止、双历史加载器、流式 O(n²) 渲染）
- 可观测性（Prometheus、traceId 对齐）
- 评测体系改造、RAG 运行时质量、在线评测与收口：本轮之后的 R2–R5，任务级拆解见 `.spec/plans/2026-11-09_trust-round.md`，不在本包实施
- 引入 Flyway、多实例部署、HTTPS/CSP 的网关配置（CSP 只在应用层加响应头）

## 4. 最小实现路径
- 最简单可行方案：三个维度的键（MinIO 对象、ES 文档 ID、DB 唯一键）统一加上 `userId`，清理与重试按 `(userId, fileMd5)` 收口；refresh 加"换发即删旧 + family 记录"；前端把 markdown 渲染包一层 DOMPurify，引用链接改用 DOM API 构造；检索结果增加 `outcome` 枚举并在阈值全滤时降级。
- 暂不引入：Flyway、租户抽象层（TenantContext）、统一的 SearchOutcome 框架化改造。
- 不做的抽象/配置化：不把消毒白名单做成配置；不把 `userId` 维度改成可插拔的多租户策略。
- 布局判断：现有 `controller/service/repository/entity` 分层与包结构合理，本包只做点状修改，不重构目录、不合并模块。

### 4.1 方案阶梯
- B0 | literal ask | 直接按 F1~F4 修，改 schema + 改键 + 加回归测试；影响面大但一次到位；风险中等（迁移）；可逆性：代码可回滚，数据需反向迁移
- B1 | minimal change | 只修"删除时按租户过滤"而不改键；可保留旧数据，但同一用户不同租户、B 重建后 A 的引用仍失效；风险：看起来修了实际没修
- B2 | recommended alternative | 引入 TenantContext 贯穿全链路：收益大但超出本轮，且与后续轮次冲突

Recommendation: B0 because A2 即使为假，迁移成本也只是一次空跑；B1 不能消除"B 重建覆盖 A"的根因。Flips if A1 is FALSIFIED（需要改成组织维度）。

## 5. 技术决策
- Workflow: FULL
- 技术栈：Java 17 / Spring Boot 3.4 / JPA / Elasticsearch Java Client；前端 Vue 3 + TS + Vite
- 证据协议：evidence: runs（verify 命令真实执行并记录）
- 本轮允许改动：`src/main/java/com/mindbase/{service,repository,model,entity,config,controller,utils}`、`src/main/resources/es-mappings/`、`docs/databases/migrations/`、`frontend/src/{utils,views/chat/modules,views/chat-history}`、`frontend/package.json`、`frontend/vitest.config.ts`、`src/test/java/com/mindbase/**`
- 本轮不应触碰：评测包（`service/eval/`）、`service/chunk/`、`service/search/` 的排序逻辑（除 outcome 语义外）、部署脚本；**不新增或修改 `.github/workflows/**`，不消耗 GitHub Actions 额度**
- Autonomy: medium
- Git integration branch：`spec/2026-11-09_fix-p0-isolation`（作者已从 `main` 创建并切换，未提交；执行端直接在该分支上 `run`）

### 5.1 验证策略
- 范围级别：package
- UI surface: unchanged
- UI surface 理由：消毒只改变恶意输入的输出，正常内容渲染字节级不变；不新增或改变任何交互流程与布局，本轮不改前端交互代码
- 变更对象：文件存储键、ES 文档 ID 与删除查询、向量表唯一键、令牌缓存、markdown 渲染管线、检索结果语义与工具结果文本
- 快速检查：`cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=DocumentVectorTenantKeyTest,TenantIsolationRegressionTest,TokenRotationTest,SearchOutcomeSemanticsTest test`
- 集成检查：`cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck`
- 全项目检查：仅在改动共享基础设施、构建/依赖或发布载荷时执行：`cd /Users/linyixi/PaiSmart && ./mvnw -q -B -DskipTests compile` 与 `cd frontend && pnpm build`
- 升级触发：改动 ES 索引 mapping 或删除查询后若出现跨索引兼容问题，升级为 integration 并记录理由

<!-- spec:correction-log -->
### 5.5 修正日志
- none.

<!-- spec:deviation-log -->
### 5.6 偏离日志
- none.

## 6. 成功标准与验证方式
- [x] 标准 A：两个用户上传相同字节文件后，后者的处理任务不会删除前者的向量与 ES 记录 -> verify: task_0002
- [x] 标准 B：access token 过期后仍可用有效 refresh 换发；旧 refresh 换发后立即失效；logout-all 后 refresh 失效 -> verify: task_0003
- [x] 标准 C：恶意 markdown / 恶意文件名不再产生可执行 DOM，且正常 markdown 渲染不变 -> verify: task_0004
- [x] 标准 D：检索的三种失败可区分，阈值全滤时返回 RRF 兜底结果而不是"未检索到" -> verify: task_0005
- [x] 标准 E：迁移脚本与回归测试随包交付，编译与前端构建通过 -> verify: task_0001

## 7. 风险与约束
- 风险点：改 ES 文档 ID 会让存量数据与新 ID 不一致 -> 缓解：task_0002 交付一次性重索引说明（复用 `EmbeddingReindexService` 或按 `userId` 重建），并在迁移脚本里给出核对 SQL
- 风险点：refresh 端点匿名可达后成为爆破面 -> 缓解：沿用现有 Redis 校验 + 限流；旧 refresh 立即失效；不新增无校验路径
- 风险点：DOMPurify 可能过滤掉客户需要的富文本（表格/细节） -> 缓解：白名单显式列出 `table/thead/tbody/tr/td/th/pre/code/blockquote/a/img`，单测覆盖正常渲染不变
- 风险点：后续 R2/R3 也会触及 `ChatHandler` 与前端聊天文件 -> 缓解：严格串行，本包 `done` 归档后再开下一轮
