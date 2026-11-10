package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.client.resilience.ModelCallPriority;
import com.mindbase.config.ContextualRetrievalProperties;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.config.ParentContextProperties;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.service.AgentToolRegistry;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测成本与 token 记账（评测缺口修复）：
 * <ul>
 *   <li>报告 cost.amount = promptTokens/1000*inputPer1k + completionTokens/1000*outputPer1k，
 *       四舍五入到 6 位；未配价时 amount 保持 null 且 note 写 unpriced: set eval.pricing.*（不做字符数估算）；</li>
 *   <li>{@link RagEvalCaseRunner} 把评委 usage 与生成 usage 合并写入用例 tokenUsage；
 *       评委 usage 缺失只计生成侧并在 note 标 partial（禁止字符数估算 token）；</li>
 *   <li>报告 config 记录单价与来源（pricingSource=eval.pricing.*），cost.amount 可由 config 复算。</li>
 * </ul>
 * 全部内存构造 / Mockito mock，不依赖任何外部服务。
 */
class EvalCostAccountingTest {

    /* ---------- Cost 折算（纯函数） ---------- */

    @Test
    void pricedCostIsPromptPer1kTimesInputPlusCompletionPer1kTimesOutput() {
        EvalReport.Cost cost = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(1000, 500, 1500), 1.0, 2.0, "CNY");

        assertEquals(2.0, cost.amount(), 1e-9, "1000/1000*1 + 500/1000*2 = 2.0，与手算一致");
        assertEquals("CNY", cost.currency(), "配价时币种随单价落报告");
        assertTrue(cost.note().contains("priced") && cost.note().contains("eval.pricing.*"),
                "note 说明取值来源与配置位置: " + cost.note());
    }

    @Test
    void unpricedCostKeepsAmountNullAndExplainsHowToConfigure() {
        EvalReport.Cost cost = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(1000, 500, 1500), null, null, null);

        assertNull(cost.amount(), "未配价不折算金额，不做字符数估算");
        assertNull(cost.currency());
        assertEquals(EvalReport.Cost.UNPRICED_NOTE, cost.note());
        assertEquals("unpriced: set eval.pricing.*", cost.note(), "note 写明在哪里配置单价");
    }

    @Test
    void partiallyConfiguredPricingStillCountsAsUnpriced() {
        // 只配一半单价会静默低估费用：宁可整体 unpriced 也不算半价
        EvalReport.Cost inputOnly = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(1000, 500, 1500), 1.0, null, "CNY");
        assertNull(inputOnly.amount(), "outputPer1k 缺失 → 未配价");
        assertEquals(EvalReport.Cost.UNPRICED_NOTE, inputOnly.note());

        EvalReport.Cost outputOnly = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(1000, 500, 1500), null, 2.0, "CNY");
        assertNull(outputOnly.amount(), "inputPer1k 缺失 → 未配价");
    }

    @Test
    void costRoundsToSixDecimalsWithHalfUp() {
        EvalReport.Cost cost = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(1000, 0, 1000), 0.012345649, 2.0, null);

        assertEquals(0.012346, cost.amount(), 1e-9, "0.012345649 四舍五入到 6 位 → 0.012346");
        assertNull(cost.currency(), "币种未配置时报告不写币种");
    }

    @Test
    void pricedConfigWithoutRecordedUsageKeepsAmountNull() {
        EvalReport.Cost cost = EvalReport.Cost.of(null, 1.0, 2.0, "CNY");

        assertNull(cost.amount(), "配置了单价但没有任何 token 用量记录 → 不编造金额");
        assertTrue(cost.note().contains("no") || cost.note().contains("没有"),
                "note 说明无用量可折算: " + cost.note());
        assertTrue(cost.note().contains("eval.pricing.*"), "note 仍指向单价来源: " + cost.note());
    }

    @Test
    void costComputesFromRecordedDimensionOnly() {
        // 只有 completion 维度有值：按有值侧折算，不补算缺失侧
        EvalReport.Cost cost = EvalReport.Cost.of(
                new EvalCaseResult.TokenUsage(null, 500, 500), 1.0, 2.0, null);

        assertEquals(1.0, cost.amount(), 1e-9, "500/1000*2 = 1.0，只计记录了的维度");
    }

    /* ---------- TokenUsage 合并（纯函数） ---------- */

    @Test
    void mergedUsageAddsJudgeTokensToGenerationTokens() {
        EvalCaseResult.TokenUsage merged = EvalCaseResult.TokenUsage.merged(
                new EvalCaseResult.TokenUsage(30, 15, 45), new EvalCaseResult.TokenUsage(3, 3, 6));

        assertEquals(33, merged.promptTokens(), "30 生成 + 3 评委");
        assertEquals(18, merged.completionTokens(), "15 生成 + 3 评委");
        assertEquals(51, merged.totalTokens());
        assertNull(merged.note(), "两侧用量齐全时不标 partial");
    }

    @Test
    void missingJudgeUsageKeepsGenerationOnlyAndMarksPartial() {
        EvalCaseResult.TokenUsage merged = EvalCaseResult.TokenUsage.merged(
                new EvalCaseResult.TokenUsage(30, 15, 45), null);

        assertEquals(30, merged.promptTokens(), "评委 usage 缺失 → 只计生成侧");
        assertEquals(15, merged.completionTokens());
        assertEquals(45, merged.totalTokens());
        assertNotNull(merged.note(), "缺失侧必须显式标注");
        assertTrue(merged.note().contains("partial"), "note 标 partial: " + merged.note());
        assertTrue(merged.note().contains("generation"), "说明只计了生成侧: " + merged.note());
    }

    @Test
    void missingGenerationUsageKeepsJudgeOnlyAndMarksPartial() {
        EvalCaseResult.TokenUsage merged = EvalCaseResult.TokenUsage.merged(
                null, new EvalCaseResult.TokenUsage(3, 3, 6));

        assertEquals(3, merged.promptTokens());
        assertEquals(3, merged.completionTokens());
        assertEquals(6, merged.totalTokens());
        assertTrue(merged.note().contains("partial") && merged.note().contains("judge"),
                "对称场景：只计评委侧并标 partial: " + merged.note());
    }

    @Test
    void mergedReturnsNullWhenNeitherSideRecorded() {
        assertNull(EvalCaseResult.TokenUsage.merged(null, null), "两侧都没有记录时不编造用量");
    }

    /* ---------- runner：评委 usage 与生成 usage 合并（react 模式，mock，无外部服务） ---------- */

    @TempDir
    Path tempDir;

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));

    private final List<LlmProviderRouter.ReActTurn> streamTurns = new ArrayList<>();
    private final List<String> completeReplies = new ArrayList<>();

    private void stubReactGenerationAndJudge() {
        when(router.buildReActMessages(anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content",
                        invocation.getArgument(0))));
        // 流式回合在调用线程同步完成（与线上流式路径一致），按序弹出预置回合
        when(router.streamReActTurn(anyString(), anyList(), anyList(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    Consumer<LlmProviderRouter.ReActTurn> onComplete = invocation.getArgument(6);
                    LlmProviderRouter.ReActTurn turn = streamTurns.remove(0);
                    if (turn.content() != null && !turn.content().isEmpty()) {
                        onChunk.accept(turn.content());
                    }
                    onComplete.accept(turn);
                    return newStreamHandle();
                });
        // react 模式下 completeReActTurn 只承载评委调用（生成走流式回合）
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(),
                eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> new LlmProviderRouter.ReActTurn(completeReplies.remove(0), List.of(),
                        Map.of(), "stop", 3, 3));
        when(tools.getTools()).thenReturn(List.of(new AgentToolRegistry.AgentTool(
                "search_knowledge", "检索知识库", Map.of("type", "object"))));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(hybridResult("m1", "config.pdf", "端口配置为 8080"))));
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                hybridResult("m1", "config.pdf", "端口配置为 8080")));
    }

    private RagEvalCaseRunner runner() {
        return new RagEvalCaseRunner(searchService, router, null, properties, objectMapper, Runnable::run, tools);
    }

    private EvalCaseResult runCase() {
        return runner().run(new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080"), "7", 3, noSampling);
    }

    @Test
    void runnerMergesJudgeUsageIntoCaseTokenUsage() {
        stubReactGenerationAndJudge();
        // 生成侧：工具轮 10/5 + 回答轮 20/10；评委轮：mock 返回 3/3
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，见 [1]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 20, 10));
        completeReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runCase();

        assertEquals(5, result.judgeScore(), "评委成功打分");
        EvalCaseResult.TokenUsage usage = result.tokenUsage();
        assertNotNull(usage, "react 模式必须记录用例 tokenUsage");
        assertEquals(33, usage.promptTokens(), "生成 30 + 评委 3");
        assertEquals(18, usage.completionTokens(), "生成 15 + 评委 3");
        assertEquals(51, usage.totalTokens());
        assertNull(usage.note(), "生成与评委用量都拿到 → 不标 partial");
    }

    @Test
    void runnerMarksPartialWhenJudgeUsageMissing() {
        stubReactGenerationAndJudge();
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，见 [1]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 20, 10));
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(),
                eq(ModelCallPriority.BACKGROUND)))
                .thenThrow(new IllegalStateException("judge down"));

        EvalCaseResult result = runCase();

        assertNull(result.judgeScore(), "评委调用失败 → 无分数");
        assertTrue(result.needsHumanReview(), "评委失败的用例进人工复核");
        EvalCaseResult.TokenUsage usage = result.tokenUsage();
        assertNotNull(usage, "评委 usage 缺失时仍要记录生成侧用量");
        assertEquals(30, usage.promptTokens(), "只计生成侧 prompt");
        assertEquals(15, usage.completionTokens(), "只计生成侧 completion");
        assertEquals(45, usage.totalTokens(), "只计生成侧 total，评委 token 不做字符数估算");
        assertNotNull(usage.note());
        assertTrue(usage.note().contains("partial"), "note 标 partial: " + usage.note());
        assertTrue(usage.note().contains("judge"), "说明缺的是评委侧: " + usage.note());
    }

    @Test
    void runnerTreatsAllZeroJudgeUsageAsUnavailable() {
        stubReactGenerationAndJudge();
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，见 [1]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 20, 10));
        completeReplies.add("{\"score\": 4, \"correct\": true, \"reason\": \"基本一致\"}");
        // 独立供应商未回填 usage 的占位形态：0/0 视为不可得，不把 0 当真实用量记进账
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(),
                eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> new LlmProviderRouter.ReActTurn(completeReplies.remove(0), List.of(),
                        Map.of(), "stop", 0, 0));

        EvalCaseResult result = runCase();

        assertEquals(4, result.judgeScore(), "评委打分成功（只影响用量记录）");
        EvalCaseResult.TokenUsage usage = result.tokenUsage();
        assertEquals(30, usage.promptTokens(), "0/0 视为 usage 不可得 → 只计生成侧");
        assertEquals(15, usage.completionTokens());
        assertEquals(45, usage.totalTokens());
        assertTrue(usage.note() != null && usage.note().contains("partial"),
                "标 partial 而不是静默当作完整用量: " + usage.note());
    }

    @Test
    void runnerKeepsJudgeParseFailureTokensInAccounting() {
        stubReactGenerationAndJudge();
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，见 [1]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 20, 10));
        // 评委输出了但不是合法 JSON：调用真实发生，token 照记（费用照算），只影响 score/correct
        completeReplies.add("抱歉，我无法按格式回答");

        EvalCaseResult result = runCase();

        assertTrue(result.needsHumanReview(), "解析失败进人工复核");
        EvalCaseResult.TokenUsage usage = result.tokenUsage();
        assertEquals(33, usage.promptTokens(), "解析失败的评委调用 token 也要记账");
        assertEquals(18, usage.completionTokens());
        assertEquals(51, usage.totalTokens());
        assertNull(usage.note(), "评委调用真实发生且拿到 usage → 不标 partial");
    }

    /* ---------- 报告层：cost 落盘与 config 记录单价来源 ---------- */

    @Test
    void reportCostUsesConfiguredPricingAndConfigRecordsPriceAndSource() throws Exception {
        RagEvalProperties properties = new RagEvalProperties();
        properties.setOutputDir(tempDir.resolve("reports").toString());
        properties.getPricing().setInputPer1k(1.0);
        properties.getPricing().setOutputPer1k(2.0);
        properties.getPricing().setCurrency("CNY");
        RagEvalService service = newService(properties, resultWithUsage());

        JsonNode report = readLatestReport(service);

        JsonNode cost = report.get("cost");
        assertNotNull(cost, "cost 字段非空");
        assertEquals(2.0, cost.get("amount").asDouble(), 1e-9, "1000/1000*1 + 500/1000*2 = 2.0");
        assertEquals("CNY", cost.get("currency").asText());
        assertTrue(cost.get("note").asText().contains("priced: eval.pricing.*"),
                "note 记录来源: " + cost.get("note").asText());
        assertEquals(1500, report.get("tokenUsage").get("totalTokens").asInt(), "报告级用量含生成+评委");

        JsonNode config = report.get("config");
        assertEquals(1.0, config.get("pricingInputPer1k").asDouble(), 1e-9, "config 记录单价，金额可复算");
        assertEquals(2.0, config.get("pricingOutputPer1k").asDouble(), 1e-9);
        assertEquals("CNY", config.get("pricingCurrency").asText());
        assertEquals("eval.pricing.*", config.get("pricingSource").asText(), "config 记录单价来源");
    }

    @Test
    void reportCostStaysUnpricedWithoutPricingConfig() throws Exception {
        RagEvalProperties properties = new RagEvalProperties();
        properties.setOutputDir(tempDir.resolve("reports-unpriced").toString());
        RagEvalService service = newService(properties, resultWithUsage());

        JsonNode report = readLatestReport(service);

        JsonNode cost = report.get("cost");
        assertNotNull(cost);
        assertTrue(cost.get("amount").isNull(), "未配价时金额保持 null");
        assertTrue(cost.get("currency").isNull());
        assertEquals("unpriced: set eval.pricing.*", cost.get("note").asText());
        JsonNode config = report.get("config");
        assertTrue(config.get("pricingInputPer1k").isNull(), "config 显式记录未配价");
        assertTrue(config.get("pricingOutputPer1k").isNull());
        assertEquals("eval.pricing.*", config.get("pricingSource").asText());
    }

    /* ---------- helpers ---------- */

    private static EvalCaseResult resultWithUsage() {
        return new EvalCaseResult("c1", "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(),
                true, true, 1, 1.0, 1.0, "answer", 5, true, "ok", null, EvalDiagnosis.OK, false, null, null,
                12L, null, null, new EvalCaseResult.TokenUsage(1000, 500, 1500), null);
    }

    private RagEvalService newService(RagEvalProperties properties, EvalCaseResult result) {
        RagEvalCaseRunner caseRunner = mock(RagEvalCaseRunner.class);
        EvalCase evalCase = new EvalCase("c1", "q", List.of(), List.of(), "ref");
        when(caseRunner.run(eq(evalCase), anyString(), anyInt(), any())).thenReturn(result);
        RagEvalService service = new RagEvalService(caseRunner, properties, new HybridSearchProperties(),
                new ParentContextProperties(), new ContextualRetrievalProperties(),
                objectMapper, new DirectExecutor());
        service.start(List.of(evalCase), "inline", 3, "cost-accounting", "7", "admin");
        return service;
    }

    private static JsonNode readLatestReport(RagEvalService service) throws Exception {
        RagEvalService.RunStatus status = service.getLatestStatus().orElseThrow();
        assertEquals(RagEvalService.RunState.SUCCEEDED, status.state(), "评测任务应成功完成");
        Path reportPath = Path.of(status.reportPath());
        assertTrue(Files.isRegularFile(reportPath));
        return new ObjectMapper().findAndRegisterModules().readTree(reportPath.toFile());
    }

    /** StreamHandle 是 final 类且构造私有，用反射构造"无上游订阅"的句柄（cancel() 对 null 订阅是空操作） */
    private static LlmProviderRouter.StreamHandle newStreamHandle() throws Exception {
        Constructor<LlmProviderRouter.StreamHandle> constructor =
                LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[]{null, (Runnable) () -> { }});
    }

    private static LlmProviderRouter.ReActTurn turnWithToolCalls(String callId, String toolName, int promptTokens,
                                                                 int completionTokens) {
        return new LlmProviderRouter.ReActTurn("",
                List.of(new LlmProviderRouter.ToolCallDecision(callId, toolName, Map.of("query", "端口"))),
                Map.of("role", "assistant", "content", ""), "tool_calls", promptTokens, completionTokens);
    }

    private static AgentToolRegistry.ToolExecutionResult searchToolResult(List<SearchResult> results) {
        return new AgentToolRegistry.ToolExecutionResult("search_knowledge", true,
                AgentToolRegistry.formatSearchResults(results),
                Map.of("results", results, "outcome", "HIT"), false);
    }

    private static SearchResult hybridResult(String md5, String fileName, String text) {
        return new SearchResult(md5, 1, text, 0.9, "7", "org", false, fileName, null, null, "HYBRID", null);
    }

    /** 同步执行器：start() 在返回前跑完整个评测（与 EvalReportSchemaV2Test 同一手法） */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
