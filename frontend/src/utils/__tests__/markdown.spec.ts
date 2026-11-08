import MarkdownIt from 'markdown-it';
import { describe, expect, it } from 'vitest';
import { attachMarkdownSanitization, escapeHtml, sanitizeMarkdownHtml } from '../markdown';

/** 与 vendor（vue-markdown-shiki）一致的初始化口径：html: true */
const mdIt = new MarkdownIt({ html: true, linkify: true });

// jsdom environment 提供全局 DOMParser（与 vendor VueMarkdownIt 内部同款解析口径）
function parseFragment(html: string): Document {
  return new DOMParser().parseFromString(html, 'text/html');
}

describe('sanitizeMarkdownHtml：XSS 向量剥除', () => {
  it('<img src=x onerror=alert(1)> 不产生可执行节点（onerror 被剥除）', () => {
    const result = sanitizeMarkdownHtml('<img src=x onerror=alert(1)>');
    expect(result).not.toContain('onerror');

    const doc = parseFragment(result);
    const img = doc.querySelector('img');
    expect(img).not.toBeNull();
    expect(img?.getAttribute('onerror')).toBeNull();
  });

  it('<script> 整体剥除，脚本文本不保留', () => {
    const result = sanitizeMarkdownHtml('<p>前</p><script>alert(1)</script><p>后</p>');
    const doc = parseFragment(result);
    expect(doc.querySelector('script')).toBeNull();
    expect(result).not.toContain('alert(1)');
    expect(result).toContain('前');
    expect(result).toContain('后');
  });

  it('脚本协议链接 href 被剥除（原始 HTML 形态）', () => {
    // no-script-url：XSS 用例本身就是脚本 URL 字面量
    // eslint-disable-next-line no-script-url
    const result = sanitizeMarkdownHtml('<a href="javascript:alert(1)">点我</a>');
    const doc = parseFragment(result);
    const anchor = doc.querySelector('a');
    expect(anchor).not.toBeNull();
    const href = anchor?.getAttribute('href') ?? '';
    expect(href.toLowerCase()).not.toContain('javascript');
  });

  it('脚本协议链接不生成 <a> 节点（markdown 链接语法 + markdown-it 渲染管线）', () => {
    // eslint-disable-next-line no-script-url
    const rendered = mdIt.render('[点我](javascript:alert(1))');
    const result = sanitizeMarkdownHtml(rendered);
    const doc = parseFragment(result);
    // markdown-it validateLink 拦截 javascript:，不生成 <a>，链接文本按纯文本保留（与修改前一致）
    expect(doc.querySelector('a')).toBeNull();
    expect(result).toContain('点我');
    // 即使 href 以其他混淆形态漏过 markdown-it，DOMPurify 也剥除危险 URI
    // eslint-disable-next-line no-script-url
    expect(sanitizeMarkdownHtml('<a href="JaVaScRiPt:alert(1)">x</a>').toLowerCase()).not.toContain('javascript:');
  });

  it('<iframe> 整体剥除', () => {
    const result = sanitizeMarkdownHtml('<iframe src="https://evil.example.com"></iframe>');
    const doc = parseFragment(result);
    expect(doc.querySelector('iframe')).toBeNull();
    expect(result).not.toContain('evil.example.com');
  });

  it('事件属性变体（大小写/换行混淆）同样被剥除', () => {
    const result = sanitizeMarkdownHtml('<img src=x OnErRoR=alert(1)> <div onmouseover="alert(1)">t</div>');
    expect(result.toLowerCase()).not.toContain('onerror');
    expect(result.toLowerCase()).not.toContain('onmouseover');
  });
});

describe('sanitizeMarkdownHtml：正常渲染结果与消毒前一致', () => {
  it('标题/表格/代码块（内含 HTML 字面量）/引用/列表/锚点/图片 逐字节一致', () => {
    const doc = [
      '# 标题一',
      '',
      '| 列A | 列B |',
      '| --- | --- |',
      '| 1 | 2 |',
      '',
      '```html',
      '<img src=x onerror=alert(1)>',
      '```',
      '',
      '> 引用一句名言',
      '',
      '1. 第一项',
      '2. 第二项',
      '',
      '[跳到第二节](#第二节)',
      '',
      '![示意图](https://example.com/a.png)',
      '',
      '## 第二节',
      '',
      '**加粗** 与 *斜体* 与 `行内代码`',
      ''
    ].join('\n');

    const rendered = mdIt.render(doc);
    expect(sanitizeMarkdownHtml(rendered)).toBe(rendered);
  });

  it('data:image 图片 URI 与 markdown-it 口径一致地放行', () => {
    const rendered = mdIt.render('![px](data:image/png;base64,iVBORw0KGgo=)');
    expect(rendered).toContain('src="data:image/png;base64,iVBORw0KGgo="');
    expect(sanitizeMarkdownHtml(rendered)).toBe(rendered);
  });

  it('内部锚点 id 与跳转链接原样保留', () => {
    const html = '<h2 id="setup">安装</h2><p><a href="#setup">返回安装</a></p>';
    expect(sanitizeMarkdownHtml(html)).toBe(html);
  });

  it('shiki 高亮产物（span/style/tabindex）与 vendor 代码组产物（input/label）原样保留', () => {
    const shiki =
      '<pre class="shiki github-dark" style="background-color:#24292e" tabindex="0"><code>' +
      '<span class="line"><span style="color:#e1e4e8">const a = 1</span></span></code></pre>';
    expect(sanitizeMarkdownHtml(shiki)).toBe(shiki);

    const codeGroup =
      '<div class="vp-code-group"><div class="tabs">' +
      '<input type="radio" name="group-1" id="tab-1" checked="checked"><label for="tab-1">ts</label>' +
      '</div><div class="blocks"></div></div>';
    expect(sanitizeMarkdownHtml(codeGroup)).toBe(codeGroup);
  });

  it('引用标注 span（class + data-file-id + title）原样保留，数据流不变', () => {
    const html =
      '来源#1: <span class="source-file-link" data-file-id="source-file-0">报告.pdf</span>' +
      '<span class="citation-suspicious-badge" title="引用校验：该片段可能无法支撑这句话">待核实</span>';
    expect(sanitizeMarkdownHtml(html)).toBe(html);
  });
});

describe('escapeHtml：恶意文件名不再生成标签', () => {
  it('转义全部 HTML 特殊字符', () => {
    expect(escapeHtml('<img src=x onerror=alert(1)>')).toBe('&lt;img src=x onerror=alert(1)&gt;');
    expect(escapeHtml(`a"b'c&d`)).toBe('a&quot;b&#39;c&amp;d');
  });

  it('createSourceLink 形态的拼接经 markdown-it 渲染后不产生标签，文件名以文本显示', () => {
    const maliciousName = '<img src=x onerror=alert(1)>.pdf';
    const link = `来源#1: <span class="source-file-link" data-file-id="source-file-0">${escapeHtml(maliciousName)}</span>`;

    const rendered = sanitizeMarkdownHtml(mdIt.render(link));
    const doc = parseFragment(rendered);
    expect(doc.querySelector('img')).toBeNull();
    expect(doc.querySelector('script')).toBeNull();

    const span = doc.querySelector('span.source-file-link');
    expect(span).not.toBeNull();
    expect(span?.getAttribute('data-file-id')).toBe('source-file-0');
    expect(span?.textContent).toBe(maliciousName);
  });
});

describe('attachMarkdownSanitization：renderer.render 输出消毒（幂等）', () => {
  it('包装后每次 render 都过白名单；重复挂载只包一层', () => {
    const renderer = {
      render: () => '<p>正文</p><script>alert(1)</script>'
    };
    const md = { renderer } as Parameters<typeof attachMarkdownSanitization>[0];

    attachMarkdownSanitization(md);
    const wrapped = md.renderer.render;
    attachMarkdownSanitization(md);
    expect(md.renderer.render).toBe(wrapped);

    const output = md.renderer.render([], {}, {});
    expect(output).not.toContain('script');
    expect(output).toContain('<p>正文</p>');
  });
});
