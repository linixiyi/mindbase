package com.mindbase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.service.ModelProviderConfigService;
import com.mindbase.utils.RequestDeadline;
import com.mindbase.utils.SecretCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 百炼 / DashScope 文本排序（rerank）客户端。
 * 把问题和每个候选片段拼在一起计算相关性，比向量检索准但更慢，只用于融合后的几十条候选。
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);
    private static final String DASHSCOPE_PROVIDER = "aliyun";

    private final HybridSearchProperties properties;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;
    private final SecretCryptoService secretCryptoService;

    public RerankClient(HybridSearchProperties properties,
                        ModelProviderConfigService modelProviderConfigService,
                        ObjectMapper objectMapper,
                        SecretCryptoService secretCryptoService) {
        this.properties = properties;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = objectMapper;
        this.secretCryptoService = secretCryptoService;
    }

    /**
     * 管理端只读状态：当前生效的 rerank 配置与 API Key 来源（不回传明文密钥）
     */
    public ModelProviderConfigService.RerankStatusView status() {
        HybridSearchProperties.Rerank cfg = properties.getRerank();
        String apiKey = resolveApiKey();
        String keySource;
        if (hasValue(cfg.getApiKey())) {
            keySource = "dedicated";
        } else if (hasValue(apiKey)) {
            keySource = "embedding-aliyun";
        } else {
            keySource = "none";
        }
        return new ModelProviderConfigService.RerankStatusView(
                cfg.isEnabled(),
                cfg.getUrl(),
                cfg.getModel(),
                hasValue(apiKey),
                secretCryptoService.mask(apiKey),
                keySource,
                cfg.getTimeoutSeconds(),
                cfg.getScoreThreshold(),
                cfg.getMaxCandidates());
    }

    /**
     * 管理端连通性测试：一条候选的最小 rerank 调用，不走检索链，也不影响问答请求
     */
    public ModelProviderConfigService.ConnectivityTestView testConnection() {
        HybridSearchProperties.Rerank cfg = properties.getRerank();
        long startAt = System.currentTimeMillis();
        try {
            String apiKey = resolveApiKey();
            if (!hasValue(apiKey)) {
                return new ModelProviderConfigService.ConnectivityTestView(false,
                        "未配置 rerank API Key（SEARCH_RERANK_API_KEY），且激活的 embedding provider 不是阿里云",
                        System.currentTimeMillis() - startAt);
            }
            Map<String, Object> body = Map.of(
                    "model", cfg.getModel(),
                    "input", Map.of("query", "ping", "documents", List.of("ping")),
                    "parameters", Map.of("return_documents", false, "top_n", 1));
            String response = WebClient.builder()
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .build()
                    .post()
                    .uri(cfg.getUrl())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RequestDeadline.cap(Duration.ofSeconds(Math.min(Math.max(cfg.getTimeoutSeconds(), 1), 8))));
            parseResponse(response, 1);
            return new ModelProviderConfigService.ConnectivityTestView(true, "连接成功", System.currentTimeMillis() - startAt);
        } catch (Exception exception) {
            return new ModelProviderConfigService.ConnectivityTestView(
                    false, formatTestFailure(exception), System.currentTimeMillis() - startAt);
        }
    }

    private String formatTestFailure(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                int statusCode = responseException.getStatusCode().value();
                if (statusCode == 401 || statusCode == 403) {
                    return "Rerank API Key 无效或未填写，请检查 SEARCH_RERANK_API_KEY 或阿里云 Embedding 供应商密钥";
                }
                if (statusCode == 404) {
                    return "Rerank API 地址不可用，请检查 SEARCH_RERANK_URL";
                }
                return "Rerank 服务返回 HTTP " + statusCode + "，请检查模型名与接口地址";
            }
            current = current.getCause();
        }
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * @return 按相关性降序排列的结果，index 对应 documents 的下标
     */
    public List<RerankHit> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        HybridSearchProperties.Rerank cfg = properties.getRerank();
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 rerank API Key（search.hybrid.rerank.api-key），且激活的 embedding provider 不是阿里云");
        }

        Map<String, Object> body = Map.of(
                "model", cfg.getModel(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", documents.size())
        );

        long start = System.currentTimeMillis();
        String response = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build()
                .post()
                .uri(cfg.getUrl())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(RequestDeadline.cap(Duration.ofSeconds(cfg.getTimeoutSeconds())));

        List<RerankHit> hits = parseResponse(response, documents.size());
        logger.debug("rerank 完成: model={}, candidates={}, costMs={}", cfg.getModel(), documents.size(),
                System.currentTimeMillis() - start);
        return hits;
    }

    List<RerankHit> parseResponse(String response, int documentCount) {
        try {
            JsonNode results = objectMapper.readTree(response).path("output").path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("rerank 响应缺少 output.results");
            }
            List<RerankHit> hits = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= documentCount || !item.has("relevance_score")) {
                    continue;
                }
                hits.add(new RerankHit(index, item.path("relevance_score").asDouble()));
            }
            hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
            return List.copyOf(hits);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 rerank 响应失败: " + e.getMessage(), e);
        }
    }

    private String resolveApiKey() {
        String configured = properties.getRerank().getApiKey();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        ModelProviderConfigService.ActiveProviderView embedding =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        return DASHSCOPE_PROVIDER.equals(embedding.provider()) ? embedding.apiKey() : null;
    }

    public record RerankHit(int index, double score) {
    }
}
