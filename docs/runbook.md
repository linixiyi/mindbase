# MindBase 本地启动 Runbook

> 敏感值（API Key、密码）一律放 git-ignored 的仓库根 `.env`，本文件只写变量名。
> `.env` 由 `DotenvEnvironmentPostProcessor` 在后端启动时自动加载（仅本机源码启动；docker 跑后端时不用它）。

## 架构与端口

| 组件 | 端口 | 运行方式 | 说明 |
| --- | --- | --- | --- |
| 前端 Vite | 9527 | `cd frontend && pnpm dev` | 浏览器入口 http://localhost:9527，代理后端 8081 |
| 后端 Spring Boot | 8081 | IDE 热部署 / `mvn spring-boot:run` | dev profile |
| MySQL | 3306 | 本机原生（brew `mysql@8.0`） | 库 `PaiSmart`，`ddl-auto=update` 自动建表 |
| Redis | 6379 | docker compose | 密码见 compose（`MindBase2025`） |
| Kafka | 9092 | docker compose | topic 由后端 NewTopic Bean 自动创建 |
| Elasticsearch | 9200 | docker compose（http + IK 插件） | elastic / 见 compose |
| MinIO | 19000（API）/ 19001（控制台） | docker compose | 见下文「MinIO 镜像说明」 |
| LLM 网关 | 20128 | 本机独立网关（OpenAI 兼容） | 需先自行启动网关本体 |

## 一次性准备

1. 复制 `.env.example` 为 `.env`，填入模型 API 与基础设施配置（本文件不出现任何真实密钥）。
2. 在本机 MySQL 里建库：`CREATE DATABASE PaiSmart CHARACTER SET utf8mb4;`（表结构由 `ddl-auto=update` 自动生成；存量迁移脚本在 `docs/databases/migrations/`）。
3. `.env` 关键项核对：
   - `SPRING_PROFILES_ACTIVE=dev`（缺失时 ES 会按基础配置走 https，本地容器是 http，启动即失败）
   - `SPRING_DATA_REDIS_PASSWORD` / `ELASTICSEARCH_PASSWORD` / `MINIO_*` 与 compose 一致
   - `ADMIN_BOOTSTRAP_PASSWORD`（校验要求密码 ≥ 12 位）

## 日常启动顺序

```bash
# 1. 基础设施（MySQL 用本机原生；如需全 docker 见下节）
cd docs && docker compose up -d redis kafka es minio

# 2. 后端（任选其一）
#    IDE：直接跑 MindBaseApplication
mvn spring-boot:run -DskipTests          # 命令行；mvn 不在 PATH 时用 IntelliJ 内置：
"/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" spring-boot:run -DskipTests

# 3. 前端
cd frontend && pnpm dev
```

健康检查：`docker compose ps`（四容器 healthy）、`curl localhost:8081/api/v1/users/login`（405/400 即通）、浏览器开 9527。

## 首次初始化管理员

`.env` 中 `ADMIN_BOOTSTRAP_ENABLED=true` → 启动一次后端（自动建 admin）→ **改回 false** → 用 `.env` 里的 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD` 登录。

登录接口：`POST /api/v1/users/login`（注意不是 /auth/login，后者只有 refreshToken）。

## MinIO 镜像说明

MinIO 已撤出 Docker Hub（历史 tag 全部 404，quay.io 亦不可用）。`docs/docker-compose.yaml` 改用
`bitnamilegacy/minio:latest`：数据目录必须是 `/bitnami/minio/data`（非 root 用户，entrypoint 自动修属主），
不能用上游镜像的 `/data`。bucket `uploads` 不由后端自动创建，首次需手动：

```bash
docker run --rm --network host --entrypoint /bin/sh -e MC_CONFIG_DIR=/tmp/.mc bitnamilegacy/minio:latest -c \
  "/opt/bitnami/minio-client/bin/mc alias set local http://localhost:19000 <MINIO_ACCESS_KEY> <MINIO_SECRET_KEY> && \
   /opt/bitnami/minio-client/bin/mc mb local/uploads"
```

（`MINIO_ACCESS_KEY/SECRET_KEY` 取 `.env` 中的值；服务器部署路径的 `/data/docker/*` bind mount 已在本地注释。）

## 全 Docker 变体（可选）

`docker compose up -d` 全量五件套（含 mysql）。注意三点：本机原生 MySQL 会占用 3306（先 `brew services stop mysql@8.0`）；
容器 MySQL 首次启动后要手动 `CREATE DATABASE PaiSmart`；后端仍建议本机源码跑（本仓库没有后端镜像）。

## 已知坑（踩过的）

- **Embedding 维度参数**：OpenAI 兼容参数名是复数 `dimensions`；单数 `dimension` 会被 SiliconFlow 静默忽略，
  返回模型原生维度（Qwen3-Embedding-8B = 4096），与 `EMBEDDING_DIMENSION=2048` 的索引不匹配。后端已修复。
- **ES scheme**：base 配置默认 https，本地容器是 http——`.env` 必须有 `SPRING_PROFILES_ACTIVE=dev`。
- **admin 密码校验**：`AdminUserInitializer` 要求 ≥ 12 位，历史默认值不够长会启动失败。
- **pnpm 12**：包管理器配置（`shamefullyHoist` 等）要写在 `frontend/pnpm-workspace.yaml`（不读 `.npmrc`）；
  `allowBuilds` 必须是真实布尔值，占位符文本会让 `pnpm install --frozen-lockfile` 直接报错。
- **frontend pre-commit**：`pnpm typecheck && pnpm lint`（全仓）+ `git diff --exit-code`，提交前先本地跑一遍 lint。
