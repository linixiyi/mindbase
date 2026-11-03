# 硬化轮 R1 - 任务拆解

- [x] G1–G4 四路并行实现 + eslint 清零 + 门禁六项 + 独立复审 + 测试数据清理（工作流）
  - id: task-g-all
  - boundary: 按 spec §5 允许清单；引用路径零改动；不加依赖；改表仅 users.enabled
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q test-compile && ! git ls-files | grep -q "\.bak$" && ! grep -rq "stone-" frontend/src/components/custom/pdf-document-viewer.vue && ! /Users/linyixi/.agents/skills/impeccable/scripts/impeccable detect --json frontend/src | grep -q '"error"'`
- [x] 主会话验收：G2 断电语义（清 Redis 键后禁用仍生效）+ .bak/ESLint/detect 终检
  - id: task-g-accept
  - depends-on: task-g-all
  - boundary: 只读验证 + SQL 探针，记录 evidence/g-accept.md
  - verify: manual: 断电语义实测（mysql 直读 enabled + 清 Redis 键 + curl 登录 403）；结果落 evidence

- [x] G5 产品自体验（主会话无头 Playwright 全旅程）
  - id: task-g5-experience
  - boundary: 只读走查 + 截图
  - verify: manual: evidence/g5-experience.md（含 pm-022 闭环、pm-014 实锤、新 bug 发现）
