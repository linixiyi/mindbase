package com.mindbase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.service.ModelProviderConfigService;
import com.mindbase.utils.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankClientTest {

    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final RerankClient client = new RerankClient(properties, providerConfig, new ObjectMapper(), new SecretCryptoService());

    @Test
    void parsesDashScopeResponseSortedByScoreAndDropsInvalidIndexes() {
        String response = """
                {"output":{"results":[
                  {"index":1,"relevance_score":0.31},
                  {"index":0,"relevance_score":0.92},
                  {"index":7,"relevance_score":0.99},
                  {"index":2}
                ]},"usage":{"total_tokens":120},"request_id":"r1"}
                """;

        List<RerankClient.RerankHit> hits = client.parseResponse(response, 3);

        assertEquals(List.of(new RerankClient.RerankHit(0, 0.92), new RerankClient.RerankHit(1, 0.31)), hits);
    }

    @Test
    void failsOnUnexpectedResponse() {
        assertThrows(IllegalStateException.class, () -> client.parseResponse("{\"code\":\"InvalidApiKey\"}", 2));
        assertThrows(IllegalStateException.class, () -> client.parseResponse("not json", 2));
    }

    @Test
    void requiresApiKeyWhenEmbeddingProviderIsNotDashScope() {
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView("zhipu", "智谱", "openai-compatible", "https://x", "embedding-3", "k", 2048));

        assertThrows(IllegalStateException.class, () -> client.rerank("q", List.of("doc")));
    }

    @Test
    void emptyCandidatesSkipTheCall() {
        assertEquals(List.of(), client.rerank("q", List.of()));
    }

    @Test
    void statusPrefersDedicatedKeyAndMasksIt() {
        properties.getRerank().setApiKey("sk-test-key-12345678");

        ModelProviderConfigService.RerankStatusView status = client.status();

        assertEquals("dedicated", status.keySource());
        assertTrue(status.hasApiKey());
        assertEquals("sk-t****5678", status.maskedApiKey());
        assertTrue(status.enabled());
        assertEquals("gte-rerank-v2", status.model());
    }

    @Test
    void statusFallsBackToAliyunEmbeddingKeyAndReportsNoneWhenAbsent() {
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView("aliyun", "阿里云", "openai-compatible", "https://x", "text-embedding-v4", "sk-aliyun-key-1234", 2048));
        assertEquals("embedding-aliyun", client.status().keySource());

        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView("zhipu", "智谱", "openai-compatible", "https://x", "embedding-3", "k", 2048));
        ModelProviderConfigService.RerankStatusView status = client.status();
        assertEquals("none", status.keySource());
        assertFalse(status.hasApiKey());
        assertEquals("", status.maskedApiKey());
    }

    @Test
    void testConnectionFailsHonestlyWithoutKey() {
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView("zhipu", "智谱", "openai-compatible", "https://x", "embedding-3", "k", 2048));

        ModelProviderConfigService.ConnectivityTestView result = client.testConnection();

        assertFalse(result.success());
        assertTrue(result.message().contains("未配置 rerank API Key"));
    }

    @Test
    void testConnectionReportsSuccessThenAuthFailureAgainstLocalServer() throws Exception {
        HttpServer ok = server(200, "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}");
        properties.getRerank().setApiKey("sk-local-test-key");
        properties.getRerank().setUrl("http://127.0.0.1:" + ok.getAddress().getPort() + "/rerank");

        ModelProviderConfigService.ConnectivityTestView success = client.testConnection();
        ok.stop(0);

        assertTrue(success.success(), success.message());
        assertEquals("连接成功", success.message());
        assertTrue(success.latencyMs() >= 0);

        HttpServer unauthorized = server(401, "{\"code\":\"InvalidApiKey\"}");
        properties.getRerank().setUrl("http://127.0.0.1:" + unauthorized.getAddress().getPort() + "/rerank");

        ModelProviderConfigService.ConnectivityTestView failed = client.testConnection();
        unauthorized.stop(0);

        assertFalse(failed.success());
        assertTrue(failed.message().contains("API Key 无效"), failed.message());
    }

    private static HttpServer server(int statusCode, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server;
    }
}
