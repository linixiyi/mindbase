# PM 链 F5：P1 十项落地 - 任务拆解

## 阶段一：实现（Flash 工作流）
- [x] P1 三路并行落地（前端体验 / 治理入口 / 后端与分页）
  - id: task-p1-all
  - boundary: 按 backlog P1 节各项 boundary；文件集三路互斥（见工作流任务书）；引用路径零改动；不加依赖不改表结构
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint . && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile`
- [x] 主会话真机抽查（新入口可达 + 冷启动引导 + IME）
  - id: task-p1-browser
  - depends-on: task-p1-all
  - boundary: 只读走查 + 记录 evidence/p1-browser.md
  - verify: manual: 管理菜单新入口可达、空知识库引导卡、localStorage/控制台无异常；结果落 evidence
