package com.mindbase.service.eval;

import com.mindbase.client.resilience.ModelCallPriority;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评委双采样与一致性判定（C11 / task-0011）：
 * eval.judge.repeat=true 时同一 prompt、同一模型连调两次，按合并规则落盘——
 * 两次 correct 一致 → 分数取均值；结论相反或单侧解析失败 → judgeScore/judgeCorrect 置空并强制人工复核；
 * 两次都解析失败 → 按既有 JUDGE_FAILED 通道处理（绝不当作"一致"）。
 * 汇总侧：一致率分母 = 两次都成功解析的用例数；repeat 关闭时不产生一致性数字。
 * <p>
 * repeat 的 Java 字段默认 false、生产默认 true 由 application.yml 承担（裁决 D6）；均值 1 位小数后
 * half-up 落 Integer judgeScore，分数不同的一致样本在 judgeReason 补写均值与落盘值（裁决 D7）。
 * 全部依赖 mock，不需要任何外部服务。
 */
class JudgeRepeatConsistencyTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));
    /** 与 completeReActTurn 调用一一对应的请求消息（校验双采样同 prompt）与按序弹出回复 */
    private final List<List<Map<String, Object>>> llmCalls = new ArrayList<>();
    private final List<String> llmReplies = new ArrayList<>();
    private RagEvalCaseRunner runner;

    @BeforeEach
    void setUp() {
        properties.setMode("rag");
        // repeat 的 Java 字段默认 false（裁决 D6：单测 bare 构造走单采样）；双采样用例显式开启
        runner = new RagEvalCaseRunner(searchService, router, null, properties,
                new com.fasterxml.jackson.databind.ObjectMapper(), Runnable::run);
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> {
                    llmCalls.add(new ArrayList<>(invocation.getArgument(1)));
                    String reply = llmReplies.remove(0);
                    if (reply == null) {
                        throw new RuntimeException("upstream 500");
                    }
                    return new LlmProviderRouter.ReActTurn(reply, List.of(), Map.of(), "stop", 1, 1);
                });
    }

    @Test
    void repeatAgreedVerdictsAverageScoreAndKeepConclusion() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        // 同 prompt 连调两次：生成 1 次 + 评委 2 次，两次评委消息完全一致
        assertEquals(3, llmCalls.size());
        assertEquals(llmCalls.get(1), llmCalls.get(2), "双采样两次评委调用必须是同一 prompt");
        assertEquals(RagEvalCaseRunner.JUDGE_SYSTEM_PROMPT, llmCalls.get(1).get(0).get("content"));
        assertEquals(5, result.judgeScore(), "两次一致取均值");
        assertEquals(Boolean.TRUE, result.judgeCorrect());
        assertTrue(result.judgeReason().contains("一致"), "保留评委理由");
        assertTrue(result.judgeReason().endsWith("A=5/true; B=5/true"), "judgeReason 带 A/B 双采样轨迹");
        assertFalse(result.needsHumanReview());
        assertNull(result.reviewReason());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertTrue(summary.judgeRepeatEnabled());
        assertEquals(1, summary.judgeAgreementCases());
        assertEquals(0, summary.judgeDisagreementCases());
        assertEquals(1.0, summary.judgeAgreementRate(), 1e-9);
    }

    @Test
    void repeatDisagreeingVerdictsAreBlankedAndForcedToReview() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 4, \"correct\": false, \"reason\": \"缺要点\"}");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"覆盖要点\"}");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals(3, llmCalls.size());
        assertNull(result.judgeScore(), "结论相反必须置空");
        assertNull(result.judgeCorrect());
        assertEquals("A=4/false; B=5/true", result.judgeReason(), "judgeReason 写 A/B 对照");
        assertTrue(result.needsHumanReview());
        assertEquals(RagEvalCaseRunner.REASON_JUDGE_DISAGREE, result.reviewReason());
        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertTrue(summary.judgeRepeatEnabled());
        assertEquals(0, summary.judgeAgreementCases());
        assertEquals(1, summary.judgeDisagreementCases());
        assertEquals(0.0, summary.judgeAgreementRate(), 1e-9);
    }

    @Test
    void repeatSingleParseFailureIsMarkedUnstableAndExcludedFromDenominator() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        llmReplies.add("我给 5 分");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals(3, llmCalls.size());
        assertNull(result.judgeScore());
        assertNull(result.judgeCorrect());
        assertTrue(result.needsHumanReview());
        assertEquals(RagEvalCaseRunner.REASON_JUDGE_UNSTABLE, result.reviewReason());
        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertTrue(summary.judgeRepeatEnabled(), "单侧失败也是双采样发生过的证据");
        assertEquals(0, summary.judgeAgreementCases());
        assertEquals(0, summary.judgeDisagreementCases());
        assertNull(summary.judgeAgreementRate(), "分母为 0 时一致率为 null，不用空一致性冒充稳定");
    }

    @Test
    void repeatBothParseFailuresGoThroughJudgeFailedAndAreNotCountedAsAgreement() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("乱码输出甲");
        llmReplies.add("乱码输出乙");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertNull(result.judgeScore());
        assertNull(result.judgeCorrect());
        assertTrue(result.needsHumanReview());
        assertEquals(HumanReviewSampler.REASON_JUDGE_FAILED, result.reviewReason(), "两次都失败走既有 JUDGE_FAILED 通道");
        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertFalse(summary.judgeRepeatEnabled(), "没有可解析样本就没有一致性信号");
        assertNull(summary.judgeAgreementCases());
        assertNull(summary.judgeDisagreementCases());
        assertNull(summary.judgeAgreementRate());
    }

    @Test
    void bareConstructionDefaultsToSingleJudgeAndNoConsistencyNumbers() {
        // 不触碰 repeat：钉住 Java 字段默认 false（裁决 D6），生产默认 true 由 application.yml 承担
        assertFalse(properties.getJudge().isRepeat(), "RagEvalProperties.Judge.repeat 的 Java 默认必须是 false");
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        assertEquals(2, llmCalls.size(), "repeat 默认关闭时评委只调一次");
        assertEquals(5, result.judgeScore());
        assertEquals(Boolean.TRUE, result.judgeCorrect());
        assertEquals("一致", result.judgeReason(), "单次调用 judgeReason 不追加 A/B 轨迹");
        assertFalse(result.needsHumanReview());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertFalse(summary.judgeRepeatEnabled(), "repeat=false 时报告标 judgeRepeatEnabled=false");
        assertNull(summary.judgeAgreementCases(), "不产生一致性数字");
        assertNull(summary.judgeDisagreementCases());
        assertNull(summary.judgeAgreementRate());
    }

    @Test
    void repeatAgreedWithDifferentScoresKeepsMeanTrailAndRoundsHalfUp() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 4, \"correct\": true, \"reason\": \"基本一致\"}");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"覆盖要点\"}");

        EvalCaseResult result = runner.run(evalCase(), "7", 3, noSampling);

        // 均值 4.5 无法入 Integer judgeScore：1 位小数后 half-up 落盘为 5（裁决 D7），
        // judgeReason 保留 A/B 原始分、1 位小数均值与最终落盘值三要素，A/B 轨迹仍在末尾
        assertEquals(5, result.judgeScore());
        assertEquals(Boolean.TRUE, result.judgeCorrect());
        assertTrue(result.judgeReason().contains("A=4/true"), "A 原始分保留: " + result.judgeReason());
        assertTrue(result.judgeReason().contains("B=5/true"), "B 原始分保留: " + result.judgeReason());
        assertTrue(result.judgeReason().contains("mean=4.5"), "1 位小数均值保留: " + result.judgeReason());
        assertTrue(result.judgeReason().contains("judgeScore=5"), "最终落盘值保留: " + result.judgeReason());
        assertTrue(result.judgeReason().endsWith("A=4/true; B=5/true"), "A/B 轨迹仍在末尾供聚合识别");
        assertFalse(result.needsHumanReview());

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(result));
        assertTrue(summary.judgeRepeatEnabled());
        assertEquals(1, summary.judgeAgreementCases(), "分数不同但结论一致仍计入一致");
        assertEquals(0, summary.judgeDisagreementCases());
        assertEquals(1.0, summary.judgeAgreementRate(), 1e-9);
    }

    @Test
    void summaryCountsUseBothParsedCasesAsDenominator() {
        properties.getJudge().setRepeat(true);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt()))
                .thenReturn(List.of(result()));

        // 一致（5/true + 5/true）
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        EvalCaseResult agreed = runner.run(evalCase(), "7", 3, noSampling);

        // 结论相反（4/false + 5/true）
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 4, \"correct\": false, \"reason\": \"缺要点\"}");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"覆盖要点\"}");
        EvalCaseResult disagreed = runner.run(evalCase(), "7", 3, noSampling);

        // 单侧解析失败（5/true + 无法解析）
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");
        llmReplies.add("我给 5 分");
        EvalCaseResult unstable = runner.run(evalCase(), "7", 3, noSampling);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(agreed, disagreed, unstable));
        assertTrue(summary.judgeRepeatEnabled());
        assertEquals(1, summary.judgeAgreementCases());
        assertEquals(1, summary.judgeDisagreementCases());
        // 分母 = 两次都成功解析的例数（一致 + 相反 = 2），单侧解析失败的不计入
        assertEquals(0.5, summary.judgeAgreementRate(), 1e-9);
        assertEquals(2, summary.needsHumanReviewCount(), "相反与单侧失败强制复核，一致不复核");
    }

    private static EvalCase evalCase() {
        return new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080");
    }

    private static SearchResult result() {
        return new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "dept", false, "config.pdf", null, null,
                "HYBRID", null);
    }
}
