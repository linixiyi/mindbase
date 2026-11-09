package com.mindbase.service.eval;

import com.mindbase.config.RagEvalProperties;
import com.mindbase.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 检索指标 v2 门禁：
 * 单证据题命中（排第 1）时 nDCG=1；两证据题只命中一条 coverage=0.5 且不再算全命中；
 * 检索异常用例计入 retrievalErrorRate 而不是从分母消失；nDCG 的 K 支持 3/5/10。
 */
class RetrievalMetricsV2Test {

    private static final double EPS = 1e-9;
    /** 聚合器输出按 4 位小数取整，对未经取整的期望值放宽到该量级 */
    private static final double ROUNDED = 5e-5;

    // ---- nDCG@K ----

    @Test
    void singleEvidenceHitAtRankOneScoresNdcgOne() {
        EvalCase evalCase = evidenceCase("断点续传");
        List<SearchResult> results = List.of(
                new SearchResult("m1", 1, "支持⼤⽂件的断点续传和分片上传", 0.9, "a.pdf"),
                new SearchResult("m9", 1, "无关内容", 0.8, "b.pdf"));

        assertEquals(1.0, RetrievalMetrics.ndcgAtK(results, evalCase, 3), EPS);
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(results, evalCase, 5), EPS);
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(results, evalCase, 10), EPS);
    }

    @Test
    void ndcgDecaysWithRankIsZeroOnMissAndBeyondK() {
        EvalCase evalCase = evidenceCase("断点续传");

        List<SearchResult> rank2 = List.of(decoy(), chunk("m1", "支持断点续传"));
        assertEquals(1 / log2(3), RetrievalMetrics.ndcgAtK(rank2, evalCase, 5), EPS);

        List<SearchResult> rank4 = List.of(decoy(), decoy(), decoy(), chunk("m1", "支持断点续传"));
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(rank4, evalCase, 3), EPS, "相关分块在 K 外时 nDCG@K 为 0");
        assertEquals(1 / log2(5), RetrievalMetrics.ndcgAtK(rank4, evalCase, 5), EPS);
        assertEquals(1 / log2(5), RetrievalMetrics.ndcgAtK(rank4, evalCase, 10), EPS);

        assertEquals(0.0, RetrievalMetrics.ndcgAtK(List.of(decoy(), decoy()), evalCase, 5), EPS);
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(List.of(), evalCase, 5), EPS);
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(null, evalCase, 5), EPS);
    }

    @Test
    void multipleRelevantChunksAreNormalizedByIdealRanking() {
        EvalCase evalCase = evidenceCase("Naive UI", "UnoCSS");
        List<SearchResult> results = List.of(decoy(), chunk("m1", "UI 组件库使用 Naive UI"),
                chunk("m1", "样式方案是 UnoCSS"));

        double expected = (1 / log2(3) + 1 / log2(4)) / (1 + 1 / log2(3));
        assertEquals(expected, RetrievalMetrics.ndcgAtK(results, evalCase, 5), EPS);
    }

    @Test
    void chunkBasedNdcgMatchesResultBasedNdcg() {
        EvalCase evalCase = evidenceCase("Naive UI", "UnoCSS");
        List<SearchResult> results = List.of(chunk("m1", "UI 组件库使用 Naive UI"),
                chunk("m1", "样式方案是 UnoCSS"), chunk("m1", "无关内容"));
        List<EvalCaseResult.RetrievedChunk> chunks = List.of(retrieved(1, true), retrieved(2, true), retrieved(3, false));

        assertEquals(RetrievalMetrics.ndcgAtK(results, evalCase, 5), RetrievalMetrics.ndcgAtK(chunks, 5), EPS);
        assertEquals(RetrievalMetrics.ndcgAtK(results, evalCase, 1), RetrievalMetrics.ndcgAtK(chunks, 1), EPS);
        assertEquals(0.0, RetrievalMetrics.ndcgAtK((List<EvalCaseResult.RetrievedChunk>) null, 5), EPS);
    }

    // ---- 多证据 coverage ----

    @Test
    void twoEvidenceCaseHittingOneScoresHalfCoverage() {
        EvalCase evalCase = evidenceCase("Naive UI", "UnoCSS");
        List<SearchResult> oneHit = List.of(chunk("m1", "状态管理使用 Pinia"), chunk("m1", "UI 组件库使用 Naive UI"));

        assertEquals(0.5, RetrievalMetrics.expectedDocCoverage(oneHit, evalCase, 5), EPS,
                "两证据题只命中一条 coverage=0.5");
        assertEquals(0.5, RetrievalMetrics.evidenceCoverage(oneHit, evalCase, 5), EPS);
        assertEquals(0.0, RetrievalMetrics.expectedDocCoverage(oneHit, evalCase, 1), EPS, "K 截断后命中的分块不算");

        List<SearchResult> bothHit = List.of(chunk("m1", "UI 组件库使用 Naive UI"), chunk("m1", "样式方案是 UnoCSS"));
        assertEquals(1.0, RetrievalMetrics.expectedDocCoverage(bothHit, evalCase, 5), EPS);
    }

    @Test
    void docOnlyCasesKeepDocumentCoverageSemantics() {
        EvalCase evalCase = new EvalCase("c1", "q", List.of("m1", "missing"), List.of("c.md"), "ref");
        List<SearchResult> results = List.of(result("m1", "a.pdf"), result("m2", "b.pdf"), result("m3", "c.md"));

        assertEquals(2.0 / 3, RetrievalMetrics.expectedDocCoverage(results, evalCase, 5), EPS,
                "没标 expectedEvidence 的用例保持原文档覆盖口径");
    }

    @Test
    void partialCoverageIsNotCountedAsFullHitInSummary() {
        EvalCaseResult partial = labeledResult(List.of(retrieved(1, true)), true, 0.5);
        EvalCaseResult full = labeledResult(List.of(retrieved(1, true), retrieved(2, true)), true, 1.0);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(partial, full));

        assertEquals(1.0, summary.recallAtK(), EPS, "旧字段口径不变：命中过即算 hit");
        assertEquals(0.75, summary.meanExpectedDocCoverage(), EPS);
        assertEquals(0.5, summary.fullCoverageHitRate(), EPS, "coverage=0.5 的用例不再算全命中");
        assertEquals(1.0, summary.ndcgAtK().get("5"), EPS, "nDCG 看排序质量，与 coverage 的完整性口径分开");
    }

    // ---- 检索异常单列 ----

    @Test
    void retrievalFailureCountedInRetrievalErrorRate() {
        EvalCaseResult hit = labeledResult(List.of(retrieved(1, true)), true, 1.0);
        EvalCaseResult miss = labeledResult(List.of(retrieved(1, false)), false, 0.0);
        EvalCaseResult failed = retrievalFailedResult();

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(hit, miss, failed));

        assertEquals(3, summary.totalCases());
        assertEquals(2, summary.labeledCases());
        assertEquals(0.5, summary.recallAtK(), EPS);
        assertEquals(1.0 / 3, summary.retrievalErrorRate(), ROUNDED, "异常用例计入错误率，不从分母消失");
        assertEquals(1L, summary.diagnosisCounts().get("RETRIEVAL_FAILED"));
    }

    @Test
    void allRetrievalFailedRunStillReportsErrorRate() {
        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(
                retrievalFailedResult(), retrievalFailedResult()));

        assertNull(summary.recallAtK(), "没有可计算的标注用例时 recall 为 null");
        assertNull(summary.ndcgAtK());
        assertNull(summary.fullCoverageHitRate());
        assertEquals(1.0, summary.retrievalErrorRate(), EPS, "整轮检索异常时错误率为 1，而不是指标整体消失");
        assertEquals(0, summary.evidenceLabeledCases());
    }

    // ---- K 列表与空数据边界 ----

    @Test
    void summaryReportsNdcgAtDefaultKsThreeFiveTen() {
        EvalCaseResult rank1 = labeledResult(List.of(retrieved(1, true)), true, 1.0);
        EvalCaseResult rank4 = labeledResult(
                List.of(retrieved(1, false), retrieved(2, false), retrieved(3, false), retrieved(4, true)), false, 0.0);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(rank1, rank4));
        Map<String, Double> ndcg = summary.ndcgAtK();

        assertEquals(Set.of("3", "5", "10"), ndcg.keySet(), "默认按 3/5/10 逐个输出");
        assertEquals(0.5, ndcg.get("3"), ROUNDED);
        assertEquals((1 + 1 / log2(5)) / 2, ndcg.get("5"), ROUNDED);
        assertEquals((1 + 1 / log2(5)) / 2, ndcg.get("10"), ROUNDED);
    }

    @Test
    void ndcgKsConfigurableFromProperties() {
        assertEquals(List.of(3, 5, 10), new RagEvalProperties().getNdcgKs(), "默认 K 支持 3/5/10");

        RagEvalProperties properties = new RagEvalProperties();
        properties.setNdcgKs(List.of(10, 3, 3));
        EvalCaseResult rank1 = labeledResult(List.of(retrieved(1, true)), true, 1.0);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(rank1), properties.getNdcgKs());
        assertEquals(Set.of("3", "10"), summary.ndcgAtK().keySet(), "K 去重升序输出");
        assertEquals(1.0, summary.ndcgAtK().get("3"), EPS);
        assertEquals(1.0, summary.ndcgAtK().get("10"), EPS);
    }

    @Test
    void summaryMetricsNullWhenNothingMeasurable() {
        EvalReport.EvalSummary empty = EvalReportAggregator.summarize(List.of());
        assertNull(empty.recallAtK());
        assertNull(empty.ndcgAtK());
        assertNull(empty.fullCoverageHitRate());
        assertNull(empty.retrievalErrorRate());

        EvalCaseResult notLabeled = new EvalCaseResult("c", "q", "ref", List.of(), List.of(), List.of(), "NONE",
                List.of(), null, null, null, null, null, "answer", 5, true, "ok", null, EvalDiagnosis.NOT_LABELED,
                false, null, null, 1L);
        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(notLabeled));

        assertNull(summary.ndcgAtK());
        assertNull(summary.fullCoverageHitRate());
        assertEquals(0.0, summary.retrievalErrorRate(), EPS, "无异常时错误率为 0 而不是 null");
    }

    // ---- fixtures ----

    private static EvalCase evidenceCase(String... evidence) {
        return new EvalCase("c1", "q", List.of(), List.of("a.pdf"), "ref", List.of(evidence));
    }

    private static EvalCaseResult labeledResult(List<EvalCaseResult.RetrievedChunk> retrieved, boolean hit,
                                                Double coverage) {
        return new EvalCaseResult("c", "q", "ref", List.of(), List.of("a.pdf"), List.of("Naive UI", "UnoCSS"),
                "HYBRID", retrieved, hit, hit, hit ? 1 : null, hit ? 1.0 : 0.0, coverage,
                "answer", null, null, null, null, hit ? EvalDiagnosis.OK : EvalDiagnosis.RETRIEVAL_MISS,
                false, null, null, 1L);
    }

    private static EvalCaseResult retrievalFailedResult() {
        return new EvalCaseResult("c", "q", "ref", List.of(), List.of("a.pdf"), List.of("Naive UI"), "NONE",
                List.of(), null, null, null, null, null, null, null, null, null, null,
                EvalDiagnosis.RETRIEVAL_FAILED, false, null, "检索失败: es 连接超时", 5L);
    }

    private static EvalCaseResult.RetrievedChunk retrieved(int rank, boolean relevant) {
        return new EvalCaseResult.RetrievedChunk(rank, "md5-1", "a.pdf", 1, 0.9, relevant);
    }

    private static SearchResult chunk(String md5, String text) {
        return new SearchResult(md5, 1, text, 0.9, "a.pdf");
    }

    private static SearchResult decoy() {
        return new SearchResult("m9", 1, "无关内容", 0.7, "b.pdf");
    }

    private static SearchResult result(String md5, String fileName) {
        return new SearchResult(md5, 1, "text", 0.9, fileName);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
