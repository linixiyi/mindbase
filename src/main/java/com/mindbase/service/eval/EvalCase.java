package com.mindbase.service.eval;

import java.util.List;
import java.util.Set;

/**
 * 一条评测用例：问题、期望命中的文档（MD5 或文件名，至少给一种才计算检索指标）、参考答案。
 * expectedEvidence 是可选的分块级标注：答案所在分块里一定会出现的关键短语，命中分块包含其中任意一个才算相关；
 * 不填时只按文件判断，同一文件的任何分块都算命中。
 * <p>
 * v2 新增数据集结构字段：
 * <ul>
 *   <li>{@code split}：dev（调优/门禁）或 test（留出集）；旧数据缺省时按 dev 处理（数据集级由
 *       {@link EvalDatasetParser.ParseResult#legacyDataset()} 标注 legacyDataset=true）；</li>
 *   <li>{@code type}：题型标签（如 fact/para/multi/conflict/neg），自由文本，报告可按它分组；</li>
 *   <li>{@code difficulty}：1-3（1 易 / 2 中 / 3 难）；</li>
 *   <li>{@code expectedRefusal}：true 表示该题应拒答（知识库无答案或前提错误），用于拒答正确率口径；
 *       未标注时为 null（不参与拒答判定）。</li>
 * </ul>
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        String referenceAnswer,
        List<String> expectedEvidence,
        /* v2：dev | test；null 在构造时归一化为 dev（旧数据兼容口径） */
        String split,
        /* v2：题型标签，自由文本；可为 null */
        String type,
        /* v2：1-3 难度；可为 null（未标注） */
        Integer difficulty,
        /* v2：应拒答题标注；null 表示未标注 */
        Boolean expectedRefusal
) {

    public static final String SPLIT_DEV = "dev";
    public static final String SPLIT_TEST = "test";
    private static final Set<String> VALID_SPLITS = Set.of(SPLIT_DEV, SPLIT_TEST);
    private static final int MIN_DIFFICULTY = 1;
    private static final int MAX_DIFFICULTY = 3;

    public EvalCase {
        expectedFileMd5s = expectedFileMd5s == null ? List.of() : List.copyOf(expectedFileMd5s);
        expectedFileNames = expectedFileNames == null ? List.of() : List.copyOf(expectedFileNames);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        if (split == null) {
            split = SPLIT_DEV;
        } else {
            split = split.trim();
            if (!VALID_SPLITS.contains(split)) {
                throw new IllegalArgumentException("非法 split（只允许 dev|test）: " + split);
            }
        }
        if (difficulty != null && (difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY)) {
            throw new IllegalArgumentException("非法 difficulty（只允许 1-3）: " + difficulty);
        }
    }

    public EvalCase(String id, String question, List<String> expectedFileMd5s, List<String> expectedFileNames,
                    String referenceAnswer) {
        this(id, question, expectedFileMd5s, expectedFileNames, referenceAnswer, List.of());
    }

    public EvalCase(String id, String question, List<String> expectedFileMd5s, List<String> expectedFileNames,
                    String referenceAnswer, List<String> expectedEvidence) {
        this(id, question, expectedFileMd5s, expectedFileNames, referenceAnswer, expectedEvidence,
                null, null, null, null);
    }

    public boolean hasExpectedDocs() {
        return !expectedFileMd5s.isEmpty() || !expectedFileNames.isEmpty();
    }

    public boolean hasExpectedEvidence() {
        return hasExpectedDocs() && !expectedEvidence.isEmpty();
    }

    /** 是否标注为应拒答题（expectedRefusal=true） */
    public boolean expectsRefusal() {
        return Boolean.TRUE.equals(expectedRefusal);
    }
}
