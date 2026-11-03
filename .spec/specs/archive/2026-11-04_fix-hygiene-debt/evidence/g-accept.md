# 硬化轮 R1 主会话验收（2026-11-04 凌晨）

- 后端已重启加载全部 R1 代码（8081 UP ~4s 增量编译）。
- **G2 断电语义 definitive 实测**：SQL 造 bcrypt 探针用户（htpasswd 生成，正确密码已知）→ 基线登录 200 → 端点禁用（「用户已禁用」）→ `redis-cli keys user:status:disabled:*` **为空**（Redis 已彻底退出禁用链）→ **正确密码登录 403「账号已被禁用，请联系管理员」** → 探针已删（users 仅剩 admin）。
- **数据清理确认**：e2e-pm-user 与邀请码 E2E-PM-2026 已由工作流清理阶段删除；g2-disable-probe 由本验收复用后删除。
- 六项门禁由工作流 world.run 实测全绿（typecheck / eslint 0+0 / mvn compile / test-compile / .bak 零 / stone- 零 / detect 无 error）；复审四 acceptance 全过、converged。
- 复审 medium（pm-022 断网 toast 与 429 倒计时画面未浏览器实测）转入 G5 体验轮顺带观察。
