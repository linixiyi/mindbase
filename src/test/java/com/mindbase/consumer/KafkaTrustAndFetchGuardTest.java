package com.mindbase.consumer;

import com.mindbase.model.FileProcessingTask;
import com.mindbase.service.DocumentService;
import com.mindbase.service.ParseService;
import com.mindbase.service.UploadService;
import com.mindbase.service.VectorizationService;
import com.mindbase.service.reliability.VectorizationLease;
import com.mindbase.service.reliability.VectorizationLeaseService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task-0009 收口测试：
 * 1) application.yml 的 spring.json.trusted.packages 不含通配符且清单固化为 com.mindbase.model；
 * 2) 取文件来源只允许 http/https 且 host 命中 mindbase.storage.allowed-hosts（默认从 minio.endpoint 派生），
 *    file://、169.254.169.254、外部 host 在发起任何连接之前被拒；
 * 3) 非法来源的任务写 error 后直接提交，不进重试/死信。
 */
class KafkaTrustAndFetchGuardTest {

    private final ParseService parseService = mock(ParseService.class);
    private final VectorizationService vectorizationService = mock(VectorizationService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final VectorizationLeaseService leaseService = mock(VectorizationLeaseService.class);
    private final UploadService uploadService = mock(UploadService.class);
    private final VectorizationLease lease = new VectorizationLease(1L, "md5", LocalDateTime.now());
    private FileProcessingConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new FileProcessingConsumer(parseService, vectorizationService, documentService, leaseService, uploadService);
        // 未显式配置 allowed-hosts：默认从 minio.endpoint 派生
        ReflectionTestUtils.setField(consumer, "allowedHostsConfig", "");
        ReflectionTestUtils.setField(consumer, "minioEndpoint", "http://localhost:9000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void trustedPackagesAreNarrowedFromWildcardAndListIsFixated() throws Exception {
        // 直接读主配置文件：src/test/resources/application.yml（测试 fixture，不在本任务 boundary 内）
        // 在测试 classpath 上会遮蔽主配置，按路径读才能断言生产配置本身
        java.nio.file.Path mainYml = java.nio.file.Path.of("src", "main", "resources", "application.yml");
        assertTrue(java.nio.file.Files.exists(mainYml),
                "必须从仓库根运行测试，找不到 " + mainYml + "（当前工作目录: " + System.getProperty("user.dir") + "）");
        List<String> rawLines = java.nio.file.Files.readAllLines(mainYml);

        Map<String, Object> root;
        try (InputStream in = java.nio.file.Files.newInputStream(mainYml)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> kafka = (Map<String, Object>) spring.get("kafka");
        Map<String, Object> consumerProps = (Map<String, Object>) kafka.get("consumer");
        Map<String, Object> properties = (Map<String, Object>) consumerProps.get("properties");

        Object trusted = properties.get("spring.json.trusted.packages");
        // 固化清单：当前只有 com.mindbase.model（Kafka 消息值只有 FileProcessingTask）；确需扩包时更新此处清单
        assertEquals("com.mindbase.model", String.valueOf(trusted), "白名单必须收窄到项目消息包，禁止回退 *");

        // mindbase.storage.allowed-hosts 配置存在（默认为空，由消费端从 minio.endpoint 派生）
        Map<String, Object> mindbase = (Map<String, Object>) root.get("mindbase");
        Map<String, Object> storage = (Map<String, Object>) mindbase.get("storage");
        assertNotNull(storage.get("allowed-hosts"), "mindbase.storage.allowed-hosts 必须存在");

        // 原文兜底断言：所有 trusted.packages 配置行都不含通配符
        List<String> trustedLines = rawLines.stream()
                .filter(line -> line.strip().startsWith("spring.json.trusted.packages"))
                .toList();
        assertEquals(1, trustedLines.size(), "trusted.packages 只应配置一处");
        assertTrue(!trustedLines.get(0).contains("*"), "trusted.packages 配置行不得包含通配符: " + trustedLines.get(0));
    }

    @Test
    void fileSchemeIsRejectedBeforeAnyConnection() {
        FileProcessingConsumer.UntrustedStorageTargetException e = assertThrows(
                FileProcessingConsumer.UntrustedStorageTargetException.class,
                () -> consumer.resolveTrustedFetchUrl("file:///etc/passwd"));
        assertTrue(e.getMessage().contains("file"), "拒绝原因应指明非法协议: " + e.getMessage());
        // 守卫是纯校验，不应触发任何服务交互（不发起连接、不读存储）
        verifyNoInteractions(uploadService, parseService, vectorizationService, documentService, leaseService);
    }

    @Test
    void cloudMetadataHostIsRejected() {
        assertThrows(FileProcessingConsumer.UntrustedStorageTargetException.class,
                () -> consumer.resolveTrustedFetchUrl("http://169.254.169.254/latest/meta-data/"));
        assertThrows(FileProcessingConsumer.UntrustedStorageTargetException.class,
                () -> consumer.resolveTrustedFetchUrl("https://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void externalHostIsRejected() {
        assertThrows(FileProcessingConsumer.UntrustedStorageTargetException.class,
                () -> consumer.resolveTrustedFetchUrl("https://expired.example.com/merged/md5"));
    }

    @Test
    void minioEndpointHostIsAllowedByDefault() throws Exception {
        // 默认派生：minio.endpoint=http://localhost:9000 → localhost 放行（http 与 https 均为允许的 scheme）
        URL url = consumer.resolveTrustedFetchUrl("http://localhost:9000/uploads/md5/a.pdf?X-Amz-Signature=abc");
        assertEquals("localhost", url.getHost());
        assertEquals(9000, url.getPort());
        URL httpsUrl = consumer.resolveTrustedFetchUrl("https://localhost:9000/uploads/md5/a.pdf");
        assertEquals("localhost", httpsUrl.getHost());
        // 纯校验路径，不应触发任何服务交互
        verifyNoInteractions(uploadService, parseService, vectorizationService, documentService, leaseService);
    }

    @Test
    void explicitAllowedHostsIsHonoredAndReplacesDefaultDerivation() throws Exception {
        ReflectionTestUtils.setField(consumer, "allowedHostsConfig", " files.internal , MinIO.internal ");
        assertEquals("minio.internal", normalizedHostOf(consumer.resolveTrustedFetchUrl("https://minio.internal/bucket/x.pdf")));
        // 显式配置后默认派生不再生效：localhost 不再被放行
        assertThrows(FileProcessingConsumer.UntrustedStorageTargetException.class,
                () -> consumer.resolveTrustedFetchUrl("http://localhost:9000/x.pdf"));
    }

    @Test
    void untrustedFilePathFailsTaskWithoutRetryOrParse() throws Exception {
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        when(uploadService.getMergedFileStream("1", "md5")).thenThrow(new RuntimeException("minio down"));

        FileProcessingTask task = new FileProcessingTask("md5", "file:///etc/passwd", "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1");

        // 不抛异常：消息正常提交，不进 Kafka 重试/死信
        assertDoesNotThrow(() -> consumer.processTask(record(task)));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(leaseService).fail(eq(lease), error.capture());
        assertTrue(error.getValue() instanceof FileProcessingConsumer.UntrustedStorageTargetException,
                "任务 error 应为取文件收口拒绝: " + error.getValue());
        verifyNoInteractions(parseService, vectorizationService);
    }

    @Test
    void endpointHostDerivationCoversBareEndpointsAndLowerCase() {
        assertEquals("localhost", FileProcessingConsumer.endpointHost("http://localhost:9000"));
        assertEquals("minio.internal", FileProcessingConsumer.endpointHost("minio.internal:9000"));
        assertEquals("minio.example.com", FileProcessingConsumer.endpointHost("https://MinIO.Example.COM/"));
        assertNull(FileProcessingConsumer.endpointHost(""));
        assertNull(FileProcessingConsumer.endpointHost(null));
    }

    private String normalizedHostOf(URL url) {
        return url.getHost().toLowerCase(java.util.Locale.ROOT);
    }

    /** 消费入口从 ConsumerRecord 里取任务，测试用同样方式包装（与 FileProcessingConsumerIdempotencyTest 一致） */
    private ConsumerRecord<String, FileProcessingTask> record(FileProcessingTask task) {
        return new ConsumerRecord<>("file-processing-topic1", 0, 0L, task.getFileMd5(), task);
    }
}
