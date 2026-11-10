package com.mindbase.service.eval;

import java.util.List;

/**
 * 单条用例的评测明细。v2 起报告保留全量明细（问题、回答、检索片段正文、评委理由、claim 级判定、
 * 生成与引用指标），第三方可以不看系统、只看报告复核每条结论。
 */
public record EvalCaseResult(
        String caseId,
        String question,
        String referenceAnswer,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        List<String> expectedEvidence,
        /* 检索 */
        String retrievalMode,
        List<RetrievedChunk> retrieved,
        /* 标注了 expectedEvidence 时按分块判断，否则按文件判断 */
        Boolean retrievalHit,
        /* 只按文件判断的命中，用来和分块级命中对比 */
        Boolean docHit,
        Integer firstRelevantRank,
        Double reciprocalRank,
        Double expectedDocCoverage,
        /* 生成 */
        String answer,
        /* 评委 */
        Integer judgeScore,
        Boolean judgeCorrect,
        String judgeReason,
        String judgeRawOutput,
        /* 结论 */
        EvalDiagnosis diagnosis,
        boolean needsHumanReview,
        String reviewReason,
        String error,
        long latencyMs,
        /* v2 全量明细：生成与评委实际看到的片段正文（rank 对应 retrieved 里的同名条目），用于复核回答是否有据 */
        List<RetrievedText> retrievedText,
        /* v2 claim 级判定（verdict 取 supported / unsupported / unverified），由生成与引用指标任务开始填充；未产出时为 null */
        List<ClaimVerdict> claimVerdicts,
        /* v2 本例 LLM token 用量；用例执行侧尚未记录时为 null，报告层仍保留字段 */
        TokenUsage tokenUsage,
        /* v2 react 链路明细：mode=react 时记录 ReAct 轮次、工具调用次数与引用编号装配结果；rag 模式不产出，为 null */
        ReactTrace reactTrace,
        /* v2 生成与引用指标（refusal/faithfulness/citation，口径见 GenerationMetrics）：回答产出后由用例
           执行侧填充；回答未产出（检索/生成失败）时为 null，不参与生成侧分母 */
        GenerationMetrics.Result generation
) {

    public record RetrievedChunk(int rank, String fileMd5, String fileName, Integer chunkId, Double score, boolean relevant) {
    }

    /** 检索片段正文，按 rank 与 {@link RetrievedChunk} 对应 */
    public record RetrievedText(int rank, String text) {
    }

    /**
     * 单条 claim 的判定。verdict 取值：supported（有据）/ unsupported（无据或与证据矛盾）/ unverified（未能判定）
     */
    public record ClaimVerdict(String claim, String verdict, String reason) {
    }

    /**
     * 本例 LLM 用量（生成 + 评委合并口径）；来源缺失的维度为 null。
     * <p>
     * note 记录用量的完整度：两侧（生成/评委）都有用量时为 null；缺失一侧时标 partial 并说明只计了哪一侧，
     * 禁止用字符数估算缺失侧的 token（见 {@link #merged}）。
     */
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens, String note) {

        /** 评委 usage 不可得（评委调用失败/输出为空/供应商未回填 usage）：用例只记录生成侧用量并标 partial */
        public static final String PARTIAL_JUDGE_MISSING =
                "partial: judge usage unavailable, counted generation side only";
        /** 生成 usage 不可得：用例只记录评委侧用量并标 partial */
        public static final String PARTIAL_GENERATION_MISSING =
                "partial: generation usage unavailable, counted judge side only";

        /** 兼容旧调用方的 3 字段构造：note 为 null（两侧用量齐全或未记录完整度说明） */
        public TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this(promptTokens, completionTokens, totalTokens, null);
        }

        /**
         * 生成与评委两侧用量合并（评测成本记账）：评委 usage 缺失时只计生成侧并标 partial，
         * 反之亦然；两侧齐全时 note 为 null。任何一侧都没有记录时返回 null。
         */
        public static TokenUsage merged(TokenUsage generation, TokenUsage judge) {
            if (generation == null && judge == null) {
                return null;
            }
            if (generation == null) {
                return new TokenUsage(judge.promptTokens(), judge.completionTokens(), judge.totalTokens(),
                        PARTIAL_GENERATION_MISSING);
            }
            if (judge == null) {
                return new TokenUsage(generation.promptTokens(), generation.completionTokens(), generation.totalTokens(),
                        PARTIAL_JUDGE_MISSING);
            }
            Integer prompt = add(generation.promptTokens(), judge.promptTokens());
            Integer completion = add(generation.completionTokens(), judge.completionTokens());
            Integer total = prompt != null && completion != null
                    ? prompt + completion
                    : add(generation.totalTokens(), judge.totalTokens());
            return new TokenUsage(prompt, completion, total, null);
        }

        private static Integer add(Integer left, Integer right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return left + right;
        }
    }

    /**
     * react 链路明细（mode=react 时产出）：生成走了生产 ChatPipeline 的 ReAct 循环，
     * 轮次/工具调用次数与引用编号装配结果可在报告里直接复核
     */
    public record ReactTrace(
            /* 模型回合数：含工具轮与最终回答轮（轮次用尽后的强制收尾轮也计入） */
            int modelRounds,
            /* 模型返回了 tool_calls 并执行工具的轮数 */
            int toolCallRounds,
            /* 实际执行的工具调用总次数（超出预算未执行的不计） */
            int toolCalls,
            /* 引用装配产出的引用编号，与回答中的 [N] 对应 */
            List<Integer> referenceNumbers,
            /* 引用编号对应的检索明细（rank=引用编号），relevant 按用例标注判断 */
            List<RetrievedChunk> references
    ) {
    }

    /**
     * 兼容旧调用方的 23 字段构造：v2 明细字段（retrievedText/claimVerdicts/tokenUsage/reactTrace/generation）留空。
     * 调用方接入全量明细后应改用完整构造，不要长期停留在本构造上。
     */
    public EvalCaseResult(String caseId, String question, String referenceAnswer, List<String> expectedFileMd5s,
                          List<String> expectedFileNames, List<String> expectedEvidence, String retrievalMode,
                          List<RetrievedChunk> retrieved, Boolean retrievalHit, Boolean docHit, Integer firstRelevantRank,
                          Double reciprocalRank, Double expectedDocCoverage, String answer, Integer judgeScore,
                          Boolean judgeCorrect, String judgeReason, String judgeRawOutput, EvalDiagnosis diagnosis,
                          boolean needsHumanReview, String reviewReason, String error, long latencyMs) {
        this(caseId, question, referenceAnswer, expectedFileMd5s, expectedFileNames, expectedEvidence, retrievalMode,
                retrieved, retrievalHit, docHit, firstRelevantRank, reciprocalRank, expectedDocCoverage, answer,
                judgeScore, judgeCorrect, judgeReason, judgeRawOutput, diagnosis, needsHumanReview, reviewReason,
                error, latencyMs, null, null, null, null, null);
    }

    /**
     * 兼容 v2 明细构造（27 字段）：generation 指标留空。生成与引用指标接入后应改用完整构造。
     */
    public EvalCaseResult(String caseId, String question, String referenceAnswer, List<String> expectedFileMd5s,
                          List<String> expectedFileNames, List<String> expectedEvidence, String retrievalMode,
                          List<RetrievedChunk> retrieved, Boolean retrievalHit, Boolean docHit, Integer firstRelevantRank,
                          Double reciprocalRank, Double expectedDocCoverage, String answer, Integer judgeScore,
                          Boolean judgeCorrect, String judgeReason, String judgeRawOutput, EvalDiagnosis diagnosis,
                          boolean needsHumanReview, String reviewReason, String error, long latencyMs,
                          List<RetrievedText> retrievedText, List<ClaimVerdict> claimVerdicts,
                          TokenUsage tokenUsage, ReactTrace reactTrace) {
        this(caseId, question, referenceAnswer, expectedFileMd5s, expectedFileNames, expectedEvidence, retrievalMode,
                retrieved, retrievalHit, docHit, firstRelevantRank, reciprocalRank, expectedDocCoverage, answer,
                judgeScore, judgeCorrect, judgeReason, judgeRawOutput, diagnosis, needsHumanReview, reviewReason,
                error, latencyMs, retrievedText, claimVerdicts, tokenUsage, reactTrace, null);
    }

    public boolean judged() {
        return judgeScore != null && judgeCorrect != null;
    }
}
