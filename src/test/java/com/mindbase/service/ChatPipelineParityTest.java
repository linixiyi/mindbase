package com.mindbase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.client.resilience.ModelCallGuard;
import com.mindbase.client.resilience.ModelWebClientFactory;
import com.mindbase.config.AiProperties;
import com.mindbase.config.ChatDeadlineProperties;
import com.mindbase.config.ModelCallProperties;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.config.UsageQuotaProperties;
import com.mindbase.service.citation.CitationVerificationService;
import com.mindbase.service.eval.RagEvalCaseRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task-0010 / C10 契约测试：生产 WS 链路与评测 headless 链路共用 {@link ChatPipelineFactory} 装配。
 * <p>
 * 断言两条链路拿到的 maxRounds、工具名集合、模型 provider、system prompt 哈希一致；
 * 引用校验这条"两边本来就不同"的链路差异显式建模为 factory 装配参数（生产开启 / headless 关闭），
 * 不允许 null 隐式表达；headless 构造与运行不依赖 WebSocketSession。
 * <p>
 * 全部用 mock/fixture，不依赖外部服务与 Spring 容器：router 用真实实例（提示词装配与 provider 解析
 * 走真实代码）+ spy 打桩流式回合；生产链路经 {@link ChatHandler} 的 factory 构造器走 processMessage
 * （监控线程池打桩为同步执行），与 ChatContextBudgetTest / ChatHandlerDeadlineTest 的既有打桩方式一致。
 */
class ChatPipelineParityTest {

    private static final String QUESTION = "青鸾计划的技术负责人是谁？";

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final ChatGenerationStateService state = mock(ChatGenerationStateService.class);
    private final ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
    private final CitationVerificationService citationVerificationService = mock(CitationVerificationService.class);
    private final ThreadPoolTaskExecutor inlineExecutor = mock(ThreadPoolTaskExecutor.class);

    /** spy router 捕获的两条链路首轮模型请求（顺序即调用顺序） */
    private final List<List<Map<String, Object>>> streamRequests = new ArrayList<>();
    private final List<LlmProviderRouter.ReActTurn> streamTurns = new ArrayList<>();

    /** task-0010：factory 两条链路的装配一致——maxRounds、工具名集合、模型 provider、截止时间，引用校验差异显式建模 */
    @Test
    @DisplayName("生产与 headless 两条链路的装配一致（maxRounds/工具集/provider/截止时间），引用校验差异显式")
    void factoryGivesBothChainsTheSameAssembly() throws Exception {
        ChatPipelineFactory factory = newFactory(newSpyRouter());
        stubRegistryTools();
        ChatPipeline production = factory.createProductionPipeline();
        ChatPipeline headless = factory.createHeadlessPipeline();

        // maxRounds：两边一致，且来源是 factory 的唯一常量
        assertEquals(factory.maxRounds(), production.maxReactRounds());
        assertEquals(factory.maxRounds(), headless.maxReactRounds());
        assertEquals(ChatPipelineFactory.MAX_REACT_ROUNDS, production.maxReactRounds());

        // 工具名集合：同一注册表实例，两边工具名完全一致
        assertSame(factory.agentToolRegistry(), production.agentToolRegistry());
        assertSame(production.agentToolRegistry(), headless.agentToolRegistry());
        assertEquals(toolNames(production.agentToolRegistry()), toolNames(headless.agentToolRegistry()));
        assertEquals(Set.of("search_knowledge", "generate_summary", "submit_feedback", "knowledge_stats"),
                toolNames(headless.agentToolRegistry()), "工具名集合与 AgentToolRegistry 的注册工具一致");

        // 模型 provider：同一 router 实例，主供应商描述（provider/model）两边一致
        assertSame(factory.llmProviderRouter(), production.llmProviderRouter());
        assertSame(production.llmProviderRouter(), headless.llmProviderRouter());
        assertEquals("primary-provider/parity-model", production.llmProviderRouter().primaryProviderDescription());
        assertEquals(production.llmProviderRouter().primaryProviderDescription(),
                headless.llmProviderRouter().primaryProviderDescription());

        // 截止时间：同一配置实例
        assertSame(factory.deadlineProperties(), production.deadlineProperties());
        assertSame(production.deadlineProperties(), headless.deadlineProperties());

        // 引用校验差异显式建模：生产开启、headless 关闭（不允许 null 隐式表达）
        assertTrue(production.citationVerificationEnabled(), "生产装配引用校验开启");
        assertFalse(headless.citationVerificationEnabled(), "评测 headless 装配显式关闭引用校验");
    }

    /** task-0010：两条链路发给模型的首轮 system prompt 哈希一致（生产走 processMessage，headless 走 runHeadless） */
    @Test
    @DisplayName("两条链路的首轮 system prompt 哈希一致，整条首轮消息一致")
    void systemPromptHashIsIdenticalOnBothChains() throws Exception {
        ChatPipelineFactory factory = newFactory(newSpyRouter());
        stubRegistryTools();
        stubStateAndExecutor();
        streamTurns.add(finalTurn("端口是 8080。"));

        // 生产 WS 链路：经 ChatHandler 的 factory 构造器走 processMessage（WebSocketSession 只是生产侧入参）
        ChatHandler handler = new ChatHandler(factory, sessionRegistry);
        handler.processMessage("7", QUESTION, mock(WebSocketSession.class), "conv-1");

        // 评测 headless 链路：factory 的 headless 装配走 runHeadless（无 WebSocketSession）
        streamTurns.add(finalTurn("端口是 8080。"));
        ChatPipeline headless = factory.createHeadlessPipeline();
        ChatPipeline.ReActRunResult result =
                headless.runHeadless("7", "system-eval", QUESTION, List.of(), Duration.ofSeconds(90));

        assertEquals("端口是 8080。", result.answer());
        assertEquals(2, streamRequests.size(), "两条链路各一次首轮模型调用");
        Map<String, Object> productionSystem = streamRequests.get(0).get(0);
        Map<String, Object> headlessSystem = streamRequests.get(1).get(0);
        assertEquals("system", productionSystem.get("role"));
        assertEquals("system", headlessSystem.get("role"));
        String productionPrompt = String.valueOf(productionSystem.get("content"));
        assertFalse(productionPrompt.isBlank(), "system prompt 不为空");
        assertEquals(sha256(productionPrompt), sha256(String.valueOf(headlessSystem.get("content"))),
                "两条链路的 system prompt 哈希必须一致");
        assertEquals(streamRequests.get(0), streamRequests.get(1),
                "同问题、同空历史、同反馈指导下，两条链路首轮消息完全一致");
    }

    /** task-0010：headless 构造与运行不依赖 WebSocketSession（离线装配可独立构造并跑通） */
    @Test
    @DisplayName("headless 离线构造跑通且签名不涉及 WebSocketSession")
    void headlessConstructionDoesNotDependOnWebSocketSession() throws Exception {
        ChatPipelineFactory offline = ChatPipelineFactory.offline(searchService, newSpyRouter(), tools, objectMapper);
        stubRegistryTools();
        ChatPipeline headless = offline.createHeadlessPipeline();
        assertEquals(offline.maxRounds(), headless.maxReactRounds());
        assertFalse(headless.citationVerificationEnabled());
        streamTurns.add(finalTurn("端口是 8080。"));

        ChatPipeline.ReActRunResult result =
                headless.runHeadless("7", "system-eval", QUESTION, List.of(), Duration.ofSeconds(90));

        assertEquals("端口是 8080。", result.answer(), "headless 运行不依赖任何会话基础设施");

        // 契约：runHeadless 与 factory 链路创建方法的参数都不涉及 WebSocketSession
        for (Method method : ChatPipeline.class.getDeclaredMethods()) {
            if ("runHeadless".equals(method.getName())) {
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertNotEquals(WebSocketSession.class, parameterType,
                            "runHeadless 的参数不得依赖 WebSocketSession");
                }
            }
        }
        for (Method method : ChatPipelineFactory.class.getDeclaredMethods()) {
            if (method.getName().startsWith("create")) {
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertNotEquals(WebSocketSession.class, parameterType,
                            "factory 链路创建方法不得依赖 WebSocketSession");
                }
            }
        }
        for (Field field : ChatPipelineFactory.SessionInfra.class.getDeclaredFields()) {
            assertNotEquals(WebSocketSession.class, field.getType(),
                    "会话基础设施集合不得持有 WebSocketSession");
        }
    }

    /** task-0010：评测 runner 与 ChatHandler 都实际经 factory 装配（消费方接线固化） */
    @Test
    @DisplayName("RagEvalCaseRunner 的 headless 实例由 factory 装配，@Deprecated 重载装配结果一致")
    void evalRunnerUsesFactoryHeadlessAssembly() throws Exception {
        LlmProviderRouter router = newSpyRouter();
        stubRegistryTools();
        ChatPipelineFactory factory = newFactory(router);

        // 生产/评测正式装配路径（@Autowired 构造器参数）：runner 的 headless 实例来自 factory
        RagEvalCaseRunner runner = new RagEvalCaseRunner(searchService, router, null, new RagEvalProperties(),
                objectMapper, Runnable::run, tools, mock(ModelProviderConfigService.class),
                mock(ModelWebClientFactory.class), factory);
        ChatPipeline evalPipeline = readReactPipeline(runner);
        assertSame(factory.agentToolRegistry(), evalPipeline.agentToolRegistry());
        assertSame(factory.llmProviderRouter(), evalPipeline.llmProviderRouter());
        assertEquals(factory.maxRounds(), evalPipeline.maxReactRounds());
        assertFalse(evalPipeline.citationVerificationEnabled(), "评测 headless 显式关闭引用校验（非 null 占位）");

        // 既有测试保留的 @Deprecated 重载：内部也经 factory 装配，结果与正式路径一致
        RagEvalCaseRunner legacy = new RagEvalCaseRunner(searchService, router, null, new RagEvalProperties(),
                objectMapper, Runnable::run, tools);
        ChatPipeline legacyPipeline = readReactPipeline(legacy);
        assertEquals(evalPipeline.maxReactRounds(), legacyPipeline.maxReactRounds());
        assertEquals(toolNames(evalPipeline.agentToolRegistry()), toolNames(legacyPipeline.agentToolRegistry()));
        assertSame(factory.llmProviderRouter(), legacyPipeline.llmProviderRouter());
        assertFalse(legacyPipeline.citationVerificationEnabled());
    }

    // —— 装配与打桩 ——

    /** 全量依赖的 factory：共享链路配置 + mock 会话基础设施，与 Spring 生产装配同一构造器 */
    private ChatPipelineFactory newFactory(LlmProviderRouter router) {
        stubStateAndExecutor();
        return new ChatPipelineFactory(searchService, router, tools, objectMapper, new ChatDeadlineProperties(),
                newRedisMock(), rateLimitService, conversationService, state, sessionRegistry,
                citationVerificationService, inlineExecutor);
    }

    /** 真实 LlmProviderRouter（提示词装配与 provider 解析走真实代码）+ spy 打桩流式回合并捕获请求 */
    private LlmProviderRouter newSpyRouter() throws Exception {
        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(
                new ModelProviderConfigService.ActiveProviderView("primary-provider", "主供应商",
                        ModelProviderConfigService.API_STYLE_OPENAI, "http://127.0.0.1:1/v1",
                        "parity-model", "test-key", null)));
        LlmProviderRouter real = new LlmProviderRouter(
                new AiProperties(),
                mock(RateLimitService.class),
                new UsageQuotaService(null, new UsageQuotaProperties()),
                providerConfig,
                new ObjectMapper(),
                mock(ModelWebClientFactory.class),
                mock(ModelCallGuard.class),
                new ModelCallProperties());
        LlmProviderRouter router = Mockito.spy(real);
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
        return router;
    }

    /** 工具名集合按真实注册表注册的四件套打桩：两条链路的工具名断言都相对这份清单 */
    private void stubRegistryTools() {
        when(tools.getTools()).thenReturn(List.of(
                new AgentToolRegistry.AgentTool("search_knowledge", "检索知识库", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("generate_summary", "生成总结", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("submit_feedback", "提交反馈", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("knowledge_stats", "知识库统计", Map.of("type", "object"))));
    }

    /** 生产链路的生成快照与监控线程池打桩：快照按入参返回、线程池同步执行 ReAct 循环 */
    private void stubStateAndExecutor() {
        when(state.createGeneration(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new ChatGenerationStateService.GenerationSnapshot(invocation.getArgument(0), "7", "conv-1", "q",
                        ChatGenerationStateService.GenerationStatus.STREAMING, "", "", "", null, null, Map.of()));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(inlineExecutor).execute(any(Runnable.class));
    }

    /**
     * redis 打桩（与 ChatHandlerDeadlineTest 相同的方式）：会话历史读写返回空、用户反馈 hash 为空，
     * 两条链路的 system prompt 在"无历史、无反馈"基线上对比
     */
    @SuppressWarnings("unchecked")
    private RedisTemplate<String, String> newRedisMock() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(hash.entries(anyString())).thenReturn(Map.of());
        when(redis.opsForHash()).thenReturn((HashOperations) hash);
        return redis;
    }

    // —— 断言辅助 ——

    private static Set<String> toolNames(AgentToolRegistry registry) {
        return registry.getTools().stream()
                .map(AgentToolRegistry.AgentTool::name)
                .collect(Collectors.toSet());
    }

    private static ChatPipeline readReactPipeline(RagEvalCaseRunner runner) throws Exception {
        Field field = RagEvalCaseRunner.class.getDeclaredField("reactPipeline");
        field.setAccessible(true);
        return (ChatPipeline) field.get(runner);
    }

    private static String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(content.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
