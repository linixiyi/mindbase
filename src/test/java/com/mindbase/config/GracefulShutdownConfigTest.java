package com.mindbase.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0020 收口测试：application.yml 配置优雅停机（C20）
 * 1) server.shutdown=graceful；
 * 2) spring.lifecycle.timeout-per-shutdown-phase=30s；
 * 3) 两处都在既有段落内合并：server 段仍保留 port，spring 既有子段不被 lifecycle 顶掉，
 *    spring.lifecycle 只含本次新增键，不覆盖其他键，也不新建重复键。
 *
 * 加载方式：用 SnakeYAML 读 classpath 上的 application.yml。测试 classpath 上存在
 * src/test/resources/application.yml（H2 fixture）会按类路径顺序遮蔽主配置，因此从
 * getResources 的候选中显式选择 target/classes 下的生产配置（与 KafkaTrustAndFetchGuardTest
 * 注释的遮蔽问题是同一件事）。
 */
class GracefulShutdownConfigTest {

    /** 既有 spring 子段：新增 lifecycle 后一个都不能少 */
    private static final Set<String> PRE_EXISTING_SPRING_SECTIONS = Set.of(
            "jackson", "datasource", "jpa", "data", "servlet", "kafka", "task", "webflux", "codec");

    @Test
    @SuppressWarnings("unchecked")
    void gracefulShutdownAndLifecycleTimeoutAreConfigured() throws Exception {
        Map<String, Object> root = loadProductionApplicationYml();

        Map<String, Object> server = (Map<String, Object>) root.get("server");
        assertEquals("graceful", server.get("shutdown"), "server.shutdown 必须为 graceful");
        // 在既有 server 段内合并：SnakeYAML 对重复键按"后者覆盖前者"合并（不抛异常），
        // 若新建了不带 port 的重复 server 键，port 会在这里丢失
        assertNotNull(server.get("port"), "server 段必须与既有 port 合并，禁止新建重复键");

        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> lifecycle = (Map<String, Object>) spring.get("lifecycle");
        assertNotNull(lifecycle, "spring.lifecycle 必须存在");
        assertEquals("30s", lifecycle.get("timeout-per-shutdown-phase"),
                "spring.lifecycle.timeout-per-shutdown-phase 必须为 30s");
        // spring.lifecycle 未覆盖其他键：只允许出现本次新增的这一个键
        assertEquals(Set.of("timeout-per-shutdown-phase"), lifecycle.keySet(),
                "spring.lifecycle 不允许出现 timeout-per-shutdown-phase 之外的键: " + lifecycle.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void addingLifecycleDoesNotShadowOtherSpringSections() throws Exception {
        Map<String, Object> root = loadProductionApplicationYml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");

        for (String section : PRE_EXISTING_SPRING_SECTIONS) {
            assertTrue(spring.containsKey(section), "spring." + section + " 必须仍然存在，禁止被 lifecycle 顶掉");
        }
        assertTrue(spring.containsKey("lifecycle"), "spring.lifecycle 必须存在");
    }

    @Test
    void topLevelKeysAreNotDuplicated() throws Exception {
        // SnakeYAML 2.3 对重复键不抛异常而是后者覆盖前者，按原文行数兜底断言没有重复键
        //（与 KafkaTrustAndFetchGuardTest 的原文断言同一模式）
        List<String> lines = readProductionYmlLines();
        assertEquals(1, lines.stream().filter(line -> line.strip().equals("server:")).count(),
                "顶层 server: 只允许出现一次");
        assertEquals(1, lines.stream().filter(line -> line.strip().equals("spring:")).count(),
                "顶层 spring: 只允许出现一次");
        assertEquals(1, lines.stream().filter(line -> line.strip().equals("lifecycle:")).count(),
                "lifecycle: 只允许出现一次");
    }

    /** 生产配置在 classpath 上的位置：target/classes/application.yml（排除 test-classes 下的 fixture） */
    private URL productionClasspathYml() throws Exception {
        List<URL> candidates = Collections.list(getClass().getClassLoader().getResources("application.yml"));
        return candidates.stream()
                .filter(url -> url.getPath().endsWith("/target/classes/application.yml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "classpath 上必须存在 target/classes/application.yml（先执行 process-resources）；实际候选: " + candidates));
    }

    private Map<String, Object> loadProductionApplicationYml() throws Exception {
        try (InputStream in = productionClasspathYml().openStream()) {
            return new Yaml().load(in);
        }
    }

    private List<String> readProductionYmlLines() throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(productionClasspathYml().openStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
