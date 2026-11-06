# P1c 主会话抽验（2026-11-07）

- 采信复审实测：actuator 暴露面最小（匿名 11 路径全 403、health 仅 UP/DOWN、admin 详情 9 组件全 UP 含新 KafkaHealthIndicator）；MDC 生命周期静态审计（5 写入点全 finally clear、跨线程同值、A/B 轮零串扰）；logback 5 pattern 全带 traceId 且滚动形态不变。
- **CWE-117 当轮修复（主会话）**：客户端 traceId 白名单 ^[0-9a-fA-F-]{8,64}$，不合规值替换为安全 UUID（TraceContext.put）。运行时实证：恶意值 "x\nFAKE-LOG-LINE role=ADMIN" 经探针注入后全部日志 FAKE-LOG-LINE 零命中，日志 traceId 全为合法 UUID。mvn test 351/351。
- 复审 low 记录：KafkaHealthIndicator 连接 churn（现规模无碍）；旁路线程池 MDC 快照（后续扩展注意）；Kafka header 端到端同值未直接观测（测试套件真实路径旁证）。
