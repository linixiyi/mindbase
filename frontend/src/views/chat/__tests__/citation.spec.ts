import { describe, expect, it } from 'vitest';
import { type CitationSourceFile, renderCitationLinks } from '../citation';

/**
 * task-0010：引用链接纯函数回归测试。 被测对象 renderCitationLinks 无任何 Vue/后端/外部服务依赖（escapeHtml 来自 utils/markdown， 仅依赖已装 dompurify
 * 所在模块的纯字符串函数），jsdom 下直接可跑。
 */

function render(text: string, mappings?: Record<string, Api.Chat.ReferenceEvidence>) {
  const sources: CitationSourceFile[] = [];
  const result = renderCitationLinks(text, { sources, mappings });
  return { text: result.text, sourceFiles: result.sourceFiles, sources };
}

describe('renderCitationLinks：引用链接纯函数（task-0010）', () => {
  it('① 普通来源：来源#1: a.pdf 渲染引用胶囊并返回来源列表；复用收集器时列表重置', () => {
    const { text, sourceFiles } = render('根据检索结果：来源#1: a.pdf');

    expect(text).toBe('根据检索结果：来源#1: <span class="source-file-link" data-file-id="source-file-0">a.pdf</span>');
    expect(sourceFiles).toEqual([{ fileName: 'a.pdf', id: 'source-file-0', referenceNumber: 1 }]);

    // 复用同一收集器再次渲染：沿用原 processSourceLinks「重置来源文件列表，避免重复」语义，id 从 0 重新计
    const reused: CitationSourceFile[] = [];
    renderCitationLinks('来源#9: z.pdf', { sources: reused });
    renderCitationLinks('来源#1: a.pdf', { sources: reused });
    expect(reused).toEqual([{ fileName: 'a.pdf', id: 'source-file-0', referenceNumber: 1 }]);
  });

  it('② 文件名含 <img onerror=alert(1)>：不得产生真实标签（简单路径不建胶囊；页码路径建胶囊但整体转义）', () => {
    // a) 简单路径：文件名以 < 开头，简单正则直接不匹配 → 原文透传，不生成任何引用胶囊
    const bare = render('来源#1: <img onerror=alert(1)>');
    expect(bare.text).toBe('来源#1: <img onerror=alert(1)>');
    expect(bare.text).not.toContain('data-file-id');
    expect(bare.sourceFiles).toHaveLength(0);

    // b) 页码路径：页码正则允许 <>，胶囊会生成，但文件名必须整体 HTML 转义，不产出真实 img 标签
    const withPage = render('来源#1: <img onerror=alert(1)> | 第5页');
    expect(withPage.text).toContain('data-file-id="source-file-0"');
    expect(withPage.text).toContain('&lt;img onerror=alert(1)&gt;');
    expect(withPage.text).not.toContain('<img');
    // 点击预览数据流要求原始文件名保留在来源列表中（转义只发生在 HTML 层）
    expect(withPage.sourceFiles[0].fileName).toBe('<img onerror=alert(1)>');
  });

  it('③ 文件名含双引号：不得逃逸 data-file-id 属性，引号必须转义为 &quot;', () => {
    const { text, sourceFiles } = render('来源#1: a" onmouseover="alert(1)');

    // 生成的 data-file-id 属性完好（值由本层生成，不含用户输入）
    expect(text).toContain('data-file-id="source-file-0"');
    // 文件名里的引号全部转义，无法闭合属性或注入新属性；
    // 既有正则行为：entryBoundary 把末尾的 ) 视为条目边界，) 留在胶囊外作普通文本
    expect(text).not.toContain('onmouseover="alert(1');
    expect(text).toContain('a&quot; onmouseover=&quot;alert(1</span>)');
    expect(sourceFiles[0].fileName).toBe('a" onmouseover="alert(1');
  });

  it('④ javascript:alert(1) 链接被中和：引用层只产出 inert span，绝不产生 <a href>', () => {
    // 文件名即攻击载荷：引用胶囊必须是纯 span 文本结构，不带任何链接语义；
    // 既有正则行为：entryBoundary 把末尾的 ) 视为条目边界，) 留在胶囊外作普通文本
    const { text, sourceFiles } = render('参考 来源#1: javascript:alert(1)');
    expect(text).toContain('data-file-id="source-file-0"');
    expect(text).not.toContain('<a');
    expect(text).not.toContain('href=');
    // eslint-disable-next-line no-script-url -- 断言载荷即攻击串本身，字符串必须逐字保留
    expect(sourceFiles[0].fileName).toBe('javascript:alert(1');

    // markdown 链接形态的 javascript: 载荷：引用层原样透传且不新增 href；
    // 真正的中和发生在 markdown 渲染链的 DOMPurify（本卡保持不移除）
    const mdLink = render('[文档](javascript:alert(1)) 来源#2: a.pdf');
    expect(mdLink.text).not.toContain('href="javascript');
    expect(mdLink.text).toContain('data-file-id="source-file-0"');
  });

  it('⑤ 页码变体：| 两侧空格可省略、支持中文标点分隔与连续多个来源，页码写入来源列表', () => {
    const { text, sourceFiles } = render('来源#1: a.pdf | 第5页；来源#2: b.pdf|第12页。来源#3: c.pdf | 第3页');

    expect(text).toContain('来源#1: <span class="source-file-link" data-file-id="source-file-0">a.pdf (第5页)</span>');
    expect(text).toContain('data-file-id="source-file-1">b.pdf (第12页)</span>');
    expect(text).toContain('data-file-id="source-file-2">c.pdf (第3页)</span>');
    expect(sourceFiles).toEqual([
      { fileName: 'a.pdf', id: 'source-file-0', referenceNumber: 1, pageNumber: 5 },
      { fileName: 'b.pdf', id: 'source-file-1', referenceNumber: 2, pageNumber: 12 },
      { fileName: 'c.pdf', id: 'source-file-2', referenceNumber: 3, pageNumber: 3 }
    ]);
  });

  it('⑥ md5 变体：MD5: 后空格可省略、大小写十六进制均可，md5 写入来源列表且不加页码后缀', () => {
    const { text, sourceFiles } = render(
      '来源#1: a.pdf | MD5: d41d8cd98f00b204e9800998ecf8427e；来源#2: b.pdf |MD5:ABCDEF01'
    );

    expect(text).toContain('data-file-id="source-file-0">a.pdf</span>');
    expect(text).toContain('data-file-id="source-file-1">b.pdf</span>');
    expect(sourceFiles).toEqual([
      { fileName: 'a.pdf', id: 'source-file-0', referenceNumber: 1, fileMd5: 'd41d8cd98f00b204e9800998ecf8427e' },
      { fileName: 'b.pdf', id: 'source-file-1', referenceNumber: 2, fileMd5: 'ABCDEF01' }
    ]);
  });

  it('⑦ SUSPICIOUS：来源加 suspicious 类并渲染「待核实」徽标；SUPPORTED 不加；映射入参不被修改', () => {
    const mappings = {
      '1': { fileMd5: 'md5-1', fileName: 'a.pdf', citationStatus: 'SUSPICIOUS' },
      '2': { fileMd5: 'md5-2', fileName: 'b.pdf', citationStatus: 'SUPPORTED' }
    } satisfies Record<string, Api.Chat.ReferenceEvidence>;
    const mappingsSnapshot = structuredClone(mappings);

    const { text, sourceFiles } = render('来源#1: a.pdf；来源#2: b.pdf', mappings);

    expect(text).toContain('class="source-file-link source-file-link--suspicious" data-file-id="source-file-0"');
    expect(text).toContain(
      '<span class="citation-suspicious-badge" title="引用校验：该片段可能无法支撑这句话">待核实</span>'
    );
    // 非可疑来源保持普通胶囊，全篇只有 1 个「待核实」徽标
    expect(text).toContain('class="source-file-link" data-file-id="source-file-1"');
    expect(text.match(/待核实/g)).toHaveLength(1);
    expect(sourceFiles).toEqual([
      { fileName: 'a.pdf', id: 'source-file-0', referenceNumber: 1 },
      { fileName: 'b.pdf', id: 'source-file-1', referenceNumber: 2 }
    ]);
    // 映射只读：与渲染前快照一致（未新增/改写任何键）
    expect(mappings).toEqual(mappingsSnapshot);
  });
});
