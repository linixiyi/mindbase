package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.config.RagEvalProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 评委模型与人工判定的一致性。只比较评委和人工都给出了 correct 的用例：
 * <ul>
 *     <li>agreementRate：两边 correct 结论相同的比例；</li>
 *     <li>cohensKappa：扣掉"碰巧一致"后的一致性，κ = (po - pe) / (1 - pe)，pe 由两边各自的判对比例算出；
 *     两边全判同一类时 pe = 1，κ 无定义，记为 null；</li>
 *     <li>meanAbsoluteScoreDiff：人工也打了 1-5 分的用例上，评委分与人工分的平均绝对差。</li>
 * </ul>
 * 一致率（或配置了下限的 κ）低于阈值时 status = NEEDS_RECALIBRATION，说明评分标准要改，或者评委模型已经不可靠。
 */
public record JudgeHumanAgreement(
        Status status,
        String warning,
        int reviewedCases,
        int comparableCases,
        int agreedCases,
        Double agreementRate,
        Double cohensKappa,
        Double judgeCorrectRate,
        Double humanCorrectRate,
        int scoredCases,
        Double meanAbsoluteScoreDiff,
        int humanOnlyCases,
        List<String> disagreementCaseIds,
        double minAgreementRate,
        Double minKappa,
        int minComparableCases
) {

    private static final int MAX_LISTED_DISAGREEMENTS = 50;

    public enum Status {
        /** 还没有人工判定 */
        NO_REVIEWS,
        /** 可比较的用例太少，不下结论 */
        INSUFFICIENT_SAMPLE,
        OK,
        /** 评委与人工一致性低于阈值，评分标准或评委模型需要重新校准 */
        NEEDS_RECALIBRATION
    }

    /**
     * 报告里单条用例的评委结论（评委调用失败或输出解析失败时 judgeCorrect 为 null）
     */
    public record JudgeLabel(String caseId, Boolean judgeCorrect, Integer judgeScore) {
    }

    /**
     * 校准集单条样本：评委输入（question/referenceAnswer/candidateAnswer）与人工标注结论（humanCorrect/humanScore）。
     * source 是样本在评测数据集里的原始条目 id，便于回溯；新建样本可缺省。
     */
    public record CalibrationCase(String id, String source, String question, String referenceAnswer,
                                  String candidateAnswer, boolean humanCorrect, Integer humanScore) {

        /** 转成一致率计算用的人工判定 */
        public HumanVerdict toHumanVerdict() {
            return new HumanVerdict(id, humanCorrect, humanScore, "calibration-v1", "calibration", null);
        }
    }

    /**
     * 加载校准集 JSONL：每行一个 {@link CalibrationCase}，空行与 # / // 开头的注释行跳过。
     * 解析或字段校验失败直接抛 IllegalArgumentException（带行号）：校准门禁不允许静默丢样本。
     */
    public static List<CalibrationCase> loadCalibrationCases(Reader reader, ObjectMapper mapper) throws IOException {
        List<CalibrationCase> cases = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            int lineNo = 0;
            while ((line = buffered.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                cases.add(parseCalibrationLine(trimmed, lineNo, mapper, seenIds));
            }
        }
        return List.copyOf(cases);
    }

    private static CalibrationCase parseCalibrationLine(String line, int lineNo, ObjectMapper mapper,
                                                        Set<String> seenIds) {
        try {
            JsonNode node = mapper.readTree(line);
            String id = requiredText(node, "id", lineNo);
            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("校准集 id 重复: " + id);
            }
            boolean humanCorrect = node.path("humanCorrect").isBoolean()
                    ? node.path("humanCorrect").asBoolean()
                    : fail(lineNo, "humanCorrect 必须是布尔值");
            JsonNode scoreNode = node.path("humanScore");
            Integer humanScore = null;
            if (!scoreNode.isMissingNode() && !scoreNode.isNull()) {
                if (!scoreNode.isInt() || scoreNode.asInt() < 1 || scoreNode.asInt() > 5) {
                    throw new IllegalArgumentException("校准集第 " + lineNo + " 行 humanScore 必须在 1-5 之间");
                }
                humanScore = scoreNode.asInt();
            }
            return new CalibrationCase(id,
                    node.path("source").isMissingNode() ? null : node.path("source").asText(null),
                    requiredText(node, "question", lineNo),
                    requiredText(node, "referenceAnswer", lineNo),
                    requiredText(node, "candidateAnswer", lineNo),
                    humanCorrect,
                    humanScore);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("校准集第 " + lineNo + " 行不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field, int lineNo) {
        String text = node.path(field).asText("");
        if (text.isBlank()) {
            throw new IllegalArgumentException("校准集第 " + lineNo + " 行缺少字段 " + field);
        }
        return text.trim();
    }

    private static boolean fail(int lineNo, String message) {
        throw new IllegalArgumentException("校准集第 " + lineNo + " 行 " + message);
    }

    public static JudgeHumanAgreement compute(List<JudgeLabel> judgeLabels,
                                              Map<String, HumanVerdict> humanVerdicts,
                                              RagEvalProperties.HumanAgreement config) {
        int comparable = 0;
        int agreed = 0;
        int judgeCorrect = 0;
        int humanCorrect = 0;
        int scored = 0;
        int scoreDiffSum = 0;
        int humanOnly = 0;
        List<String> disagreements = new ArrayList<>();
        for (JudgeLabel label : judgeLabels) {
            HumanVerdict human = humanVerdicts.get(label.caseId());
            if (human == null || human.correct() == null) {
                continue;
            }
            if (label.judgeCorrect() == null) {
                humanOnly++;
                continue;
            }
            comparable++;
            boolean j = label.judgeCorrect();
            boolean h = human.correct();
            judgeCorrect += j ? 1 : 0;
            humanCorrect += h ? 1 : 0;
            if (j == h) {
                agreed++;
            } else if (disagreements.size() < MAX_LISTED_DISAGREEMENTS) {
                disagreements.add(label.caseId());
            }
            if (human.score() != null && label.judgeScore() != null) {
                scored++;
                scoreDiffSum += Math.abs(human.score() - label.judgeScore());
            }
        }

        Double agreementRate = ratio(agreed, comparable);
        Double kappa = comparable == 0 ? null : kappa(comparable, agreed, judgeCorrect, humanCorrect);
        Double meanScoreDiff = scored == 0 ? null : (double) scoreDiffSum / scored;
        Status status = status(humanVerdicts.size(), comparable, agreementRate, kappa, config);
        return new JudgeHumanAgreement(status, warning(status, comparable, agreementRate, kappa, config),
                humanVerdicts.size(), comparable, agreed, agreementRate, kappa,
                ratio(judgeCorrect, comparable), ratio(humanCorrect, comparable),
                scored, meanScoreDiff, humanOnly, List.copyOf(disagreements),
                config.getMinAgreementRate(), config.getMinKappa(), config.getMinComparableCases());
    }

    static Double kappa(int n, int agreed, int judgeCorrect, int humanCorrect) {
        double po = (double) agreed / n;
        double pJudge = (double) judgeCorrect / n;
        double pHuman = (double) humanCorrect / n;
        double pe = pJudge * pHuman + (1 - pJudge) * (1 - pHuman);
        if (Math.abs(1 - pe) < 1e-12) {
            return null;
        }
        return (po - pe) / (1 - pe);
    }

    private static Status status(int reviewed, int comparable, Double agreementRate, Double kappa,
                                 RagEvalProperties.HumanAgreement config) {
        if (reviewed == 0) {
            return Status.NO_REVIEWS;
        }
        if (comparable < Math.max(config.getMinComparableCases(), 1)) {
            return Status.INSUFFICIENT_SAMPLE;
        }
        boolean lowAgreement = agreementRate != null && agreementRate < config.getMinAgreementRate();
        boolean lowKappa = config.getMinKappa() != null && kappa != null && kappa < config.getMinKappa();
        return lowAgreement || lowKappa ? Status.NEEDS_RECALIBRATION : Status.OK;
    }

    private static String warning(Status status, int comparable, Double agreementRate, Double kappa,
                                  RagEvalProperties.HumanAgreement config) {
        return switch (status) {
            case NO_REVIEWS, OK -> null;
            case INSUFFICIENT_SAMPLE -> String.format(Locale.ROOT,
                    "评委和人工都给出结论的用例只有 %d 条（至少需要 %d 条），一致率暂不下结论",
                    comparable, config.getMinComparableCases());
            case NEEDS_RECALIBRATION -> String.format(Locale.ROOT,
                    "评委与人工一致率 %s（阈值 %s），Cohen's kappa %s%s。评分标准或评委模型需要重新校准，"
                            + "本次评测的准确率和平均分不能直接采信，先看 disagreementCaseIds 里的分歧用例",
                    percent(agreementRate), percent(config.getMinAgreementRate()),
                    kappa == null ? "无定义" : String.format(Locale.ROOT, "%.2f", kappa),
                    config.getMinKappa() == null ? "" : String.format(Locale.ROOT, "（下限 %.2f）", config.getMinKappa()));
        };
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
