package com.mindbase.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;

/**
 * Kafka 探活（P1c 可观测性）：Spring Boot 内置健康指标不含 Kafka，
 * 用 AdminClient describeCluster 在超时窗口内如实上报 broker 连通性。
 * 详情仅在 show-details 授权时展示，匿名请求只见 UP/DOWN，不暴露 broker 地址以外的信息。
 */
@Component
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String bootstrapServers;

    public KafkaHealthIndicator(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        try (AdminClient adminClient = AdminClient.create(config)) {
            DescribeClusterResult cluster = adminClient.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
            builder.up()
                    .withDetail("bootstrapServers", bootstrapServers)
                    .withDetail("nodeCount", cluster.nodes().get().size());
        }
    }
}
