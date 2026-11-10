package com.mindbase.service.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测报告。label 用来区分同一数据集的多次评测（例如调整分块前后各跑一次）。
 * <p>
 * v2：新增 {@code schemaVersion} 与来源信息（git 提交、ES 别名、embedding 模型、提示词版本），
 * cases 保留全量明细（question/answer/retrievedText/judgeReason/claimVerdicts），
 * 报告可被第三方独立复核；报告文件改为"临时文件 + rename"落盘，中断不会留下半份 JSON。
 * <p>
 * 发布门禁：config 构造时补 judgeIndependence（INDEPENDENT/SELF_JUDGE）、releaseEligible 与
 * releaseBlockedReason，self-judge 或评委一致性不达标的报告不得用于发布判定（scripts/eval_compare.py
 * 在 accuracy/judgeScore 门禁上会因此拦截），判定规则见 {@link EvalReportAggregator#releaseGate}。
 */
public record EvalReport(
        String runId,
        String label,
        String datasetSource,
        int topK,
        Instant startedAt,
        Instant finishedAt,
        /* v2 报告结构版本，读取方据此选择解析方式 */
        String schemaVersion,
        /* 产出本报告的代码版本；环境里没有 .git 或 git 命令时为 unknown，字段保留不省略 */
        String gitCommit,
        /* 检索评测使用的 ES 别名，与线上问答读同一个别名 */
        String esAlias,
        /* 检索索引对应的 embedding 模型版本（provider:model:dimension），与 ES 物理索引的版本命名同源 */
        String embeddingModel,
        /* 生成提示词的版本（生效的 ai.prompt.rules 的 sha256 前 8 位）；规则未配置时为 unknown */
        String promptVersion,
        Map<String, Object> config,
        /* 本次评测 LLM token 用量合计；用例侧尚未记录用量时为 null（字段保留） */
        EvalCaseResult.TokenUsage tokenUsage,
        /* 费用；项目未配置模型单价时 amount 为 null，不做估算以避免编造费用，note 说明取值来源 */
        Cost cost,
        EvalSummary summary,
        Map<String, String> diagnosisGuide,
        List<EvalCaseResult> cases
) {

    public static final String SCHEMA_VERSION = "2.0";

    /* ---- 发布门禁相关 config 键（判定规则见 EvalReportAggregator#releaseGate） ---- */
    /** config 里已有的评委身份键（RagEvalService 写入：SELF_JUDGE 或 provider/model），独立性据此派生 */
    public static final String CONFIG_JUDGE = "judge";
    /** 评委独立性：INDEPENDENT（独立供应商）或 SELF_JUDGE（与生成同模型） */
    public static final String CONFIG_JUDGE_INDEPENDENCE = "judgeIndependence";
    /** 该报告的分数（尤其 accuracy/judgeScore）能否用于发布判定 */
    public static final String CONFIG_RELEASE_ELIGIBLE = "releaseEligible";
    /** releaseEligible=false 时的阻断原因；放行时为 null */
    public static final String CONFIG_RELEASE_BLOCKED_REASON = "releaseBlockedReason";
    /** 评委与人工的 Cohen's kappa；没有人工复核数据时为 null（不伪造，见 kappaNote） */
    public static final String CONFIG_KAPPA = "kappa";
    /** κ 门禁下限；为空表示不启用 κ 检查（但不代表可发布，其余规则照常生效） */
    public static final String CONFIG_MIN_KAPPA = "minKappa";
    /** 评委与人工都给出结论的可比样本数；没有人工复核数据时为 null */
    public static final String CONFIG_COMPARABLE_CASES = "comparableCases";
    /** 可比样本数下限 */
    public static final String CONFIG_MIN_COMPARABLE_CASES = "minComparableCases";
    /** kappa 为 null 时报告里的说明（写明未计算，而不是留空让人猜） */
    public static final String CONFIG_KAPPA_NOTE = "kappaNote";

    /**
     * 构造时即在 config 里落发布门禁三键（judgeIndependence/releaseEligible/releaseBlockedReason）与 κ 说明，
     * 报告一写出就带"这份分数能不能用于发布判定"的结论，第三方读报告无需复算。
     * κ 与可比样本数据来自 config 同名键（有人工复核数据时由重建方写入）；刚跑完还没有人工数据时
     * κ 为 null，此时不伪造 κ，只按 SELF_JUDGE 规则判定并写明 kappa 未计算。
     */
    public EvalReport {
        config = EvalReportAggregator.releaseGateConfig(config);
    }

    /**
     * 发布门禁结论。releaseBlockedReason 汇总全部命中的阻断原因；放行时为 null。
     */
    public record ReleaseGate(String judgeIndependence, boolean releaseEligible, String releaseBlockedReason) {
    }

    /**
     * 费用。amount/currency 只有在配置了模型单价时才有值；note 说明取值来源（当前恒为未定价）。
     */
    public record Cost(Double amount, String currency, String note) {

        public static Cost unpriced() {
            return new Cost(null, null, "unpriced: 模型单价未配置，不做估算");
        }
    }

    /**
     * 汇总指标。没有可计算的样本时对应指标为 null（例如没有任何用例标注期望文档时 recallAtK 为 null）。
     * <p>
     * 检索指标 v2 的三个新字段（ndcgAtK/fullCoverageHitRate/retrievalErrorRate）与旧字段并列输出：
     * recallAtK 等 old 字段的名称与口径保持不变，历史报告对比不受影响。
     * 生成与引用指标（refusalAccuracy/faithfulness/citationPrecision/citationRecall）同为并列新增，
     * 口径见 {@link GenerationMetrics}；answer 未产出的用例不进生成侧分母。
     */
    public record EvalSummary(
            int totalCases,
            int labeledCases,
            int retrievalHits,
            Double recallAtK,
            Double mrr,
            /* 只按文件判断的 Recall@K；evidenceLabeledCases 为 0 时与 recallAtK 相同 */
            Double docRecallAtK,
            /* 标注了 expectedEvidence、按分块判断命中的用例数 */
            int evidenceLabeledCases,
            Double meanExpectedDocCoverage,
            int judgedCases,
            int correctCases,
            Double meanJudgeScore,
            Double accuracy,
            int needsHumanReviewCount,
            int failedCases,
            Map<String, Long> retrievalModeCounts,
            Map<String, Long> diagnosisCounts,
            /* v2 并列新增：数据集级 nDCG@K，键为 K 值（默认 3/5/10），与 recallAtK 同分母；无标注用例或未配置 K 时为 null */
            Map<String, Double> ndcgAtK,
            /* v2 并列新增：按覆盖率判定的全命中率（coverage=1 才算）；多证据题只命中一部分不再计入，与 recallAtK 的"命中过即算"并列对比 */
            Double fullCoverageHitRate,
            /* v2 并列新增：检索调用异常用例占比，分母为全部用例；异常用例单列于此，不从 recall 分母里悄悄消失 */
            Double retrievalErrorRate,
            /* v2 并列新增：标注了 expectedRefusal 的用例数与其中拒答行为符合标注的用例数（含应拒答而拒答、不应拒答而正常回答） */
            int refusalLabeledCases,
            int refusalCorrectCases,
            /* v2 并列新增：拒答正确率（refusalCorrectCases/refusalLabeledCases）；无可判用例时为 null */
            Double refusalAccuracy,
            /* v2 并列新增：标注应拒答（expectedRefusal=true）的用例数与其中实际拒答的用例数 */
            int refusalExpectedCases,
            int refusedAsExpected,
            /* v2 并列新增：claim 级有据率的用例级平均（各例 supported/(supported+unsupported)，unverified 不计入分母）；
               没有任何可判 claim 的用例时为 null */
            Double faithfulness,
            /* v2 并列新增：引用指向相关分块比例的用例级平均；没有任何回答带引用编号时为 null */
            Double citationPrecision,
            /* v2 并列新增：相关分块被引用比例的用例级平均；证据里没有任何相关分块时为 null */
            Double citationRecall
    ) {
    }
}
