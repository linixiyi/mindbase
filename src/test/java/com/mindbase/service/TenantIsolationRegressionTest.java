package com.mindbase.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.mindbase.entity.EsDocument;
import com.mindbase.model.ChunkInfo;
import com.mindbase.model.DocumentVector;
import com.mindbase.model.FileProcessingTask;
import com.mindbase.model.FileUpload;
import com.mindbase.client.EmbeddingClient;
import com.mindbase.repository.ChunkInfoRepository;
import com.mindbase.repository.DocumentVectorRepository;
import com.mindbase.repository.FileUploadRepository;
import com.mindbase.service.index.KnowledgeIndexNames;
import com.mindbase.service.reliability.FileProcessingOutboxService;
import io.minio.ComposeObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户隔离回归（task_0002）：清理、合并、删除、重试全部按上传者收口。
 * 模拟用户 A / 用户 B 上传相同字节文件（同 fileMd5）：
 * - 合并对象键带 userId（merged/{userId}/{fileMd5}）；
 * - ES 文档 ID 带 userId（userId_fileMd5_chunkId），且向量化只携带本任务上传者的分块；
 * - ES deleteByFileMd5 调用带 userId，查询 DSL 含 fileMd5 与 userId 两个 term；
 * - B 的清理（clearIndexedData）只删 B 的记录，不触碰 A 的；
 * - 重试按 (fileMd5, requesterId) 定位发起者自己的上传记录，不取全库第一条。
 */
class TenantIsolationRegressionTest {

    private static final String FILE_MD5 = "md5-shared";
    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    private static final ModelProviderConfigService.ActiveProviderView PROVIDER = new ModelProviderConfigService.ActiveProviderView(
            "aliyun", "阿里云", "openai-compatible", "https://dashscope", "text-embedding-v4", "k", 2048);
    private static final String MODEL_VERSION = "aliyun:text-embedding-v4:2048";

    private final MinioClient minioClient = mock(MinioClient.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final ChunkInfoRepository chunkInfoRepository = mock(ChunkInfoRepository.class);
    private final DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
    private final ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
    private final FileProcessingOutboxService fileProcessingOutboxService = mock(FileProcessingOutboxService.class);

    @BeforeEach
    void setUp() {
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(FILE_MD5, USER_B))
                .thenReturn(Optional.of(fileUpload(USER_B, null)));
    }

    @Test
    void mergedObjectKeyContainsUserId() throws Exception {
        UploadService uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);

        ChunkInfo chunk = new ChunkInfo();
        chunk.setFileMd5(FILE_MD5);
        chunk.setUserId(USER_B);
        chunk.setChunkIndex(0);
        chunk.setStoragePath("chunks/user-b/md5-shared/0");
        when(chunkInfoRepository.findByFileMd5AndUserIdOrderByChunkIndexAsc(FILE_MD5, USER_B)).thenReturn(List.of(chunk));
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("https://minio.example/signed");
        when(fileUploadRepository.completeMergeIfMerging(eq(10L), eq(FileUpload.STATUS_MERGING), eq(FileUpload.STATUS_COMPLETED), any()))
                .thenReturn(1);
        when(chunkInfoRepository.deleteByFileMd5AndUserId(FILE_MD5, USER_B)).thenReturn(1);

        uploadService.mergeChunks(FILE_MD5, "report.pdf", USER_B);

        ArgumentCaptor<ComposeObjectArgs> compose = ArgumentCaptor.forClass(ComposeObjectArgs.class);
        verify(minioClient).composeObject(compose.capture());
        assertEquals("merged/user-b/" + FILE_MD5, compose.getValue().object(),
                "合并对象键必须带上传者维度，两个用户上传相同字节文件时各写各的对象");
    }

    @Test
    void esDocumentIdContainsUserIdAndForeignChunksAreNotIndexed() {
        VectorizationService vectorizationService = new VectorizationService();
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ReflectionTestUtils.setField(vectorizationService, "embeddingClient", embeddingClient);
        ReflectionTestUtils.setField(vectorizationService, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(vectorizationService, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(vectorizationService, "modelProviderConfigService", providerConfig());
        ReflectionTestUtils.setField(vectorizationService, "batchSize", 10);

        when(embeddingClient.embedWithProvider(anyList(), eq(USER_B), eq(EmbeddingClient.UsageType.UPLOAD), eq(PROVIDER)))
                .thenAnswer(invocation -> {
                    List<?> texts = invocation.getArgument(0);
                    return new EmbeddingClient.EmbeddingUsageResult(
                            texts.stream().map(t -> new float[]{1f}).toList(), texts.size() * 5, MODEL_VERSION);
                });
        // 同一 fileMd5 下 A、B 各自的分块共存（各自 chunkId 从 0 开始），B 的向量化只能带上 B 的分块
        List<DocumentVector> coexistingChunks = List.of(
                chunk(USER_A, 0, "text-a-0"),
                chunk(USER_B, 0, "text-b-0"));
        when(documentVectorRepository.findByFileMd5OrderByChunkIdAsc(eq(FILE_MD5), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(coexistingChunks, Pageable.ofSize(10), false));

        vectorizationService.vectorizeWithUsage(FILE_MD5, USER_B, "TEAM", false, USER_B);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EsDocument>> batches = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchService).bulkIndex(batches.capture());
        List<EsDocument> docs = batches.getValue();
        assertEquals(1, docs.size(), "A 的分块不能被写成 B 的 ES 文档");
        assertEquals(USER_B + "_" + FILE_MD5 + "_0", docs.get(0).getId(), "ES 文档 ID 必须带上传者维度");
        assertEquals("text-b-0", docs.get(0).getTextContent());
        assertEquals(USER_B, docs.get(0).getUserId());
    }

    @Test
    void esDeleteByFileMd5QueryCarriesUserIdTerm() throws Exception {
        ElasticsearchClient esClient = mock(ElasticsearchClient.class);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(mock(DeleteByQueryResponse.class));
        ElasticsearchService elasticsearchService = new ElasticsearchService();
        ReflectionTestUtils.setField(elasticsearchService, "esClient", esClient);

        // 全索引变体：删除文档时清理所有版本索引
        elasticsearchService.deleteByFileMd5(USER_B, FILE_MD5);
        ArgumentCaptor<DeleteByQueryRequest> allIndices = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient).deleteByQuery(allIndices.capture());
        assertEquals(List.of(KnowledgeIndexNames.ALL_INDICES_PATTERN), allIndices.getValue().index());
        assertEquals(Map.of("fileMd5", FILE_MD5, "userId", USER_B), termsOf(allIndices.getValue()),
                "删除查询 DSL 必须同时含 fileMd5 与 userId term，B 的清理不能命中 A 的文档");

        // 指定索引变体：线上别名清理
        elasticsearchService.deleteByFileMd5(KnowledgeIndexNames.ALIAS, USER_B, FILE_MD5);
        ArgumentCaptor<DeleteByQueryRequest> alias = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient, org.mockito.Mockito.times(2)).deleteByQuery(alias.capture());
        assertEquals(List.of(KnowledgeIndexNames.ALIAS), alias.getValue().index());
        assertEquals(Map.of("fileMd5", FILE_MD5, "userId", USER_B), termsOf(alias.getValue()));
    }

    @Test
    void userBCleanupDoesNotTouchUserARecords() {
        DocumentService documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(documentService, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(documentService, "stringRedisTemplate", stringRedisTemplate());

        documentService.clearIndexedData(USER_B, FILE_MD5);

        // B 的清理只命中 B 自己的 ES 文档与向量记录
        verify(elasticsearchService).deleteByFileMd5(KnowledgeIndexNames.ALIAS, USER_B, FILE_MD5);
        verify(documentVectorRepository).deleteByUserIdAndFileMd5(USER_B, FILE_MD5);
        // A 的记录不可触碰：不允许出现带 user-a 的删除，也不允许退回无上传者维度的全量删除
        verify(elasticsearchService, never()).deleteByFileMd5(eq(USER_A), anyString());
        verify(elasticsearchService, never()).deleteByFileMd5(anyString(), eq(USER_A), anyString());
        verify(documentVectorRepository, never()).deleteByUserIdAndFileMd5(eq(USER_A), anyString());
        verify(documentVectorRepository, never()).deleteByFileMd5(anyString());
    }

    @Test
    void retryLocatesRequestersOwnUploadRecord() {
        DocumentService documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(documentService, "fileProcessingOutboxService", fileProcessingOutboxService);

        FileUpload bRecord = fileUpload(USER_B, FileUpload.VECTORIZATION_STATUS_FAILED);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(FILE_MD5, USER_B))
                .thenReturn(Optional.of(bRecord));

        documentService.enqueueAsyncVectorizationRetry(FILE_MD5, USER_B);

        // 重试按发起者自己的记录收口，不取全库第一条（同 MD5 下 A 的记录不能被 B 的重试碰走）
        verify(fileUploadRepository).findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(FILE_MD5, USER_B);
        verify(fileUploadRepository, never()).findFirstByFileMd5OrderByCreatedAtDesc(anyString());

        ArgumentCaptor<FileProcessingTask> taskCaptor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(bRecord), taskCaptor.capture(), eq(true));
        assertEquals(USER_B, taskCaptor.getValue().getUserId(), "重发任务必须携带发起者自己的 userId");
    }

    private static ModelProviderConfigService providerConfig() {
        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING)).thenReturn(PROVIDER);
        return providerConfig;
    }

    private static org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate() {
        return mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    }

    /** 提取删除查询 bool.filter 里的 term 字段与取值 */
    private static Map<String, String> termsOf(DeleteByQueryRequest request) {
        Map<String, String> terms = new HashMap<>();
        for (Query filter : request.query().bool().filter()) {
            if (filter.isTerm()) {
                terms.put(filter.term().field(), filter.term().value().stringValue());
            }
        }
        return terms;
    }

    private static FileUpload fileUpload(String userId, String vectorizationStatus) {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setId(10L);
        fileUpload.setFileMd5(FILE_MD5);
        fileUpload.setFileName("report.pdf");
        fileUpload.setUserId(userId);
        fileUpload.setOrgTag("TEAM");
        fileUpload.setTotalSize(1024L);
        fileUpload.setVectorizationStatus(vectorizationStatus);
        return fileUpload;
    }

    private static DocumentVector chunk(String userId, int chunkId, String text) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(FILE_MD5);
        vector.setUserId(userId);
        vector.setChunkId(chunkId);
        vector.setTextContent(text);
        return vector;
    }
}
