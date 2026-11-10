package com.mindbase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mindbase.config.ChatDeadlineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.entity.SearchResult;
import com.mindbase.exception.RateLimitExceededException;
import com.mindbase.model.SearchOutcome;
import com.mindbase.service.citation.CitationVerdict;
import com.mindbase.service.citation.CitationVerificationService;
import com.mindbase.utils.RequestDeadline;
import com.mindbase.utils.TraceContext;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap.KeySetView;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 聊天编排核心：ReAct 轮次、工具调用、引用装配，以及生成收尾（引用校验、落库、完成推送）。
 * 与 {@code WebSocketSession} 完全无关——连接相关的绑定与直接发送都通过 {@link SessionSink} 适配，
 * 由 {@link ChatHandler}（生产 WS 链路）和 {@code RagEvalCaseRunner}（离线评测 react 链路）各自实例化，
 * 两条链路的装配统一经 {@link ChatPipelineFactory}（工具注册表/最大轮数/提示词/模型路由/截止时间一致）。
 * <p>
 * 实例内的状态全部按 generationId 隔离，两个适配器各持一个实例互不影响。
 * 会话基础设施（redis/session 注册表/会话服务/生成快照）在 headless 装配下为空（显式 SessionInfra.none()）：
 * 离线评测以 headless 方式运行（{@link #runHeadless}），空分支在生产装配下永远不会走到。
 */
public class ChatPipeline {

    private static final Logger logger = LoggerFactory.getLogger(ChatPipeline.class);
    private static final int MAX_CONTEXT_SNIPPET_LEN = 300;
    private static final int MAX_MATCHED_CHUNK_LEN = 800;
    private static final int MAX_EVIDENCE_SNIPPET_LEN = 160;
    private static final int GENERATION_COMPLETION_TIMEOUT_SECONDS = 120;
    private static final int MAX_REACT_TOOL_CALLS = 8;
    private static final int REACT_MAX_COMPLETION_TOKENS = 2000;
    /** chat.react.max-context-tokens 缺省值：预算读不到（无 router/非法值）时的兜底，与 yml 默认一致 */
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 6000;
    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final LlmProviderRouter llmProviderRouter;
    private final RateLimitService rateLimitService;
    private final ConversationService conversationService;
    private final ChatGenerationStateService chatGenerationStateService;
    private final ChatSessionRegistry chatSessionRegistry;
    private final AgentToolRegistry agentToolRegistry;
    private final CitationVerificationService citationVerificationService;
    private final ThreadPoolTaskExecutor chatMonitorExecutor;
    private final ObjectMapper objectMapper;
    private final ChatDeadlineProperties deadlineProperties;
    /** ReAct 最大轮数：由 ChatPipelineFactory 统一装配传入，生产与评测两条链路取同一来源 */
    private final int maxReactRounds;
    /**
     * 引用校验是否随装配开启（task-0010 显式建模）：生产装配 true，评测 headless 装配 false。
     * 两链路的这个差异是装配参数，不允许用 null 隐式表达；实际生效还取决于
     * citationVerificationService 与 ai.citation-verification.enabled
     */
    private final boolean citationVerificationEnabled;

    // 用于存储每次生成任务的完整响应
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    // 用于跟踪每次生成任务的响应完成状态
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    // 用于持有正在进行中的 LLM 流，支持主动取消上游请求
    private final Map<String, LlmProviderRouter.StreamHandle> activeStreams = new ConcurrentHashMap<>();
    // 停止标志 - 简单方案
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    // 用于标记已经取消的生成任务，防止后续又被落成 completed
    private final KeySetView<String, Boolean> cancelledGenerations = ConcurrentHashMap.newKeySet();
    // 用于存储每次生成任务的引用映射：generationId -> {referenceNumber -> detail}
    private final Map<String, Map<Integer, ChatHandler.ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();
    // 父子检索时模型看到的是父块：引用校验按父块判断是否被支撑，避免把相邻分块里的内容误判为可疑引用。
    // 只在内存里保存到本次生成结束，不写进 ChatHandler.ReferenceInfo（引用展示和持久化仍只用命中的小分块）
    private final Map<String, Map<Integer, String>> generationParentContexts = new ConcurrentHashMap<>();
    // ReAct search_knowledge 实际命中的检索结果（引用编号 → 结果），供评测链路读取模型真实使用的证据
    private final Map<String, Map<Integer, SearchResult>> generationToolResults = new ConcurrentHashMap<>();
    // 上下文预算裁剪：本次生成中因超出 chat.react.max-context-tokens 被丢弃的检索分块累计数
    private final Map<String, Integer> generationDroppedChunks = new ConcurrentHashMap<>();
    // 引用校验整体状态（PASSED/TRUNCATED/UNVERIFIED/NO_CITATIONS）：生产链路写入回答元数据与完成帧，headless 随 ReActRunResult 带回
    private final Map<String, String> generationCitationStatuses = new ConcurrentHashMap<>();

    /**
     * 工厂装配构造器：{@link ChatPipelineFactory} 的两条链路都从这里实例化。
     * 会话基础设施打包成 {@link ChatPipelineFactory.SessionInfra}（headless 显式 none()），
     * 轮数与引用校验开关作为显式装配参数传入。
     */
    ChatPipeline(HybridSearchService searchService,
                 LlmProviderRouter llmProviderRouter,
                 AgentToolRegistry agentToolRegistry,
                 ObjectMapper objectMapper,
                 ChatDeadlineProperties deadlineProperties,
                 int maxReactRounds,
                 boolean citationVerificationEnabled,
                 CitationVerificationService citationVerificationService,
                 ChatPipelineFactory.SessionInfra sessionInfra) {
        this.searchService = searchService;
        this.llmProviderRouter = llmProviderRouter;
        this.agentToolRegistry = agentToolRegistry;
        this.objectMapper = objectMapper;
        this.deadlineProperties = deadlineProperties == null ? new ChatDeadlineProperties() : deadlineProperties;
        this.maxReactRounds = Math.max(maxReactRounds, 1);
        this.citationVerificationEnabled = citationVerificationEnabled;
        this.citationVerificationService = citationVerificationService;
        this.redisTemplate = sessionInfra.redisTemplate();
        this.rateLimitService = sessionInfra.rateLimitService();
        this.conversationService = sessionInfra.conversationService();
        this.chatGenerationStateService = sessionInfra.chatGenerationStateService();
        this.chatSessionRegistry = sessionInfra.chatSessionRegistry();
        this.chatMonitorExecutor = sessionInfra.chatMonitorExecutor();
    }

    /**
     * @deprecated 生产装配走 {@link ChatPipelineFactory#createProductionPipeline()}，评测装配走
     * {@link ChatPipelineFactory#createHeadlessPipeline()}；本重载仅为既有测试保留，生产与评测链路不得再调用。
     */
    @Deprecated
    public ChatPipeline(RedisTemplate<String, String> redisTemplate,
                        HybridSearchService searchService,
                        LlmProviderRouter llmProviderRouter,
                        RateLimitService rateLimitService,
                        ConversationService conversationService,
                        ChatGenerationStateService chatGenerationStateService,
                        ChatSessionRegistry chatSessionRegistry,
                        AgentToolRegistry agentToolRegistry,
                        CitationVerificationService citationVerificationService,
                        ObjectMapper objectMapper,
                        @Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor chatMonitorExecutor,
                        ChatDeadlineProperties deadlineProperties) {
        this(searchService, llmProviderRouter, agentToolRegistry, objectMapper, deadlineProperties,
                ChatPipelineFactory.MAX_REACT_ROUNDS, citationVerificationService != null, citationVerificationService,
                new ChatPipelineFactory.SessionInfra(redisTemplate, rateLimitService, conversationService,
                        chatGenerationStateService, chatSessionRegistry, chatMonitorExecutor));
    }

    /**
     * 会话出口抽象：把"当前连接"的绑定与直接发送交给适配器，pipeline 不感知 WebSocketSession
     */
    public interface SessionSink {
        /** 直接发给当前连接的帧（错误帧、幂等复用时的 start 帧） */
        void sendJson(Map<String, ?> frame);

        /** 把当前连接绑定为该生成的推送目标 */
        void bindGeneration(String userId, String generationId);

        /** 重复提交/断线重连时，把当前连接重新挂到进行中的生成上 */
        void resumeGeneration(String userId, String generationId);
    }

    /**
     * headless（无会话）同步 ReAct 运行：与 {@link #processMessage} 共用同一 ReAct 循环、工具执行与引用装配，
     * 但不做会话管理、幂等、限流、落库、生成快照与完成推送，回答与装配结果直接返回给调用方。
     * 供离线评测（RagEvalCaseRunner react 模式）等无 WebSocket 基础设施的调用方使用。
     *
     * @param toolUserId     工具执行（search_knowledge 权限过滤）使用的用户身份
     * @param llmRequesterId 模型调用的请求方 ID（评测用 system-eval，避免占用真实用户配额）
     * @param budget         本次运行的总时间预算，与生产共用 RequestDeadline 机制
     */
    public ReActRunResult runHeadless(String toolUserId,
                                      String llmRequesterId,
                                      String userMessage,
                                      List<Map<String, String>> history,
                                      Duration budget) throws Exception {
        String generationId = UUID.randomUUID().toString();
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        responseBuilders.put(generationId, new StringBuilder());
        responseFutures.put(generationId, responseFuture);
        ReactRunStats stats = new ReactRunStats();
        Map<Integer, ChatHandler.ReferenceInfo> references;
        Map<Integer, SearchResult> toolRetrieved;
        int droppedChunks;
        String citationVerificationStatus;
        try (RequestDeadline.Scope ignored = RequestDeadline.open(RequestDeadline.after(budget))) {
            runReActLoop(toolUserId, llmRequesterId, userMessage, null, generationId,
                    history == null ? List.of() : history, responseFuture, stats, true);
        } finally {
            references = Map.copyOf(generationReferenceMappings.getOrDefault(generationId, Map.of()));
            toolRetrieved = Map.copyOf(generationToolResults.getOrDefault(generationId, Map.of()));
            droppedChunks = generationDroppedChunks.getOrDefault(generationId, 0);
            citationVerificationStatus = generationCitationStatuses.get(generationId);
            cleanupGenerationState(generationId, null);
        }
        // 正常收尾时 future 已被 finalizeResponse 完成；循环中途异常时异常已向上抛出
        String answer = responseFuture.get(5, TimeUnit.SECONDS);
        return new ReActRunResult(answer, stats.modelRounds, stats.toolCallRounds, stats.toolCalls,
                stats.promptTokens, stats.completionTokens, references, toolRetrieved, droppedChunks,
                citationVerificationStatus);
    }

    /**
     * headless 运行的可观测结果：回答、ReAct 轮次与工具调用次数、引用编号装配结果、模型实际检索到的片段，
     * 上下文预算裁剪丢弃的分块数（droppedChunks，0 表示没有裁剪；评测链路据此写 reactTrace.droppedChunks），
     * 以及引用校验整体状态（citationVerificationStatus，PASSED/TRUNCATED/UNVERIFIED/NO_CITATIONS；
     * 校验未开启时为 null，评测链路可据此区分"校验通过"与"没校验"）
     */
    public record ReActRunResult(
            String answer,
            int modelRounds,
            int toolCallRounds,
            int toolCalls,
            int promptTokens,
            int completionTokens,
            Map<Integer, ChatHandler.ReferenceInfo> references,
            Map<Integer, SearchResult> toolRetrieved,
            int droppedChunks,
            String citationVerificationStatus
    ) {
    }

    // —— 装配一致性只读视图：供 ChatPipelineParityTest 断言两条链路装配一致 ——
    // （包私有：pipeline 不在 Spring 容器里，这些值只能来自 ChatPipelineFactory 装配）

    /** 两条链路统一的 ReAct 最大轮数 */
    int maxReactRounds() {
        return maxReactRounds;
    }

    /** 引用校验是否随装配开启：生产 true / headless false，差异显式建模而非 null 占位 */
    boolean citationVerificationEnabled() {
        return citationVerificationEnabled;
    }

    /** 两条链路统一的工具注册表 */
    AgentToolRegistry agentToolRegistry() {
        return agentToolRegistry;
    }

    /** 两条链路统一的提示词与模型路由入口 */
    LlmProviderRouter llmProviderRouter() {
        return llmProviderRouter;
    }

    /** 两条链路统一的截止时间配置 */
    ChatDeadlineProperties deadlineProperties() {
        return deadlineProperties;
    }

    /** ReAct 循环的过程计数，生产链路忽略，headless 链路写入评测报告 */
    private static final class ReactRunStats {
        private int modelRounds;
        private int toolCallRounds;
        private int toolCalls;
        private int promptTokens;
        private int completionTokens;
    }

    public void processMessage(String userId, String userMessage, SessionSink sink, String requestedConversationId) {
        // 请求级截止时间从收到消息开始计算，ReAct 循环、检索、重排序共用这一个预算
        long deadlineNanos = RequestDeadline.after(Duration.ofSeconds(Math.max(deadlineProperties.getTotalSeconds(), 1L)));
        String conversationId = null;
        String generationId = null;
        String claimedGenerationId = null;
        try {
            // 1. 获取或创建会话 ID
            if (requestedConversationId != null && !requestedConversationId.isBlank()) {
                conversationService.validateConversationOwner(Long.parseLong(userId), requestedConversationId);
                conversationId = requestedConversationId;
            } else {
                conversationId = getOrCreateConversationId(userId);
            }

            // 请求幂等：同一用户在同一会话里重复提交同一个问题（重复点击、断线重发），复用进行中的生成，不再调用一次模型，也不占限流额度
            String candidateGenerationId = UUID.randomUUID().toString();
            String inFlightGenerationId = chatGenerationStateService.claimRequest(
                    userId, conversationId, userMessage, candidateGenerationId);
            if (inFlightGenerationId != null) {
                attachToInFlightGeneration(userId, inFlightGenerationId, conversationId, sink);
                return;
            }
            claimedGenerationId = candidateGenerationId;

            rateLimitService.checkChatByUser(userId);
            conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);
            ChatGenerationStateService.GenerationSnapshot generation =
                    chatGenerationStateService.createGeneration(candidateGenerationId, userId, conversationId, userMessage);
            generationId = generation.generationId();
            sink.bindGeneration(userId, generationId);
            final String finalConversationId = conversationId;
            final String finalGenerationId = generationId;
            logger.info("会话ID: {}, 用户ID: {}", conversationId, userId);
            sendGenerationStart(userId, finalGenerationId, finalConversationId);

            // 为当前生成任务创建响应构建器
            responseBuilders.put(finalGenerationId, new StringBuilder());
            // 创建一个CompletableFuture来跟踪响应完成状态
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(finalGenerationId, responseFuture);

            // 2. 获取对话历史
            List<Map<String, String>> history = getConversationHistory(conversationId);
            logger.debug("获取到 {} 条历史对话", history.size());

            // 3. 异步执行 ReAct 决策循环：模型按需返回 tool_calls，避免在 WebSocket 处理线程上阻塞 90s+ 的工具流
            try {
                // traceId 跨线程传播（P1c）：入口线程的 MDC 快照在池线程恢复，处理结束清理防线程池污染
                Map<String, String> traceContext = MDC.getCopyOfContextMap();
                chatMonitorExecutor.execute(() -> {
                    if (traceContext != null) {
                        MDC.setContextMap(traceContext);
                    }
                    try {
                        runReActLoopSafely(userId, userMessage, finalConversationId, finalGenerationId, history,
                                responseFuture, deadlineNanos);
                    } finally {
                        TraceContext.clear();
                    }
                });
            } catch (RejectedExecutionException ex) {
                logger.warn("聊天处理线程池已满，generationId: {}", finalGenerationId);
                chatGenerationStateService.markFailed(finalGenerationId,
                        ChatFailureCategory.BUSY.name(), failureUserMessage(ChatFailureCategory.BUSY));
                persistFailedConversation(userId, userMessage, finalConversationId, ChatFailureCategory.BUSY);
                handleError(userId, finalGenerationId, ChatFailureCategory.BUSY, ex);
                sendCompletionNotification(userId, finalGenerationId, finalConversationId, true, false);
                cleanupGenerationState(finalGenerationId, ex);
            }

        } catch (RateLimitExceededException e) {
            releaseUnstartedRequest(userId, conversationId, userMessage, claimedGenerationId, generationId);
            sink.sendJson(Map.of(
                    "type", "error", "code", 429, "message", e.getMessage(),
                    "errorCategory", "RATE_LIMIT",
                    "retryAfterSeconds", e.getRetryAfterSeconds()));
        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            releaseUnstartedRequest(userId, conversationId, userMessage, claimedGenerationId, generationId);
            ChatFailureCategory category = resolveFailureCategory(e);
            if (generationId != null) {
                chatGenerationStateService.markFailed(generationId, category.name(), failureUserMessage(category));
            }
            if (conversationId != null && !conversationId.isBlank()) {
                persistFailedConversation(userId, userMessage, conversationId, category);
            }
            sink.sendJson(buildErrorFrame(generationId, category));
            if (generationId != null) {
                cleanupGenerationState(generationId, e);
            }
        }
    }

    /**
     * 把当前连接挂到进行中的生成上，后续增量照常推送；start 里带上已经生成的内容，前端据此补齐
     */
    private void attachToInFlightGeneration(String userId, String generationId, String conversationId, SessionSink sink) {
        logger.info("重复提交同一问题，复用进行中的生成: userId={}, generationId={}", userId, generationId);
        sink.resumeGeneration(userId, generationId);
        String content = chatGenerationStateService.getGenerationForUser(generationId, userId)
                .map(ChatGenerationStateService.GenerationSnapshot::content)
                .orElse("");
        sink.sendJson(Map.of(
                "type", "start",
                "generationId", generationId,
                "conversationId", conversationId,
                "timestamp", System.currentTimeMillis(),
                "resumed", true,
                "content", content
        ));
    }

    /**
     * 生成任务还没创建就失败（限流、会话校验失败等）时释放幂等键，用户可以立即重试
     */
    private void releaseUnstartedRequest(String userId, String conversationId, String userMessage,
                                         String claimedGenerationId, String generationId) {
        if (claimedGenerationId == null || generationId != null) {
            return;
        }
        try {
            chatGenerationStateService.releaseRequest(userId, conversationId, userMessage, claimedGenerationId);
        } catch (Exception e) {
            logger.warn("释放请求幂等键失败，等待过期: userId={}, error={}", userId, e.getMessage());
        }
    }

    private void runReActLoopSafely(String userId,
                                    String userMessage,
                                    String conversationId,
                                    String generationId,
                                    List<Map<String, String>> history,
                                    CompletableFuture<String> responseFuture,
                                    long deadlineNanos) {
        try (RequestDeadline.Scope ignored = RequestDeadline.open(deadlineNanos)) {
            runReActLoop(userId, userId, userMessage, conversationId, generationId, history, responseFuture, new ReactRunStats(), false);
        } catch (Exception e) {
            logger.error("ReAct 循环执行失败: generationId={}", generationId, e);
            ChatFailureCategory category = resolveFailureCategory(e);
            chatGenerationStateService.markFailed(generationId, category.name(), failureUserMessage(category));
            persistFailedConversation(userId, userMessage, conversationId, category);
            handleError(userId, generationId, category, e);
            sendCompletionNotification(userId, generationId, conversationId, true, false);
            cleanupGenerationState(generationId, e);
        }
    }

    /**
     * @param llmRequesterId 生产链路与 userId 相同；评测链路传 system-eval，模型调用不占真实用户配额
     * @param headless       true 时不落库、不写生成快照、不推完成帧（评测链路）；false 为生产行为
     */
    private void runReActLoop(String userId,
                              String llmRequesterId,
                              String userMessage,
                              String conversationId,
                              String generationId,
                              List<Map<String, String>> history,
                              CompletableFuture<String> responseFuture,
                              ReactRunStats stats,
                              boolean headless) {
        // 循环会向 messages 追加 assistant/tool 消息：防御性拷贝，不依赖路由实现返回可变列表
        List<Map<String, Object>> messages = new ArrayList<>(llmProviderRouter.buildReActMessages(
                userMessage,
                "",
                history,
                buildRecentFeedbackGuidance(userId)
        ));
        int executedToolCalls = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int round = 1; round <= maxReactRounds; round++) {
            if (finishCancelledGeneration(generationId, responseFuture, responseBuilders.get(generationId))) {
                return;
            }
            // 第一轮总是带工具：知识库优先，至少要检索一次；它的工具执行仍受"截止时间减最终回答预留"约束。
            // 从第二轮起，剩余时间不够再来一轮工具调用就提前收尾
            if (round > 1 && !hasTimeForToolRound()) {
                logger.info("问答剩余时间不足以再进行一轮工具调用，提前收尾: generationId={}, round={}, remainingMs={}",
                        generationId, round, RequestDeadline.remaining().map(Duration::toMillis).orElse(-1L));
                break;
            }

            LlmProviderRouter.ReActTurn turn = streamReActTurnBlocking(
                    llmRequesterId, conversationId, generationId, messages, agentToolRegistry.getTools());
            if (turn == null) {
                // 上游 stream 被取消（如用户点 stop），保证内存映射被回收
                cleanupGenerationState(generationId, null);
                return;
            }
            totalPromptTokens += turn.promptTokens();
            totalCompletionTokens += turn.completionTokens();
            stats.modelRounds++;
            stats.promptTokens = totalPromptTokens;
            stats.completionTokens = totalCompletionTokens;

            if (turn.toolCalls().isEmpty()) {
                finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                        responseBuilders.get(generationId),
                        new LlmProviderRouter.StreamCompletion(turn.finishReason(), totalPromptTokens, totalCompletionTokens, turn.content().length()),
                        stats, headless);
                return;
            }

            stats.toolCallRounds++;
            messages.add(turn.assistantMessage());
            for (LlmProviderRouter.ToolCallDecision toolCall : turn.toolCalls()) {
                ExecutedToolResult executedToolResult;
                if (executedToolCalls >= MAX_REACT_TOOL_CALLS) {
                    executedToolResult = new ExecutedToolResult(
                            "工具调用预算已用尽，本次工具未执行。请基于已有 tool 结果给出最终回答。",
                            false
                    );
                    sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
                } else {
                    // 工具只能用到截止时间减去最终回答预留的那一段，检索慢了也不会挤掉最终回答的时间
                    try (RequestDeadline.Scope ignored = openToolDeadline()) {
                        executedToolResult = executeToolForReAct(userId, userMessage, generationId, conversationId, toolCall);
                    }
                    executedToolCalls++;
                    stats.toolCalls = executedToolCalls;
                }
                messages.add(toolMessage(toolCall.id(), executedToolResult.content()));
                if (executedToolResult.streamedToUser()) {
                    finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                            responseBuilders.get(generationId),
                            new LlmProviderRouter.StreamCompletion(
                                    "tool_streamed",
                                    totalPromptTokens,
                                    totalCompletionTokens,
                                    responseBuilders.get(generationId) != null ? responseBuilders.get(generationId).length() : 0
                            ),
                            stats, headless);
                    return;
                }
            }
        }

        // 轮次用尽或剩余时间不够再调用工具，都走这里：不带工具让模型直接给出最终回答
        messages.add(Map.of(
                "role", "user",
                "content", "ReAct 轮次或时间预算已用尽，请不要再调用工具，直接基于已有 tool 结果给出最终回答。"
        ));
        LlmProviderRouter.ReActTurn finalTurn = streamReActTurnBlocking(
                llmRequesterId, conversationId, generationId, messages, List.of());
        if (finalTurn == null) {
            cleanupGenerationState(generationId, null);
            return;
        }
        totalPromptTokens += finalTurn.promptTokens();
        totalCompletionTokens += finalTurn.completionTokens();
        stats.modelRounds++;
        stats.promptTokens = totalPromptTokens;
        stats.completionTokens = totalCompletionTokens;
        finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                responseBuilders.get(generationId),
                new LlmProviderRouter.StreamCompletion(finalTurn.finishReason(), totalPromptTokens, totalCompletionTokens, finalTurn.content().length()),
                stats, headless);
    }

    /**
     * 剩余时间至少要够"一轮工具调用 + 最终回答预留"，否则不再进入新的工具轮
     */
    private boolean hasTimeForToolRound() {
        return RequestDeadline.hasAtLeast(Duration.ofSeconds(
                deadlineProperties.getFinalAnswerReserveSeconds() + deadlineProperties.getMinToolRoundSeconds()));
    }

    private RequestDeadline.Scope openToolDeadline() {
        long reserveNanos = TimeUnit.SECONDS.toNanos(Math.max(deadlineProperties.getFinalAnswerReserveSeconds(), 0L));
        return RequestDeadline.currentNanos()
                .map(deadline -> RequestDeadline.open(deadline - reserveNanos))
                .orElse(() -> { });
    }

    private ExecutedToolResult executeToolForReAct(String userId,
                                                   String userMessage,
                                                   String generationId,
                                                   String conversationId,
                                                   LlmProviderRouter.ToolCallDecision toolCall) {
        sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing");
        AtomicBoolean summaryStreamStarted = new AtomicBoolean(false);
        try {
            logger.info("ReAct 执行 Agent Tool: name={}, userId={}, generationId={}, toolCallId={}, args={}",
                    toolCall.name(), userId, generationId, toolCall.id(), toolCall.arguments());
            Consumer<String> toolChunkConsumer = "generate_summary".equals(toolCall.name())
                    ? chunk -> {
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        if (summaryStreamStarted.compareAndSet(false, true)) {
                            appendStreamChunk(userId, generationId, conversationId, "\n\n");
                        }
                        appendStreamChunk(userId, generationId, conversationId, chunk);
                    }
                    : null;
            AgentToolRegistry.ToolExecutionResult toolResult =
                    agentToolRegistry.executeTool(toolCall.name(), toolCall.arguments(), userId, toolChunkConsumer);

            // search_knowledge 返回的 SearchResult 列表与模型 prompt 中的 [N] 编号一一对应，
            // 必须把它落到 generationReferenceMappings 里，否则前端点击引用拿不到 MD5/页码。
            String content = toolResult.content();
            if ("search_knowledge".equals(toolCall.name())) {
                content = appendReferencesFromSearchTool(generationId, userMessage, toolResult, toolCall.id());
            }
            if (content == null || content.isBlank()) {
                content = "工具 " + toolCall.name() + " 执行成功，但没有返回可展示内容。";
            }
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");
            return new ExecutedToolResult(content, toolResult.streamedToUser());
        } catch (Exception exception) {
            logger.warn("ReAct Agent Tool 执行失败，作为 tool message 返回模型: name={}, generationId={}",
                    toolCall.name(), generationId, exception);
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
            // generate_summary 已经把部分摘要流给前端，再让模型重写会拼出"半个旧摘要 + 新摘要"。
            // 直接以失败提示收尾，让 ReAct 循环立即 finalize，避免数据不一致。
            if ("generate_summary".equals(toolCall.name()) && summaryStreamStarted.get()) {
                // 中断占位只写用户话术（pm-015）：异常原文已在上方 logger.warn 留档，不进流式内容/生成快照
                appendStreamChunk(userId, generationId, conversationId, "\n\n（摘要生成中断，请稍后重试）");
                return new ExecutedToolResult(
                        "工具 " + toolCall.name() + " 已部分流式输出后失败: " + exception.getMessage(),
                        true
                );
            }
            return new ExecutedToolResult("工具 " + toolCall.name() + " 执行失败: " + exception.getMessage(), false);
        }
    }

    String appendReferencesFromSearchTool(String generationId,
                                          String userMessage,
                                          AgentToolRegistry.ToolExecutionResult toolResult) {
        return appendReferencesFromSearchTool(generationId, userMessage, toolResult, "");
    }

    String appendReferencesFromSearchTool(String generationId,
                                          String userMessage,
                                          AgentToolRegistry.ToolExecutionResult toolResult,
                                          String toolCallId) {
        if (toolResult == null || toolResult.data() == null) {
            return toolResult == null ? null : toolResult.content();
        }
        Object resultsObj = toolResult.data().get("results");
        if (!(resultsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return toolResult.content();
        }
        List<SearchResult> results = rawList.stream().filter(SearchResult.class::isInstance)
                .map(SearchResult.class::cast).toList();
        if (results.isEmpty() || results.stream().noneMatch(result -> result.getFileMd5() != null)) {
            // 没有可编号的命中（无结果或全部缺 fileMd5）：不进入引用编号流程（与改造前一致）
            return toolResult.content();
        }

        Map<Integer, ChatHandler.ReferenceInfo> existingMapping =
                new HashMap<>(generationReferenceMappings.getOrDefault(generationId, Map.of()));
        int firstReferenceNumber = existingMapping.keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(0) + 1;
        SearchOutcome outcome = resolveSearchOutcome(toolResult);

        // 上下文预算（chat.react.max-context-tokens）：工具结果拼入消息前按 token 估算裁剪，
        // 首条命中始终保留，其余按分数从低到高丢弃；丢弃数量累计随 runHeadless 带回（droppedChunks）
        ContextTrim trim = trimSearchResultsToContextBudget(
                generationId, toolCallId, results, outcome, firstReferenceNumber);
        if (trim.dropped() > 0) {
            generationDroppedChunks.merge(generationId, trim.dropped(), Integer::sum);
        }

        // 引用编号按 (fileMd5, chunkId) 去重：重复分块复用首个编号，正文不重复进 prompt
        ReferenceAssignment assignment =
                assignReferenceNumbers(generationId, existingMapping, trim.kept(), firstReferenceNumber, userMessage);
        Map<Integer, ChatHandler.ReferenceInfo> mapping = assignment.mapping();
        generationReferenceMappings.put(generationId, mapping);
        if (chatGenerationStateService != null) {
            chatGenerationStateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(mapping));
        }
        logger.info("ReAct search_knowledge 引用映射已追加: generationId={}, count={}, droppedChunks={}",
                generationId, mapping.size(), trim.dropped());

        // 评测链路读取模型实际检索证据用：引用编号与结果一一对应，生成结束随 runHeadless 返回
        Map<Integer, SearchResult> numbered = generationToolResults.computeIfAbsent(generationId,
                key -> new java.util.LinkedHashMap<>());
        // 父块上下文只挂本次新分配的编号；重复分块的父块已在首个编号下存在
        Map<Integer, String> parentContexts = generationParentContexts.computeIfAbsent(generationId,
                key -> new ConcurrentHashMap<>());
        for (int i = 0; i < trim.kept().size(); i++) {
            SearchResult result = trim.kept().get(i);
            Integer novelNumber = assignment.novelNumberByIndex().get(i);
            if (novelNumber != null) {
                numbered.put(novelNumber, result);
                if (result.getFileMd5() != null && result.getParentContext() != null
                        && !result.getParentContext().isBlank()) {
                    parentContexts.put(novelNumber, result.getParentContext());
                }
            } else {
                numbered.putIfAbsent(assignment.reusedNumberByIndex().get(i), result);
            }
        }

        // 工具消息文本：新分块按分配编号连续格式化；重复分块只附编号复用说明，正文不再重复发送
        String content;
        if (assignment.novelResults().isEmpty()) {
            content = buildReusedChunkNote(assignment.reused());
        } else {
            // task_0005 调用适配：把 search_knowledge 工具数据里的检索分类传给格式化，
            // 引用编号与映射构建逻辑不变；未带 outcome 的旧数据按 HIT 处理，输出与改造前一致
            content = AgentToolRegistry.formatSearchResults(assignment.novelResults(), outcome, firstReferenceNumber);
            if (!assignment.reused().isEmpty()) {
                content = content + "\n\n" + buildReusedChunkNote(assignment.reused());
            }
        }
        return content;
    }

    /** 一次 search_knowledge 结果的上下文预算裁剪结果：保留的检索结果与丢弃数量 */
    private record ContextTrim(List<SearchResult> kept, int dropped) {
    }

    /**
     * search_knowledge 结果按 chat.react.max-context-tokens 预算裁剪：首条命中始终保留，
     * 其余按分数从低到高丢弃（null 分数视为最低）。估算用 LlmProviderRouter.estimateObjectMessagesTokens
     * （与计费同源）；估算不可用（router 缺失、返回 0、抛异常）时退回字符数上限（tokens×4）并记 warn，不静默放行。
     */
    private ContextTrim trimSearchResultsToContextBudget(String generationId,
                                                         String toolCallId,
                                                         List<SearchResult> results,
                                                         SearchOutcome outcome,
                                                         int firstReferenceNumber) {
        if (results.size() <= 1) {
            return new ContextTrim(List.copyOf(results), 0);
        }
        int budgetTokens = resolveMaxContextTokens();
        boolean[] alive = new boolean[results.size()];
        Arrays.fill(alive, true);
        // 候选丢弃顺序：跳过首条（始终保留），其余按分数升序（分数相同按检索顺序，稳定）
        List<Integer> dropOrder = new ArrayList<>();
        for (int i = 1; i < results.size(); i++) {
            dropOrder.add(i);
        }
        dropOrder.sort(Comparator.comparing((Integer index) -> scoreOrZero(results.get(index)))
                .thenComparing(index -> index));
        int dropPtr = 0;
        int dropped = 0;
        boolean charFallback = false;
        while (true) {
            List<SearchResult> current = aliveResults(results, alive);
            String content = AgentToolRegistry.formatSearchResults(current, outcome, firstReferenceNumber);
            int estimated = charFallback ? -1 : estimateToolMessageTokens(generationId, toolCallId, content);
            if (estimated <= 0) {
                charFallback = true;
            }
            boolean overBudget = charFallback
                    ? content.length() > (long) budgetTokens * 4
                    : estimated > budgetTokens;
            if (!overBudget || dropPtr >= dropOrder.size()) {
                if (charFallback) {
                    logger.warn("ReAct 上下文 token 估算不可用，退回字符数上限（tokens×4={} 字符）: generationId={}, contentChars={}, droppedChunks={}",
                            (long) budgetTokens * 4, generationId, content.length(), dropped);
                }
                break;
            }
            alive[dropOrder.get(dropPtr++)] = false;
            dropped++;
        }
        return new ContextTrim(aliveResults(results, alive), dropped);
    }

    /** ReAct 工具结果上下文预算（chat.react.max-context-tokens）：读不到有效值时用默认 6000 */
    private int resolveMaxContextTokens() {
        if (llmProviderRouter == null) {
            return DEFAULT_MAX_CONTEXT_TOKENS;
        }
        try {
            int configured = llmProviderRouter.reactMaxContextTokenBudget();
            return configured > 0 ? configured : DEFAULT_MAX_CONTEXT_TOKENS;
        } catch (Exception exception) {
            logger.warn("读取 chat.react.max-context-tokens 失败，使用默认值 {}: {}",
                    DEFAULT_MAX_CONTEXT_TOKENS, exception.getMessage());
            return DEFAULT_MAX_CONTEXT_TOKENS;
        }
    }

    /**
     * 按 LlmProviderRouter.estimateObjectMessagesTokens 估算 tool message 的 tokens；
     * 估算抛异常时返回 -1（调用方退回字符数上限并已记 warn）
     */
    private int estimateToolMessageTokens(String generationId, String toolCallId, String content) {
        if (llmProviderRouter == null) {
            return -1;
        }
        try {
            return llmProviderRouter.estimateObjectMessagesTokens(List.of(toolMessage(toolCallId, content)));
        } catch (Exception exception) {
            logger.warn("ReAct 上下文 token 估算失败，退回字符数上限: generationId={}, error={}",
                    generationId, exception.getMessage());
            return -1;
        }
    }

    private static double scoreOrZero(SearchResult result) {
        return result.getScore() != null ? result.getScore() : 0.0d;
    }

    private static List<SearchResult> aliveResults(List<SearchResult> results, boolean[] alive) {
        List<SearchResult> current = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (alive[i]) {
                current.add(results.get(i));
            }
        }
        return current;
    }

    /** 引用编号分配结果：mapping 为最终引用映射，novel 为本次拿到新编号的结果（含未编号的缺 md5 项），reused 为复用既有编号的重复分块 */
    private record ReferenceAssignment(Map<Integer, ChatHandler.ReferenceInfo> mapping,
                                       List<SearchResult> novelResults,
                                       List<ReusedReference> reused,
                                       Map<Integer, Integer> novelNumberByIndex,
                                       Map<Integer, Integer> reusedNumberByIndex) {
    }

    private record ReusedReference(int number, SearchResult result) {
    }

    /**
     * 引用编号按 (fileMd5, chunkId) 去重：已在映射中的键复用其最早编号，重复分块正文不重复进 prompt；
     * 新分块从 firstReferenceNumber 起按顺序连续编号（与 formatSearchResults 的位置编号一致）。
     * 缺 fileMd5 的结果沿用旧行为：占据一个位置编号，但不进入引用映射。
     */
    private ReferenceAssignment assignReferenceNumbers(String generationId,
                                                       Map<Integer, ChatHandler.ReferenceInfo> existingMapping,
                                                       List<SearchResult> results,
                                                       int firstReferenceNumber,
                                                       String userMessage) {
        // 既有 (fileMd5, chunkId) → 最早分配的编号：同键出现多个编号时取最小（"复用首个编号"）
        Map<String, Integer> numberByKey = new HashMap<>();
        List<Integer> existingNumbers = new ArrayList<>(existingMapping.keySet());
        existingNumbers.sort(Integer::compareTo);
        for (Integer number : existingNumbers) {
            ChatHandler.ReferenceInfo info = existingMapping.get(number);
            String key = referenceKey(info.fileMd5(), info.chunkId());
            if (key != null) {
                numberByKey.putIfAbsent(key, number);
            }
        }

        Map<Integer, ChatHandler.ReferenceInfo> mapping = new HashMap<>(existingMapping);
        List<SearchResult> novel = new ArrayList<>();
        List<ReusedReference> reused = new ArrayList<>();
        Map<Integer, Integer> novelNumberByIndex = new HashMap<>();
        Map<Integer, Integer> reusedNumberByIndex = new HashMap<>();
        int nextNumber = firstReferenceNumber;
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            String key = referenceKey(result.getFileMd5(), result.getChunkId());
            Integer existing = key != null ? numberByKey.get(key) : null;
            if (existing != null) {
                reused.add(new ReusedReference(existing, result));
                reusedNumberByIndex.put(i, existing);
                continue;
            }
            if (key != null) {
                numberByKey.put(key, nextNumber);
            }
            novel.add(result);
            novelNumberByIndex.put(i, nextNumber);
            if (result.getFileMd5() != null) {
                String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
                mapping.put(nextNumber, buildReferenceInfo(result, fileLabel, userMessage));
            }
            nextNumber++;
        }
        return new ReferenceAssignment(mapping, novel, reused, novelNumberByIndex, reusedNumberByIndex);
    }

    /** 引用去重键：fileMd5#chunkId；缺 fileMd5 的结果无法标识，返回 null 不参与去重 */
    private static String referenceKey(String fileMd5, Integer chunkId) {
        if (fileMd5 == null) {
            return null;
        }
        return fileMd5 + "#" + chunkId;
    }

    /** 重复分块的编号复用说明：正文不重复发送，只告诉模型这些分块对应哪个既有编号 */
    private static String buildReusedChunkNote(List<ReusedReference> reused) {
        StringBuilder note = new StringBuilder("以下 ").append(reused.size())
                .append(" 个分块与此前结果重复，正文不重复提供：");
        for (ReusedReference reusedRef : reused) {
            String fileLabel = reusedRef.result().getFileName() != null
                    && !reusedRef.result().getFileName().isBlank() ? reusedRef.result().getFileName() : "unknown";
            note.append("\n[").append(reusedRef.number()).append("] ").append(fileLabel)
                    .append(" (fileMd5=").append(reusedRef.result().getFileMd5())
                    .append(", chunkId=").append(reusedRef.result().getChunkId())
                    .append(") 复用编号 [").append(reusedRef.number()).append("] 中已提供的内容");
        }
        return note.toString();
    }

    /**
     * 从工具结果数据里读检索分类；缺失或非法时按 HIT 处理（兼容旧数据），保证引用格式化输出不变
     */
    private static SearchOutcome resolveSearchOutcome(AgentToolRegistry.ToolExecutionResult toolResult) {
        Object outcome = toolResult.data().get("outcome");
        if (outcome instanceof String name && !name.isBlank()) {
            try {
                return SearchOutcome.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return SearchOutcome.HIT;
            }
        }
        return SearchOutcome.HIT;
    }

    private record ExecutedToolResult(String content, boolean streamedToUser) {
    }

    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private LlmProviderRouter.ReActTurn streamReActTurnBlocking(String requesterId,
                                                                String conversationId,
                                                                String generationId,
                                                                List<Map<String, Object>> messages,
                                                                List<AgentToolRegistry.AgentTool> tools) {
        CompletableFuture<LlmProviderRouter.ReActTurn> turnFuture = new CompletableFuture<>();
        LlmProviderRouter.StreamHandle streamHandle = llmProviderRouter.streamReActTurn(
                requesterId,
                messages,
                tools,
                REACT_MAX_COMPLETION_TOKENS,
                chunk -> appendStreamChunk(requesterId, generationId, conversationId, chunk),
                turnFuture::completeExceptionally,
                turnFuture::complete
        );
        activeStreams.put(generationId, streamHandle);

        // 单轮上限和请求剩余时间取较小值
        long turnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENERATION_COMPLETION_TIMEOUT_SECONDS);
        long deadline = RequestDeadline.currentNanos().map(requestDeadline -> Math.min(requestDeadline, turnDeadline))
                .orElse(turnDeadline);
        try {
            while (true) {
                if (isGenerationCancelled(generationId)) {
                    streamHandle.cancel();
                    return null;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    streamHandle.cancel();
                    // cause 带 TimeoutException，失败归因据此落入 TIMEOUT 类别
                    throw new RuntimeException(failureUserMessage(ChatFailureCategory.TIMEOUT), new TimeoutException("模型回合等待超时"));
                }
                try {
                    long waitMillis = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 200L);
                    return turnFuture.get(Math.max(waitMillis, 1L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 短轮询用于及时响应用户停止生成。
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            streamHandle.cancel();
            throw new RuntimeException("模型响应被中断", exception);
        } catch (ExecutionException exception) {
            streamHandle.cancel();
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("ReAct 流式模型回合调用失败", cause);
        } finally {
            activeStreams.remove(generationId, streamHandle);
        }
    }

    private void appendStreamChunk(String userId, String generationId, String conversationId, String chunk) {
        if (chunk == null || chunk.isEmpty() || isGenerationCancelled(generationId)) {
            return;
        }
        StringBuilder responseBuilder = responseBuilders.get(generationId);
        if (responseBuilder != null) {
            responseBuilder.append(chunk);
        }
        if (chatGenerationStateService != null) {
            chatGenerationStateService.appendChunk(generationId, chunk);
        }
        sendResponseChunk(userId, generationId, conversationId, chunk);
    }

    private void finalizeResponse(String userId, String userMessage, String conversationId, String generationId,
                                  CompletableFuture<String> responseFuture,
                                  StringBuilder responseBuilder,
                                  LlmProviderRouter.StreamCompletion completion,
                                  ReactRunStats stats, boolean headless) {
        if (finishCancelledGeneration(generationId, responseFuture, responseBuilder)) {
            return;
        }
        if (responseBuilder == null) {
            if (responseFuture.isDone() || !responseFutures.containsKey(generationId)) {
                return;
            }
            RuntimeException exception = new RuntimeException("响应构建器为空");
            if (responseFuture.completeExceptionally(exception)) {
                if (!headless) {
                    handleError(userId, generationId, ChatFailureCategory.OTHER, exception);
                    sendCompletionNotification(userId, generationId, conversationId, true, false);
                    chatGenerationStateService.markFailed(generationId,
                            ChatFailureCategory.OTHER.name(), failureUserMessage(ChatFailureCategory.OTHER));
                    persistFailedConversation(userId, userMessage, conversationId, ChatFailureCategory.OTHER);
                }
                cleanupGenerationState(generationId, exception);
            }
            return;
        }
        String completeResponse = responseBuilder.toString();
        if (completeResponse.isBlank()) {
            RuntimeException exception = new RuntimeException("模型未返回有效内容");
            if (responseFuture.completeExceptionally(exception)) {
                if (!headless) {
                    handleError(userId, generationId, ChatFailureCategory.PROVIDER_UNAVAILABLE, exception);
                    sendCompletionNotification(userId, generationId, conversationId, true, false);
                    chatGenerationStateService.markFailed(generationId,
                            ChatFailureCategory.PROVIDER_UNAVAILABLE.name(),
                            failureUserMessage(ChatFailureCategory.PROVIDER_UNAVAILABLE));
                    persistFailedConversation(userId, userMessage, conversationId, ChatFailureCategory.PROVIDER_UNAVAILABLE);
                }
                cleanupGenerationState(generationId, exception);
            }
            return;
        }
        if (!responseFuture.complete(completeResponse)) {
            return;
        }
        logger.info("模型回答收尾: generationId={}, conversationId={}, answerChars={}, finishReason={}, promptTokens={}, completionTokens={}",
                generationId,
                conversationId,
                completeResponse.length(),
                completion != null && completion.finishReason() != null ? completion.finishReason() : "unknown",
                completion != null ? completion.promptTokens() : 0,
                completion != null ? completion.completionTokens() : 0);
        Map<Integer, ChatHandler.ReferenceInfo> referenceMappings = verifyCitations(
                userId, generationId, completeResponse, generationReferenceMappings.get(generationId));
        String citationVerificationStatus = generationCitationStatuses.get(generationId);
        if (headless) {
            // 评测链路：不落库、不写生成快照、不推完成帧；引用装配结果与校验状态由 runHeadless 读取后返回调用方
            return;
        }
        // 先把消息事务性地落 MySQL；只有 MySQL 成功后才写 Redis 短期会话历史，
        // 否则两个数据源会出现一边有记录、一边没有的不一致状态。
        boolean persisted = persistConversation(userId, userMessage, completeResponse, conversationId, referenceMappings);
        if (persisted) {
            updateConversationHistory(conversationId, userMessage, completeResponse, referenceMappings, citationVerificationStatus);
        } else {
            logger.warn("MySQL 落库失败，跳过 Redis 会话历史写入以保持两端一致: generationId={}, conversationId={}",
                    generationId, conversationId);
        }
        chatGenerationStateService.markCompleted(generationId, toSerializableReferenceMappings(referenceMappings));
        sendCompletionNotification(userId, generationId, conversationId, false, !persisted);
        logger.info("对话存储信息 - Redis键: {}, 值: {}", "user:" + userId + ":current_conversation", conversationId);
        cleanupGenerationState(generationId, null);
        logger.info("消息处理完成，用户ID: {}", userId);
    }

    /**
     * 引用事后校验（ai.citation-verification.enabled）：不被片段支撑的引用标记为 SUSPICIOUS，前端降低展示优先级。
     * 整体状态（PASSED/TRUNCATED/UNVERIFIED/NO_CITATIONS）记入 generationCitationStatuses，
     * 由收尾路径写入回答元数据与完成帧；校验失败/超时降级 UNVERIFIED，不阻断回答。
     */
    private Map<Integer, ChatHandler.ReferenceInfo> verifyCitations(String userId, String generationId, String answer,
                                                        Map<Integer, ChatHandler.ReferenceInfo> referenceMappings) {
        if (referenceMappings == null || referenceMappings.isEmpty() || !citationVerificationEnabled
                || citationVerificationService == null || !citationVerificationService.isEnabled()) {
            return referenceMappings;
        }
        Map<Integer, String> referenceTexts = new HashMap<>();
        Map<Integer, String> parentContexts = generationParentContexts.getOrDefault(generationId, Map.of());
        referenceMappings.forEach((number, detail) ->
                referenceTexts.put(number, parentContexts.getOrDefault(number, detail.matchedChunkText())));
        CitationVerificationService.VerificationOutcome outcome =
                citationVerificationService.verifyDetailed(userId, answer, referenceTexts);
        generationCitationStatuses.put(generationId, outcome.status().name());
        logger.info("引用校验状态: generationId={}, status={}, verdicts={}, detail={}",
                generationId, outcome.status(), outcome.verdicts().size(), outcome.detail());
        Map<Integer, CitationVerdict> verdicts = outcome.verdicts();
        if (verdicts.isEmpty()) {
            return referenceMappings;
        }
        Map<Integer, ChatHandler.ReferenceInfo> verified = new HashMap<>();
        referenceMappings.forEach((number, detail) -> {
            CitationVerdict verdict = verdicts.get(number);
            verified.put(number, verdict == null ? detail : detail.withCitation(verdict.status().name(), verdict.reason()));
        });
        generationReferenceMappings.put(generationId, verified);
        return verified;
    }

    private boolean persistConversation(String userId, String userMessage, String completeResponse, String conversationId,
                                        Map<Integer, ChatHandler.ReferenceInfo> referenceMappings) {
        try {
            Long userIdLong = Long.parseLong(userId);
            conversationService.recordConversation(
                    userIdLong,
                    userMessage,
                    completeResponse,
                    conversationId,
                    toSerializableReferenceMappings(referenceMappings)
            );
            return true;
        } catch (Exception e) {
            logger.error("持久化对话历史失败: userId={}, conversationId={}", userId, conversationId, e);
            return false;
        }
    }

    private void cleanupGenerationState(String generationId, Throwable throwable) {
        if (chatSessionRegistry != null) {
            chatSessionRegistry.releaseGeneration(generationId);
        }
        responseBuilders.remove(generationId);
        generationReferenceMappings.remove(generationId);
        generationParentContexts.remove(generationId);
        generationToolResults.remove(generationId);
        generationDroppedChunks.remove(generationId);
        generationCitationStatuses.remove(generationId);
        stopFlags.remove(generationId);
        activeStreams.remove(generationId);
        cancelledGenerations.remove(generationId);
        CompletableFuture<String> future = responseFutures.remove(generationId);
        if (throwable != null && future != null && !future.isDone()) {
            future.completeExceptionally(throwable);
        }
    }

    private boolean finishCancelledGeneration(String generationId,
                                              CompletableFuture<String> responseFuture,
                                              StringBuilder responseBuilder) {
        if (!isGenerationCancelled(generationId)) {
            return false;
        }

        if (!responseFuture.isDone()) {
            responseFuture.complete(responseBuilder != null ? responseBuilder.toString() : "");
        }
        cleanupGenerationState(generationId, null);
        logger.info("生成任务已取消并停止后续收尾逻辑: generationId={}", generationId);
        return true;
    }

    private boolean isGenerationCancelled(String generationId) {
        return generationId != null && (cancelledGenerations.contains(generationId) || Boolean.TRUE.equals(stopFlags.get(generationId)));
    }

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
            logger.info("为用户 {} 创建新的会话ID: {}", userId, conversationId);
        } else {
            logger.info("获取到用户 {} 的现有会话ID: {}", userId, conversationId);
        }

        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        List<Map<String, Object>> records = getConversationHistoryRecords(conversationId);
        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> message : records) {
            Map<String, String> normalized = new HashMap<>();
            normalized.put("role", String.valueOf(message.getOrDefault("role", "")));
            normalized.put("content", String.valueOf(message.getOrDefault("content", "")));
            Object timestamp = message.get("timestamp");
            if (timestamp != null) {
                normalized.put("timestamp", String.valueOf(timestamp));
            }
            history.add(normalized);
        }
        return history;
    }

    private String buildRecentFeedbackGuidance(String userId) {
        if (redisTemplate == null || userId == null || userId.isBlank()) {
            return "";
        }
        try {
            Map<Object, Object> feedbackEntries = redisTemplate.opsForHash().entries("feedback:" + userId);
            if (feedbackEntries == null || feedbackEntries.isEmpty()) {
                return "";
            }

            StringBuilder guidance = new StringBuilder("近期用户对回答的显式反馈如下，按时间倒序排列，越靠前越新。")
                    .append("后续回答需要参考这些偏好：good 表示用户认可这类回答方式，bad 表示需要避免类似问题；")
                    .append("如果同一轮回答既有 good 又有 bad，以最新一条为准。\n");
            feedbackEntries.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> -parseFeedbackTimestamp(entry.getKey())))
                    .limit(5)
                    .forEach(entry -> guidance.append("- ")
                            .append("feedbackTime=")
                            .append(entry.getKey())
                            .append("; ")
                            .append(entry.getValue())
                            .append("\n"));
            return guidance.toString().trim();
        } catch (Exception exception) {
            logger.warn("读取用户反馈上下文失败: userId={}", userId, exception);
            return "";
        }
    }

    private long parseFeedbackTimestamp(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private List<Map<String, Object>> getConversationHistoryRecords(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (json == null) {
                logger.debug("会话 {} 没有历史记录", conversationId);
                return new ArrayList<>();
            }

            List<Map<String, Object>> history = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            logger.debug("读取到会话 {} 的 {} 条历史记录", conversationId, history.size());
            return history;
        } catch (JsonProcessingException e) {
            logger.error("解析对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response,
                                           Map<Integer, ChatHandler.ReferenceInfo> referenceMapping,
                                           String citationVerificationStatus) {
        String key = "conversation:" + conversationId;
        List<Map<String, Object>> history = getConversationHistoryRecords(conversationId);

        // 获取当前时间戳
        String currentTimestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // 添加用户消息（带时间戳）
        Map<String, Object> userMsgMap = new HashMap<>();
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        userMsgMap.put("timestamp", currentTimestamp);
        history.add(userMsgMap);

        // 添加助手回复（带时间戳）
        Map<String, Object> assistantMsgMap = new HashMap<>();
        assistantMsgMap.put("role", "assistant");
        assistantMsgMap.put("content", response);
        assistantMsgMap.put("timestamp", currentTimestamp);
        if (citationVerificationStatus != null) {
            // 引用校验整体状态（PASSED/TRUNCATED/UNVERIFIED/NO_CITATIONS）写入回答元数据
            assistantMsgMap.put("citationVerificationStatus", citationVerificationStatus);
        }
        if (referenceMapping != null && !referenceMapping.isEmpty()) {
            assistantMsgMap.put("referenceMappings", toSerializableReferenceMappings(referenceMapping));
        }
        history.add(assistantMsgMap);

        // 限制历史记录长度，保留最近的20条消息
        if (history.size() > 20) {
            history = history.subList(history.size() - 20, history.size());
        }

        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
            logger.debug("更新会话历史，会话ID: {}, 总消息数: {}", conversationId, history.size());
        } catch (JsonProcessingException e) {
            logger.error("序列化对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
        }
    }

    private Map<String, Map<String, Object>> toSerializableReferenceMappings(Map<Integer, ChatHandler.ReferenceInfo> referenceMapping) {
        Map<String, Map<String, Object>> serialized = new HashMap<>();
        if (referenceMapping == null || referenceMapping.isEmpty()) {
            return serialized;
        }
        for (Map.Entry<Integer, ChatHandler.ReferenceInfo> entry : referenceMapping.entrySet()) {
            ChatHandler.ReferenceInfo detail = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", detail.fileMd5());
            item.put("fileName", detail.fileName());
            item.put("pageNumber", detail.pageNumber());
            item.put("anchorText", detail.anchorText());
            item.put("retrievalMode", detail.retrievalMode());
            item.put("retrievalLabel", detail.retrievalLabel());
            item.put("retrievalQuery", detail.retrievalQuery());
            item.put("matchedChunkText", detail.matchedChunkText());
            item.put("evidenceSnippet", detail.evidenceSnippet());
            item.put("score", detail.score());
            item.put("chunkId", detail.chunkId());
            item.put("citationStatus", detail.citationStatus());
            item.put("citationReason", detail.citationReason());
            serialized.put(String.valueOf(entry.getKey()), item);
        }
        return serialized;
    }

    private String buildContext(List<SearchResult> searchResults, String generationId, String userMessage) {
        if (searchResults == null || searchResults.isEmpty()) {
            // 返回空字符串，让 LLM provider 按"无检索结果"逻辑处理
            return "";
        }

        // 创建当前生成任务的引用映射
        Map<Integer, ChatHandler.ReferenceInfo> referenceMapping = new HashMap<>();

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            if (snippet.length() > MAX_CONTEXT_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_CONTEXT_SNIPPET_LEN) + "…";
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            String fileMd5 = result.getFileMd5();

            // 格式：[1] (test1.txt | 第5页) 文件内容... 或 [1] (test1.txt) 文件内容...
            // 有页码时显示页码，方便AI引用
            Integer pageNum = result.getPageNumber();
            if (pageNum != null && pageNum > 0) {
                context.append(String.format("[%d] (%s | 第%d页) %s\n", i + 1, fileLabel, pageNum, snippet));
            } else {
                context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
            }

            // 保存引用编号到MD5的映射
            if (fileMd5 != null) {
                ChatHandler.ReferenceInfo detail = buildReferenceInfo(result, fileLabel, userMessage);
                referenceMapping.put(i + 1, detail);
                // 详细日志：记录每个引用编号的映射关系
                logger.info("引用映射: generationId={}, 引用编号#{}={}, 文件名={}, MD5={}, page={}, retrievalMode={}, chunkId={}",
                    generationId, i + 1, fileLabel, fileMd5, result.getPageNumber(), detail.retrievalMode(), detail.chunkId());
            }
        }

        // 保存当前生成任务的引用映射
        generationReferenceMappings.put(generationId, referenceMapping);
        if (chatGenerationStateService != null) {
            chatGenerationStateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(referenceMapping));
        }
        logger.info("保存生成任务 {} 的引用映射，共 {} 条: {}", generationId, referenceMapping.size(), referenceMapping);

        return context.toString();
    }

    private void sendGenerationStart(String userId, String generationId, String conversationId) {
        if (chatSessionRegistry == null) {
            return;
        }
        chatSessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "start",
                "generationId", generationId,
                "conversationId", conversationId,
                "timestamp", System.currentTimeMillis()
        ));
    }

    private void sendResponseChunk(String userId, String generationId, String conversationId, String chunk) {
        if (Boolean.TRUE.equals(stopFlags.get(generationId))) {
            logger.debug("检测到停止标志，跳过发送响应块: generationId={}", generationId);
            return;
        }

        if (chatSessionRegistry == null) {
            return;
        }
        chatSessionRegistry.sendJsonToUser(userId, Map.of(
                "type", "chunk",
                "generationId", generationId,
                "conversationId", conversationId,
                "chunk", chunk
        ));
    }

    private void sendToolCallStatus(String userId,
                                    String generationId,
                                    String conversationId,
                                    LlmProviderRouter.ToolCallDecision toolCall,
                                    String status) {
        if (chatSessionRegistry == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "tool_call");
        payload.put("tool", toolCall.name());
        payload.put("toolCallId", toolCall.id());
        payload.put("status", status);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("timestamp", System.currentTimeMillis());
        chatSessionRegistry.sendJsonToUser(userId, payload);
    }

    private void sendCompletionNotification(String userId,
                                            String generationId,
                                            String conversationId,
                                            boolean failed,
                                            boolean persistenceDegraded) {
        if (chatSessionRegistry == null) {
            return;
        }
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "completion");
        notification.put("generationId", generationId);
        notification.put("conversationId", conversationId);
        notification.put("status", failed ? "failed" : "finished");
        notification.put("message", failed ? "响应已中断" : "响应已完成");
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("date", java.time.LocalDateTime.now().toString());
        if (!failed) {
            String citationVerificationStatus = generationCitationStatuses.get(generationId);
            if (citationVerificationStatus != null) {
                notification.put("citationVerificationStatus", citationVerificationStatus);
            }
            Map<Integer, ChatHandler.ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
            if (referenceMappings != null && !referenceMappings.isEmpty()) {
                notification.put("referenceMappings", toSerializableReferenceMappings(referenceMappings));
            }
        }
        if (persistenceDegraded) {
            notification.put("persistenceDegraded", true);
            notification.put("persistenceWarning", "本次回复未能持久化到数据库，刷新后可能无法在历史中找到。");
        }
        chatSessionRegistry.sendJsonToUser(userId, notification);
    }

    /**
     * 聊天失败的用户可见类别（pm-001）：错误帧（errorCategory）与生成快照共用同一套类别码，
     * 用户话术统一取自 {@link #failureUserMessage}；原始异常文本只留在服务端日志。
     */
    private enum ChatFailureCategory {
        /** 模型供应商不可用：HTTP 失败（如密钥错误）、熔断打开、模型空响应 */
        PROVIDER_UNAVAILABLE,
        /** 超时降级：模型回合等待超时 */
        TIMEOUT,
        /** 系统繁忙：线程池满、供应商舱壁满 */
        BUSY,
        /** 发送受限：聊天限流（已有 code 429 + retryAfterSeconds 倒计时） */
        RATE_LIMIT,
        /** 其他未归类失败 */
        OTHER
    }

    /** 「繁忙/不可用」类用户话术的唯一来源：error 帧、失败快照、失败回合落库都取这里 */
    private static String failureUserMessage(ChatFailureCategory category) {
        return switch (category) {
            case PROVIDER_UNAVAILABLE -> "模型服务暂时不可用，请稍后重试";
            case TIMEOUT -> "回答生成超时，请稍后重试";
            case BUSY -> "系统繁忙，请稍后重试";
            case RATE_LIMIT -> "请求过于频繁，请稍后重试";
            case OTHER -> "AI服务暂时不可用，请稍后重试";
        };
    }

    /**
     * 通用异常的类别归因：超时 → TIMEOUT；模型 HTTP 失败/熔断 → PROVIDER_UNAVAILABLE；
     * 供应商舱壁满 → BUSY；其余归 OTHER
     */
    private static ChatFailureCategory resolveFailureCategory(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof TimeoutException) {
                return ChatFailureCategory.TIMEOUT;
            }
            if (current instanceof WebClientResponseException || current instanceof CallNotPermittedException) {
                return ChatFailureCategory.PROVIDER_UNAVAILABLE;
            }
            if (current instanceof BulkheadFullException) {
                return ChatFailureCategory.BUSY;
            }
            current = current.getCause();
            depth++;
        }
        return ChatFailureCategory.OTHER;
    }

    /** error 帧在既有字段（type/error/generationId）之上只新增 errorCategory，向后兼容 */
    private Map<String, Object> buildErrorFrame(String generationId, ChatFailureCategory category) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("type", "error");
        frame.put("error", failureUserMessage(category));
        frame.put("errorCategory", category.name());
        if (generationId != null) {
            frame.put("generationId", generationId);
        }
        return frame;
    }

    private void handleError(String userId, String generationId, ChatFailureCategory category, Throwable error) {
        logger.error("AI服务错误[{}]: {}", category, error.getMessage(), error);
        if (chatSessionRegistry == null) {
            return;
        }
        chatSessionRegistry.sendJsonToUser(userId, buildErrorFrame(generationId, category));
    }

    /**
     * 失败回合落库（pm-001）：把用户提问原文与失败态回复写入 MySQL，
     * 刷新后该回合仍可见。失败态用现有字段承载：answer 存「[回答失败|类别]统一话术」，
     * 读取端（ConversationService.toMessageHistory）据此还原 status/errorCategory，不改表结构。
     */
    private void persistFailedConversation(String userId, String userMessage, String conversationId,
                                           ChatFailureCategory category) {
        if (userMessage == null || userMessage.isBlank() || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            conversationService.recordFailedConversation(
                    Long.parseLong(userId), userMessage, conversationId,
                    category.name(), failureUserMessage(category));
            logger.info("失败回合已落库: userId={}, conversationId={}, category={}", userId, conversationId, category);
        } catch (Exception e) {
            logger.error("持久化失败回合出错: userId={}, conversationId={}, category={}", userId, conversationId, category, e);
        }
    }

    /**
     * 停止响应：设置取消标志、取消上游流，并把 stop 帧推给用户
     */
    public void stopResponse(String userId, String generationId) {
        String resolvedGenerationId = generationId;
        if (resolvedGenerationId == null || resolvedGenerationId.isBlank()) {
            resolvedGenerationId = chatGenerationStateService.getActiveGenerationForUser(userId)
                    .map(ChatGenerationStateService.GenerationSnapshot::generationId)
                    .orElse(null);
        }

        if (resolvedGenerationId == null || resolvedGenerationId.isBlank()) {
            logger.warn("收到停止请求但未找到活动生成任务，用户ID: {}", userId);
            return;
        }
        final String targetGenerationId = resolvedGenerationId;

        logger.info("收到停止请求，用户ID: {}，generationId: {}", userId, targetGenerationId);

        // 设置停止标志
        cancelledGenerations.add(targetGenerationId);
        stopFlags.put(targetGenerationId, true);
        chatGenerationStateService.markCancelled(targetGenerationId);
        LlmProviderRouter.StreamHandle streamHandle = activeStreams.get(targetGenerationId);
        if (streamHandle != null) {
            streamHandle.cancel();
        }
        CompletableFuture<String> responseFuture = responseFutures.get(targetGenerationId);
        if (responseFuture != null && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(new CancellationException("响应已停止"));
        }

        if (chatSessionRegistry != null) {
            chatSessionRegistry.sendJsonToUser(userId, Map.of(
                    "type", "stop",
                    "generationId", targetGenerationId,
                    "message", "响应已停止",
                    "timestamp", System.currentTimeMillis(),
                    "date", java.time.Instant.now().toString()
            ));
        }

        logger.info("已停止上游流式生成: generationId={}", targetGenerationId);
    }

    /**
     * 根据引用编号获取文件MD5
     *
     * @param generationId    生成任务ID
     * @param referenceNumber 引用编号
     * @return 文件MD5，如果找不到则返回null
     */
    public String getReferenceMd5(String generationId, int referenceNumber) {
        ChatHandler.ReferenceInfo detail = getReferenceDetail(generationId, referenceNumber);
        return detail != null ? detail.fileMd5() : null;
    }

    public ChatHandler.ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        logger.info("查询引用MD5: generationId={}, referenceNumber=#{}", generationId, referenceNumber);

        Map<Integer, ChatHandler.ReferenceInfo> referenceMapping = generationReferenceMappings.get(generationId);
        if (referenceMapping == null && chatGenerationStateService != null) {
            referenceMapping = chatGenerationStateService.getGeneration(generationId)
                    .map(ChatGenerationStateService.GenerationSnapshot::referenceMappings)
                    .filter(mappings -> !mappings.isEmpty())
                    .map(this::toReferenceInfoMap)
                    .orElse(null);
        }
        if (referenceMapping == null) {
            logger.error("未找到生成任务 {} 的引用映射，当前所有生成任务: {}", generationId, generationReferenceMappings.keySet());
            return null;
        }

        logger.info("生成任务 {} 的引用映射内容: {}", generationId, referenceMapping);

        ChatHandler.ReferenceInfo detail = referenceMapping.get(referenceNumber);
        if (detail == null) {
            logger.error("生成任务 {} 中未找到引用编号 #{}, 可用的引用编号: {}", generationId, referenceNumber, referenceMapping.keySet());
            return null;
        }

        logger.info("成功找到引用映射: generationId={}, referenceNumber=#{}, fileMd5={}, pageNumber={}",
                generationId, referenceNumber, detail.fileMd5(), detail.pageNumber());
        return detail;
    }

    private Map<Integer, ChatHandler.ReferenceInfo> toReferenceInfoMap(Map<String, Map<String, Object>> serializedMappings) {
        Map<Integer, ChatHandler.ReferenceInfo> referenceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serializedMappings.entrySet()) {
            Map<String, Object> item = entry.getValue();
            referenceMap.put(Integer.parseInt(entry.getKey()), new ChatHandler.ReferenceInfo(
                    (String) item.get("fileMd5"),
                    (String) item.get("fileName"),
                    item.get("pageNumber") instanceof Number number ? number.intValue() : null,
                    (String) item.get("anchorText"),
                    (String) item.get("retrievalMode"),
                    (String) item.get("retrievalLabel"),
                    (String) item.get("retrievalQuery"),
                    (String) item.get("matchedChunkText"),
                    (String) item.get("evidenceSnippet"),
                    item.get("score") instanceof Number number ? number.doubleValue() : null,
                    item.get("chunkId") instanceof Number number ? number.intValue() : null,
                    (String) item.get("citationStatus"),
                    (String) item.get("citationReason")
            ));
        }
        return referenceMap;
    }

    private ChatHandler.ReferenceInfo buildReferenceInfo(SearchResult result, String fileLabel, String userMessage) {
        String matchedChunkText = trimToMaxLength(
                result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(),
                MAX_MATCHED_CHUNK_LEN
        );
        String evidenceSnippet = buildEvidenceSnippet(userMessage, result.getAnchorText(), matchedChunkText);

        return new ChatHandler.ReferenceInfo(
                result.getFileMd5(),
                fileLabel,
                result.getPageNumber(),
                result.getAnchorText(),
                result.getRetrievalMode(),
                buildRetrievalLabel(result.getRetrievalMode()),
                normalizeEvidenceText(userMessage),
                matchedChunkText,
                evidenceSnippet,
                result.getScore(),
                result.getChunkId(),
                null,
                null
        );
    }

    private String buildRetrievalLabel(String retrievalMode) {
        if ("TEXT_ONLY".equalsIgnoreCase(retrievalMode)) {
            return "关键词召回";
        }
        if ("HYBRID_RRF".equalsIgnoreCase(retrievalMode)) {
            return "混合召回（RRF 融合排序，未经 rerank 精排）";
        }
        return "混合召回（语义相关 + 关键词命中）";
    }

    private String buildEvidenceSnippet(String userMessage, String anchorText, String matchedChunkText) {
        String normalizedAnchorText = normalizeEvidenceText(anchorText);
        if (!normalizedAnchorText.isBlank()) {
            return trimToMaxLength(normalizedAnchorText, MAX_EVIDENCE_SNIPPET_LEN);
        }

        String normalizedMatchedChunk = normalizeEvidenceText(matchedChunkText);
        if (normalizedMatchedChunk.isBlank()) {
            String normalizedUserMessage = normalizeEvidenceText(userMessage);
            if (!normalizedUserMessage.isBlank()) {
                return trimToMaxLength(normalizedUserMessage, MAX_EVIDENCE_SNIPPET_LEN);
            }
            return "";
        }

        String[] sentences = normalizedMatchedChunk.split("(?<=[。！？!?；;])");
        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.length() >= 12) {
                return trimToMaxLength(trimmedSentence, MAX_EVIDENCE_SNIPPET_LEN);
            }
        }

        return trimToMaxLength(normalizedMatchedChunk, MAX_EVIDENCE_SNIPPET_LEN);
    }

    private String trimToMaxLength(String value, int maxLength) {
        String normalized = normalizeEvidenceText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    private String normalizeEvidenceText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
