package com.mindbase.service.eval;

import com.mindbase.entity.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 生成与引用指标（v2）：faithfulness（claim 级有据率）、citation precision/recall（回答引用是否指向相关分块）、
 * 拒答正确率（expectedRefusal 标注与实际拒答行为的一致性）。
 * <p>
 * 口径说明：
 * <ul>
 *   <li><b>faithfulness</b>：回答拆成 claim 后逐条判 supported / unsupported / unverified，
 *       有据率 = supported / (supported + unsupported)。无据（unsupported）claim 计入不忠实；
 *       unverified（没有检索证据可核对，例如检索为空）不计入分母——检索失败是检索问题，
 *       不算生成不忠实，数量单列可复核。拒答回答不拆 claim（"如实说明没找到"不是事实性主张）。</li>
 *   <li><b>claim 判定是词面口径</b>：claim 与证据做归一化包含或 token（拉丁/数字整词 + 中文二字组）
 *       覆盖率匹配，不依赖模型、不联网；同义改写可能被误判为 unsupported，语义蕴含仍以 LLM 评委为准，
 *       claim 明细随报告保留供人工复核。切分也是确定性的（按句读 + 去引用标记），测试可直接注入固定
 *       claim 列表（{@link #faithfulness(List)}）或固定证据文本（{@link #judgeClaims(String, List)}），
 *       无需任何模型参与。</li>
 *   <li><b>citation precision</b>：回答里标注的引用编号 [N] / 来源#N 中，指向相关分块（按
 *       {@link RetrievalMetrics#isRelevant} 用例标注判定）的比例；指向错误分块或越界编号都会拉低 precision。
 *       <b>citation recall</b>：证据里的相关分块被回答引用过的比例。两者都没有可计算样本时为 null。</li>
 *   <li><b>拒答</b>：确定性标记词判定（见 {@link #REFUSAL_MARKERS}）；expectedRefusal 未标注的用例
 *       refusal 为 null 不参与口径，标注为 true 的用例拒答则 refusal=1（拒答正确）。</li>
 * </ul>
 */
public final class GenerationMetrics {

    /** claim 判定取值（与 {@link EvalCaseResult.ClaimVerdict} 约定一致） */
    public static final String SUPPORTED = "supported";
    public static final String UNSUPPORTED = "unsupported";
    public static final String UNVERIFIED = "unverified";

    /**
     * 词面匹配的 supported 阈值：claim 的 token（拉丁/数字整词 + 中文二字组）在证据中的覆盖率达到该值
     * 判为有据。取 0.5 是折中：完全有据的句子（含少量改写）通常 &gt; 0.8，编造内容通常 &lt; 0.2。
     */
    static final double TOKEN_SUPPORT_THRESHOLD = 0.5;

    /** claim 里的引用标记：[1]、[1][2] 与旧 rag 链路的 来源#2，拆 claim 前剥掉，避免编号残片干扰匹配 */
    private static final Pattern CITATION_MARKERS = Pattern.compile("\\[\\d+\\]|来源#\\d+");

    /** claim 切分分隔：句读与换行；逗号不切（复合句仍是一条 claim） */
    private static final Pattern CLAIM_DELIMITERS = Pattern.compile("[。！？!?；;\n]+");

    /**
     * 拒答标记词（匹配前与回答一起做 NFKC + 去空白 + 小写归一化，所以中文英文都按去空格后的形态写）。
     * 取强拒答语义的短语，避免把"资料中没有提到 X，但 Y"这类部分回答整条误判；
     * 误判代价已知且可控：拒答口径只影响 refusal 字段与拒答正确率，评委正确性独立打分。
     */
    private static final Set<String> REFUSAL_MARKERS = normalizeAll(
            "无法回答", "无法确定", "无法提供", "无法检索", "暂时无法", "无法从资料", "无法从知识库",
            "不能回答", "不能确定", "不知道", "无法判断",
            "没有找到相关", "未找到相关", "没有相关", "无相关", "未检索到", "暂无相关", "检索不到",
            "资料中没有", "资料中未", "资料里没有", "资料里未", "知识库中没", "知识库里没",
            "文档中没有", "文档里没有", "没有足够的信息", "信息不足", "不足以回答", "没有依据", "缺乏依据",
            "没有提及", "未提及", "没有提到", "未提到",
            "notfoundin", "cannotfind", "unabletoanswer", "notmentionedin",
            "norelevantinformation", "insufficientinformation", "noinformation", "idontknow");

    /** 回答里的引用编号：[N] 与旧 rag 链路的 来源#N，编号对应模型实际看到的第 N 条证据 */
    private static final Pattern CITATION_NUMBERS = Pattern.compile("\\[(\\d+)\\]|来源#(\\d+)");

    private GenerationMetrics() {
    }

    /**
     * 单条用例的生成与引用指标（写入 {@link EvalCaseResult} 的 generation 字段）。
     * answer 未产出（检索/生成失败）时整个 Result 为 null，不参与任何生成侧分母。
     *
     * @param refusal        1=拒答 0=未拒答；expectedRefusal 未标注的用例仍给出行为值，口径由 refusalCorrect 承担
     * @param refusalCorrect 拒答行为是否符合 expectedRefusal 标注；未标注为 null
     * @param refusalExpected 用例标注应拒答（expectedRefusal=true），供汇总侧统计应拒答分母
     * @param faithfulness    有据 claim 占比；无可判 claim（全部 unverified 或拒答/无 claim）为 null
     * @param citationPrecision 引用指向相关分块的比例；回答没有引用编号时为 null
     * @param citationRecall    相关分块被引用的比例；证据中没有相关分块时为 null
     */
    public record Result(
            Integer refusal,
            Boolean refusalCorrect,
            boolean refusalExpected,
            Double faithfulness,
            int totalClaims,
            int supportedClaims,
            int unsupportedClaims,
            int unverifiedClaims,
            Double citationPrecision,
            Double citationRecall,
            int citedCount,
            int citedRelevantCount,
            int relevantAvailableCount
    ) {
    }

    /** 引用指标中间结果（{@link #citations} 返回，{@link Result} 展开承载） */
    public record CitationMetrics(Double precision, Double recall, int cited, int citedRelevant, int relevantAvailable) {
    }

    /** 单条用例的完整评估：metrics 写入 EvalCaseResult.generation，claimVerdicts 写入 EvalCaseResult.claimVerdicts */
    public record Evaluation(Result metrics, List<EvalCaseResult.ClaimVerdict> claimVerdicts) {
    }

    /**
     * 用例执行侧唯一入口：拆 claim → 逐条判 supported/unsupported/unverified → 计算引用与拒答指标。
     * 证据列表传模型实际看到的片段（react 模式为工具检索结果，rag 模式为 topK 检索结果），
     * "有据"的定义始终相对于模型生成时真正拿到的证据。
     */
    public static Evaluation evaluateCase(EvalCase evalCase, String answer, List<SearchResult> evidence) {
        if (answer == null || answer.isBlank()) {
            return new Evaluation(null, null);
        }
        boolean refused = isRefusal(answer);
        List<EvalCaseResult.ClaimVerdict> verdicts = refused
                ? List.of()
                : judgeClaims(answer, evidenceTexts(evidence));
        CitationMetrics citations = citations(answer, evidence, evalCase);
        Result result = buildResult(evalCase, answer, refused, verdicts, citations);
        return new Evaluation(result, verdicts);
    }

    private static Result buildResult(EvalCase evalCase, String answer, boolean refused,
                                      List<EvalCaseResult.ClaimVerdict> verdicts, CitationMetrics citations) {
        Boolean refusalCorrect = evalCase.expectedRefusal() == null ? null : refused == evalCase.expectedRefusal();
        int supported = 0;
        int unsupported = 0;
        int unverified = 0;
        for (EvalCaseResult.ClaimVerdict verdict : verdicts) {
            if (SUPPORTED.equals(verdict.verdict())) {
                supported++;
            } else if (UNSUPPORTED.equals(verdict.verdict())) {
                unsupported++;
            } else {
                unverified++;
            }
        }
        return new Result(refused ? 1 : 0, refusalCorrect, evalCase.expectsRefusal(),
                refused ? null : faithfulness(verdicts),
                verdicts.size(), supported, unsupported, unverified,
                citations.precision(), citations.recall(),
                citations.cited(), citations.citedRelevant(), citations.relevantAvailable());
    }

    // ---- claim 切分与判定（确定性，不依赖模型、不联网） ----

    /**
     * 回答拆 claim：剥掉引用标记后按句读切分；归一化后长度不足 2 的残片（标点、单个字符）不是 claim，直接丢弃。
     * 包级可见供测试直接验证切分行为。
     */
    static List<String> splitClaims(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        String cleaned = CITATION_MARKERS.matcher(answer).replaceAll("");
        List<String> claims = new ArrayList<>();
        for (String part : CLAIM_DELIMITERS.split(cleaned)) {
            String claim = part.strip();
            if (!claim.isEmpty() && RetrievalMetrics.normalizeText(claim).length() >= 2) {
                claims.add(claim);
            }
        }
        return List.copyOf(claims);
    }

    /**
     * 逐条 claim 判定。evidenceTexts 为空（检索无结果）时全部判 unverified——没有证据就没有"无据"可言；
     * 否则按词面口径判 supported（归一化包含或 token 覆盖率 ≥ {@link #TOKEN_SUPPORT_THRESHOLD}）/ unsupported。
     * 测试用固定 claim 列表与固定证据文本注入即可覆盖全部行为，不需要任何模型调用。
     */
    public static List<EvalCaseResult.ClaimVerdict> judgeClaims(String answer, List<String> evidenceTexts) {
        List<String> claims = splitClaims(answer);
        if (claims.isEmpty()) {
            return List.of();
        }
        List<String> evidence = evidenceTexts == null ? List.of()
                : evidenceTexts.stream()
                        .filter(text -> text != null && !text.isBlank())
                        .map(RetrievalMetrics::normalizeText)
                        .filter(text -> !text.isEmpty())
                        .toList();
        List<EvalCaseResult.ClaimVerdict> verdicts = new ArrayList<>(claims.size());
        for (String claim : claims) {
            verdicts.add(classifyClaim(claim, RetrievalMetrics.normalizeText(claim), evidence));
        }
        return List.copyOf(verdicts);
    }

    private static EvalCaseResult.ClaimVerdict classifyClaim(String claim, String normalized, List<String> evidence) {
        if (evidence.isEmpty()) {
            return new EvalCaseResult.ClaimVerdict(claim, UNVERIFIED, "没有检索证据可核对（unverified 不计入 faithfulness 分母）");
        }
        if (evidence.stream().anyMatch(text -> text.contains(normalized))) {
            return new EvalCaseResult.ClaimVerdict(claim, SUPPORTED, "claim 归一化后完整出现在检索证据中");
        }
        Set<String> evidenceTokens = evidence.stream()
                .map(GenerationMetrics::tokenize)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        double coverage = tokenCoverage(normalized, evidenceTokens);
        if (coverage >= TOKEN_SUPPORT_THRESHOLD) {
            return new EvalCaseResult.ClaimVerdict(claim, SUPPORTED,
                    String.format(Locale.ROOT, "claim token 覆盖率 %.2f ≥ %.2f", coverage, TOKEN_SUPPORT_THRESHOLD));
        }
        return new EvalCaseResult.ClaimVerdict(claim, UNSUPPORTED,
                String.format(Locale.ROOT, "证据中找不到该说法（token 覆盖率 %.2f）", coverage));
    }

    /**
     * 词面口径的 token 化：拉丁/数字连续串作整词（elasticsearch、8080、rag 各一个 token，
     * 避免跨字母边界的碎片 bigram 干扰匹配），中文连续串取全部二字组，单个汉字自成一个 token，标点丢弃。
     */
    static LinkedHashSet<String> tokenize(String normalized) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        int start = 0;
        while (start < normalized.length()) {
            char c = normalized.charAt(start);
            if (isLatinOrDigit(c)) {
                int end = start;
                while (end < normalized.length() && isLatinOrDigit(normalized.charAt(end))) {
                    end++;
                }
                tokens.add(normalized.substring(start, end));
                start = end;
            } else if (isCjk(c)) {
                int end = start;
                while (end < normalized.length() && isCjk(normalized.charAt(end))) {
                    end++;
                }
                for (int i = start; i < end; i++) {
                    tokens.add(i + 2 <= end ? normalized.substring(i, i + 2) : normalized.substring(i, i + 1));
                }
                start = end;
            } else {
                start++;
            }
        }
        return tokens;
    }

    private static double tokenCoverage(String normalizedClaim, Set<String> evidenceTokens) {
        Set<String> claimTokens = tokenize(normalizedClaim);
        if (claimTokens.isEmpty()) {
            return 0d;
        }
        long matched = claimTokens.stream().filter(evidenceTokens::contains).count();
        return (double) matched / claimTokens.size();
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 有据率 = supported / (supported + unsupported)；unverified 不计入分母。
     * 没有 supported/unsupported claim（全 unverified、拒答、无 claim）时返回 null，不编数字。
     * 固定 claim 列表注入点：测试与离线复核直接传 {@link EvalCaseResult.ClaimVerdict} 列表即可。
     */
    public static Double faithfulness(List<EvalCaseResult.ClaimVerdict> verdicts) {
        int supported = 0;
        int unsupported = 0;
        for (EvalCaseResult.ClaimVerdict verdict : verdicts == null ? List.<EvalCaseResult.ClaimVerdict>of() : verdicts) {
            if (verdict == null) {
                continue;
            }
            if (SUPPORTED.equals(verdict.verdict())) {
                supported++;
            } else if (UNSUPPORTED.equals(verdict.verdict())) {
                unsupported++;
            }
        }
        int judgeable = supported + unsupported;
        return judgeable == 0 ? null : round((double) supported / judgeable);
    }

    // ---- 引用指标 ----

    /**
     * 引用指标：从回答里抽取引用编号 [N] / 来源#N，编号 N 对应 evidence 的第 N 条（1 起）。
     * 越界编号（证据里没有第 N 条）按"引用了错误分块"计入 cited 但不算 relevant，precision 因此被拉低。
     * relevantAvailable 是证据里相关分块总数（recall 的分母）；cited=0 或 relevantAvailable=0 时
     * 对应指标为 null（没有可计算样本就不编数字）。
     */
    public static CitationMetrics citations(String answer, List<SearchResult> evidence, EvalCase evalCase) {
        List<SearchResult> basis = evidence == null ? List.of() : evidence;
        Set<Integer> citedNumbers = new LinkedHashSet<>();
        for (MatchResult match : CITATION_NUMBERS.matcher(answer == null ? "" : answer).results().toList()) {
            String number = match.group(1) != null ? match.group(1) : match.group(2);
            citedNumbers.add(Integer.parseInt(number));
        }
        int cited = citedNumbers.size();
        int citedRelevant = 0;
        for (int number : citedNumbers) {
            if (number >= 1 && number <= basis.size()
                    && RetrievalMetrics.isRelevant(basis.get(number - 1), evalCase)) {
                citedRelevant++;
            }
        }
        int relevantAvailable = 0;
        for (SearchResult result : basis) {
            if (result != null && RetrievalMetrics.isRelevant(result, evalCase)) {
                relevantAvailable++;
            }
        }
        return new CitationMetrics(
                cited == 0 ? null : round((double) citedRelevant / cited),
                relevantAvailable == 0 ? null : round((double) citedRelevant / relevantAvailable),
                cited, citedRelevant, relevantAvailable);
    }

    // ---- 拒答 ----

    /**
     * 确定性拒答判定：回答命中任一 {@link #REFUSAL_MARKERS} 标记即为拒答。
     * 空回答不算拒答（生成失败由 diagnosis 口径单独记录，不冒充拒答行为）。
     */
    public static boolean isRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalized = RetrievalMetrics.normalizeText(answer);
        if (normalized.isEmpty()) {
            return false;
        }
        return REFUSAL_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static Set<String> normalizeAll(String... markers) {
        return java.util.Arrays.stream(markers).map(RetrievalMetrics::normalizeText).collect(Collectors.toSet());
    }

    // ---- 汇总辅助 ----

    /**
     * 证据文本口径：与评委看到的证据一致（父子检索命中取父块文本，否则取命中小分块原文），
     * claim 的"有据"判定与生成时模型真正看到的内容对齐。
     */
    static List<String> evidenceTexts(List<SearchResult> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(result -> result != null)
                .map(GenerationMetrics::evidenceTextOf)
                .toList();
    }

    private static String evidenceTextOf(SearchResult result) {
        if (result.getParentContext() != null && !result.getParentContext().isBlank() && result.getParentKey() != null) {
            return result.getParentContext();
        }
        return result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent();
    }

    private static Double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
