# P1 加固轮计划（2026-11-07，源自全面评估路线图）

- [x] P1a 安全面尾巴：/reference-detail 属主校验（评估 P1#2）；/api/v1/test/** 调试端点移出 permitAll 或删除（P1#9）；OCR health 收紧至 ADMIN（复审建议）；同款模式横扫（其余 controller 直通 ID 查询）
- [x] P1b CI 流水线：四 job 配置落地（复审容器级 351/351 实测；Actions 实跑绿随 P1e 验证）
- [ ] P1c 可观测性起步：actuator 健康/信息端点暴露（liveness/readiness 分离）+ 结构化日志基础（trace id 贯穿 WS→Service 链）
- [ ] P1d 演示卫生：README 快速开始按从零实测修正（评估 P1 项）；测试用户清理 SQL 脚本入库（scripts/）；介绍页/README 与现状一致性抽查
- [ ] P1e 收口：门禁全绿 + 评估报告 P0/P1 状态表更新 + push
