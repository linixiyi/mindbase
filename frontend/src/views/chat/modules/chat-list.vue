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

// pm-018：流式生成期间尊重用户滚动位置。此前对消息数组浅拷贝的 watch 在任何一次 chunk
// 追加后都无条件 scrollToBottom，长回答流式期间上翻回看会被反复拽回底部。现在：
// 1) 距底超过 FOLLOW_THRESHOLD 视为「上翻」，chunk 到达不再吸底，只点亮「回到底部」玻璃 chip；
// 2) 用户回到底部（自己滚回或点 chip 平滑回底）即恢复自动跟随；
// 3) 切换/新建会话恢复默认跟随——首条消息、新会话始终吸底。
const FOLLOW_THRESHOLD = 120;
const followBottom = ref(true);
const hasNewWhileAway = ref(false);
// 点 chip 后的平滑回底途中：滚动事件来自动画本身，不当作用户上翻
const restoringBottom = ref(false);

const showBackToBottom = computed(() => hasNewWhileAway.value && !followBottom.value);

// naive-ui NScrollbar 公开实例只暴露 scrollTo/scrollBy，滚动容器元素不从实例取——
// 元素级 scroll 事件不冒泡，事件目标即 .n-scrollbar-container 本身，从事件捕获最可靠
let scrollEl: HTMLElement | null = null;

function distanceFromBottom() {
  const el = scrollEl;
  if (!el) return 0;
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

function handleScroll(e: Event) {
  if (e.target instanceof HTMLElement) {
    scrollEl = e.target;
  }
  if (restoringBottom.value) {
    // 平滑回底途中只关心是否落底；中途位置不改变跟随状态
    if (distanceFromBottom() <= FOLLOW_THRESHOLD) {
      restoringBottom.value = false;
      followBottom.value = true;
      hasNewWhileAway.value = false;
    }
    return;
  }
  if (distanceFromBottom() <= FOLLOW_THRESHOLD) {
    followBottom.value = true;
    hasNewWhileAway.value = false;
  } else {
    // 用户上翻：作废在途的延迟吸底（否则 100ms 窗口内的 chunk 定时器会把视口又拽回底部，
    // 落底事件还会把跟随态重新拉回 true——流式期间几乎必现的竞态）
    followBottom.value = false;
    cancelPendingScroll();
  }
}

watch(() => [...list.value], scrollToBottom);

let scrollTimer: ReturnType<typeof setTimeout> | null = null;

function cancelPendingScroll() {
  if (scrollTimer !== null) {
    clearTimeout(scrollTimer);
    scrollTimer = null;
  }
}

function scrollToBottom() {
  if (!followBottom.value) {
    // 上翻期间到达的新内容：只点亮「回到底部」，不拽视口（平滑回底途中也不点亮）
    cancelPendingScroll();
    if (!restoringBottom.value) {
      hasNewWhileAway.value = true;
    }
    return;
  }
  cancelPendingScroll();
  // 记录调度时刻的 scrollTop：100ms 延迟窗口内内容增长不改变 scrollTop（只会推高距底值），
  // 只有用户翻动才改变它——以它作「窗口内用户已翻走」的判据，距底增大不误伤跟随
  const scheduledFromTop = scrollEl?.scrollTop ?? null;
  scrollTimer = setTimeout(() => {
    scrollTimer = null;
    // 落点双检：延迟窗口内用户已翻走（跟随态已被滚动事件翻转，或 scrollTop 已移动而事件
    // 尚未派发——markdown 高亮等长帧下可能发生）则放弃吸底，转为点亮「回到底部」
    const userMoved =
      !followBottom.value ||
      (scheduledFromTop !== null && scrollEl !== null && Math.abs(scrollEl.scrollTop - scheduledFromTop) > 1);
    if (!userMoved) {
      scrollbarRef.value?.scrollBy({
        top: 999999999999999,
        behavior: 'auto'
      });
    } else if (!restoringBottom.value) {
      hasNewWhileAway.value = true;
    }
  }, 100);
}

function backToBottom() {
  restoringBottom.value = true;
  hasNewWhileAway.value = false;
  scrollbarRef.value?.scrollTo({
    top: 999999999999999,
    behavior: 'smooth'
  });
  // 流式期间底部持续下移，平滑动画的落点是发起时刻的旧底部——可能始终差一小段够不到
  // 阈值。1s 后复查：已落底则恢复跟随并吸掉残距；未落底就以新底部为目标再平滑补一段
  // （最多 3 次）；仍不落底则放弃，保持「上翻」态，下一个 chunk 会重新点亮 chip
  let retries = 0;
  const settle = () => {
    if (!restoringBottom.value) return;
    if (distanceFromBottom() <= FOLLOW_THRESHOLD) {
      restoringBottom.value = false;
      followBottom.value = true;
      hasNewWhileAway.value = false;
      scrollToBottom();
      return;
    }
    if (retries >= 3) {
      restoringBottom.value = false;
      return;
    }
    retries += 1;
    scrollbarRef.value?.scrollTo({
      top: 999999999999999,
      behavior: 'smooth'
    });
    setTimeout(settle, 1000);
  };
  setTimeout(settle, 1000);
}

// 切换/新建会话：恢复默认吸底跟随（pm-018「首条消息/新会话默认跟随」）
watch(conversationId, () => {
  followBottom.value = true;
  hasNewWhileAway.value = false;
  restoringBottom.value = false;
});

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
  // 无选中会话不回填全量历史（pm-016）：删除/归档当前会话清选中态后，消息区停在欢迎区；
  // 选中会话后 params 变化会再次触发本次加载
  if (!conversationId.value) {
    loading.value = false;
    return;
  }
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
    <!-- relative：pm-018 的「回到底部」chip 与日期筛选钮共同的本组件定位上下文（浮层锚在本列内，不越到输入框之下） -->
    <div class="relative h-0 flex flex-col flex-1">
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
      <NScrollbar v-else ref="scrollbarRef" class="flex-1" @scroll="handleScroll">
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

      <!--
        pm-018：上翻期间有新内容到达时浮出的「回到底部」chip。胶囊玻璃材质走 global.scss 的
        .app-glass-chip 单源供给（DESIGN.md §12），36px 高与日期筛选钮同档、12px 缘距节奏；
        落在消息列底部、输入框之上，点击平滑回底并恢复吸底跟随
      -->
      <div
        v-if="showBackToBottom"
        class="chat-back-to-bottom pointer-events-none absolute inset-x-0 bottom-3 z-20 flex justify-center"
      >
        <button
          type="button"
          class="app-glass-chip pointer-events-auto h-9 gap-1.5 rounded-full px-3.5"
          @click="backToBottom"
        >
          <icon-lucide:arrow-down class="text-icon" />
          回到底部
        </button>
      </div>
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

/* pm-018「回到底部」浮层：容器只负责居中与不挡点击（pointer-events-none），button 自己恢复
   pointer-events；入场一次性上浮（复用 chat-welcome-rise 关键帧），消失即 v-if 摘除——
   伴随回底动作本身，无需出场动画 */
.chat-back-to-bottom {
  animation: chat-welcome-rise var(--app-dur-base, 250ms) var(--app-ease-emphasized, ease) both;
}

.chat-back-to-bottom button {
  font-size: 13px;
  color: var(--app-text-secondary);
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
