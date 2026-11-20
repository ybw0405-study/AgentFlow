import { beforeEach, describe, expect, it, vi } from 'vitest'
import type * as ApiModule from '@/api'
import {
  buildLlmConfig,
  connectModel,
  hostOf,
  mergeModels,
  probeErrorMessage,
  validateConnectInput,
} from '@/dsl/model-connection'
import type { ConnectModelInput } from '@/dsl/model-connection'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  authorize: vi.fn(),
  probeModels: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
}))

vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>()
  return {
    ...actual,
    connectorApi: {
      create: mocks.create,
      authorize: mocks.authorize,
      probeModels: mocks.probeModels,
      update: mocks.update,
      remove: mocks.remove,
    },
  }
})

function input(overrides: Partial<ConnectModelInput> = {}): ConnectModelInput {
  return {
    name: '混元 Hy4 连接',
    baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1',
    allowedHosts: [],
    models: ['hy4'],
    model: 'hy4',
    secret: 'sk-test-key',
    noKeyAllowed: false,
    allowWithoutProbe: false,
    ...overrides,
  }
}

describe('连接配置构造', () => {
  it('允许域名留空时从 Base URL 推导', () => {
    expect(hostOf('https://api.deepseek.com/v1')).toBe('api.deepseek.com')
    expect(hostOf('不是 URL')).toBe('')
    const config = buildLlmConfig({ baseUrl: 'https://api.deepseek.com/v1', models: ['deepseek-chat'] })
    expect(config.allowedHosts).toEqual(['api.deepseek.com'])
  })

  it('config 结构与连接器页完全一致', () => {
    const config = buildLlmConfig({
      baseUrl: 'https://api.deepseek.com/v1',
      allowedHosts: ['api.deepseek.com'],
      models: ['deepseek-chat'],
      defaultModel: 'deepseek-chat',
    })
    expect(config).toEqual({
      baseUrl: 'https://api.deepseek.com/v1',
      allowedHosts: ['api.deepseek.com'],
      models: ['deepseek-chat'],
      defaultModel: 'deepseek-chat',
      auth: { headerName: 'Authorization', valuePrefix: 'Bearer ' },
      operations: {
        chat: { method: 'POST', path: '/chat/completions' },
        models: { method: 'GET', path: '/models' },
      },
    })
  })

  it('合并模型清单去重且保持预填优先', () => {
    expect(mergeModels(['hy4'], ['hy4', 'hy4-flash'])).toEqual(['hy4', 'hy4-flash'])
    expect(mergeModels([], [])).toEqual([])
  })

  it('探测错误码翻成人话', () => {
    expect(probeErrorMessage('CONN-HTTP-4XX')).toContain('检查 Base URL 路径与 API Key')
    expect(probeErrorMessage('CONN-SANDBOX-001')).toContain('沙箱')
    expect(probeErrorMessage(null)).toBeTruthy()
  })
})

describe('向导输入校验', () => {
  it('拦下明显填错的内容', () => {
    expect(validateConnectInput(input({ name: '' }))).toContain('连接名称')
    expect(validateConnectInput(input({ model: '' }))).toContain('模型名')
    expect(validateConnectInput(input({ baseUrl: 'abc' }))).toContain('合法的 URL')
    expect(validateConnectInput(input({ baseUrl: 'ftp://host/v1' }))).toContain('http/https')
    expect(validateConnectInput(input({ secret: '', noKeyAllowed: false }))).toContain('API Key')
    expect(validateConnectInput(input())).toBeNull()
  })
})

describe('建立连接并验证', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((m) => m.mockReset())
    mocks.create.mockResolvedValue({ id: 'c-new', name: '混元 Hy4 连接' })
    mocks.authorize.mockResolvedValue(undefined)
    mocks.update.mockResolvedValue({})
    mocks.remove.mockResolvedValue(undefined)
    mocks.probeModels.mockResolvedValue({ ok: true, models: ['hy4', 'hy4-flash'], errorCode: null })
  })

  it('成功：建 → 存密钥 → 真实探测 → 回写清单，返回可直接写回节点的结果', async () => {
    const result = await connectModel(input())

    expect(mocks.create).toHaveBeenCalledTimes(1)
    const body = mocks.create.mock.calls[0][0]
    expect(body).toMatchObject({ name: '混元 Hy4 连接', type: 'LLM', authType: 'API_KEY' })
    expect(body.config).toMatchObject({
      baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1',
      allowedHosts: ['api.hunyuan.cloud.tencent.com'],
      defaultModel: 'hy4',
      auth: { headerName: 'Authorization', valuePrefix: 'Bearer ' },
      operations: {
        chat: { method: 'POST', path: '/chat/completions' },
        models: { method: 'GET', path: '/models' },
      },
    })
    expect(mocks.authorize).toHaveBeenCalledWith('c-new', 'sk-test-key')
    expect(mocks.probeModels).toHaveBeenCalledWith('c-new')
    expect(mocks.update).toHaveBeenCalledWith('c-new', {
      config: expect.objectContaining({ models: ['hy4', 'hy4-flash'], defaultModel: 'hy4' }),
    })
    expect(result).toMatchObject({ ok: true, connectorId: 'c-new', model: 'hy4' })
    expect(result.warning).toBeUndefined()
    expect(mocks.remove).not.toHaveBeenCalled()
  })

  it('探测失败：自动删掉临时连接，返回可操作的原因，不留下脏记录', async () => {
    mocks.probeModels.mockResolvedValue({ ok: false, models: [], errorCode: 'CONN-HTTP-4XX' })

    const result = await connectModel(input())

    expect(result.ok).toBe(false)
    expect(result.message).toContain('检查 Base URL 路径与 API Key')
    expect(mocks.remove).toHaveBeenCalledWith('c-new')
    expect(mocks.update).not.toHaveBeenCalled()
  })

  it('端点不提供 /models 且用户勾选降级：按预填模型名保存并给出警告', async () => {
    mocks.probeModels.mockResolvedValue({ ok: false, models: [], errorCode: 'CONN-RESPONSE-001' })

    const result = await connectModel(input({ allowWithoutProbe: true }))

    expect(result.ok).toBe(true)
    expect(result.warning).toContain('端点未提供模型清单')
    expect(mocks.remove).not.toHaveBeenCalled()
    expect(mocks.update).toHaveBeenCalledTimes(1)
  })

  it('存密钥失败也要清理，不把半条连接留在列表里', async () => {
    mocks.authorize.mockRejectedValue(new Error('network down'))

    const result = await connectModel(input())

    expect(result.ok).toBe(false)
    expect(mocks.remove).toHaveBeenCalledWith('c-new')
  })

  it('本地无 Key 端点：authType=NONE，跳过探测并提示运行工作流确认', async () => {
    const result = await connectModel(input({ secret: '', noKeyAllowed: true }))

    expect(mocks.create.mock.calls[0][0].authType).toBe('NONE')
    expect(mocks.authorize).not.toHaveBeenCalled()
    expect(mocks.probeModels).not.toHaveBeenCalled()
    expect(result.ok).toBe(true)
    expect(result.warning).toContain('跳过自动探测')
  })
})
