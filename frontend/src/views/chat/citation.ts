/**
 * 聊天回答引用链接构造（task-0010：从 chat-message.vue 抽出的纯函数层）
 *
 * 助手回答里的「来源#N: 文件名 [| 第X页 | MD5:xxx]」片段由 LLM 生成，属于不可信输入； 本模块负责把这类片段解析成引用胶囊（span + data-file-id），并把解析出的来源按序收集， 供
 * chat-message.vue 写入本地状态后支撑点击预览（fileName/fileMd5/页码数据流不变）。
 *
 * 安全约束：
 *
 * - 文件名拼入 HTML 前必须经过 escapeHtml（../../utils/markdown），恶意文件名不得生成真实标签；
 * - 引用胶囊只产出 span 文本结构，绝不产出 <a href>，javascript: 等载荷在本层被天然中和 （markdown 链接形态的消毒仍在渲染链的 DOMPurify，不得因本次抽离移除）；
 * - 行为与抽取前完全一致：来源#N、页码、md5、SUSPICIOUS「待核实」标记的 DOM 结构与文案零改动。
 */
import { escapeHtml } from '../../utils/markdown';

/** 单个引用来源（与原 chat-message.vue 的 sourceFiles 条目同构） */
export interface CitationSourceFile {
  fileName: string;
  id: string;
  referenceNumber: number;
  fileMd5?: string;
  pageNumber?: number;
}

export interface RenderCitationLinksOptions {
  /** 来源收集器（输出参数）：调用方传入数组，函数先清空再按解析顺序写入—— 与原实现「processSourceLinks 重置来源文件列表，避免重复」一致； 返回值 sourceFiles 即该数组，组件据此写入本地状态。 */
  sources: CitationSourceFile[];
  /** 引用校验映射（msg.referenceMappings）：citationStatus === 'SUSPICIOUS' 的来源降级并渲染「待核实」 */
  mappings?: Record<string, Api.Chat.ReferenceEvidence>;
}

export interface RenderCitationLinksResult {
  /** 处理后的正文：引用片段已替换为胶囊 HTML，裸 URL 已包 <> */
  text: string;
  /** 本次解析出的全部来源，与 options.sources 为同一数组 */
  sourceFiles: CitationSourceFile[];
}

// 裸 URL 识别：仅处理 http(s) 链接；已在标签或 markdown 链接（](、href=、src=）里的 URL 由守卫跳过
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]+/g;

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

interface CreateSourceLinkInput {
  sources: CitationSourceFile[];
  mappings: Record<string, Api.Chat.ReferenceEvidence> | undefined;
  sourceNum: string;
  fileName: string;
  extras?: { fileMd5?: string; pageNumber?: number; displayName?: string };
}

function createSourceLink({ sources, mappings, sourceNum, fileName, extras }: CreateSourceLinkInput): string {
  const trimmedFileName = fileName.trim();
  const fileId = `source-file-${sources.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);
  const suspicious = mappings?.[sourceNum]?.citationStatus === 'SUSPICIOUS';
  const linkClass = suspicious ? 'source-file-link source-file-link--suspicious' : 'source-file-link';

  sources.push({
    fileName: trimmedFileName,
    id: fileId,
    referenceNumber,
    fileMd5: extras?.fileMd5,
    pageNumber: extras?.pageNumber
  });

  // 文件名来自 LLM 文本，拼接进 HTML 前显式转义，恶意文件名不再生成标签；
  // sources 内保留原始文件名，引用点击预览的数据流零改动
  const safeDisplayName = escapeHtml(extras?.displayName || trimmedFileName);
  const link = `来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${safeDisplayName}</span>`;
  // 引用校验判为不支撑的来源降低展示优先级，并提示用户自行核实
  return suspicious
    ? `${link}<span class="citation-suspicious-badge" title="引用校验：该片段可能无法支撑这句话">待核实</span>`
    : link;
}

/**
 * 解析助手回答里的引用片段并渲染成引用胶囊（纯函数；原 chat-message.vue › processSourceLinks 与裸 URL 归一化的等价迁移，组合顺序保持 normalizeBareUrls ∘
 * processSourceLinks）。
 *
 * 支持单个来源，也支持一个括号里包含多个来源： (来源#1: test.pdf | 第5页; 来源#2: other.pdf | 第8页)
 */
export function renderCitationLinks(
  text: string,
  { sources, mappings }: RenderCitationLinksOptions
): RenderCitationLinksResult {
  // 重置来源文件列表，避免重复（输出参数契约：调用方可复用同一收集器）
  sources.length = 0;

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
    return createSourceLink({
      sources,
      mappings,
      sourceNum,
      fileName,
      extras: {
        pageNumber: Number.parseInt(pageNum, 10),
        displayName: `${fileName.trim()} (第${pageNum}页)`
      }
    });
  });

  processedText = processedText.replace(md5Pattern, (...args: unknown[]) => {
    const [_match, sourceNum, fileName, fileMd5] = args as [string, string, string, string];
    return createSourceLink({
      sources,
      mappings,
      sourceNum,
      fileName,
      extras: { fileMd5: fileMd5.trim() }
    });
  });

  processedText = processedText.replace(simplePattern, (_match, sourceNum, fileName) => {
    return createSourceLink({ sources, mappings, sourceNum, fileName });
  });

  return { text: normalizeBareUrls(processedText), sourceFiles: sources };
}
