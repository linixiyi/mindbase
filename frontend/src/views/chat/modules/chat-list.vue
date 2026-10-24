<script setup lang="ts">
import { useRouter } from 'vue-router';
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const router = useRouter();
const { list, sessionId, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);

// 冷启动感知（pm-004）：用现有知识库列表接口探测可访问文档数（分页只取 1 条、看 totalElements，
// 不新增接口）。空知识库时欢迎区不展示必然空检索的建议问法，改为「去上传文档」引导；
// 探测失败保持现状（展示建议问法），不让这一次请求改变默认体验
const knowledgeBaseEmpty = ref(false);
const knowledgeBaseChecked = ref(false);

async function checkKnowledgeBaseEmpty() {
  const { error, data } = await request<Api.KnowledgeBase.List>({
    url: '/documents/accessible',
    params: { page: 1, size: 1 }
  });
  if (!error && data) {
    knowledgeBaseEmpty.value = Number(data.totalElements || 0) === 0;
  }
  knowledgeBaseChecked.value = true;
}

function goKnowledgeBase() {
  router.push('/knowledge-base');
}

// R2 welcome：建议问法来自本会话真实回答过的产品能力（知识库问答/引用溯源/混合检索/总结）
const suggestions = ['MindBase 的引用标注如何做到可溯源？', '什么是混合检索？', '帮我总结知识库里的内容'];

function applySuggestion(q: string) {
  chatStore.input = { message: q, conversationId: chatStore.conversationId || undefined };
}

// pm-006：日期筛选改义为「过滤当前已加载消息」。此前日期与会话 ID 同发，后端 conversationId
// 分支恒丢弃日期参数，控件恒无效；现在会话消息整段载入（该分支本就无分页）后在前端按日期过滤，
// 无会话选中时 list 为全量历史，同样适用。请求参数不再携带日期
const params = computed(() => {
  const p: Record<string, string> = {};
  if (conversationId.value) {
    p.conversationId = conversationId.value;
  }
  return p;
});

const filterActive = computed(() => Array.isArray(range.value));

const visibleMessages = computed(() => {
  if (!filterActive.value || !range.value) return list.value;
  const [start, end] = range.value;
  const startMs = dayjs(start).startOf('day').valueOf();
  const endMs = dayjs(end).endOf('day').valueOf();
  return list.value.filter(message => {
    // 刚发出的实时消息可能尚无 timestamp——不隐藏，避免筛选期间发送的消息凭空消失
    if (!message.timestamp) return true;
    const timestamp = dayjs(message.timestamp).valueOf();
    return timestamp >= startMs && timestamp <= endMs;
  });
});

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = visibleMessages.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

let historyRequestId = 0;
watch(params, getList, { immediate: true });

async function getList() {
  historyRequestId += 1;
  const requestId = historyRequestId;
  const messagesBeforeRequest = [...list.value];
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (requestId !== historyRequestId) return;
  const messagesUnchanged =
    list.value.length === messagesBeforeRequest.length &&
    list.value.every((message, index) => message === messagesBeforeRequest[index]);
  const hasPending = list.value.some(message => ['pending', 'loading'].includes(message.status || ''));
  if (!error && messagesUnchanged && !hasPending) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
  checkKnowledgeBaseEmpty();
});

onActivated(() => {
  // keep-alive 回到本页时重查一次：上传完文档回来，冷启动引导应收起、恢复建议问法
  if (knowledgeBaseChecked.value) {
    checkKnowledgeBaseEmpty();
  }
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
</script>

<template>
  <Suspense>
    <div class="h-0 flex flex-col flex-1">
      <!--
        日期筛选：默认隐藏，收进右上角图标触发的浮层（overlay 材质由 global.scss 的 .n-popover 承担）；
        pm-006 改义：range 只驱动前端过滤（visibleMessages），不再进请求参数
      -->
      <NPopover v-if="!showEmpty" trigger="click" placement="bottom-end" :show-arrow="false">
        <template #trigger>
          <button
            class="app-glass-chip chat-filter-toggle absolute right-3 top-3 z-20 h-9 w-9 rounded-full"
            :class="{ 'chat-filter-toggle--active': filterActive }"
            aria-label="按日期筛选消息"
            title="按日期筛选消息"
          >
            <icon-lucide:calendar class="text-icon" />
          </button>
        </template>
        <div class="chat-filter-panel">
          <div class="chat-filter-panel__label">按日期筛选当前会话消息</div>
          <NDatePicker v-model:value="range" type="daterange" clearable size="small" />
        </div>
      </NPopover>

      <!--
 Empty state → welcome（R2 animate+delight）：产品语言直接引导提问，建议问法一键填入输入框；
           入场为一次性 stagger（250ms emphasized），reduced-motion 下被全局压扁
           冷启动分支（pm-004）：空知识库下建议问法必然命中空检索，换成「去上传文档」引导卡
-->
      <div v-if="showEmpty" class="chat-welcome">
        <div class="app-glass-rim chat-welcome__badge">
          <icon-lucide:message-circle class="text-icon-xl text-[rgb(var(--primary-color))]" />
        </div>
        <template v-if="knowledgeBaseChecked && knowledgeBaseEmpty">
          <div class="chat-welcome__title">先给知识库添加一份文档</div>
          <div class="chat-welcome__desc">上传文档并完成向量化后，在这里提问就能得到带引用标注的回答</div>
          <NButton type="primary" class="chat-welcome__cta" @click="goKnowledgeBase">
            <template #icon>
              <icon-lucide:upload class="text-icon" />
            </template>
            去上传文档
          </NButton>
        </template>
        <template v-else>
          <div class="chat-welcome__title">
            {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
          </div>
          <div class="chat-welcome__desc">直接在下方提问——回答基于知识库检索生成，句末附引用标注</div>
          <div v-if="knowledgeBaseChecked" class="chat-welcome__suggest">
            <span class="chat-welcome__label">试试这样问</span>
            <button
              v-for="(q, i) in suggestions"
              :key="q"
              type="button"
              class="chat-welcome__chip"
              :style="{ animationDelay: `${140 + i * 60}ms` }"
              @click="applySuggestion(q)"
            >
              {{ q }}
            </button>
          </div>
        </template>
      </div>

      <!-- Message list -->
      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div v-if="filterActive && visibleMessages.length === 0" class="chat-filter-empty">
            所选日期范围内没有消息，可调整范围或清除筛选
          </div>
          <div v-else class="mx-auto max-w-[800px] w-full px-4 pb-6 pt-12">
            <VueMarkdownItProvider>
              <ChatMessage
                v-for="(item, index) in visibleMessages"
                :key="index"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
              />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped lang="scss">
/* 筛选入口：胶囊玻璃 chip，材质经 global.scss 的 .app-glass-chip 单源供给——与左侧会话列表开关
   同尺寸（36px）、同材质、同 12px 缘距节奏（DESIGN.md §12）；此处只保留激活态（有筛选值时一线蓝）。
   absolute 相对 chat/index.vue 的画布列定位。 */
.chat-filter-toggle--active,
.chat-filter-toggle--active:hover {
  color: rgb(var(--primary-color));
}
.dark .chat-filter-toggle--active,
.dark .chat-filter-toggle--active:hover {
  color: rgb(var(--primary-400-color));
}

/* 弹层内文：Footnote 说明 + 控件层日期选择器 */
.chat-filter-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-filter-panel__label {
  font-size: 12px;
  color: var(--app-text-tertiary);
}

/* 筛选空结果：居中 Body/secondary 说明——次级状态，不立徽标不抢主层级 */
.chat-filter-empty {
  padding: 96px 24px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-text-secondary);
  text-align: center;
}

/* R2 welcome：一次性 stagger 入场（250ms emphasized）；建议问法为胶囊，hover 提到主色 */
.chat-welcome {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
}

/* R2 徽标 tile 升为玻璃徽标（R8 bolder，DESIGN.md §12）：azure 玻璃体 = 半透明淡蓝染
   + 边缘受光 + e1 软影。
   R9 quieter：发丝线描边移除——描边与 rim 在同一顶缘声明了两次边缘（ghost ring，
   craft floor「elevation 只声明一次」）；玻璃徽标的形由淡蓝染界 + 受光 + 软影给出。
   R12 overdrive：受光升级为共享各向异性 rim 环（.app-glass-rim::before，DESIGN.md §18）。
   不加 backdrop-filter——空态画布是纯白，无内容可折射，玻璃性由透明染+边缘+受光表达，
   少一层合成。亮 10% / 暗 16% 淡蓝染，与选中态胶囊同一对透明档（DESIGN.md › Chips）。 */
.chat-welcome__badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 80px;
  border-radius: 24px;
  background: rgb(var(--primary-color) / 0.1);
  box-shadow: var(--app-shadow-e1);
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) both;
}

/* 暗色只抬淡蓝染一档（0.10→0.16，对齐选中态胶囊的暗色档）；边缘高光
   走共享各向异性 rim 环（.app-glass-rim::before，R12 §18）token 自动换暗值，不另立词汇 */
.dark .chat-welcome__badge {
  background: rgb(var(--primary-color) / 0.16);
}

.chat-welcome__title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.35;
  color: rgb(var(--base-text-color));
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 60ms both;
}

.chat-welcome__desc {
  color: var(--app-text-secondary);
  font-size: 14px;
  line-height: 1.55;
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 120ms both;
}

.chat-welcome__suggest {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
  max-width: 560px;
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) 180ms both;
}

.chat-welcome__label {
  width: 100%;
  font-size: 12px;
  color: var(--app-text-tertiary);
}

/* 冷启动 CTA（pm-004）：主按钮居中，与建议胶囊区同一节奏（margin-top 对齐 __suggest） */
.chat-welcome__cta {
  margin-top: 8px;
}

.chat-welcome__chip {
  padding: 6px 14px;
  border: 1px solid var(--app-border);
  border-radius: 9999px;
  background: var(--app-elevated, var(--app-surface));
  font-size: 14px;
  color: var(--app-text-secondary);
  cursor: pointer;
  transition:
    background-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    border-color var(--app-dur-fast, 150ms) var(--app-ease-standard, ease),
    transform var(--app-dur-fast, 150ms) var(--app-ease-standard, ease);
}

.chat-welcome__chip:hover {
  border-color: rgb(var(--primary-color) / 0.4);
  background: rgb(var(--primary-color) / 0.08);
  color: rgb(var(--primary-color));
}

/* R10 animate 复审：建议问法胶囊是原生 button，吃不到全局 .n-button 按压律——补上同一记
   scale 0.97（DESIGN.md › Motion › 微反馈），与 chip/NButton 家族同一手感；focus-visible
   与 .app-glass-chip 同规格 */
.chat-welcome__chip:active {
  transform: scale(0.97);
}

.chat-welcome__chip:focus-visible {
  outline: 2px solid rgb(var(--primary-color) / 0.6);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .chat-welcome__chip:active {
    transform: none;
  }
}

@keyframes chat-welcome-rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}
</style>
