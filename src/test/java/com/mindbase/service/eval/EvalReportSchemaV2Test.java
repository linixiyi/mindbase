package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.client.EmbeddingClient;
import com.mindbase.config.AiProperties;
import com.mindbase.config.ContextualRetrievalProperties;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.config.ParentContextProperties;
import com.mindbase.config.RagEvalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报告 schema v2 门禁：schemaVersion 与来源信息存在、cases 全量明细可复核、
 * 写入中断不留半份 JSON、token/费用字段保留。
 */
class EvalReportSchemaV2Test {

    private static final Pattern GIT_COMMIT = Pattern.compile("[0-9a-f]{40}");

    @TempDir
    Path tempDir;

    private final RagEvalCaseRunner caseRunner = mock(RagEvalCaseRunner.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RagEvalProperties properties = new RagEvalProperties();
    private final AiProperties aiProperties = new AiProperties();
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private RagEvalService service;

    @BeforeEach
    void setUp() {
        properties.setOutputDir(tempDir.resolve("reports").toString());
        properties.setDatasetDir(tempDir.resolve("datasets").toString());
        aiProperties.getPrompt().setRules("测试规则 v1：仅用简体中文作答。");
        when(embeddingClient.currentModelVersion()).thenReturn("aliyun:text-embedding-v4:2048");
        service = new RagEvalService(caseRunner, properties, new HybridSearchProperties(), new ParentContextProperties(),
                new ContextualRetrievalProperties(), objectMapper, aiProperties, embeddingClient, new DirectExecutor());
    }

    @Test
    void reportCarriesSchemaVersionAndSourceMetadata() throws Exception {
        runSingleCase(fullDetailResult("c1"));

        JsonNode report = readReportOfSingleRun();

        assertEquals(EvalReport.SCHEMA_VERSION, report.get("schemaVersion").asText(), "schemaVersion 必须存在");
        assertEquals("2.0", report.get("schemaVersion").asText());
        String gitCommit = report.get("gitCommit").asText();
        assertTrue(GIT_COMMIT.matcher(gitCommit).matches() || "unknown".equals(gitCommit),
                "gitCommit 是 40 位提交号或 unknown: " + gitCommit);
        assertEquals("knowledge_base", report.get("esAlias").asText(), "报告记录检索用的 ES 别名");
        assertEquals("aliyun:text-embedding-v4:2048", report.get("embeddingModel").asText(),
                "报告记录检索索引对应的 embedding 模型版本");
        assertTrue(report.get("promptVersion").asText().startsWith("sha256:"), "promptVersion 来自生效提示词规则的摘要");
        assertTrue(report.has("tokenUsage"), "tokenUsage 字段保留");
    }

    @Test
    void casesKeepFullDetailFieldsForThirdPartyReview() throws Exception {
        runSingleCase(fullDetailResult("c1"));

        JsonNode report = readReportOfSingleRun();
        JsonNode detail = report.get("cases").get(0);

        assertEquals("c1", detail.get("caseId").asText());
        assertFalse(detail.get("question").asText().isBlank(), "cases 保留 question");
        assertFalse(detail.get("answer").asText().isBlank(), "cases 保留 answer");
        assertFalse(detail.get("judgeReason").asText().isBlank(), "cases 保留 judgeReason");
        assertEquals("MindBase 用 MySQL 存储主数据", detail.get("retrievedText").get(0).get("text").asText(),
                "cases 保留生成与评委实际看到的检索片段正文");
        assertEquals(1, detail.get("retrievedText").get(0).get("rank").asInt());
        assertEquals("MindBase 用 MySQL 存储主数据", detail.get("claimVerdicts").get(0).get("claim").asText(),
                "cases 保留 claim 级判定");
        assertEquals("supported", detail.get("claimVerdicts").get(0).get("verdict").asText());

        JsonNode tokenUsage = report.get("tokenUsage");
        assertNotNull(tokenUsage, "有用量记录的用例要汇总出报告级 tokenUsage");
        assertEquals(120, tokenUsage.get("promptTokens").asInt());
        assertEquals(80, tokenUsage.get("completionTokens").asInt());
        assertEquals(200, tokenUsage.get("totalTokens").asInt());
        assertNotNull(report.get("cost"), "cost 字段非空");
        assertFalse(report.get("cost").get("note").asText().isBlank(), "费用取值来源必须说明");
    }

    @Test
    void interruptedWriteLeavesNoPartialJson() throws Exception {
        // config 里塞一个 Jackson 序列化不了的对象：写出前半部分后中断，模拟序列化中途失败
        EvalReport report = reportWithCase("c1");
        report.config().put("boom", new Object());

        assertThrows(Exception.class, () -> service.writeReport(report), "序列化失败要向上抛出");

        Path dir = Path.of(properties.getOutputDir());
        List<String> leftovers;
        try (Stream<Path> files = Files.list(dir)) {
            leftovers = files.map(path -> path.getFileName().toString()).toList();
        }
        assertTrue(leftovers.stream().noneMatch(name -> name.endsWith(".json")),
                "写入中断后正式文件名下不能有半份 JSON，实际残留: " + leftovers);
        assertTrue(leftovers.stream().noneMatch(name -> name.contains(".tmp")),
                "中断产生的临时文件要被清理，实际残留: " + leftovers);
    }

    @Test
    void successfulWriteProducesCompleteJsonWithoutTempLeftovers() throws Exception {
        Path written = service.writeReport(reportWithCase("c1"));

        assertTrue(written.toString().endsWith(".json"));
        assertTrue(Files.isRegularFile(written));
        JsonNode report = objectMapper.readTree(written.toFile());
        assertEquals("2.0", report.get("schemaVersion").asText(), "rename 后的正式文件是完整 JSON");
        assertEquals(1, report.get("summary").get("totalCases").asInt());
        try (Stream<Path> files = Files.list(written.getParent())) {
            assertEquals(1, files.count(), "成功路径只留正式报告文件，不残留临时文件");
        }
    }

    @Test
    void tokenUsageAndCostFieldsStayPresentWhenUsageNotRecorded() throws Exception {
        // 用例执行侧尚未记录用量时走 23 字段兼容构造：报告级字段仍保留，不做估算
        runSingleCase(new EvalCaseResult(
                "c1", "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(), true, true, 1, 1.0, 1.0,
                "answer", 4, true, "ok", null, EvalDiagnosis.OK, false, null, null, 12L));

        JsonNode report = readReportOfSingleRun();

        assertTrue(report.has("tokenUsage"), "字段保留");
        assertTrue(report.get("tokenUsage").isNull(), "没有用量数据时不编造数字");
        JsonNode detail = report.get("cases").get(0);
        assertTrue(detail.has("retrievedText") && detail.get("retrievedText").isNull(), "cases 明细字段保留");
        assertTrue(detail.has("claimVerdicts") && detail.get("claimVerdicts").isNull(), "cases 明细字段保留");
        JsonNode cost = report.get("cost");
        assertNotNull(cost, "cost 字段非空");
        assertTrue(cost.get("amount").isNull(), "未配置单价时不编造费用");
        assertFalse(cost.get("note").asText().isBlank());
    }

    @Test
    void gitCommitFallsBackToUnknownOutsideGitRepository() {
        assertEquals("unknown", RagEvalService.gitCommitOf(tempDir), "没有 .git 的目录返回 unknown，字段不省略");

        String repoCommit = RagEvalService.gitCommitOf(Path.of("").toAbsolutePath());
        assertTrue(GIT_COMMIT.matcher(repoCommit).matches() || "unknown".equals(repoCommit),
                "仓库目录里解析出真实提交号，无 git 环境则 unknown: " + repoCommit);
    }

    @Test
    void promptVersionTracksPromptRulesText() {
        String v1 = RagEvalService.promptVersionOf("规则一");
        assertEquals(v1, RagEvalService.promptVersionOf("规则一"), "同一规则文本版本稳定");
        assertTrue(RagEvalService.promptVersionOf("规则二").startsWith("sha256:"));
        assertTrue(!v1.equals(RagEvalService.promptVersionOf("规则二")), "规则文本变了版本随之变化");
        assertEquals("unknown", RagEvalService.promptVersionOf(null), "规则未配置时 unknown，字段不省略");
        assertEquals("unknown", RagEvalService.promptVersionOf("  "));
    }

    @Test
    void aggregatorSumsTokenUsageAndReturnsNullWhenNothingRecorded() {
        EvalCaseResult withUsage = fullDetailResult("c1");
        EvalCaseResult partial = new EvalCaseResult("c2", "q", "ref", List.of(), List.of(), List.of(), "HYBRID",
                List.of(), null, null, null, null, null, "answer", 4, true, "ok", null, EvalDiagnosis.NOT_LABELED,
                false, null, null, 1L, null, null, new EvalCaseResult.TokenUsage(10, 10, null), null);
        EvalCaseResult legacy = legacyResult("c3");

        EvalCaseResult.TokenUsage usage = EvalReportAggregator.tokenUsage(java.util.Arrays.asList(withUsage, partial, legacy, null));

        assertEquals(130, usage.promptTokens(), "只累计记录了的维度");
        assertEquals(90, usage.completionTokens());
        assertEquals(220, usage.totalTokens(), "该例 total 缺失时按该例 prompt+completion 计入");

        assertNull(EvalReportAggregator.tokenUsage(List.of(legacyResult("c4"))), "没有任何用量记录时返回 null");
        assertNull(EvalReportAggregator.tokenUsage(List.of()));
    }

    @Test
    void reportAggregatesTokenUsageAcrossMultipleCases() throws Exception {
        EvalCase evalCase1 = new EvalCase("c1", "q1", List.of(), List.of(), "ref");
        EvalCase evalCase2 = new EvalCase("c2", "q2", List.of(), List.of(), "ref");
        when(caseRunner.run(eq(evalCase1), anyString(), anyInt(), any())).thenReturn(fullDetailResult("c1"));
        when(caseRunner.run(eq(evalCase2), anyString(), anyInt(), any())).thenReturn(
                new EvalCaseResult("c2", "q2", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(), null, null,
                        null, null, null, "answer", 4, true, "ok", null, EvalDiagnosis.NOT_LABELED, false, null, null,
                        12L, null, null, new EvalCaseResult.TokenUsage(30, 20, null), null));

        service.start(List.of(evalCase1, evalCase2), "inline", 3, "schema-v2", "7", "admin");

        JsonNode report = readLatestReport();
        assertEquals(150, report.get("tokenUsage").get("promptTokens").asInt(), "120 + 30");
        assertEquals(100, report.get("tokenUsage").get("completionTokens").asInt(), "80 + 20");
        assertEquals(250, report.get("tokenUsage").get("totalTokens").asInt(), "200 + (30+20)");
    }

    /** 带全量 v2 明细的单条用例结果 */
    private static EvalCaseResult fullDetailResult(String caseId) {
        return new EvalCaseResult(
                caseId, "MindBase 的主数据存在哪里？", "主数据存 MySQL",
                List.of(), List.of("mindbase.pdf"), List.of("MySQL"),
                "HYBRID",
                List.of(new EvalCaseResult.RetrievedChunk(1, "md5-1", "mindbase.pdf", 3, 0.87, true)),
                true, true, 1, 1.0, 1.0,
                "MindBase 的主数据存在 MySQL 里，检索与缓存分别用 Elasticsearch 和 Redis。",
                5, true, "回答与参考答案一致",
                "{\"score\": 5, \"correct\": true}",
                EvalDiagnosis.OK, false, null, null, 15L,
                List.of(new EvalCaseResult.RetrievedText(1, "MindBase 用 MySQL 存储主数据")),
                List.of(new EvalCaseResult.ClaimVerdict("MindBase 用 MySQL 存储主数据", "supported", "片段 1 直接支撑")),
                new EvalCaseResult.TokenUsage(120, 80, 200), null);
    }

    private static EvalCaseResult legacyResult(String caseId) {
        return new EvalCaseResult(caseId, "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(),
                null, null, null, null, null, "answer", null, null, null, null, EvalDiagnosis.NOT_LABELED,
                false, null, null, 1L);
    }

    private EvalReport reportWithCase(String caseId) {
        return new EvalReport("runid00000001", "schema-v2", "inline", 3, Instant.now(), Instant.now(),
                EvalReport.SCHEMA_VERSION, "unknown", "knowledge_base", "aliyun:text-embedding-v4:2048",
                "sha256:deadbeef", new java.util.LinkedHashMap<>(), new EvalCaseResult.TokenUsage(120, 80, 200),
                EvalReport.Cost.unpriced(), EvalReportAggregator.summarize(List.of(fullDetailResult(caseId))),
                EvalReportAggregator.diagnosisGuide(), List.of(fullDetailResult(caseId)));
    }

    private void runSingleCase(EvalCaseResult result) {
        EvalCase evalCase = new EvalCase(result.caseId(), result.question(), List.of(), List.of(), "ref");
        when(caseRunner.run(eq(evalCase), anyString(), anyInt(), any())).thenReturn(result);
        service.start(List.of(evalCase), "inline", 3, "schema-v2", "7", "admin");
    }

    private JsonNode readReportOfSingleRun() throws Exception {
        return readLatestReport();
    }

    private JsonNode readLatestReport() throws Exception {
        RagEvalService.RunStatus status = service.getLatestStatus().orElseThrow();
        assertEquals(RagEvalService.RunState.SUCCEEDED, status.state(), "评测任务应成功完成");
        Path reportPath = Path.of(status.reportPath());
        assertTrue(Files.isRegularFile(reportPath));
        return objectMapper.readTree(reportPath.toFile());
    }

    /** 同步执行，便于断言任务结束后的状态 */
    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
