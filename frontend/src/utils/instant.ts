/**
 * 把服务端返回的「真实瞬间」渲染成浏览器本地时间。
 *
 * 后端时间字段带 UTC 偏移（如 `2026-11-20T13:20:49.404+00:00`，MySQL 以 UTC 存），
 * 不是本地墙上时间。早先各处 `slice(0, 19)` 把 `+00:00` 一并截掉，13:20 这个 UTC 值
 * 就被当成本地 13:20 显示——在中国时区整整差 8 小时，而且界面上没有任何时区标注，
 * 读者无从察觉。这里统一解析偏移后按本地时区输出 `YYYY-MM-DD HH:mm:ss`。
 *
 * 只用于瞬间（createdAt / startedAt / finishedAt / publishedAt / timeoutAt 等）。
 * cron 档位与「下次触发」是带自己 timezone 的墙钟，必须原样展示，不走这里。
 */
export function formatInstant(value: string | null | undefined, fallback = '—'): string {
  if (!value) return fallback
  // 后端约定 DATETIME 一律 UTC；没带偏移时补 Z，绝不按本地时区去猜墙上时间。
  const normalized = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value) ? value : `${value}Z`
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}
