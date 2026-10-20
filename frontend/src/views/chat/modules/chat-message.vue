<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { router } from '@/router';
import { request } from '@/service/request';
import { formatDate } from '@/utils/common';
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message;
  sessionId?: string;
  retrievalQueryFallback?: string;
}>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();
const feedbackSubmitting = ref<Record<string, boolean>>({});

function getMessageFeedbackKey(message: Api.Chat.Message) {
  return message.generationId || `${message.conversationId || 'unknown'}:${message.timestamp || ''}`;
}

async function handleFeedback(message: Api.Chat.Message, rating: 'good' | 'bad') {
  if (message.role !== 'assistant') {
    return;
  }

  const key = getMessageFeedbackKey(message);
  if (feedbackSubmitting.value[key]) {
    return;
  }

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: true
  };

  const { error } = await request({
    url: 'chat/feedback',
    method: 'POST',
    data: {
      rating,
      reason: rating === 'good' ? '用户点击点赞，表示认可本次回答' : '用户点击点踩，表示不满意本次回答',
      conversationId: message.conversationId || props.sessionId,
      generationId: message.generationId
    }
  });

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: false
  };

  if (error) {
    window.$message?.error('反馈记录失败');
    return;
  }

  message.feedbackRating = rating;
  window.$message?.success(rating === 'good' ? '已记录点赞反馈' : '已记录点踩反馈');
}

// 存储文件名和对应的事件处理
const sourceFiles = ref<
  Array<{ fileName: string; id: string; referenceNumber: number; fileMd5?: string; pageNumber?: number }>
>([]);
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]+/g;
const toolNameLabels: Record<string, string> = {
  search_knowledge: '检索知识库',
  generate_summary: '生成知识摘要',
  submit_feedback: '记录反馈',
  knowledge_stats: '读取知识库统计'
};
const toolStatusLabels: Record<Api.Chat.AgentToolEvent['status'], string> = {
  executing: '执行中',
  success: '已完成',
  failed: '失败'
};

const toolEvents = computed(() => props.msg.toolEvents || []);

function getToolLabel(tool: string) {
  return toolNameLabels[tool] || tool;
}

function getToolStatusLabel(status: Api.Chat.AgentToolEvent['status']) {
  return toolStatusLabels[status] || status;
}

function splitTrailingUrlPunctuation(rawUrl: string) {
  let url = rawUrl;
  let trailing = '';

  while (url) {
    const lastChar = url.at(-1);
    if (!lastChar) break;

    if (/[，。！？；：、,.!?;:]/.test(lastChar)) {
      trailing = `${lastChar}${trailing}`;
      url = url.slice(0, -1);
    } else if (lastChar === ')' || lastChar === '）') {
      const openingChar = lastChar === ')' ? '(' : '（';
      const closingChar = lastChar;
      const openingCount = (url.match(new RegExp(`\\${openingChar}`, 'g')) || []).length;
      const closingCount = (url.match(new RegExp(`\\${closingChar}`, 'g')) || []).length;

      if (closingCount > openingCount) {
        trailing = `${lastChar}${trailing}`;
        url = url.slice(0, -1);
      } else {
        break;
      }
    } else {
      break;
    }
  }

  return { url, trailing };
}

function normalizeBareUrls(text: string) {
  return text.replace(bareUrlPattern, (match, offset: number, source: string) => {
    const previousChar = source[offset - 1] || '';
    const previousTwoChars = source.slice(Math.max(0, offset - 2), offset);
    const previousTenChars = source.slice(Math.max(0, offset - 10), offset).toLowerCase();

    if (previousChar === '<' || previousTwoChars === '](' || /(?:href|src)=["']?$/.test(previousTenChars)) {
      return match;
    }

    const { url, trailing } = splitTrailingUrlPunctuation(match);
    return url ? `<${url}>${trailing}` : match;
  });
}

function createSourceLink(
  sourceNum: string,
  fileName: string,
  extras?: { fileMd5?: string; pageNumber?: number; displayName?: string }
): string {
  const trimmedFileName = fileName.trim();
  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);
  const suspicious = props.msg.referenceMappings?.[sourceNum]?.citationStatus === 'SUSPICIOUS';
  const linkClass = suspicious ? 'source-file-link source-file-link--suspicious' : 'source-file-link';

  sourceFiles.value.push({
    fileName: trimmedFileName,
    id: fileId,
    referenceNumber,
    fileMd5: extras?.fileMd5,
    pageNumber: extras?.pageNumber
  });

  const link = `来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${extras?.displayName || trimmedFileName}</span>`;
  // 引用校验判为不支撑的来源降低展示优先级，并提示用户自行核实
  return suspicious
    ? `${link}<span class="citation-suspicious-badge" title="引用校验：该片段可能无法支撑这句话">待核实</span>`
    : link;
}

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 重置来源文件列表，避免重复
  sourceFiles.value = [];

  // 支持单个来源，也支持一个括号里包含多个来源：
  // (来源#1: test.pdf | 第5页; 来源#2: other.pdf | 第8页)
  const entryBoundary = '(?=\\s*(?:[;；,，、。！？!?\\)）]|$))';
  const pagePattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*第(\\d+)页${entryBoundary}`,
    'g'
  );
  const md5Pattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*MD5:\\s*([a-fA-F0-9]+)${entryBoundary}`,
    'g'
  );
  const simplePattern = new RegExp(`来源#(\\d+):\\s*([^<>\\n\\r|;；,，、。！？!?]+?)${entryBoundary}`, 'g');

  let processedText = text.replace(pagePattern, (...args: unknown[]) => {
    const [_match, sourceNum, fileName, pageNum] = args as [string, string, string, string];
    return createSourceLink(sourceNum, fileName, {
      pageNumber: Number.parseInt(pageNum, 10),
      displayName: `${fileName.trim()} (第${pageNum}页)`
    });
  });

  processedText = processedText.replace(md5Pattern, (...args: unknown[]) => {
    const [_match, sourceNum, fileName, fileMd5] = args as [string, string, string, string];
    return createSourceLink(sourceNum, fileName, {
      fileMd5: fileMd5.trim()
    });
  });

  processedText = processedText.replace(simplePattern, (_match, sourceNum, fileName) => {
    return createSourceLink(sourceNum, fileName);
  });

  return processedText;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processSourceLinks(rawContent));
  }

  return rawContent;
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  const rawText = scope?.textContent?.replace(/\s+/g, ' ').trim() || '';
  if (!rawText) return '';

  const beforeCitation = rawText.split(/(?:\(|（)?来源#\d+:/)[0] || rawText;
  return beforeCitation
    .replace(/^\s*\d+\.\s*/, '')
    .replace(/[（(]\s*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function openReferencePreviewPage(payload: {
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  score?: number | null;
  chunkId?: number | null;
  fileName: string;
  fileMd5?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  sessionId?: string;
  referenceNumber: number;
}) {
  const previewKey = `reference-preview:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  localStorage.setItem(previewKey, JSON.stringify(payload));

  const routeLocation = router.resolve({
    path: '/chat',
    query: {
      preview: 'reference',
      previewKey
    }
  });

  window.open(routeLocation.href, '_blank', 'noopener,noreferrer');
}

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        const contextAnchorText = extractContextAnchorText(target);
        handleSourceFileClick({
          fileName: file.fileName,
          referenceNumber: file.referenceNumber,
          fileMd5: file.fileMd5,
          anchorText: contextAnchorText
        });
      }
    }
  }
}

// 处理来源文件点击事件
function hasIncompleteRetrievalEvidence(detail: Api.Chat.ReferenceEvidence | null | undefined) {
  return !detail?.retrievalQuery || !detail?.matchedChunkText || !detail?.evidenceSnippet;
}

async function fetchReferenceDetail(sessionId: string, referenceNumber: number) {
  try {
    const { error: detailError, data: detailData } = await request<Api.Document.ReferenceDetailResponse>({
      url: 'documents/reference-detail',
      params: {
        sessionId,
        referenceNumber: referenceNumber.toString()
      }
    });

    if (!detailError && detailData?.fileMd5) {
      return detailData;
    }
  } catch (detailErr) {
    console.warn('通过API查询引用详情失败:', detailErr);
  }
  return null;
}

function buildReferencePreviewPayload(
  source: Api.Chat.ReferenceEvidence | null,
  options: {
    fileName: string;
    fileMd5: string | null;
    anchorText?: string;
    fallbackRetrievalQuery: string;
    sessionId?: string;
    referenceNumber: number;
  }
) {
  return {
    fileName: source?.fileName || options.fileName,
    fileMd5: options.fileMd5,
    pageNumber: source?.pageNumber,
    anchorText: options.anchorText || source?.anchorText || '',
    retrievalMode: source?.retrievalMode,
    retrievalLabel: source?.retrievalLabel,
    retrievalQuery: source?.retrievalQuery || options.fallbackRetrievalQuery,
    evidenceSnippet: source?.evidenceSnippet,
    matchedChunkText: source?.matchedChunkText,
    score: source?.score,
    chunkId: source?.chunkId,
    sessionId: options.sessionId,
    referenceNumber: options.referenceNumber
  };
}

async function handleSourceFileClick(fileInfo: {
  fileName: string;
  referenceNumber: number;
  fileMd5?: string;
  anchorText?: string;
}) {
  const { fileName, referenceNumber, fileMd5: extractedMd5, anchorText: clickedAnchorText } = fileInfo;
  const persistedDetail =
    props.msg.referenceMappings?.[String(referenceNumber)] || props.msg.referenceMappings?.[referenceNumber];
  const referenceSessionId = props.msg.generationId || props.msg.conversationId || props.sessionId;
  console.log(
    '点击了来源文件:',
    fileName,
    '引用编号:',
    referenceNumber,
    '提取的MD5:',
    extractedMd5,
    '会话ID:',
    referenceSessionId
  );

  try {
    const fallbackRetrievalQuery = props.retrievalQueryFallback || '';

    let detail: Api.Document.ReferenceDetailResponse | null = null;
    if (referenceSessionId && hasIncompleteRetrievalEvidence(persistedDetail)) {
      detail = await fetchReferenceDetail(referenceSessionId, referenceNumber);
    }

    if (persistedDetail?.fileMd5 && !detail) {
      openReferencePreviewPage(
        buildReferencePreviewPayload(persistedDetail, {
          fileName,
          fileMd5: persistedDetail.fileMd5,
          anchorText: clickedAnchorText,
          fallbackRetrievalQuery,
          sessionId: referenceSessionId,
          referenceNumber
        })
      );
      return;
    }

    const targetMd5 = detail?.fileMd5 || extractedMd5 || null;
    openReferencePreviewPage(
      buildReferencePreviewPayload(detail, {
        fileName,
        fileMd5: targetMd5,
        anchorText: clickedAnchorText,
        fallbackRetrievalQuery,
        sessionId: referenceSessionId,
        referenceNumber
      })
    );
  } catch (err) {
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${fileName}`);
  }
}
</script>

<template>
  <div
    class="chat-message mb-8 flex flex-col gap-2"
    :class="msg.role === 'user' ? 'chat-message--user items-end' : 'chat-message--assistant items-stretch'"
  >
    <template v-if="msg.role === 'user'">
      <div class="chat-user-bubble">{{ content }}</div>
      <div class="chat-message__meta">
        <span class="chat-message__author">{{ msg.username || authStore.userInfo.username }}</span>
        <span class="chat-message__time">{{ formatDate(msg.timestamp) }}</span>
      </div>
    </template>
    <template v-else>
      <div class="chat-message__head">
        <span class="chat-message__avatar">
          <SystemLogo class="text-14px" />
        </span>
        <span class="chat-message__author">MindBase</span>
        <span class="chat-message__time">{{ formatDate(msg.timestamp) }}</span>
      </div>
      <div v-if="toolEvents.length > 0" class="chat-tool-events">
        <div
          v-for="event in toolEvents"
          :key="event.id || event.tool"
          class="chat-tool-event"
          :class="`chat-tool-event--${event.status}`"
        >
          <icon-lucide:ellipsis v-if="event.status === 'executing'" class="text-14px" />
          <icon-lucide:circle-check v-else-if="event.status === 'success'" class="text-14px" />
          <icon-lucide:circle-x v-else class="text-14px" />
          <span class="chat-tool-event__name">{{ getToolLabel(event.tool) }}</span>
          <span class="chat-tool-event__status">{{ getToolStatusLabel(event.status) }}</span>
        </div>
      </div>
      <div v-if="msg.status === 'pending' || (msg.status === 'loading' && !msg.content)" class="chat-stream-indicator">
        <icon-lucide:ellipsis class="text-24px" />
      </div>
      <div v-else-if="msg.status === 'error'" class="chat-error-text">
        {{ msg.content || '服务器繁忙，请稍后再试' }}
      </div>
      <div v-else class="chat-answer" @click="handleContentClick">
        <VueMarkdownIt :content="content" />
      </div>
    </template>
    <div class="chat-message__actions" :class="{ 'chat-message__actions--end': msg.role === 'user' }">
      <NButton quaternary size="tiny" title="复制回答" aria-label="复制回答" @click="handleCopy(msg.content)">
        <template #icon>
          <icon-lucide:copy class="text-14px" />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        size="tiny"
        title="点赞"
        aria-label="点赞"
        :type="msg.feedbackRating === 'good' ? 'primary' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'good')"
      >
        <template #icon>
          <icon-lucide:thumbs-up class="text-14px" />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        size="tiny"
        title="点踩"
        aria-label="点踩"
        :type="msg.feedbackRating === 'bad' ? 'error' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'bad')"
      >
        <template #icon>
          <icon-lucide:thumbs-down class="text-14px" />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat-message {
  min-width: 0;
}

.chat-message__head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 24px;
}

.chat-message__avatar {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--app-subtle);
  color: var(--app-muted);
}

.chat-message__author {
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
  color: var(--app-text);
}

.chat-message__time {
  font-size: 12px;
  line-height: 1.4;
  color: rgb(var(--base-text-color) / 0.45);
}

.chat-message__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-right: 4px;

  .chat-message__author {
    font-size: 12px;
    color: var(--app-muted);
  }
}

.chat-message__actions {
  display: flex;
  gap: 2px;
  margin-top: -4px;
}
.chat-message__actions--end {
  justify-content: flex-end;
}

/* 用户气泡：淡蓝填充白字、18px 圆角、发信侧（右上）收窄到 6px、内边距 12px 16px、16px/1.75
   （DESIGN.md › Chat / chat-bubble-user） */
.chat-user-bubble {
  max-width: min(85%, 640px);
  padding: 12px 16px;
  border-radius: 18px 6px 18px 18px;
  background: rgb(var(--primary-color));
  /* 白字为 DESIGN.md chat-bubble-user 的固定配对常量（textColor 即纯白），非主题变量 */
  color: rgb(255 255 255);
  font-size: 16px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 工具调用：状态胶囊，语义深阶文字（原值只给填充与图形） */
.chat-tool-events {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chat-tool-event {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border: 1px solid var(--app-border);
  border-radius: 9999px;
  background: var(--app-subtle);
  font-size: 12px;
  line-height: 18px;
  color: var(--app-muted);
}

.chat-tool-event__name {
  font-weight: 500;
  color: var(--app-text);
}

.chat-tool-event__status {
  color: var(--app-muted);
}

.chat-tool-event--executing {
  color: rgb(var(--primary-color));
}
.dark .chat-tool-event--executing {
  color: rgb(var(--primary-400-color));
}

.chat-tool-event--success .chat-tool-event__status {
  color: rgb(var(--success-700-color));
}
.dark .chat-tool-event--success .chat-tool-event__status {
  color: rgb(var(--success-400-color));
}

.chat-tool-event--failed .chat-tool-event__status {
  color: rgb(var(--error-700-color));
}
.dark .chat-tool-event--failed .chat-tool-event__status {
  color: rgb(var(--error-400-color));
}

/* 流式进行中：一线蓝标出"正在进行的事"，状态点以 1.6s 透明度脉动呼吸——
   只绑定等待/生成状态，回答就绪即静止（DESIGN.md › Motion & Delight › 呼吸律） */
.chat-stream-indicator {
  padding: 2px 0;
  color: rgb(var(--primary-color));
  animation: azure-breathe 1.6s var(--app-ease-standard, ease) infinite;
}
.dark .chat-stream-indicator {
  color: rgb(var(--primary-400-color));
}

@keyframes azure-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

.chat-error-text {
  font-size: 14px;
  line-height: 1.6;
  color: rgb(var(--error-700-color));
}
.dark .chat-error-text {
  color: rgb(var(--error-400-color));
}

/* AI 回答：无气泡，直接坐在画布实底上的印刷排版（Body 16px/1.75——DESIGN.md › Chat「正文 16px/1.75」） */
.chat-answer {
  min-width: 0;
  overflow-wrap: break-word;
  font-size: 16px;
  line-height: 1.75;
  color: var(--app-text);

  :deep(.vp-doc) {
    /* 弃用 vendor 自带的 Inter/Google Fonts 栈，回归系统字体栈（SF Pro + 苹方） */
    font-family: inherit;
    font-size: 16px;
    line-height: 1.75;
    font-variant-numeric: tabular-nums;
    /* 把 vendor 的 VitePress 变量吸附到本世界的 token */
    --vp-c-brand: rgb(var(--primary-color));
    --vp-c-brand-light: rgb(var(--primary-color));
    --vp-c-brand-lighter: rgb(var(--primary-color) / 0.12);
    --vp-c-brand-dark: rgb(var(--primary-color));
    --vp-c-brand-darker: rgb(var(--primary-color));
    --vp-c-text-1: var(--app-text);
    --vp-c-text-2: var(--app-muted);
    --vp-c-text-code: rgb(var(--primary-color));
    --vp-c-divider: var(--app-border);
    --vp-c-border: var(--app-border);
    --vp-c-bg-soft: var(--app-subtle);
    --vp-c-mute: var(--app-subtle);
  }

  :deep(.vp-doc div[class*='language-']) {
    border-radius: 10px;
  }
}
.dark .chat-answer :deep(.vp-doc) {
  --vp-c-brand: rgb(var(--primary-400-color));
  --vp-c-brand-light: rgb(var(--primary-400-color));
  --vp-c-brand-dark: rgb(var(--primary-400-color));
  --vp-c-brand-darker: rgb(var(--primary-400-color));
  --vp-c-text-code: rgb(var(--primary-400-color));
}

/* 引用标注：只改样式层——DOM 结构、data-file-id 与解析数据流零改动（AGENTS.md 硬约束）。
   亮色淡蓝 10% 底胶囊，暗色提亮底 + 派生 400 阶文字（DESIGN.md › citation-chip）。 */
.chat-answer :deep(.source-file-link) {
  display: inline-block;
  margin: 0 1px;
  padding: 1px 8px;
  border-radius: 9999px;
  background: rgb(var(--primary-color) / 0.1);
  color: rgb(var(--primary-color));
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  cursor: pointer;
  text-decoration: none;
  transition: background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}

.chat-answer :deep(.source-file-link:hover) {
  background: rgb(var(--primary-color) / 0.16);
}

.chat-answer :deep(.source-file-link:active) {
  background: rgb(var(--primary-color) / 0.22);
}

.dark .chat-answer :deep(.source-file-link) {
  background: rgb(var(--primary-color) / 0.16);
  color: rgb(var(--primary-400-color));
}

/* 引用校验不支撑的来源：降级为中性胶囊 + 虚线下划线 */
.chat-answer :deep(.source-file-link--suspicious) {
  background: var(--app-subtle);
  color: var(--app-muted);
  text-decoration: underline dashed;
  text-underline-offset: 3px;
}

.chat-answer :deep(.source-file-link--suspicious:hover) {
  background: var(--app-fill-hover, var(--app-subtle));
  color: var(--app-text);
}

.dark .chat-answer :deep(.source-file-link--suspicious) {
  background: rgb(var(--base-text-color) / 0.08);
}

/* "待核实"徽标：warning 深阶文字胶囊 */
.chat-answer :deep(.citation-suspicious-badge) {
  display: inline-block;
  margin-left: 4px;
  padding: 1px 8px;
  border-radius: 9999px;
  background: rgb(var(--warning-color) / 0.12);
  color: rgb(var(--warning-700-color));
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
}

.dark .chat-answer :deep(.citation-suspicious-badge) {
  color: rgb(var(--warning-400-color));
}
</style>
