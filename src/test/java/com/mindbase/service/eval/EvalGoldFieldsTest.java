package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.entity.SearchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0003 gold 字段：文件级 md5 与分块级 chunkId。
 * <p>
 * 成功判定对应：
 * <ul>
 *   <li>md5/chunkId 解析：{@code expectedFileMd5s} 与 {@code expectedChunkIds} 都被解析器接受；
 *       chunkIds 与 expectedFileNames 长度不一致时 warn 不崩（分块级 gold 不可用，回退短语）；</li>
 *   <li>回填后覆盖率 100%：打包资源 eval-v2.jsonl 的 126 条 gold 全部带与
 *       eval-datasets/manifest.json 一致的 expectedFileMd5s；chunkId 回填（task-0005，
 *       D8）后按实际状态分支断言：分块级 gold 可用 → {@code goldFallback=null}，
 *       未回填或匹配不到写 [] → 短语回退 {@code goldFallback=phrase}（绝不写空串）；</li>
 *   <li>chunkId 优先于短语：标注 chunkIds 后命中按 chunkId 判定（同文件其他分块即使含证据短语也不算命中），
 *       未标注或检索结果未带 chunkId（未索引）时回退短语判定并标 {@code goldFallback=phrase}。</li>
 * </ul>
 * 全部离线：只用打包资源与仓库内 manifest，不依赖外部服务。
 */
class EvalGoldFieldsTest {

    private static final String DATASET_RESOURCE = "/eval/eval-v2.jsonl";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** eval-datasets/manifest.json 的 文件名 -> md5（测试启动时从仓库根读取） */
    private static Map<String, String> manifestMd5s;

    @BeforeAll
    static void loadManifest() throws Exception {
        Path manifestPath = Path.of("eval-datasets", "manifest.json");
        assertTrue(Files.exists(manifestPath),
                "eval-datasets/manifest.json 必须存在（surefire 工作目录应为仓库根）");
        JsonNode documents = objectMapper.readTree(Files.readString(manifestPath)).path("documents");
        assertTrue(documents.isArray() && !documents.isEmpty(), "manifest 缺少 documents 数组");
        manifestMd5s = new HashMap<>();
        documents.forEach(doc -> {
            String name = doc.path("name").asText("").trim();
            String md5 = doc.path("md5").asText("").trim();
            if (!name.isEmpty() && !md5.isEmpty()) {
                manifestMd5s.put(name, md5);
            }
        });
        assertEquals(10, manifestMd5s.size(), "manifest 应有 10 篇文档的 md5");
    }

    // ---- ① md5 / chunkId 解析 ----

    @Test
    void parsesExpectedFileMd5sAndChunkIds() throws Exception {
        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q", "referenceAnswer": "r", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["a.pdf", "b.pdf"], "expectedFileMd5s": ["m-a", "m-b"], "expectedChunkIds": ["3", "12"], "expectedEvidence": ["短语"]}
                """);

        assertFalse(result.legacyDataset());
        assertTrue(result.warnings().isEmpty(), "md5/chunkIds 与文件数一致时不应有 warn");
        EvalCase evalCase = result.cases().get(0);
        assertEquals(List.of("m-a", "m-b"), evalCase.expectedFileMd5s());
        assertEquals(List.of("3", "12"), evalCase.expectedChunkIds());
        assertTrue(evalCase.hasExpectedChunkIds());
        assertTrue(evalCase.hasUsableChunkGold(), "chunkIds 与文件名等长即分块级 gold 可用");
        assertNull(evalCase.goldFallback(), "分块级 gold 可用时不是短语回退口径");
    }

    @Test
    void chunkIdLengthMismatchWarnsButDoesNotCrash() throws Exception {
        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q", "referenceAnswer": "r", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "expectedFileNames": ["a.pdf"], "expectedChunkIds": ["3", "7"], "expectedEvidence": ["短语"]}
                """);

        EvalCase evalCase = result.cases().get(0);
        assertEquals(1, result.cases().size(), "长度不一致只 warn，不崩、不丢用例");
        assertFalse(result.legacyDataset(), "gold 标注问题不算 legacy（不影响 v2 结构字段判定）");
        assertTrue(result.warnings().stream()
                        .anyMatch(w -> w.contains("第 1 行") && w.contains("c1")
                                && w.contains("expectedChunkIds 与 expectedFileNames 长度不一致")),
                "warnings 应包含长度失配明细，实际: " + result.warnings());
        assertEquals(List.of("3", "7"), evalCase.expectedChunkIds(), "原始标注保留，供审计");
        assertFalse(evalCase.hasUsableChunkGold(), "失配后分块级 gold 不可用");
        assertEquals(EvalCase.GOLD_FALLBACK_PHRASE, evalCase.goldFallback(), "失配回退短语口径");
        // 回退后按短语判定：同文件其他分块（chunkId=7）含短语即命中
        SearchResult phraseChunk = new SearchResult("m-a", 7, "这里出现短语", 0.9, "a.pdf");
        assertTrue(RetrievalMetrics.isRelevant(phraseChunk, evalCase), "失配回退短语：含短语即命中");
    }

    // ---- ② 回填后覆盖率 100%（126 条 gold 全部带与 manifest 一致的文件 md5） ----

    @Test
    void backfilledDatasetHasFullMd5Coverage() throws Exception {
        List<EvalCase> cases;
        try (var reader = new InputStreamReader(
                EvalGoldFieldsTest.class.getResourceAsStream(DATASET_RESOURCE), StandardCharsets.UTF_8)) {
            assertNotNull(reader, "打包资源 " + DATASET_RESOURCE + " 必须存在");
            cases = EvalDatasetParser.parse(reader, objectMapper, 1000);
        }

        assertEquals(126, cases.size(), "eval-v2.jsonl 应有 126 条用例");
        for (EvalCase evalCase : cases) {
            assertTrue(evalCase.hasExpectedDocs(), "用例 " + evalCase.id() + " 缺期望文档");
            List<String> md5s = evalCase.expectedFileMd5s();
            assertFalse(md5s.isEmpty(), "回填后用例 " + evalCase.id() + " 应有 expectedFileMd5s");
            assertEquals(evalCase.expectedFileNames().size(), md5s.size(),
                    "用例 " + evalCase.id() + " 的 md5 应与文件名平行等长");
            for (int i = 0; i < md5s.size(); i++) {
                String name = evalCase.expectedFileNames().get(i);
                String expected = manifestMd5s.get(name);
                assertNotNull(expected, "manifest 缺少文档 " + name + "（用例 " + evalCase.id() + "）");
                assertEquals(expected, md5s.get(i),
                        "用例 " + evalCase.id() + " 的 md5 应与 manifest 一致（" + name + "）");
            }
            // 写回后口径（D8，task-0005）：分块级 gold 可用（chunkIds 非空且与文件数平行）→
            // goldFallback 为 null；不可用（未回填，或匹配不到证据短语写 []）→ 仍回退短语口径。
            // 按数据集实际状态分支断言，而不是只断言单边。
            if (evalCase.hasUsableChunkGold()) {
                assertNull(evalCase.goldFallback(),
                        "chunkId 已回填的用例 " + evalCase.id() + " 应是分块级 gold 可用口径（goldFallback=null）");
                for (String chunkId : evalCase.expectedChunkIds()) {
                    assertFalse(chunkId.isBlank(),
                            "用例 " + evalCase.id() + " 的 chunkId 不应为空白（匹配不到应写 [] 而不是空串，"
                                    + "空串在 chunkId 口径下永假且不回退短语）");
                }
            } else {
                assertEquals(EvalCase.GOLD_FALLBACK_PHRASE, evalCase.goldFallback(),
                        "未回填 chunkId 的用例 " + evalCase.id() + " 应回退短语口径（goldFallback=phrase）");
            }
        }
    }

    // ---- ③ chunkId 优先于短语 ----

    @Test
    void chunkIdAnnotationTakesPriorityOverPhrase() {
        EvalCase evalCase = caseWithChunkGold();

        // chunkId=3 命中：即使文本不含证据短语也算相关（chunkId 是更精确的分块标注）
        SearchResult expectedChunk = new SearchResult("m-a", 3, "不含短语的内容", 0.9, "a.pdf");
        assertTrue(RetrievalMetrics.isRelevant(expectedChunk, evalCase),
                "chunkId 命中优先于短语：分块 3 无短语也算相关");
        // 同文件 chunkId=7：即使文本含证据短语也不算相关
        SearchResult otherChunkWithPhrase = new SearchResult("m-a", 7, "这里出现秘密短语", 0.9, "a.pdf");
        assertFalse(RetrievalMetrics.isRelevant(otherChunkWithPhrase, evalCase),
                "chunkId 标注后，同文件其他分块含短语也不算命中");
        // 排序判定同样走 chunkId 口径：含短语的分块 7 排第一也不会抢到 firstRelevantRank
        assertEquals(2, RetrievalMetrics.firstRelevantRank(
                        List.of(otherChunkWithPhrase, expectedChunk), evalCase, 5),
                "第一条相关分块是 chunkId=3 的那条（rank=2），不是含短语的分块 7");
        assertEquals(1, RetrievalMetrics.firstRelevantRank(List.of(expectedChunk), evalCase, 5));
        assertNull(RetrievalMetrics.goldFallback(evalCase, List.of(expectedChunk), 5),
                "chunkId 在生效，不是短语回退口径");
    }

    @Test
    void unindexedResultsFallBackToPhraseAndAreMarked() {
        EvalCase evalCase = caseWithChunkGold();

        // 未索引：检索结果不带 chunkId → 回退短语判定
        SearchResult phraseOnly = new SearchResult("m-a", null, "这里出现秘密短语", 0.9, "a.pdf");
        assertTrue(RetrievalMetrics.isRelevant(phraseOnly, evalCase), "未索引回退短语：含短语即命中");
        SearchResult noPhrase = new SearchResult("m-a", null, "无关内容", 0.9, "a.pdf");
        assertFalse(RetrievalMetrics.isRelevant(noPhrase, evalCase), "未索引回退短语：无短语不命中");
        assertEquals(EvalCase.GOLD_FALLBACK_PHRASE,
                RetrievalMetrics.goldFallback(evalCase, List.of(phraseOnly, noPhrase), 5),
                "top-K 全部未带 chunkId 时用例标 goldFallback=phrase");
        // 结果为空不算回退（没有发生判定）
        assertNull(RetrievalMetrics.goldFallback(evalCase, List.of(), 5));
    }

    @Test
    void chunkGoldMatchesResultKeyedByMd5WhenFileNameMissing() {
        EvalCase evalCase = caseWithChunkGold();

        // 文件名缺失时按 md5 对位（md5 与文件名平行）：md5=m-a 是第 0 个文件，期望 chunkId=3
        SearchResult byMd5 = new SearchResult("m-a", 3, "text", 0.9, null);
        assertTrue(RetrievalMetrics.isRelevant(byMd5, evalCase));
        SearchResult wrongChunkByMd5 = new SearchResult("m-a", 9, "text", 0.9, null);
        assertFalse(RetrievalMetrics.isRelevant(wrongChunkByMd5, evalCase));
    }

    @Test
    void unannotatedCaseKeepsPhraseBehaviorAndMarksGoldFallback() {
        EvalCase evalCase = new EvalCase("c1", "q", List.of(), List.of("a.pdf"), "r",
                List.of("秘密短语"));

        assertTrue(evalCase.expectedChunkIds().isEmpty(), "未标注时 chunkIds 为空列表");
        assertEquals(EvalCase.GOLD_FALLBACK_PHRASE, evalCase.goldFallback(),
                "未标注 chunkIds 时用例标 goldFallback=phrase");
        // 旧行为保持：同文件任何分块只要含短语就命中（chunkId 无标注，不参与判定）
        SearchResult chunk7WithPhrase = new SearchResult("m-a", 7, "这里出现秘密短语", 0.9, "a.pdf");
        assertTrue(RetrievalMetrics.isRelevant(chunk7WithPhrase, evalCase));
        SearchResult chunk7WithoutPhrase = new SearchResult("m-a", 7, "无关内容", 0.9, "a.pdf");
        assertFalse(RetrievalMetrics.isRelevant(chunk7WithoutPhrase, evalCase));
        assertEquals(EvalCase.GOLD_FALLBACK_PHRASE,
                RetrievalMetrics.goldFallback(evalCase, List.of(chunk7WithPhrase), 5));
    }

    @Test
    void fileLevelCaseWithoutEvidenceIsNotPhraseFallback() {
        EvalCase evalCase = new EvalCase("c1", "q", List.of("m-a"), List.of("a.pdf"), "r");

        assertNull(evalCase.goldFallback(), "没有证据短语就没有短语回退（文件级口径），不标 phrase");
        SearchResult anyChunk = new SearchResult("m-a", 42, "任意内容", 0.9, "a.pdf");
        assertTrue(RetrievalMetrics.isRelevant(anyChunk, evalCase), "文件级口径：期望文件的任何分块都算命中");
        assertNull(RetrievalMetrics.goldFallback(evalCase, List.of(anyChunk), 5));
    }

    // ---- fixtures ----

    /** 分块级 gold 用例：期望 a.pdf 的 chunkId=3，证据短语只是回退口径 */
    private static EvalCase caseWithChunkGold() {
        return new EvalCase("c1", "q", List.of("m-a"), List.of("a.pdf"), "r",
                List.of("秘密短语"), null, null, null, null, List.of("3"));
    }

    private static EvalDatasetParser.ParseResult parse(String jsonl) throws Exception {
        return EvalDatasetParser.parseWithDiagnostics(new java.io.StringReader(jsonl), objectMapper, 10);
    }
}
