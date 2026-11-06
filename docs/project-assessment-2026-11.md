# MindBase 项目全面评估（2026-11）

> 本文由六份独立审计合并而成：①安全（认证/授权边界/注入/上传/密钥/CORS/限流/管理端点）②工程质量（测试/错误处理/事务/日志/死代码/依赖）③水平扩展与数据一致性（ES 索引/Kafka 分区/模块边界/配置管理）④性能（检索链路/高频接口/前端/WS 推送）⑤部署与运维（CI/CD/可观测性/健康检查/备份/Docker）⑥面试官视角（15 分钟评估）。
> 全部结论均来自各审计的实测命令输出或代码核验，证据锚点（`文件:行号` 或实测结果）原样保留。审计过程中创建的 probe 用户（audit-sec-probe, id=22）及其邀请码使用已在审计内声明，admin 会话状态已复原。
> 评估立场：这是一个**求职作品集项目**，按『面试官 15 分钟』与『资深工程师 2 小时』两个深度给出诚实结论，不粉饰不夸大。

---

## 总判定

### 面试官 15 分钟视角

第一印象的两极都落在文档与演示态上。差异化叙事是**真实且可逐条验证的**：interview.md 与 README 的能力声明抽查 8 处有 7 处与当前代码一致（RRF 检索、Kafka FixedBackOff(3000,4)、WS stop 链路、位图上传 composeObject、评测指标 Recall@5=0.7609 均对上），介绍页诚实声明『没有真实客户、生产部署数据或用户证言——欢迎在 GitHub 逐条对照验证』——这是大多数作品集做不到的。但『亲手跑一把』环节会连扣两分：按 README 从零走 infra.sh 路径**跑不通**（infra.sh:28 的 SERVICES 只含 minio/kafka/elasticsearch，MySQL/Redis 需本机自装且全文无说明；IK 分词器在 README 中零指引，走 infra.sh 路径的读者后端首次启动建索引即失败）；演示数据是**测试残留而非企业演示态**——知识库只有 1 篇自指的 e2e 测试文档（且源文件不在仓库），用户管理页暴露 16+ 个自动化测试账号（g6probe、h5debug_reg_1106、rv_reg_mw3tinc4、audit-sec-probe 等），而 README 最突出的差异化能力（冲突裁决、离线评测、DLQ 重放）在实机全为空表：eval runs=0、冲突裁决与死信队列无数据。15 分钟内面试官看不到任何一条有数据的治理页面。若面试官恰好有安全直觉并注册两个账号试一下会话接口，P0 越权当场穿帮。

### 工程师 2 小时视角

两小时深挖会先看到真实力：内部可靠性机制达到作品集亮点水准——outbox 条件认领+指数退避补发、DLT 落库人工重放复用 outbox、DB 心跳租约替代 Redis 锁（多实例正确）、ES reindex 追平两遍+计数校验+失败回滚别名切换、删除级联 SELECT FOR UPDATE 收口 TOCTOU；检索链路实测 ES 双路召回 30-50ms、用量聚合 41ms，是全项目最强部分。但继续深挖会击穿『权限隔离』这一产品核心卖点：**两个 P0 越权在真实运行环境实测复现**——普通用户可读出 admin 的完整聊天历史（HTTP 200，130KB/22 条消息）、可任意归档/取消归档 admin 的会话（均 200）；内部 OCR 端点匿名可达（实测 200）。外部运维面几乎全缺：无 CI（.github 不存在）、零 metrics/trace（pom 无 actuator/micrometer，/actuator/* 实测 403 与不存在路径一致）、无健康检查端点、MinIO 零备份、应用无 Dockerfile。测试覆盖在 Controller/Service 层断崖：16 个 Controller 仅 1 个有测试、38 个 Service 中 17 个零测试、前端零测试基建。结论：**核心链路的设计与文档是真功夫，但『企业级』声称在安全边界与运维维度目前撑不住**。

---

## 六维评分表

| 维度 | 分数 | 一句话 |
|---|---|---|
| 安全 | 5/10 | 骨架扎实（路由角色、ES/SQL 权限过滤、密钥管理、pay 验签都做对了），但多租户资源级属主校验存在系统性缺口，两个越权（读+写）实测复现，登录限流可被一个伪造头 100% 绕过 |
| 工程质量 | 6/10 | 核心域测试有真实深度（eval/conflict/reliability/contextual 全覆盖）且日志与死代码纪律良好，但 Controller 16 个仅 1 个有测试、17 个 Service 零测试、前端零测试基建，无全局异常处理器，支付下单把外部 HTTP 包进 DB 事务 |
| 扩展性与一致性 | 6.5/10 | 一致性可靠性骨架（outbox→DB 租约→DLT→人工重放、别名原子切换、TOCTOU 收口、幂等消费）达到作品集亮点水准，但聊天流/停止绑单实例静默失效，Kafka 1 分区封顶吞吐，匿名可达的内部 OCR 实测 P0 |
| 性能 | 7/10 | 检索链路是全项目最强部分（双路召回实测 30-50ms、时间预算贯穿、聚合实测 41ms），扣分在流式热路径每 chunk 6 次 Redis 往返的写放大与长会话全量拉取无虚拟滚动 |
| 部署运维 | 4/10 | 内部可靠性机制（outbox 补发、DLT 重放、卡住任务自恢复、部署脚本自带校验）扎实且部署文档罕见地诚实，但外部运维面几乎全缺：无 CI、无 metrics/trace、无健康端点、MinIO 零备份、应用无 Dockerfile |
| 面试官视角 | 6.5/10 | 差异化叙事真实可验证（8 处抽查 7 处与代码一致）、文档一流，但快速开始跑不通与测试残留的演示态会让面试官在『亲手跑一把』环节显著扣分 |

六维区间 **4-7/10，中位 6.25**：强项集中（检索、可靠性链路、文档），短板也集中（授权边界收口、外部运维面、演示卫生）。

---

## 强项（值得在面试中主动展示的）

1. **Kafka 可靠性链路（安全/扩展性/运维三份审计一致认定的亮点）**：outbox 条件认领+指数退避补发、DLT 落库人工重放复用 outbox、DB 心跳租约替代 Redis 锁（多实例语义正确）、max.poll.records=1 + 恢复任务兜底（recovery 60s 间隔、心跳超时 600s，application.yml:183-185）、位图+composeObject 上传、DLQ E2E 已走通。扩展性审计原话：『一致性可靠性骨架……达到作品集亮点水准』。
2. **ES 混合检索设计与实测性能（性能审计 7/10，全项目最高分）**：BM25+KNN 并行召回、KNN 预过滤、请求级时间预算贯穿 embedding/recall/rerank，实测 ES 双路召回 30-50ms；别名 reindex 追平两遍+计数校验+失败回滚（EmbeddingReindexService.java:172-186）；删除级联 SELECT FOR UPDATE 收口。用量统计在默认余额模式下走写时预聚合（UserTokenService.java:386-414 按 user+day upsert）+ SQL GROUP BY，实测 30 天 overview 41ms。
3. **文档诚实度与叙事一致性（面试审计正向确认的加分项）**：DEPLOY.md 声称的 prepare（备份/SHA256 校验/manifest）/publish（受控停机+验收）/rollback/DRY_RUN 均在脚本中真实实现（deploy_remote.py:120-150、:153-161、:197-208；deploy_release.py:3 DRY_RUN=1 不碰任何文件），运维审计评价『部署文档与现实高度一致，无虚报』『部署文档罕见地诚实』；interview.md 抽查 8 处 7 处与代码一致；介绍页能力区 8 点与 README 一一对应且主动声明无客户数据。
4. **安全基础件做对了的部分（安全审计 5/10 中的『做对了』半边）**：JWT HS256 256-bit 密钥、Redis 双重校验、BCrypt、SQL 全参数化、ES typed DSL 无 script、ES 双路召回+SQL 层组织标签过滤实测通过、WS 握手认证实测通过、微信回调走 v3 验签、git 历史扫描（PEM/root@IP/sk-*/LTAI/AKID 模式，近 50 提交）无命中、.env 未被跟踪、Spring Security 默认响应头实测在位。 rename/delete/WS 链路的 validateConversationOwner 说明属主校验意识存在，问题集中在漏改的路径。
5. **代码卫生基本面（工程质量审计确认）**：无空 catch、无 printStackTrace（grep 实测 0）、聊天仅记 messageLength 不记内容（LogUtils.java:106）、LiteParse 命令行脱敏（ParseService.java:563）、AES-GCM 实现规范（随机 12 字节 IV、128 位 tag、密钥长度校验，SecretCryptoService.java:17-19）、前端仅 20 处 any 且 typecheck 通过、全部路由懒加载（router/elegant/imports.ts:18-32）、shiki 11.8MB 拆 330 个按语言动态加载 chunk、HybridSearchService（419 行）结构清晰注释到位是好的第一印象。

---

## 短板与风险（按严重级合并去重，保留全部证据锚点）

### P0（3 项，其中 2 项实测复现，1 项实测 200）

**P0-1 跨用户聊天历史读取 IDOR（实测复现；安全审计与性能审计两路独立确认）**
- 位置：`src/main/java/com/mindbase/controller/ConversationController.java:54-55` → `ConversationService.java:291-294`（getMessagesByConversationId→findByConversationIdOrderByTimestampAsc）
- 问题：GET /api/v1/users/conversation?conversationId=<uuid> 时 conversationId 直通 service，查询完全不带 userId 过滤，任何认证用户可读任意他人的全部对话消息（用户提问+助手回答全文，含引用内容/引用映射）。validateConversationOwner 存在且在 rename/delete/WS 链路都被调（grep 全仓仅 3 处调用：ConversationService.java:237,258、ChatHandler.java:127），唯独这条读取路径漏了。
- 实测：invite-only 模式下经 admin 建码注册 probe 用户（id=22，role=USER），以其 token 请求 admin 的会话 8928f78d-21b1-4036-a3d8-d23d94c5c61b → HTTP 200，返回 admin 的对话内容『请用6个编号小节介绍MindBase的企业知识库与RAG工作流…』及助手回答；对照（admin token 读该会话）返回 200、130KB/22 条消息。对照：不带 conversationId 的同接口按 username 过滤，仅 by-conversationId 分支漏过滤。

**P0-2 跨用户会话状态篡改 IDOR（实测复现）**
- 位置：`ConversationSessionController.java:78-96(archive)、119-137(unarchive)`；`ConversationService.java:217-229`
- 问题：archive/unarchive 端点只提取 username 做非空检查，不把 userId 传给 service；service 直接 findByConversationId 后改状态。同一资源族内不一致——rename(:237) 与 delete(:258) 都调 validateConversationOwner，唯独 archive/unarchive 漏了。任意认证用户可将他人会话反复归档/恢复（完整性破坏，影响他人使用）。
- 实测：probe 用户 PUT /api/v1/users/conversations/f1c003bc-d7ad-48fc-bf2b-be7cdd8d0a45/archive → HTTP 200 {"code":200,"message":"归档成功"}；unarchive 同样 200（admin 会话状态已复原 ACTIVE）。

**P0-3 内部 OCR 端点匿名可达 + 鉴权 fail-open（实测 200；安全/工程质量/扩展性三份审计各自确认，合并去重）**
- 位置：`config/SecurityConfig.java:56`（/api/v1/internal/ocr/** permitAll）+ `service/AliyunOcrService.java:135-139`（verifyCallbackToken 仅在已配置 token 时才拒绝：`if (hasText(callbackToken) && !Objects.equals(...))`）+ `controller/InternalOcrController.java:35-56`；配置源 `application.yml:239`（callback-token: ${ALIYUN_OCR_CALLBACK_TOKEN:} 默认空）
- 问题：回调 token 未配置时恒放行（本地 .env 无此变量，grep 实测 0 命中）。配置了 Aliyun OCR（支持的模式）后即变成未认证的付费 API 代理/SSRF 到固定上游；当前本地因 OCR 未配置只暴露 health 信息与 503。
- 实测：匿名 POST /api/v1/internal/ocr/aliyun/health → HTTP 200 {"configured":false,"tokenRequired":false,"enabled":false}；logs/business.2026-11-07.log:326-327 记录 [用户:anonymous] 该请求 [状态:200]，:198-199 显示 POST /liteparse 以 anonymous 身份穿过全部过滤器进入 controller（仅因 LiteParse 未启动返回 503）。

### P1（合并去重后 24 项，按组列出）

**安全组（5 项）**

1. **登录/注册 IP 限流可被单个伪造头 100% 绕过（实测）** — `UserController.java:160-170`（resolveClientIp 依次信任 CF-Connecting-IP/True-Client-IP/X-Forwarded-For 等全部客户端可设头）；`application-dev.yml:95-97`（登录 30 次/60s）。实测：32 次错误密码登录、每次 X-Forwarded-For=10.9.9.<i> 递增 → 全部 401 无一次 429；随后不带头 32 次 → 第 32 次起 429（桶本身生效）。代码无部署拓扑判断，直连 8081 即绕过。effort: S
2. **引用详情端点无会话属主校验（代码级确认）** — `DocumentController.java:841-872`(/reference-detail)；`ChatHandler.java:1082-1100`（getReferenceDetail 仅按 generationId 查，方法签名无 userId）。先按 sessionId 取 ReferenceInfo（含他人文件名、命中分块文本、检索 query、证据片段），之后只校验『引用的文件』可访问，从不校验『会话/生成任务』属于请求者；ChatHandler.java:1092 失败时还把全量 generationId 写进 ERROR 日志。对照：ChatController.getGeneration(:78) 用 getGenerationForUser(generationId, userId) 做了属主过滤，说明新代码有此意识、旧端点漏改。未实测到他人数据（generationId 为 UUID 难枚举）。effort: S
3. **吊销链缺口（代码级确认）** — `JwtAuthenticationFilter.java:56-64`（过期 ≤10 分钟宽限刷新）→ `JwtUtils.java:248-264`（refreshToken 直接 extractClaimsIgnoreExpiration→generateToken，全程不查 Redis 黑名单/tokenCacheService）。『登出所有设备』对已过期但仍持握旧 token 的攻击者在 10 分钟窗口内无效。对照：显式端点 /auth/refreshToken 有 validateRefreshToken 的 Redis 校验（AuthController.java:36）。未实测（需等待 1h 过期窗口）。effort: S
4. **Kafka JsonDeserializer 信任所有包** — `application.yml:50` 与 `application-dev.yml:50`（spring.json.trusted.packages: "*"）；`KafkaConfig.java:135-136` 原样放入 ConsumerConfig。本地 Kafka 无认证（bootstrap 默认 127.0.0.1:9092、无 SASL），任何能向 file-processing topic 写消息的本地进程可经 __TypeId__ 头指定任意 classpath 类实例化（Jackson gadget 面），配合死信重放扩大影响。实际消费目标类型为 FileProcessingTask，属 defense-in-depth 级。effort: S
5. **清库/迁移端点的第二重『管理员密钥』硬编码在公开仓库** — `AdminController.java:1111`（POST /admin/migrate-minio 校验常量 "migration2024"）、`:1179`（POST /admin/clear-all-data 校验常量 "CLEAR_ALL_2024"）。任何人可读，防线只剩 SecurityConfig.java:72 的 hasRole("ADMIN")，该密钥零熵且违反 AGENTS.md『任何密钥不进被跟踪文件』硬规则。应 env 注入或直接删除这道假第二重。effort: S

**工程质量组（6 项）**

6. **测试断层** — 339 个后端测试（grep @Test 实测，64 文件）分布严重不均：Controller 层 16 个仅 UploadController 有测试；38 个 Service 中 17 个零测试（DocumentService 1027 行、UserTokenService 521 行配额记账、WxPayService 338 行、RateLimitService 101 行限流、OrgTagCacheService 231 行多租户缓存、ElasticsearchService 127 行检索核心、SecretCryptoService 90 行加密、ChatGenerationStateService 324 行、TokenCacheService 252 行、RechargeService 211 行等）；前端完全无测试基建——package.json scripts 无 test 条目、无 vitest/jest 配置，仅 1 个孤儿 spec（playwright-kb-column.spec.ts）硬编码 127.0.0.1:9529（当前 dev server 是 9527）且无 playwright.config.ts。mvn test 未执行（应用携真实数据在跑，避免测试触碰同一 MySQL/ES/Kafka）。effort: L
7. **无全局异常处理器** — grep @RestControllerAdvice/@ControllerAdvice/ExceptionHandler 全仓 0 命中；16 个 Controller 内嵌 100 处 catch(Exception) 各自为政，至少 4 种错误形态（手写 Map.of 信封 135 处、裸字符串 body、ResponseStatusException、Security 默认空 body 403）；226 处 e.getMessage() 直达客户端（内部 SQL/路径/命令行可泄漏）；`ParseController.java:36-44` 对任何异常一律返回 HTTP 400（DB 故障也变 Bad Request）。实测：错误凭证 → {"code":400,...}，未认证访问受保护路径 → 403 空 body。effort: M
8. **支付链路把外部 HTTP 调用包进 DB 事务** — `RechargeService.java:58` @Transactional(rollbackFor=Exception.class) 内 `:112` 调 wxPayService.createOrder(payReq) 发起微信下单网络请求（支付 API 慢时 MySQL 连接被长时间占用，连接池耗尽风险）；`:118-121` handlePayCallback 事务内先做 wxPayService.payCallback(request)（含验签/可能的证书下载网络 I/O）；`WxPayService.java:176-192` refundCallback 的 @Transactional 包住验签+HTTP 读取，且 :171 注释自认退款流程未接入业务（移植残留）。effort: S
9. **调试端点残留生产代码 + permitAll（实测匿名可达并触发 DB 写）** — `src/main/java/com/mindbase/test/TransactionTestController.java:11-37`（在 main 源码集而非 test）+ `SecurityConfig.java:54`（/api/v1/test/** permitAll）。实测：curl http://localhost:8081/api/v1/test/transaction/protected 无任何认证头 → HTTP 200，内部 testEntityRepository.save 后抛异常回滚（TransactionTestService.java:32-33）。H6 债务包刚做过死代码清理但漏掉了这处。effort: S
10. **Tika 2.9.1 存在双 XXE CVE 且运行在解析用户上传的攻击面** — `pom.xml:134-140`（tika-core/tika-parsers-standard-package 2.9.1）；CVE-2025-54988（PDF XFA，修复于 3.2.2）与 CVE-2025-66516（tika-core 同源，2025-12 披露）。缓解：ParseService 按 %PDF- 魔数把 PDF 路由到 LiteParse（ParseService.java:556-561），仅 Office/文本走 Tika AutoDetectParser（:143），PDF-XFA 路径基本被绕开——故 P1 而非 P0，但升级 3.x 应尽快。effort: M
11. **生产 profile 仍用 hibernate ddl-auto: update** — `application-prod.yml:1-5`。Hibernate 将在 prod 启动时自动改表结构，与 docs/databases/ 的受控 DDL/迁移脚本并存会漂移，属生产数据风险反模式（应为 validate + 显式迁移）。effort: S

**扩展性/一致性组（4 项）**

12. **Kafka 文件处理全链路并行度封顶 1（实测）** — `KafkaConfig.java:41`（partitions 默认 1）+ `application.yml:58`（SPRING_KAFKA_TOPIC_PARTITIONS:1）+ `KafkaConfig.java:131`（MAX_POLL_RECORDS=1，注释自述一条消息=一份文件分钟级处理）。实测 docker exec kafka kafka-topics.sh --describe → file-processing-topic1 PartitionCount: 1 ReplicationFactor: 1（另两个遗留 topic 也是 1 分区）。扩多少实例都只有 1 份文件在处理；消费端 DB 租约已天然支持多消费者、key=fileMd5 保序不受扩分区影响——扩分区是低风险吞吐解锁点，但必须先于实例数扩展。effort: S
13. **聊天生成/stop 单实例绑定（代码推理，未实测双实例）** — `ChatSessionRegistry.java:19-22`（实例本地 ConcurrentHashMap）+ `ChatWebSocketHandler.java:104-105`（type=resume 仅查本地 registry）+ `ChatHandler.java:1030-1060`（stopResponse 的 stopFlags/activeStreams 为本地 map，跨实例只写了 Redis markCancelled）+ 前端依赖 snapshot 自动 resume（frontend/src/store/modules/chat/index.ts:188/416-417）。多实例下 resume 是静默 no-op（界面冻结无报错）、stop 后原实例 LLM 流继续跑 token 继续扣。生成内容/状态在 Redis 共享（ChatGenerationStateService.java:30-47）但增量推送不经共享层。最小修法 LB 层 WS 粘性；彻底修法增量经 Redis pub/sub 广播（DocumentStatusNotifier.java:24-26 已示范）。effort: M
14. **ES 残留无补偿，三条路径产生『MySQL 已删/标记失败、ES 部分数据仍可搜』** — (a) 余额不足失败的消息正常提交：`FileProcessingConsumer.java:100-106` catch 内直接 return，无 DLT、无 clearIndexedData、无重试，部分分块按真实权限字段持续可搜直到同 MD5 重传；(b) 删除时 ES deleteByFileMd5 失败仅记 error：`DocumentService.java:199-206`（注释『继续删除其他数据』），已删文档内容无限期可搜；(c) DLT 停放期间最后一次尝试的残留（重放时自愈）。grep 全仓无任何 ES↔MySQL 对账任务（EmbeddingReindexService 只服务模型升级）。搜索只查 ES 不校验文件状态，用户端无感知。effort: M
15. **AdminController 巨石 + 手写鉴权与框架 RBAC 重复** — `controller/AdminController.java`（1596 行、38 个端点），用户/邀请码/组织标签/模型供应商/限流/充值/迁移/清库/系统状态至少 6 个领域挤在一个 controller；38 处手写 jwtUtils.extractUsernameFromToken(token.replace("Bearer ","")) + 39 处 validateAdmin + HashMap 响应模板，而 `SecurityConfig.java:72` 已对 /api/v1/admin/** 强制 hasRole("ADMIN")；`:50/:62/:89/:92` 直接注入 4 个 Repository。DocumentController（1012 行）亦直接注入 FileUploadRepository。其余 service 层次健康，controller 是唯一的层次破坏点。effort: M

**性能组（2 项）**

16. **流式聊天热路径每 chunk 6 次 Redis 往返的写放大（代码路径逐行核实）** — `ChatGenerationStateService.java:85-93`（appendChunk：Redis APPEND 后调 touch）与 `:184-205`（touch：GET meta + 整份重写 SET meta——含 question 全文的 JSON + EXPIRE content + EXPIRE refs + SET active key，共 6 次独立 RedisTemplate 调用，Lettuce 未配置 pipeline）。按常见 SSE 出块速率 20-40 chunk/s 估算（推测值），单活跃流 120-240 Redis ops/s，绝大部分只为刷新 updatedAt。修复方向：touch 节流（如每秒一次）或砍掉 meta 重写只 expire。effort: M
17. **长会话全量拉取 + 前端无虚拟滚动的组合（后端载荷与 DOM 双双无上界）** — 后端 `ConversationService.java:291-294`（getMessagesByConversationId 无分页/无条数上限；Redis 上下文只截 20 条供 LLM，MySQL 不设限）；前端 `frontend/src/store/modules/chat/index.ts:253-273`（loadMessages 整段拉取）+ `chat-list.vue:332`（v-for 全量渲染，:key=index），:178-180 注释自认『会话消息整段载入（该分支本就无分页）』。实测：GET /users/conversation?conversationId=f1c003bc-…（最大会话）→ 200 size=130339B 13.6ms；MySQL 实测 reference_mappings_json 最大单条 8.3KB、answer 最大 5.6KB、单会话最多 22 行；frontend/src/views 全目录 grep NVirtualList/virtual 零命中。数百条消息的会话切换将带来数 MB 载荷与数百次 markdown/katex/shiki 渲染，主线程卡顿。effort: M

**部署运维组（4 项）**

18. **CI/CD 完全缺失** — `.github/` 不存在（ls 实测 NO such file），.gitlab-ci.yml/Jenkinsfile/.circleci 均无；git remote 为 github.com/linixiyi/mindbase.git——托管在 GitHub 却没有任何 workflow：push 不跑测试、不跑构建、不做 secret 扫描（AGENTS.md 要求的提交前 IP/密钥扫描只是人工约定）。后端 64 个测试文件、部署脚本自带 python unittest，全部依赖手动执行。effort: M
19. **零 metrics、零 trace、非结构化日志** — pom.xml 无 actuator/micrometer/prometheus 任何依赖（grep 实测无匹配）；实测 GET /actuator/health 与 /actuator/metrics 返回 403，与 /actuator/definitely-not-real 的 403 完全一致（Security 拦截未知路径而非端点存在）；logback-spring.xml 四个 appender 全是纯文本 pattern 且 0 处 %X——LogUtils 定义了 requestId/userId/sessionId 等 MDC 键且 15 个业务文件在用（grep -rln LogUtils. = 15），但 MDC 值永远不会出现在日志输出里；熔断/背压/DLT/outbox 这些内部治理机制没有任何外部可见指标，代码里的『告警』就是 logger.warn（FileProcessingBackpressureMonitor.java:113）。effort: M
20. **无健康检查端点** — application.yml 无 management: 段（Read 核实）；无 liveness/readiness 区分、无依赖（MySQL/ES/Kafka/MinIO）探活。docs/runbook.md:43 用业务接口的 405/400 当活性探针自证其无（实测 POST /users/login -d '{}' → 400）；docs/nginx.conf 仅 location /、/api/、/proxy-ws 三段无 health，LB 与外部监控无从下手。对照组：docker-compose 里五个中间件反而都配了 healthcheck（docker-compose.yaml:16,53,111,156）。effort: S
21. **备份缺口：MinIO 零备份、无计划性备份** — 唯一备份是发布 prepare 时 MySQL 一次性导出（实现质量不错：deploy_remote.py:138-148 校验退出码+CREATE TABLE+尾部 'Dump completed'，gzip 完整性），但 runbook 与 DEPLOY.md grep cron/定时备份零匹配；MinIO 存放全部原始上传文件，全仓库没有任何 MinIO 备份脚本——存储卷损坏即原始文件永久丢失，MySQL 里只有元数据。ES 属可派生状态（KnowledgeIndexAdminController 提供重建/回滚）可接受。DEPLOY.md:27 自认备份『不等同于做过恢复演练』、:13 写明 rollback 时『数据库不会自动恢复』。effort: M

**面试官视角组（3 项）**

22. **演示态是测试残留而非企业演示态（实机实测）** — 管理员可见知识库仅 1 篇文档 mindbase-e2e-test.md（自指介绍 MindBase 的 e2e 测试文档，find 全仓无该源文件，克隆后无法复现）；所有历史回答都引用这同一篇，聊天页模型自述『知识库当前收录内容集中于一篇测试文档』；用户管理页暴露 16+ 个自动化测试账号（g6probe、h5debug_reg_1106、h5pm032_1106a-h、h5rvw_user1-5、rv_reg_mw3tinc4、audit-sec-probe——最后者创建于审计当天 2026-11-07，浏览器 DOM 快照第一行可见）；实机 eval runs=0（curl /api/v1/admin/eval/runs → 0）、冲突裁决/死信队列为空。README 最突出的差异化能力在演示态下全是空表。effort: M
23. **按 README 从零操作跑不通（三处环境假设）** — (1) README.md:106/117-125 写『MySQL 8、Redis…可用 infra.sh 或 docker-compose 启动』，但 infra.sh:28 的 SERVICES=(minio kafka elasticsearch) 不含 MySQL/Redis 且全文无自装说明；(2) infra.sh:13-20 假设二进制已预下载到 ~/Downloads 固定布局（minio1/、kafka_2.13-3.9.0、elasticsearch-8.10.0），README 未给下载步骤；(3) ES mapping 依赖 IK 分词器（EsIndexInitializer.java:156-158），docker-compose 路径会自动装 analysis-ik（docker-compose.yaml:148-152）但 README 全文无 'IK' 字样——走 infra.sh 路径首次启动建索引即失败，只能靠报错提示自救。另 README 写 ES 8.10 而 compose 镜像 8.10.4，小版本漂移。effort: M
24. **interview.md §3 与 §8 自相矛盾 + 行号漂移** — §3『ES 混合检索与向量化』（:108-147）是过期内容且与同文档 §8（:332-513）矛盾：§3 声称 numCandidates 是 topK 的 30 倍并给出 rescore（queryWeight 0.2/rescoreQueryWeight 1.0）的关键代码块（:134-144），但当前 HybridSearchService 里 grep 'rescore' 零命中，numCandidates 实为召回窗口 × knn-num-candidates-multiplier 上限 10000（HybridSearchService.java:247），§8 自己（:347）明确说 rescore 是『老版本代码』、现行方案是 RRF。面试官通读此文档再对照源码会读到两种互斥的检索实现叙述。全文行号引用普遍漂移（如 ChatHandler.java:520-558 实际在 1030-1048）。其余抽查一致（§1 stop 链路、§2 append/computeIfPresent、§4 位图与 composeObject、§5 FixedBackOff(3000,4)、§8 allOf 降级自评、§10 评测指标与 docs/eval/reports/05 号报告吻合）。effort: S

### P2（合并去重后 22 项，选做/长期）

**安全组（4 项）**

1. 文件类型校验仅凭扩展名，无 magic-byte/内容检测 — `FileTypeValidationService.java:23-56` 纯静态集合（grep tika/detect/magic 无结果），`UploadController.java:90-92` 传的是 @RequestParam fileName，白名单含 html/htm。缓解：对象键 merged/{md5} 不含用户文件名、每分片 50MB/总请求 100MB 硬限制（application.yml:30-31）、非管理员另有组织级总大小限制（UploadController.java:136-156）；svg 虽在解析白名单外但 DocumentController.getPreviewType(:959) 归为 image，若后续放开预览存在以 MinIO 域为源的脚本执行面。effort: M
2. JWT 以 URL 查询参数/路径段传递的多处泄漏面 + 错误日志打印完整 token — `DocumentController.java:443-444、562-563、777-778`（download/preview/download-by-md5 支持 ?token=，:985-987 fallbackToken 采纳）；`WebSocketConfig.java:30`（/chat/{token} 路径传 token）——均进入访问日志/浏览器历史/Referer；`JwtUtils.java:150` 解析失败时把完整 token 打进 ERROR 日志。effort: M
3. refresh-token 机制自相矛盾、logout 不吊销 refresh token（实测） — /api/v1/auth/refreshToken 未列入 permitAll（SecurityConfig.java:51-52、76），必须持仍有效的 access token 才能刷新，refresh token 无法实现续期用途（实测：logout 后旧 access+有效 refresh → 403）；logout 只 invalidateToken(access)（UserController.java:396），refresh token 在 Redis 存活 7 天不被吊销。机制要么 permitAll+换发轮换吊销，要么删除。effort: M
4. /api/v1/users/upload-orgs 对所有用户恒定 403（实测，fail-closed 功能缺陷） — `UserController.java:342` 要求 @RequestAttribute("userId")，但该属性只由 `OrgTagAuthorizationFilter.java:60-68` 的路径清单注入，清单未含此端点 → ServletRequestBindingException → Http403ForbiddenEntryPoint → 403（后端日志证实）。若前端仍在调用，上传组织选择功能已坏。effort: S

**工程质量/结构组（3 项）**

5. 巨型类体量（AdminController 巨石已升入 P1-15，此处为其余三个） — ChatHandler.java 1228 行/77 成员上帝类，单类持有 8 个 ConcurrentHashMap（responseBuilders/responseFutures/activeStreams/stopFlags/cancelledGenerations/generationReferenceMappings/generationParentContexts 等，:77-88 区间），混合 WS 生命周期+流式编排+引用校验，且头注释是 '@author Generated by Assistant'（:43-47，全仓 grep 仅此 1 处的 AI 生成痕迹恰好留在最核心文件）；DocumentService.java 1027 行、DocumentController.java 1012 行。均为可扩展性与可测性瓶颈，且 AdminController 的 1596 行正是零测试区。整体注释质量高（中文注释解释设计意图），问题在体量而非质量。effort: L
6. AES-GCM 加密密钥回退复用 jwt.secret-key — `SecretCryptoService.java:23` ${model-provider.security.secret-key:${jwt.secret-key:}}，JWT 签名密钥与对称加密密钥跨用途复用，任一泄露即两处失守；实现本身规范。effort: S
7. 双 logger 命名约定并存 — @Slf4j 的 log（34 处）与手写 logger（563 处）混用约 6%；其余纪律良好（无 printStackTrace/空 catch、错误路径未降级 warn）。effort: S

**扩展性/一致性组（5 项）**

8. /error 改写匿名错误状态码（实测） — SecurityConfig.java:47-76 的 permitAll 清单不含 /error，sendError() 转发重新过安全链落入 anyRequest().authenticated()。实测：GET /api/v1/nonexistent-path（匿名）→ 403（应 404）；POST /liteparse 后端日志记 503（LoggingInterceptor.java:66）而 wire 返回 403 空 body。匿名调用方无法区分不存在与拒绝。effort: S
9. ES 重建窗口与多实例假设 — 别名切换后、追平完成前新索引内有旧模型向量，KNN 无 modelVersion 过滤（HybridSearchService.java:246-256，grep 仅 EmbeddingReindexService.java:250 用于续建判断），窗口内跨模型余弦相似度无意义（窗口小且自愈）；重建状态 AtomicReference 仅内存（EmbeddingReindexService.java:59），两实例可并发重建（validate 失败兜底非静默损坏）；pause 只停本实例 listener container（FileProcessingListenerGate.java:35-49）。当前单机不触发。单实例重建读写路径正确：写→别名（重建中=旧索引）、读→别名、追平两遍+计数校验+失败回滚。effort: S
10. 删除级联不完备（冲突记录） — `DocumentService.java:154-260` deleteDocument 全程无冲突记录清理（无 conflictRepository 引用）；仅冲突裁决路径的 deleteFile 对关联 PENDING 置 OBSOLETE（DocumentConflictService.java:155-169 obsoleteSiblings），且对『文件已不存在』有兜底（:142-149 warn+return false 不会 crash）；:84-93 list() 不过滤已失效 md5。直接删文档后 PENDING 冲突永久滞留管理队列。effort: S
11. 熔断/舱壁状态实例本地 — `ModelCallGuard.java:47-56` CircuitBreakerRegistry.of/BulkheadRegistry.ofDefaults 每实例新建，无共享层；实例 A 熔断某供应商实例 B 继续打。Resilience4j 常见取舍，单实例无影响；对照 VectorizationLeaseService 用 DB 条件更新实现跨实例任务锁（正确做法已存在）。effort: M
12. MinIO bucket/路径硬编码绕过配置 — application.yml:76 已提供 MINIO_BUCKET_NAME（.env.example:31 也有），但 bucket("uploads")、"merged/"+md5 字面量散布 4 个文件 38 处命中（UploadService.java:136/563/577/583/591/601/636-637、DocumentService.java:206-231、BootstrapKnowledgeInitializer、MinioMigrationUtil），改配置即坏。effort: S

**性能/前端组（4 项）**

13. 前端首屏 bundle 偏重但可接受 — 实测 pnpm build：entry 1545KB raw / 509KB gzip + CSS 139KB/18KB；vite.config.ts 无 manualChunks/rollupOptions（grep 零命中）。好的方面已实测：全部路由懒加载、echarts/pdfjs/usage-monitor(639KB) 均在懒加载 chunk。effort: M
14. 非默认用量统计路径遗留 OOM 风险（代码 FIXME 自认） — `UsageDashboardService.java:23-29` userRepository.findAll + 逐用户逐天 5 次 Redis GET 无 MGET/pipeline（UsageQuotaService.java:231-243、512-533，100 用户×30 天=15000 次往返）；默认 use-user-token-balance=true（application.yml:166）下走健康的 UsageBalanceDashboardService（写时预聚合+SQL GROUP BY，实测 41ms），一旦配置回切即触发。QuotaConfiguration.java:49-57 按配置二选一。effort: S
15. 管理端 user-activities 返回写死模拟数据（实测，面试点开即穿帮） — `AdminController.java:224-238`（:221-222 注释自认『模拟用户活动数据』，真实服务未接入）。实测 curl 返回与源码硬编码一致（user1/user2、2023-03-01T10:15:30、192.168.1.100/101）。与 PRODUCT.md『不需要任何这里其实没做完的补丁说明』的品牌承诺冲突。effort: S
16. ES mapping 漂移 + 遗留全量契约 — 模板 es-mappings/knowledge_base.json 声明 isPublic，Lombok is- 前缀使 EsDocument 实际序列化为 ES 字段 public（HybridSearchService.java:271 查询 public，运行时一致功能正常）；实测线上索引 mapping 两字段并存、public 靠动态映射，语义漂移重索引易踩坑。另 /documents/accessible 与 /uploads 无分页参数时仍返回全量数组（DocumentController.java:267-275、309-315 旧契约），当前前端 3 处调用点均已带分页参数（逐一核实），属可清理死契约。effort: S

**部署运维组（3 项）**

17. 应用本体不可容器化 + compose 明文密码 — find 全仓无 Dockerfile（仅 docs/docker-compose.yaml 五个中间件，无应用 service），runbook.md:68 自认『本仓库没有后端镜像』，application-docker.yml（约 250 行完整 profile）是悬空死配置；compose 硬编码同一密码 MindBase2025 共 4 处（:11/35/59/131，MySQL root/MinIO/Redis/ES），其中 :134 ELASTIC_PASSWORD 与 README.md:153『所有敏感信息…一律通过环境变量注入，不写入仓库』承诺直接冲突（dev 凭证风险低，但可被面试官一句话戳破）。生产是否复用无法本机验证。effort: S
18. 未配置优雅停机（实害有限） — 三个 application*.yml 均无 server.shutdown=graceful（grep shutdown 无匹配），SIGTERM 时 Web 层立即停止、在途 HTTP 与 WS 流被切断；ChatWebSocketHandler 无 destroy/drain 钩子（仅 afterConnectionClosed :136）。缓解实打实：部署停机 SIGTERM→最长 30s→SIGKILL 受控（deploy_remote.py:171-184）、Kafka 中断由恢复任务兜底、客户端有 WS 断线重连续传、5 个 @PreDestroy 清理线程池/连接。effort: S
19. 发布流程残余差距（文档与现实一致性的反面清单） — 单机手工编排、DEPLOY_SERVER_USER 默认 root（DEPLOY.md:7）、明确不宣称零停机（:32）、无 staging 概念、构建产物依赖本地手动 mvn package+pnpm build（:5）——与 CI 缺失互为因果。基线是正面的：README/runbook 引用的 infra.sh、deploy-front.sh、DEPLOY.md、docker-compose.yaml、.env.example 全部存在（ls -la 核实）。effort: L

**面试官视角组（3 项）**

20. README/介绍页零截图、零架构图、零 GIF — grep '![' README.md 命中 0，docs/index.html 无 img；产品实际有打磨过的 UI（实机截图：登录页大留白蓝主色、聊天页含表格/标题锚点/点赞点踩的富渲染引用回答），但第一眼只有文字列表，仓库内已有 DESIGN.md 设计体系未在 README 露出。effort: S
21. 登录页第一屏两处硬伤 — (1)『用户协议』『隐私政策』是无任何 @click 的死按钮（pwd-login.vue:126-131）；(2) 记住我文案『记住用户名和密码』（zh-cn.ts:207）与实现只存用户名（pwd-login.vue:71-75 注释 'Only persist the user name'）不符——实现是对的、文案做了与 PRODUCT.md 安全承诺相反的过度承诺。effort: S
22. 后端 API 消息英文残留 — 前端视图层抽查干净（硬编码英文仅技术词，语言切换入口按承诺隐藏 theme/settings.ts:16），但后端消息是英文：'Refresh token cannot be empty'/'Invalid refresh token'/'Token refreshed successfully'（AuthController.java:30/36/56）、'Get all users successful'（AdminController，curl 响应首字段实测）——会在网络面板和错误 toast 里露出，与『中文唯一界面语言』承诺不一致。effort: S

---

## 改进路线图

### Phase 1：立即修 P0（全部 ≤ 半天工作量，建议一个提交内完成 + 回归验证）

| 项 | 内容 | effort |
|---|---|---|
| 1 | **修 P0-1/P0-2 两处 IDOR**：GET /users/conversation 与 archive/unarchive 补 validateConversationOwner（对照同资源族 rename/delete 的现有实现即可，安全审计已给出全部锚点） | S |
| 2 | **修 P0-3 内部 OCR fail-closed**：callbackToken 未配置时拒绝而非放行（AliyunOcrService.java:136 的 hasText 短路反转），/api/v1/internal/ocr/** 移出 permitAll 或改为 token 强校验 | S |
| 3 | **顺手同提交（P1/P2 但分钟级）**：删除 TransactionTestController 与 /api/v1/test/** permitAll；删除 AdminController.java:1111/:1179 两处硬编码密钥（RBAC 已覆盖）；/error 加入 permitAll（修 404→403 改写） | S |
| 4 | **回归验证**：用审计同款两账号法复测越权读/归档，匿名复测 /internal/ocr/*（audit-sec-probe 探针账号可在此时清理） | S |

### Phase 2：近期 P1 高价值（目标 2-4 周，按『面试回报/工作量』排序）

| 项 | 内容 | effort |
|---|---|---|
| 1 | **演示态企业化**：清理 16+ 测试账号；种子数据脚本造出冲突裁决/离线评测/DLQ 重放各至少一条有数据案例；用 3-5 篇真实业务文档替换自指 e2e 文档并入仓库 | M |
| 2 | **快速开始修通**：README 补 MySQL/Redis 安装说明 + IK 插件指引（或 infra.sh 覆盖全部五件套 + 二进制下载步骤）；对齐 ES 版本号 | M |
| 3 | **CI 最小闭环**：GitHub Actions 跑 mvn test + pnpm typecheck/lint + AGENTS.md 要求的 secret 扫描 | M |
| 4 | **可观测性最小闭环**：引入 actuator（health/metrics）+ logback %X 把 MDC 键打进输出 | M |
| 5 | interview.md §3 修订：删除 rescore 过期代码块，统一为 RRF 叙述；顺手校一轮行号 | S |
| 6 | 限流修伪造头绕过：仅在可信反代后信任 XFF（部署拓扑判断或改用连接地址/网关注入） | S |
| 7 | 引用详情端点补会话属主校验（对齐 getGenerationForUser 模式） | S |
| 8 | 吊销链收口：隐式刷新路径校验 Redis 黑名单/tokenCache | S |
| 9 | 支付事务拆分：wxPayService.createOrder / payCallback 移出 DB 事务，事务只包 DB 写 | S |
| 10 | prod ddl-auto → validate（显式迁移接管） | S |
| 11 | Kafka trusted packages 收窄到具体包 | S |
| 12 | Kafka 扩分区（消费端租约已支持多消费者，先扩分区再扩实例） | S |
| 13 | ES 残留补偿：失败路径统一清 ES 或搜索按文件状态过滤 + 删除对账任务 | M |
| 14 | @RestControllerAdvice 全局异常处理 + 收敛 226 处 e.getMessage() 出参 | M |
| 15 | 测试补齐优先序：ConversationService（越权回归）→ UserTokenService 配额记账 → Recharge/WxPay 支付链路 | L |
| 16 | AdminController 按领域拆分 + 统一鉴权/响应封装（与全局异常处理器一并做） | M |
| 17 | WS 多实例：短期 LB 会话粘性并在文档声明单实例约束；长期增量经 Redis pub/sub 广播（DocumentStatusNotifier 已示范） | M |
| 18 | MinIO 计划备份脚本 + 一次真实恢复演练（DEPLOY.md:27 的自认缺口） | M |
| 19 | Tika 升级 3.2.2+ | M |

### Phase 3：长期 P2 选做（按精力挑，部分可在面试中作为『已知债务+偿还计划』主动讲）

| 项 | 内容 | effort |
|---|---|---|
| 1 | ChatHandler/DocumentService/DocumentController 拆分（含去掉 '@author Generated by Assistant' 头注释） | L |
| 2 | 流式 touch 节流或 pipeline 化（砍掉 meta 重写只 expire） | M |
| 3 | 消息分页 + 前端虚拟滚动 | M |
| 4 | vendor manualChunks 分包 + 压缩告警线 | M |
| 5 | README/介绍页补截图与架构图（UI 是现成资产） | S |
| 6 | 登录页死按钮/记住我文案、后端英文消息清理 | S |
| 7 | 文件 magic-byte 检测（扩展名校验升级） | M |
| 8 | JWT URL 泄漏面收敛 + 日志不再打印完整 token | M |
| 9 | AES-GCM 密钥独立于 jwt.secret-key | S |
| 10 | server.shutdown=graceful + WS drain 钩子 | S |
| 11 | 应用 Dockerfile（compose 与 application-docker.yml 已备，激活即可） | S |
| 12 | MinIO bucket/路径统一走配置（消除 4 文件 38 处字面量） | S |
| 13 | deleteDocument 级联失效关联 PENDING 冲突 | S |
| 14 | ES mapping 模板对齐 public 字段 + 清理全量数组死契约 | S |
| 15 | user-activities 接真实数据或下线（模拟数据与品牌承诺冲突） | S |
| 16 | refresh-token 机制二选一：permitAll+轮换吊销，或删除 | M |
| 17 | 非默认 UsageDashboardService 修复（MGET/pipeline）或删除 | S |
| 18 | 熔断状态共享或文档化为单实例已知约束 | M |
| 19 | 双 logger 命名统一 | S |

---

## 结论

**当前水平**：这是一个有真实工程深度的中高级作品集。核心链路（ES 混合检索、Kafka 可靠性处理、文件上传/向量化）的设计质量与实测表现（30-50ms 召回、outbox/DLT/租约/幂等消费）超出多数作品集的『CRUD + 调 API』水准；文档诚实度（DEPLOY.md 与脚本逐条对得上、interview.md 抽查 7/8 一致、介绍页主动声明无客户数据）是罕见的加分项。六维区间 4-7/10 也说明短板同样真实：安全边界在资源级属主校验上有系统性漏改，外部运维面（CI/可观测性/健康检查/备份）接近空白。

**离『可以直接拿去面试的完成态』还差三件事**：

1. **三个 P0 必须修掉**（Phase 1，合计约一天）：两个实测越权直接击穿『权限隔离』这一产品核心卖点——资深面试官拿两个账号几分钟就能复现，这属于『被问出来 = 致命、主动修掉 = 加分』的差距；匿名 OCR 与调试端点同批清掉。
2. **演示态必须换装**（Phase 2 前两项）：知识库从 1 篇自指测试文档换成真实业务文档，治理页面（冲突裁决/评测/DLQ）至少各有一条真实数据，快速开始按 README 能跑通——15 分钟视角的扣分几乎全在这里，而这是投入产出比最高的修复。
3. **运维面补两个最小闭环**：CI（GitHub Actions 跑测试+secret 扫描）与 actuator health/metrics——不必上 Prometheus 全家桶，但『无 CI、无健康端点』会让『企业级』叙事在第一个运维追问下失守。

修完上述三件事（预计 2-3 周），这个项目可以达到『敢让面试官随便点、随便问』的状态。测试断层与巨型类不必在面试前清完——64 个测试文件的域包深度 + 一份诚实的『已知债务与偿还计划』（本文 Phase 2/3 即是）反而是资深工程师视角的加分项；真正不能带进场的是：能被当场复现的安全洞、点开即穿帮的演示态、和跑不通的快速开始。
