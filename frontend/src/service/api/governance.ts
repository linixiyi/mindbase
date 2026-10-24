import { request } from '../request';

/** 治理能力接口：文档冲突裁决 / 知识索引重建回滚 / 离线 RAG 评测。 后端均为 /api/v1/admin/** 管理员端点。 */

/** 疑似文档冲突列表（status 缺省查全部） */
export function fetchGetDocumentConflictList(params: { status?: string | null; page: number; size: number }) {
  return request<Api.DocumentConflict.ListPayload>({ url: '/admin/document-conflicts', params });
}

/** 人工裁决冲突：KEEP_NEW 删旧文档 / KEEP_EXISTING 删新文档 / IGNORED 标记不是冲突 */
export function fetchResolveDocumentConflict(id: number, data: { action: string; note?: string }) {
  return request<Api.DocumentConflict.Item>({
    url: `/admin/document-conflicts/${id}/resolve`,
    method: 'post',
    data
  });
}

/** 索引总览：线上别名指向、索引列表、当前重建进度 */
export function fetchGetKnowledgeIndexOverview() {
  return request<Api.KnowledgeIndex.Overview>({ url: '/admin/knowledge-index' });
}

/** 重建进度（轮询用） */
export function fetchGetKnowledgeIndexRebuildStatus() {
  return request<Api.KnowledgeIndex.RebuildStatus | null>({ url: '/admin/knowledge-index/rebuild' });
}

/** 提交 embedding 重建：targetProvider 需已在模型配置中保存维度与 Key */
export function fetchStartKnowledgeIndexRebuild(data: { targetProvider: string; fresh: boolean }) {
  return request<Api.KnowledgeIndex.RebuildStatus>({
    url: '/admin/knowledge-index/rebuild',
    method: 'post',
    data
  });
}

/** 回滚线上别名到指定历史索引 */
export function fetchRollbackKnowledgeIndex(index: string) {
  return request({ url: '/admin/knowledge-index/rollback', method: 'post', data: { index } });
}

/** 触发离线评测：datasetPath 与 jsonl 二选一 */
export function fetchRunRagEval(data: { datasetPath?: string; jsonl?: string; topK?: number; label?: string }) {
  return request<Api.RagEval.RunStatus>({ url: '/admin/eval/run', method: 'post', data });
}

/** 评测任务列表 */
export function fetchGetRagEvalRuns() {
  return request<Api.RagEval.RunStatus[]>({ url: '/admin/eval/runs' });
}

/** 完整评测报告（任务完成后可读，含人工判定附件） */
export function fetchGetRagEvalReport(runId: string) {
  return request<Api.RagEval.ReportPayload>({ url: `/admin/eval/runs/${runId}/report` });
}

/** 文件处理死信列表（重试耗尽的文件任务，按入队时间倒序） */
export function fetchGetDeadLetterList(params: { page: number; size: number }) {
  return request<Api.DeadLetter.ListPayload>({ url: '/admin/dead-letters', params });
}

/** 人工重放：任务经 outbox 回到文件处理链，成功后删除死信记录；再次失败会作为新死信重新落库 */
export function fetchReplayDeadLetter(id: number) {
  return request<Api.DeadLetter.Item>({ url: `/admin/dead-letters/${id}/replay`, method: 'post' });
}
