package com.mindbase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.service.ModelProviderConfigService;
import com.mindbase.utils.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * C15：RerankClient 复用同一 WebClient，连续调用不重建。
 */
class RerankClientReuseTest {

    private final ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();

    private RerankClient newClient() {
        return new RerankClient(properties, providerConfig, new ObjectMapper(), new SecretCryptoService());
    }

    @Test
    void webClientIsLazilyCreatedOnceAndReusedAcrossCalls() {
        RerankClient client = newClient();

        WebClient first = client.webClient();
        WebClient second = client.webClient();

        assertNotNull(first);
        assertNotNull(second);
        assertSame(client.webClient(), client.webClient());
        assertSame(first, second);
    }
}
