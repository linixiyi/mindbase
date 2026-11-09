package com.mindbase.service.eval;

import com.mindbase.client.resilience.ModelCallPriority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.ChatDeadlineProperties;
import com.mindbase.config.ParentContextProperties;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.repository.DocumentVectorRepository;
import com.mindbase.service.AgentToolRegistry;
import com.mindbase.service.ChatPipeline;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import com.mindbase.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * react 链路门禁：评测生成走生产 ChatPipeline 的 ReAct 循环（与线上一致的轮次/工具调用/引用装配），
 * 工具调用轮数、引用编号、token 用量写入用例明细，可在报告里读到；rag 链路保持历史口径不变。
 */
class EvalReActPipelineTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));

    /** 流式 ReAct 回合按序返回；completeReActTurn（生成直答/评委）按序返回 */
    private final List<LlmProviderRouter.ReActTurn> streamTurns = new ArrayList<>();
    private final List<String> completeReplies = new ArrayList<>();
    private final List<List<Map<String, Object>>> streamRequests = new ArrayList<>();
    private final List<List<Map<String, Object>>> judgeCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(router.buildReActMessages(anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        when(router.buildRagMessages(anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        // 流式回合在调用线程同步完成（内容走 onChunk，与线上流式路径一致），便于断言
        when(router.streamReActTurn(anyString(), anyList(), anyList(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    streamRequests.add(new ArrayList<>(invocation.getArgument(1)));
                    Consumer<String> onChunk = invocation.getArgument(4);
                    Consumer<LlmProviderRouter.ReActTurn> onComplete = invocation.getArgument(6);
                    LlmProviderRouter.ReActTurn turn = streamTurns.remove(0);
                    if (turn.content() != null && !turn.content().isEmpty()) {
                        onChunk.accept(turn.content());
                    }
                    onComplete.accept(turn);
                    return newStreamHandle();
                });
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), eq(ModelCallPriority.BACKGROUND)))
                .thenAnswer(invocation -> {
                    List<Map<String, Object>> messages = invocation.getArgument(1);
                    String reply = completeReplies.remove(0);
                    if (messages.size() > 1 && "system".equals(messages.get(0).get("role"))
                            && String.valueOf(messages.get(0).get("content")).contains("评委")) {
                        judgeCalls.add(messages);
                    }
                    return new LlmProviderRouter.ReActTurn(reply, List.of(), Map.of(), "stop", 3, 3);
                });
        when(tools.getTools()).thenReturn(List.of(new AgentToolRegistry.AgentTool(
                "search_knowledge", "检索知识库", Map.of("type", "object"))));
    }

    @Test
    void defaultModeIsReact() {
        assertEquals("react", properties.getMode(), "eval.mode 默认走生产 ReAct 路径");
        assertTrue(new RagEvalCaseRunner(searchService, router, expander(), properties, objectMapper, Runnable::run)
                .reactMode(), "默认 mode 下 runner 走 react 链路");
    }

    @Test
    void reactModeRunsProductionLoopAndReportsRoundsAndReferences() throws Exception {
        properties.setMode("react");
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                hybridResult("m1", "config.pdf", "端口配置为 8080")));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(
                        new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "org", false, "config.pdf"),
                        new SearchResult("other", 2, "无关内容", 0.5, "7", "org", false, "x.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，见 [1]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 20, 10));
        completeReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, expander(), properties,
                objectMapper, Runnable::run, tools);
        EvalCaseResult result = runner.run(new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080"),
                "7", 3, noSampling);

        assertEquals("端口是 8080，见 [1]。", result.answer());
        assertEquals(5, result.judgeScore());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        // 检索指标仍按 topK 直接检索记录，与 rag 口径一致
        assertTrue(result.retrievalHit());
        assertEquals(1, result.firstRelevantRank());

        // 工具调用轮数与引用编号写入明细，报告可读
        EvalCaseResult.ReactTrace trace = result.reactTrace();
        assertNotNull(trace, "react 模式必须产出 reactTrace");
        assertEquals(2, trace.modelRounds(), "一个工具轮 + 一个最终回答轮");
        assertEquals(1, trace.toolCallRounds());
        assertEquals(1, trace.toolCalls());
        assertEquals(List.of(1, 2), trace.referenceNumbers(), "search_knowledge 的两条结果装配为引用编号 [1][2]");
        assertEquals(2, trace.references().size());
        EvalCaseResult.RetrievedChunk reference = trace.references().get(0);
        assertEquals(1, reference.rank());
        assertEquals("m1", reference.fileMd5());
        assertEquals("config.pdf", reference.fileName());
        assertEquals(1, reference.chunkId());
        assertTrue(reference.relevant(), "引用编号对应的命中按用例标注判为相关");
        assertFalse(trace.references().get(1).relevant(), "非期望文件的相关性为 false");

        // react 循环的 LLM 用量写入 tokenUsage（评委调用不计入生成用量）
        assertNotNull(result.tokenUsage());
        assertEquals(30, result.tokenUsage().promptTokens(), "10 + 20");
        assertEquals(15, result.tokenUsage().completionTokens(), "5 + 10");
        assertEquals(45, result.tokenUsage().totalTokens());

        // 第二轮模型消息带上了工具结果与 [N] 编号：与线上一致的 tool message
        List<Map<String, Object>> secondRound = streamRequests.get(1);
        String toolMessageContent = secondRound.stream()
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst().orElse("");
        assertTrue(toolMessageContent.contains("[1] config.pdf"), "模型看到的片段带引用编号，实际: " + toolMessageContent);

        // 评委证据使用模型 search_knowledge 实际命中的片段
        assertEquals(1, judgeCalls.size());
        JsonNode payload = objectMapper.readTree((String) judgeCalls.get(0).get(1).get("content"));
        assertEquals(2, payload.get("evidence").size());
        assertTrue(payload.get("evidence").get(0).get("text").asText().contains("端口配置为 8080"));
    }

    @Test
    void reactModeAccumulatesReferenceNumbersAcrossToolRounds() throws Exception {
        properties.setMode("react");
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(
                hybridResult("m1", "config.pdf", "端口配置为 8080")));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "org", false, "config.pdf"))),
                searchToolResult(List.of(new SearchResult("m2", 4, "重启后端口生效", 0.8, "7", "org", false, "restart.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(turnWithToolCalls("call_2", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，重启后生效 [1][2]。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 10, 10));
        completeReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, expander(), properties,
                objectMapper, Runnable::run, tools);
        EvalCaseResult result = runner.run(new EvalCase("c1", "端口？", List.of("m1", "m2"), List.of(), "8080"),
                "7", 3, noSampling);

        EvalCaseResult.ReactTrace trace = result.reactTrace();
        assertNotNull(trace);
        assertEquals(3, trace.modelRounds(), "两个工具轮 + 一个最终回答轮");
        assertEquals(2, trace.toolCallRounds(), "两轮模型返回 tool_calls 并执行了工具");
        assertEquals(2, trace.toolCalls());
        assertEquals(List.of(1, 2), trace.referenceNumbers(), "第二次检索的引用编号从上次最大编号之后递增");
        assertEquals("m1", trace.references().get(0).fileMd5());
        assertEquals("m2", trace.references().get(1).fileMd5());
        assertTrue(trace.references().get(1).relevant());
        assertEquals(30, result.tokenUsage().promptTokens(), "生成 token 只计流式回合（评委调用不计入）");
        assertEquals(20, result.tokenUsage().completionTokens());
    }

    @Test
    void headlessPipelineReturnsReferenceDetailsAndToolEvidence() throws Exception {
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(new SearchResult("m1", 1, "端口配置为 8080", 0.9, "7", "org", false, "config.pdf"))),
                searchToolResult(List.of(new SearchResult("m2", 4, "重启后端口生效", 0.8, "7", "org", false, "restart.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(turnWithToolCalls("call_2", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080，重启后生效。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 10, 10));

        // 与生产 RagEvalCaseRunner 相同的 headless 装配：会话/持久化基础设施为 null
        ChatPipeline pipeline = new ChatPipeline(null, searchService, router, null, null, null, null,
                tools, null, objectMapper, null, new ChatDeadlineProperties());

        ChatPipeline.ReActRunResult result = pipeline.runHeadless("7", "system-eval", "端口？", List.of(),
                Duration.ofSeconds(90));

        assertEquals("端口是 8080，重启后生效。", result.answer());
        assertEquals(3, result.modelRounds());
        assertEquals(2, result.toolCallRounds());
        assertEquals(2, result.toolCalls());
        assertEquals(30, result.promptTokens());
        assertEquals(20, result.completionTokens());
        assertEquals(List.of(1, 2), result.references().keySet().stream().sorted().toList());
        assertEquals("config.pdf", result.references().get(1).fileName());
        assertEquals("端口配置为 8080", result.references().get(1).matchedChunkText(), "引用详情是命中的小分块");
        assertEquals("restart.pdf", result.references().get(2).fileName());
        assertEquals(4, result.references().get(2).chunkId());
        assertEquals(2, result.toolRetrieved().size());
    }

    @Test
    void ragModeKeepsLegacySingleShotBehavior() throws Exception {
        properties.setMode("rag");
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                hybridResult("other", "x.pdf", "无关内容"),
                hybridResult("m1", "config.pdf", "端口配置为 8080")));
        completeReplies.add("端口是 8080（来源#2: config.pdf）");
        completeReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, expander(), properties,
                objectMapper, Runnable::run);
        EvalCaseResult result = runner.run(new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080"),
                "7", 3, noSampling);

        // rag 口径与改造前一致：单轮直答 + topK 直接检索证据，无 react 明细
        assertEquals("端口是 8080（来源#2: config.pdf）", result.answer());
        assertEquals(5, result.judgeScore());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertTrue(result.retrievalHit());
        assertEquals(2, result.firstRelevantRank());
        assertNull(result.reactTrace(), "rag 模式不产出 reactTrace");
        assertNull(result.tokenUsage(), "rag 模式与改造前一致，不记录 token 用量");
        assertTrue(streamRequests.isEmpty(), "rag 模式不走流式 ReAct 循环");

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(router).buildRagMessages(eq("端口？"), context.capture());
        assertTrue(context.getValue().contains("[2] config.pdf"), "评委/生成上下文仍用 topK 直接检索结果");
        JsonNode payload = objectMapper.readTree((String) judgeCalls.get(0).get(1).get("content"));
        assertEquals(2, payload.get("evidence").size());
    }

    @Test
    void reactGenerationFailureIsRecordedAsGenerationFailed() {
        properties.setMode("react");
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                hybridResult("m1", "config.pdf", "端口配置为 8080")));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenThrow(
                new IllegalStateException("es down"));
        streamTurns.add(turnWithToolCalls("call_1", "search_knowledge", 10, 5));
        streamTurns.add(new LlmProviderRouter.ReActTurn("端口是 8080。", List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 10, 5));
        completeReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, expander(), properties,
                objectMapper, Runnable::run, tools);
        EvalCaseResult result = runner.run(new EvalCase("c1", "端口？", List.of("m1"), List.of(), "8080"),
                "7", 3, noSampling);

        // 工具执行失败不会中断链路：失败文本作为 tool message 返回模型，最终仍给出回答
        assertEquals("端口是 8080。", result.answer());
        assertNotNull(result.reactTrace());
        assertEquals(1, result.reactTrace().toolCallRounds());
        assertEquals(1, result.reactTrace().toolCalls());
        assertTrue(result.reactTrace().referenceNumbers().isEmpty(), "工具失败没有引用装配");
    }

    /**
     * StreamHandle 是 final 类且构造私有，用反射构造"无上游订阅"的句柄（cancel() 对 null 订阅是空操作）
     */
    private static LlmProviderRouter.StreamHandle newStreamHandle() throws Exception {
        java.lang.reflect.Constructor<LlmProviderRouter.StreamHandle> constructor =
                LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[]{null, (Runnable) () -> { }});
    }

    private ParentContextExpander expander() {
        ParentContextProperties parentProperties = new ParentContextProperties();
        parentProperties.setEnabled(false);
        return new ParentContextExpander(parentProperties, documentVectorRepository);
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
}
