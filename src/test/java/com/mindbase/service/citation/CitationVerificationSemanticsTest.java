package com.mindbase.service.citation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.AiProperties;
import com.mindbase.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引用校验整体状态语义：TRUNCATED / UNVERIFIED / NO_CITATIONS / PASSED。
 * 全部走 mock 的 LlmProviderRouter 与本地线程执行器，不依赖任何外部服务。
 */
class CitationVerificationSemanticsTest {

    private static final String ANSWER_WITH_CITATION = "分片大小是 5MB(来源#1: upload.md)。合并在内存里完成(来源#2: merge.md)。";
    private static final Map<Integer, String> REFERENCES = Map.of(
            1, "分片上传：每个分片 5MB，支持断点续传。",
            2, "合并时先在磁盘上拼成临时文件，再上传到对象存储。"
    );

    private final AiProperties aiProperties = new AiProperties();
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CitationVerificationService service;

    @BeforeEach
    void setUp() {
        aiProperties.getCitationVerification().setEnabled(true);
        service = new CitationVerificationService(aiProperties, router, objectMapper, Runnable::run);
    }

    @Test
    void thirteenClaimsWithMaxTwelveReturnsTruncatedAndKeepsVerifiedPart() throws Exception {
        StringBuilder answer = new StringBuilder();
        Map<Integer, String> references = new LinkedHashMap<>();
        for (int i = 1; i <= 13; i++) {
            answer.append("第").append(i).append("条事实陈述内容。(来源#").append(i).append(": f").append(i).append(".md)");
            references.put(i, "第" + i + "条证据片段内容");
        }
        aiProperties.getCitationVerification().setMaxClaims(12);
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt()))
                .thenReturn(turn(supportedIds(12)));

        CitationVerificationService.VerificationOutcome outcome =
                service.verifyDetailed("7", answer.toString(), references);

        assertEquals(CitationVerificationService.VerificationStatus.TRUNCATED, outcome.status(),
                "13 对超过 max-claims=12，整体状态必须是 TRUNCATED");
        assertEquals(12, outcome.verdicts().size(), "已校验部分必须保留");
        assertEquals(CitationVerdict.Status.SUPPORTED, outcome.verdicts().get(1).status());
        assertFalse(outcome.verdicts().containsKey(13), "超出上限的第 13 条不产出结论");
        assertNotNull(outcome.detail());
        assertTrue(outcome.detail().contains("13"), "detail 应说明总对数");

        // 模型调用只送入前 12 对，第 13 对不进判定请求
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass(List.class);
        verify(router).completeReActTurn(eq("7"), messages.capture(), eq(List.of()), anyInt());
        String payload = (String) messages.getValue().get(1).get("content");
        List<?> items = objectMapper.readValue(payload, List.class);
        assertEquals(12, items.size(), "只把 max-claims 以内的（句子, 片段）对送校验");
    }

    @Test
    void judgeClientFailureDegradesToUnverified() {
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("quota"));

        CitationVerificationService.VerificationOutcome outcome =
                service.verifyDetailed("7", ANSWER_WITH_CITATION, REFERENCES);

        assertEquals(CitationVerificationService.VerificationStatus.UNVERIFIED, outcome.status(),
                "校验客户端异常必须降级为 UNVERIFIED，而不是返回空 Map 的 PASSED");
        assertTrue(outcome.verdicts().isEmpty());
        assertNotNull(outcome.detail());
        // 旧接口兼容：异常路径仍返回空 Map，不影响既有调用方
        assertTrue(service.verify("7", ANSWER_WITH_CITATION, REFERENCES).isEmpty());
    }

    @Test
    void answerWithoutCitationsReturnsNoCitations() {
        CitationVerificationService.VerificationOutcome outcome =
                service.verifyDetailed("7", "这是一段没有任何引用标记的回答。", REFERENCES);

        assertEquals(CitationVerificationService.VerificationStatus.NO_CITATIONS, outcome.status());
        assertTrue(outcome.verdicts().isEmpty());
        verify(router, never()).completeReActTurn(any(), anyList(), anyList(), anyInt());
    }

    @Test
    void judgeTimeoutDegradesToUnverified() {
        // 执行器永不执行任务 → 固定触发 1 秒超时；校验失败不向调用方抛异常
        CitationVerificationService stalled = new CitationVerificationService(
                aiProperties, router, objectMapper, task -> { });
        aiProperties.getCitationVerification().setTimeoutSeconds(1);

        long start = System.nanoTime();
        CitationVerificationService.VerificationOutcome outcome =
                stalled.verifyDetailed("7", ANSWER_WITH_CITATION, REFERENCES);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(CitationVerificationService.VerificationStatus.UNVERIFIED, outcome.status(),
                "超时必须降级为 UNVERIFIED");
        assertTrue(outcome.verdicts().isEmpty());
        assertTrue(elapsedMillis < 5000, "1 秒超时配置下不应长时间挂起");
    }

    @Test
    void completeVerificationReturnsPassed() {
        when(router.completeReActTurn(any(), anyList(), anyList(), anyInt()))
                .thenReturn(turn(""" 
                        [{"id":1,"supported":true,"reason":"片段写明5MB"},
                         {"id":2,"supported":false,"reason":"片段说在磁盘上合并"}]"""));

        CitationVerificationService.VerificationOutcome outcome =
                service.verifyDetailed("7", ANSWER_WITH_CITATION, REFERENCES);

        assertEquals(CitationVerificationService.VerificationStatus.PASSED, outcome.status(),
                "全部（句子, 引用）对都得到判定即 PASSED；个别可疑看逐条 verdict");
        assertEquals(CitationVerdict.Status.SUPPORTED, outcome.verdicts().get(1).status());
        assertEquals(CitationVerdict.Status.SUSPICIOUS, outcome.verdicts().get(2).status());
        assertEquals(2, outcome.verdicts().size());
    }

    @Test
    void disabledVerificationIsUnverified() {
        aiProperties.getCitationVerification().setEnabled(false);

        CitationVerificationService.VerificationOutcome outcome =
                service.verifyDetailed("7", ANSWER_WITH_CITATION, REFERENCES);

        assertEquals(CitationVerificationService.VerificationStatus.UNVERIFIED, outcome.status());
        assertTrue(outcome.verdicts().isEmpty());
        verify(router, never()).completeReActTurn(any(), anyList(), anyList(), anyInt());
    }

    private static String supportedIds(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                json.append(',');
            }
            json.append("{\"id\":").append(i).append(",\"supported\":true,\"reason\":\"片段覆盖\"}");
        }
        return json.append(']').toString();
    }

    private static LlmProviderRouter.ReActTurn turn(String content) {
        return new LlmProviderRouter.ReActTurn(content, List.of(), Map.of(), "stop", 10, 10);
    }
}
