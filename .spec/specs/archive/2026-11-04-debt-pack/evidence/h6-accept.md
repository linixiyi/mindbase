# H6 主会话抽验（2026-11-06）

- **哈希裁剪亲手复核**：curl 创建邀请码 → createdBy 仅 {id, username}，响应零 password/零 bcrypt 字面量 ✅
- **DLQ 终态亲手复核**：死信表仅剩既有 id=1/2（注入 id=3 已被重放处置离场）；file_processing_outbox#51 status=SENT sent_at=02:42:38.161（与复审申报分秒不差）✅
- 门禁五项工作流 world.run 实测全绿（runs.jsonl）；mvn test 339/339 含 ChatHandler 专项测试
- 复审 low：落库 ALERT 日志行因重启截断不可复核（四路旁证成立）；重放语义偏差为既有语义非本次引入（已记录）
