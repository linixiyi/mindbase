# MindBase

MindBase 是一个企业级的 AI 知识库管理系统，围绕检索增强生成（RAG）工作流构建：上传文档后自动解析、分块、向量化并写入 Elasticsearch，用户用自然语言提问，系统基于知识库片段生成带引用的中文回答。

项目介绍页（GitHub Pages）：https://linixiyi.github.io/mindbase/

核心技术栈：Spring Boot 3.4 / Vue 3 / MySQL / Redis / Elasticsearch / Kafka / MinIO / WebSocket。

## 核心功能

### 知识库管理

- 文件分片上传、断点续传、秒传（MD5 校验）
- 支持 PDF（LiteParse 解析，保留页码，可开 OCR）、Office、文本等常见格式
- 文档可设为公开或私有，并通过组织标签（org tag）做细粒度权限
- 文档处理全链路走 Kafka：解析 → 分块 → Embedding → 写入 ES，失败自动重试并进入死信队列

### AI 问答

- 流式输出（WebSocket），ReAct 风格的工具调用循环
- 回答强制基于检索片段，句末带 `(来源#编号: 文件名 | 第X页)` 引用标注
- 可选的引用逐句校验（citation verification），无依据的引用会被标记
- 问答请求级截止时间：检索、重排、工具调用共用一个时间预算

### 检索

- 混合检索：BM25 与 KNN 双路并行召回，RRF 融合，可接 gte-rerank 重排
- 父子检索（small-to-big）：命中小分块后自动扩展相邻分块给模型，引用仍指向命中分块
- 上下文检索（Contextual Retrieval，可选）：入库时由 LLM 为每个分块生成上下文说明，提升召回

### 企业级能力

- 多租户：组织标签 + 用户/组织关系驱动权限过滤
- 邀请码注册、注册模式可配（开放 / 邀请制 / 关闭）
- Redis 令牌桶限流（登录、注册、聊天、LLM、Embedding 接口）
- 模型调用治理：按供应商熔断、舱壁隔离、指数退避重试、LLM 主备切换
- Token 配额与用量统计，支持按总量或按日重置两种策略，可接微信支付充值（可选）
- 管理后台：文档冲突裁决、死信队列人工重放、知识索引重建/回滚、离线评测（含人工复核与评委一致率）、用户启停与 Token 配额调整、邀请码 / 组织标签 / 模型供应商主备配置管理、充值订单与套餐全量视图、rerank 连通性测试与主备转移链可视化

### 文档冲突检测

新文件向量化完成后异步扫描：相似度高但数字 / 版本号 / 日期不一致的分块对记为疑似冲突，管理员在后台人工决定保留哪一篇，可选 LLM 二次确认。

### 离线 RAG 评测

- 内置评测框架：检索看 Recall@K / MRR，生成看 LLM-as-a-judge，支持人工抽检与评委一致率（含 Cohen's kappa）
- 自带 50 条评测集（`src/main/resources/eval/mindbase-eval-v1.jsonl`），历史评测报告见 `docs/eval/reports/`

## 技术栈

后端：

- 框架：Spring Boot 3.4.2（Java 17）
- 数据库：MySQL 8.0 + Spring Data JPA
- 缓存：Redis
- 搜索：Elasticsearch 8.10.x（本地容器镜像 8.10.4）
- 消息队列：Apache Kafka
- 文件存储：MinIO（S3 兼容）
- 文档解析：Apache Tika + LiteParse（PDF，可选 Tesseract / 阿里云 OCR）
- 安全：Spring Security + JWT
- AI：OpenAI 兼容 LLM API（DeepSeek / 智谱等）+ Embedding API + 重排 API
- 实时通信：WebSocket（WebFlux）

前端：

- Vue 3 + TypeScript + Vite
- UI：Naive UI，样式：UnoCSS + SCSS
- 状态：Pinia，路由：Vue Router
- 包管理：pnpm

## 项目结构

```bash
src/main/java/com/mindbase/
├── MindBaseApplication.java     # 主应用程序入口
├── client/                      # LLM / Embedding / Rerank 客户端与熔断重试
├── config/                      # 配置类
├── consumer/                    # Kafka 消费者（解析 + 向量化链路）
├── controller/                  # REST API
├── entity/ model/               # 数据实体与领域模型
├── exception/                   # 自定义异常
├── handler/                     # WebSocket 处理器
├── repository/                  # 数据访问层
├── service/                     # 业务逻辑（检索、评测、冲突检测等）
└── utils/                       # 工具类
```

```bash
frontend/
├── packages/                    # 可复用模块
├── public/                      # 静态资源
├── src/
│   ├── components/ layouts/     # 组件与布局
│   ├── router/ service/ store/  # 路由、API 集成、状态
│   ├── views/                   # 页面
│   └── locales/                 # 国际化文案
└── vite.config.ts               # 构建配置（dev 模式代理后端 8081）
```

## 快速开始

本地端口总览：

| 组件 | 端口 |
| --- | --- |
| 前端 Vite dev | 9527 |
| 后端 Spring Boot | 8081 |
| MySQL | 3306 |
| Redis / Kafka / Elasticsearch | 6379 / 9092 / 9200 |
| MinIO | 19000（docker compose）/ 9000（`infra.sh`） |

### 1. 前置环境

- JDK 17、Maven（仓库自带 `./mvnw`，内置 Maven 3.9.11，无全局 Maven 时可用）
- Node.js >= 18.20、pnpm >= 8.7
- MySQL 8（推荐本机安装；也可走第 3 步的容器全量变体）
- Redis、Kafka、Elasticsearch 8.10.x、MinIO（推荐用 `docs/docker-compose.yaml` 启动）

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填写模型 API Key 等配置
```

应用启动时由 `DotenvEnvironmentPostProcessor` 自动加载**启动目录**下的 `.env`（读取的是进程工作目录而非 classpath，因此 `mvn spring-boot:run` 必须在仓库根目录执行，IDE 跑主类时把 Working directory 设为仓库根）。`SPRING_PROFILES_ACTIVE` 也由它注入，无需手动指定 profile。

本地最小必填变量（对应 docker compose 路径，模板缺失时按此核对）：

| 变量 | 说明 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE=dev` | 必填。缺失时走基础配置、Elasticsearch 默认 https，连本地 http 的 ES 容器启动即失败 |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | MySQL 连接；不填默认 `localhost:3306/PaiSmart`、root、空密码 |
| `SPRING_DATA_REDIS_PASSWORD` | compose Redis 带密码，需与 compose 配置一致 |
| `ELASTICSEARCH_PASSWORD` | compose ES 需要认证 |
| `MINIO_ENDPOINT` | compose 的 MinIO 在 **19000**（`http://localhost:19000`）；`infra.sh` 路径才是 9000 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 与 MinIO 实例一致 |
| `JWT_SECRET_KEY` | Base64 字符串（`openssl rand -base64 32` 生成）；缺失时登录签发 token 直接失败 |
| `DEEPSEEK_API_KEY` / `EMBEDDING_API_KEY` | 问答与向量化必需 |
| `ADMIN_BOOTSTRAP_ENABLED` / `_USERNAME` / `_PASSWORD` | 首次启动建 admin 时临时置 `true`，密码 ≥ 12 位，建完改回 `false` |

完整变量清单与默认值见 `.env.example`。

### 3. 启动本地基础服务

**路径 A（推荐）：Docker Compose（`docs/docker-compose.yaml`）**

```bash
cd docs
docker compose up -d redis kafka es minio      # MySQL 也可容器化：docker compose up -d
cd ..
```

- ES 容器启动时会自动安装 analysis-ik 分词器——索引 mapping 依赖 IK，这条路径开箱即用。
- MinIO 映射在 **19000**（控制台 19001），记得在 `.env` 设 `MINIO_ENDPOINT=http://localhost:19000`；bucket `uploads` 不会自动创建，首次上传前按 `docs/runbook.md`「MinIO 镜像说明」手动建一次。
- MySQL 若也走容器：容器 MySQL 首次启动后需手动建库 `CREATE DATABASE PaiSmart CHARACTER SET utf8mb4;`（本机 3306 已被占用时先停掉其一）。

**MySQL 本机安装（Homebrew，与仓库日常开发一致）：**

```bash
brew install mysql@8.0
brew services start mysql@8.0
mysql -uroot -e "CREATE DATABASE PaiSmart CHARACTER SET utf8mb4;"
```

**路径 B：`./infra.sh`（只管 minio / kafka / elasticsearch，MySQL、Redis 需自行安装）**

```bash
./infra.sh start          # 启动全部（minio / kafka / elasticsearch）
./infra.sh status         # 查看状态
./infra.sh urls           # 查看访问地址
```

- 该脚本假设二进制已下载解压到 `~/Downloads`（`minio1/`、`kafka/kafka_2.13-3.9.0`、`elasticsearch-8.10.0`），可用 `MINIO_DIR` / `KAFKA_DIR` / `ELASTICSEARCH_DIR` 环境变量改路径。
- **此路径的 ES 不带 IK 分词器**：后端首次启动建索引会失败（报错会提示安装 `analysis-ik`，插件版本需与 ES 小版本一致）。不想手动装插件就用路径 A。
- 此路径的 MinIO API 在 9000（`.env` 默认值即 9000）。

**PDF 解析（上传 PDF 前安装一次即可）：**

```bash
./infra.sh install liteparse      # 即 npm install -g @llamaindex/liteparse
```

### 4. 启动后端

```bash
mvn spring-boot:run       # 或 ./mvnw spring-boot:run；IDE 直接运行 MindBaseApplication 亦可
```

- 必须在**仓库根目录**执行（IDE 的 Working directory 指向仓库根），否则读不到 `.env`。
- 默认端口 `8081`。首次启动会自动初始化 ES 索引、Kafka topic 和数据库表结构（`ddl-auto=update`）。
- 就绪检查：`curl http://localhost:8081/actuator/health` 返回 `{"status":"UP",...}`。

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev                  # http://localhost:9527
```

开发模式下前端通过 Vite 代理访问后端 `http://localhost:8081/api/v1`（代理目标见 `frontend/.env.test`）。

## 部署

- 前端部署：`./deploy-front.sh`（读取 `.env` 中的 `DEPLOY_SERVER_HOST` / `DEPLOY_SERVER_USER` / `DEPLOY_SERVER_KEY`）
- 后端发布流程：见 `scripts/DEPLOY.md`
- Docker Compose 全套基础设施：`cd docs && docker compose up -d`（`docs/docker-compose.yaml`）
- 测试数据清理（历轮验证留下的测试账号 / 邀请码 / 组织标签）：`./scripts/cleanup-test-data.sh`（默认 dry-run，`--yes` 才执行）

## 环境变量

完整变量清单与默认值见 `.env.example`。所有敏感信息（数据库密码、API Key、JWT 密钥、支付证书）一律通过环境变量注入，不写入仓库。

## 开发约定

仓库的 agent 协作约定见 `AGENTS.md` 与 `CLAUDE.md`。

## License

Apache-2.0
