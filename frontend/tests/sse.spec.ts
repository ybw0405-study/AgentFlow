import { afterEach, describe, expect, it, vi } from 'vitest'
import { openSse, MAX_BUFFER_CHARS, MAX_DATA_LINES } from '@/api/sse'
import type { SseFrame } from '@/api/sse'

const encoder = new TextEncoder()

/** 构造可逐块读取的 SSE 响应，并记录 reader.cancel() 是否被调用。 */
function sseBody(chunks: string[], contentType = 'text/event-stream') {
  const cancel = vi.fn(async () => {})
  const encoded = chunks.map((c) => encoder.encode(c))
  let i = 0
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    headers: {
      get: (name: string) => (name.toLowerCase() === 'content-type' ? contentType : null),
    },
    body: {
      getReader: () => ({
        read: async () =>
          i < encoded.length
            ? { done: false, value: encoded[i++] }
            : { done: true, value: undefined },
        cancel,
      }),
    },
  }))
  return cancel
}

/** 收下 onError 的第一个错误并结束等待。 */
function collectError(): Promise<unknown> {
  return new Promise<unknown>((resolve) => {
    openSse('/x', { headers: {} }, { onFrame: () => {}, onError: resolve })
  })
}

describe('sse client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('parses frames and skips heartbeat comments', async () => {
    sseBody([
      'event: engine-event\ndata: {"seq":1}\n\n' +
      ': ka\n\n' +
      'id: 2\nevent: engine-event\ndata: {"seq":2}\ndata: {"more":true}\n\n',
    ])
    const frames: SseFrame[] = []
    await new Promise<void>((resolve) => {
      openSse('/x', { headers: {} }, {
        onFrame: (f) => frames.push(f),
        onError: () => resolve(),
      })
      setTimeout(resolve, 50)
    })
    expect(frames).toHaveLength(2)
    expect(frames[0]).toEqual({ id: undefined, event: 'engine-event', data: '{"seq":1}' })
    expect(frames[1].id).toBe('2')
    expect(frames[1].data).toBe('{"seq":2}\n{"more":true}')
  })

  it('accepts text/event-stream with a charset parameter', async () => {
    sseBody(['event: engine-event\ndata: ok\n\n'], 'text/event-stream; charset=utf-8')
    const frames: SseFrame[] = []
    await new Promise<void>((resolve) => {
      openSse('/x', { headers: {} }, {
        onFrame: (f) => { frames.push(f); resolve() },
        onError: () => resolve(),
      })
      setTimeout(resolve, 50)
    })
    expect(frames).toEqual([{ id: undefined, event: 'engine-event', data: 'ok' }])
  })

  it('reports http errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, body: null }))
    const err = await new Promise<unknown>((resolve) => {
      openSse('/x', { headers: {} }, { onFrame: () => {}, onError: resolve })
    })
    expect(String(err)).toContain('401')
  })

  it('rejects a non event-stream Content-Type and cancels the reader', async () => {
    const cancel = sseBody(['data: x\n\n'], 'text/html')
    const err = await collectError()
    expect(String(err)).toContain('text/event-stream')
    expect(String(err)).toContain('text/html')
    expect(cancel).toHaveBeenCalled()
  })

  it('rejects a missing Content-Type instead of parsing an HTML page', async () => {
    const cancel = sseBody(['<html>login</html>\n'], '')
    const err = await collectError()
    expect(String(err)).toContain('text/event-stream')
    expect(cancel).toHaveBeenCalled()
  })

  it('aborts when the buffer exceeds the hard cap (no-newline stream)', async () => {
    const cancel = sseBody(['x'.repeat(MAX_BUFFER_CHARS + 1)])
    const err = await collectError()
    expect(String(err)).toContain('字符上限')
    expect(cancel).toHaveBeenCalled()
  })

  it('aborts when a single frame has too many data lines', async () => {
    const lines = Array.from({ length: MAX_DATA_LINES + 1 }, () => 'data: x').join('\n')
    const cancel = sseBody([`${lines}\n\n`])
    const err = await collectError()
    expect(String(err)).toContain('data 行')
    expect(cancel).toHaveBeenCalled()
  })
})
