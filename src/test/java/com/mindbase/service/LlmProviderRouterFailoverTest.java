package com.mindbase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.mindbase.client.resilience.ModelCallGuard;
import com.mindbase.client.resilience.ModelCallRateLimiter;
import com.mindbase.client.resilience.ModelWebClientFactory;
import com.mindbase.config.AiProperties;
import com.mindbase.config.ModelCallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用两个本地 HTTP 服务模拟主备供应商，走真实 WebClient：主供应商持续 503，重试耗尽后切到备用供应商
 */
class LlmProviderRouterFailoverTest {

    private HttpServer primary;
    private HttpServer backup;
    private final AtomicInteger primaryCalls = new AtomicInteger();
    private final AtomicReference<String> backupRequestBody = new AtomicReference<>();
    private ModelProviderConfigService providerConfig;
    private LlmProviderRouter router;

    @BeforeEach
    void setUp() throws Exception {
        primary = server(exchange -> {
            primaryCalls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        backup = server(exchange -> {
            backupRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean stream = backupRequestBody.get().contains("\"stream\":true");
            byte[] body = (stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"备用\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"备用回答\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        ModelCallProperties properties = new ModelCallProperties();
        properties.getRetry().setInitialBackoffMs(1);
        properties.getRetry().setMaxBackoffMs(2);

        providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(
                provider("deepseek", "deepseek-chat", primary),
                provider("qwen", "qwen-flash", backup)));

        router = new LlmProviderRouter(new AiProperties(), mock(RateLimitService.class), mock(UsageQuotaService.class),
                providerConfig, new ObjectMapper(), new ModelWebClientFactory(properties),
                new ModelCallGuard(properties, mock(ModelCallRateLimiter.class)), properties);
    }

    @AfterEach
    void tearDown() {
        primary.stop(0);
        backup.stop(0);
    }

    @Test
    void blockingCallFailsOverToBackupWithBackupModelName() {
        LlmProviderRouter.ReActTurn turn = router.completeReActTurn("1",
                List.of(Map.of("role", "user", "content", "你好")), List.of(), 100);

        assertEquals("备用回答", turn.content());
        // 首次调用 + 默认 2 次重试
        assertEquals(3, primaryCalls.get());
        assertTrue(backupRequestBody.get().contains("\"model\":\"qwen-flash\""));
    }

    @Test
    void streamFailsOverBeforeAnyContent() throws Exception {
        StringBuilder content = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        router.streamReActTurn("1", List.of(Map.of("role", "user", "content", "你好")), List.of(), 100,
                content::append,
                failure -> {
                    error.set(failure);
                    done.countDown();
                },
                turn -> done.countDown());

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("备用回答", content.toString());
        assertEquals(3, primaryCalls.get());
    }

    @Test
    void failoverStatusListsResolvedChainWithCircuitStateAndExcludedReasons() {
        when(providerConfig.getCurrentSettings()).thenReturn(new ModelProviderConfigService.ModelProviderSettingsView(
                new ModelProviderConfigService.ScopeSettingsView("llm", "deepseek", List.of(
                        new ModelProviderConfigService.ProviderConfigView("deepseek", "DeepSeek", "openai-compatible",
                                "http://primary.local/v1", "deepseek-chat", null, true, true, true, "sk-a****0001"),
                        new ModelProviderConfigService.ProviderConfigView("qwen", "Qwen", "openai-compatible",
                                "http://backup.local/v1", "qwen-flash", null, true, false, true, "sk-b****0002"),
                        new ModelProviderConfigService.ProviderConfigView("zhipu", "ZhipuAI", "openai-compatible",
                                "http://zhipu.local/v1", "glm-4.5-air", null, true, false, false, ""),
                        new ModelProviderConfigService.ProviderConfigView("openrouter", "OpenRouter", "openai-compatible",
                                "http://openrouter.local/v1", "or-model", null, false, false, false, ""))),
                new ModelProviderConfigService.ScopeSettingsView("embedding", "aliyun", List.of())));

        LlmProviderRouter.LlmFailoverStatusView status = router.failoverStatus();

        assertTrue(status.failoverEnabled());
        assertEquals(4, status.providers().size());

        LlmProviderRouter.LlmFailoverProviderView primaryView = status.providers().get(0);
        assertEquals("deepseek", primaryView.provider());
        assertTrue(primaryView.inChain());
        assertTrue(primaryView.primary());
        assertEquals(1, primaryView.position());
        assertEquals("CLOSED", primaryView.circuitState());
        assertNull(primaryView.excludedReason());

        LlmProviderRouter.LlmFailoverProviderView backupView = status.providers().get(1);
        assertEquals("qwen", backupView.provider());
        assertTrue(backupView.inChain());
        assertFalse(backupView.primary());
        assertEquals(2, backupView.position());

        LlmProviderRouter.LlmFailoverProviderView noKey = status.providers().get(2);
        assertEquals("zhipu", noKey.provider());
        assertFalse(noKey.inChain());
        assertEquals("未配置 API Key", noKey.excludedReason());

        LlmProviderRouter.LlmFailoverProviderView disabled = status.providers().get(3);
        assertEquals("openrouter", disabled.provider());
        assertFalse(disabled.inChain());
        assertEquals("未启用", disabled.excludedReason());
    }

    private static ModelProviderConfigService.ActiveProviderView provider(String code, String model, HttpServer server) {
        return new ModelProviderConfigService.ActiveProviderView(code, code, ModelProviderConfigService.API_STYLE_OPENAI,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", model, "test-key", null);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }
}
