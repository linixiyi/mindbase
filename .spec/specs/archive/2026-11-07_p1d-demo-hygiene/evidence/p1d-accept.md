# P1d 主会话抽验（2026-11-07）

- 采信复审 live 库只读实测：dry-run 99 行逐表一致、FK 4 条全指 users、跨用户 md5=0、23 冲突全为孤儿且 0 条涉 admin、8 邀请码全命中过滤——--yes 执行不会 FK 失败且按设计不可能触及 admin 演示数据（实测 admin 1 用户/2 会话/36 记录/1 文件/2 向量完好）。
- 快速开始 5 项抽查全中 + 额外实测 /actuator/health 与 README 逐字一致、端口表 lsof 核对。
- 复审 low 记录：compose MySQL 变体的 SPRING_DATASOURCE_PASSWORD 提示、test_entity 残留表未纳入清理范围（无 FK 不阻塞）、脚本注释编号不连续（纯外观）。
