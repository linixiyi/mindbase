# 安全 P0 轮计划（2026-11-07，源自全面评估实测复现）

- [x] S1 会话历史读取 IDOR：GET /users/conversation?conversationId=X 补属主校验（validateConversationOwner 接入读取路径）
- [x] S2 会话归档/恢复 IDOR：archive/unarchive 端点补 validateConversationOwner（与 rename/delete 对齐）
- [x] S3 内部 OCR 端点匿名可达：回调 token 改 fail-closed（未配置即拒绝）+ 收紧 permitAll 策略
- [x] S4 回归测试与防复发（含复扫第四洞 switch 同修）：三个 IDOR 场景各一条测试 + 越权探测脚本入库（evidence），mvn test 全量绿
