package com.mindbase.service.eval;

import com.mindbase.config.RagEvalProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 汇总单条结果为数据集级指标：
 * Recall@K、MRR 与 nDCG@K 只在标注了期望文档的用例上计算；平均分与准确率只在评委成功打分的用例上计算。
 * 检索调用异常的用例单列 retrievalErrorRate（分母为全部用例），不混入 recall/nDCG 的分母。
 * 生成与引用指标（拒答正确率、faithfulness、citation precision/recall）按 {@link GenerationMetrics.Result}
 * 在产出回答的用例上计算，口径见 {@link GenerationMetrics}。
 * <p>
 * 统计口径（v2）：比例指标配 {@link EvalStatistics#wilsonInterval} 置信区间，
 * 两次评测的同题对比走 {@link #compareRuns}（McNemar 精确检验 + 配对 bootstrap），
 * 统计实现见 {@link EvalStatistics}。
 */
public final class EvalReportAggregator {

    private static final Set<EvalDiagnosis> FAILURES =
            EnumSet.of(EvalDiagnosis.RETRIEVAL_FAILED, EvalDiagnosis.GENERATION_FAILED, EvalDiagnosis.JUDGE_FAILED);

    /** 覆盖率按 double 除法得出，与 1 比较时容忍浮点误差 */
    private static final double COVERAGE_EPSILON = 1e-9;

    /** compareRuns 默认配对 bootstrap：重采样次数与固定种子（同一批输入得到同一区间，可复现） */
    private static final int DEFAULT_BOOTSTRAP_ITERATIONS = 2000;
    private static final long DEFAULT_BOOTSTRAP_SEED = 20261109L;

    private EvalReportAggregator() {
    }

    /** K 取 {@link RagEvalProperties#DEFAULT_NDCG_KS}（3/5/10） */
    public static EvalReport.EvalSummary summarize(List<EvalCaseResult> results) {
        return summarize(results, RagEvalProperties.DEFAULT_NDCG_KS);
    }

    /**
     * @param ndcgKs nDCG@K 的截断 K 列表；去重、过滤非法值后升序逐个输出
     */
    public static EvalReport.EvalSummary summarize(List<EvalCaseResult> results, List<Integer> ndcgKs) {
        int labeled = 0;
        int hits = 0;
        int docHits = 0;
        int evidenceLabeled = 0;
        double rrSum = 0d;
        double coverageSum = 0d;
        int fullCoverageHits = 0;
        int judged = 0;
        int correct = 0;
        double scoreSum = 0d;
        int review = 0;
        int failed = 0;
        int retrievalErrors = 0;
        /* 生成与引用指标的累计量：拒答口径按用例计数，faithfulness/citation 按可计算的用例取平均 */
        int refusalLabeled = 0;
        int refusalCorrectCases = 0;
        int refusalExpected = 0;
        int refusedAsExpected = 0;
        double faithfulnessSum = 0d;
        int faithfulnessCases = 0;
        double citationPrecisionSum = 0d;
        int citationPrecisionCases = 0;
        double citationRecallSum = 0d;
        int citationRecallCases = 0;
        List<EvalCaseResult> labeledResults = new ArrayList<>();
        Map<String, Long> modes = new TreeMap<>();
        Map<String, Long> diagnoses = new LinkedHashMap<>();
        for (EvalDiagnosis diagnosis : EvalDiagnosis.values()) {
            diagnoses.put(diagnosis.name(), 0L);
        }

        for (EvalCaseResult result : results) {
            if (result.retrievalHit() != null) {
                labeled++;
                labeledResults.add(result);
                hits += result.retrievalHit() ? 1 : 0;
                docHits += Boolean.TRUE.equals(result.docHit()) ? 1 : 0;
                evidenceLabeled += result.expectedEvidence() != null && !result.expectedEvidence().isEmpty() ? 1 : 0;
                rrSum += result.reciprocalRank() == null ? 0d : result.reciprocalRank();
                Double coverage = result.expectedDocCoverage();
                if (coverage != null) {
                    coverageSum += coverage;
                    // 全命中按覆盖率判定：多证据题只命中一部分（coverage<1）不再算全命中
                    fullCoverageHits += coverage >= 1d - COVERAGE_EPSILON ? 1 : 0;
                }
            }
            if (result.judged()) {
                judged++;
                scoreSum += result.judgeScore();
                correct += result.judgeCorrect() ? 1 : 0;
            }
            GenerationMetrics.Result generation = result.generation();
            if (generation != null) {
                if (generation.refusalCorrect() != null) {
                    refusalLabeled++;
                    refusalCorrectCases += generation.refusalCorrect() ? 1 : 0;
                }
                if (generation.refusalExpected()) {
                    refusalExpected++;
                    refusedAsExpected += Integer.valueOf(1).equals(generation.refusal()) ? 1 : 0;
                }
                if (generation.faithfulness() != null) {
                    faithfulnessSum += generation.faithfulness();
                    faithfulnessCases++;
                }
                if (generation.citationPrecision() != null) {
                    citationPrecisionSum += generation.citationPrecision();
                    citationPrecisionCases++;
                }
                if (generation.citationRecall() != null) {
                    citationRecallSum += generation.citationRecall();
                    citationRecallCases++;
                }
            }
            review += result.needsHumanReview() ? 1 : 0;
            if (result.diagnosis() != null) {
                failed += FAILURES.contains(result.diagnosis()) ? 1 : 0;
                retrievalErrors += result.diagnosis() == EvalDiagnosis.RETRIEVAL_FAILED ? 1 : 0;
                diagnoses.merge(result.diagnosis().name(), 1L, Long::sum);
            }
            modes.merge(result.retrievalMode() == null ? "NONE" : result.retrievalMode(), 1L, Long::sum);
        }

        return new EvalReport.EvalSummary(
                results.size(),
                labeled,
                hits,
                ratio(hits, labeled),
                ratio(rrSum, labeled),
                ratio(docHits, labeled),
                evidenceLabeled,
                ratio(coverageSum, labeled),
                judged,
                correct,
                ratio(scoreSum, judged),
                ratio(correct, judged),
                review,
                failed,
                modes,
                diagnoses,
                ndcgByK(labeledResults, ndcgKs),
                ratio(fullCoverageHits, labeled),
                ratio(retrievalErrors, results.size()),
                refusalLabeled,
                refusalCorrectCases,
                ratio(refusalCorrectCases, refusalLabeled),
                refusalExpected,
                refusedAsExpected,
                ratio(faithfulnessSum, faithfulnessCases),
                ratio(citationPrecisionSum, citationPrecisionCases),
                ratio(citationRecallSum, citationRecallCases));
    }

    /**
     * 数据集级 nDCG@K：每个 K 一项（键为 K 值），按用例明细里记录的分块相关性计算，与 Recall@K 同一分母
     * （只算标注了期望文档的用例）。没有可计算样本或未配置 K 时为 null。
     */
    private static Map<String, Double> ndcgByK(List<EvalCaseResult> labeledResults, List<Integer> ks) {
        if (labeledResults.isEmpty() || ks == null || ks.isEmpty()) {
            return null;
        }
        List<Integer> validKs = ks.stream().filter(k -> k >= 1).distinct().sorted().toList();
        if (validKs.isEmpty()) {
            return null;
        }
        Map<String, Double> ndcgAtK = new LinkedHashMap<>();
        for (Integer k : validKs) {
            double sum = 0d;
            for (EvalCaseResult result : labeledResults) {
                sum += RetrievalMetrics.ndcgAtK(result.retrieved(), k);
            }
            ndcgAtK.put(String.valueOf(k), ratio(sum, labeledResults.size()));
        }
        return ndcgAtK;
    }

    /**
     * 准确率（评委判对占比）的 Wilson 95% 置信区间，与 {@link EvalReport.EvalSummary#accuracy()} 同一分母
     * （评委成功打分的用例）。没有可计算样本时返回 null，不编数字。
     */
    public static EvalStatistics.Interval accuracyWilsonInterval(List<EvalCaseResult> results) {
        int judged = 0;
        int correct = 0;
        for (EvalCaseResult result : results) {
            if (result != null && result.judged()) {
                judged++;
                correct += result.judgeCorrect() ? 1 : 0;
            }
        }
        return EvalStatistics.wilsonInterval(correct, judged);
    }

    /**
     * 同一批题两次评测的配对比较（默认 2000 次重采样、固定种子），详见 {@link #compareRuns(List, List, int, long)}。
     */
    public static PairedRunComparison compareRuns(List<EvalCaseResult> baseline, List<EvalCaseResult> variant) {
        return compareRuns(baseline, variant, DEFAULT_BOOTSTRAP_ITERATIONS, DEFAULT_BOOTSTRAP_SEED);
    }

    /**
     * 同一批题两次评测的配对比较：二元口径（judgeCorrect）走 {@link EvalStatistics#mcnemarExactP}，
     * 分数口径（judgeScore 差值）走 {@link EvalStatistics#pairedBootstrap}。
     * <p>
     * 用例按 caseId 对齐（caseId 缺失时退回 question），只在两边都成功打分的用例上比较；
     * 没配对上的用例不悄悄消失，损失数记录在 unmatched* 字段里。没有任何配对样本时统计量为 null。
     */
    public static PairedRunComparison compareRuns(List<EvalCaseResult> baseline, List<EvalCaseResult> variant,
                                                  int bootstrapIterations, long bootstrapSeed) {
        Map<String, EvalCaseResult> baselineByKey = new LinkedHashMap<>();
        for (EvalCaseResult result : baseline) {
            if (result != null) {
                baselineByKey.putIfAbsent(pairingKey(result), result);
            }
        }
        Map<String, EvalCaseResult> variantByKey = new LinkedHashMap<>();
        for (EvalCaseResult result : variant) {
            if (result != null) {
                variantByKey.putIfAbsent(pairingKey(result), result);
            }
        }

        int bothCorrect = 0;
        int bothWrong = 0;
        int baselineOnlyWins = 0;
        int variantOnlyWins = 0;
        List<double[]> scorePairs = new ArrayList<>();
        for (Map.Entry<String, EvalCaseResult> entry : baselineByKey.entrySet()) {
            EvalCaseResult other = variantByKey.get(entry.getKey());
            if (other == null) {
                continue;
            }
            EvalCaseResult base = entry.getValue();
            if (!base.judged() || !other.judged()) {
                continue;
            }
            boolean baseCorrect = base.judgeCorrect();
            boolean variantCorrect = other.judgeCorrect();
            if (baseCorrect && !variantCorrect) {
                baselineOnlyWins++;
            } else if (!baseCorrect && variantCorrect) {
                variantOnlyWins++;
            } else if (baseCorrect) {
                bothCorrect++;
            } else {
                bothWrong++;
            }
            scorePairs.add(new double[]{base.judgeScore(), other.judgeScore()});
        }

        int unmatchedBaseline = 0;
        int unmatchedVariant = 0;
        for (EvalCaseResult result : baseline) {
            if (result != null && !variantByKey.containsKey(pairingKey(result))) {
                unmatchedBaseline++;
            }
        }
        for (EvalCaseResult result : variant) {
            if (result != null && !baselineByKey.containsKey(pairingKey(result))) {
                unmatchedVariant++;
            }
        }

        int matchedCases = bothCorrect + bothWrong + baselineOnlyWins + variantOnlyWins;
        Double mcnemarP = matchedCases > 0 ? EvalStatistics.mcnemarExactP(baselineOnlyWins, variantOnlyWins) : null;

        Double deltaMean = null;
        Double deltaLower = null;
        Double deltaUpper = null;
        if (!scorePairs.isEmpty()) {
            double[] baseScores = new double[scorePairs.size()];
            double[] variantScores = new double[scorePairs.size()];
            for (int i = 0; i < scorePairs.size(); i++) {
                baseScores[i] = scorePairs.get(i)[0];
                variantScores[i] = scorePairs.get(i)[1];
            }
            EvalStatistics.BootstrapResult bootstrap =
                    EvalStatistics.pairedBootstrap(baseScores, variantScores, bootstrapIterations, bootstrapSeed);
            deltaMean = bootstrap.meanDelta();
            deltaLower = bootstrap.ciLower();
            deltaUpper = bootstrap.ciUpper();
        }

        return new PairedRunComparison(
                matchedCases,
                bothCorrect + baselineOnlyWins,
                bothCorrect + variantOnlyWins,
                baselineOnlyWins,
                variantOnlyWins,
                mcnemarP,
                deltaMean,
                deltaLower,
                deltaUpper,
                unmatchedBaseline,
                unmatchedVariant);
    }

    /** 配对键：caseId 优先，缺失时退回 question */
    private static String pairingKey(EvalCaseResult result) {
        return result.caseId() != null ? result.caseId() : result.question();
    }

    /**
     * 同一批题两次评测的配对比较结果。没有配对样本的统计量为 null，不编数字；
     * 配不上的用例数显式暴露，口径损失可被复核。
     */
    public record PairedRunComparison(
            int matchedCases,
            /* 配对用例中各自判对的用例数（baseline 对 = bothCorrect + baselineOnlyWins） */
            int baselineCorrect,
            int variantCorrect,
            /* b：仅 baseline 对、variant 错的单向翻转数 */
            int baselineOnlyWins,
            /* c：仅 variant 对、baseline 错的单向翻转数 */
            int variantOnlyWins,
            /* McNemar 精确检验双侧 p；没有配对样本时为 null */
            Double mcnemarP,
            /* judgeScore 差值（variant − baseline）的观测均值与配对 bootstrap 95% 区间；没有配对样本时为 null */
            Double scoreDeltaMean,
            Double scoreDeltaCiLower,
            Double scoreDeltaCiUpper,
            /* 未能配对上的用例数（分母损失显式可见） */
            int unmatchedBaseline,
            int unmatchedVariant
    ) {
    }

    /**
     * 汇总 token 用量：只累计用例侧实际记录了的维度；没有任何用例记录用量时返回 null（报告仍保留字段）。
     * 单条用例 totalTokens 缺失但 prompt/completion 齐全时，该例按两者之和计入。
     */
    public static EvalCaseResult.TokenUsage tokenUsage(List<EvalCaseResult> results) {
        Integer prompt = null;
        Integer completion = null;
        Integer total = null;
        for (EvalCaseResult result : results) {
            EvalCaseResult.TokenUsage usage = result == null ? null : result.tokenUsage();
            if (usage == null) {
                continue;
            }
            prompt = add(prompt, usage.promptTokens());
            completion = add(completion, usage.completionTokens());
            total = add(total, caseTotal(usage));
        }
        if (prompt == null && completion == null && total == null) {
            return null;
        }
        return new EvalCaseResult.TokenUsage(prompt, completion, total);
    }

    private static Integer caseTotal(EvalCaseResult.TokenUsage usage) {
        if (usage.totalTokens() != null) {
            return usage.totalTokens();
        }
        return usage.promptTokens() != null && usage.completionTokens() != null
                ? usage.promptTokens() + usage.completionTokens()
                : null;
    }

    private static Integer add(Integer sum, Integer value) {
        if (value == null) {
            return sum;
        }
        return sum == null ? value : sum + value;
    }

    public static Map<String, String> diagnosisGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        for (EvalDiagnosis diagnosis : EvalDiagnosis.values()) {
            guide.put(diagnosis.name(), diagnosis.description());
        }
        return guide;
    }

    private static Double ratio(double numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round(numerator / denominator * 10000d) / 10000d;
    }
}
