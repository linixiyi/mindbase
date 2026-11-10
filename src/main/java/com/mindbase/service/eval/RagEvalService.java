package com.mindbase.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.client.EmbeddingClient;
import com.mindbase.config.AiProperties;
import com.mindbase.config.ContextualRetrievalProperties;
import com.mindbase.config.HybridSearchProperties;
import com.mindbase.config.ParentContextProperties;
import com.mindbase.config.RagEvalProperties;
import com.mindbase.exception.CustomException;
import com.mindbase.service.index.KnowledgeIndexNames;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 离线 RAG 评测任务：异步逐条跑用例，汇总 Recall@K / MRR / nDCG@K / 评委平均分 / 准确率 /
 * 拒答正确率 / faithfulness / citation precision/recall 和问题归因，报告写成 JSON 文件。
 * 单条用例的生成与引用指标由 {@link RagEvalCaseRunner} 按 {@link GenerationMetrics} 口径产出，
 * 数据集级汇总经 {@link EvalReportAggregator#summarize} 进入报告 summary。
 * 同一时间只允许一个评测任务；同一数据集改动分块前后各跑一次，用 label 区分即可对比。
 */
@Service
public class RagEvalService {

    private static final Logger logger = LoggerFactory.getLogger(RagEvalService.class);
    private static final String CLASSPATH_PREFIX = "classpath:eval/";
    private static final Pattern SAFE_CLASSPATH_NAME = Pattern.compile("[\\w\\-]+(/[\\w\\-]+)*\\.jsonl");
    private static final Pattern UNSAFE_LABEL_CHARS = Pattern.compile("[^\\w\\-\\u4e00-\\u9fa5]+");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final int MAX_KEPT_RUNS = 20;
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final Pattern GIT_COMMIT_PATTERN = Pattern.compile("[0-9a-f]{40}");
    /** 来源信息取不到时的占位值：字段保留，值标 unknown */
    static final String SOURCE_UNKNOWN = "unknown";
    private static volatile String cachedGitCommit;

    private final RagEvalCaseRunner caseRunner;
    private final RagEvalProperties properties;
    private final HybridSearchProperties searchProperties;
    private final ParentContextProperties parentContextProperties;
    private final ContextualRetrievalProperties contextualProperties;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final EmbeddingClient embeddingClient;
    private final ExecutorService executor;
    private final Map<String, RunProgress> runs = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile RunProgress latest;

    @Autowired
    public RagEvalService(RagEvalCaseRunner caseRunner,
                          RagEvalProperties properties,
                          HybridSearchProperties searchProperties,
                          ParentContextProperties parentContextProperties,
                          ContextualRetrievalProperties contextualProperties,
                          ObjectMapper objectMapper,
                          AiProperties aiProperties,
                          EmbeddingClient embeddingClient) {
        this(caseRunner, properties, searchProperties, parentContextProperties, contextualProperties, objectMapper,
                aiProperties, embeddingClient, Executors.newSingleThreadExecutor(r -> new Thread(r, "rag-eval")));
    }

    RagEvalService(RagEvalCaseRunner caseRunner,
                   RagEvalProperties properties,
                   HybridSearchProperties searchProperties,
                   ParentContextProperties parentContextProperties,
                   ContextualRetrievalProperties contextualProperties,
                   ObjectMapper objectMapper,
                   ExecutorService executor) {
        this(caseRunner, properties, searchProperties, parentContextProperties, contextualProperties, objectMapper,
                null, null, executor);
    }

    RagEvalService(RagEvalCaseRunner caseRunner,
                   RagEvalProperties properties,
                   HybridSearchProperties searchProperties,
                   ParentContextProperties parentContextProperties,
                   ContextualRetrievalProperties contextualProperties,
                   ObjectMapper objectMapper,
                   AiProperties aiProperties,
                   EmbeddingClient embeddingClient,
                   ExecutorService executor) {
        this.caseRunner = caseRunner;
        this.properties = properties;
        this.searchProperties = searchProperties;
        this.parentContextProperties = parentContextProperties;
        this.contextualProperties = contextualProperties;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.embeddingClient = embeddingClient;
        this.executor = executor;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public List<EvalCase> loadDataset(String datasetPath) {
        if (datasetPath == null || datasetPath.isBlank()) {
            throw new CustomException("datasetPath 不能为空", HttpStatus.BAD_REQUEST);
        }
        String path = datasetPath.trim();
        try {
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String name = path.substring(CLASSPATH_PREFIX.length());
                if (!SAFE_CLASSPATH_NAME.matcher(name).matches()) {
                    throw new CustomException("非法的 classpath 数据集名称: " + name, HttpStatus.BAD_REQUEST);
                }
                ClassPathResource resource = new ClassPathResource("eval/" + name);
                if (!resource.exists()) {
                    throw new CustomException("数据集不存在: " + path, HttpStatus.NOT_FOUND);
                }
                try (InputStream in = resource.getInputStream()) {
                    return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            }
            Path base = Paths.get(properties.getDatasetDir()).toAbsolutePath().normalize();
            Path file = base.resolve(path).normalize();
            if (!file.startsWith(base)) {
                throw new CustomException("数据集只能放在 eval.dataset-dir 目录下: " + base, HttpStatus.BAD_REQUEST);
            }
            if (!Files.isRegularFile(file)) {
                throw new CustomException("数据集不存在: " + file, HttpStatus.NOT_FOUND);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parse(reader);
            }
        } catch (CustomException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new CustomException("数据集格式错误: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            throw new CustomException("读取数据集失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public List<EvalCase> parseInline(String jsonl) {
        try {
            return parse(new StringReader(jsonl == null ? "" : jsonl));
        } catch (IllegalArgumentException e) {
            throw new CustomException("数据集格式错误: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            throw new CustomException("读取数据集失败: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /* 数据集级诊断：旧格式数据集（缺 v2 字段）时为 true，报告 config 里标注 legacyDataset=true（评估缺口修复） */
    private volatile boolean legacyDataset = false;

    private List<EvalCase> parse(Reader reader) throws Exception {
        EvalDatasetParser.ParseResult result =
                EvalDatasetParser.parseWithDiagnostics(reader, objectMapper, Math.max(properties.getMaxCases(), 1));
        this.legacyDataset = result.legacyDataset();
        for (String warning : result.warnings()) {
            logger.warn("评测数据集兼容告警: {}", warning);
        }
        return result.cases();
    }

    /**
     * 提交评测任务（异步执行）
     *
     * @param searchUserId 检索以该用户身份执行（权限过滤与线上一致）
     */
    public synchronized RunStatus start(List<EvalCase> cases, String datasetSource, Integer topK, String label,
                                        String searchUserId, String operator) {
        RunProgress running = latest;
        if (running != null && running.state == RunState.RUNNING) {
            throw new CustomException("已有评测任务在运行: " + running.runId, HttpStatus.CONFLICT);
        }
        if (cases == null || cases.isEmpty()) {
            throw new CustomException("数据集为空", HttpStatus.BAD_REQUEST);
        }
        int k = topK == null ? properties.getDefaultTopK() : topK;
        if (k < 1 || k > properties.getMaxTopK()) {
            throw new CustomException("topK 需在 1 到 " + properties.getMaxTopK() + " 之间", HttpStatus.BAD_REQUEST);
        }

        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RunProgress progress = new RunProgress(runId, sanitizeLabel(label), datasetSource, k, cases.size(), operator);
        latest = progress;
        remember(progress);
        HumanReviewSampler sampler = HumanReviewSampler.of(properties.getHumanReviewSampleRate(), properties.getRandomSeed());
        try {
            executor.execute(() -> runAll(progress, cases, searchUserId, sampler));
        } catch (Exception e) {
            progress.fail(e);
            throw new CustomException("评测任务提交失败: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        logger.info("RAG 评测任务已提交: runId={}, label={}, dataset={}, cases={}, topK={}, operator={}",
                runId, progress.label, datasetSource, cases.size(), k, operator);
        return progress.snapshot();
    }

    public Optional<RunStatus> getStatus(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(RunProgress::snapshot);
    }

    public Optional<RunStatus> getLatestStatus() {
        return Optional.ofNullable(latest).map(RunProgress::snapshot);
    }

    public List<RunStatus> listStatuses() {
        synchronized (runs) {
            List<RunStatus> statuses = new ArrayList<>();
            runs.values().forEach(progress -> statuses.add(progress.snapshot()));
            Collections.reverse(statuses);
            return statuses;
        }
    }

    public Optional<JsonNode> readReport(String runId) {
        Optional<Path> reportPath = findReportPath(runId);
        if (reportPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(reportPath.get().toFile()));
        } catch (Exception e) {
            throw new CustomException("读取评测报告失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 报告文件路径。任务状态只在内存里保留最近 20 个，重启后按文件名（..._{runId}.json）在输出目录里找
     */
    public Optional<Path> findReportPath(String runId) {
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            return Optional.empty();
        }
        RunProgress progress = runs.get(runId);
        if (progress != null) {
            return Optional.ofNullable(progress.reportPath).map(Paths::get);
        }
        Path dir = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String suffix = "_" + runId + ".json";
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(suffix)).findFirst();
        } catch (Exception e) {
            throw new CustomException("查找评测报告失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 人工判定更新后，把评委与人工一致率挂到内存里的任务状态上，列表和详情接口直接可见
     */
    void recordHumanAgreement(String runId, JudgeHumanAgreement agreement) {
        RunProgress progress = runs.get(runId);
        if (progress != null) {
            progress.humanAgreement = agreement;
        }
    }

    void runAll(RunProgress progress, List<EvalCase> cases, String searchUserId, HumanReviewSampler sampler) {
        try {
            List<EvalCaseResult> results = new ArrayList<>(cases.size());
            for (EvalCase evalCase : cases) {
                results.add(caseRunner.run(evalCase, searchUserId, progress.topK, sampler));
                progress.completedCases = results.size();
            }
            EvalCaseResult.TokenUsage totalUsage = EvalReportAggregator.tokenUsage(results);
            EvalReport report = new EvalReport(progress.runId, progress.label, progress.datasetSource, progress.topK,
                    progress.startedAt, Instant.now(),
                    EvalReport.SCHEMA_VERSION, gitCommit(), KnowledgeIndexNames.ALIAS, embeddingModel(), promptVersion(),
                    configSnapshot(progress.topK), totalUsage,
                    EvalReport.Cost.of(totalUsage, pricingInputPer1k(), pricingOutputPer1k(), pricingCurrency()),
                    EvalReportAggregator.summarize(results), EvalReportAggregator.diagnosisGuide(), List.copyOf(results));
            Path reportPath = writeReport(report);
            progress.succeed(report.summary(), reportPath.toString());
            logger.info("RAG 评测完成: runId={}, label={}, report={}, summary={}",
                    progress.runId, progress.label, reportPath, report.summary());
        } catch (Exception e) {
            progress.fail(e);
            logger.error("RAG 评测失败: runId={}", progress.runId, e);
        }
    }

    /**
     * 报告先写到同目录临时文件，完整写出后原子 rename 成正式文件名：
     * 序列化或落盘中断时正式文件名下不会出现半份 JSON（临时文件由 finally 清理，
     * 即使进程被杀，遗留的 .tmp 文件也不会命中任何按 runId 查找报告的路径）。
     */
    Path writeReport(EvalReport report) throws Exception {
        Path dir = Paths.get(properties.getOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String fileName = FILE_TIME.format(report.startedAt()) + "_" + report.label() + "_" + report.runId() + ".json";
        Path file = dir.resolve(fileName);
        Path temp = dir.resolve(fileName + ".tmp-" + UUID.randomUUID());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), report);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return file;
    }

    /**
     * 报告来源的 git 提交，进程生命周期内只解析一次。
     * 部署或测试环境没有 .git（或没有 git 命令）时返回 unknown，字段保留不省略。
     */
    private static String gitCommit() {
        String cached = cachedGitCommit;
        if (cached != null) {
            return cached;
        }
        String commit = gitCommitOf(Paths.get("").toAbsolutePath());
        cachedGitCommit = commit;
        return commit;
    }

    /**
     * 在指定目录解析 git 提交；任何失败（无 .git、无 git 命令、超时）都返回 unknown。
     */
    static String gitCommitOf(Path workingDir) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return SOURCE_UNKNOWN;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                String commit = line == null ? "" : line.trim();
                return GIT_COMMIT_PATTERN.matcher(commit).matches() ? commit : SOURCE_UNKNOWN;
            }
        } catch (Exception e) {
            return SOURCE_UNKNOWN;
        }
    }

    /** embedding 模型版本，与 ES 物理索引的版本命名同源；provider 未配置时为 null（字段保留） */
    private String embeddingModel() {
        if (embeddingClient == null) {
            return null;
        }
        try {
            return embeddingClient.currentModelVersion();
        } catch (Exception e) {
            logger.warn("读取 embedding 模型版本失败: {}", e.getMessage());
            return null;
        }
    }

    /** 生成提示词的版本：对生效的 ai.prompt.rules 取 sha256 前 8 位，规则文本变了版本随之变化 */
    String promptVersion() {
        String rules = aiProperties == null || aiProperties.getPrompt() == null ? null : aiProperties.getPrompt().getRules();
        return promptVersionOf(rules);
    }

    static String promptVersionOf(String promptRules) {
        if (promptRules == null || promptRules.isBlank()) {
            return SOURCE_UNKNOWN;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(promptRules.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return SOURCE_UNKNOWN;
        }
    }

    private Map<String, Object> configSnapshot(int topK) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", topK);
        // 生成链路（react=生产 ChatPipeline ReAct 循环 / rag=检索单轮直答）；react 链路与生产 WS 链路的差异必须记录在报告里
        String mode = properties.getMode();
        config.put("mode", mode);
        if (!"rag".equalsIgnoreCase(mode)) {
            config.put("reactChainNote", "与生产 WS 链路共用 ChatPipeline 的 ReAct 循环/工具调用/引用装配；"
                    + "差异：headless 运行无会话/幂等/限流，不落 MySQL/Redis，不推送 WS 帧，不做引用事后校验；"
                    + "工具检索以发起评测的管理员身份执行，模型调用以 eval.requester-id 计量");
        }
        config.put("llm", caseRunner.primaryLlmDescription());
        // 评委身份落盘（评估缺口修复）：SELF_JUDGE 或独立 provider/model，第三方据此复核评委独立性与 kappa 门禁可信度
        config.put("judge", caseRunner.judgeLlmDescription());
        config.put("legacyDataset", legacyDataset);
        config.put("generationMaxTokens", properties.getGenerationMaxTokens());
        config.put("judgeMaxTokens", properties.getJudgeMaxTokens());
        config.put("rerankEnabled", searchProperties.getRerank().isEnabled());
        config.put("rerankModel", searchProperties.getRerank().getModel());
        config.put("rerankScoreThreshold", searchProperties.getRerank().getScoreThreshold());
        config.put("rrfK", searchProperties.getRrfK());
        if (parentContextProperties != null) {
            config.put("parentContextEnabled", parentContextProperties.isEnabled());
            config.put("parentContextNeighbourChunks", parentContextProperties.getNeighbourChunks());
            config.put("parentContextMaxCharsPerHit", parentContextProperties.getMaxCharsPerHit());
        }
        if (contextualProperties != null) {
            config.put("contextualRetrievalEnabled", contextualProperties.isEnabled());
        }
        config.put("humanReviewSampleRate", properties.getHumanReviewSampleRate());
        config.put("randomSeed", properties.getRandomSeed());
        // 单价与来源落盘（评估缺口修复）：cost.amount 可由本 config 的单价 × tokenUsage 复算；
        // 未配价时单价为 null，cost.note 写 unpriced: set eval.pricing.*
        RagEvalProperties.Pricing pricing = properties.getPricing();
        config.put("pricingInputPer1k", pricing == null ? null : pricing.getInputPer1k());
        config.put("pricingOutputPer1k", pricing == null ? null : pricing.getOutputPer1k());
        config.put("pricingCurrency", pricing == null ? null : blankToNull(pricing.getCurrency()));
        config.put("pricingSource", "eval.pricing.*");
        return config;
    }

    private Double pricingInputPer1k() {
        RagEvalProperties.Pricing pricing = properties.getPricing();
        return pricing == null ? null : pricing.getInputPer1k();
    }

    private Double pricingOutputPer1k() {
        RagEvalProperties.Pricing pricing = properties.getPricing();
        return pricing == null ? null : pricing.getOutputPer1k();
    }

    private String pricingCurrency() {
        RagEvalProperties.Pricing pricing = properties.getPricing();
        return pricing == null ? null : blankToNull(pricing.getCurrency());
    }

    /** 空白字符串（env 默认空占位）按未配置处理 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void remember(RunProgress progress) {
        synchronized (runs) {
            runs.put(progress.runId, progress);
            while (runs.size() > MAX_KEPT_RUNS) {
                String oldest = runs.keySet().iterator().next();
                runs.remove(oldest);
            }
        }
    }

    static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "default";
        }
        String cleaned = UNSAFE_LABEL_CHARS.matcher(label.trim()).replaceAll("_");
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }

    public enum RunState {
        RUNNING, SUCCEEDED, FAILED
    }

    public record RunStatus(
            String runId,
            String label,
            String datasetSource,
            int topK,
            RunState state,
            int totalCases,
            int completedCases,
            String operator,
            String reportPath,
            EvalReport.EvalSummary summary,
            /* 评委与人工一致率，录入人工判定后才有；低于阈值时 status = NEEDS_RECALIBRATION 并带 warning */
            JudgeHumanAgreement humanAgreement,
            String message,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    static final class RunProgress {
        private final String runId;
        private final String label;
        private final String datasetSource;
        private final int topK;
        private final int totalCases;
        private final String operator;
        private final Instant startedAt = Instant.now();
        private volatile RunState state = RunState.RUNNING;
        private volatile int completedCases;
        private volatile String reportPath;
        private volatile EvalReport.EvalSummary summary;
        private volatile JudgeHumanAgreement humanAgreement;
        private volatile String message;
        private volatile Instant finishedAt;

        RunProgress(String runId, String label, String datasetSource, int topK, int totalCases, String operator) {
            this.runId = runId;
            this.label = label;
            this.datasetSource = datasetSource;
            this.topK = topK;
            this.totalCases = totalCases;
            this.operator = operator;
        }

        void succeed(EvalReport.EvalSummary summary, String reportPath) {
            this.summary = summary;
            this.reportPath = reportPath;
            this.finishedAt = Instant.now();
            this.state = RunState.SUCCEEDED;
        }

        void fail(Exception e) {
            this.message = e.getMessage();
            this.finishedAt = Instant.now();
            this.state = RunState.FAILED;
        }

        RunStatus snapshot() {
            return new RunStatus(runId, label, datasetSource, topK, state, totalCases, completedCases, operator,
                    reportPath, summary, humanAgreement, message, startedAt, finishedAt);
        }
    }
}
