# H5 主会话抽验（2026-11-06）

- 采信复审 18/18 独立无头实测：注册→登录免重输（与记住我共存）、忘记密码诚实引导（弹层 0 input）、占位文案清零、端点驱动 OPEN/CLOSED/失败三分支、INVITE_ONLY 端到端 5 次注册成功。
- 门禁五项由工作流 world.run 实测全绿（runs.jsonl）。
- 新发现（复审）：邀请码创建响应 data[].createdBy 内嵌完整 admin User 含 bcrypt 哈希（AdminController.java:469-477）——低危但应裁剪，收编 H6。
- 测试留痕：h5rvw_user1..5 + h5debug/pm032 系列（无删除端点）；清理 SQL：DELETE FROM users WHERE username LIKE 'h5%' OR username LIKE 'rv_reg%';
