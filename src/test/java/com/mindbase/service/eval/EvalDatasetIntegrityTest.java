package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_0010 数据集完整性门禁：随仓库打包的 {@code /eval/eval-v2.jsonl}（基于 eval-datasets/corpus/
 * 下 10 篇真实 PDF 重建）必须满足——题量 ≥120、dev 90 / test 30、id 唯一、每篇文档 ≥6 题、
 * 07 扫描件的题 ≤10 条且标注 ocr=true、负样本 ≥15、每题都有 expectedEvidence。
 * <p>
 * 失败处理分支（同任务书）：07（07-os-filesystem-ppt-zh.pdf，中文扫描 PPT）因 OCR 运行时未配置无法解析，
 * 它的题标 {@code status="blocked"}，不计入任何分母（题量/split/覆盖/负样本都只在非 blocked 题上统计），
 * 其余 9 篇必须满足覆盖要求——本测试把这一口径固化为断言。
 * <p>
 * 测试只依赖打包资源（PDF 不入库，故不读语料文件本身），CI 可重复执行。
 */
class EvalDatasetIntegrityTest {

    private static final String DATASET_RESOURCE = "/eval/eval-v2.jsonl";

    /** 评测语料 10 篇（与 eval-datasets/manifest.json 的 documents[].name 一致，顺序无关） */
    private static final Set<String> CORPUS_FILES = Set.of(
            "01-clip-radford-2021.pdf",
            "02-dinov2-oquab-2024.pdf",
            "03-denoising-vit-yang-2024.pdf",
            "04-tactile-force-chen-2025.pdf",
            "05-medical-instrumentation-ch2.pdf",
            "06-drug-discovery-a16z.pdf",
            "07-os-filesystem-ppt-zh.pdf",
            "08-os-review-zh.pdf",
            "09-bme6144-week6-slides.pdf",
            "10-bme6111-week4-slides.pdf");

    /** 07 扫描件：OCR 未配置时 blocked，不计入分母 */
    private static final String SCAN_DOC = "07-os-filesystem-ppt-zh.pdf";

    private static final int MIN_TOTAL = 120;
    private static final int EXPECTED_DEV = 90;
    private static final int EXPECTED_TEST = 30;
    private static final int MIN_NEGATIVE = 15;
    private static final int MIN_PER_DOC = 6;
    private static final int MIN_DOCS_COVERED = 8;
    private static final int MAX_BLOCKED_07 = 10;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static List<JsonNode> rawCases;
    private static EvalDatasetParser.ParseResult parsed;
    private static List<JsonNode> blocked;
    private static List<JsonNode> counted;

    @BeforeAll
    static void loadDataset() throws Exception {
        StringBuilder raw = new StringBuilder();
        try (var in = EvalDatasetIntegrityTest.class.getResourceAsStream(DATASET_RESOURCE)) {
            assertNotNull(in, "打包资源 " + DATASET_RESOURCE + " 必须存在");
            try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    raw.append(buf, 0, n);
                }
            }
        }
        rawCases = new ArrayList<>();
        for (String line : raw.toString().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            JsonNode node = objectMapper.readTree(trimmed);
            assertTrue(node.isObject(), "每行数据必须是 JSON 对象: " + trimmed);
            rawCases.add(node);
        }

        // 真实解析链路：schema 校验 + id 唯一（重复 id 会抛 IllegalArgumentException）+ legacy 标注
        // maxCases 用生产默认（RagEvalProperties.maxCases=500），保证数据集在真实配置下可整批加载
        try (var reader = new java.io.StringReader(raw.toString())) {
            parsed = EvalDatasetParser.parseWithDiagnostics(reader, objectMapper, 500);
        }

        blocked = rawCases.stream().filter(node -> "blocked".equals(text(node, "status"))).toList();
        counted = rawCases.stream().filter(node -> !"blocked".equals(text(node, "status"))).toList();
    }

    @Test
    void parsesWithV2SchemaAndUniqueIds() {
        assertTrue(parsed.cases().size() == rawCases.size(),
                "EvalDatasetParser 解析出的用例数应与数据行数一致");
        assertFalse(parsed.legacyDataset(), "eval-v2 是全字段 v2 数据集，不应标 legacyDataset");
        assertTrue(parsed.warnings().isEmpty(), () -> "不应有 v2 字段缺省告警: " + parsed.warnings());

        List<String> ids = rawCases.stream().map(node -> text(node, "id")).toList();
        Set<String> unique = new HashSet<>(ids);
        assertEquals(ids.size(), unique.size(), () -> {
            Map<String, Long> duplicated = ids.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream().filter(e -> e.getValue() > 1)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return "id 必须唯一，重复: " + duplicated;
        });
    }

    @Test
    void everyCaseCarriesFullV2FieldsAndEvidence() {
        for (JsonNode node : rawCases) {
            String id = text(node, "id");
            assertNotNull(text(node, "question"), id + " 缺 question");
            assertFalse(text(node, "question").isBlank(), id + " question 为空");
            assertNotNull(text(node, "referenceAnswer"), id + " 缺 referenceAnswer");
            assertTrue(node.get("expectedRefusal").isBoolean(), id + " expectedRefusal 必须是布尔");
            int difficulty = node.get("difficulty").asInt();
            assertTrue(difficulty >= 1 && difficulty <= 3, id + " difficulty 必须在 1-3: " + difficulty);
            assertTrue(node.get("expectedFileNames").isArray() && node.get("expectedFileNames").size() > 0,
                    id + " 缺 expectedFileNames");
            // 成功判定：每题都有 expectedEvidence（blocked 题也不例外）
            assertTrue(node.get("expectedEvidence").isArray() && node.get("expectedEvidence").size() > 0,
                    id + " 缺 expectedEvidence");
            for (var name : node.get("expectedFileNames")) {
                assertTrue(CORPUS_FILES.contains(name.asText()),
                        id + " 引用了语料清单之外的文件: " + name.asText());
            }
            String type = text(node, "type");
            assertEquals(type, id.split("-")[0], id + " id 前缀应与 type 一致");
            String docNumber = id.split("-")[1];
            for (var name : node.get("expectedFileNames")) {
                assertTrue(name.asText().startsWith(docNumber + "-"),
                        id + " 的文档编号 " + docNumber + " 与文件 " + name.asText() + " 不匹配");
            }
        }
    }

    @Test
    void countedVolumeAndSplitCountsMatchPlan() {
        // 失败处理分支：07 的 blocked 题不计入分母——分母只统计非 blocked 题
        assertTrue(counted.size() >= MIN_TOTAL, () -> "计数题 " + counted.size() + " < " + MIN_TOTAL
                + "（blocked 题 " + blocked.size() + " 条不计入）");
        long dev = counted.stream().filter(node -> "dev".equals(text(node, "split"))).count();
        long test = counted.stream().filter(node -> "test".equals(text(node, "split"))).count();
        assertEquals(EXPECTED_DEV, dev, "dev 计数题应为 " + EXPECTED_DEV);
        assertEquals(EXPECTED_TEST, test, "test 计数题应为 " + EXPECTED_TEST);
        assertEquals(counted.size(), dev + test, "计数题必须全部落在 dev/test 两个 split 上");
    }

    @Test
    void blockedScanDocQuestionsAreFlaggedAndExcluded() {
        // 成功判定：07 的题不超过 10 条且标注 ocr=true
        assertTrue(blocked.size() <= MAX_BLOCKED_07, () -> "07 的题 " + blocked.size() + " > " + MAX_BLOCKED_07);
        for (JsonNode node : blocked) {
            String id = text(node, "id");
            assertTrue(node.get("ocr").asBoolean(false), id + " blocked 题必须标注 ocr=true");
            assertTrue(node.get("expectedFileNames").toString().contains(SCAN_DOC),
                    id + " blocked 题应指向 07 扫描件");
        }
        // 其余 9 篇不得有 blocked 题；计数题里也不允许出现 07（否则它就混进了分母）
        for (JsonNode node : counted) {
            assertFalse(node.get("expectedFileNames").toString().contains(SCAN_DOC),
                    text(node, "id") + " 是计数题却引用 07 扫描件——07 的题必须全部 blocked");
        }
    }

    @Test
    void countedCoverageMeetsPerDocRequirement() {
        Map<String, Long> byDoc = counted.stream()
                .flatMap(node -> expectedNames(node).stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertTrue(byDoc.keySet().size() >= MIN_DOCS_COVERED,
                () -> "被覆盖文档 " + byDoc.keySet().size() + " < " + MIN_DOCS_COVERED + ": " + byDoc);
        for (Map.Entry<String, Long> entry : byDoc.entrySet()) {
            // 失败处理分支：除 07（blocked）外的每篇文档必须满足覆盖要求
            assertTrue(entry.getValue() >= MIN_PER_DOC,
                    () -> entry.getKey() + " 计数题 " + entry.getValue() + " < " + MIN_PER_DOC);
        }
    }

    @Test
    void negativeSamplesMeetQuotaAndPrefixConvention() {
        long negatives = counted.stream()
                .filter(node -> node.get("expectedRefusal").asBoolean(false))
                .count();
        assertTrue(negatives >= MIN_NEGATIVE, () -> "负样本 " + negatives + " < " + MIN_NEGATIVE);
        for (JsonNode node : counted) {
            String id = text(node, "id");
            boolean refusal = node.get("expectedRefusal").asBoolean(false);
            assertEquals("neg".equals(text(node, "type")), refusal,
                    id + " 非 blocked 题的 neg 类型与 expectedRefusal 必须一一对应");
        }
    }

    private static List<String> expectedNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (var name : node.get("expectedFileNames")) {
            out.add(name.asText().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText().trim();
    }
}
