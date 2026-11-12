package com.mindbase.service.eval;

import com.mindbase.config.RagEvalProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * neg 口径（task-0004）：应拒答题（expectedRefusal=true，数据集 type=neg）退出检索指标分母——
 * evidenceLabeledCases、recallAtK、mrr、docRecallAtK、meanExpectedDocCoverage、fullCoverageHitRate
 * 与 nDCG@K 的分母只含非 neg 用例；neg 用例的检索结果单独计数（summary.negExcludedCases）但不计入
 * 命中率（检索到"论文没提这个信息"的证据锚点不是检索成功）。accuracy/refusal/faithfulness/citation
 * 等生成与评委指标仍包含 neg 用例（拒答正确率是生成指标）。summary 新增 retrievalCases 与
 * negExcludedCases 两个计数字段（additive，schemaVersion 保持 2.0），与旧口径对账：
 * labeledCases + negExcludedCases = 旧口径 labeledCases。
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
        /* neg 口径（task-0004）：检索分母（非 neg）与被排除的 neg 用例分别计数，损失可对账 */
        int retrievalCases = 0;
        int negExcludedCases = 0;
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
        /* 评委双采样一致性（task-0011）：一致/相反按用例明细计数，单侧解析失败（judge-unstable）不计入分母 */
        int judgeAgreement = 0;
        int judgeDisagreement = 0;
        boolean judgeRepeatSampled = false;
        List<EvalCaseResult> labeledResults = new ArrayList<>();
        Map<String, Long> modes = new TreeMap<>();
        Map<String, Long> diagnoses = new LinkedHashMap<>();
        for (EvalDiagnosis diagnosis : EvalDiagnosis.values()) {
            diagnoses.put(diagnosis.name(), 0L);
        }

        for (EvalCaseResult result : results) {
            if (result.retrievalHit() != null) {
                if (isNegCase(result)) {
                    // neg 题退出检索分母（task-0004）：单独计数，命中/覆盖率/排序一律不计——
                    // "检索到了应拒答题的证据锚点"不是检索成功，neg 命中率由生成侧拒答口径承担
                    negExcludedCases++;
                } else {
                    labeled++;
                    retrievalCases++;
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
            // 评委双采样一致性计数：相反（judge-disagree）与单侧失败（judge-unstable）由强制复核原因识别，
            // 一致由 judgeReason 末尾的 A/B 轨迹识别（repeat 关闭的普通评审判定不含该轨迹，不计入）
            String reviewReason = result.reviewReason();
            if (RagEvalCaseRunner.REASON_JUDGE_DISAGREE.equals(reviewReason)) {
                judgeDisagreement++;
                judgeRepeatSampled = true;
            } else if (RagEvalCaseRunner.REASON_JUDGE_UNSTABLE.equals(reviewReason)) {
                judgeRepeatSampled = true;
            } else if (result.judged() && result.judgeReason() != null
                    && RagEvalCaseRunner.REPEAT_AGREEMENT_TRACE.matcher(result.judgeReason()).find()) {
                judgeAgreement++;
                judgeRepeatSampled = true;
            }
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
                retrievalCases,
                negExcludedCases,
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
                ratio(citationRecallSum, citationRecallCases),
                judgeRepeatSampled,
                judgeRepeatSampled ? judgeAgreement : null,
                judgeRepeatSampled ? judgeDisagreement : null,
                ratio(judgeAgreement, judgeAgreement + judgeDisagreement));
    }

    /**
     * neg 用例判定（task-0004）：应拒答题（expectedRefusal=true，数据集 type=neg）退出检索指标分母。
     * 判定来源是用例明细的 {@code generation.refusalExpected()}——用例执行侧经 {@link GenerationMetrics}
     * 把数据集标注写入该字段，聚合器从这里识别，不再重复解析数据集。
     * 已知口径损失：回答未产出（检索/生成失败）的用例没有 generation 明细、无法识别 neg——
     * 检索失败的用例本来就不进检索分母；检索成功但生成失败的 neg 用例会留在检索分母，
     * 与普通用例口径一致（数量可从 failedCases/GENERATION_FAILED 侧复核）。
     */
    private static boolean isNegCase(EvalCaseResult result) {
        GenerationMetrics.Result generation = result == null ? null : result.generation();
        return generation != null && generation.refusalExpected();
    }

    /**
     * 数据集级 nDCG@K：每个 K 一项（键为 K 值），按用例明细里记录的分块相关性计算，与 Recall@K 同一分母
     * （只算标注了期望文档且非 neg 的用例）。没有可计算样本或未配置 K 时为 null。
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

    /** 无人工复核数据时不伪造 κ，报告里写明未计算（config.kappaNote 与阻断原因里都会出现） */
    static final String KAPPA_NOT_COMPUTED_NOTE = "kappa 未计算：无人工复核数据，不伪造 κ";

    /**
     * 评委独立性与发布门禁：返回的报告结论写入 config（judgeIndependence/releaseEligible/releaseBlockedReason），
     * releaseEligible=false 表示该报告的评委分数不能用于发布判定。判定规则（命中任一即阻断）：
     * <ol>
     *   <li>评委身份为 {@link RagEvalCaseRunner#SELF_JUDGE}（评委与生成同模型自评；评委身份缺失时同样按
     *       SELF_JUDGE 处理，宁可误拦不可放行）；</li>
     *   <li>κ 可计算且配置了下限（minKappa 非 null）而 κ 低于下限；</li>
     *   <li>可比样本数已知（comparableCases 非 null）且少于 minComparableCases。</li>
     * </ol>
     * 没有人工复核数据时 κ 为 null：不伪造 κ，只按 SELF_JUDGE 规则判定，报告写明 kappa 未计算
     * （见 {@link #releaseGateConfig} 写入的 kappaNote）。minKappa 为 null 表示不启用 κ 检查，
     * 但这不改变其余规则的结论。
     */
    public static EvalReport.ReleaseGate releaseGate(String judgeIdentity, Double kappa, Double minKappa,
                                                     Integer comparableCases, Integer minComparableCases) {
        String independence = judgeIdentity == null || judgeIdentity.isBlank()
                || RagEvalCaseRunner.SELF_JUDGE.equals(judgeIdentity.trim())
                ? RagEvalCaseRunner.SELF_JUDGE
                : RagEvalCaseRunner.INDEPENDENT;
        List<String> reasons = new ArrayList<>();
        if (RagEvalCaseRunner.SELF_JUDGE.equals(independence)) {
            reasons.add("评委与生成共用同一模型（SELF_JUDGE），自评分数不能用于发布判定");
            if (kappa == null) {
                reasons.add(KAPPA_NOT_COMPUTED_NOTE);
            }
        }
        if (kappa != null && minKappa != null && kappa < minKappa) {
            reasons.add(String.format(Locale.ROOT,
                    "评委与人工 Cohen's kappa %.2f 低于下限 %.2f，评委一致性不足", kappa, minKappa));
        }
        if (comparableCases != null && minComparableCases != null && comparableCases < minComparableCases) {
            reasons.add(String.format(Locale.ROOT,
                    "评委与人工可比样本 %d 条少于下限 %d 条，一致性结论不成立", comparableCases, minComparableCases));
        }
        boolean eligible = reasons.isEmpty();
        return new EvalReport.ReleaseGate(independence, eligible, eligible ? null : String.join("；", reasons));
    }

    /**
     * 从报告 config 派生发布门禁三键（judgeIndependence/releaseEligible/releaseBlockedReason）并补 κ 说明。
     * κ 与可比样本输入取 config 同名键（{@link EvalReport} 的 CONFIG_KAPPA 等常量）：
     * 刚跑完报告时还没有人工复核数据，这些键缺失 → κ 记为 null 并写明未计算，只按 SELF_JUDGE 规则判定。
     * 返回的是排序后的新 LinkedHashMap，不改写调用方传入的 map。
     */
    static Map<String, Object> releaseGateConfig(Map<String, Object> config) {
        Map<String, Object> enriched = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        Double kappa = asDouble(enriched.get(EvalReport.CONFIG_KAPPA));
        Double minKappa = asDouble(enriched.get(EvalReport.CONFIG_MIN_KAPPA));
        Integer comparableCases = asInteger(enriched.get(EvalReport.CONFIG_COMPARABLE_CASES));
        Integer minComparableCases = asInteger(enriched.get(EvalReport.CONFIG_MIN_COMPARABLE_CASES));
        EvalReport.ReleaseGate gate = releaseGate(asText(enriched.get(EvalReport.CONFIG_JUDGE)),
                kappa, minKappa, comparableCases, minComparableCases);
        enriched.put(EvalReport.CONFIG_JUDGE_INDEPENDENCE, gate.judgeIndependence());
        enriched.put(EvalReport.CONFIG_RELEASE_ELIGIBLE, gate.releaseEligible());
        enriched.put(EvalReport.CONFIG_RELEASE_BLOCKED_REASON, gate.releaseBlockedReason());
        enriched.put(EvalReport.CONFIG_KAPPA, kappa);
        if (kappa == null) {
            enriched.put(EvalReport.CONFIG_KAPPA_NOTE, KAPPA_NOT_COMPUTED_NOTE);
        }
        return enriched;
    }

    private static String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
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
