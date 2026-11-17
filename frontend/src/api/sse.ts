/** 零依赖 SSE 客户端：fetch + 手写帧解析（CRLF 容错、多行 data、注释心跳）。 */

export interface SseFrame {
  id?: string
  event: string
  data: string
}

export interface SseSession {
  close: () => void
}

export interface SseHandlers {
  onFrame: (frame: SseFrame) => void
  onError?: (err: unknown) => void
}

export function openSse(
  url: string,
  options: { headers: Record<string, string> },
  handlers: SseHandlers,
): SseSession {
  const controller = new AbortController()
  void (async () => {
    try {
      const resp = await fetch(url, {
        headers: options.headers,
        credentials: 'include',
        signal: controller.signal,
      })
      if (!resp.ok || !resp.body) {
        handlers.onError?.(new Error(`SSE HTTP ${resp.status}`))
        return
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        for (const frame of drainFrames()) {
          if (frame) handlers.onFrame(frame)
        }
      }
      for (const frame of drainFrames(true)) {
        if (frame) handlers.onFrame(frame)
      }

      function drainFrames(flush = false): (SseFrame | null)[] {
        const frames: (SseFrame | null)[] = []
        let idx: number
        // 帧以空行分隔（\n\n 或 \r\n\r\n）
        while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
          frames.push(parseFrame(buffer.slice(0, idx)))
          buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, '')
        }
        if (flush && buffer.trim()) {
          frames.push(parseFrame(buffer))
          buffer = ''
        }
        return frames
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        handlers.onError?.(err)
      }
    }
  })()
  return { close: () => controller.abort() }
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  let id: string | undefined
  const dataLines: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith(':')) continue // 心跳注释
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
  }
  if (!dataLines.length) return null
  return { id, event, data: dataLines.join('\n') }
}
