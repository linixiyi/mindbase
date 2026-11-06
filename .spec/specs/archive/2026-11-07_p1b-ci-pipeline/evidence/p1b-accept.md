# P1b 主会话抽验（2026-11-07）

- 采信复审实测（超出常规复审强度）：复审员本机起 mysql:8/redis:7 容器按 CI 同款 env + 死端口隔离 ES/Kafka，./mvnw -B test 351/351 全绿；前端 /tmp 干净副本（无 .env）四命令全过 + 负向对照证明 VITE_ 注入必要；repo-gates/secret-scan 逐字重跑全零。
- **medium 当轮修复**：npm_config_registry env 对 pnpm 12 无效（复审死端口实验证伪）——改 `pnpm install --frozen-lockfile --registry=...` flag，注释同步修正；YAML 复验 OK。
- 测试规模注释 68→66 类由复审更正（@Test 351 精确）。
- 待 push 后 GitHub Actions 实跑绿（P1e 收口时验证并记录 run URL）。
