import DOMPurify from 'dompurify';

/**
 * markdown 渲染安全工具（task_0004：markdown 渲染消毒与引用链接安全构造）
 *
 * 聊天回答正文来自 LLM 输出与上游检索内容，属于不可信输入；vendor 的 markdown 渲染器 （vue-markdown-shiki）以 `html: true` 初始化，消息里的原始 HTML 会原样进入渲染结果，
 * 且其内部 DOM 重建还会把渲染产物经 innerHTML 注入。本模块在 markdown-it 渲染输出与 DOM 注入之间加一道 DOMPurify 白名单消毒，并为引用标注的文件名提供显式 HTML 转义。
 *
 * 约束：只做样式层与输出消毒，不改变引用标注的 DOM 结构、data-file-id 与解析数据流。
 */

/** 白名单标签：覆盖 markdown-it 标准输出 + shiki 高亮产物 + vendor 代码组/锚点插件产物 */
const SANITIZE_ALLOWED_TAGS = [
  'a',
  'abbr',
  'b',
  'blockquote',
  'br',
  'caption',
  'code',
  'del',
  'div',
  'em',
  'figcaption',
  'figure',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'hr',
  'i',
  'img',
  'input',
  'ins',
  'kbd',
  'label',
  'li',
  'mark',
  'ol',
  'p',
  'pre',
  'q',
  's',
  'samp',
  'small',
  'span',
  'strike',
  'strong',
  'sub',
  'summary',
  'sup',
  'table',
  'tbody',
  'td',
  'tfoot',
  'th',
  'thead',
  'tr',
  'u',
  'ul'
];

/** 白名单属性：不允许任何事件处理器（on*）、iframe/style/script 相关属性 */
const SANITIZE_ALLOWED_ATTR = [
  'align',
  'alt',
  'aria-label',
  'checked',
  'class',
  'colspan',
  'data-file-id',
  'disabled',
  'for',
  'height',
  'href',
  'id',
  'name',
  'rel',
  'rowspan',
  'src',
  'start',
  'style',
  'tabindex',
  'target',
  'title',
  'type',
  'width'
];

/**
 * URI 白名单：在 DOMPurify 默认基础上补 `data:image/*`——vendor 的 markdown-it validateLink 本就放行 data:image 供 markdown
 * 图片使用，消毒层保持同样口径， 其余 data:（如 data:text/html）与 javascript:/vbscript: 一律剥除。
 */
const SANITIZE_ALLOWED_URI_REGEXP =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|data:image\/(?:gif|png|jpeg|bmp|webp)[;,]):|[^a-z]|[a-z+.-]+(?:[^a-z+.-:]|$))/i;

/**
 * 消毒 markdown 渲染产物（纯函数）：对最终 HTML 做 DOMPurify 白名单过滤， `<script>`/`<iframe>` 整体剥除、`on*` 事件属性剥除、`javascript:` 等 危险 URI
 * 剥除；白名单内的正常 markdown 产物（表格/代码块/标题/引用/ 内部锚点/引用标注 span）原样通过，渲染结果与消毒前一致。
 */
export function sanitizeMarkdownHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: SANITIZE_ALLOWED_TAGS,
    ALLOWED_ATTR: SANITIZE_ALLOWED_ATTR,
    ALLOWED_URI_REGEXP: SANITIZE_ALLOWED_URI_REGEXP
  });
}

const HTML_ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
};

/** 显式 HTML 转义（纯函数）：文件名等不可信文本拼入 HTML 字符串前必须经过它 */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, ch => HTML_ESCAPE_MAP[ch]);
}

/** markdown 渲染器的最小结构类型（兼容 vendor 内置 markdown-it 与标准 markdown-it） */
type SanitizableMarkdownRenderer = {
  renderer: {
    render: (tokens: unknown, options: unknown, env: unknown) => string;
  };
};

const SANITIZE_FLAG = '__mindBaseSanitizeAttached__';

/**
 * 给已创建的 markdown-it 实例挂上输出消毒：包装 `renderer.render`， 使每一次 markdown → HTML 的产物都先过 {@link sanitizeMarkdownHtml}。
 * 幂等（同一实例只包一层），可安全地在多个消息组件上重复调用； 不替换渲染器实例、不改动任何渲染规则，shiki 高亮产物同样走白名单放行。
 */
export function attachMarkdownSanitization(md: SanitizableMarkdownRenderer): void {
  const renderer = md?.renderer;
  if (!renderer || typeof renderer.render !== 'function') {
    return;
  }
  const flagHolder = renderer as unknown as Record<string, unknown>;
  if (flagHolder[SANITIZE_FLAG]) {
    return;
  }

  const originalRender = renderer.render.bind(renderer);
  renderer.render = (tokens, options, env) => sanitizeMarkdownHtml(originalRender(tokens, options, env));
  flagHolder[SANITIZE_FLAG] = true;
}
