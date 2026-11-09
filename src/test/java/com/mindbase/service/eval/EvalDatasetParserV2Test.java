package com.mindbase.service.eval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_0005 数据集 schema v2：split(dev|test)/type/difficulty(1-3)/expectedRefusal。
 * 成功判定：缺 split 旧数据按 dev 处理并 warn；重复 id 解析失败；非法 split 解析失败。
 */
class EvalDatasetParserV2Test {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalDatasetParser.ParseResult parse(String jsonl) throws Exception {
        return EvalDatasetParser.parseWithDiagnostics(new StringReader(jsonl), objectMapper, 10);
    }

    @Test
    void missingSplitDefaultsToDevWithWarnAndLegacyFlag() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger parserLogger = (Logger) LoggerFactory.getLogger(EvalDatasetParser.class);
        parserLogger.addAppender(appender);
        parserLogger.setLevel(Level.WARN);
        EvalDatasetParser.ParseResult result;
        try {
            result = parse("""
                    {"id": "c1", "question": "q1", "referenceAnswer": "a1"}
                    {"id": "c2", "question": "q2", "split": "test", "type": "fact", "difficulty": 1, "expectedRefusal": false, "referenceAnswer": "a2"}
                    """);
        } finally {
            parserLogger.detachAppender(appender);
        }

        // 成功判定 ①：缺 split 的旧数据按 dev 处理并 warn
        assertEquals("dev", result.cases().get(0).split());
        assertEquals("test", result.cases().get(1).split());
        assertTrue(result.legacyDataset(), "缺省 v2 字段的数据集应标 legacyDataset=true");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("第 1 行") && w.contains("split")));
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("legacyDataset=true")), "缺省 split 必须产生 warn 日志");
    }

    @Test
    void explicitDevAndTestSplitsAreParsedWithoutLegacyFlag() throws Exception {
        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q1", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "referenceAnswer": "a1"}
                {"id": "c2", "question": "q2", "split": "test", "type": "neg", "difficulty": 3, "expectedRefusal": true, "referenceAnswer": "a2"}
                """);

        assertEquals("dev", result.cases().get(0).split());
        assertEquals("test", result.cases().get(1).split());
        assertTrue(result.cases().get(1).expectsRefusal());
        assertFalse(result.cases().get(0).expectsRefusal());
        assertFalse(result.legacyDataset(), "v2 字段齐全的数据集不是 legacy");
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void missingOptionalV2FieldsAlsoFlagLegacy() throws Exception {
        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q1", "split": "dev", "referenceAnswer": "a1"}
                """);

        assertTrue(result.legacyDataset());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("type")
                && w.contains("difficulty") && w.contains("expectedRefusal")));
        assertNull(result.cases().get(0).type());
        assertNull(result.cases().get(0).difficulty());
        assertNull(result.cases().get(0).expectedRefusal());
    }

    @Test
    void duplicateIdFailsParsing() {
        // 成功判定 ②：重复 id 解析失败
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        {"id": "c1", "question": "q1", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "referenceAnswer": "a1"}
                        {"id": "c1", "question": "q2", "split": "dev", "type": "fact", "difficulty": 1, "expectedRefusal": false, "referenceAnswer": "a2"}
                        """));
        assertTrue(error.getMessage().contains("第 2 行"));
        assertTrue(error.getMessage().contains("第 1 行"));
        assertTrue(error.getMessage().contains("c1"));
    }

    @Test
    void explicitIdCollidingWithAutoIdFailsParsing() {
        // 第 1 行没有 id（自动取 line-1），第 2 行显式用 "line-1" 会撞车，报告里 caseId 就不唯一了
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("""
                        {"question": "q1", "referenceAnswer": "a1"}
                        {"id": "line-1", "question": "q2", "referenceAnswer": "a2"}
                        """));
        assertTrue(error.getMessage().contains("重复"));
        assertTrue(error.getMessage().contains("line-1"));
    }

    @Test
    void illegalSplitFailsParsing() {
        // 成功判定 ③：非法 split 解析失败（只允许 dev|test，区分大小写）
        for (String bad : new String[]{"train", "DEV", "val"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("{\"id\": \"c1\", \"question\": \"q\", \"split\": \"" + bad
                            + "\", \"referenceAnswer\": \"a\"}"),
                    () -> "split=" + bad + " 应被拒绝");
            assertTrue(error.getMessage().contains("第 1 行"), () -> error.getMessage());
            assertTrue(error.getMessage().contains("split"), () -> error.getMessage());
        }
    }

    @Test
    void nonTextualSplitFailsParsing() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"id\": \"c1\", \"question\": \"q\", \"split\": 3, \"referenceAnswer\": \"a\"}"));
        assertTrue(error.getMessage().contains("split"));
    }

    @Test
    void difficultyMustBeIntegerBetweenOneAndThree() throws Exception {
        for (String bad : new String[]{"0", "4", "-1", "\"high\"", "2.5"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("{\"id\": \"c1\", \"question\": \"q\", \"split\": \"dev\", \"difficulty\": " + bad
                            + ", \"referenceAnswer\": \"a\"}"),
                    () -> "difficulty=" + bad + " 应被拒绝");
            assertTrue(error.getMessage().contains("difficulty"), () -> error.getMessage());
        }

        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q1", "split": "dev", "difficulty": 1, "referenceAnswer": "a"}
                {"id": "c2", "question": "q2", "split": "dev", "difficulty": 2, "referenceAnswer": "a"}
                {"id": "c3", "question": "q3", "split": "dev", "difficulty": 3, "referenceAnswer": "a"}
                """);
        assertEquals(1, result.cases().get(0).difficulty());
        assertEquals(2, result.cases().get(1).difficulty());
        assertEquals(3, result.cases().get(2).difficulty());
    }

    @Test
    void expectedRefusalMustBeBoolean() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("{\"id\": \"c1\", \"question\": \"q\", \"split\": \"dev\", \"expectedRefusal\": \"yes\","
                        + " \"referenceAnswer\": \"a\"}"));
        assertTrue(error.getMessage().contains("expectedRefusal"));

        EvalDatasetParser.ParseResult result = parse("""
                {"id": "c1", "question": "q1", "split": "dev", "expectedRefusal": true, "referenceAnswer": "a"}
                {"id": "c2", "question": "q2", "split": "dev", "expectedRefusal": false, "referenceAnswer": "a"}
                """);
        assertTrue(result.cases().get(0).expectsRefusal());
        assertFalse(result.cases().get(1).expectsRefusal());
        assertFalse(result.cases().get(1).expectedRefusal() == null);
    }

    @Test
    void bundledMindbaseDatasetConformsToV2Schema() throws Exception {
        EvalDatasetParser.ParseResult result;
        try (var in = getClass().getResourceAsStream("/eval/mindbase-eval-v1.jsonl")) {
            result = EvalDatasetParser.parseWithDiagnostics(
                    new InputStreamReader(in, StandardCharsets.UTF_8), objectMapper, 100);
        }

        List<EvalCase> cases = result.cases();
        assertEquals(53, cases.size(), "50 道原有题 + 3 道负向权限题（negperm-）");
        assertFalse(result.legacyDataset(), "捆绑数据集已迁移到 v2 schema，不应标 legacy");
        assertTrue(result.warnings().isEmpty());
        for (EvalCase evalCase : cases) {
            assertTrue("dev".equals(evalCase.split()) || "test".equals(evalCase.split()),
                    () -> evalCase.id() + " split 非法: " + evalCase.split());
            assertTrue(evalCase.type() != null && !evalCase.type().isBlank(),
                    () -> evalCase.id() + " 缺 type");
            assertTrue(evalCase.difficulty() != null && evalCase.difficulty() >= 1 && evalCase.difficulty() <= 3,
                    () -> evalCase.id() + " difficulty 非法: " + evalCase.difficulty());
            boolean isNeg = evalCase.id().startsWith("neg-") || evalCase.id().startsWith("negperm-");
            assertEquals(isNeg, evalCase.expectsRefusal(),
                    () -> evalCase.id() + " expectedRefusal 应为 " + isNeg);
        }
        assertEquals(4, cases.stream().filter(c -> c.id().startsWith("neg-") && c.expectsRefusal()).count(),
                "neg- 无答案应拒答题 4 条");
        assertEquals(3, cases.stream().filter(c -> c.id().startsWith("negperm-") && c.expectsRefusal()).count(),
                "negperm- 负向权限应拒答题 3 条");
        assertEquals(7, cases.stream().filter(EvalCase::expectsRefusal).count(), "应拒答题合计 4+3=7 条");
    }

    @Test
    void bundledSampleDatasetConformsToV2Schema() throws Exception {
        EvalDatasetParser.ParseResult result;
        try (var in = getClass().getResourceAsStream("/eval/sample-eval.jsonl")) {
            result = EvalDatasetParser.parseWithDiagnostics(
                    new InputStreamReader(in, StandardCharsets.UTF_8), objectMapper, 100);
        }

        assertEquals(5, result.cases().size());
        assertFalse(result.legacyDataset());
        assertTrue(result.cases().stream().allMatch(c -> "dev".equals(c.split())));
        assertTrue(result.cases().stream().allMatch(EvalCase::hasExpectedDocs));
    }
}
