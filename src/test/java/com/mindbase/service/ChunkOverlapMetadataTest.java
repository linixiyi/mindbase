package com.mindbase.service;

import com.mindbase.config.ParentContextProperties;
import com.mindbase.entity.SearchResult;
import com.mindbase.model.DocumentVector;
import com.mindbase.repository.DocumentVectorRepository;
import com.mindbase.service.chunk.ChunkTextMerger;
import com.mindbase.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task-0008：分块重叠不进正文。
 * <ul>
 *     <li>相邻块正文互不包含：ParseService 不再把上一块结尾前置进本块 textContent，
 *         重叠长度只记录到日志元数据（ES 文档结构在任务 boundary 外无元数据位，不入 ES、不加 DB 列）；</li>
 *     <li>父块扩展仍能取到邻块：ParentContextExpander 的 neighbour-chunks 窗口照常工作，
 *         父块文本就是干净的邻接拼接，不再有重复句子；</li>
 *     <li>合并与旧版对同一 fixture 等价：ChunkTextMerger 去掉"12 字相同"启发式后，
 *         对无重叠输入与旧格式重叠输入的结果和旧实现一致。</li>
 * </ul>
 * 全程 mock/内存构造，不依赖任何外部服务。
 */
class ChunkOverlapMetadataTest {

    private static final String FILE_MD5 = "md5-overlap-test";
    private static final int CHUNK_SIZE = 60;
    private static final int OVERLAP_SIZE = 100;
    private static final int MIN_CHUNK_SIZE = 30;

    /**
     * 四段互不相同的段落：每段 ≤ chunkSize 且任意两段之和超过 chunkSize，确保切成 4 个相邻分块；
     * 每段是单个句子且长度 ≤ 有效 overlap 窗口 min(overlapSize, chunkSize-1)=59，
     * 旧实现会把整个前块前置进本块，因此旧重叠长度可解析推出等于前块长度。
     */
    private static final String FIXTURE = "第一段讲述系统登录的完整流程与账号安全策略，所有口令都必须哈希后存储。\n\n"
            + "第二段讲述检索服务的超时控制策略，长查询会降级为关键词召回以避免请求阻塞。\n\n"
            + "第三段讲述文件解析的并发闸门设计，堆内存超过阈值时会暂停消息消费形成背压。\n\n"
            + "第四段讲述评测报告的聚合逻辑，逐例分数会汇总成覆盖率并参与发布判定。";

    private final ParentContextProperties parentProperties = new ParentContextProperties();
    private final DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
    private ParentContextExpander expander;

    @BeforeEach
    void setUp() {
        parentProperties.setEnabled(true);
        parentProperties.setNeighbourChunks(1);
        parentProperties.setMaxCharsPerHit(4000);
        parentProperties.setMaxTotalChars(20000);
        expander = new ParentContextExpander(parentProperties, repository);
    }

    private ParseService parseService() {
        ParseService parseService = new ParseService();
        ReflectionTestUtils.setField(parseService, "chunkSize", CHUNK_SIZE);
        ReflectionTestUtils.setField(parseService, "overlapSize", OVERLAP_SIZE);
        ReflectionTestUtils.setField(parseService, "minChunkSize", MIN_CHUNK_SIZE);
        return parseService;
    }

    @Test
    void adjacentChunkBodiesDoNotContainEachOther() {
        ParseService parseService = parseService();

        List<String> chunks = parseService.splitTextIntoChunksWithSemantics(FIXTURE, CHUNK_SIZE);

        assertEquals(4, chunks.size(), "fixture 应切成 4 个相邻分块");
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1);
            String current = chunks.get(i);
            assertFalse(current.contains(previous), "后块正文不应包含前块（旧实现会把前块结尾前置进本块）");
            assertFalse(previous.contains(current), "前块正文不应包含后块");
        }

        // 重叠长度只记录到分块元数据（当前实现内为日志），正文保持原样
        int[] overlapLengths = parseService.recordSemanticOverlapMetadata(FILE_MD5, chunks, 0, CHUNK_SIZE);
        assertEquals(chunks.size(), overlapLengths.length, "重叠长度数组应与分块对齐");
        assertEquals(0, overlapLengths[0], "首块没有前块，重叠长度为 0");
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).length(), overlapLengths[i],
                    "记录的重叠长度应等于旧实现会前置进本块的前块文本长度");
            assertTrue(overlapLengths[i] > 0);
        }
    }

    @Test
    void parentExpansionStillFetchesNeighbourChunks() {
        List<String> chunks = parseService().splitTextIntoChunksWithSemantics(FIXTURE, CHUNK_SIZE);
        List<DocumentVector> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(FILE_MD5);
            vector.setChunkId(i + 1);
            vector.setTextContent(chunks.get(i));
            vectors.add(vector);
        }
        when(repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(eq(FILE_MD5), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int from = invocation.getArgument(1);
                    int to = invocation.getArgument(2);
                    return vectors.stream()
                            .filter(v -> v.getChunkId() >= from && v.getChunkId() <= to)
                            .toList();
                });

        SearchResult hit = new SearchResult(FILE_MD5, 2, chunks.get(1), 0.9, "7", "org", false,
                "doc.pdf", null, "anchor-2", "HYBRID", null);

        SearchResult expanded = expander.expand(List.of(hit)).get(0);

        String expectedParent = chunks.get(0) + "\n\n" + chunks.get(1) + "\n\n" + chunks.get(2);
        assertEquals(expectedParent, expanded.getParentContext(),
                "父块仍取到前后邻块，且正文无重叠后就是干净的邻接拼接（无重复句子）");
        assertEquals(FILE_MD5 + "#1-3", expanded.getParentKey());
        assertEquals(chunks.get(1), expanded.getMatchedChunkText(), "引用与证据片段仍指向命中的小分块");
    }

    @Test
    void mergeStaysEquivalentToLegacyImplementationOnSameFixtures() {
        List<String> cleanChunks = parseService().splitTextIntoChunksWithSemantics(FIXTURE, CHUNK_SIZE);

        List<List<String>> fixtures = List.of(
                cleanChunks,
                // 旧格式：上一块结尾以 "重叠句\n\n" 固定形状前置进下一块（历史数据仍长这样）
                List.of("第一段讲端口。默认端口是 8080。",
                        "默认端口是 8080。\n\n第二段讲超时。超时时间是 30 秒。"),
                List.of("a", "", "b"),
                List.of());

        for (List<String> fixture : fixtures) {
            assertEquals(legacyMerge(fixture), ChunkTextMerger.merge(fixture),
                    "对同一 fixture 新旧合并结果应等价");
        }

        // "12 字相同"字面启发式已从合并路径移除：无 "\n\n" 分隔的字面重叠不再剥离（旧实现会剥离）
        List<String> literalOverlap = List.of(
                "the quick brown fox jumps over the lazy dog",
                "over the lazy dog and runs away");
        assertEquals("the quick brown fox jumps over the lazy dog\n\nover the lazy dog and runs away",
                ChunkTextMerger.merge(literalOverlap), "无分隔符的字面重叠不再被启发式剥离");
        assertNotEquals(legacyMerge(literalOverlap), ChunkTextMerger.merge(literalOverlap),
                "该输入是新旧实现唯一可观察的行为差异来源（旧格式重叠从不以这种形状产生）");
    }

    /**
     * task-0008 之前 ChunkTextMerger.merge 的原样拷贝（重叠头剥离 + "12 字相同"字面探测），仅用于等价对照。
     */
    private static String legacyMerge(List<String> orderedChunks) {
        if (orderedChunks == null || orderedChunks.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        String previous = null;
        for (String chunk : orderedChunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            String text = chunk.trim();
            String addition = previous == null ? text : legacyStripLeadingOverlap(previous, text);
            if (!addition.isEmpty()) {
                if (!merged.isEmpty()) {
                    merged.append("\n\n");
                }
                merged.append(addition);
            }
            previous = text;
        }
        return merged.toString();
    }

    private static String legacyStripLeadingOverlap(String previous, String next) {
        int separator = next.indexOf("\n\n");
        if (separator > 0) {
            String head = next.substring(0, separator);
            String compactHead = head.replaceAll("\\s+", "");
            if (!compactHead.isEmpty() && previous.replaceAll("\\s+", "").endsWith(compactHead)) {
                return next.substring(separator + 2).trim();
            }
        }
        int maxProbe = Math.min(Math.min(previous.length(), next.length() - 1), 600);
        for (int length = maxProbe; length >= 12; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(length).trim();
            }
        }
        return next;
    }
}
