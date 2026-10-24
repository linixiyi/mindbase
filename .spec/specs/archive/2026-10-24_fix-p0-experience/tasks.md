# PM 链 F4：P0 三项修复 - 任务拆解

## 阶段一：实现（Flash 工作流 dwfrun 见 chain）
- [x] pm-001 聊天失败恢复：后端错误码分层 + 前端错误卡片重试 + 失败回合持久化
  - id: task-pm-001
  - boundary: ChatHandler 错误响应（新增类别码字段，不破坏现有协议）、ConversationService 失败回合落库（不改表结构）、前端 chat-message/input-box/chat store（错误渲染+重试+快照）；引用路径零改动
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint src/views/chat src/store/modules/chat && cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && grep -q "重试" frontend/src/views/chat/modules/chat-message.vue`
- [x] pm-002 记住我安全：只存 userName，读时清理存量明文密码
  - id: task-pm-002
  - boundary: 只改 frontend/src/views/_builtin/login/modules/pwd-login.vue（及必要的 storage 工具调用点）
  - verify: `cd /Users/linyixi/PaiSmart/frontend && pnpm typecheck && pnpm exec eslint src/views/_builtin/login && ! grep -A3 "localStg.set('rememberedLogin'" src/views/_builtin/login/modules/pwd-login.vue | grep -i password`
- [x] pm-003 删除隔离：scoped 删除 + 最后持有者全局清理
  - id: task-pm-003
  - boundary: 只改 src/main/java/com/mindbase/service/DocumentService.java 删除链路（复用既有 deleteByFileMd5AndUserId 仓储方法）
  - verify: `cd /Users/linyixi/PaiSmart && "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" -q -DskipTests compile && grep -q "deleteByFileMd5AndUserId" src/main/java/com/mindbase/service/DocumentService.java`
- [x] 门禁与独立复审（工作流内置）+ 主会话真机 E2E 回归
  - id: task-e2e-regression
  - depends-on: task-pm-001
  - boundary: 只读真机验证
  - verify: manual: 正常问答仍全链路可用（流式+引用）；DevTools 断言 localStorage.rememberedLogin 无 password 字段；结果记入本包 evidence/f4-e2e.md
