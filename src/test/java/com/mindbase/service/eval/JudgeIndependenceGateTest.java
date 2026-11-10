package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.service.HybridSearchService;
import com.mindbase.service.LlmProviderRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * 评委独立性与发布门禁（评测缺口修复）：
 * <ul>
 *   <li>报告 config 构造时落 judgeIndependence / releaseEligible / releaseBlockedReason 三键：
 *       SELF_JUDGE → false；κ 可计算且低于 minKappa → false；可比样本少于 minComparableCases → false；</li>
 *   <li>没有人工复核数据时不伪造 κ：报告写明 kappa 未计算，只按 SELF_JUDGE 规则判定；</li>
 *   <li>scripts/eval_compare.py 在 --fail-if 涉及 accuracy/judgeScore 且 releaseEligible=false 时退出 2，
 *       --allow-self-judge 才放行并打印降级说明（subprocess 对临时 fixture 断言退出码）。</li>
 * </ul>
 * 全部内存构造 / fixture，不依赖任何外部服务。
 */
class JudgeIndependenceGateTest {

    private static final String SELF_BLOCK_REASON =
            "评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定；" + EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    /* ---------- Java 侧：报告构造即判定（内存构造的报告对象） ---------- */

    @Test
    void selfJudgeReportIsReleaseBlockedAndWritesKappaNotComputed() {
        EvalReport report = report(Map.of(EvalReport.CONFIG_JUDGE, RagEvalCaseRunner.SELF_JUDGE));
        Map<String, Object> config = report.config();

        assertEquals(RagEvalCaseRunner.SELF_JUDGE, config.get(EvalReport.CONFIG_JUDGE_INDEPENDENCE),
                "评委身份是 SELF_JUDGE 时报告必须标 SELF_JUDGE");
        assertEquals(Boolean.FALSE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE),
                "自评报告不能用于发布判定");
        String reason = (String) config.get(EvalReport.CONFIG_RELEASE_BLOCKED_REASON);
        assertTrue(reason != null && reason.contains("SELF_JUDGE"), "阻断原因要点名 SELF_JUDGE: " + reason);
        assertTrue(reason.contains("kappa 未计算"), "无人工复核数据时必须在阻断原因里写明 kappa 未计算");
        assertNull(config.get(EvalReport.CONFIG_KAPPA), "没有人工复核数据时 κ 为 null，不伪造");
        assertEquals(EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE, config.get(EvalReport.CONFIG_KAPPA_NOTE),
                "kappaNote 写明未计算原因");
    }

    @Test
    void independentJudgeWithKappa07AtOrAboveFloorIsReleaseEligible() {
        EvalReport report = report(Map.of(
                EvalReport.CONFIG_JUDGE, "secondary/judge-x",
                EvalReport.CONFIG_KAPPA, 0.7,
                EvalReport.CONFIG_MIN_KAPPA, 0.6,
                EvalReport.CONFIG_COMPARABLE_CASES, 10,
                EvalReport.CONFIG_MIN_COMPARABLE_CASES, 5));
        Map<String, Object> config = report.config();

        assertEquals(RagEvalCaseRunner.INDEPENDENT, config.get(EvalReport.CONFIG_JUDGE_INDEPENDENCE),
                "配置了独立评委供应商的报告标 INDEPENDENT");
        assertEquals(Boolean.TRUE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE),
                "独立评委 + κ 0.7 ≥ 下限 0.6 + 样本充足 → 门禁放行");
        assertNull(config.get(EvalReport.CONFIG_RELEASE_BLOCKED_REASON), "放行时没有阻断原因");
        assertEquals(0.7, ((Number) config.get(EvalReport.CONFIG_KAPPA)).doubleValue(), 1e-9,
                "κ 数值原样落报告，可复核");
        assertNull(config.get(EvalReport.CONFIG_KAPPA_NOTE), "κ 已计算时不写未计算说明");
    }

    @Test
    void independentJudgeWithTooFewComparableCasesIsReleaseBlocked() {
        EvalReport report = report(Map.of(
                EvalReport.CONFIG_JUDGE, "secondary/judge-x",
                EvalReport.CONFIG_COMPARABLE_CASES, 3,
                EvalReport.CONFIG_MIN_COMPARABLE_CASES, 5));
        Map<String, Object> config = report.config();

        assertEquals(Boolean.FALSE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE),
                "可比样本 3 条少于下限 5 条 → 门禁拦截");
        String reason = (String) config.get(EvalReport.CONFIG_RELEASE_BLOCKED_REASON);
        assertTrue(reason != null && reason.contains("可比样本") && reason.contains("3") && reason.contains("5"),
                "阻断原因写明样本数与下限: " + reason);
        assertFalse(reason.contains("SELF_JUDGE"), "独立评委的阻断原因不应误报为自评");
        assertEquals(EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE, config.get(EvalReport.CONFIG_KAPPA_NOTE),
                "本场景没有 κ 数据，报告仍写明 kappa 未计算");
    }

    @Test
    void independentJudgeWithKappaBelowFloorIsReleaseBlocked() {
        EvalReport report = report(Map.of(
                EvalReport.CONFIG_JUDGE, "secondary/judge-x",
                EvalReport.CONFIG_KAPPA, 0.5,
                EvalReport.CONFIG_MIN_KAPPA, 0.6,
                EvalReport.CONFIG_COMPARABLE_CASES, 20,
                EvalReport.CONFIG_MIN_COMPARABLE_CASES, 5));
        Map<String, Object> config = report.config();

        assertEquals(Boolean.FALSE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE),
                "κ 0.5 低于下限 0.6 → 门禁拦截");
        String reason = (String) config.get(EvalReport.CONFIG_RELEASE_BLOCKED_REASON);
        assertTrue(reason != null && reason.contains("kappa") && reason.contains("0.6"),
                "阻断原因写明 κ 与下限: " + reason);
    }

    @Test
    void missingJudgeIdentityFailsClosedAsSelfJudge() {
        EvalReport report = report(Map.of());
        Map<String, Object> config = report.config();

        assertEquals(RagEvalCaseRunner.SELF_JUDGE, config.get(EvalReport.CONFIG_JUDGE_INDEPENDENCE),
                "评委身份缺失时按 SELF_JUDGE 处理，宁可误拦不可放行");
        assertEquals(Boolean.FALSE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE));
    }

    @Test
    void independentJudgeWithoutHumanDataIsEligibleButNotesKappaNotComputed() {
        EvalReport report = report(Map.of(EvalReport.CONFIG_JUDGE, "secondary"));
        Map<String, Object> config = report.config();

        assertEquals(Boolean.TRUE, config.get(EvalReport.CONFIG_RELEASE_ELIGIBLE),
                "判定规则只含三条阻断：独立评委且无 κ 数据时三条都不命中 → 放行（不伪造 κ）");
        assertNull(config.get(EvalReport.CONFIG_RELEASE_BLOCKED_REASON));
        assertEquals(EvalReportAggregator.KAPPA_NOT_COMPUTED_NOTE, config.get(EvalReport.CONFIG_KAPPA_NOTE),
                "但没有人工复核数据这一点要写明：kappa 未计算");
    }

    @Test
    void runnerDerivesJudgeIndependenceFromProviderConfig() {
        RagEvalProperties properties = new RagEvalProperties();
        RagEvalCaseRunner runner = new RagEvalCaseRunner(mock(HybridSearchService.class),
                mock(LlmProviderRouter.class), null, properties, objectMapper, Runnable::run);

        assertEquals(RagEvalCaseRunner.SELF_JUDGE, runner.judgeIndependence(),
                "eval.judge.provider 未配置 → SELF_JUDGE（与生成同模型自评）");
        assertEquals(RagEvalCaseRunner.SELF_JUDGE, runner.judgeLlmDescription());

        properties.getJudge().setProvider("secondary");
        assertEquals(RagEvalCaseRunner.INDEPENDENT, runner.judgeIndependence(),
                "配置了独立评委供应商 → INDEPENDENT");
        assertEquals("secondary", runner.judgeLlmDescription());
    }

    /* ---------- 脚本侧：subprocess 对临时 fixture 断言退出码 ---------- */

    @Test
    void compareScriptBlocksSelfJudgeReportOnAccuracyGateWithExit2() throws Exception {
        Path report = writeFixtureReport("self-judge", Map.of(
                EvalReport.CONFIG_JUDGE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, false,
                EvalReport.CONFIG_RELEASE_BLOCKED_REASON, SELF_BLOCK_REASON));

        ScriptResult result = runCompare(report.toString(), "--fail-if", "accuracy>=0.8");

        assertEquals(2, result.exitCode(), "self 报告配 --fail-if accuracy>=0.8 必须退出 2，实际输出:\n" + result.output());
        assertTrue(result.output().contains("releaseEligible=false"), "要说明是发布门禁拦截:\n" + result.output());
        assertTrue(result.output().contains("SELF_JUDGE"), "要打印 judgeIndependence:\n" + result.output());
        assertTrue(result.output().contains("kappa 未计算"), "要打印 releaseBlockedReason 原文:\n" + result.output());
    }

    @Test
    void compareScriptBlocksSelfJudgeEvenWhenMetricConditionItselfWouldPass() throws Exception {
        Path report = writeFixtureReport("self-judge", Map.of(
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, false,
                EvalReport.CONFIG_RELEASE_BLOCKED_REASON, SELF_BLOCK_REASON));

        // accuracy=0.9 不命中 accuracy<0.99 这个失败条件，但发布门禁仍要拦——
        // 拦的是"评委分数不可信"这件事本身，与指标数值方向无关
        ScriptResult result = runCompare(report.toString(), "--fail-if", "accuracy<0.99");

        assertEquals(2, result.exitCode(), "发布门禁与指标数值方向无关，实际输出:\n" + result.output());
    }

    @Test
    void compareScriptPassesEligibleIndependentReportWithExit0() throws Exception {
        Path report = writeFixtureReport("independent-judge", Map.of(
                EvalReport.CONFIG_JUDGE, "secondary/judge-x",
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.INDEPENDENT,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, true));

        ScriptResult result = runCompare(report.toString(), "--fail-if", "accuracy<0.8");

        assertEquals(0, result.exitCode(), "independent 且门禁放行的报告应正常通过，实际输出:\n" + result.output());
        assertFalse(result.output().contains("降级说明"), "正常放行不需要降级说明:\n" + result.output());
    }

    @Test
    void compareScriptAllowSelfJudgeOverridesGateWithDegradationNote() throws Exception {
        Path report = writeFixtureReport("self-judge", Map.of(
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, false,
                EvalReport.CONFIG_RELEASE_BLOCKED_REASON, SELF_BLOCK_REASON));

        ScriptResult result = runCompare(report.toString(), "--fail-if", "accuracy<0.8", "--allow-self-judge");

        assertEquals(0, result.exitCode(), "--allow-self-judge 应放行（指标条件本身通过），实际输出:\n" + result.output());
        assertTrue(result.output().contains("降级说明"), "放行时必须打印降级说明:\n" + result.output());
        assertTrue(result.output().contains("SELF_JUDGE"), "降级说明里要点名原因:\n" + result.output());
    }

    @Test
    void compareScriptStillReportsMetricViolationsAfterAllowSelfJudge() throws Exception {
        Path report = writeFixtureReport("self-judge", Map.of(
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, false,
                EvalReport.CONFIG_RELEASE_BLOCKED_REASON, SELF_BLOCK_REASON));

        // 放行只是跳过发布门禁，指标条件该失败还是失败（accuracy 0.9 命中失败条件 accuracy>=0.8 → 3）
        ScriptResult result = runCompare(report.toString(), "--fail-if", "accuracy>=0.8", "--allow-self-judge");

        assertEquals(3, result.exitCode(), "放行后指标违规仍按原退出码 3，实际输出:\n" + result.output());
        assertTrue(result.output().contains("降级说明"), "放行说明与违规输出并存:\n" + result.output());
    }

    @Test
    void compareScriptLeavesRetrievalOnlyGatesUnaffectedByReleaseGate() throws Exception {
        Path report = writeFixtureReport("self-judge", Map.of(
                EvalReport.CONFIG_JUDGE_INDEPENDENCE, RagEvalCaseRunner.SELF_JUDGE,
                EvalReport.CONFIG_RELEASE_ELIGIBLE, false,
                EvalReport.CONFIG_RELEASE_BLOCKED_REASON, SELF_BLOCK_REASON));

        // 检索类门禁不依赖评委分数：releaseEligible=false 不影响 recall 类条件（走原 0/3 退出码）
        ScriptResult result = runCompare(report.toString(), "--fail-if", "recallAtK<0.8");

        assertEquals(0, result.exitCode(), "检索类门禁不受发布门禁影响，实际输出:\n" + result.output());
    }

    /* ---------- helpers ---------- */

    /** 内存构造 v2 报告：只填发布门禁相关字段，其余用最小合法值 */
    private static EvalReport report(Map<String, Object> config) {
        return new EvalReport("runid00000001", "judge-gate", "inline-fixture.jsonl", 5,
                Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:01:00Z"),
                EvalReport.SCHEMA_VERSION, "unknown", "knowledge_base", null, "unknown",
                new LinkedHashMap<>(config), null, null, null, Map.of(), List.of());
    }

    /** 临时 v2 形态报告 fixture：summary/cases 最小合法，config 带发布门禁键（模拟 Java 侧写出的报告） */
    private Path writeFixtureReport(String label, Map<String, Object> gateConfig) throws Exception {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", 5);
        config.put("mode", "react");
        config.put("llm", "mock-provider/mock-model");
        config.putAll(gateConfig);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCases", 1);
        summary.put("labeledCases", 1);
        summary.put("retrievalHits", 1);
        summary.put("recallAtK", 1.0);
        summary.put("mrr", 1.0);
        summary.put("docRecallAtK", 1.0);
        summary.put("judgedCases", 1);
        summary.put("correctCases", 1);
        summary.put("accuracy", 0.9);
        summary.put("meanJudgeScore", 4.0);
        summary.put("failedCases", 0);
        summary.put("retrievalModeCounts", Map.of("HYBRID", 1));
        summary.put("diagnosisCounts", Map.of("OK", 1));

        Map<String, Object> caseItem = new LinkedHashMap<>();
        caseItem.put("caseId", "fact-01-gate");
        caseItem.put("type", "fact");
        caseItem.put("retrievalMode", "HYBRID");
        caseItem.put("retrievalHit", true);
        caseItem.put("firstRelevantRank", 1);
        caseItem.put("reciprocalRank", 1.0);
        caseItem.put("judgeScore", 4);
        caseItem.put("judgeCorrect", true);
        caseItem.put("diagnosis", "OK");
        caseItem.put("latencyMs", 1);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", "gatefixture1");
        report.put("label", label);
        report.put("topK", 5);
        report.put("schemaVersion", EvalReport.SCHEMA_VERSION);
        report.put("config", config);
        report.put("summary", summary);
        report.put("cases", List.of(caseItem));

        Path file = tempDir.resolve(label + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        return file;
    }

    private ScriptResult runCompare(String... args) throws Exception {
        Path script = Paths.get("scripts", "eval_compare.py").toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            fail("找不到 scripts/eval_compare.py（测试工作目录应为仓库根）: " + script);
        }
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(script.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("eval_compare.py 运行超时");
        }
        return new ScriptResult(process.exitValue(), output);
    }

    private record ScriptResult(int exitCode, String output) {
    }
}
