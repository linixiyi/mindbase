package com.mindbase.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.mindbase.client.DeepSeekClient;
import com.mindbase.client.EmbeddingClient;
import com.mindbase.client.RerankClient;
import com.mindbase.config.ContextualRetrievalProperties;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.entity.EsDocument;
import com.mindbase.entity.SearchResult;
import com.mindbase.model.FileUpload;
import com.mindbase.model.SearchOutcome;
import com.mindbase.model.SearchResponse;
import com.mindbase.model.User;
import com.mindbase.repository.FileUploadRepository;
import com.mindbase.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * task_0005 检索失败三分类与阈值降级的语义验证：
 * 权限异常 → NO_PERMISSION_MATCH；召回异常（混合 + 纯文本兜底均失败）→ RECALL_FAILED；
 * rerank 阈值全滤 → BELOW_THRESHOLD 且结果非空（RRF 前 N 兜底）；正常命中 → HIT；
 * search_knowledge 工具结果文本按 outcome 区分；ChatHandler 引用标注数据流保持不变。
 */
class SearchOutcomeSemanticsTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankClient rerankClient = mock(RerankClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrgTagCacheService orgTagCacheService = mock(OrgTagCacheService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final ContextualRetrievalProperties contextualProperties = new ContextualRetrievalProperties();

    private HybridSearchService service;
    private List<Hit<EsDocument>> bm25Hits;
    private List<Hit<EsDocument>> knnHits;
    private RuntimeException esFailure;
    private RuntimeException knnFailure;

    @BeforeEach
    void setUp() throws Exception {
        service = new HybridSearchService(esClient, embeddingClient, rerankClient, properties, contextualProperties,
                userRepository, orgTagCacheService, fileUploadRepository, Runnable::run);

        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("dept-a"));
        when(embeddingClient.embed(anyList(), anyString(), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        FileUpload upload = new FileUpload();
        upload.setFileMd5("md5");
        upload.setFileName("manual.pdf");
        when(fileUploadRepository.findByFileMd5In(anyList())).thenReturn(List.of(upload));

        // 文档 kw 只有关键词命中（BM25 第 1），vec 只有语义相近（KNN 第 2），both 两路都召回
        bm25Hits = List.of(hit("kw", 1), hit("both", 2));
        knnHits = List.of(hit("both", 2), hit("vec", 3));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            if (esFailure != null) {
                throw esFailure;
            }
            if (request.knn().isEmpty()) {
                return response(bm25Hits);
            }
            if (knnFailure != null) {
                throw knnFailure;
            }
            return response(knnHits);
        });
    }

    @Test
    void permissionFailureYieldsNoPermissionMatch() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        SearchResponse response = service.searchWithPermissionDetailed("q", "7", 5);

        assertEquals(SearchOutcome.NO_PERMISSION_MATCH, response.outcome());
        assertTrue(response.results().isEmpty(), "权限解析失败时不执行检索，结果为空");
        try {
            verify(esClient, never()).search(any(SearchRequest.class), eq(EsDocument.class));
        } catch (java.io.IOException neverThrown) {
            // never() 不会真正触发调用，catch 仅为满足 search 的受检异常签名
        }
        verify(rerankClient, never()).rerank(anyString(), anyList());
    }

    @Test
    void recallFailureYieldsRecallFailed() {
        esFailure = new RuntimeException("es down");

        SearchResponse response = service.searchWithPermissionDetailed("q", "7", 5);

        assertEquals(SearchOutcome.RECALL_FAILED, response.outcome());
        assertTrue(response.results().isEmpty(), "混合检索与纯文本兜底均失败时结果为空");
        assertNotNull(response.detail());
    }

    @Test
    void allBelowThresholdFallsBackToRrfTopK() {
        // 默认 scoreThreshold=0.1：两个候选的 rerank 分数都低于阈值 → 阈值全滤
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(0, 0.05),
                new RerankClient.RerankHit(1, 0.04)));

        SearchResponse response = service.searchWithPermissionDetailed("q", "7", 2);

        assertEquals(SearchOutcome.BELOW_THRESHOLD, response.outcome());
        assertFalse(response.results().isEmpty(), "阈值全滤时按 RRF 前 N 兜底，结果非空");
        // RRF 顺序：both(1/62+1/61) > kw(1/61) > vec(1/62)，topK=2 取前两条
        assertEquals(List.of(2, 1), response.results().stream().map(SearchResult::getChunkId).toList());
        assertTrue(response.results().stream().allMatch(r -> "HYBRID_RRF".equals(r.getRetrievalMode())),
                "兜底结果按 RRF 融合排序标记检索模式");
        assertNotNull(response.detail());
    }

    @Test
    void normalHitYieldsHit() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(1, 0.91),
                new RerankClient.RerankHit(0, 0.55),
                new RerankClient.RerankHit(2, 0.05)));

        SearchResponse response = service.searchWithPermissionDetailed("q", "7", 5);

        assertEquals(SearchOutcome.HIT, response.outcome());
        assertEquals(List.of(1, 2), response.results().stream().map(SearchResult::getChunkId).toList());
        assertTrue(response.results().stream().allMatch(r -> "HYBRID".equals(r.getRetrievalMode())));
    }

    @Test
    void degradedTextOnlyFallbackStillHits() {
        // KNN 路失败但纯文本兜底有结果：检索降级但仍算正常命中（与"两条路都异常"的 RECALL_FAILED 区分）
        knnFailure = new RuntimeException("knn failed");

        SearchResponse response = service.searchWithPermissionDetailed("q", "7", 5);

        assertEquals(SearchOutcome.HIT, response.outcome());
        assertFalse(response.results().isEmpty());
        assertTrue(response.results().stream().allMatch(r -> "TEXT_ONLY".equals(r.getRetrievalMode())));
    }

    @Test
    void toolResultTextsDifferByOutcome() {
        List<SearchResult> results = List.of(
                new SearchResult("md5", 1, "text-1", 0.9, "7", "org", false, "a.txt"));
        when(searchServiceMock.searchWithPermissionDetailed("q-hit", "u1", 5)).thenReturn(SearchResponse.hit(results));
        when(searchServiceMock.searchWithPermissionDetailed("q-noperm", "u1", 5))
                .thenReturn(SearchResponse.noPermissionMatch("权限解析失败"));
        when(searchServiceMock.searchWithPermissionDetailed("q-recall", "u1", 5))
                .thenReturn(SearchResponse.recallFailed("召回失败"));
        when(searchServiceMock.searchWithPermissionDetailed("q-below", "u1", 5))
                .thenReturn(SearchResponse.belowThreshold(results, "阈值全滤"));
        AgentToolRegistry registry = newRegistry();

        AgentToolRegistry.ToolExecutionResult hit =
                registry.executeTool("search_knowledge", Map.of("query", "q-hit"), "u1");
        AgentToolRegistry.ToolExecutionResult noPerm =
                registry.executeTool("search_knowledge", Map.of("query", "q-noperm"), "u1");
        AgentToolRegistry.ToolExecutionResult recall =
                registry.executeTool("search_knowledge", Map.of("query", "q-recall"), "u1");
        AgentToolRegistry.ToolExecutionResult below =
                registry.executeTool("search_knowledge", Map.of("query", "q-below"), "u1");

        assertTrue(hit.content().startsWith("检索到 1 个知识库片段"));
        assertTrue(noPerm.content().contains("权限"), "权限分类有专属提示文本");
        assertTrue(recall.content().contains("召回失败"), "召回失败分类有专属提示文本");
        assertTrue(below.content().startsWith("检索到 1 个知识库片段"));
        assertTrue(below.content().contains("阈值"), "阈值兜底分类附带着重说明");
        // 四种 outcome 的工具文本两两不同，不再一律"未检索到"
        assertEquals(4, Set.of(hit.content(), noPerm.content(), recall.content(), below.content()).size());
        // outcome 进入工具数据供下游（ChatHandler 引用格式化）使用；results 原样透传，引用标注数据流不变
        assertEquals("BELOW_THRESHOLD", below.data().get("outcome"));
        assertSame(results, below.data().get("results"));
    }

    @Test
    void genuineEmptyHitKeepsOriginalNoMatchText() {
        when(searchServiceMock.searchWithPermissionDetailed("q", "u1", 5)).thenReturn(SearchResponse.hit(List.of()));
        AgentToolRegistry registry = newRegistry();

        AgentToolRegistry.ToolExecutionResult result =
                registry.executeTool("search_knowledge", Map.of("query", "q"), "u1");

        assertEquals("未检索到相关知识库片段。", result.content(), "正常检索无匹配仍沿用原文案");
        assertEquals("HIT", result.data().get("outcome"));
    }

    @Test
    void chatHandlerLegacyToolDataKeepsExactReferenceText() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null, null);
        SearchResult result = new SearchResult("md5", 1, "text-1", 0.5, "7", "org", false, "a.txt");

        // 未带 outcome 的旧工具数据：引用格式化输出与改造前逐字节一致
        String legacy = handler.appendReferencesFromSearchTool("generation", "question",
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "",
                        Map.of("results", List.of(result))));

        assertEquals(AgentToolRegistry.formatSearchResults(List.of(result), 1), legacy);
    }

    @Test
    void chatHandlerAppendsThresholdGuidanceButKeepsReferenceList() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null, null);
        SearchResult result = new SearchResult("md5", 1, "text-1", 0.5, "7", "org", false, "a.txt");

        String below = handler.appendReferencesFromSearchTool("generation", "question",
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "",
                        Map.of("results", List.of(result), "outcome", "BELOW_THRESHOLD")));

        assertTrue(below.startsWith(AgentToolRegistry.formatSearchResults(List.of(result), 1)),
                "引用编号列表不变");
        assertTrue(below.contains("阈值"), "仅追加阈值兜底的处置提示");
        // 引用映射照常落库：引用标注数据流零改动
        assertEquals("md5", handler.getReferenceDetail("generation", 1).fileMd5());
    }

    @Test
    void chatHandlerPassesThroughFailureTextForEmptyResults() {
        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        ChatHandler handler = new ChatHandler(null, null, null, null, null, state,
                null, null, null, null, null, null);
        String failureText = AgentToolRegistry.outcomeText(SearchOutcome.RECALL_FAILED, List.of());

        String content = handler.appendReferencesFromSearchTool("generation", "question",
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, failureText,
                        Map.of("results", List.of(), "outcome", "RECALL_FAILED")));

        assertEquals(failureText, content, "空结果直接透传 outcome 专属文本，不进入引用编号流程");
    }

    private final HybridSearchService searchServiceMock = mock(HybridSearchService.class);

    private AgentToolRegistry newRegistry() {
        return new AgentToolRegistry(searchServiceMock, mock(DeepSeekClient.class), mock(StringRedisTemplate.class),
                mock(ElasticsearchClient.class), mock(FileUploadRepository.class), null);
    }

    private static Hit<EsDocument> hit(String id, int chunkId) {
        EsDocument doc = new EsDocument(id, "md5", chunkId, "text-" + id, null, null, null, "v1", "7", "dept-a", false);
        return Hit.of(h -> h.index("knowledge_base_v1").id(id).score(1.0).source(doc));
    }

    private static co.elastic.clients.elasticsearch.core.SearchResponse<EsDocument> response(List<Hit<EsDocument>> hits) {
        // com.mindbase.model.SearchResponse 已占用简单名，ES 客户端响应类型此处用全限定名
        return co.elastic.clients.elasticsearch.core.SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hits).total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq)).maxScore(1.0)));
    }
}
