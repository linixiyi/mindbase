# PM 体验升级 backlog（2026-10-24，R1 审计产出）

> **链状态（2026-10-24 收口）**：P0 三项全部落地并真机验收（F4 包）；P1 十项全部落地（F5 包，pm-009 四入口含 DLQ 追补；pm-013 按「落地或明确排期/取舍」规则以 B 线 PM 裁决收口）；P2 二十一项目前不排期。剩余已知债务见 F5 复审：DLQ 重放真机待真实死信、pm-012 禁用态 Redis 持久键待改表时补列、pdf-document-viewer stone 色板。

来源：五份视角审计（① 新用户首次价值时刻 ② 核心 RAG 问答旅程 ③ 管理员效率 ④ 信息架构与文案 ⑤ 性能与可靠性感知），合并去重后共 34 项（P0×3、P1×10、P2×21）。

合并与定级规则：
- 同一问题多视角命中合并为一条，保留全部证据引用并注明命中视角；
- 优先级以审计共识为基础，按 PM 价值排序裁定：先保核心旅程可靠（问答可用性与失败恢复），再补企业级治理体验，最后打磨；
- 不新增任何无审计证据项；pm-001 为四视角共识、真人日志头号痛点，PM 按价值排序自 P1 提级 P0；
- PM 本轮对 P0 锚点做了抽查复核（pwd-login.vue:54、DocumentService.java:128-184、ChatHandler.java:187/970、FileUploadRepository.java:47、git ls-files .bak），均与审计一致。

## P0——F4 轮全部落地（≤5 项）

### pm-001 聊天失败是死胡同：无错误分层、无重试、失败回合不入历史（四视角共识，PM 提级 P0）
- 问题：所有 AI 类失败（供应商不可用、熔断、超时、线程池满）被压成同一句「AI服务暂时不可用，请稍后重试」，无错误码、无类别；前端错误气泡是一行红字死文本，无重试按钮；失败问答（含用户提问本身）不落 MySQL，刷新后凭空消失，与屏幕残留的死消息互相矛盾；同类失败在链路上有三种措辞。真人模拟日志在 LLM 不可用期间真实复现，为头号痛点。
- 证据（4/5 视角命中）：
  - ①首价值：frontend/src/views/chat/modules/input-box.vue:139-155（handleErrorPayload 置错误文案且消息永久留存）；chat-message.vue:435（错误态仅一行 chat-error-text）；grep 重试/retry 全聊天模块仅剩限流倒计时文案（input-box.vue:7-15）。
  - ②核心旅程：src/main/java/com/mindbase/service/ChatHandler.java:180-191、:965-972（handleError 压平一切异常，无 code）；ChatHandler.java:610/649 persistConversation 只在成功收尾调用——失败问答不落库。
  - ④IA/文案：ChatHandler.java:187、:970 两处固定文案，:168 线程池满又是另一句；前端兜底再叠「服务器繁忙，请稍后再试」（chat-message.vue:436、input-box.vue:25）——三处措辞不一；操作区 :442-476 仅复制/点赞/点踩；对照：限流 429 已有 code+retryAfterSeconds+倒计时（ChatHandler.java:177-179、input-box.vue:140-141、:151），且 model-provider/index.vue:152-163 与 usage-monitor/index.vue:455-466 已有「标题+原因+重试」错误态组件——分层模式库内已有，唯独核心聊天旅程未用。
  - ⑤可靠性：ChatHandler.java:184-188 真实异常 markFailed 持久化但推前端统一话术；前端 store mapGenerationStatus 把 FAILED 快照留在消息列表（frontend/src/store/modules/chat/index.ts:27-38、:90），刷新后死消息仍在。
  - PM 复核：grep ChatHandler.java 确认 :187、:970 均为固定串「AI服务暂时不可用，请稍后重试」（本轮命令输出）。
- 方案方向：后端 error 帧与生成快照补错误类别码（PROVIDER_UNAVAILABLE / CIRCUIT_OPEN / TIMEOUT / BUSY / RATE_LIMIT 等），前端按类别分层文案并在错误消息行内挂「重试本问」（复用原问题重发）；供应商/密钥类错误附「管理员可在模型供应商页检查配置」引导；统一三处「繁忙/不可用」措辞；失败交互至少持久化用户提问或提供统一会话内失败标记，消除刷新前后不一致（与 pm-015 快照原文泄露同源，实现时一并收口）。
- 验收判据：
  1. 真机：将模型供应商 apiKey 改错后提问，错误消息卡片显示按类别分层的中文文案与「重试本问」按钮；恢复 apiKey 后点击重试成功收到完整回答。
  2. 同一失败场景刷新页面后，该回合仍有可见记录（提问原文不凭空消失）。
  3. `grep -n "AI服务暂时不可用" src/main/java/com/mindbase/service/ChatHandler.java` 命中处均携带错误类别码路径；`grep -rn "重试本问\|重新生成" frontend/src/views/chat/modules/` 命中操作入口；「繁忙/不可用」类文案收敛为统一字典。
- area：both

### pm-002 「记住我」把用户明文密码写入 localStorage
- 问题：登录勾选「记住我」把 userName+password 明文写入 localStorage（localStg 即 window.localStorage），无任何加密或脱敏，本机访问或 XSS 均可读取。两轮体验日志只把「凭证记住、一键登录顺畅」当优点，未察觉明文落盘。
- 证据（①首价值）：frontend/src/views/_builtin/login/modules/pwd-login.vue:53-58（localStg.set('rememberedLogin', { userName, password })）；frontend/src/utils/storage.ts:5；frontend/packages/utils/src/storage.ts:7（'local' → window.localStorage）。PM 复核：grep pwd-login.vue 确认 :26-31 读取、:54 写入 rememberedLogin 含 password 字段（本轮命令输出）。
- 方案方向：记住我只持久化用户名；免输凭证诉求走后端长效刷新令牌；明文口令不得进入任何客户端存储。
- 验收判据：真机 DevTools Console 执行 `JSON.parse(localStorage.getItem('rememberedLogin'))` 结果无 password 字段；再次访问登录页用户名自动回填、密码为空仍可正常登录；`grep -n "password" frontend/src/views/_builtin/login/modules/pwd-login.vue` 在 set 语句处无明文口令写入。
- area：frontend

### pm-003 删除文档按 MD5 全局清理：跨用户数据连带清除
- 问题：两用户上传同一文件时各持一条同 MD5 的 FileUpload 记录、共享同一 MinIO 对象与同一批 ES 分块；删除接口却全局按 MD5 清理所有关联数据——一人删除会连带抹掉另一人的文档记录与检索索引（跨用户数据损失）。
- 证据（③管理员）：上传去重按 (fileMd5,userId) 且表唯一键 uk_file_upload_md5_user（FileUpload.java:19、UploadService.java:825-837）；deleteDocument 全局按 MD5 删：ES deleteByFileMd5（DocumentService.java:128）、MinIO 对象（:137-143）、DocumentVector（:167-171）、chunkInfo（:174-178）、FileUpload deleteByFileMd5 全量删（:181）；按 (md5,userId) 的删法 deleteByFileMd5AndUserId 已存在（FileUploadRepository.java:47）但删除链路未用。PM 复核（本轮命令输出）：DocumentService.java:128/167/176/184 均为全局 deleteByFileMd5；FileUploadRepository.java:47 与 ChunkInfoRepository.java:31 的 scoped 删法存在，且 UploadService.java:697/730 已在别处使用 scoped 版本——仅删除链路用全局版。
- 方案方向：删除改引用计数语义：先只删请求者的 FileUpload 行；仅当该 MD5 无其余持有者时才清理 ES/MinIO/向量/分片元数据（scoped 删法已存在）；或改全局单记录+成员关系表。与 pm-007（删除链路断裂）同路径，实现时一并收口。
- 验收判据：真机双账号 A/B 上传同一文件（同 MD5）后：A 删除自己的文件，B 的知识库列表仍显示该文件、B 提问检索仍命中其内容；B 也删除后 ES/MinIO/向量清理才执行；`git grep -n "deleteByFileMd5AndUserId" src/main/java/com/mindbase/service/DocumentService.java` 命中（删除链路改用 scoped 删法，全局清理仅在最后持有者分支）。
- area：backend

## P1——F5 轮落地或排期

### pm-004 空知识库冷启动死胡同：静态建议问法导向空结果、空态无上传 CTA、全产品无 onboarding
- 问题：新用户登录统一落地 /chat，三条硬编码建议问法在空知识库下全部命中空检索链路（「（本轮无检索结果）」→按 prompt 规则答「暂无相关信息」）且无「先上传文档」出路；知识库空表格只有静态说明、核心动作被命名为通用 CRUD 词「新增」，冷启动用户找不到上传入口；全前端无任何 onboarding——「第一次得到带引用的回答」这一首价值时刻在冷启动下不可达。
- 证据（3/5 视角命中）：
  - ①首价值：frontend/.env:20（VITE_ROUTE_HOME=chat）；chat-list.vue:29-34（三条静态建议）；ChatHandler.java:854-858（空检索 buildContext 返回空串）；LlmProviderRouter.java:447-451（no-result-text）；application.yml:411-416（prompt 规则）；grep 新手/引导/onboarding/快速开始/第一步 全前端零命中；knowledge-base/index.vue:632-641（空态仅静态说明，注释自述「上传入口在表头不重复」）；table-header-operation.vue:46-51（按钮文案 $t('common.add')，「新增」，zh-cn.ts:11）。
  - ②核心旅程：chat-list.vue:29-33 建议硬编码且产品自指（问 MindBase 自身能力），与知识库实际内容脱钩；空库下点建议命中 AgentToolRegistry.java:266「未检索到相关知识库片段。」——首次提问即落空。
  - ④IA/文案：index.vue:635-641 空态无行动入口、上传靠表头小钮；体验日志痛点 2/5 对应。
- 方案方向：欢迎区感知知识库空态切换为冷启动引导（说明先上传、直达 /knowledge-base 的主 CTA），建议问法在有文档后再展示或按文档内容生成；知识库空态中央放「上传第一个文档」主按钮直开上传弹窗（表头入口保留）；表头按钮文案改「上传文档」；空态补一句首价值链说明（上传→向量化→去提问）。
- 验收判据：真机（空知识库账号）：① /chat 欢迎区不出现三条静态建议问法，出现含「去上传文档」CTA 的引导卡，点击跳转 /knowledge-base；② 知识库空态中央有「上传第一个文档」主按钮，点击直接打开上传弹窗；③ 上传至少一个文档完成后，欢迎区建议问法不再导向「暂无相关信息」。
- area：frontend

### pm-005 发送快捷键缺 IME 组合输入防护：中文输入法按 Enter 确认候选词会触发发送
- 问题：input-box 的 Enter 分支仅判 e.key==='Enter' 即 preventDefault 并发送，无 isComposing/keyCode 229 防护；Chrome 系浏览器在 IME 确认键上会下发 key='Enter'、isComposing=true 的 keydown——中文优先产品的核心输入路径存在提前发送半截消息的风险。
- 证据（②核心旅程，代码级核实；IME 实机行为未在审计环境复验，如实标注）：frontend/src/views/chat/modules/input-box.vue:412-420（无组合防护）、:441（textarea v-model，组合期间不更新模型值）；PRODUCT.md 原则 4 中文优先。
- 方案方向：Enter 分支加入组合输入防护（isComposing/229 时放行默认行为、不触发发送），并必须用真实 IME 做实机回归（审计环境无法代验）。
- 验收判据：真机 macOS 中文拼音输入法：输入拼音后按 Enter 确认候选词，消息不发送、候选文本上屏；非组合态按 Enter 正常发送；`grep -n "isComposing\|229" frontend/src/views/chat/modules/input-box.vue` 在 Enter 分支命中防护。
- area：frontend

### pm-006 聊天页「按日期筛选历史消息」在有活跃会话时是死功能
- 问题：聊天页筛选同时发送日期与会话 ID，后端 conversationId 非空分支直接按会话取消息、丢弃 start/end_date；而 store 在无选中会话时自动选中第一个 ACTIVE 会话，会话几乎恒有值——筛选恒无效，激活态只改图标颜色，用户无从察觉。
- 证据（②核心旅程）：frontend/src/views/chat/modules/chat-list.vue:46-56（日期+会话 ID 同发）、:160-167（激活态仅图标变色）；frontend/src/store/modules/chat/index.ts:163-169（自动选中首个 ACTIVE）；src/main/java/com/mindbase/controller/ConversationController.java:54-63（conversationId 分支忽略日期参数）。
- 方案方向：明确筛选语义：后端在 conversationId 分支同时应用日期过滤，或前端把入口改为「跨会话检索我的历史」走无 conversationId 分支；不得保留恒无效控件。
- 验收判据：真机：选中一个含跨日期消息的会话，设置仅覆盖部分消息的日期范围点筛选，返回集合与所选日期一致（范围内显示、范围外不显示）；或该入口已改义/移除且无恒无效控件残留。
- area：both

### pm-007 管理员删除文档链路断裂：删他人文档必 404、第 2 页起删除前端 TypeError 静默失败
- 问题：前端对 admin 放行任意行的删除按钮，但后端按 (fileMd5, 请求者id) 查询，非本人文件查不到直接 404「文档不存在」，admin 分支不可达（与 reindex/retry 的全局查询写法不一致）；翻页后删除走本地 tasks 索引，第 2 页的行不在本地 → TypeError、请求未发出且无任何提示。
- 证据（③管理员）：frontend/src/views/knowledge-base/index.vue:47-49、:177-188（canManageFile 放行）、:253-274（handleDelete，index=-1 时仍访问 tasks.value[index].uploadedChunks，:263）；frontend/src/hooks/common/table.ts:124-133（翻页只 getData 不 getList）；DocumentController.java:70-91（findFirstByFileMd5AndUserId 查询，:83 ADMIN 分支对他人文件不可达），对照 :122、:175 reindex/retry 用全局 findFirstByFileMd5；DocumentService.java:123 同款查询双重确认。
- 方案方向：删除接口与 reindex/retry 对齐（全局查 MD5 后做 owner/admin 判权）；handleDelete 对 index=-1 补守卫直接走 DELETE 接口，或翻页后同步 getList；与 pm-003 引用计数删除同轮收口。
- 验收判据：真机 admin 账号：① 删除任一非本人上传的文档成功（或返回明确权限文案而非「文档不存在」404）；② 造 >1 页数据后翻到第 2 页删除任一行成功且列表刷新，Console 无 TypeError、Network 可见 DELETE 请求发出。
- area：both

### pm-008 管理端聊天记录页无分页全量加载
- 问题：管理端历史查询端点无分页参数，无 userid 无日期时 findAll 整张对话表、每条记录展开 user+assistant 两条全量返回，前端一次性 v-for 渲染全部消息，随日常使用必然劣化，与「企业级可信」定位不符。
- 证据（②核心旅程）：src/main/java/com/mindbase/controller/AdminController.java:761-823（端点无分页参数）；ConversationService.java:249-255（findAllByOrderByTimestampAsc）、:258-292（toMessageHistory 展开）；frontend/src/views/chat-history/index.vue:66-80、:129-151（全量接收渲染）。
- 方案方向：管理端历史查询引入分页（时间游标或页码）+ 按用户/会话维度聚合的列表-详情两级结构，前端只渲染当前页。
- 验收判据：造 >200 条消息数据后：管理端请求带分页参数（Network 可见 page/size 或 cursor），单次响应条数 ≤ 页大小；页面仅渲染当前页条数；按用户筛选走服务端过滤。
- area：both

### pm-009 四类已实现的后端治理能力无任何界面入口（冲突裁决/DLQ 重放/索引重建回滚/离线评测）
- 问题：产品文档与 README 宣称的企业差异化能力有完整后端 REST 接口，但管理后台 11 个菜单无入口、前端零调用：文档冲突人工裁决（PRODUCT.md 定位支柱）、死信队列查看/重放（README 承诺只完成「进入」一半）、知识索引重建/回滚、离线 RAG 评测。
- 证据（2/5 视角命中）：
  - ③管理员：DocumentConflictAdminController.java:28-56（/admin/document-conflicts 列表 + /{id}/resolve 裁决）；FileProcessingDeadLetterConsumer.java:19（注释明言落库「供排查和人工重放」，但 controller/service 目录 grep DeadLetter 零引用，无查看/重放 API 更无 UI）；KnowledgeIndexAdminController.java:54-77（rebuild/rollback 前端零引用）；grep conflict / knowledge-index|reindex 于 frontend/src 均零命中；PRODUCT.md:23、:41、README:14。
  - ④IA/文案：RagEvalAdminController.java:35（admin/eval /run）；frontend/src 全量 grep『document-conflicts|admin/eval|knowledge-index』零匹配；routes.ts 无对应路由；PRODUCT.md:41-42、README.md:37-44。
- 方案方向：管理后台补最小可用入口：冲突裁决列表（保留/废弃，复用现成 resolve API）、DLQ 记录列表+重放按钮（落库记录已在）、索引重建/回滚入口、评测触发与结果页（可并入知识库/用量监控管理区）；后端全部现成，只缺前端视图。
- 验收判据：真机 admin：四个入口均可从菜单到达且操作走通——冲突列表可 resolve（Network 命中 /admin/document-conflicts/{id}/resolve）、DLQ 列表可重放、索引 rebuild 可触发并有进行/完成反馈、评测 run 可触发并展示结果。
- area：frontend

### pm-010 邀请码「复制链接/复制话术」产出占位域名死链
- 问题：分享基址硬编码 https://your-domain.example/#/login/register，管理员复制链接发给员工即打不开——主分享路径产出错误结果且提示「成功」、无任何告警。
- 证据（③管理员）：frontend/src/views/invite-code/index.vue:105（硬编码）、:267-269（直接拼接）、:135-154（两按钮写入剪贴板并提示成功）。
- 方案方向：改用 window.location.origin 运行时推导（或纳入前端配置项），本地/部署环境自动成立。
- 验收判据：真机点击「复制链接」，剪贴板内容以当前 origin 开头（本地为 http://localhost:9527）且粘贴打开即达注册页；`git grep "your-domain.example" frontend/src` 零命中。
- area：frontend

### pm-011 限流配置读取失败时回填硬编码默认值，且可直接保存覆盖线上真实配置
- 问题：getRateLimits 失败且无缓存时限流表单回填硬编码默认值，只要表单非空即渲染可编辑+保存按钮，无二次确认直接 PUT——一次网络抖动+一次顺手保存即用客户端猜测值覆盖真实限流参数（同页用量总览有专门错误态，限流面板没有）。
- 证据（③管理员）：frontend/src/views/usage-monitor/index.vue:43-74（默认值硬编码）、:115-117（失败回填默认值）、:286-290（非空即渲染表单+保存）、:121-139（直接 PUT）；对照 :455-467 总览有加载失败态。
- 方案方向：读取失败进入与总览一致的错误态并禁用保存（或保存前 diff 提示）；默认值仅供新建初始化，不作为失败回填。
- 验收判据：真机停后端/断网后打开用量监控：限流面板显示加载失败态且保存按钮禁用，Network 无 PUT rate-limit 请求；恢复后可正常读取-编辑-保存。
- area：frontend

### pm-012 用户管理无禁用/启用操作，状态列只读
- 问题：用户表格展示「是否启用」列、筛选也支持状态，但操作列仅「分配组织标签/追加 Token」两钮，前后端均无启停端点——多租户企业场景下管理员无法停用离职/违规账号。
- 证据（③管理员）：frontend/src/views/user/index.vue:57-66、:127-142（操作列仅两钮）；grep AdminController 全部 @Mapping（/users/list、/users/{id}/org-tags、/users/{id}/tokens/add、create-admin）无用户状态切换，UserController 端点同样没有。
- 方案方向：补 PATCH /admin/users/{id}/status + 表格行内开关（带确认弹窗）；禁用用户联动踢出会话。
- 验收判据：真机 admin 禁用某测试账号后：该账号后续请求被拒（401/403）且已登录会话失效、重新登录被拒；重新启用后可正常登录使用；Network 可见 PATCH /admin/users/{id}/status。
- area：both

### pm-013 语言承诺未兑现：切换入口被隐藏 en-us 不可达，且 11 个业务页未接 i18n 形成混合态
- 问题：品牌承诺「中文为主、英文为完整备选、不做半成品翻译」两条都不成立——其一，语言切换挂在 multilingual.visible:false 之下，全应用无入口，en-us 实际不可达；其二，即便放开，仅登录页接了 $t，11 个业务页标题/空态/按钮/表头硬编码中文，切英文后是英文菜单+中文主体混合态。（两份审计对入口当前可见性的描述不一致：①核实被主题配置隐藏，④称顶栏切换在位；以更具体的配置项核实为准，实现轮需先复核 settings.ts 当前值。）
- 证据（2/5 视角命中）：
  - ①首价值：frontend/src/theme/settings.ts:16（multilingual.visible:false）；login/index.vue:54-55 与 global-header/index.vue:177-178 两处 LangSwitch 均挂该开关下；PRODUCT.md:57 语言承诺。
  - ④IA/文案：global-header/index.vue:179-181（顶栏切换组件在位）；frontend/src/views 全量 grep『$t(』仅 _builtin/login 5 文件 31 处；页面标题硬编码中文 10 处（如 model-provider/index.vue:143、chat-history/index.vue:86、knowledge-base/index.vue:605）；空态文案硬编码（conversation-sidebar.vue:108、chat-history/index.vue:146）；PRODUCT.md:74「不做半成品翻译」承诺。
- 方案方向：二选一并写进品牌承诺执行：A) 至少登录页放开语言切换，并优先补 11 个视图的标题/空态/主按钮三档 i18n（长尾分期）；B) 收敛为仅中文（隐藏语言切换）并同步修订 PRODUCT.md 语言承诺。不允许维持「承诺英文完整备选但不可达/半成品」的现状。
- 验收判据：按选定路线可判定——A：登录页出现语言切换，切 en 后 11 个业务页的页题/空态/主按钮三档均为英文；B：全应用无语言切换入口且 PRODUCT.md 语言承诺改为仅中文。
- area：both
- **PM 裁决（2026-10-24，F5 轮记录）**：选 **B 线——收敛为仅中文**。理由：目标用户（开发者本人 + 中文语境面试官）下 A 线（11 视图 × 页题/空态/主按钮三档 i18n）为低价值长尾；切换入口已由 settings.ts multilingual.visible:false 隐藏（现状即 B 的应用态）；执行项仅剩 PRODUCT.md 语言承诺修订为「中文为唯一界面语言」（归 F6 文档同步轮落地）。取舍依据：PRODUCT.md 原则 4「中文优先」的现实延伸，且消除「承诺完整英文但不可达」的诚实性缺口

## P2——记录不排期

### pm-014 命令面板无全局快捷键、只搜菜单标题
- 问题：面板唯一入口是顶栏搜索小图标，无 Ctrl/Cmd+K 全局绑定与任何提示；且 search() 只在菜单标题里过滤，检索不到任何知识内容——对检索型产品可发现性与价值双弱。
- 证据（2/5 视角命中）：①首价值：frontend/src/layouts/modules/global-search/index.vue:12（唯一触发钮）、components/search-modal.vue:38-45（仅 routeStore.searchMenus 过滤）、:93-98（仅面板内 Escape/Enter/箭头）；grep onKeyStroke/ctrlKey/metaKey 全 src 仅两处均非打开面板。④IA/文案：同法 grep『ctrl|⌘|快捷键』无任何快捷键提示或全局绑定。
- 方案方向：注册 Cmd/Ctrl+K 全局打开并在触发钮 tooltip 标注键帽；面板结果接知识库检索（HybridSearch 能力可复用）。
- 验收判据：真机按 Cmd/Ctrl+K 打开面板、Esc 关闭；触发钮 tooltip 显示快捷键键帽。
- area：frontend

### pm-015 生成快照路径把原始异常文本当回答正文渲染
- 问题：markFailed 存 e.getMessage() 原文，快照接口原样返回，前端以 snapshot.errorMessage 兜底为消息 content——LLM 不可用（chunk 停止→8 秒停滞→快照轮询）场景恰会触发，与 handleError 统一话术形成两套用户可见文案，且向用户泄露技术细节。
- 证据（②核心旅程）：ChatHandler.java:184、:239（markFailed 存原文）；ChatGenerationStateService.java:116-118、:124-133、:171（快照读取）；ChatController.java:66-78（返回前端）；frontend/src/store/modules/chat/index.ts:90（errorMessage 兜底为正文）。
- 方案方向：快照 FAILED 状态只返回类别码+统一用户话术，原始 errorMessage 留给服务端日志/管理端诊断视图（与 pm-001 错误分层同源，可一并收口）。
- 验收判据：模拟 LLM 失败走快照路径，前端消息正文为统一中文话术，不含异常类名/堆栈片段；`grep -n "errorMessage" frontend/src/store/modules/chat/index.ts` 不再直接渲染为 content。
- area：both

### pm-016 会话只能归档/恢复：无重命名、无删除
- 问题：会话控制器仅 5 个 Mapping，无 PATCH/rename、无 DELETE；标题由首问自动覆盖且 50 字截断后不可修改；企业场景下无彻底删除手段。
- 证据（②核心旅程）：src/main/java/com/mindbase/controller/ConversationSessionController.java:31-116；ConversationService.java:190-201；frontend/src/views/chat/modules/conversation-sidebar.vue:130-156（仅归档/恢复按钮）。
- 方案方向：补会话重命名与删除（删除需级联清理消息并考虑权限），侧栏操作区二级菜单收纳。
- 验收判据：真机可重命名会话（侧栏与列表即时更新）并可删除会话（消息级联清理，DB 无孤儿消息行）。
- area：both

### pm-017 引用预览载荷向 localStorage 无限累积
- 问题：每次点击引用写一个带时间戳+随机串的 reference-preview:* 键，含 evidenceSnippet/matchedChunkText 等知识库片段文本，全前端无任何 removeItem 清理。
- 证据（②核心旅程）：frontend/src/views/chat/modules/chat-message.vue:248-261（写）；reference-preview-page.vue:46-79（只读不删）；grep removeItem 零命中。
- 方案方向：预览页读取后即 removeItem 该键或定期清理同前缀旧键；敏感场景改 sessionStorage/BroadcastChannel 传递。
- 验收判据：真机连续点击 3 次引用预览并返回后，DevTools Application 中 reference-preview:* 键数量不随点击次数累积。
- area：frontend

### pm-018 流式生成期间无条件吸底滚动
- 问题：对消息列表浅拷贝数组 watch，任何一次 chunk 追加都触发 scrollToBottom，无「用户已上滚则暂停吸底」判定——长回答流式期间回看被反复拽回。
- 证据（②核心旅程）：frontend/src/views/chat/modules/chat-list.vue:16-25（读毕全文件确认无上滚判定）。
- 方案方向：滚动前检测距底 <N px 才吸底；上滚暂停并在底部显示「回到底部」浮标。
- 验收判据：真机长回答流式期间上滚 >200px，视口不再被拽回；出现「回到底部」浮标，点击回底。
- area：frontend

### pm-019 上传链路过程感缺口：后台分片失败完全静默、保存按钮无 loading
- 问题：分片/合并失败仅 console.error 无任何全局通知（Pinia store 应用级，跨页面上传失败无感知）；MD5 计算（大文件数秒）期间保存按钮仅 disabled 无 loading 与过程文案。
- 证据（⑤可靠性，代码级核实；上传流程审计环境未实测）：frontend/src/store/modules/knowledge-base/index.ts:219-222（catch 仅 console.error+置 Break）、:127（首步 await calculateMD5）；upload-dialog.vue:57-65、:173（仅 :disabled 无 :loading）。
- 方案方向：分片/合并失败发全局通知并附「去知识库」入口；保存按钮加 loading 并在哈希阶段给出过程文案。
- 验收判据：真机断网状态发起上传出现全局失败通知；选择大文件后保存按钮进入 loading 且有阶段文案。
- area：frontend

### pm-020 WebSocket 长时间断线无升级提示、唯一「重连失败」toast 不可达
- 问题：重连次数无限（retries 恒真），onFailed 仅在重试判定 false 时触发而该条件不可达；长时间断线用户只见小脉动点，无行动指引。
- 证据（⑤可靠性）：frontend/src/store/modules/chat/index.ts:278-286（retries=()=>allowReconnect.value 恒真、:281-285 onFailed toast 不可达）；input-box.vue:51、:462-472（脉动点+禁发）。
- 方案方向：连续 N 次重连失败升级提示（「已尝试重连 X 次，请检查网络或刷新页面」）并提供手动「立即重连」动作。
- 验收判据：真机停后端 >1 分钟：连接状态区出现升级文案与「立即重连」动作；恢复后端后点击即重连成功。
- area：frontend

### pm-021 浏览器刷新不恢复正在浏览的会话
- 问题：刷新后固定回到 updatedAt 最新的 ACTIVE 会话；后端已有 Redis current_conversation（TTL 7 天）记录但前端从未用于恢复；切换会话也不更新 updatedAt。
- 证据（⑤可靠性）：frontend/src/store/modules/chat/index.ts:163-169（自动选中首个 ACTIVE）；ConversationService.java:152-158（switch 只写 Redis）、:84-85（列表按 updatedAt 倒序）。
- 方案方向：conversationId 放入路由（query/param）实现刷新即恢复，或刷新时优先恢复 Redis current_conversation 再退回最新活跃会话。
- 验收判据：真机切到非最新会话 B 后刷新浏览器，仍停留在会话 B（路由含会话标识或恢复后端记录）。
- area：both

### pm-022 网络/超时英文技术串直达用户；REST 429 的 retryAfterSeconds 被丢弃
- 问题：全局错误出口仅映射 5 条邀请码/注册文案，网络/超时类英文技术串直接 toast；REST 429 响应含 retryAfterSeconds 但登录等表单无倒计时（倒计时 UX 仅聊天 WS 路径实现）。
- 证据（⑤可靠性）：frontend/src/service/request/index.ts（onError）+ shared.ts:6-17（backendMessageMap 仅 5 条）；frontend/packages/axios/src/options.ts:35（默认 10s 超时）；UserController.java:129-134（响应含 retryAfterSeconds）；input-box.vue:67-72、:139-155（仅 WS 路径倒计时）。
- 方案方向：错误出口统一映射网络/超时类为中文用户文案；REST 429 复用聊天倒计时逻辑（按钮禁用+秒级倒计时）。
- 验收判据：真机断网触发请求，toast 为中文文案无英文技术串；触发登录限流后登录按钮显示秒级倒计时。
- area：frontend

### pm-023 文档列表全量加载 + orgTag 名 N+1 查询 + 内存分页
- 问题：/documents/accessible 先取全部再逐条转 DTO，每个文件逐条查 orgTag 名（N+1），paginateList 只是事后 subList；前端还叠一层本地切片——千级文档即 10+ 倍查询，管理端首页线性变慢。
- 证据（③管理员）：DocumentController.java:223-272、:320-343（:340 + :931-948 每文件 getOrgTagName→findByTagId 逐条查）；frontend/src/views/knowledge-base/index.vue:20-45（前端二次切片兜底）。
- 方案方向：SQL 层真分页 + orgTag 名一次性批量查询（或复用 OrgTagCacheService 缓存）；前后端去掉双重分页。
- 验收判据：造千级文档后接口返回单页数据，SQL 日志/Hibernate 统计无逐条 orgTag 查询；响应耗时不再随总量线性增长。
- area：backend

### pm-024 知识库列表无筛选与批量操作、上传仅单文件
- 问题：列表无状态/组织/文件名筛选、无多选批量删除（找「向量化失败」文档只能逐页翻）；上传 NUpload max=1 multiple=false，批量入库一次一个。
- 证据（③管理员）：frontend/src/views/knowledge-base/index.vue:78-192、:606-617（无 selection 列/筛选表单，对照 user 页有 UserSearch）；modules/upload-dialog.vue:152-161。
- 方案方向：加状态/组织标签/文件名筛选与多选批量删除；上传弹窗放开多文件（分片队列已支持逐个入队）。
- 验收判据：真机按状态=FAILED 筛选仅显示失败文档；一次选择 3 个文件上传全部入队完成。
- area：frontend

### pm-025 邀请码禁用是不可逆单行道、有效期后端能力闲置
- 问题：已禁用行无启用按钮；后端 update 无 enabled 参数且 controller 恒传 expiresAt=null；前端创建表单无有效期字段（payload 恒 expiresAt:null）——服务层完整的 expiresAt 校验逻辑闲置。
- 证据（③管理员）：frontend/src/views/invite-code/index.vue:228-243、:337-341；AdminController.java:470-478；InviteCodeService.java:62、:132-155。
- 方案方向：禁用/启用对称操作（PATCH enable）；创建表单加可选有效期字段透传。
- 验收判据：真机停用的邀请码可重新启用；创建时可填有效期且 payload expiresAt 非恒 null（Network 可见）。
- area：both

### pm-026 组织标签父级级联只取当前分页数据、层级结构不可见
- 问题：新增下级标签时父级级联 options 绑定当前页（每页 10 条）data，选不到其他页的父标签；表格无 parentTag 列无树形展开，层级结构在管理端不可见。
- 证据（③管理员）：frontend/src/views/org-tag/modules/org-tag-operate-dialog.vue:115-117；org-tag/index.vue:11-59（后端已有 /org-tags/tree 未用）。
- 方案方向：级联改调 /admin/org-tags/tree 全量（标签量级小）；表格加父级列或树形展开。
- 验收判据：标签 >10 条（跨页）时新增下级可选到任意页的父标签（Network 命中 tree 接口）；列表可见层级关系。
- area：frontend

### pm-027 管理员无全量充值订单视图、支付状态仅手动查询
- 问题：/recharge/orders 从 token 取 userId 仅返回本人订单，AdminController 无订单列表端点（仅套餐 CRUD），对账/营收不可见；支付弹窗无轮询，状态靠手动点「我已完成支付」。
- 证据（③管理员）：RechargeController.java:116-152（:122-138）；AdminController.java:1036-1089（仅 recharge-packages）；frontend/src/views/recharge/index.vue:152-174（checkPayStatus 绑在按钮上）。
- 方案方向：补 /admin/recharge/orders（含用户/状态/时间筛选）供对账；支付弹窗 3-5 秒轮询直至终态。
- 验收判据：admin 可见全量订单列表并可筛选；发起支付后弹窗自动轮询并在回调到达后自动进入终态，无需手动点击。
- area：both

### pm-028 rerank 供应商配置界面不可见不可测、LLM 故障转移链不可见
- 问题：模型配置页仅聊天/Embedding 两 scope，rerank 只在环境变量——失效时检索静默降级为 RRF，管理员无任何界面可诊断（PRODUCT.md 把 gte-rerank 列为差异化能力）；LLM 备选顺序（按 provider 编码字母序）在界面上也不可见。
- 证据（③管理员）：frontend/src/views/model-provider/index.vue:146-149、:114-134；application.yml:340-353；ModelProviderConfigService.java:87-112；PRODUCT.md:23。
- 方案方向：模型配置页补 rerank scope（或至少只读状态+测试连接）；展示 LLM 故障转移链顺序。
- 验收判据：模型配置页可见 rerank 当前配置状态并可发起测试连接（返回成功/失败）；页面展示 LLM 主备顺序。
- area：both

### pm-029 「模型配置/服务商/模型供应商」三种称呼并存
- 问题：中文菜单与页题叫「模型配置」，页面内部叫「服务商/模型服务商」，PRODUCT.md 与英文 locale 叫「模型供应商/Model Providers」——同一对象命名漂移，用户按菜单名搜帮助或对照英文界面无法对齐。
- 证据（④IA/文案）：zh-cn.ts:169、en-us.ts:169；model-provider/index.vue:143-144、:167-168；PRODUCT.md:13。
- 方案方向：统一术语字典并落 locale：中文统一「模型供应商」（与 PRODUCT.md、英文对齐），「服务商」作一致简称；同步修订菜单 i18n 与 h1。
- 验收判据：`git grep "模型配置" frontend/src` 零命中（locale/菜单/h1 统一为「模型供应商」）。
- area：frontend

### pm-030 租户概念三名并存：组织标签/所属空间/组织
- 问题：菜单与管理页叫「组织标签」，上传对话框叫「所属空间/空间」，个人中心叫「组织」——员工在「空间」里传文件、管理员在「组织标签」里管权限，词汇映射只能靠猜。
- 证据（④IA/文案）：upload-dialog.vue:123、:130、:139；org-tag/index.vue:93（通篇无「空间」）；personal-center/index.vue:294。
- 方案方向：定一个面向员工的主词（「组织」或「空间」二选一），管理页菜单名与首屏给出等价说明；上传空态补责任方引导（指明联系管理员）。
- 验收判据：三处界面租户称呼统一为主词（或管理页首屏有明确等价说明）；`git grep "所属空间" frontend/src` 仅剩等价说明处命中或零命中。
- area：frontend

### pm-031 菜单扁平交错、order 冲突、标签页标题丢品牌名、「聊天记录」命名歧义
- 问题：11 项扁平菜单员工页与管理页交错（personal-center/recharge 夹在管理页之间、user 排末尾）；invite-code 与 usage-monitor 同 order:6、user 无 order；标签页标题只剩裸路由名（无 MindBase）；管理页「聊天记录」实为全员审计视图，与员工侧栏「对话列表」同名异义。
- 证据（④IA/文案）：frontend/src/router/elegant/routes.ts:87、:180、:184-193、:60；router/guard/title.ts:9-11、store/modules/app/index.ts:76-80（useTitle 设裸路由名）；conversation-sidebar.vue:63；PRODUCT.md:56「应用标题：MindBase」。
- 方案方向：菜单分「工作台/管理」两组并修 order；标签页标题统一「页面名 · MindBase」；「聊天记录」更名能区分全员审计语义的名字（如「问答审计」）。
- 验收判据：`git grep -n "order: 6" frontend/src/router/elegant/routes.ts` 无重复且 user 有 order；真机标签页标题含「MindBase」后缀；管理菜单出现审计语义命名。
- area：frontend

### pm-032 注册成功需重输用户名、忘记密码无入口
- 问题：注册成功仅 toggle 回登录模块，不回填用户名；reset-pwd 模块存在但登录表单无任何链接指向——忘记密码时用户在界面上无路可走。
- 证据（①首价值）：register.vue:69-71；pwd-login.vue:26-32、:85-104；grep reset-pwd 全 src 无 UI 链接（仅路由定义 elegant/routes.ts:103）。
- 方案方向：注册成功自动登录或至少回填用户名；登录框补「忘记密码」入口（管理员站点可指向管理员联系方式或自服务重置）。
- 验收判据：真机注册成功后登录表单用户名已回填（或直接进入应用）；登录框可见「忘记密码」入口且可达 reset-pwd 或替代指引。
- area：frontend

### pm-033 注册页向新用户展示开发者占位文案、邀请码策略与后端可配模式脱节
- 问题：邀请渠道二维码为空串时走 placeholder 分支渲染开发注释文案（新用户第一屏可见）；前端恒定强制邀请码必填、宣称邀请制，而后端 validateRegistrationPolicy 支持 OPEN 模式（默认 INVITE_ONLY，改配置即出现前后端脱节/UI 阻断）。
- 证据（①首价值）：constants/invite-channel.ts:4（qrCodeImageUrl 空串）；register.vue:134-138（placeholder 分支）、:42（inviteCode 恒必填）；zh-cn.ts:218（inviteCodeTip 恒称邀请制）；UserService.java:138-150；application.yml:94-99。
- 方案方向：注册策略由后端接口下发驱动表单（必填项、提示语随 mode 变化）；占位文案改为面向用户的「请联系管理员获取邀请渠道」。
- 验收判据：后端切 OPEN 后真机注册表单邀请码非必填且提示语相应变化；INVITE_ONLY 下占位区不再出现开发者注释文案。
- area：both

### pm-034 控制器 .bak 备份文件被 git 跟踪并公开
- 问题：DocumentController.java.bak 与正式文件并存于控制器目录且被 git 跟踪公开在仓库，影响「真实企业在用的系统」的仓库卫生观感（作品集受众为面试官）。
- 证据（③管理员）：git ls-files | grep -i "\.bak" 命中 src/main/java/com/mindbase/controller/DocumentController.java.bak。PM 复核：本轮同命令确认仍被跟踪。
- 方案方向：删除 .bak（git 历史已保留版本）；如需本地备份改用全局 gitignore 的 *.bak 规则。
- 验收判据：`git ls-files | grep -i "\.bak"` 零输出。
- area：docs

## 实现轮约定

- 实现工作流模型：GLM-5.3-Flash。
- 门禁（每项实现通过标准）：
  - `cd frontend && pnpm typecheck` —— 0 error；
  - `cd frontend && pnpm exec eslint <改动文件>` —— 0 error；
  - detect 检查无 error 级；
  - 旧色残留零命中（对改动文件 grep 旧色值零命中）；
  - 涉后端改动加 `mvn -q -DskipTests compile` 通过。
- 轮次：P0 三项（pm-001~003）F4 轮全部落地；P1 十项（pm-004~013）F5 轮落地或明确排期；P2 二十一项仅记录不排期。
