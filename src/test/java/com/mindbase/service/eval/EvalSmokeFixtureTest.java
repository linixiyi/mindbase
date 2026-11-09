package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.ChatDeadlineProperties;
import com.mindbase.config.ContextualRetrievalProperties;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.config.ParentContextProperties;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.repository.DocumentVectorRepository;
import com.mindbase.service.AgentToolRegistry;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import com.mindbase.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测冒烟门禁的 fixture 执行器：内置 20 题合成语料，以 react 模式（生产 ChatPipeline ReAct 链路）
 * 无网络、无 ES 跑完整条评测链路，用真实的 RagEvalService/RagEvalCaseRunner/EvalReportAggregator
 * 落一份真实 v2 报告到 {@code target/eval-smoke/}，供 {@code scripts/eval_gate.sh} 做阈值门禁。
 * <p>
 * fixture 的每一条检索命中、评测得分都是构造出来的确定性行为，指标断言是精确值而不是范围值；
 * gate 脚本的阈值只作为 JSON 侧的复核（脚本层与测试层各自独立断言同一份报告）。
 */
class EvalSmokeFixtureTest {

    private static final Path SMOKE_DIR = Path.of("target", "eval-smoke");

    /** 内置 20 题 fixture 数据集：全字段 v2 结构（split/type/difficulty/expectedRefusal 齐全，非 legacy） */
    private static final String FIXTURE_JSONL = """
            # 评测冒烟 fixture：20 题合成语料（无真实文档、无敏感信息），由 EvalSmokeFixtureTest 以 react 模式无网络执行。
            # 每题的检索行为与评委结论在测试里按 id 确定性构造：1-18 命中（multi-02 第 2 位命中，multi-04/multi-05 半覆盖），
            # neg-01 检索未命中且答错，fact-06 检索调用失败计入 retrievalErrorRate。
            {"id": "fact-01-mindbase-role", "question": "MindBase 是什么？", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["企业级 AI 知识管理系统"], "referenceAnswer": "MindBase 是一个企业级 AI 知识管理系统，围绕 RAG 工作流构建。"}
            {"id": "fact-02-mindbase-storage", "question": "MindBase 用什么数据库做主存储？", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["MySQL"], "referenceAnswer": "MindBase 的主持久化使用 MySQL。"}
            {"id": "fact-03-mindbase-cache", "question": "MindBase 的 Redis 用来做什么？", "split": "dev", "type": "fact", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["缓存"], "referenceAnswer": "Redis 用于缓存、会话和短期聊天上下文。"}
            {"id": "fact-04-rustfs-api", "question": "RustFS 支持什么接口？", "split": "dev", "type": "fact", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["rustfs-notes.txt"], "expectedEvidence": ["S3 兼容接口"], "referenceAnswer": "RustFS 提供 S3 兼容接口。"}
            {"id": "fact-05-rustfs-erasure", "question": "RustFS 靠什么保证数据可靠？", "split": "dev", "type": "fact", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["rustfs-notes.txt"], "expectedEvidence": ["纠删码"], "referenceAnswer": "RustFS 使用纠删码保证数据可靠性。"}
            {"id": "para-01-mindbase-rag", "question": "MindBase 的 RAG 流程有哪些环节？", "split": "dev", "type": "para", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["检索"], "referenceAnswer": "包括检索、重排序与生成等环节。"}
            {"id": "para-02-mindbase-search", "question": "为什么 MindBase 默认用混合检索？", "split": "dev", "type": "para", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["混合检索"], "referenceAnswer": "混合检索兼顾关键词精确匹配与语义相似度。"}
            {"id": "para-03-resume-focus", "question": "候选人的主要技术方向是什么？", "split": "dev", "type": "para", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["resume-sample.pdf"], "expectedEvidence": ["AI 应用开发"], "referenceAnswer": "主要技术方向是 AI 应用开发。"}
            {"id": "multi-01-mindbase-stack", "question": "MindBase 的后端基于什么框架？", "split": "dev", "type": "multi", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["Spring Boot"], "referenceAnswer": "后端基于 Spring Boot，前端使用 Vue 3。"}
            {"id": "multi-02-mindbase-mq", "question": "MindBase 用什么做异步文件处理？", "split": "dev", "type": "multi", "difficulty": 3, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["Kafka"], "referenceAnswer": "使用 Kafka 做异步文件处理。"}
            {"id": "multi-03-resume-projects", "question": "简历里有哪几类项目经历？", "split": "dev", "type": "multi", "difficulty": 3, "expectedRefusal": false, "expectedFileNames": ["resume-sample.pdf"], "expectedEvidence": ["RAG"], "referenceAnswer": "包括 RAG 知识库与推荐系统两类项目。"}
            {"id": "conflict-01-mindbase-port", "question": "MindBase 后端服务运行在哪个端口？", "split": "dev", "type": "conflict", "difficulty": 3, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["8081"], "referenceAnswer": "后端服务运行在 8081 端口。"}
            {"id": "rustfs-01-put", "question": "怎么向 RustFS 上传对象？", "split": "dev", "type": "rustfs", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["rustfs-notes.txt"], "expectedEvidence": ["PUT"], "referenceAnswer": "使用 S3 兼容的 PUT 接口上传对象。"}
            {"id": "rustfs-02-bucket", "question": "RustFS 的桶是什么？", "split": "dev", "type": "rustfs", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["rustfs-notes.txt"], "expectedEvidence": ["桶"], "referenceAnswer": "桶是 RustFS 中存放对象的顶层容器。"}
            {"id": "resume-01-skills", "question": "候选人掌握哪些编程语言？", "split": "dev", "type": "resume", "difficulty": 2, "expectedRefusal": false, "expectedFileNames": ["resume-sample.pdf"], "expectedEvidence": ["Java"], "referenceAnswer": "掌握 Java 与 TypeScript。"}
            {"id": "resume-02-education", "question": "候选人的学历是什么？", "split": "dev", "type": "resume", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["resume-sample.pdf"], "expectedEvidence": ["本科"], "referenceAnswer": "本科学历，计算机相关专业。"}
            {"id": "multi-04-partial-storage", "question": "MindBase 的存储和检索分别依赖什么？", "split": "dev", "type": "multi", "difficulty": 3, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["MySQL", "Elasticsearch"], "referenceAnswer": "主存储用 MySQL，检索依赖 Elasticsearch。"}
            {"id": "multi-05-partial-deploy", "question": "RustFS 的部署形态和接口是什么？", "split": "dev", "type": "multi", "difficulty": 3, "expectedRefusal": false, "expectedFileNames": ["rustfs-notes.txt"], "expectedEvidence": ["单二进制", "S3 兼容接口"], "referenceAnswer": "RustFS 以单二进制部署并提供 S3 兼容接口。"}
            {"id": "neg-01-quantum", "question": "MindBase 在量子计算上的应用方案是什么？", "split": "dev", "type": "neg", "difficulty": 2, "expectedRefusal": true, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["量子计算"], "referenceAnswer": "知识库没有量子计算相关内容，应当拒答说明未找到。"}
            {"id": "fact-06-retrieval-error", "question": "MindBase 默认检索返回多少条？", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["mindbase-intro.pdf"], "expectedEvidence": ["topK"], "referenceAnswer": "默认返回 topK 5 条。"}
            """;

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RagEvalProperties properties = new RagEvalProperties();

    /** 按用例顺序的确定性队列：检索行为（正常列表或异常）、工具检索结果、模型回合、评委回复 */
    private final ArrayDeque<Object> searchBehaviors = new ArrayDeque<>();
    private final ArrayDeque<AgentToolRegistry.ToolExecutionResult> toolBehaviors = new ArrayDeque<>();
    private final ArrayDeque<LlmProviderRouter.ReActTurn> streamTurns = new ArrayDeque<>();
    private final ArrayDeque<String> completeReplies = new ArrayDeque<>();

    private RagEvalService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(SMOKE_DIR);
        properties.setOutputDir(SMOKE_DIR.toString());
        // fixture 确定性：评委输出全部可解析，人工复核抽样关闭（默认 0.1 会随机抽走用例）
        properties.setHumanReviewSampleRate(0d);
        properties.setRandomSeed(1L);

        when(router.buildReActMessages(anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        // 流式回合在调用线程同步完成（与线上流式路径一致），按用例顺序弹出预置回合
        when(router.streamReActTurn(anyString(), anyList(), anyList(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    Consumer<LlmProviderRouter.ReActTurn> onComplete = invocation.getArgument(6);
                    LlmProviderRouter.ReActTurn turn = streamTurns.remove();
                    if (turn.content() != null && !turn.content().isEmpty()) {
                        onChunk.accept(turn.content());
                    }
                    onComplete.accept(turn);
                    return newStreamHandle();
                });
        // react 模式下 completeReActTurn 只承载评委调用，按用例顺序弹出预置回复
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt(), any()))
                .thenAnswer(invocation -> new LlmProviderRouter.ReActTurn(completeReplies.remove(), List.of(),
                        Map.of(), "stop", 3, 3));
        when(tools.getTools()).thenReturn(List.of(new AgentToolRegistry.AgentTool(
                "search_knowledge", "检索知识库", Map.of("type", "object"))));
        when(tools.executeTool(eq("search_knowledge"), ArgumentMatchers.anyMap(), anyString(), any()))
                .thenAnswer(invocation -> toolBehaviors.remove());

        // 检索行为按用例顺序弹出；RuntimeException 表示检索调用失败（计入 retrievalErrorRate）
        when(searchService.searchWithPermission(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            Object behavior = searchBehaviors.remove();
            if (behavior instanceof RuntimeException failure) {
                throw failure;
            }
            return behavior;
        });

        RagEvalCaseRunner caseRunner = new RagEvalCaseRunner(searchService, router, expander(), properties,
                objectMapper, Runnable::run, tools);
        service = new RagEvalService(caseRunner, properties, new HybridSearchProperties(),
                new ParentContextProperties(), new ContextualRetrievalProperties(), objectMapper, new DirectExecutor());
    }

    @Test
    void fixtureDatasetParsesAsFullV2WithoutLegacyFlags() throws Exception {
        EvalDatasetParser.ParseResult parsed = parseFixture();
        assertEquals(20, parsed.cases().size(), "fixture 固定 20 题");
        assertFalse(parsed.legacyDataset(), "fixture 全部字段齐全，不是 legacy 数据集");
        assertTrue(parsed.warnings().isEmpty());
        assertEquals(20, parsed.cases().stream().map(EvalCase::id).distinct().count(), "id 唯一");
    }

    @Test
    void smokeFixtureRunsTwentyCasesThroughReactPipelineAndWritesV2Report() throws Exception {
        // 只在本测试清场：gate 脚本按目录里的最新 JSON 定位报告，测试方法之间互不清对方的产物
        deleteRecursively(SMOKE_DIR);
        Files.createDirectories(SMOKE_DIR);
        EvalDatasetParser.ParseResult parsed = parseFixture();
        enqueueBehaviors(parsed.cases());

        RagEvalService.RunStatus status = service.start(parsed.cases(), "inline-fixture-20.jsonl", 5,
                "smoke-fixture-20", "7", "junit");

        assertEquals(RagEvalService.RunState.SUCCEEDED, status.state(), "冒烟评测要成功结束");
        assertEquals(20, status.completedCases());
        Path reportPath = Path.of(status.reportPath());
        assertTrue(Files.isRegularFile(reportPath), "报告已落盘: " + reportPath);

        JsonNode report = objectMapper.readTree(reportPath.toFile());
        assertEquals("2.0", report.get("schemaVersion").asText(), "走真实 RagEvalService 落盘，schemaVersion=2.0");
        assertEquals("smoke-fixture-20", report.get("label").asText());
        assertEquals("react", report.get("config").get("mode").asText(), "冒烟门禁跑的是默认 react 生产链路");
        assertFalse(report.get("diagnosisGuide").isEmpty(), "报告带归因指南");
        assertEquals(20, report.get("cases").size());
        assertTrue(report.get("cases").get(0).has("question") && !report.get("cases").get(0).get("question").asText().isBlank(),
                "cases 保留问题明细，可离线复核");

        // 检索指标：19 题可评（fact-06 检索异常不计入分母），18 命中；neg-01 命中期望文件但证据短语未命中
        JsonNode summary = report.get("summary");
        assertEquals(20, summary.get("totalCases").asInt());
        assertEquals(19, summary.get("labeledCases").asInt());
        assertEquals(18, summary.get("retrievalHits").asInt());
        assertEquals(0.9474, summary.get("recallAtK").asDouble(), 1e-4, "18/19");
        assertEquals(1.0, summary.get("docRecallAtK").asDouble(), 1e-4, "neg-01 文件级命中，19/19");
        assertEquals(0.9211, summary.get("mrr").asDouble(), 1e-4, "17 题 rank1 + multi-02 rank2 = 17.5/19");
        assertEquals(19, summary.get("evidenceLabeledCases").asInt());
        assertEquals(0.8947, summary.get("meanExpectedDocCoverage").asDouble(), 1e-4, "16 全覆盖 + 2 半覆盖 = 17/19");
        assertEquals(0.8421, summary.get("fullCoverageHitRate").asDouble(), 1e-4, "半覆盖不算全命中：16/19");
        assertEquals(0.9279, summary.get("ndcgAtK").get("5").asDouble(), 1e-4, "multi-02 的 1/log2(3) 折损");
        assertEquals(0.05, summary.get("retrievalErrorRate").asDouble(), 1e-4, "1/20 检索异常单列，不从分母消失");

        // 评委指标：19 题被判（fact-06 无回答不判评委），neg-01 答错
        assertEquals(19, summary.get("judgedCases").asInt());
        assertEquals(18, summary.get("correctCases").asInt());
        assertEquals(0.9474, summary.get("accuracy").asDouble(), 1e-4);
        assertEquals(4.8421, summary.get("meanJudgeScore").asDouble(), 1e-4, "18×5 + 1×2 = 92/19");
        assertEquals(0, summary.get("needsHumanReviewCount").asInt());
        assertEquals(1, summary.get("failedCases").asInt());

        JsonNode modeCounts = summary.get("retrievalModeCounts");
        assertEquals(19, modeCounts.get("HYBRID").asInt());
        assertEquals(1, modeCounts.get("NONE").asInt());
        JsonNode diagnoses = summary.get("diagnosisCounts");
        assertEquals(18, diagnoses.get("OK").asInt());
        assertEquals(1, diagnoses.get("RETRIEVAL_MISS").asInt());
        assertEquals(1, diagnoses.get("RETRIEVAL_FAILED").asInt());

        // react 链路明细：工具轮 + 回答轮，引用编号与 token 用量可复核
        JsonNode firstCase = report.get("cases").get(0);
        JsonNode trace = firstCase.get("reactTrace");
        assertNotNull(trace, "react 模式必须产出 reactTrace");
        assertEquals(2, trace.get("modelRounds").asInt());
        assertEquals(1, trace.get("toolCalls").asInt());
        assertEquals(List.of(1), toIntList(trace.get("referenceNumbers")));
        assertTrue(firstCase.get("answer").asText().contains("[1]"), "回答带引用编号");
        assertTrue(firstCase.get("judgeReason").asText().contains("一致"), "评委理由保留在明细里");
        assertEquals(45, firstCase.get("tokenUsage").get("totalTokens").asInt(), "每题 30 prompt + 15 completion");
        assertEquals(570, report.get("tokenUsage").get("promptTokens").asInt(), "19 题 × 30");
        assertEquals(285, report.get("tokenUsage").get("completionTokens").asInt(), "19 题 × 15");
        assertEquals(855, report.get("tokenUsage").get("totalTokens").asInt());

        // 构造出的边界行为逐题落在报告里
        JsonNode rankTwo = report.get("cases").get(9);
        assertEquals("multi-02-mindbase-mq", rankTwo.get("caseId").asText());
        assertEquals(2, rankTwo.get("firstRelevantRank").asInt(), "multi-02 相关分块排第 2 位");
        JsonNode partial = report.get("cases").get(16);
        assertEquals("multi-04-partial-storage", partial.get("caseId").asText());
        assertEquals(0.5, partial.get("expectedDocCoverage").asDouble(), 1e-9, "两证据只命中一条 → 覆盖率 0.5");
        assertTrue(partial.get("retrievalHit").asBoolean(), "命中过即算 recall 命中");
        JsonNode negCase = report.get("cases").get(18);
        assertEquals("neg-01-quantum", negCase.get("caseId").asText());
        assertFalse(negCase.get("retrievalHit").asBoolean(), "证据短语未命中 → 分块级未命中");
        assertTrue(negCase.get("docHit").asBoolean(), "期望文件在结果里 → 文件级命中");
        assertEquals("RETRIEVAL_MISS", negCase.get("diagnosis").asText());
        assertFalse(negCase.get("judgeCorrect").asBoolean(), "应拒答题没拒答，评委判错");
        JsonNode errorCase = report.get("cases").get(19);
        assertEquals("fact-06-retrieval-error", errorCase.get("caseId").asText());
        assertEquals("RETRIEVAL_FAILED", errorCase.get("diagnosis").asText());
        assertNull(errorCase.get("answer").isNull() ? null : errorCase.get("answer"), "检索异常的用例没有回答");
        assertTrue(errorCase.get("error").asText().contains("es down"), "失败原因写入明细");
        assertTrue(errorCase.get("retrievalHit").isNull(), "检索异常不产出命中判定");

        try (Stream<Path> files = Files.list(SMOKE_DIR)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size(), "冒烟目录只留本次报告: " + names);
            assertTrue(names.get(0).endsWith(".json"));
        }

        System.out.println("[eval-smoke] report=" + reportPath.toAbsolutePath());
    }

    private EvalDatasetParser.ParseResult parseFixture() throws Exception {
        return EvalDatasetParser.parseWithDiagnostics(new java.io.StringReader(FIXTURE_JSONL), objectMapper, 500);
    }

    /** 按用例顺序预置：检索结果、工具结果、模型回合（工具轮 + 回答轮）、评委回复 */
    private void enqueueBehaviors(List<EvalCase> cases) {
        for (EvalCase evalCase : cases) {
            if ("fact-06-retrieval-error".equals(evalCase.id())) {
                searchBehaviors.add(new IllegalStateException("es down"));
                continue;
            }
            List<SearchResult> results = searchResultsFor(evalCase);
            searchBehaviors.add(results);
            toolBehaviors.add(new AgentToolRegistry.ToolExecutionResult("search_knowledge", true,
                    AgentToolRegistry.formatSearchResults(results),
                    Map.of("results", results, "outcome", "HIT"), false));
            String answer = "neg-01-quantum".equals(evalCase.id())
                    ? "MindBase 可以直接用于量子计算场景。"
                    : "根据知识库：" + evalCase.referenceAnswer() + " [1]。";
            streamTurns.add(turnWithToolCalls("call_" + evalCase.id(), 10, 5));
            streamTurns.add(new LlmProviderRouter.ReActTurn(answer, List.of(),
                    Map.of("role", "assistant", "content", ""), "stop", 20, 10));
            boolean correct = !"neg-01-quantum".equals(evalCase.id());
            completeReplies.add(correct
                    ? "{\"score\": 5, \"correct\": true, \"reason\": \"与参考答案一致\"}"
                    : "{\"score\": 2, \"correct\": false, \"reason\": \"应拒答未拒答，回答与知识库不符\"}");
        }
    }

    private List<SearchResult> searchResultsFor(EvalCase evalCase) {
        return switch (evalCase.id()) {
            // 相关分块排在第 2 位：验证 MRR 折损与 nDCG 折损都有真实样本
            case "multi-02-mindbase-mq" -> List.of(
                    hybridResult("noise-md5", "noise.pdf", "与问题无关的占位内容"),
                    hybridResult("mb-md5", "mindbase-intro.pdf", chunkTextFor(evalCase)));
            // neg-01：返回期望文件里的无关分块（文件级命中、分块级未命中）
            case "neg-01-quantum" -> List.of(
                    hybridResult("mb-md5", "mindbase-intro.pdf", "MindBase 支持多种文档格式与细粒度权限管理。"));
            default -> List.of(hybridResult(md5For(evalCase), evalCase.expectedFileNames().get(0),
                    chunkTextFor(evalCase)));
        };
    }

    /** 分块正文包含该题的第一条证据短语（multi-04/multi-05 刻意只包含第一条 → 半覆盖） */
    private String chunkTextFor(EvalCase evalCase) {
        return switch (evalCase.id()) {
            case "fact-01-mindbase-role" -> "MindBase 是企业级 AI 知识管理系统，围绕 RAG 工作流构建。";
            case "fact-02-mindbase-storage" -> "MindBase 使用 MySQL 做主存储，Redis 用于缓存与会话。";
            case "fact-03-mindbase-cache" -> "Redis 在 MindBase 中承担缓存、会话与短期聊天上下文。";
            case "fact-04-rustfs-api" -> "RustFS 提供 S3 兼容接口，可替换 MinIO 使用。";
            case "fact-05-rustfs-erasure" -> "RustFS 使用纠删码保证数据可靠性。";
            case "para-01-mindbase-rag" -> "MindBase 的 RAG 流程包括检索、重排序与生成。";
            case "para-02-mindbase-search" -> "混合检索同时兼顾关键词匹配与语义相似度，是 MindBase 的默认检索方式。";
            case "para-03-resume-focus" -> "候选人的主要技术方向是 AI 应用开发。";
            case "multi-01-mindbase-stack" -> "MindBase 后端基于 Spring Boot，前端使用 Vue 3。";
            case "multi-02-mindbase-mq" -> "MindBase 使用 Kafka 做异步文件处理。";
            case "multi-03-resume-projects" -> "简历中包含 RAG 知识库与推荐系统项目。";
            case "conflict-01-mindbase-port" -> "MindBase 后端服务默认运行在 8081 端口。";
            case "rustfs-01-put" -> "向 RustFS 上传对象使用 S3 兼容的 PUT 接口。";
            case "rustfs-02-bucket" -> "桶是 RustFS 中存放对象的顶层容器。";
            case "resume-01-skills" -> "候选人掌握 Java 与 TypeScript。";
            case "resume-02-education" -> "候选人为本科学历，计算机相关专业。";
            case "multi-04-partial-storage" -> "MindBase 使用 MySQL 做主存储。";
            case "multi-05-partial-deploy" -> "RustFS 以单二进制方式部署。";
            default -> throw new IllegalStateException("fixture 缺少分块正文: " + evalCase.id());
        };
    }

    private String md5For(EvalCase evalCase) {
        return switch (evalCase.expectedFileNames().get(0)) {
            case "mindbase-intro.pdf" -> "mb-md5";
            case "rustfs-notes.txt" -> "rustfs-md5";
            case "resume-sample.pdf" -> "resume-md5";
            default -> "unknown-md5";
        };
    }

    private ParentContextExpander expander() {
        ParentContextProperties parentProperties = new ParentContextProperties();
        parentProperties.setEnabled(false);
        return new ParentContextExpander(parentProperties, documentVectorRepository);
    }

    /** StreamHandle 是 final 类且构造私有，用反射构造"无上游订阅"的句柄（cancel() 对 null 订阅是空操作） */
    private static LlmProviderRouter.StreamHandle newStreamHandle() throws Exception {
        java.lang.reflect.Constructor<LlmProviderRouter.StreamHandle> constructor =
                LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[]{null, (Runnable) () -> { }});
    }

    private static LlmProviderRouter.ReActTurn turnWithToolCalls(String callId, int promptTokens, int completionTokens) {
        return new LlmProviderRouter.ReActTurn("",
                List.of(new LlmProviderRouter.ToolCallDecision(callId, "search_knowledge", Map.of("query", "知识库检索"))),
                Map.of("role", "assistant", "content", ""), "tool_calls", promptTokens, completionTokens);
    }

    private static SearchResult hybridResult(String md5, String fileName, String text) {
        return new SearchResult(md5, 1, text, 0.9, "7", "org", false, fileName, null, null, "HYBRID", null);
    }

    private static List<Integer> toIntList(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asInt()));
        return out;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception e) {
                    throw new IllegalStateException("清理失败: " + p, e);
                }
            });
        }
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
