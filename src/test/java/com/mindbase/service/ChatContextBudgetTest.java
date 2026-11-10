package com.mindbase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.client.resilience.ModelCallGuard;
import com.mindbase.client.resilience.ModelWebClientFactory;
import com.mindbase.config.AiProperties;
import com.mindbase.config.ChatDeadlineProperties;
import com.mindbase.config.ModelCallProperties;
import com.mindbase.config.UsageQuotaProperties;
import com.mindbase.entity.SearchResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task-0006 ReAct 上下文预算与引用去重：
 * search_knowledge 结果拼入模型消息前按 chat.react.max-context-tokens 用
 * LlmProviderRouter.estimateObjectMessagesTokens（与计费同源的真实估算）裁剪——首条命中始终保留，
 * 其余按分数从低到高丢弃，丢弃数量计入 droppedChunks；同一 (fileMd5, chunkId) 复用首个引用编号；
 * 估算不可用（返回 0 / 抛异常）时退回字符数上限（tokens×4）并记 warn，不静默放行。
 * <p>
 * 全部用 mock/fixture，不依赖外部服务：router 用真实实例（估算走真实代码）+ spy 打桩流式回合。
 */
class ChatContextBudgetTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmProviderRouter realRouter;
    private LlmProviderRouter router;
    private final List<List<Map<String, Object>>> streamRequests = new ArrayList<>();
    private final List<LlmProviderRouter.ReActTurn> streamTurns = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private Logger pipelineLogger;

    @AfterEach
    void detachLogAppender() {
        if (pipelineLogger != null) {
            logAppender.stop();
            pipelineLogger.detachAppender(logAppender);
            pipelineLogger = null;
        }
    }

    private void attachLogAppender() {
        pipelineLogger = (Logger) LoggerFactory.getLogger(ChatPipeline.class);
        logAppender.start();
        pipelineLogger.addAppender(logAppender);
    }

    /** 真实 LlmProviderRouter（估算走真实 UsageQuotaService 代码），外部依赖全部 mock */
    private LlmProviderRouter newRealRouter(int budgetTokens) throws Exception {
        LlmProviderRouter real = new LlmProviderRouter(
                new AiProperties(),
                mock(RateLimitService.class),
                new UsageQuotaService(null, new UsageQuotaProperties()),
                mock(ModelProviderConfigService.class),
                new ObjectMapper(),
                mock(ModelWebClientFactory.class),
                mock(ModelCallGuard.class),
                new ModelCallProperties());
        Field budgetField = LlmProviderRouter.class.getDeclaredField("reactMaxContextTokens");
        budgetField.setAccessible(true);
        budgetField.setInt(real, budgetTokens);
        return real;
    }

    private void setUpRouter(int budgetTokens) throws Exception {
        realRouter = newRealRouter(budgetTokens);
        router = Mockito.spy(realRouter);
        doAnswer(invocation -> {
            streamRequests.add(new ArrayList<>(invocation.getArgument(1)));
            Consumer<String> onChunk = invocation.getArgument(4);
            Consumer<LlmProviderRouter.ReActTurn> onComplete = invocation.getArgument(6);
            LlmProviderRouter.ReActTurn turn = streamTurns.remove(0);
            if (turn.content() != null && !turn.content().isEmpty()) {
                onChunk.accept(turn.content());
            }
            onComplete.accept(turn);
            return newStreamHandle();
        }).when(router).streamReActTurn(anyString(), anyList(), anyList(), anyInt(), any(), any(), any());
        when(tools.getTools()).thenReturn(List.of(new AgentToolRegistry.AgentTool(
                "search_knowledge", "检索知识库", Map.of("type", "object"))));
    }

    private ChatPipeline newPipeline() {
        return new ChatPipeline(null, searchService, router, null, null, null, null,
                tools, null, objectMapper, null, new ChatDeadlineProperties());
    }

    private ChatPipeline.ReActRunResult runReact(String question) throws Exception {
        return newPipeline().runHeadless("7", "system-eval", question, List.of(), Duration.ofSeconds(90));
    }

    /** task-0006：超预算时按分数从低到高裁剪，首条命中始终保留，且发给模型的 tool 结果总估算 ≤ 预算 */
    @Test
    void overBudgetResultsAreTrimmedByLowestScoreAndFirstHitIsKept() throws Exception {
        setUpRouter(3000);
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(
                        chunk("md5-a", 1, 0.9, "a.pdf"),
                        chunk("md5-b", 2, 0.8, "b.pdf"),
                        chunk("md5-c", 3, 0.7, "c.pdf"),
                        chunk("md5-d", 4, 0.6, "d.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1"));
        streamTurns.add(finalTurn("端口配置见 [1][2]。"));

        ChatPipeline.ReActRunResult result = runReact("端口怎么配？");

        // 首条命中始终保留；低分的后两条被裁掉
        assertEquals(List.of(1, 2), result.references().keySet().stream().sorted().toList(),
                "保留首条与次高分的两条命中，其余按分数从低到高丢弃");
        assertEquals("md5-a", result.references().get(1).fileMd5(), "首条命中始终保留");
        assertEquals("md5-b", result.references().get(2).fileMd5());
        assertEquals(2, result.droppedChunks(), "裁剪数量写入 droppedChunks");
        assertEquals(List.of(1, 2), result.toolRetrieved().keySet().stream().sorted().toList());

        // 发给模型的 tool 结果用真实估算（与计费同源）复核：总估算 ≤ 预算
        List<Map<String, Object>> finalRequest = streamRequests.get(streamRequests.size() - 1);
        List<Map<String, Object>> toolMessages = finalRequest.stream()
                .filter(message -> "tool".equals(message.get("role"))).toList();
        assertEquals(1, toolMessages.size(), "一条 search_knowledge 工具结果");
        int estimated = realRouter.estimateObjectMessagesTokens(toolMessages);
        assertTrue(estimated <= 3000 && estimated > 0,
                "裁剪后 tool 结果总估算应 ≤ 预算 3000，实际: " + estimated);
        String toolContent = String.valueOf(toolMessages.get(0).get("content"));
        assertTrue(toolContent.contains("md5-a"), "保留的片段在工具结果里");
        assertFalse(toolContent.contains("md5-d"), "最低分片段不再发给模型");
    }

    /** task-0006：同一 (fileMd5, chunkId) 出现两次，复用首个引用编号，编号数=1 */
    @Test
    void duplicateChunkReusesFirstReferenceNumber() throws Exception {
        setUpRouter(6000);
        SearchResult firstHit = chunk("same-md5", 3, 0.9, "same.pdf");
        SearchResult duplicateHit = chunk("same-md5", 3, 0.9, "same.pdf");
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(firstHit)),
                searchToolResult(List.of(duplicateHit)));
        streamTurns.add(turnWithToolCalls("call_1"));
        streamTurns.add(turnWithToolCalls("call_2"));
        streamTurns.add(finalTurn("端口配置见 [1]。"));

        ChatPipeline.ReActRunResult result = runReact("端口怎么配？");

        assertEquals(List.of(1), result.references().keySet().stream().sorted().toList(),
                "重复分块复用首个编号，不产生新编号");
        assertEquals(1, result.references().size(), "同一分块出现两次，引用编号数=1");
        assertEquals(1, result.toolRetrieved().size(), "重复分块不重复计入检索证据");
        assertEquals(0, result.droppedChunks(), "去重不是裁剪，droppedChunks 不变");

        List<Map<String, Object>> finalRequest = streamRequests.get(streamRequests.size() - 1);
        List<String> toolContents = finalRequest.stream()
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .toList();
        assertEquals(2, toolContents.size(), "两次检索各产生一条 tool message");
        String secondToolContent = toolContents.get(toolContents.size() - 1);
        assertTrue(secondToolContent.contains("复用编号 [1]"),
                "重复分块的编号复用说明要告知模型: " + secondToolContent);
        assertFalse(secondToolContent.contains("[2]"), "重复分块不得拿到新编号");
    }

    /** task-0006 失败处理：估算返回 0 时退回字符数上限（tokens×4）并记 warn，不得静默放行 */
    @Test
    void zeroEstimateFallsBackToCharLimitAndWarns() throws Exception {
        setUpRouter(10);
        doReturn(0).when(router).estimateObjectMessagesTokens(anyList());
        attachLogAppender();
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(
                        chunk("md5-a", 1, 0.9, "a.pdf"),
                        chunk("md5-b", 2, 0.8, "b.pdf"),
                        chunk("md5-c", 3, 0.7, "c.pdf"),
                        chunk("md5-d", 4, 0.6, "d.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1"));
        streamTurns.add(finalTurn("端口配置见 [1]。"));

        ChatPipeline.ReActRunResult result = runReact("端口怎么配？");

        assertEquals(List.of(1), result.references().keySet().stream().sorted().toList(),
                "字符上限（10×4=40 字符）下仍按首条保留裁剪，不静默放行");
        assertEquals("md5-a", result.references().get(1).fileMd5());
        assertEquals(3, result.droppedChunks());
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("字符数上限")),
                "估算不可用必须记 warn");
    }

    /** task-0006 失败处理：估算抛异常时同样退回字符数上限并记 warn */
    @Test
    void throwingEstimateFallsBackToCharLimitAndWarns() throws Exception {
        setUpRouter(10);
        doThrow(new IllegalStateException("estimator down")).when(router).estimateObjectMessagesTokens(anyList());
        attachLogAppender();
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                searchToolResult(List.of(
                        chunk("md5-a", 1, 0.9, "a.pdf"),
                        chunk("md5-b", 2, 0.8, "b.pdf"))));
        streamTurns.add(turnWithToolCalls("call_1"));
        streamTurns.add(finalTurn("端口配置见 [1]。"));

        ChatPipeline.ReActRunResult result = runReact("端口怎么配？");

        assertEquals(List.of(1), result.references().keySet().stream().sorted().toList(),
                "估算抛异常退回字符数上限（10×4=40 字符），首条仍保留");
        assertEquals("md5-a", result.references().get(1).fileMd5());
        assertEquals(1, result.droppedChunks());
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("字符数上限")),
                "估算抛异常必须记 warn");
    }

    /** task-0006：chat.react.max-context-tokens 落在主配置 application.yml，默认 6000 */
    @Test
    void chatReactMaxContextTokensDefaultsTo6000() throws Exception {
        // 测试 classpath 的 /application.yml 被 src/test/resources 的最小测试配置遮蔽，
        // 这里直接读主配置源文件（surefire 工作目录 = 仓库根）
        java.nio.file.Path config = java.nio.file.Path.of("src", "main", "resources", "application.yml");
        assertTrue(java.nio.file.Files.exists(config), "必须能读到 src/main/resources/application.yml");
        try (InputStream in = java.nio.file.Files.newInputStream(config)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> chat = (Map<String, Object>) root.get("chat");
            assertNotNull(chat, "chat 配置段必须存在");
            Map<String, Object> react = (Map<String, Object>) chat.get("react");
            assertNotNull(react, "chat.react 配置段必须存在");
            assertEquals("${CHAT_REACT_MAX_CONTEXT_TOKENS:6000}",
                    String.valueOf(react.get("max-context-tokens")),
                    "max-context-tokens 默认必须是 6000（env 可覆盖）");
        }
    }

    /** 每条命中 1200 个 CJK 字符（估算 ≈1152 tokens），足够构造超预算场景 */
    private static SearchResult chunk(String md5, int chunkId, double score, String fileName) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            text.append("端");
        }
        return new SearchResult(md5, chunkId, text.toString(), score, "7", "org", false, fileName);
    }

    private static AgentToolRegistry.ToolExecutionResult searchToolResult(List<SearchResult> results) {
        return new AgentToolRegistry.ToolExecutionResult("search_knowledge", true,
                AgentToolRegistry.formatSearchResults(results),
                Map.of("results", results, "outcome", "HIT"), false);
    }

    private static LlmProviderRouter.ReActTurn turnWithToolCalls(String callId) {
        return new LlmProviderRouter.ReActTurn("",
                List.of(new LlmProviderRouter.ToolCallDecision(callId, "search_knowledge", Map.of("query", "端口"))),
                Map.of("role", "assistant", "content", ""), "tool_calls", 10, 5);
    }

    private static LlmProviderRouter.ReActTurn finalTurn(String content) {
        return new LlmProviderRouter.ReActTurn(content, List.of(),
                Map.of("role", "assistant", "content", ""), "stop", 10, 10);
    }

    /** StreamHandle 是 final 类且构造私有，用反射构造"无上游订阅"的句柄（cancel() 对 null 订阅是空操作） */
    private static LlmProviderRouter.StreamHandle newStreamHandle() throws Exception {
        Constructor<LlmProviderRouter.StreamHandle> constructor =
                LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[]{null, (Runnable) () -> { }});
    }
}
