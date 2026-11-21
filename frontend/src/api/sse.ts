/** 零依赖 SSE 客户端：fetch + 手写帧解析（CRLF 容错、多行 data、注释心跳）。 */

/** 未消费帧缓冲上限（字符，ASCII 下约 1 MiB）。无空行的畸形流会让 buffer 只增不减，必须封顶。 */
export const MAX_BUFFER_CHARS = 1024 * 1024
/** 单帧 data 行上限，防止单帧内超长 data 列表吃满内存。 */
export const MAX_DATA_LINES = 10_000

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
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null
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
      reader = resp.body.getReader()
      // 200 + text/html（登录页 / 网关错误页）绝不能当帧流解析：否则整页 HTML 会被
      // 塞进 buffer 逐行扫描，长时间悬挂的「无换行流」还会把标签页内存吃满。
      const contentType = resp.headers?.get('content-type') ?? ''
      if (!contentType.toLowerCase().includes('text/event-stream')) {
        throw new Error(`SSE 响应 Content-Type 应为 text/event-stream，实际为：${contentType || '(缺失)'}`)
      }
      const decoder = new TextDecoder()
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // drainFrames 只按空行消耗 buffer；没有空行时它永远不缩小，必须在解析前封顶。
        if (buffer.length > MAX_BUFFER_CHARS) {
          throw new Error(`SSE 帧缓冲超过 ${MAX_BUFFER_CHARS} 字符上限，疑似无换行流`)
        }
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
      // 统一在这里取消读取：Content-Type 不符、缓冲/单帧超限都会 throw 到此，
      // 避免底层连接继续挂着；主动 close() 触发的 AbortError 也会被吞掉，不误报 onError。
      if (reader) void reader.cancel().catch(() => {})
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
    else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
      if (dataLines.length > MAX_DATA_LINES) {
        throw new Error(`SSE 单帧 data 行超过 ${MAX_DATA_LINES} 行上限`)
      }
    }
  }
  if (!dataLines.length) return null
  return { id, event, data: dataLines.join('\n') }
}
