# 后端启动记录 — 2026-10-24

环境：`/Users/linyixi/PaiSmart`（Spring Boot 3.4.2，仓库根 `.env` 由 `DotenvEnvironmentPostProcessor` 自动加载）。
本次实测：四个中间件 + 后端 8081 全部就绪；前端 9527 未动（已在运行，未起第二实例）。

## ① 逐组件表

| 服务 | 端口 | 启动方式（本次实际执行） | 日志位置 / 查看方式 | 端口验证命令 |
|---|---|---|---|---|
| MySQL | 3306 | brew 常驻服务（本次之前已在跑，未重启）：`brew services start mysql@8.0` | `brew services list`；数据目录见 `~/Library/LaunchAgents/homebrew.mxcl.mysql@8.0.plist` | `nc -z localhost 3306` → OPEN；后端 Hibernate 查询正常出结果 |
| Redis | 6379 | Docker 容器 `redis`（`restart: always`，Docker Desktop 启动时自动拉起，无需手动 start） | `docker logs redis --tail 50`；`docker inspect redis --format '{{.State.Health.Status}}'` → healthy | `nc -z localhost 6379` → OPEN |
| Elasticsearch | 9200 | Docker 容器 `es`（同上自动拉起；首次冷启动需 30-60s 过 healthcheck） | `docker logs es --tail 50`；`docker inspect es --format '{{.State.Health.Status}}'` → healthy | `curl -s -o /dev/null -w '%{http_code}' http://localhost:9200/` → 401（开着安全认证，401 即活着） |
| Kafka | 9092 | Docker 容器 `kafka`（同上自动拉起） | `docker logs kafka --tail 50`；容器 healthcheck healthy | `docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list`（本次列出 `file-processing`、`vectorization` 等 6 个 topic） |
| MinIO | **19000**（控制台 19001） | Docker 容器 `minio`（同上自动拉起） | `docker logs minio --tail 50` | `curl -s -o /dev/null -w '%{http_code}' http://localhost:19000/minio/health/live` → 200 |
| 后端 (MindBaseApplication) | 8081 | 见下方 backendHow | `/tmp/mindbase-backend.log`（`tail -f /tmp/mindbase-backend.log`；应用自身也写 `./logs/` 下 logback 滚动文件） | `nc -z localhost 8081` → OPEN；真实 API：`POST /api/v1/users/login` → 401 业务 JSON |

定义来源：四个中间件的容器与端口映射定义在 `docs/docker-compose.yaml`（compose 项目名 `mindbase`）；后端连接参数在仓库根 `.env` + `src/main/resources/application.yml` / `application-dev.yml`。

## ② 下次如何一键再启（按本次实际路径，可整段复制）

```bash
# 1) Docker Desktop（启动 daemon 后 restart:always 容器会自动拉起 redis/minio/es/kafka）
open -a Docker
# 等 daemon 就绪、四容器 healthy：
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
# （首次冷启动 ES 可能要 30-60s 才 healthy）

# 2) MySQL（brew 常驻；已在跑则跳过）
brew services list | grep mysql
# 若未启动： brew services start mysql@8.0

# 3) 后端（必须从仓库根启动：DotenvEnvironmentPostProcessor 从 user.dir 读 .env；
#    profile 由 .env 的 SPRING_PROFILES_ACTIVE 自动注入，无需显式传）
cd /Users/linyixi/PaiSmart
nohup "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" spring-boot:run \
  > /tmp/mindbase-backend.log 2>&1 &
disown

# 4) 等 8081 就绪（本次实测 ~15s）
for i in $(seq 1 60); do nc -z localhost 8081 >/dev/null 2>&1 && { echo OPEN; break; }; sleep 5; done
tail -5 /tmp/mindbase-backend.log   # 应看到 "Started MindBaseApplication"

# 5) 真实 API 冒烟（401 + JSON body = 全链路活着）
curl -s -X POST http://localhost:8081/api/v1/users/login -H 'Content-Type: application/json' \
  -d '{"username":"smoke","password":"smoke"}' --max-time 5
```

## ③ 坑与注意

1. **凭证卫生**：本文件不写任何真实密码/密钥。MySQL root、Redis requirepass、ES、MinIO 共用一套开发口令，原文在 `docs/docker-compose.yaml` 的 environment 段；后端侧覆盖值（Redis/ES/MinIO/JWT/AI key 等）在仓库根 `.env`（git-ignored）。下文一律以 `<见 .env>` / `<compose 内统一开发口令>` 占位。
2. **不要 `docker start mysql`**：compose 里有个 `mysql` 容器但处于 `Created`（从未启动）状态；brew 的 mysql@8.0 已占 3306，启动它会端口冲突。3306 一律走 brew。
3. **MinIO 是 19000 不是 9000**：`application-dev.yml:66` 的默认值是 9000，但 `.env` 的 `MINIO_ENDPOINT` 覆盖为 19000，与 `docs/docker-compose.yaml` 的映射一致。照抄 yml 默认值会连不上。
4. **Maven 不在 PATH**：本机没有独立安装 mvn，用 IntelliJ 捆绑的 Maven 3.9.11：`/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`（路径含空格，命令里必须带引号）。仓库无 mvnw wrapper。
5. **Java 版本**：pom 要求 17（`pom.xml:30`），本机只有 Homebrew OpenJDK 21，用 21 编译运行正常（release 17 向下兼容），本次启动实测无编译/运行错误。
6. **后端必须在仓库根启动**：`DotenvEnvironmentPostProcessor.java:34` 用 `System.getProperty("user.dir")/.env` 定位配置文件；换目录启动会丢掉全部 `.env` 连接参数（Redis/ES 密码、MinIO 端点等），启动即连不上。
7. **登录端点是 `/api/v1/users/login`**：`SecurityConfig.java:51` 白名单放行的是 users/login；POST `/api/v1/auth/login` 会 403（被 Http403ForbiddenEntryPoint 拦截），别把 403 误判成故障。
8. **良性日志噪音**：启动日志有一条 netty `MacOSDnsServerAddressStreamProvider` UnsatisfiedLinkError ERROR——已知回退告警，不影响功能，可忽略。
9. **不要动 `paismart-docker-*` 容器组**：那是 4 周前退出的旧版全容器部署栈（backend/frontend/rustfs 等），与本地开发栈（`mindbase` compose 项目）无关。
10. **进程存活性**：后端以 `nohup + disown` 启动，maven 父进程 PPID=1，脱离终端会话存活；停止方式：`pkill -f spring-boot:run`（会连带 fork 出的应用 JVM）。
