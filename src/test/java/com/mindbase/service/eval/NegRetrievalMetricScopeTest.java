package com.mindbase.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0004 neg 检索指标口径：应拒答题（expectedRefusal=true / type=neg）退出检索指标分母。
 * <p>
 * 用例集 1 正 1 neg 构造：neg 用例刻意让检索"命中"（证据锚点就在返回分块里，负样本题的锚点
 * 本来就在语料中），断言它的检索结果只单独计数（negExcludedCases）而不进任何检索命中率/分母；
 * accuracy/refusal/faithfulness 等生成与评委指标仍按 2 例计算（拒答正确率是生成指标）。
 * 数据集字段核对：eval-v2.jsonl 的 17 条负样本全部同时满足 {@code type=neg} 与
 * {@code expectedRefusal=true}（聚合器按 generation.refusalExpected 识别，见
 * {@link EvalReportAggregator#isNegCase}）。
 */
class NegRetrievalMetricScopeTest {

    /** 1 正 1 neg：neg 检索命中但不进分母/命中率；accuracy 与拒答口径仍按 2 例 */
    @Test
    void negCaseExcludedFromRetrievalDenominatorsButKeptInGenerationMetrics() {
        EvalCaseResult positive = result("fact-x", "端口是什么？", true,
                "端口配置为 8080，见 [1]。", 5, true, EvalDiagnosis.OK,
                new GenerationMetrics.Result(0, true, false, 1.0, 1, 1, 0, 0, 1.0, 1.0, 1, 1, 1));
        // neg 题：证据锚点被检索命中（retrievalHit=true/docHit=true/coverage=1.0），但模型顺着编造没有拒答
        EvalCaseResult negAnswered = result("neg-x", "采购花了多少美元？", true,
                "采购花了 100 万美元。", 2, false, EvalDiagnosis.PROMPT_OR_MODEL,
                new GenerationMetrics.Result(0, false, true, 0.0, 1, 0, 1, 0, null, null, 0, 0, 1));

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(positive, negAnswered));

        // 检索分母只含非 neg：分母=1，neg 检索命中不算命中率（否则 retrievalHits=2、分母=2）
        assertEquals(2, summary.totalCases());
        assertEquals(1, summary.labeledCases(), "检索分母只含非 neg 用例");
        assertEquals(1, summary.retrievalCases(), "检索题数（新口径分母）");
        assertEquals(1, summary.negExcludedCases(), "neg 用例单独计数");
        assertEquals(1, summary.retrievalHits(), "neg 的检索命中不计入命中率");
        assertEquals(1.0, summary.recallAtK());
        assertEquals(1.0, summary.mrr(), "neg 的倒数排名不进 MRR");
        assertEquals(1.0, summary.docRecallAtK(), "neg 的文件级命中不进 docRecall 分母");
        assertEquals(1, summary.evidenceLabeledCases(), "neg 虽带 expectedEvidence 但不进 evidenceLabeled");
        assertEquals(1.0, summary.meanExpectedDocCoverage(), "neg 的覆盖率不进分母");
        assertEquals(1.0, summary.fullCoverageHitRate());
        summary.ndcgAtK().forEach((k, v) -> assertEquals(1.0, v, "nDCG@" + k + " 只按非 neg 用例计算"));

        // 生成与评委指标仍包含 neg：accuracy 按 2 例；refusalAccuracy 只按 neg 的 judgeCorrect 计算
        assertEquals(2, summary.judgedCases(), "accuracy 分母仍包含 neg 用例");
        assertEquals(1, summary.correctCases());
        assertEquals(0.5, summary.accuracy(), "neg 答错计入 accuracy 分子分母");
        assertEquals(3.5, summary.meanJudgeScore(), "(5+2)/2");
        assertEquals(1, summary.refusalLabeledCases(), "拒答口径只统计 neg 用例");
        assertEquals(0, summary.refusalCorrectCases(), "neg 未拒答且评委判错 → 不计正确");
        assertEquals(0.0, summary.refusalAccuracy());
        assertEquals(1, summary.refusalExpectedCases());
        assertEquals(0, summary.refusedAsExpected());
        assertEquals(0.5, summary.faithfulness(), "neg 的 faithfulness 仍进生成侧平均");
        assertEquals(0, summary.failedCases());
        assertEquals("2.0", EvalReport.SCHEMA_VERSION, "neg 口径是 additive 变更，schemaVersion 保持 2.0");
    }

    /**
     * 已知口径损失（见 {@link EvalReportAggregator#isNegCase}）：回答未产出的用例没有 generation 明细，
     * 无法识别 neg——检索未命中又生成失败的此类 neg 用例按普通用例口径留在检索分母并拉低 recall
     * （数量可从 GENERATION_FAILED 侧复核）；若生成失败的是命中用例，同样留在分母与命中率里。
     */
    @Test
    void negCaseWithoutAnswerCannotBeIdentifiedAndStaysInRetrievalDenominator() {
        EvalCaseResult positive = result("fact-y", "端口是什么？", true,
                "端口配置为 8080，见 [1]。", 5, true, EvalDiagnosis.OK,
                new GenerationMetrics.Result(0, true, false, 1.0, 1, 1, 0, 0, 1.0, 1.0, 1, 1, 1));
        EvalCaseResult negFailed = new EvalCaseResult("neg-y", "采购花了多少美元？", "应拒答",
                List.of(), List.of("a.pdf"), List.of("alpha"), "HYBRID",
                List.of(new EvalCaseResult.RetrievedChunk(1, "md5-a", "a.pdf", 3, 0.9, false)),
                false, false, null, 0.0, 0.0, null, null, null, null, null,
                EvalDiagnosis.GENERATION_FAILED, false, null, "生成失败: timeout", 1L,
                null, null, null, null, null);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(positive, negFailed));

        assertEquals(2, summary.labeledCases(), "无回答的 neg 用例无法识别，留在检索分母");
        assertEquals(2, summary.retrievalCases());
        assertEquals(0, summary.negExcludedCases());
        assertEquals(0.5, summary.recallAtK(), "未识别的 neg 未命中会拉低 recall（生成失败用例的口径损失）");
    }

    /** 全 neg 数据集：检索分母为 0 → 检索率为 null 不编数字；拒答正确率等生成指标照常计算 */
    @Test
    void allNegDatasetYieldsNullRetrievalRatesButKeepsGenerationMetrics() {
        EvalCaseResult negRefused = result("neg-z", "采购花了多少美元？", true,
                "资料中没有提到数据集的采购成本。", 5, true, EvalDiagnosis.OK,
                new GenerationMetrics.Result(1, true, true, null, 0, 0, 0, 0, null, null, 0, 0, 0));

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(negRefused));

        assertEquals(1, summary.totalCases());
        assertEquals(0, summary.labeledCases());
        assertEquals(0, summary.retrievalCases());
        assertEquals(1, summary.negExcludedCases());
        assertNull(summary.recallAtK(), "检索分母为 0 时不编数字");
        assertNull(summary.mrr());
        assertNull(summary.docRecallAtK());
        assertNull(summary.meanExpectedDocCoverage());
        assertNull(summary.fullCoverageHitRate());
        assertNull(summary.ndcgAtK());
        assertEquals(0, summary.evidenceLabeledCases());
        // 生成侧照常：正确拒答 → accuracy 与拒答正确率都是 1.0
        assertEquals(1, summary.judgedCases());
        assertEquals(1.0, summary.accuracy());
        assertEquals(1, summary.refusalExpectedCases());
        assertEquals(1, summary.refusedAsExpected());
        assertEquals(1.0, summary.refusalAccuracy());
    }

    /**
     * 1 正 1 neg 的完整构造：两者检索行为相同（rank1 命中、coverage=1.0），差别只在
     * generation.refusalExpected 与评委结论，保证分母变化只能来自 neg 判定。
     */
    private static EvalCaseResult result(String caseId, String question, boolean hit,
                                         String answer, Integer score, Boolean correct, EvalDiagnosis diagnosis,
                                         GenerationMetrics.Result generation) {
        return new EvalCaseResult(caseId, question, "参考答案",
                List.of(), List.of("a.pdf"), List.of("alpha"), "HYBRID",
                List.of(new EvalCaseResult.RetrievedChunk(1, "md5-a", "a.pdf", 3, 0.9, true)),
                hit, hit, hit ? 1 : null, hit ? 1.0 : 0.0, hit ? 1.0 : 0.0,
                answer, score, correct, "评委理由", null, diagnosis, false, null, null, 1L,
                List.of(new EvalCaseResult.RetrievedText(1, "a.pdf 分块正文")),
                List.of(), null, null, generation);
    }
}
