/** axios 实例：Bearer + X-Space-Id 注入、401 刷新重放一次、ProblemDetail 解包。 */

import axios from 'axios'
import type { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import type { ProblemDetail } from '@/types/api'

export interface SessionHooks {
  getAccessToken: () => string | null
  getSpaceId: () => string | null
  refresh: () => Promise<boolean>
  onUnauthorized: () => void
}

let hooks: SessionHooks | null = null

export function setSessionHooks(h: SessionHooks): void {
  hooks = h
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | undefined
  readonly fieldErrors: { field: string; message: string }[]

  constructor(pd: ProblemDetail) {
    super(pd.detail ?? pd.title ?? `HTTP ${pd.status}`)
    this.status = pd.status
    this.code = pd.code ?? `HTTP-${pd.status}`
    this.traceId = pd.traceId
    this.fieldErrors = pd.errors ?? []
  }
}

export const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 20_000,
  withCredentials: true,
})

const REFRESH_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout']

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = hooks?.getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  const spaceId = hooks?.getSpaceId()
  if (spaceId) {
    config.headers.set('X-Space-Id', spaceId)
  }
  return config
})

let refreshing: Promise<boolean> | null = null

http.interceptors.response.use(
  (resp) => resp,
  async (error: AxiosError) => {
    const config = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    const isAuthPath = config?.url && REFRESH_PATHS.some((p) => config.url?.endsWith(p))
    if (error.response?.status === 401 && config && !config._retried && !isAuthPath) {
      config._retried = true
      refreshing ??= (hooks?.refresh() ?? Promise.resolve(false)).finally(() => {
        refreshing = null
      })
      const ok = await refreshing
      if (ok) {
        return http.request(config)
      }
      hooks?.onUnauthorized()
    }
    if (error.response?.data && typeof error.response.data === 'object'
        && 'status' in error.response.data) {
      return Promise.reject(new ApiError(error.response.data as ProblemDetail))
    }
    return Promise.reject(error)
  },
)

/** 五个封装都透传可选 `AbortSignal`：调用方（如执行详情卸载）可取消在途 REST，
 *  省略时行为与原来完全一致，现有调用点不需要改动。 */

export async function get<T>(url: string, params?: Record<string, unknown>, signal?: AbortSignal): Promise<T> {
  const resp = await http.get<{ data: T }>(url, { params, signal })
  return resp.data.data
}

export async function post<T>(url: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const resp = await http.post<{ data: T }>(url, body, { signal })
  return resp.data.data
}

export async function put<T>(url: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const resp = await http.put<{ data: T }>(url, body, { signal })
  return resp.data.data
}

export async function patch<T>(url: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const resp = await http.patch<{ data: T }>(url, body, { signal })
  return resp.data.data
}

export async function del<T>(url: string, signal?: AbortSignal): Promise<T> {
  const resp = await http.delete<{ data: T }>(url, { signal })
  return resp.data.data
}
