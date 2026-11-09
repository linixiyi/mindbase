package com.mindbase.service.eval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计口径测试：断言值全部锚定外部参考（任务口径与二项分布精确值），不是实现自身回归。
 * 参考值来源：Wilson 区间与 McNemar 精确 p 均可用组合数学独立复算
 * （43/50 → [0.738138, 0.930492]；b=10,c=0 → p = 2/2^10 = 0.001953125）。
 */
class EvalStatisticsTest {

    /* ---------------- Wilson 置信区间 ---------------- */

    @Test
    void wilsonIntervalFor43of50MatchesReference() {
        // 任务口径：43/50 → Wilson 95% CI ≈ [73.8, 93.1]，浮点容差 ±0.2pp
        EvalStatistics.Interval ci = EvalStatistics.wilsonInterval(43, 50);
        assertNotNull(ci);
        assertEquals(0.738, ci.lower(), 0.002, "43/50 Wilson 下界 ≈ 73.8%");
        assertEquals(0.931, ci.upper(), 0.002, "43/50 Wilson 上界 ≈ 93.1%");
        // 点估计落在区间内
        assertTrue(ci.lower() < 0.86 && 0.86 < ci.upper());
    }

    @Test
    void wilsonIsNotNormalApproximation() {
        // 正态近似在 1/10 时下界为 −0.086（越出 [0,1]）；Wilson 下界仍为正（参考值 0.0179）
        EvalStatistics.Interval sparse = EvalStatistics.wilsonInterval(1, 10);
        assertEquals(0.0179, sparse.lower(), 0.0005);
        assertTrue(sparse.lower() > 0, "Wilson 下界不得为负（正态近似才会）");

        // 0/50：正态近似退化为 [0,0]；Wilson 上界仍给出 7.13%
        EvalStatistics.Interval zero = EvalStatistics.wilsonInterval(0, 50);
        assertTrue(zero.lower() >= 0 && zero.lower() < 1e-6);
        assertEquals(0.0713, zero.upper(), 0.0005);

        // 43/50 的 Wilson 上界 93.05% 明显低于正态近似的 95.62%——锁死没有偷用正态近似
        assertTrue(EvalStatistics.wilsonInterval(43, 50).upper() < 0.94);
    }

    @Test
    void wilsonDegenerateCases() {
        assertNull(EvalStatistics.wilsonInterval(0, 0), "没有可计算样本时返回 null，不编数字");
        assertThrows(IllegalArgumentException.class, () -> EvalStatistics.wilsonInterval(51, 50));
        assertThrows(IllegalArgumentException.class, () -> EvalStatistics.wilsonInterval(-1, 50));
        assertThrows(IllegalArgumentException.class, () -> EvalStatistics.wilsonInterval(5, 10, 0));

        // 全对：区间不越过 1，下界参考值 0.9287（与 0/50 的上界 7.13% 对称）
        EvalStatistics.Interval full = EvalStatistics.wilsonInterval(50, 50);
        assertEquals(0.9287, full.lower(), 0.0005);
        assertEquals(1.0, full.upper(), 1e-6);
    }

    /* ---------------- McNemar 精确检验 ---------------- */

    @Test
    void mcnemarExactTenOneSidedFlipsGives002() {
        // 任务口径：10 例单向翻转 → McNemar p≈0.002（精确二项双侧 p = 2/1024 = 0.001953125）
        double p = EvalStatistics.mcnemarExactP(10, 0);
        assertEquals(0.001953125, p, 1e-9);
        assertTrue(p < 0.05);
        assertEquals(p, EvalStatistics.mcnemarExactP(0, 10), 0.0, "翻转方向对称：b/c 互换 p 不变");

        // 其余精确参考值：无不一致对或完全均衡时无证据
        assertEquals(1.0, EvalStatistics.mcnemarExactP(0, 0), 0.0);
        assertEquals(1.0, EvalStatistics.mcnemarExactP(5, 5), 0.0);
        // 1 与 9 的不均衡 → 精确 p = 22/1024
        assertEquals(0.021484375, EvalStatistics.mcnemarExactP(1, 9), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> EvalStatistics.mcnemarExactP(-1, 0));
    }

    /* ---------------- 配对 bootstrap ---------------- */

    @Test
    void pairedBootstrapConvergesOnSameCases() {
        // 同批 50 题：variant 在其中 10 题各多对 1 分（judgeScore 4→5），其余相同 → 均值差 0.2
        double[] baseline = new double[50];
        double[] variant = new double[50];
        for (int i = 0; i < 50; i++) {
            baseline[i] = 4;
            variant[i] = i < 10 ? 5 : 4;
        }

        EvalStatistics.BootstrapResult seedA = EvalStatistics.pairedBootstrap(baseline, variant, 4000, 20261109L);
        EvalStatistics.BootstrapResult seedB = EvalStatistics.pairedBootstrap(baseline, variant, 4000, 42L);
        assertEquals(0.2, seedA.meanDelta(), 1e-12);
        assertEquals(0.2, seedB.meanDelta(), 1e-12);

        // 收敛判定 1：同批题重复采样（换种子）后百分位区间一致（差异远小于区间宽度 ~0.22）
        assertTrue(Math.abs(seedA.ciLower() - seedB.ciLower()) < 0.02,
                "lower 跨种子应收敛: " + seedA + " vs " + seedB);
        assertTrue(Math.abs(seedA.ciUpper() - seedB.ciUpper()) < 0.02,
                "upper 跨种子应收敛: " + seedA + " vs " + seedB);

        // 收敛判定 2：点估计落在区间内，且真实差异不会被区间覆盖 0
        assertTrue(seedA.ciLower() <= seedA.meanDelta() && seedA.meanDelta() <= seedA.ciUpper());
        assertTrue(seedA.ciLower() > 0, "10/50 的真实提升区间应整体为正: " + seedA);

        // 收敛判定 3：重采样次数从 200 → 4000，区间稳定（粗采样只允许小幅抖动）
        EvalStatistics.BootstrapResult coarse = EvalStatistics.pairedBootstrap(baseline, variant, 200, 7L);
        assertTrue(Math.abs(coarse.ciLower() - seedA.ciLower()) < 0.08,
                "增加重采样次数应收敛: " + coarse + " vs " + seedA);
        assertTrue(Math.abs(coarse.ciUpper() - seedA.ciUpper()) < 0.08);
    }

    @Test
    void pairedBootstrapIdenticalSystemsGivesZeroInterval() {
        double[] scores = {4, 3, 5, 2, 4, 3, 5, 5, 4, 3};
        EvalStatistics.BootstrapResult result = EvalStatistics.pairedBootstrap(scores, scores.clone(), 500, 1L);
        assertEquals(0.0, result.meanDelta(), 0.0);
        assertEquals(0.0, result.ciLower(), 0.0, "同系统同批题：每次重采样差值恒为 0，区间宽度为 0");
        assertEquals(0.0, result.ciUpper(), 0.0);
    }

    @Test
    void pairedBootstrapRejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> EvalStatistics.pairedBootstrap(new double[]{1}, new double[]{1, 2}, 100, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> EvalStatistics.pairedBootstrap(new double[]{}, new double[]{}, 100, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> EvalStatistics.pairedBootstrap(null, new double[]{1}, 100, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> EvalStatistics.pairedBootstrap(new double[]{1, 2}, new double[]{1, 2}, 0, 1L));
    }

    /* ---------------- CLI（scripts/eval_gate.sh 依赖此输出格式） ---------------- */

    @Test
    void cliPrintsSingleLineJsonForGate() {
        String wilson = captureStdout(() -> EvalStatistics.main(new String[]{"wilson", "43", "50"}));
        assertTrue(wilson.startsWith("{\"lower\":0.738"), "输出应是单行 JSON 且下界 0.738…: " + wilson);
        assertTrue(wilson.contains("\"upper\":0.93"), "上界 0.9305…: " + wilson);

        String mcnemar = captureStdout(() -> EvalStatistics.main(new String[]{"mcnemar", "10", "0"}));
        assertEquals("{\"p\":0.001953125}", mcnemar, "10 例单向翻转的精确 p");
    }

    /* ---------------- 聚合器统计口径集成 ---------------- */

    @Test
    void aggregatorAccuracyWilsonUsesJudgedDenominatorOnly() {
        // 43 判对 + 7 判错 + 5 未打分：分母只取评委成功打分的 50 例 → 与 43/50 参考区间一致
        List<EvalCaseResult> results = new ArrayList<>();
        for (int i = 0; i < 43; i++) {
            results.add(judgedCase("ok-" + i, 5, true));
        }
        for (int i = 0; i < 7; i++) {
            results.add(judgedCase("bad-" + i, 2, false));
        }
        for (int i = 0; i < 5; i++) {
            results.add(judgedCase("skip-" + i, null, null));
        }

        EvalStatistics.Interval ci = EvalReportAggregator.accuracyWilsonInterval(results);
        assertNotNull(ci);
        assertEquals(0.738, ci.lower(), 0.002);
        assertEquals(0.931, ci.upper(), 0.002);
        assertNull(EvalReportAggregator.accuracyWilsonInterval(List.of()), "没有可计算样本时返回 null");
    }

    @Test
    void aggregatorCompareRunsDetectsTenOneSidedFlips() {
        List<EvalCaseResult> baseline = new ArrayList<>();
        List<EvalCaseResult> variant = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            baseline.add(judgedCase("both-" + i, 5, true));
            variant.add(judgedCase("both-" + i, 5, true));
        }
        // 10 例单向翻转：baseline 对（5 分）、variant 错（4 分）
        for (int i = 0; i < 10; i++) {
            baseline.add(judgedCase("flip-" + i, 5, true));
            variant.add(judgedCase("flip-" + i, 4, false));
        }
        baseline.add(judgedCase("only-base", 5, true));
        variant.add(judgedCase("only-var", 5, true));

        EvalReportAggregator.PairedRunComparison comparison = EvalReportAggregator.compareRuns(baseline, variant);
        assertEquals(15, comparison.matchedCases());
        assertEquals(15, comparison.baselineCorrect());
        assertEquals(5, comparison.variantCorrect());
        assertEquals(10, comparison.baselineOnlyWins());
        assertEquals(0, comparison.variantOnlyWins());
        assertEquals(0.001953125, comparison.mcnemarP(), 1e-9, "10 例单向翻转 → McNemar p≈0.002");
        assertEquals(1, comparison.unmatchedBaseline());
        assertEquals(1, comparison.unmatchedVariant());

        // 分数口径：均值差 = −10/15 ≈ −0.667，配对 bootstrap 区间整体为负（回退真实存在）
        assertEquals(-10.0 / 15, comparison.scoreDeltaMean(), 1e-9);
        assertNotNull(comparison.scoreDeltaCiLower());
        assertNotNull(comparison.scoreDeltaCiUpper());
        assertTrue(comparison.scoreDeltaCiUpper() < 0,
                "区间上界应为负: " + comparison.scoreDeltaCiUpper());
        assertFalse(comparison.scoreDeltaCiLower() > comparison.scoreDeltaMean());
    }

    @Test
    void aggregatorCompareRunsEmptyGivesNullStatistics() {
        EvalReportAggregator.PairedRunComparison comparison = EvalReportAggregator.compareRuns(List.of(), List.of());
        assertEquals(0, comparison.matchedCases());
        assertNull(comparison.mcnemarP());
        assertNull(comparison.scoreDeltaMean());
        assertNull(comparison.scoreDeltaCiLower());
        assertEquals(0, comparison.unmatchedBaseline());
        assertEquals(0, comparison.unmatchedVariant());
    }

    /* ---------------- 辅助 ---------------- */

    private static EvalCaseResult judgedCase(String caseId, Integer score, Boolean correct) {
        return new EvalCaseResult(caseId, "q-" + caseId, "ref", List.of(), List.of(), List.of(),
                "HYBRID", List.of(), true, true, 1, 1.0, 1.0, "answer", score, correct, null, null,
                EvalDiagnosis.OK, false, null, null, 1L);
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8).trim();
    }
}
