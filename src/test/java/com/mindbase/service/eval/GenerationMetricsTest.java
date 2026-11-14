package com.mindbase.service.eval;

import com.mindbase.client.resilience.ModelCallPriority;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生成与引用指标门禁（task_0009）：
 * <ul>
 *   <li>无据 claim 计入 unfaithful——unsupported 让 faithfulness 下降，unverified 不计入分母；</li>
 *   <li>引用指向错误 chunk（含越界编号）时 citation precision 下降；</li>
 *   <li>应拒答题拒答则 refusal=1；不应拒答而拒答、应拒答而不拒答都判 incorrect。</li>
 * </ul>
 * claim 判定全部走确定性词面口径：测试用固定 claim 列表 / 固定证据文本注入，不经模型、不联网；
 * runner 集成部分用 Mockito 桩检索与 LLM，同样无网络、无 ES/Kafka/MinIO。
 */
class GenerationMetricsTest {

    private static final double EPS = 1e-9;

    // ---- 成功判定 1：无据 claim 计入 unfaithful ----

    @Test
    void unsupportedClaimCountsIntoUnfaithfulness() {
        // 固定 claim 列表注入（失败处理约定）：supported / unsupported / unverified 各一条
        List<EvalCaseResult.ClaimVerdict> verdicts = List.of(
                new EvalCaseResult.ClaimVerdict("MindBase 用 MySQL 存主数据", "supported", "片段 1 直接支撑"),
                new EvalCaseResult.ClaimVerdict("MindBase 的 P99 是 50ms", "unsupported", "证据中找不到"),
                new EvalCaseResult.ClaimVerdict("谢谢", "unverified", "无证据可核对"));

        assertEquals(0.5, GenerationMetrics.faithfulness(verdicts), EPS,
                "unverified 不进分母：supported/(supported+unsupported) = 1/2，无据 claim 计入 unfaithful");

        List<EvalCaseResult.ClaimVerdict> allUnsupported = List.of(
                new EvalCaseResult.ClaimVerdict("c1", "unsupported", "无据"),
                new EvalCaseResult.ClaimVerdict("c2", "unsupported", "无据"));
        assertEquals(0.0, GenerationMetrics.faithfulness(allUnsupported), EPS, "全部无据 → faithfulness=0");

        List<EvalCaseResult.ClaimVerdict> allSupported = List.of(
                new EvalCaseResult.ClaimVerdict("c1", "supported", "有据"));
        assertEquals(1.0, GenerationMetrics.faithfulness(allSupported), EPS);

        assertNull(GenerationMetrics.faithfulness(List.of(
                new EvalCaseResult.ClaimVerdict("c1", "unverified", "没有证据"))),
                "只有 unverified 时无可判 claim，返回 null 不编数字");
        assertNull(GenerationMetrics.faithfulness(List.of()));
        assertNull(GenerationMetrics.faithfulness(null));
    }

    @Test
    void judgeClaimsSplitsSentencesAndMarksFabricatedClaimUnsupported() {
        // 确定性词面判定：第一句可溯源到证据（token 覆盖率过阈），第二句是编造
        String answer = "MindBase 用 Elasticsearch 存储向量数据。MindBase 的 P99 延迟是 50 毫秒。";
        List<String> evidence = List.of("MindBase 用 Elasticsearch 保存向量数据。");

        List<EvalCaseResult.ClaimVerdict> verdicts = GenerationMetrics.judgeClaims(answer, evidence);

        assertEquals(2, verdicts.size());
        assertEquals("MindBase 用 Elasticsearch 存储向量数据", verdicts.get(0).claim(),
                "claim 为去掉句读后的原句");
        assertEquals(GenerationMetrics.SUPPORTED, verdicts.get(0).verdict(), "有据 claim 判 supported");
        assertEquals(GenerationMetrics.UNSUPPORTED, verdicts.get(1).verdict(), "编造内容判 unsupported");
        assertEquals(0.5, GenerationMetrics.faithfulness(verdicts), EPS, "无据 claim 拉低 faithfulness");
    }

    @Test
    void claimsWithoutJudgeableEvidenceAreUnverifiedAndExcludedFromDenominator() {
        List<EvalCaseResult.ClaimVerdict> verdicts =
                GenerationMetrics.judgeClaims("MindBase 的 P99 是 50 毫秒。", List.of());

        assertEquals(1, verdicts.size());
        assertEquals(GenerationMetrics.UNVERIFIED, verdicts.get(0).verdict(), "检索无结果时 claim 无从核对");
        assertNull(GenerationMetrics.faithfulness(verdicts), "unverified 不进分母，无可判 claim 时为 null");
    }

    @Test
    void splitClaimsStripsCitationMarkersAndIsDeterministic() {
        assertEquals(List.of("端口是 8080", "重启后生效"), GenerationMetrics.splitClaims("端口是 8080 [1]。重启后生效[2][3]；"));
        assertEquals(List.of("端口是 8080（见）"), GenerationMetrics.splitClaims("端口是 8080（见来源#2）。"));
        assertEquals(List.of(), GenerationMetrics.splitClaims("。！？"));
        assertEquals(List.of(), GenerationMetrics.splitClaims(null));
        assertEquals(List.of(), GenerationMetrics.splitClaims("  "));
        // 同一输入两次切分结果一致（确定性，不依赖模型）
        assertEquals(GenerationMetrics.splitClaims("A。B！C？"), GenerationMetrics.splitClaims("A。B！C？"));
    }

    // ---- 成功判定 2：引用指向错误 chunk 时 citation precision 下降 ----

    @Test
    void citationPrecisionDropsWhenCitationPointsAtWrongChunk() {
        EvalCase evalCase = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080", List.of("8080"));
        SearchResult good = new SearchResult("m1", 1, "端口配置为 8080", 0.9, "config.pdf");
        SearchResult wrong = new SearchResult("m9", 1, "与端口无关的薪酬内容", 0.8, "x.pdf");

        GenerationMetrics.CitationMetrics citeGood =
                GenerationMetrics.citations("见 [1]。", List.of(good, wrong), evalCase);
        assertEquals(1.0, citeGood.precision(), EPS, "引用指向相关分块 → precision=1");
        assertEquals(1.0, citeGood.recall(), EPS, "相关分块被引用 → recall=1");
        assertEquals(1, citeGood.cited());
        assertEquals(1, citeGood.citedRelevant());
        assertEquals(1, citeGood.relevantAvailable());

        GenerationMetrics.CitationMetrics citeWrong =
                GenerationMetrics.citations("见 [1] 和 [2]。", List.of(good, wrong), evalCase);
        assertEquals(0.5, citeWrong.precision(), EPS, "引用指向错误 chunk → precision 从 1 降到 0.5");
        assertEquals(1.0, citeWrong.recall(), EPS, "recall 不受错误引用影响（相关分块仍被 [1] 覆盖）");

        assertEquals(0.0, GenerationMetrics.citations("见 [2]。", List.of(good, wrong), evalCase).precision(), EPS,
                "只引用错误 chunk → precision=0");
        assertEquals(0.0, GenerationMetrics.citations("见 [9]。", List.of(good, wrong), evalCase).precision(), EPS,
                "越界编号（证据里没有第 9 条）按错误引用计，precision=0");

        assertNull(GenerationMetrics.citations("端口是 8080。", List.of(good, wrong), evalCase).precision(),
                "回答没有引用编号时 precision 为 null 不编数字");
        assertNull(GenerationMetrics.citations("见 [1]。", List.of(wrong), evalCase).recall(),
                "证据里没有相关分块时 recall 为 null（分母 0）");
    }

    // ---- 成功判定 3：应拒答题拒答则 refusal=1 ----

    @Test
    void refusedAnswerOnShouldRefuseCaseScoresOne() {
        EvalCase shouldRefuse = new EvalCase("c1", "P99 是多少？", List.of(), List.of(), "应拒答",
                List.of(), "dev", "neg", 2, true);

        GenerationMetrics.Evaluation refused = GenerationMetrics.evaluateCase(shouldRefuse,
                "知识库中没有相关内容，无法回答。", List.of());
        assertEquals(1, refused.metrics().refusal(), "应拒答题拒答则 refusal=1");
        assertTrue(refused.metrics().refusalCorrect(), "拒答行为符合 expectedRefusal=true");
        assertTrue(refused.metrics().refusalExpected());
        assertNull(refused.metrics().faithfulness(), "拒答回答不拆 claim，faithfulness 不参与");
        assertEquals(0, refused.metrics().totalClaims());
        assertTrue(refused.claimVerdicts().isEmpty(), "拒答不产出 claim 判定");

        GenerationMetrics.Evaluation fabricated = GenerationMetrics.evaluateCase(shouldRefuse,
                "MindBase 的 P99 延迟是 50 毫秒。", List.of());
        assertEquals(0, fabricated.metrics().refusal(), "应拒答而编造回答 → refusal=0");
        assertFalse(fabricated.metrics().refusalCorrect());
    }

    @Test
    void refusalCorrectCoversBothDirectionsAndUnlabeledCases() {
        EvalCase shouldAnswer = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080",
                List.of("8080"), "dev", "fact", 1, false);
        SearchResult good = new SearchResult("m1", 1, "端口配置为 8080", 0.9, "config.pdf");

        GenerationMetrics.Evaluation answered = GenerationMetrics.evaluateCase(shouldAnswer,
                "端口配置为 8080，见 [1]。", List.of(good));
        assertEquals(0, answered.metrics().refusal());
        assertTrue(answered.metrics().refusalCorrect(), "不应拒答而正常回答 → correct");
        assertFalse(answered.metrics().refusalExpected());
        assertEquals(1.0, answered.metrics().faithfulness(), EPS, "claim 有据 → faithfulness=1");
        assertEquals(1.0, answered.metrics().citationPrecision(), EPS);
        assertNotNull(answered.claimVerdicts());
        assertEquals(1, answered.claimVerdicts().size());

        GenerationMetrics.Evaluation overRefusal = GenerationMetrics.evaluateCase(shouldAnswer,
                "资料中没有相关信息。", List.of(good));
        assertEquals(1, overRefusal.metrics().refusal());
        assertFalse(overRefusal.metrics().refusalCorrect(), "不应拒答却拒答 → incorrect（双向口径）");

        EvalCase unlabeled = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080");
        assertNull(GenerationMetrics.evaluateCase(unlabeled, "端口配置为 8080。", List.of(good))
                .metrics().refusalCorrect(), "expectedRefusal 未标注 → 不参与拒答口径，refusalCorrect=null");
    }

    @Test
    void emptyOrFailedAnswerProducesNoGenerationMetrics() {
        EvalCase evalCase = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080");

        assertNull(GenerationMetrics.evaluateCase(evalCase, null, List.of()).metrics(),
                "回答未产出（检索/生成失败）不参与生成侧分母");
        assertNull(GenerationMetrics.evaluateCase(evalCase, "  ", List.of()).metrics());
        assertNull(GenerationMetrics.evaluateCase(evalCase, null, List.of()).claimVerdicts());
        assertFalse(GenerationMetrics.isRefusal(null), "空回答不算拒答，生成失败由 diagnosis 口径记录");
        assertFalse(GenerationMetrics.isRefusal("端口是 8080"), "正常回答不误判拒答");
    }

    @Test
    void refusalChecksOnlyFirstClaimSoPartialAnswersAreNotRefusals() {
        assertTrue(GenerationMetrics.isRefusal("结论：无法确认训练用了多少块 A100。"), "首条主张明确表示无法确认 → 拒答");
        assertTrue(GenerationMetrics.isRefusal("基于已检索的片段，没有找到相关资料。"));
        assertFalse(GenerationMetrics.isRefusal(
                        "**结论：** 最大的 ResNet 模型在 592 张 GPU 上训练了 18 天。资料中没有提到采购成本。"),
                "首条主张是事实结论，后文提到资料缺失不算整条拒答");
        assertFalse(GenerationMetrics.isRefusal("**结论：** 文本编码器无法判断类别名的词义。"),
                "“无法判断”是在描述问题，不是回答层面拒答");
    }

    @Test
    void markdownHeadingsAndMetaClaimsDoNotEnterFaithfulness() {
        List<String> claims = GenerationMetrics.splitClaims(
                "**结论：** 端口是 8080。\n- **补充论据：**\n- **配置项**：见下表。");
        assertFalse(claims.stream().anyMatch(c -> c.contains("**")), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.endsWith("：")), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.startsWith("结论") || c.startsWith("补充")), claims.toString());
        assertTrue(claims.contains("端口是 8080"), claims.toString());
    }

    @Test
    void crossLingualClaimsAreUnverifiedInsteadOfUnsupported() {
        List<EvalCaseResult.ClaimVerdict> verdicts = GenerationMetrics.judgeClaims(
                "MindBase 的主存储是 MySQL。", List.of("MindBase uses MySQL as its primary storage."));
        assertEquals(1, verdicts.size());
        assertEquals(GenerationMetrics.UNVERIFIED, verdicts.get(0).verdict(),
                "中文 claim 对英文证据做词面覆盖率只会得到假 unsupported");
        assertNull(GenerationMetrics.faithfulness(verdicts), "跨语言 claim 不进 faithfulness 分母");
    }

    // ---- 汇总口径：summary 里的拒答正确率 / faithfulness / citation ----

    @Test
    void summaryAggregatesGenerationMetricsMacroPerCase() {
        SearchResult good = new SearchResult("m1", 1, "端口配置为 8080", 0.9, "config.pdf");
        SearchResult wrong = new SearchResult("m9", 1, "无关内容", 0.8, "x.pdf");
        EvalCase labeled = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080",
                List.of("8080"), "dev", "fact", 1, false);
        EvalCase shouldRefuse = new EvalCase("c2", "P99？", List.of(), List.of(), "拒答",
                List.of(), "dev", "neg", 2, true);

        GenerationMetrics.Evaluation first = GenerationMetrics.evaluateCase(labeled,
                "端口配置为 8080，见 [1] 和 [2]。", List.of(good, wrong));
        // first：faithfulness=1（有据）、precision=0.5（[2] 指向错误 chunk）、recall=1
        GenerationMetrics.Evaluation refused = GenerationMetrics.evaluateCase(shouldRefuse,
                "知识库中没有相关内容。", List.of());
        // refused：refusal=1、faithfulness=null（不进 faithfulness 平均）

        EvalCaseResult firstResult = resultWithGeneration("c1", labeled, first);
        EvalCaseResult refusedResult = resultWithGeneration("c2", shouldRefuse, refused);
        EvalCaseResult notAnswered = new EvalCaseResult("c3", "q", "ref", List.of(), List.of(), List.of(),
                "NONE", List.of(), null, null, null, null, null, null, null, null, null, null,
                EvalDiagnosis.GENERATION_FAILED, false, null, "生成失败", 1L);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(firstResult, refusedResult, notAnswered));

        // task-0004/首份真机报告复审：refusalAccuracy 只按 neg（expectedRefusal=true）的 judgeCorrect 计算；
        // 非 neg 的“正常回答”不再计入拒答分母（旧口径会把 34/109 条正文含“资料中没有”的回答误判为拒答）。
        assertEquals(1, summary.refusalLabeledCases(), "只有 c2 是 neg，计入拒答口径");
        assertEquals(1, summary.refusalCorrectCases(), "c2 按标注拒答且评委判对");
        assertEquals(1.0, summary.refusalAccuracy(), EPS);
        assertEquals(1, summary.refusalExpectedCases());
        assertEquals(1, summary.refusedAsExpected());
        assertEquals(1.0, summary.faithfulness(), EPS, "只有 c1 有可判 claim，宏平均取其 faithfulness");
        assertEquals(0.5, summary.citationPrecision(), EPS, "错误引用拉低数据集级 citation precision");
        assertEquals(1.0, summary.citationRecall(), EPS);
        assertEquals(1, summary.failedCases(), "回答未产出的用例不进生成侧分母，只计入 failedCases");
    }

    @Test
    void summaryGenerationMetricsNullWhenNoAnswerProduced() {
        EvalCaseResult legacy = new EvalCaseResult("c1", "q", "ref", List.of(), List.of(), List.of(),
                "HYBRID", List.of(), true, true, 1, 1.0, 1.0, "answer", 5, true, "ok", null,
                EvalDiagnosis.OK, false, null, null, 1L);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(legacy));

        assertEquals(0, summary.refusalLabeledCases());
        assertNull(summary.refusalAccuracy(), "没有生成指标时拒答正确率为 null 不编数字");
        assertNull(summary.faithfulness());
        assertNull(summary.citationPrecision());
        assertNull(summary.citationRecall());
    }

    // ---- runner 集成（rag 模式，Mockito 桩，无网络无 ES） ----

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));
    private final List<String> llmReplies = new java.util.ArrayList<>();

    @BeforeEach
    void setUpRunner() {
        properties.setMode("rag");
        when(router.buildRagMessages(anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", "q")));
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> new LlmProviderRouter.ReActTurn(llmReplies.remove(0), List.of(),
                        Map.of(), "stop", 1, 1));
    }

    @Test
    void runnerFillsClaimVerdictsAndCitationMetricsInRagMode() {
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                new SearchResult("m9", 1, "无关的薪酬内容", 0.8, "7", "dept", false, "x.pdf", null, null, "HYBRID", null),
                new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "dept", false, "config.pdf", null, null, "HYBRID", null)));
        llmReplies.add("端口配置为 8080，见 [1] 和 [2]。");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties,
                objectMapper, Runnable::run);
        EvalCase evalCase = new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080", List.of("8080"));
        EvalCaseResult result = runner.run(evalCase, "7", 3, noSampling);

        assertNotNull(result.generation(), "rag 模式回答产出后必须填充生成与引用指标");
        assertEquals(2, result.generation().citedCount(), "回答引用了 [1] 和 [2]");
        assertEquals(1, result.generation().citedRelevantCount(), "[1] 指向 config.pdf（相关），[2] 指向 x.pdf（错误）");
        assertEquals(0.5, result.generation().citationPrecision(), EPS, "引用指向错误 chunk → precision 下降");
        assertEquals(1.0, result.generation().citationRecall(), EPS);
        assertNull(result.generation().refusalCorrect(), "未标注 expectedRefusal 的用例不参与拒答口径");
        assertFalse(result.claimVerdicts().isEmpty(), "claim 级判定写入用例明细");
        assertNotNull(result.generation().faithfulness());
    }

    @Test
    void runnerScoresRefusalOneOnNegativePermissionCase() {
        // 负向权限用例：检索无结果（权限过滤生效），模型如实拒答
        when(searchService.searchWithPermission("薪酬？", "7", 3)).thenReturn(List.of());
        llmReplies.add("暂时无法检索您权限范围内的知识库内容，未检索到相关资料。");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"如实拒答\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties,
                objectMapper, Runnable::run);
        EvalCase evalCase = new EvalCase("negperm-01", "薪酬？", List.of(), List.of(), "应拒答",
                List.of(), "dev", "negperm", 2, true);
        EvalCaseResult result = runner.run(evalCase, "7", 3, noSampling);

        assertEquals(1, result.generation().refusal(), "应拒答题拒答则 refusal=1");
        assertTrue(result.generation().refusalCorrect());
        assertTrue(result.claimVerdicts().isEmpty(), "拒答回答不拆 claim");
        assertNull(result.generation().faithfulness());
        assertNull(result.generation().citationPrecision(), "拒答回答没有引用编号");
    }

    @Test
    void runnerLeavesGenerationNullWhenGenerationFails() {
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "dept", false, "config.pdf",
                        null, null, "HYBRID", null)));
        llmReplies.add(null);

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, properties,
                objectMapper, Runnable::run);
        EvalCaseResult result = runner.run(
                new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080"), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.GENERATION_FAILED, result.diagnosis());
        assertNull(result.generation(), "回答未产出时 generation 为 null");
        assertNull(result.claimVerdicts());
    }

    // ---- fixtures ----

    private static EvalCaseResult resultWithGeneration(String caseId, EvalCase evalCase,
                                                       GenerationMetrics.Evaluation evaluation) {
        return new EvalCaseResult(caseId, evalCase.question(), evalCase.referenceAnswer(),
                evalCase.expectedFileMd5s(), evalCase.expectedFileNames(), evalCase.expectedEvidence(),
                "HYBRID", List.of(), true, true, 1, 1.0, 1.0,
                "answer", 5, true, "ok", null, EvalDiagnosis.OK, false, null, null, 1L,
                null, evaluation.claimVerdicts(), null, null, evaluation.metrics());
    }
}
