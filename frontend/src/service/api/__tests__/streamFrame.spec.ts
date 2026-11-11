import { describe, expect, it, vi } from 'vitest';
import { parseStreamFrame } from '../chat';

// chat.ts 同时导出 HTTP 请求包装（依赖 ../request → 路由/locales 的 @ 别名与浏览器环境），
// 本单测只测 WS 帧纯解析逻辑：传输层打桩，保证无后端、无外部服务可跑。
// vi.mock 会被 vitest 提升到文件顶部，先于 ../chat 的模块求值生效。
vi.mock('../../request', () => ({ request: vi.fn() }));

describe('parseStreamFrame：WS 帧统一解析（task-0008）', () => {
  it('① 合法 start/chunk/completion 帧按 type 原样解析', () => {
    const start = parseStreamFrame(
      JSON.stringify({
        type: 'start',
        generationId: 'g-1',
        conversationId: 'c-1',
        timestamp: 1_700_000_000_000,
        resumed: true,
        content: '重连已生成内容'
      })
    );
    expect(start).toEqual({
      type: 'start',
      generationId: 'g-1',
      conversationId: 'c-1',
      timestamp: 1_700_000_000_000,
      resumed: true,
      content: '重连已生成内容'
    });

    const chunk = parseStreamFrame(
      JSON.stringify({ type: 'chunk', generationId: 'g-1', conversationId: 'c-1', chunk: '你好' })
    );
    expect(chunk).toEqual({ type: 'chunk', generationId: 'g-1', conversationId: 'c-1', chunk: '你好' });

    const completion = parseStreamFrame(
      JSON.stringify({ type: 'completion', generationId: 'g-1', status: 'finished' })
    );
    expect(completion).toEqual({ type: 'completion', generationId: 'g-1', status: 'finished' });
  });

  it('② 未知 type 返回 unknown 帧', () => {
    const raw = JSON.stringify({ type: 'hologram', payload: 1 });
    const frame = parseStreamFrame(raw);

    expect(frame).toEqual({ type: 'unknown', raw });
    expect(frame).not.toEqual(expect.objectContaining({ type: 'hologram' }));
  });

  it('③ 非法 JSON 返回 unknown 且携带原始报文，不抛异常', () => {
    expect(parseStreamFrame('not-json{')).toEqual({ type: 'unknown', raw: 'not-json{' });
    // 心跳等非 JSON 报文与空串同样兜底
    expect(parseStreamFrame('__chat_ping__')).toEqual({ type: 'unknown', raw: '__chat_ping__' });
    expect(parseStreamFrame('')).toEqual({ type: 'unknown', raw: '' });
  });

  it('④ 缺 generationId 的 chunk 不崩溃，仍解析为 chunk 帧', () => {
    expect(() => parseStreamFrame(JSON.stringify({ type: 'chunk', chunk: '片段' }))).not.toThrow();

    const frame = parseStreamFrame(JSON.stringify({ type: 'chunk', chunk: '片段' }));
    expect(frame).toEqual({ type: 'chunk', chunk: '片段' });
  });

  it('⑤ 缺 type 与非对象 JSON 同样兜底为 unknown（不抛异常）', () => {
    const noType = JSON.stringify({ generationId: 'g-1', chunk: 'x' });
    expect(parseStreamFrame(noType)).toEqual({ type: 'unknown', raw: noType });

    expect(parseStreamFrame('123')).toEqual({ type: 'unknown', raw: '123' });
    expect(parseStreamFrame('null')).toEqual({ type: 'unknown', raw: 'null' });
    expect(parseStreamFrame('"str"')).toEqual({ type: 'unknown', raw: '"str"' });
  });
});
