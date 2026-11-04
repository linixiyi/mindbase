# MindBase

MindBase 是一个企业级的 AI 知识库管理系统，围绕检索增强生成（RAG）工作流构建：上传文档后自动解析、分块、向量化并写入 Elasticsearch，用户用自然语言提问，系统基于知识库片段生成带引用的中文回答。

项目介绍页（GitHub Pages）：https://linixiyi.github.io/mindbase/

核心技术栈：Spring Boot 3.4 / Vue 3 / MySQL / Redis / Elasticsearch / Kafka / MinIO（或 RustFS）/ WebSocket。

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
- 搜索：Elasticsearch 8.10.0
- 消息队列：Apache Kafka
- 文件存储：MinIO / RustFS
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

### 1. 前置环境

- JDK 17、Maven
- Node.js >= 18.20、pnpm >= 8.7
- MySQL 8、Redis、Kafka、Elasticsearch 8.10、MinIO（本地可用 `infra.sh` 或 `docs/docker-compose.yaml` 启动）

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填写 MySQL / Redis / ES / MinIO / JWT / 模型 API Key 等配置
```

应用启动时会由 `DotenvEnvironmentPostProcessor` 自动加载根目录 `.env`，适用于 IDE 直接跑主类和 `mvn spring-boot:run`。

### 3. 启动本地基础服务

```bash
./infra.sh start          # 启动全部（minio / kafka / elasticsearch）
./infra.sh status         # 查看状态
./infra.sh urls           # 查看访问地址
```

服务安装目录通过 `MINIO_DIR` / `KAFKA_DIR` / `ELASTICSEARCH_DIR` 环境变量指定，默认在 `~/Downloads` 下。

### 4. 启动后端

```bash
mvn spring-boot:run
```

或从 IDE 直接运行 `MindBaseApplication`，默认端口 `8081`。首次启动会自动初始化 ES 索引、Kafka topic 和数据库结构。

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev                  # http://localhost:9527
```

开发模式下前端通过 Vite 代理访问后端 `http://localhost:8081/api/v1`。

## 部署

- 前端部署：`./deploy-front.sh`（读取 `.env` 中的 `DEPLOY_SERVER_HOST` / `DEPLOY_SERVER_USER` / `DEPLOY_SERVER_KEY`）
- 后端发布流程：见 `scripts/DEPLOY.md`
- Docker Compose 全套基础设施：`docs/docker-compose.yaml`

## 环境变量

完整变量清单与默认值见 `.env.example`。所有敏感信息（数据库密码、API Key、JWT 密钥、支付证书）一律通过环境变量注入，不写入仓库。

## 开发约定

仓库的 agent 协作约定见 `AGENTS.md` 与 `CLAUDE.md`。

## License

Apache-2.0
