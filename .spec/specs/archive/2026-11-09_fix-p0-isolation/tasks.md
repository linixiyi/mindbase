# P0 隔离与故障语义修复 - 任务拆解

## 使用规则
- 每个任务都要写清楚 `boundary` 和 `verify`；机器任务的 verify 以首个反引号 span 为执行的命令
- 如果一个任务没有验证方式，就不能开始；确无命令可执行的任务写 `verify: manual: <理由>`
- 发现的可执行问题必须回写本包，并在本轮做完
- 新任务包使用 `YYYY-MM-DD_<verb>-<object>`，详见 `references/naming-and-commits.md`
- verify 命令不得自引用本包 check 门禁（自指命令在「最近一条」语义下不可满足）
- 多会话/多 agent 共享时，任务可选加一行 `claim: <认领者> <ISO 时间>` 声明在做者；纯信息约定，非锁、无机器键、不进门禁，勾选即失效，归档保留

## 阶段一：数据层

- [x] task_0001 为向量表补上传者维度并改唯一键
  - boundary: 只改 `src/main/java/com/mindbase/model/DocumentVector.java`（新增 `userId` 字段，唯一键改 `uk_document_vectors_user_md5_chunk (user_id, file_md5, chunk_id)`）、`src/main/java/com/mindbase/repository/DocumentVectorRepository.java`（`deleteByUserIdAndFileMd5`，保留旧方法但加 `@Deprecated` 注释）、新增 `docs/databases/migrations/2026-11-09-document-vectors-tenant-key.sql`（含加列、回填、换唯一键、只读核对 SQL 四段）、新增 `src/test/java/com/mindbase/repository/DocumentVectorTenantKeyTest.java`；不改别的实体与方法签名
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=DocumentVectorTenantKeyTest test`
  - 成功判定：`@DataJpaTest` 下同一 `file_md5 + chunk_id` 配不同 `user_id` 可共存；同 `user_id` 重复插入抛 `DataIntegrityViolationException`
  - 失败处理：H2 不接受迁移脚本语法时，测试只验证 JPA 实体约束（`ddl-auto=create-drop`），迁移脚本由 MySQL 手工执行并在完成说明里标注；不要为迁就 H2 改生产 SQL

## 阶段二：隔离与令牌

- [x] task_0002 清理、合并、删除、重试全部按上传者收口
  - depends-on: task_0001
  - boundary: `service/UploadService.java`（合并对象键 `merged/{userId}/{fileMd5}`）、`service/DocumentService.java`（`clearIndexedData(String userId, String fileMd5)`、`enqueueAsyncVectorizationRetry` 改用 `findFirstByFileMd5AndUserIdOrderByCreatedAtDesc`）、`service/ElasticsearchService.java`（`deleteByFileMd5(String index, String userId, String fileMd5)`，删除查询加 `term userId`）、`service/VectorizationService.java`（ES 文档 ID 改 `userId + "_" + fileMd5 + "_" + chunkId`）、`consumer/FileProcessingConsumer.java` 与任务载荷（把 userId 传到位）、新增 `src/test/java/com/mindbase/service/TenantIsolationRegressionTest.java`；不改权限过滤、不改检索排序
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=TenantIsolationRegressionTest test`
  - 成功判定：测试断言合并路径含 userId、ES 文档 ID 含 userId、`deleteByFileMd5` 调用带 userId 且查询 DSL 含 `userId` term；模拟 A/B 两用户同 MD5 时，B 的清理不触碰 A 的记录
  - 失败处理：若 `FileProcessingTask` 无 userId 字段，允许加字段并在 outbox 构造处补值（同一 boundary），不要从 ES 反查用户

- [x] task_0003 refresh token 轮换与撤销
  - depends-on: task_0002
  - boundary: `config/SecurityConfig.java`（新增 `/api/v1/auth/refreshToken` 精确 permitAll，禁止整段 `/api/v1/auth/**`）、`controller/AuthController.java`（换发后失效旧 refresh；校验失败统一 401）、`service/TokenCacheService.java`（新增 `revokeRefreshToken`、`revokeRefreshFamily`，family 记录写 `jwt:refresh:family:{userId}`）、`utils/JwtUtils.java`（生成 refresh 时写 family）、`controller/UserController.java`（logout-all 同时吊销 refresh）、新增 `src/test/java/com/mindbase/controller/TokenRotationTest.java`；不改登录接口契约、不改前端存储方式
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=TokenRotationTest test`
  - 成功判定：无 Authorization 头时用有效 refresh 换发返回 200；同一 refresh 第二次换发返回 401；logout-all 后换发返回 401；签名伪造返回 401
  - 失败处理：MockMvc 切片缺 Redis 时，用 `@WebMvcTest(AuthController.class)` + mock `JwtUtils`/`TokenCacheService`；不要为了测试拉起完整上下文

## 阶段三：前端渲染安全

- [x] task_0004 markdown 渲染消毒与引用链接安全构造
  - boundary: 新增 `frontend/src/utils/markdown.ts`（DOMPurify 白名单包装 + 纯函数导出）、改 `frontend/src/views/chat/modules/chat-message.vue`（渲染走 `markdown.ts`；`createSourceLink` 改用 DOM API 或显式转义）、`frontend/package.json`（新增 devDependency `dompurify`、`vitest`；新增 script `test:unit`）、新增 `frontend/vitest.config.ts`、新增 `frontend/src/utils/__tests__/markdown.spec.ts`；不改聊天交互、不改后端入库逻辑
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm install && pnpm exec vitest run src/utils/__tests__/markdown.spec.ts && pnpm typecheck`
  - 成功判定：`<img src=x onerror=alert(1)>`、`<script>`、`javascript:` 链接、`<iframe>` 均不产生可执行节点；正常表格/代码块/标题/引用/内部锚点的渲染结果与修改前一致；恶意文件名不再生成标签
  - 失败处理：`pnpm install` 因镜像失败时改用 `--registry=https://registry.npmjs.org`；若 DOMPurify 在 SSR/Node 环境报 `window is not defined`，在 `vitest.config.ts` 用 `environment: 'jsdom'` 并补 `jsdom` 依赖

- [x] task_0005 检索失败三分类与阈值降级
  - boundary: 新增 `src/main/java/com/mindbase/model/SearchOutcome.java`（`HIT / NO_PERMISSION_MATCH / RECALL_FAILED / BELOW_THRESHOLD`）与 `SearchResponse.java`（`results` + `outcome` + `detail`）、改 `service/HybridSearchService.java`（三种失败分别置位；阈值全滤时返回 RRF 前 N 条并置 `BELOW_THRESHOLD`）、`service/AgentToolRegistry.java`（按 outcome 输出不同提示文本，不再一律"未检索到"）、`service/ChatHandler.java` 仅做调用适配；对 `service/eval/RagEvalCaseRunner.java` 只允许同步签名所需的最小适配；新增 `src/test/java/com/mindbase/service/SearchOutcomeSemanticsTest.java`
  - verify: `cd /Users/linyixi/PaiSmart && ./mvnw -q -B -Dtest=SearchOutcomeSemanticsTest test`
  - 成功判定：权限异常得到 `NO_PERMISSION_MATCH`、召回异常得到 `RECALL_FAILED`、全部低于阈值得到 `BELOW_THRESHOLD` 且结果非空（RRF 兜底）、正常命中得到 `HIT`；工具结果文本按 outcome 区分
  - 失败处理：若改造 `SearchResponse` 波及超过 5 个调用点，允许保留旧 `List` 重载返回视图，但必须在完成说明里列出未迁移的调用点

---

**当前进度**：由脚本计算，无需手动维护

## 轮内修正（复审 medium 当轮修复，2026-11-07）
- [x] ddl.sql:75 唯一键同步为新租户键（与迁移脚本头部声称一致，新装库不再残留旧严键）
  - id: task-followup-ddl
  - boundary: 只改 docs/databases/ddl.sql 一行
  - verify: manual: 复审发现项 1 闭环（grep uk_document_vectors_user_md5_chunk 命中）
- [x] 共存持有者删除分支补目标用户向量与 ES 清理（原只删 chunk_info，删除者内容残留可自检索）
  - id: task-followup-orphan
  - boundary: 只改 DocumentService.java else 分支（复用既有 deleteByUserIdAndFileMd5 与 ES deleteByFileMd5(targetUserId, fileMd5)）
  - verify: manual: 复审发现项 2 闭环（mvn compile+全量 374/374）
- [x] task_0002 spec_verify 记录补齐（主会话补跑 exit 0）
  - id: task-followup-sv0002
  - verify: manual: runs.jsonl 中 task_0002 记录已存在（.dsh spec_verify 补跑）
