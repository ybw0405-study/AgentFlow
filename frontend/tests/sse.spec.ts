import { describe, expect, it, vi } from 'vitest'
import { openSse } from '@/api/sse'
import type { SseFrame } from '@/api/sse'

function streamOf(text: string): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(text))
      controller.close()
    },
  })
}

describe('sse client', () => {
  it('parses frames and skips heartbeat comments', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: streamOf(
        'event: engine-event\ndata: {"seq":1}\n\n' +
        ': ka\n\n' +
        'id: 2\nevent: engine-event\ndata: {"seq":2}\ndata: {"more":true}\n\n',
      ),
    }))
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

  it('reports http errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, body: null }))
    const err = await new Promise<unknown>((resolve) => {
      openSse('/x', { headers: {} }, { onFrame: () => {}, onError: resolve })
    })
    expect(String(err)).toContain('401')
  })
})
