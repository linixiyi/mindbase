/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      uploadMaxSizeBytes: number | null;
      uploadMaxSizeMb: number | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description' | 'uploadMaxSizeBytes' | 'uploadMaxSizeMb'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace User {
    interface UsageQuota {
      enabled: boolean;
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageSnapshot {
      day: string;
      chatRequestCount: number;
      llm: UsageQuota;
      embedding: UsageQuota;
    }

    interface TokenRecord {
      id: number;
      recordDate: string;
      tokenType: 'LLM' | 'EMBEDDING';
      changeType: 'INCREASE' | 'CONSUME';
      amount: number;
      balanceBefore: number | null;
      balanceAfter: number | null;
      reason: string;
      remark: string | null;
      requestCount: number;
      createdAt: string;
    }

    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      status: number;
      /** 后端禁用/启用端点落地后下发；缺省时回退 status 展示 */
      enabled?: boolean;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createdAt: string;
      usage: UsageSnapshot;
      chatUsage?: string;
      llmUsage?: string;
      embeddingUsage?: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace InviteCode {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        enabled: boolean;
      }
    >;

    interface Creator {
      id: number;
      username: string;
    }

    interface Item {
      id: number;
      code: string;
      maxUses: number;
      usedCount: number;
      expiresAt: string | null;
      enabled: boolean;
      createdBy?: Creator;
      createdAt: string;
      updatedAt: string;
    }

    interface ListPayload {
      records: Item[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  /**
   * namespace Recharge
   *
   * backend api module: "recharge"
   */
  namespace Recharge {
    /** 充值套餐 */
    interface Package {
      id: number;
      packageName: string;
      packagePrice: number; // 单位分
      packageDesc: string;
      packageBenefit: string;
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      enabled: boolean;
      createdAt: string;
      updatedAt: string;
    }

    /** 订单信息 */
    interface OrderInfo {
      outTradeNo: string;
      appId: string;
      prePayId: string;
      expireTime: number;
    }

    /** 充值订单 */
    interface Order {
      id: number;
      tradeNo: string;
      userId: string;
      packageId: number;
      amount: number; // 单位分
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      wxTransactionId: string;
      status: 'NOT_PAY' | 'PAYING' | 'SUCCEED' | 'FAIL' | 'CANCELLED';
      description: string;
      payTime: string | null;
      createdAt: string;
      updatedAt: string;
    }

    /** 管理端充值订单（含用户名补齐） */
    interface AdminOrder extends Order {
      username: string | null;
    }

    /** 管理端充值订单分页载荷 */
    interface AdminOrderList {
      records: AdminOrder[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  namespace Admin {
    interface WindowLimit {
      max: number;
      windowSeconds: number;
    }

    interface DualWindowLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface TokenBudgetLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface RateLimitSettings {
      chatMessage: WindowLimit;
      llmGlobalToken: TokenBudgetLimit;
      embeddingUploadToken: TokenBudgetLimit;
      embeddingQueryRequest: DualWindowLimit;
      embeddingQueryGlobalToken: TokenBudgetLimit;
    }

    interface ModelProviderItem {
      provider: string;
      displayName: string;
      apiStyle: string;
      apiBaseUrl: string;
      model: string;
      dimension: number | null;
      enabled: boolean;
      active: boolean;
      hasApiKey: boolean;
      maskedApiKey: string;
      apiKeyInput?: string;
    }

    interface ModelProviderScopeSettings {
      scope: 'llm' | 'embedding';
      activeProvider: string;
      providers: ModelProviderItem[];
    }

    interface ModelProviderSettings {
      llm: ModelProviderScopeSettings;
      embedding: ModelProviderScopeSettings;
    }

    interface ConnectivityTestResult {
      success: boolean;
      message: string;
      latencyMs: number;
    }

    interface RerankProviderStatus {
      enabled: boolean;
      url: string;
      model: string;
      hasApiKey: boolean;
      maskedApiKey: string;
      keySource: 'dedicated' | 'embedding-aliyun' | 'none';
      timeoutSeconds: number;
      scoreThreshold: number;
      maxCandidates: number;
    }

    interface FailoverProviderStatus {
      provider: string;
      displayName: string;
      model: string;
      apiBaseUrl: string;
      inChain: boolean;
      primary: boolean;
      position: number;
      circuitState: string | null;
      excludedReason: string | null;
    }

    interface LlmFailoverStatus {
      failoverEnabled: boolean;
      configuredBackupProviders: string[];
      providers: FailoverProviderStatus[];
    }

    interface UsageTrendPoint {
      day: string;
      chatRequestCount: number;
      llmUsedTokens: number;
      llmRequestCount: number;
      embeddingUsedTokens: number;
      embeddingRequestCount: number;
    }

    interface UsageRankingItem {
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageAlert {
      level: 'critical' | 'warning';
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
      usageRatio: number;
      message: string;
    }

    interface UsageOverview {
      days: number;
      today: UsageTrendPoint;
      trends: UsageTrendPoint[];
      llmRankings: UsageRankingItem[];
      embeddingRankings: UsageRankingItem[];
      alerts: UsageAlert[];
    }
  }

  namespace KnowledgeBase {
    interface SearchParams {
      userId: string;
      query: string;
      topK: number;
    }

    /** pm-024a：知识库列表服务端筛选（与分页参数同链下发给 /documents/accessible） */
    interface SearchFilter {
      /** 文件名关键词（空串/null 均不过滤） */
      fileName: string;
      /** 公开/私有（null=全部） */
      isPublic: boolean;
      /** 上传状态=处理中(UPLOADING)/已完成(COMPLETED)；向量化失败(VECTOR_FAILED) */
      status: 'UPLOADING' | 'COMPLETED' | 'VECTOR_FAILED';
    }

    /**
     * 列表查询参数 = 分页 + 筛选（与 user 页 SearchParams 同律：RecordNullable 全字段可选可空， 满足 useTable 对 apiFn 参数型与 CommonSearchParams
     * 的双向兼容）
     */
    type ListParams = CommonType.RecordNullable<Common.CommonSearchParams & SearchFilter>;

    /** 筛选表单草稿（NSelect 值不支持 boolean，公开/私有以 'PUBLIC'/'PRIVATE' 承载， 查询时在视图内换算为 boolean|null 再下发） */
    type FilterDraft = {
      fileName: string | null;
      isPublic: 'PUBLIC' | 'PRIVATE' | null;
      status: 'UPLOADING' | 'COMPLETED' | 'VECTOR_FAILED' | null;
    };

    interface SearchResult {
      fileMd5: string;
      chunkId: number;
      textContent: string;
      score: number;
      fileName: string;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      uploadMaxSizeBytes?: number | null;
      uploadMaxSizeMb?: number | null;
      isPublic: boolean;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      id?: number;
      file: File;
      chunk: Blob | null;
      fileMd5: string;
      chunkIndex: number;
      totalSize: number;
      fileName: string;
      userId?: string;
      orgTag: string | null;
      orgTagName?: string | null;
      public: boolean;
      isPublic: boolean;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
      actualEmbeddingTokens?: number;
      actualChunkCount?: number;
      vectorizationStatus?: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | null;
      vectorizationErrorMessage?: string | null;
      createdAt?: string;
      mergedAt?: string;
      requestIds?: string[]; // 请求ID，用于取消上传
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'fileMd5' | 'fileName'>;

    interface Progress {
      uploaded: number[];
      progress: number;
      totalChunks: number;
    }

    interface MergeResult {
      objectUrl: string;
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
    }
  }

  namespace Chat {
    type GenerationStatus = 'STREAMING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

    interface ReferenceEvidence {
      fileMd5: string;
      fileName: string;
      pageNumber?: number | null;
      anchorText?: string | null;
      /** HYBRID：双路召回 + rerank；HYBRID_RRF：rerank 不可用时按 RRF 排序兜底；TEXT_ONLY：embedding 失败降级 */
      retrievalMode?: 'HYBRID' | 'HYBRID_RRF' | 'TEXT_ONLY' | null;
      retrievalLabel?: string | null;
      retrievalQuery?: string | null;
      matchedChunkText?: string | null;
      evidenceSnippet?: string | null;
      score?: number | null;
      chunkId?: number | null;
      /** 引用事后校验结论，未开启校验时为空；SUSPICIOUS 表示片段无法支撑引用它的句子 */
      citationStatus?: 'SUPPORTED' | 'SUSPICIOUS' | null;
      citationReason?: string | null;
    }

    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
    }

    interface AgentToolEvent {
      id?: string;
      tool: string;
      status: 'executing' | 'success' | 'failed';
      timestamp?: number;
    }

    interface Conversation {
      conversationId: string;
    }

    interface Message {
      role: 'user' | 'assistant';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      /** 失败类别码（pm-001）：PROVIDER_UNAVAILABLE / TIMEOUT / BUSY / RATE_LIMIT / OTHER */
      errorCategory?: string;
      timestamp?: string;
      conversationId?: string;
      generationId?: string;
      username?: string;
      referenceMappings?: Record<string, ReferenceEvidence>;
      toolEvents?: AgentToolEvent[];
      feedbackRating?: 'good' | 'bad';
    }

    interface Token {
      cmdToken: string;
    }

    interface GenerationSnapshot {
      generationId: string;
      userId: string;
      conversationId: string;
      question: string;
      status: GenerationStatus;
      content: string;
      createdAt: string;
      updatedAt: string;
      /** 失败类别码（pm-001），FAILED 状态时存在 */
      errorCategory?: string | null;
      errorMessage?: string | null;
      referenceMappings?: Record<string, ReferenceEvidence>;
    }

    interface ConversationSession {
      id: number;
      conversationId: string;
      title: string;
      status: 'ACTIVE' | 'ARCHIVED';
      createdAt: string;
      updatedAt: string;
    }
  }

  /** 文件处理死信：GET /admin/dead-letters + POST /admin/dead-letters/{id}/replay */
  namespace DeadLetter {
    interface Item {
      id: number;
      fileMd5: string | null;
      taskType: string | null;
      /** 文件处理任务 JSON，重放时原样回投 */
      payload: string | null;
      errorMessage: string | null;
      /** 死信记录自身在 DLT 中的位置 */
      sourceTopic: string;
      sourcePartition: number;
      sourceOffset: number;
      createdAt: string;
    }

    interface ListPayload {
      records: Item[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  namespace Document {
    interface DownloadResponse {
      fileName: string;
      downloadUrl: string;
      fileSize: number;
      fileMd5?: string;
    }

    interface ReferenceDetailResponse extends Chat.ReferenceEvidence {
      referenceNumber: number;
    }
  }

  /** 疑似文档冲突：POST /admin/document-conflicts/{id}/resolve 的人工裁决 */
  namespace DocumentConflict {
    type Status = 'PENDING' | 'KEEP_NEW' | 'KEEP_EXISTING' | 'IGNORED' | 'OBSOLETE';

    interface Item {
      id: number;
      pairKey: string;
      newFileMd5: string;
      newChunkId: number;
      newFileName: string | null;
      existingFileMd5: string;
      existingChunkId: number;
      existingFileName: string | null;
      similarity: number;
      maskedSimilarity: number | null;
      newSnippet: string | null;
      existingSnippet: string | null;
      newValues: string | null;
      existingValues: string | null;
      llmReason: string | null;
      status: Status;
      createdAt: string;
      resolvedBy: string | null;
      resolvedAt: string | null;
      resolutionNote: string | null;
    }

    interface ListPayload {
      records: Item[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  /** 知识索引管理：GET/POST /admin/knowledge-index */
  namespace KnowledgeIndex {
    interface IndexInfo {
      name: string;
      live: boolean;
      meta: Record<string, string>;
      docCount: number;
    }

    type RebuildState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

    interface RebuildStatus {
      state: RebuildState;
      phase: string;
      targetProvider: string;
      targetModelVersion: string;
      targetIndex: string;
      previousIndex: string | null;
      totalFiles: number;
      processedFiles: number;
      skippedFiles: number;
      indexedChunks: number;
      expectedChunks: number;
      actualChunks: number;
      message: string | null;
      startedAt: string;
      finishedAt: string | null;
    }

    interface Overview {
      liveIndex: string | null;
      legacyConcreteIndex: boolean;
      indices: IndexInfo[];
      rebuild: RebuildStatus | null;
    }
  }

  /** 离线 RAG 评测：POST /admin/eval/run + GET /admin/eval/runs */
  namespace RagEval {
    type RunState = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

    interface EvalSummary {
      totalCases: number;
      labeledCases: number;
      retrievalHits: number;
      recallAtK: number | null;
      mrr: number | null;
      docRecallAtK: number | null;
      evidenceLabeledCases: number;
      meanExpectedDocCoverage: number | null;
      judgedCases: number;
      correctCases: number;
      meanJudgeScore: number | null;
      accuracy: number | null;
      needsHumanReviewCount: number;
      failedCases: number;
      retrievalModeCounts: Record<string, number>;
      diagnosisCounts: Record<string, number>;
    }

    interface HumanAgreement {
      status: 'NO_REVIEWS' | 'INSUFFICIENT_SAMPLE' | 'OK' | 'NEEDS_RECALIBRATION';
      warning: string | null;
      reviewedCases: number;
      comparableCases: number;
      agreedCases: number;
      agreementRate: number | null;
      cohensKappa: number | null;
      judgeCorrectRate: number | null;
      humanCorrectRate: number | null;
      scoredCases: number;
      meanAbsoluteScoreDiff: number | null;
      humanOnlyCases: number;
      disagreementCaseIds: string[];
    }

    interface RunStatus {
      runId: string;
      label: string;
      datasetSource: string;
      topK: number;
      state: RunState;
      totalCases: number;
      completedCases: number;
      operator: string;
      reportPath: string | null;
      summary: EvalSummary | null;
      humanAgreement: HumanAgreement | null;
      message: string | null;
      startedAt: string;
      finishedAt: string | null;
    }

    interface ReportPayload {
      runId: string;
      label: string;
      datasetSource: string;
      topK: number;
      startedAt: string;
      finishedAt: string | null;
      config: Record<string, unknown>;
      summary: EvalSummary;
      diagnosisGuide: Record<string, string>;
      humanReview?: {
        reviews?: unknown[];
        agreement?: HumanAgreement;
      };
    }
  }
}
