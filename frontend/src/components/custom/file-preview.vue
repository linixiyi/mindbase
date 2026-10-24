<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import { getServiceBaseURL } from '@/utils/service';
import PdfDocumentViewer from '@/components/custom/pdf-document-viewer.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  fileName: string;
  fileMd5?: string;
  pageNumber?: number;
  anchorText?: string;
  searchText?: string;
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string;
  retrievalQuery?: string;
  evidenceSnippet?: string;
  matchedChunkText?: string;
  score?: number | null;
  chunkId?: number | null;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');
const previewType = ref<'pdf' | 'image' | 'text' | 'download'>('text');
const previewUrl = ref('');
const sourceUrl = ref('');
const singlePageMode = ref(false);
const sourcePageNumber = ref<number | undefined>(undefined);
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL: serviceBaseUrl } = getServiceBaseURL(import.meta.env, isHttpProxy);

const resolvedPreviewUrl = computed(() => resolveFileAccessUrl(previewUrl.value));
const resolvedSourceUrl = computed(() => resolveFileAccessUrl(sourceUrl.value));
const fileExtensionLabel = computed(() => {
  const extension = getFileExt(props.fileName)?.toUpperCase();
  return extension || 'FILE';
});
const fallbackRetrievalLabel = computed(() => {
  if (props.retrievalMode === 'TEXT_ONLY') {
    return '关键词召回';
  }
  if (props.retrievalMode === 'HYBRID') {
    return '混合召回（语义相关 + 关键词）';
  }
  if (props.retrievalMode === 'HYBRID_RRF') {
    return '混合召回（RRF 融合排序，未经 rerank）';
  }
  return '';
});
const resolvedHighlightAnchor = computed(() => props.anchorText || '');
const resolvedHighlightSearchText = computed(() => {
  if (resolvedHighlightAnchor.value) {
    return '';
  }

  return [props.searchText, props.matchedChunkText]
    .map(text => text?.trim())
    .filter((text, index, values): text is string => Boolean(text) && values.indexOf(text) === index)
    .join('\n');
});
const displayScore = computed(() => {
  if (typeof props.score !== 'number' || Number.isNaN(props.score)) {
    return '';
  }
  return props.score.toFixed(3);
});
const displayPage = computed(() => sourcePageNumber.value || props.pageNumber || undefined);
const displayPageLabel = computed(() => (displayPage.value ? `第 ${displayPage.value} 页` : ''));
const headerMetaLine = computed(() => {
  if (previewType.value === 'pdf') {
    return [displayPageLabel.value, displayScore.value ? `分数 ${displayScore.value}` : ''].filter(Boolean).join(' / ');
  }
  if (previewType.value === 'image') {
    return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : '']
      .filter(Boolean)
      .join(' / ');
  }
  if (previewType.value === 'text') {
    return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : '']
      .filter(Boolean)
      .join(' / ');
  }
  return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : ''].filter(Boolean).join(' / ');
});
const heroHeadline = computed(() => {
  if (props.retrievalLabel || fallbackRetrievalLabel.value) {
    return props.retrievalLabel || fallbackRetrievalLabel.value;
  }
  if (previewType.value === 'pdf') {
    return '文档已定位到可阅读页';
  }
  return '已就绪的引用文档';
});
const heroDescription = computed(() => {
  if (props.retrievalQuery) {
    return '左侧展示的是本次 RAG 检索的问题与定位线索，右侧则直接打开原始文档，方便核对答案依据。';
  }
  if (props.evidenceSnippet) {
    return '左侧展示的是这次检索的定位线索，右侧则直接打开原始文档，方便核对答案依据。';
  }
  if (resolvedHighlightAnchor.value) {
    return '当前预览会优先围绕这条上下文线索定位，方便你核对答案和原文是否一致。';
  }
  return '这里展示的是引用来源的原始文档内容，你可以直接浏览、下载或在新窗口中打开。';
});
const canOpenInNewTab = computed(() => Boolean(resolvedSourceUrl.value || resolvedPreviewUrl.value));

function resolveFileAccessUrl(url: string) {
  if (!url) return '';
  if (/^(https?:)?\/\//i.test(url) || /^(blob:|data:)/i.test(url)) {
    return url;
  }

  if (url.startsWith('/api/')) {
    if (serviceBaseUrl.startsWith('/proxy-')) {
      return `${serviceBaseUrl}${url.replace(/^\/api\/v\d+/, '')}`;
    }

    if (/^https?:\/\//i.test(serviceBaseUrl)) {
      return `${new URL(serviceBaseUrl).origin}${url}`;
    }

    const serviceOrigin = serviceBaseUrl.replace(/\/api(?:\/v\d+)?\/?$/, '');
    return `${serviceOrigin}${url}`;
  }

  if (url.startsWith('/')) {
    return url;
  }

  return `${serviceBaseUrl.replace(/\/$/, '')}/${url.replace(/^\//, '')}`;
}

// 获取文件图标：本地 sprite 只注册了 pdf/doc/docx/txt/dflt 五枚 tile（R7 colorize），
// 其余扩展名（md/jpg 等）一律落 dflt 兜底——引用不存在的 symbol 会渲染成空白 <use>
function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

// 监听文件名变化，加载预览内容
watch(
  () => props.fileName,
  async newFileName => {
    if (newFileName && props.visible) {
      await loadPreviewContent();
    }
  },
  { immediate: true }
);

// 监听可见性变化
watch(
  () => props.visible,
  async visible => {
    if (visible && props.fileName) {
      await loadPreviewContent();
    }
  }
);

// 加载预览内容
type PreviewResponse = {
  fileName: string;
  fileSize: number;
  fileMd5?: string;
  content?: string;
  previewUrl?: string;
  sourceUrl?: string;
  singlePageMode?: boolean;
  sourcePageNumber?: number;
  previewType?: 'pdf' | 'image' | 'text' | 'download';
};

function applyPreviewResponse(data: PreviewResponse) {
  previewType.value = data.previewType || 'download';
  content.value = data.content || '';
  previewUrl.value = data.previewUrl || '';
  sourceUrl.value = data.sourceUrl || data.previewUrl || '';
  singlePageMode.value = Boolean(data.singlePageMode);
  sourcePageNumber.value = data.sourcePageNumber || props.pageNumber;
}

async function loadPreviewContent() {
  if (!props.fileName) return;

  const previewMode = props.fileMd5 ? 'MD5' : '文件名';

  console.log('[文件预览] 开始加载预览内容:', {
    fileName: props.fileName,
    fileMd5: props.fileMd5,
    visible: props.visible
  });

  loading.value = true;
  error.value = '';
  content.value = '';
  previewUrl.value = '';
  sourceUrl.value = '';
  singlePageMode.value = false;
  sourcePageNumber.value = undefined;
  previewType.value = 'text';

  try {
    const params = props.fileMd5
      ? { fileName: props.fileName, fileMd5: props.fileMd5, pageNumber: props.pageNumber }
      : { fileName: props.fileName, pageNumber: props.pageNumber };

    console.log(`[文件预览] 使用${previewMode}模式预览, 请求参数:`, params);

    const { error: requestError, data } = await request<PreviewResponse>({
      url: '/documents/preview',
      params
    });

    console.log(`[文件预览] ${previewMode}模式API响应:`, {
      hasError: Boolean(requestError),
      error: requestError,
      hasData: Boolean(data),
      contentLength: data?.content?.length || 0,
      contentPreview: data?.content?.substring(0, 100) || ''
    });

    if (requestError) {
      error.value = `预览失败：${requestError.message || '未知错误'}`;
    } else if (data) {
      applyPreviewResponse(data);
    }
  } catch (err: any) {
    error.value = `预览失败：${err.message || '网络错误'}`;
  } finally {
    loading.value = false;
  }
}

// 下载文件
async function downloadFile() {
  if (!props.fileName) return;

  downloading.value = true;

  try {
    // 优先使用 MD5 下载（如果存在）
    if (props.fileMd5) {
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
        fileMd5?: string;
      }>({
        url: '/documents/download-by-md5',
        params: {
          fileMd5: props.fileMd5
        }
      });

      if (requestError) {
        window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
      } else if (data) {
        // 使用预签名URL下载文件
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    } else {
      // 降级：使用文件名下载（向后兼容）
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
      }>({
        url: '/documents/download',
        params: {
          fileName: props.fileName
        }
      });

      if (requestError) {
        window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
      } else if (data) {
        // 使用预签名URL下载文件
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    }
  } catch (err: any) {
    window.$message?.error(`下载失败：${err.message || '网络错误'}`);
  } finally {
    downloading.value = false;
  }
}

function openPreviewInNewTab() {
  const targetUrl = resolvedSourceUrl.value || resolvedPreviewUrl.value;
  if (!targetUrl) return;

  if (previewType.value === 'pdf' && displayPage.value) {
    window.open(`${targetUrl}#page=${displayPage.value}`, '_blank', 'noopener,noreferrer');
    return;
  }

  window.open(targetUrl, '_blank', 'noopener,noreferrer');
}

// 关闭预览
function closePreview() {
  emit('close');
}
</script>

<template>
  <div class="file-preview-container">
    <div class="preview-content">
      <template v-if="loading">
        <div class="state-panel">
          <div class="state-orb">
            <NSpin size="large" />
          </div>
          <div class="state-copy">
            <strong>正在装载引用文档</strong>
            <span>整理线索、页码定位和可预览内容。</span>
          </div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="state-panel state-panel--error">
          <div class="state-orb state-orb--error">
            <icon-lucide:circle-alert class="text-icon-xl" />
          </div>
          <div class="state-copy">
            <strong>这份文档暂时没能打开</strong>
            <span>{{ error }}</span>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="content-wrapper" :class="{ 'content-wrapper--immersive': previewType === 'pdf' && previewUrl }">
          <aside class="insight-rail">
            <section class="info-card source-card">
              <div class="source-card-top">
                <div class="file-badge-shell">
                  <div class="file-badge-icon">
                    <SvgIcon :local-icon="getFileIcon(fileName)" class="text-icon" />
                  </div>
                  <div class="file-badge-copy">
                    <h2 class="preview-title">{{ fileName }}</h2>
                    <p v-if="headerMetaLine" class="preview-subtitle">{{ headerMetaLine }}</p>
                  </div>
                </div>
              </div>
              <div class="source-actions">
                <NButton
                  v-if="previewType !== 'pdf'"
                  size="small"
                  secondary
                  :disabled="!canOpenInNewTab"
                  @click="openPreviewInNewTab"
                >
                  <template #icon>
                    <icon-lucide:external-link />
                  </template>
                  新窗口
                </NButton>
                <NButton size="small" secondary :loading="downloading" @click="downloadFile">
                  <template #icon>
                    <icon-lucide:download />
                  </template>
                  下载
                </NButton>
                <NButton size="small" quaternary @click="closePreview">
                  <template #icon>
                    <icon-lucide:x />
                  </template>
                  关闭
                </NButton>
              </div>
            </section>

            <section class="info-card info-card--hero">
              <span class="info-label">概览</span>
              <strong class="info-title">{{ heroHeadline }}</strong>
              <p class="info-copy">{{ heroDescription }}</p>
              <div v-if="retrievalQuery" class="info-inline-block">
                <span class="info-label">检索问题</span>
                <p class="support-copy">{{ retrievalQuery }}</p>
              </div>
            </section>

            <section v-if="evidenceSnippet" class="info-card">
              <span class="info-label">线索</span>
              <p class="support-copy">{{ evidenceSnippet }}</p>
            </section>

            <section v-else-if="resolvedHighlightAnchor" class="info-card">
              <span class="info-label">定位线索</span>
              <p class="support-copy">{{ resolvedHighlightAnchor }}</p>
            </section>
          </aside>

          <section class="preview-stage">
            <div class="stage-body">
              <template v-if="previewType === 'pdf' && previewUrl">
                <div class="pdf-preview-stack">
                  <PdfDocumentViewer
                    :url="resolvedPreviewUrl"
                    :source-url="resolvedSourceUrl"
                    :file-name="fileName"
                    :page-number="pageNumber"
                    :single-page-mode="singlePageMode"
                    :source-page-number="sourcePageNumber"
                    :anchor-text="resolvedHighlightAnchor"
                    :search-text="resolvedHighlightSearchText"
                    :visible="visible"
                  />
                </div>
              </template>
              <template v-else-if="previewType === 'image' && resolvedPreviewUrl">
                <div class="image-preview-shell">
                  <img :src="resolvedPreviewUrl" :alt="fileName" class="preview-image" />
                </div>
              </template>
              <template v-else-if="previewType === 'text'">
                <div class="text-preview-shell">
                  <pre class="preview-text">{{ content }}</pre>
                </div>
              </template>
              <template v-else>
                <div class="download-placeholder">
                  <div class="placeholder-icon">
                    <SvgIcon :local-icon="getFileIcon(fileName)" class="text-icon-xl" />
                  </div>
                  <div class="state-copy">
                    <strong>当前格式暂不支持在线预览</strong>
                    <span>你可以先下载文件，或在新窗口中尝试打开原始资源。</span>
                  </div>
                  <div class="placeholder-actions">
                    <NButton secondary :disabled="!canOpenInNewTab" @click="openPreviewInNewTab">
                      <template #icon>
                        <icon-lucide:external-link />
                      </template>
                      新窗口打开
                    </NButton>
                    <NButton type="primary" @click="downloadFile">
                      <template #icon>
                        <icon-lucide:download />
                      </template>
                      下载后查看
                    </NButton>
                  </div>
                </div>
              </template>
            </div>
          </section>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
/*
 * 引用详情预览 · 静光玻璃（DESIGN.md）
 * 正文阅读区：bg-container 实底 + 发丝线描边，零装饰阴影（阴影只属浮层，
 * 弹窗挂法由外壳承载 elevation-3）；圆角走 10/14/18/24 刻度；语义色作文字时
 * 浅色取 -700、暗色取 -400；独立页与知识库弹窗两种挂法共用同一套面板语言。
 */
.file-preview-container {
  @apply relative flex h-full min-h-0 flex-col overflow-hidden bg-container;
  height: min(92vh, calc(100vh - 20px));
  min-height: min(760px, calc(100vh - 20px));
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-xl);
  color: var(--app-text);

  .file-badge-shell {
    @apply flex items-start gap-4;
  }

  .file-badge-icon {
    @apply flex h-13 w-13 shrink-0 items-center justify-center border border-solid border-hairline bg-fill-subtle text-secondary;
    border-radius: var(--app-radius-md);
  }

  .file-badge-copy {
    @apply min-w-0;
  }

  .preview-title {
    @apply m-0 truncate;
    font-size: 20px;
    font-weight: 600;
    line-height: 1.35;
    color: var(--app-text);
  }

  .preview-subtitle {
    @apply mt-1;
    font-size: 14px;
    line-height: 1.55;
    color: var(--app-text-secondary);
  }

  .preview-content {
    @apply relative z-1 min-h-0 flex-1 overflow-hidden p-4;

    .content-wrapper {
      @apply grid h-full min-h-0 grid-cols-[240px_minmax(0,1fr)] gap-4 overflow-hidden;
    }

    .content-wrapper--immersive {
      grid-template-columns: 240px minmax(0, 1fr);
    }

    .state-panel {
      @apply flex h-full min-h-[420px] flex-col items-center justify-center gap-5 px-10 text-center;
    }

    .state-orb {
      @apply flex h-16 w-16 items-center justify-center border border-solid border-hairline bg-fill-subtle text-secondary;
      border-radius: var(--app-radius-md);
    }

    .state-orb--error {
      background: rgb(var(--error-color) / 0.12);
      border-color: rgb(var(--error-color) / 0.2);
      color: rgb(var(--error-700-color));

      .dark & {
        color: rgb(var(--error-400-color));
      }
    }

    .state-copy {
      @apply flex max-w-520px flex-col gap-2;
      color: var(--app-text-secondary);
    }

    .state-copy strong {
      font-size: 20px;
      font-weight: 600;
      line-height: 1.35;
      color: var(--app-text);
    }

    .state-copy span {
      font-size: 14px;
      line-height: 1.6;
    }

    .insight-rail {
      @apply flex min-h-0 min-w-0 flex-col gap-4 overflow-y-auto overflow-x-hidden pr-1;
    }

    /* 卡片内小面板：fill-subtle 静底 + md 圆角，零边框零阴影 */
    .info-card {
      @apply p-4;
      background: var(--app-fill-subtle);
      border-radius: var(--app-radius-md);
      color: var(--app-text-secondary);
    }

    .info-card--hero {
      @apply bg-transparent p-0;
    }

    /* 来源卡：rail 主面板，发丝线描边 + lg 圆角，零阴影 */
    .source-card {
      @apply gap-0 border border-solid border-hairline p-4;
      border-radius: var(--app-radius-lg);
    }

    .source-card-top {
      @apply min-w-0 overflow-hidden;
    }

    .source-actions {
      @apply mt-4 flex flex-wrap gap-2;
    }

    /* 中文小标签：不用大写与加宽字距冒充 eyebrow */
    .info-label {
      font-size: 12px;
      font-weight: 500;
      line-height: 1.4;
      color: var(--app-text-tertiary);
    }

    .info-title {
      @apply mt-3 block break-words;
      font-size: 20px;
      font-weight: 600;
      line-height: 1.35;
      color: var(--app-text);
    }

    .info-copy,
    .support-copy {
      @apply mb-0 mt-3 break-words;
      font-size: 14px;
      line-height: 1.65;
      color: var(--app-text-secondary);
      overflow-wrap: anywhere;
    }

    .info-inline-block {
      @apply mt-4 px-4 py-3;
      background: var(--app-fill-subtle);
      border-radius: var(--app-radius-sm);
    }

    /* 预览主舞台：发丝线视窗 + lg 圆角，实底承接文档内容 */
    .preview-stage {
      @apply min-h-0 overflow-hidden;
    }

    .stage-body {
      @apply h-full min-h-0 overflow-hidden border border-solid border-hairline;
      border-radius: var(--app-radius-lg);
      background: var(--app-surface);
    }

    .pdf-preview-stack {
      @apply flex h-full min-h-0 flex-col;
    }

    .text-preview-shell {
      @apply h-full p-4;
    }

    .preview-text {
      @apply m-0 h-full overflow-auto whitespace-pre-wrap break-words;
      font-size: 14px;
      line-height: 1.6;
      color: var(--app-text-secondary);
    }

    .image-preview-shell {
      @apply flex h-full min-h-0 items-center justify-center overflow-auto p-4;
    }

    .preview-image {
      @apply max-h-full max-w-full border border-solid border-hairline object-contain;
      border-radius: var(--app-radius-md);
    }

    .download-placeholder {
      @apply flex h-full min-h-[320px] flex-col items-center justify-center gap-5 px-8 text-center;
      color: var(--app-text-secondary);
    }

    .placeholder-icon {
      @apply flex h-16 w-16 items-center justify-center border border-solid border-hairline bg-fill-subtle text-secondary;
      border-radius: var(--app-radius-md);
    }

    .placeholder-actions {
      @apply flex flex-wrap items-center justify-center gap-3;
    }
  }

  @media (max-width: 960px) {
    height: min(92vh, calc(100vh - 24px));
    min-height: auto;

    .preview-content {
      @apply px-4 pb-4;
    }

    .preview-content .content-wrapper,
    .preview-content .content-wrapper--immersive {
      @apply grid-cols-1;
    }

    .preview-content .insight-rail {
      @apply max-h-[30vh] pr-0;
    }

    .preview-content .preview-stage {
      min-height: 58vh;
    }
  }
}
</style>
