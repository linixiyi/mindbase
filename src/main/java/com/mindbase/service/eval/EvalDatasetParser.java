package com.mindbase.service.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 JSONL 数据集，每行一个用例：
 * <pre>{@code
 * {"id": "可选", "question": "...", "split": "dev|test", "type": "fact", "difficulty": 1,
 *  "expectedRefusal": false, "expectedFileMd5s": ["..."], "expectedFileNames": ["..."],
 *  "expectedChunkIds": ["..."], "expectedEvidence": ["答案分块里的关键短语"], "referenceAnswer": "..."}
 * }</pre>
 * <p>
 * v2 字段规则：
 * <ul>
 *   <li>{@code split}：只允许 {@code dev} / {@code test}，其他值（含大小写不符）解析失败；
 *       旧数据缺省时按 {@code dev} 处理，并记 warn、把数据集标为 legacy；</li>
 *   <li>{@code type}：自由文本题型标签；</li>
 *   <li>{@code difficulty}：1-3 的整数，越界或非整数解析失败；</li>
 *   <li>{@code expectedRefusal}：布尔值，非布尔解析失败；</li>
 *   <li>{@code expectedFileMd5s} / {@code expectedChunkIds}：可选列表。{@code expectedChunkIds} 与
 *       {@code expectedFileNames} 平行（第 i 个 chunkId 属于第 i 个期望文件）；两者长度不一致时记 warn
 *       但不崩——分块级 gold 按不可用处理，命中判定回退证据短语（用例标 {@code goldFallback=phrase}）。</li>
 * </ul>
 * 任一 v2 结构字段缺省（旧数据集兼容）时，{@link ParseResult#legacyDataset()} 为 true，
 * 报告层应据此在报告里标注 {@code legacyDataset=true}；缺省字段与 gold 标注问题明细见
 * {@link ParseResult#warnings()}。
 * <p>
 * id 重复（显式 id 撞车，或显式 id 与缺省 id {@code line-<行号>} 撞车）解析失败。
 * 空行和以 # 或 // 开头的注释行会被跳过；格式错误时报出行号。
 */
public final class EvalDatasetParser {

    private static final Logger logger = LoggerFactory.getLogger(EvalDatasetParser.class);

    private EvalDatasetParser() {
    }

    /**
     * 兼容入口：只返回用例列表。数据集级诊断（legacyDataset / warnings）请用 {@link #parseWithDiagnostics}。
     */
    public static List<EvalCase> parse(Reader reader, ObjectMapper objectMapper, int maxCases) throws IOException {
        return parseWithDiagnostics(reader, objectMapper, maxCases).cases();
    }

    /**
     * 解析结果及数据集级诊断。
     *
     * @param cases         用例列表（保持文件顺序）
     * @param legacyDataset true 表示至少有一条用例缺省 v2 字段（旧数据集兼容口径），
     *                      报告层应据此在报告里标注 {@code legacyDataset=true}
     * @param warnings     人读的缺省字段与 gold 标注问题明细（含行号），供报告/日志引用
     */
    public record ParseResult(List<EvalCase> cases, boolean legacyDataset, List<String> warnings) {
    }

    public static ParseResult parseWithDiagnostics(Reader reader, ObjectMapper objectMapper, int maxCases)
            throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> seenIds = new HashMap<>();
        boolean legacy = false;
        BufferedReader buffered = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        String line;
        int lineNo = 0;
        while ((line = buffered.readLine()) != null) {
            lineNo++;
            String trimmed = stripBom(line).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            if (cases.size() >= maxCases) {
                throw new IllegalArgumentException("数据集超过单次评测上限 " + maxCases + " 条");
            }
            ParsedCase parsed = parseLine(trimmed, lineNo, objectMapper);
            EvalCase evalCase = parsed.evalCase;
            if (!parsed.missingV2Fields.isEmpty()) {
                legacy = true;
                warnings.add("第 " + lineNo + " 行（id=" + evalCase.id() + "）缺少 v2 字段: "
                        + String.join(", ", parsed.missingV2Fields)
                        + (parsed.missingV2Fields.contains("split") ? "；缺省 split 按 dev 处理" : ""));
            }
            for (String annotationWarning : parsed.annotationWarnings) {
                warnings.add("第 " + lineNo + " 行（id=" + evalCase.id() + "）" + annotationWarning);
            }
            Integer firstLine = seenIds.putIfAbsent(evalCase.id(), lineNo);
            if (firstLine != null) {
                throw new IllegalArgumentException("第 " + lineNo + " 行 id 重复（首次出现在第 " + firstLine
                        + " 行）: " + evalCase.id());
            }
            cases.add(evalCase);
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("数据集为空");
        }
        if (legacy) {
            logger.warn("数据集包含缺省 v2 字段的用例（legacyDataset=true，报告需标注 legacyDataset=true）共 {} 条，"
                    + "明细: {}", warnings.size(), warnings);
        }
        List<String> annotationWarnings = warnings.stream()
                .filter(w -> w.contains(CHUNK_ID_LENGTH_MISMATCH)).toList();
        if (!annotationWarnings.isEmpty()) {
            logger.warn("数据集存在 gold 标注问题（不阻断解析，命中判定回退证据短语）共 {} 条，明细: {}",
                    annotationWarnings.size(), annotationWarnings);
        }
        return new ParseResult(List.copyOf(cases), legacy, List.copyOf(warnings));
    }

    /** chunkIds 与文件名长度失配的 warn 前缀，供调用方与测试识别 */
    static final String CHUNK_ID_LENGTH_MISMATCH = "expectedChunkIds 与 expectedFileNames 长度不一致";

    record ParsedCase(EvalCase evalCase, Set<String> missingV2Fields, List<String> annotationWarnings) {
    }

    static ParsedCase parseLine(String line, int lineNo, ObjectMapper objectMapper) {
        JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("第 " + lineNo + " 行不是合法 JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行必须是 JSON 对象");
        }
        String question = text(node, "question");
        String reference = text(node, "referenceAnswer");
        if (question == null) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 question");
        }
        if (reference == null) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 referenceAnswer");
        }
        Set<String> missingV2Fields = new LinkedHashSet<>();

        String rawSplit = text(node, "split");
        String split;
        if (rawSplit == null) {
            split = EvalCase.SPLIT_DEV;
            missingV2Fields.add("split");
        } else if (!EvalCase.SPLIT_DEV.equals(rawSplit) && !EvalCase.SPLIT_TEST.equals(rawSplit)) {
            throw new IllegalArgumentException("第 " + lineNo + " 行 split 非法（只允许 dev|test）: " + rawSplit);
        } else {
            split = rawSplit;
        }

        String type = text(node, "type");
        if (type == null) {
            missingV2Fields.add("type");
        }

        Integer difficulty;
        try {
            difficulty = integer(node, "difficulty");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("第 " + lineNo + " 行 " + e.getMessage());
        }
        if (difficulty == null) {
            missingV2Fields.add("difficulty");
        } else if (difficulty < 1 || difficulty > 3) {
            throw new IllegalArgumentException("第 " + lineNo + " 行 difficulty 非法（只允许 1-3）: " + difficulty);
        }

        JsonNode refusalNode = node.get("expectedRefusal");
        Boolean expectedRefusal;
        if (refusalNode == null || refusalNode.isNull()) {
            expectedRefusal = null;
            missingV2Fields.add("expectedRefusal");
        } else if (!refusalNode.isBoolean()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行 expectedRefusal 必须是布尔值: " + refusalNode);
        } else {
            expectedRefusal = refusalNode.asBoolean();
        }

        String id = text(node, "id");
        List<String> expectedFileNames = strings(node, "expectedFileNames");
        List<String> expectedChunkIds = strings(node, "expectedChunkIds");
        List<String> annotationWarnings = new ArrayList<>();
        if (!expectedChunkIds.isEmpty() && expectedChunkIds.size() != expectedFileNames.size()) {
            // warn 不崩：分块级 gold 与文件失配后无法逐文件对位，按不可用处理（用例标 goldFallback=phrase）
            annotationWarnings.add(CHUNK_ID_LENGTH_MISMATCH + "（chunkIds=" + expectedChunkIds.size()
                    + ", files=" + expectedFileNames.size() + "），分块级 gold 按不可用处理，命中判定回退证据短语");
        }
        EvalCase evalCase = new EvalCase(id == null ? "line-" + lineNo : id, question,
                strings(node, "expectedFileMd5s"), expectedFileNames, reference,
                strings(node, "expectedEvidence"), split, type, difficulty, expectedRefusal, expectedChunkIds);
        return new ParsedCase(evalCase, missingV2Fields, List.copyOf(annotationWarnings));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("difficulty 必须是整数: " + value);
        }
        return value.intValue();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isTextual()) {
            return value.asText().isBlank() ? List.of() : List.of(value.asText().trim());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
