# P1 加固轮计划（2026-11-07，源自全面评估路线图）

- [x] P1a 安全面尾巴：/reference-detail 属主校验（评估 P1#2）；/api/v1/test/** 调试端点移出 permitAll 或删除（P1#9）；OCR health 收紧至 ADMIN（复审建议）；同款模式横扫（其余 controller 直通 ID 查询）
- [x] P1b CI 流水线：四 job 配置落地（复审容器级 351/351 实测；Actions 实跑绿随 P1e 验证）
- [x] P1c 可观测性起步：actuator 健康分层 + MDC traceId 贯穿 + CWE-117 修复
- [x] P1d 演示卫生：快速开始修正 + 清理脚本入库（live 实测）+ 一致性抽查
- [x] P1e 收口：push 完成（Actions 账号级故障待用户排查，本地等价验证全绿存档）
