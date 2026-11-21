import { beforeEach, describe, expect, it, vi } from 'vitest'

const axiosMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
  request: vi.fn(),
}))

vi.mock('axios', () => ({
  default: {
    create: () => ({
      interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
      ...axiosMock,
    }),
  },
}))

import { del, get, patch, post, put } from '@/api/http'

beforeEach(() => {
  vi.clearAllMocks()
  for (const fn of Object.values(axiosMock)) {
    fn.mockResolvedValue({ data: { data: 'ok' } })
  }
})

describe('http 封装的 AbortSignal 透传', () => {
  it('五个封装都把可选末位 signal 传进 axios config', async () => {
    const signal = new AbortController().signal

    await get('/x', { a: 1 }, signal)
    await post('/x', { a: 1 }, signal)
    await put('/x', { a: 1 }, signal)
    await patch('/x', { a: 1 }, signal)
    await del('/x', signal)

    expect(axiosMock.get.mock.calls[0][1].signal).toBe(signal)
    expect(axiosMock.post.mock.calls[0][2].signal).toBe(signal)
    expect(axiosMock.put.mock.calls[0][2].signal).toBe(signal)
    expect(axiosMock.patch.mock.calls[0][2].signal).toBe(signal)
    expect(axiosMock.delete.mock.calls[0][1].signal).toBe(signal)
  })

  it('省略 signal 时现有调用点行为不变（config 里 signal 为 undefined）', async () => {
    await get('/x', { a: 1 })
    await post('/x', { a: 1 })
    await del('/x')

    expect(axiosMock.get.mock.calls[0][1].signal).toBeUndefined()
    expect(axiosMock.post.mock.calls[0][2].signal).toBeUndefined()
    expect(axiosMock.delete.mock.calls[0][1].signal).toBeUndefined()
    // params / body 仍按原顺序传递
    expect(axiosMock.get.mock.calls[0][1].params).toEqual({ a: 1 })
    expect(axiosMock.post.mock.calls[0][1]).toEqual({ a: 1 })
  })
})
