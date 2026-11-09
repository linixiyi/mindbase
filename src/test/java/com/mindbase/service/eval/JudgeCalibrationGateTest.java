package com.mindbase.service.eval;

import com.mindbase.client.resilience.ModelCallPriority;
import com.mindbase.client.resilience.ModelWebClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.exception.CustomException;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import com.mindbase.service.ModelProviderConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 评委独立化与 kappa 门禁：
 * <ul>
 *   <li>eval.judge.provider 未配置 → 评委与生成共用主链路，报告标 {@code SELF_JUDGE}；</li>
 *   <li>配置后评委走独立供应商定向调用，解析失败按评委失败处理，绝不静默回退成自评；</li>
 *   <li>默认 minKappa=0.6：κ=0.4 → NEEDS_RECALIBRATION，κ=0.7 → OK；</li>
 *   <li>校准集 classpath:eval/calibration-v1.jsonl ≥30 条合法人工标注。</li>
 * </ul>
 * 全部用 mock provider，不联网。
 */
class JudgeCalibrationGateTest {

    private static final String JUDGE_REPLY_JSON =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                    + "\"{\\\"score\\\": 4, \\\"correct\\\": true, \\\"reason\\\": \\\"一致\\\"}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ModelProviderConfigService providerService = mock(ModelProviderConfigService.class);
    private final ModelWebClientFactory webClientFactory = mock(ModelWebClientFactory.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));
    /** 共享链路（生成 + SELF_JUDGE 评委）实际收到的调用 */
    private final List<List<Map<String, Object>>> sharedCalls = new ArrayList<>();
    private final List<String> routerReplies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties.setMode("rag");
        when(router.buildRagMessages(anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> {
                    sharedCalls.add(invocation.getArgument(1));
                    String reply = routerReplies.remove(0);
                    if (reply == null) {
                        throw new RuntimeException("upstream 500");
                    }
                    return new LlmProviderRouter.ReActTurn(reply, List.of(), Map.of(), "stop", 1, 1);
                });
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "org", false, "config.pdf")));
    }

    @Test
    void judgeWithoutProviderConfigIsMarkedSelfJudgeAndSharesGenerationChannel() {
        routerReplies.add("端口是 8080");
        routerReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties, objectMapper,
                Runnable::run);

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals(RagEvalCaseRunner.SELF_JUDGE, runner.judgeLlmDescription(),
                "未配置 eval.judge.provider 时报告必须标 SELF_JUDGE");
        assertEquals(5, result.judgeScore());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertEquals(2, sharedCalls.size(), "生成与评委是共享链路上的两次调用");
    }

    @Test
    void judgeWithProviderConfigRoutesToIndependentProviderWithoutTouchingSharedChannel() {
        properties.getJudge().setProvider("secondary");
        properties.getJudge().setModel("judge-x");
        stubJudgeProvider();
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        stubJudgeWebClient(bodyCaptor);
        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties, objectMapper,
                Runnable::run, null, providerService, webClientFactory);
        routerReplies.add("端口是 8080");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals("secondary/judge-x", runner.judgeLlmDescription());
        assertEquals(4, result.judgeScore(), "评委分数来自独立供应商的 mock 返回");
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertEquals(1, sharedCalls.size(), "只有生成走共享链路，评委不得复用生成供应商");
        verify(webClientFactory).clientFor(eq("llm"), argThat(view -> "secondary".equals(view.provider())));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertEquals("judge-x", body.get("model"), "请求模型用 eval.judge.model 覆盖");
        assertEquals(Boolean.FALSE, body.get("stream"));
    }

    @Test
    void judgeModelDefaultsToProviderConfiguredModelWhenOverrideBlank() {
        properties.getJudge().setProvider("secondary");
        properties.getJudge().setModel("  ");
        stubJudgeProvider();
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        stubJudgeWebClient(bodyCaptor);
        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties, objectMapper,
                Runnable::run, null, providerService, webClientFactory);
        routerReplies.add("端口是 8080");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals("secondary", runner.judgeLlmDescription(), "未覆盖模型时描述只到 provider");
        assertEquals(4, result.judgeScore());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        assertEquals("secondary-default", body.get("model"), "eval.judge.model 为空时用供应商配置的默认模型");
    }

    @Test
    void judgeProviderResolutionFailureFailsTheCaseInsteadOfSilentSelfJudge() {
        properties.getJudge().setProvider("ghost");
        when(providerService.getProvider("llm", "ghost"))
                .thenThrow(new CustomException("未找到模型供应商配置: llm/ghost", HttpStatus.BAD_REQUEST));
        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties, objectMapper,
                Runnable::run, null, providerService, webClientFactory);
        routerReplies.add("端口是 8080");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());
        assertTrue(result.needsHumanReview());
        assertNull(result.judgeScore());
        assertTrue(result.error().contains("未找到模型供应商配置"));
        assertEquals(1, sharedCalls.size(), "生成仍走共享链路");
        verifyNoInteractions(webClientFactory);
    }

    @Test
    void kappaOf04IsBelowDefaultFloorAndFlagsNeedsRecalibration() {
        assertEquals(0.6, new RagEvalProperties.HumanAgreement().getMinKappa(), 1e-9,
                "minKappa 默认 0.6");
        // 评委判对 6/10、人工判对 5/10、一致 7：po=0.7，pe=0.5，κ=0.4
        JudgeHumanAgreement agreement = agreementOf(
                new boolean[]{true, true, true, true, true, true, false, false, false, false},
                new boolean[]{true, true, true, true, false, false, false, false, false, true});

        assertEquals(0.4, agreement.cohensKappa(), 1e-9);
        assertEquals(JudgeHumanAgreement.Status.NEEDS_RECALIBRATION, agreement.status());
        assertTrue(agreement.warning().contains("重新校准"));
    }

    @Test
    void kappaOf07PassesTheGateWithDefaultFloor() {
        // n=40：17 双对、3 评委对人工错、3 评委错人工对、17 双错 → pj=ph=0.5，po=0.85，pe=0.5，κ=0.7
        List<JudgeHumanAgreement.JudgeLabel> labels = new ArrayList<>();
        Map<String, HumanVerdict> verdicts = new HashMap<>();
        int id = 0;
        for (int i = 0; i < 17; i++) {
            addCase(labels, verdicts, id++, true, true);
        }
        for (int i = 0; i < 3; i++) {
            addCase(labels, verdicts, id++, true, false);
        }
        for (int i = 0; i < 3; i++) {
            addCase(labels, verdicts, id++, false, true);
        }
        for (int i = 0; i < 17; i++) {
            addCase(labels, verdicts, id++, false, false);
        }

        JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(labels, verdicts,
                new RagEvalProperties.HumanAgreement());

        assertEquals(0.7, agreement.cohensKappa(), 1e-9);
        assertEquals(0.85, agreement.agreementRate(), 1e-9, "一致率 0.85 高于 0.8 下限，不触发一致率告警");
        assertEquals(JudgeHumanAgreement.Status.OK, agreement.status());
        assertNull(agreement.warning());
    }

    @Test
    void calibrationResourceShipsAtLeast30LabeledCasesAndFeedsTheAgreementGate() throws Exception {
        List<JudgeHumanAgreement.CalibrationCase> cases = loadCalibrationSet();

        assertTrue(cases.size() >= 30, "校准集样本数必须 ≥30，实际 " + cases.size());
        // 加载器已校验 id 唯一 / 必填字段非空 / humanScore 1-5；这里再确认标注两端齐全
        cases.forEach(c -> {
            assertNotNull(c.humanCorrect());
            assertNotNull(c.referenceAnswer());
            assertNotNull(c.candidateAnswer());
        });

        // mock provider（不联网）：假设评委与人工标注完全一致，门禁应放行
        List<JudgeHumanAgreement.JudgeLabel> labels = cases.stream()
                .map(c -> new JudgeHumanAgreement.JudgeLabel(c.id(), c.humanCorrect(), c.humanScore()))
                .toList();
        Map<String, HumanVerdict> verdicts = new HashMap<>();
        cases.forEach(c -> verdicts.put(c.id(), c.toHumanVerdict()));

        JudgeHumanAgreement agreement = JudgeHumanAgreement.compute(labels, verdicts,
                new RagEvalProperties.HumanAgreement());

        assertEquals(cases.size(), agreement.comparableCases());
        assertEquals(1.0, agreement.agreementRate(), 1e-9);
        assertEquals(JudgeHumanAgreement.Status.OK, agreement.status());
    }

    @Test
    void calibrationLoaderRejectsDuplicateIdsMalformedRowsAndOutOfRangeScores() {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> JudgeHumanAgreement.loadCalibrationCases(
                reader("{\"id\": \"x\", \"question\": \"q\", \"referenceAnswer\": \"r\", \"candidateAnswer\": \"c\", \"humanCorrect\": true}\n"
                        + "{\"id\": \"x\", \"question\": \"q\", \"referenceAnswer\": \"r\", \"candidateAnswer\": \"c\", \"humanCorrect\": true}"),
                mapper));
        assertThrows(IllegalArgumentException.class, () -> JudgeHumanAgreement.loadCalibrationCases(
                reader("not json"), mapper));
        assertThrows(IllegalArgumentException.class, () -> JudgeHumanAgreement.loadCalibrationCases(
                reader("{\"id\": \"y\", \"question\": \"q\", \"referenceAnswer\": \"r\", \"candidateAnswer\": \"c\", "
                        + "\"humanCorrect\": true, \"humanScore\": 6}"), mapper));
        assertThrows(IllegalArgumentException.class, () -> JudgeHumanAgreement.loadCalibrationCases(
                reader("{\"id\": \"z\", \"question\": \"\", \"referenceAnswer\": \"r\", \"candidateAnswer\": \"c\", "
                        + "\"humanCorrect\": true}"), mapper));
    }

    private static java.io.Reader reader(String content) {
        return new java.io.StringReader(content);
    }

    private List<JudgeHumanAgreement.CalibrationCase> loadCalibrationSet() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("eval/calibration-v1.jsonl")) {
            assertNotNull(in, "classpath:eval/calibration-v1.jsonl 必须存在");
            return JudgeHumanAgreement.loadCalibrationCases(new InputStreamReader(in, StandardCharsets.UTF_8),
                    objectMapper);
        }
    }

    private void stubJudgeProvider() {
        when(providerService.getProvider("llm", "secondary"))
                .thenReturn(new ModelProviderConfigService.ActiveProviderView("secondary", "Secondary", "openai",
                        "http://secondary.mock", "secondary-default", "sk-test", null));
    }

    /** 独立供应商的 mock WebClient：不联网，返回固定评委 JSON，同时捕获请求体供断言 */
    private void stubJudgeWebClient(ArgumentCaptor<Object> bodyCaptor) {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec post = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.ResponseSpec response = mock(WebClient.ResponseSpec.class);
        when(webClient.post()).thenReturn(post);
        doReturn(post).when(post).uri(anyString(), any(Object[].class));
        doReturn(post).when(post).contentType(any(MediaType.class));
        doReturn(post).when(post).bodyValue(bodyCaptor.capture());
        when(post.retrieve()).thenReturn(response);
        when(response.bodyToMono(String.class)).thenReturn(Mono.just(JUDGE_REPLY_JSON));
        when(webClientFactory.clientFor(anyString(), any(ModelProviderConfigService.ActiveProviderView.class)))
                .thenReturn(webClient);
    }

    private static void addCase(List<JudgeHumanAgreement.JudgeLabel> labels,
                                Map<String, HumanVerdict> verdicts, int id, boolean judgeCorrect, boolean humanCorrect) {
        String caseId = "k" + id;
        labels.add(new JudgeHumanAgreement.JudgeLabel(caseId, judgeCorrect, judgeCorrect ? 5 : 1));
        verdicts.put(caseId, new HumanVerdict(caseId, humanCorrect, humanCorrect ? 4 : 1, null, "admin", null));
    }

    private static JudgeHumanAgreement agreementOf(boolean[] judge, boolean[] human) {
        List<JudgeHumanAgreement.JudgeLabel> labels = new ArrayList<>();
        Map<String, HumanVerdict> verdicts = new HashMap<>();
        for (int i = 0; i < judge.length; i++) {
            addCase(labels, verdicts, i, judge[i], human[i]);
        }
        return JudgeHumanAgreement.compute(labels, verdicts, new RagEvalProperties.HumanAgreement());
    }

    private static EvalCase evalCase() {
        return new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080");
    }
}
