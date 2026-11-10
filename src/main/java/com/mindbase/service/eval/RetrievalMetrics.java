package com.mindbase.service.eval;

import com.mindbase.entity.SearchResult;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索指标：
 * - Recall@K（本项目口径）：前 K 条结果里只要出现任一相关分块即记为命中，数据集上取命中率；
 * - 倒数排名 RR：第一条相关分块排在第 r 位时为 1/r，前 K 条都没有时为 0，数据集上取平均即 MRR；
 * - nDCG@K：相关分块越靠前得分越高。DCG = Σ rel_i / log2(i+1)（i 为 1 起的排名，rel 二值，
 *   按 {@link #isRelevant} 判定），再除以"全部相关分块置顶"的理想 DCG 归一；
 *   唯一相关分块排第 1 位时 nDCG=1，排第 r 位时为 1/log2(r+1)，前 K 都不相关时为 0。
 *   理想 DCG 只用前 K 内的相关分块数（检索侧只拿到 topK 列表）。
 * - 期望项覆盖率：前 K 条覆盖了多少比例的期望项。标注了 expectedEvidence 的用例按证据短语算
 *   （命中的短语数 / 短语总数，两证据题只命中一条即 0.5，不再按"命中过一次"记满）；
 *   否则按期望文档算（命中的期望文档数 / 期望文档总数）。覆盖率口径不受 chunkId 标注影响
 *   （chunkId 只升级"是否命中"的判定）。
 * 期望文档优先按 MD5 匹配，其次按文件名（忽略大小写）匹配。
 * 用例标注了 expectedEvidence 时，相关分块还要求分块文本包含其中任意一个短语（分块级）；
 * 否则同一文件的任何分块都算相关（文件级）。文件级命中另外单独记录，用来对比两种口径的差距。
 * <p>
 * 分块级 gold（expectedChunkIds，与 expectedFileNames 平行）：标注且可用时相关分块按 chunkId 判定，
 * 证据短语不再参与（chunkId 是比短语更精确的分块标注）；该用例的检索结果整体未带 chunkId（未索引）
 * 或标注不可用（未标注/与文件数失配）时回退证据短语判定，运行期口径可用
 * {@link #goldFallback(EvalCase, List, int)} 读出（回退时为 {@code goldFallback=phrase}）。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * @return 第一条相关结果的排名（从 1 开始），前 K 条都不相关时返回 0
     */
    public static int firstRelevantRank(List<SearchResult> results, EvalCase evalCase, int k) {
        int limit = limit(results, k);
        for (int i = 0; i < limit; i++) {
            if (isRelevant(results.get(i), evalCase)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 只按文件判断的命中：前 K 条里出现任一期望文件的分块即算命中
     */
    public static boolean docHit(List<SearchResult> results, EvalCase evalCase, int k) {
        Set<String> md5s = lower(evalCase.expectedFileMd5s());
        Set<String> names = lower(evalCase.expectedFileNames());
        int limit = limit(results, k);
        for (int i = 0; i < limit; i++) {
            if (isDocRelevant(results.get(i), md5s, names)) {
                return true;
            }
        }
        return false;
    }

    public static double reciprocalRank(int rank) {
        return rank > 0 ? 1d / rank : 0d;
    }

    /**
     * nDCG@K：相关性二值（按 {@link #isRelevant}），唯一相关分块排第 1 位时为 1，排第 r 位时为 1/log2(r+1)。
     * 理想排序取前 K 内的全部相关分块置顶（检索侧只有 topK 列表，K 之外的相关性不可见）。
     */
    public static double ndcgAtK(List<SearchResult> results, EvalCase evalCase, int k) {
        int limit = limit(results, k);
        if (limit == 0) {
            return 0d;
        }
        double dcg = 0d;
        int relevant = 0;
        for (int i = 0; i < limit; i++) {
            if (isRelevant(results.get(i), evalCase)) {
                relevant++;
                dcg += 1d / log2Discount(i + 1);
            }
        }
        return ndcg(dcg, relevant, k);
    }

    /**
     * nDCG@K 的报告侧入口：直接按用例明细里记录的分块（rank 1 起、relevant 为用例标注判定）计算，
     * 与 {@link #ndcgAtK(List, EvalCase, int)} 在同一份检索结果上取值一致。
     */
    public static double ndcgAtK(List<EvalCaseResult.RetrievedChunk> retrieved, int k) {
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0d;
        }
        double dcg = 0d;
        int relevant = 0;
        for (EvalCaseResult.RetrievedChunk chunk : retrieved) {
            if (chunk.relevant() && chunk.rank() >= 1 && chunk.rank() <= k) {
                relevant++;
                dcg += 1d / log2Discount(chunk.rank());
            }
        }
        return ndcg(dcg, relevant, k);
    }

    private static double ndcg(double dcg, int relevantInTopK, int k) {
        if (relevantInTopK == 0) {
            return 0d;
        }
        double idcg = 0d;
        for (int rank = 1; rank <= Math.min(relevantInTopK, k); rank++) {
            idcg += 1d / log2Discount(rank);
        }
        return dcg / idcg;
    }

    /** 排名 i（1 起）的折损 log2(i+1) */
    private static double log2Discount(int rank) {
        return Math.log(rank + 1) / Math.log(2);
    }

    /**
     * 期望项覆盖率。标注了 expectedEvidence 的用例按证据短语覆盖（多证据改 coverage：
     * 命中的短语数 / 短语总数），否则按期望文档覆盖，两种口径都只看前 K 条。
     */
    public static double expectedDocCoverage(List<SearchResult> results, EvalCase evalCase, int k) {
        List<String> evidence = normalizedEvidence(evalCase);
        if (!evidence.isEmpty()) {
            return evidenceCoverage(results, evidence, k);
        }
        int expectedCount = evalCase.expectedFileMd5s().size() + evalCase.expectedFileNames().size();
        if (expectedCount == 0) {
            return 0d;
        }
        List<SearchResult> top = results == null ? List.of() : results.subList(0, limit(results, k));
        Set<String> retrievedMd5s = top.stream().map(SearchResult::getFileMd5).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        Set<String> retrievedNames = top.stream().map(SearchResult::getFileName).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        long covered = evalCase.expectedFileMd5s().stream().map(RetrievalMetrics::lower).filter(retrievedMd5s::contains).count()
                + evalCase.expectedFileNames().stream().map(RetrievalMetrics::lower).filter(retrievedNames::contains).count();
        return (double) covered / expectedCount;
    }

    /**
     * 证据短语覆盖率：前 K 条分块里出现了多少比例的期望证据短语（一个短语出现在多个分块只计一次）。
     * 标注的短语归一化后全部为空时返回 0。
     */
    public static double evidenceCoverage(List<SearchResult> results, EvalCase evalCase, int k) {
        return evidenceCoverage(results, normalizedEvidence(evalCase), k);
    }

    private static double evidenceCoverage(List<SearchResult> results, List<String> evidence, int k) {
        if (evidence.isEmpty()) {
            return 0d;
        }
        List<SearchResult> top = results == null ? List.of() : results.subList(0, limit(results, k));
        long covered = evidence.stream()
                .filter(phrase -> top.stream().anyMatch(result -> normalizeText(result.getTextContent()).contains(phrase)))
                .count();
        return (double) covered / evidence.size();
    }

    /**
     * 相关分块判定：文档命中（MD5 或文件名）为前提。
     * 分块级 gold（expectedChunkIds）标注且可用时按 chunkId 判命中（短语不参与）；
     * 未标注、标注失配或该结果未带 chunkId（未索引）时回退证据短语判定。
     */
    public static boolean isRelevant(SearchResult result, EvalCase evalCase) {
        if (!isDocRelevant(result, lower(evalCase.expectedFileMd5s()), lower(evalCase.expectedFileNames()))) {
            return false;
        }
        if (evalCase.hasUsableChunkGold()) {
            String expectedChunkId = expectedChunkIdFor(result, evalCase);
            if (expectedChunkId != null && result.getChunkId() != null) {
                return chunkIdEquals(result.getChunkId(), expectedChunkId);
            }
            // 找不到结果对应的期望文件位次（如仅按 MD5 命中且 MD5 与文件名不平行），
            // 或检索结果未带 chunkId（未索引）→ 回退证据短语判定
        }
        return containsEvidence(result, normalizedEvidence(evalCase));
    }

    /**
     * 该用例在给定检索结果上的 gold 判定口径：分块级 gold 不可用（未标注/失配，标注了证据短语），
     * 或 gold 可用但 top-K 结果整体未带 chunkId（未索引，标注了证据短语）时返回
     * {@code phrase}（goldFallback=phrase）；按 chunkId 判定、文件级判定（无短语）或结果为空时返回 null。
     * 部分结果缺 chunkId 的混合态不在此标记（缺 chunkId 的那条在 {@link #isRelevant} 里逐条回退）。
     */
    public static String goldFallback(EvalCase evalCase, List<SearchResult> results, int k) {
        if (!evalCase.hasUsableChunkGold()) {
            return evalCase.goldFallback();
        }
        List<SearchResult> top = results == null ? List.of() : results.subList(0, limit(results, k));
        if (top.isEmpty() || top.stream().anyMatch(result -> result.getChunkId() != null)) {
            return null;
        }
        return evalCase.hasExpectedEvidence() ? EvalCase.GOLD_FALLBACK_PHRASE : null;
    }

    /**
     * 结果对应的期望 chunkId：按文件名对位（expectedChunkIds 与 expectedFileNames 平行），
     * 文件名对不上且 MD5 数与文件名数一致时按 MD5 对位；都对不上返回 null（调用方回退短语）。
     */
    private static String expectedChunkIdFor(SearchResult result, EvalCase evalCase) {
        List<String> names = evalCase.expectedFileNames();
        String name = lower(result.getFileName());
        if (name != null) {
            for (int i = 0; i < names.size(); i++) {
                if (name.equals(lower(names.get(i)))) {
                    return evalCase.expectedChunkIds().get(i);
                }
            }
        }
        List<String> md5s = evalCase.expectedFileMd5s();
        String md5 = lower(result.getFileMd5());
        if (md5 != null && md5s.size() == names.size()) {
            for (int i = 0; i < md5s.size(); i++) {
                if (md5.equals(lower(md5s.get(i)))) {
                    return evalCase.expectedChunkIds().get(i);
                }
            }
        }
        return null;
    }

    /** chunkId 比对：字符串相等优先，标注带前导零（如 "03"）时按数值兜底 */
    private static boolean chunkIdEquals(Integer chunkId, String expectedChunkId) {
        String expected = expectedChunkId == null ? "" : expectedChunkId.trim();
        if (chunkId == null || expected.isEmpty()) {
            return false;
        }
        if (String.valueOf(chunkId).equals(expected)) {
            return true;
        }
        try {
            return Integer.parseInt(expected) == chunkId;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isDocRelevant(SearchResult result, Set<String> expectedMd5s, Set<String> expectedNames) {
        String md5 = lower(result.getFileMd5());
        String name = lower(result.getFileName());
        return (md5 != null && expectedMd5s.contains(md5)) || (name != null && expectedNames.contains(name));
    }

    private static boolean containsEvidence(SearchResult result, List<String> evidence) {
        if (evidence.isEmpty()) {
            return true;
        }
        String text = normalizeText(result.getTextContent());
        return evidence.stream().anyMatch(text::contains);
    }

    private static List<String> normalizedEvidence(EvalCase evalCase) {
        return evalCase.expectedEvidence().stream()
                .map(RetrievalMetrics::normalizeText)
                .filter(phrase -> !phrase.isEmpty())
                .toList();
    }

    /**
     * NFKC 把 PDF 抽取出的康熙部首（如 ⼤）、全角字符还原成常规字符，再去掉空白、统一小写，避免排版差异导致匹配不上
     */
    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static int limit(List<SearchResult> results, int k) {
        return Math.min(results == null ? 0 : results.size(), Math.max(k, 0));
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            out.add(lower(value));
        }
        return out;
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
