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
 *       未标注时为 null（不参与拒答判定）；</li>
 *   <li>{@code expectedChunkIds}：分块级 gold，可空。与 {@code expectedFileNames} 平行的数组，
 *       第 i 个元素是第 i 个期望文件里答案所在分块的 chunkId（chunkId 是文件内序号，跨文件会重复，
 *       必须与文件配对才有意义）。标注后检索命中按 chunkId 判定（见 {@link RetrievalMetrics}），
 *       优先于证据短语；未标注或与文件数不一致时回退证据短语判定（{@link #goldFallback()}=phrase）。</li>
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
        Boolean expectedRefusal,
        /* v2：分块级 gold，与 expectedFileNames 平行；null/缺省归一化为空列表（未标注，回退短语口径） */
        List<String> expectedChunkIds
) {

    public static final String SPLIT_DEV = "dev";
    public static final String SPLIT_TEST = "test";
    /**
     * goldFallback 标记值：分块级 gold 未标注（或与 expectedFileNames 失配不可用）且有证据短语时，
     * 命中判定按证据短语进行，用例以 {@code goldFallback=phrase} 标注这一口径。
     */
    public static final String GOLD_FALLBACK_PHRASE = "phrase";
    private static final Set<String> VALID_SPLITS = Set.of(SPLIT_DEV, SPLIT_TEST);
    private static final int MIN_DIFFICULTY = 1;
    private static final int MAX_DIFFICULTY = 3;

    public EvalCase {
        expectedFileMd5s = expectedFileMd5s == null ? List.of() : List.copyOf(expectedFileMd5s);
        expectedFileNames = expectedFileNames == null ? List.of() : List.copyOf(expectedFileNames);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
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

    /**
     * 兼容 v2 结构的 10 字段构造（expectedChunkIds 引入前的完整形态）：分块级 gold 未标注。
     * 新代码请用 11 字段完整构造。
     */
    public EvalCase(String id, String question, List<String> expectedFileMd5s, List<String> expectedFileNames,
                    String referenceAnswer, List<String> expectedEvidence, String split, String type,
                    Integer difficulty, Boolean expectedRefusal) {
        this(id, question, expectedFileMd5s, expectedFileNames, referenceAnswer, expectedEvidence, split, type,
                difficulty, expectedRefusal, null);
    }

    /**
     * 分块级 gold 是否标注且可用：非空且与 {@code expectedFileNames} 等长（逐文件对位）。
     * 长度失配时解析器会 warn，标注按不可用处理，命中判定回退证据短语。
     */
    public boolean hasUsableChunkGold() {
        return !expectedChunkIds.isEmpty() && expectedChunkIds.size() == expectedFileNames.size();
    }

    public boolean hasExpectedChunkIds() {
        return !expectedChunkIds.isEmpty();
    }

    public boolean hasExpectedDocs() {
        return !expectedFileMd5s.isEmpty() || !expectedFileNames.isEmpty();
    }

    public boolean hasExpectedEvidence() {
        return hasExpectedDocs() && !expectedEvidence.isEmpty();
    }

    /**
     * 该用例的命中判定口径标记：分块级 gold 不可用（未标注或长度失配）且标注了证据短语时返回
     * {@code phrase}（goldFallback=phrase，命中按证据短语判定）；分块级 gold 可用或没有证据短语
     * （文件级判定，无短语可回退）时返回 null。检索结果整体未带 chunkId 的运行期回退见
     * {@link RetrievalMetrics#goldFallback(EvalCase, List, int)}。
     */
    public String goldFallback() {
        return !hasUsableChunkGold() && hasExpectedEvidence() ? GOLD_FALLBACK_PHRASE : null;
    }

    /** 是否标注为应拒答题（expectedRefusal=true） */
    public boolean expectsRefusal() {
        return Boolean.TRUE.equals(expectedRefusal);
    }
}
