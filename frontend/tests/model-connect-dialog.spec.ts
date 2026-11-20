import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ModelConnectDialog from '@/components/canvas/ModelConnectDialog.vue'
import { MODEL_PRESETS } from '@/dsl/model-presets'
import type { ModelPreset } from '@/dsl/model-presets'
import type { ConnectModelResult } from '@/dsl/model-connection'
import type * as ConnectionModule from '@/dsl/model-connection'

const mocks = vi.hoisted(() => ({ connectModel: vi.fn() }))

vi.mock('@/dsl/model-connection', async (importOriginal) => {
  const actual = await importOriginal<typeof ConnectionModule>()
  return { ...actual, connectModel: mocks.connectModel }
})

const hy4 = MODEL_PRESETS.find((p) => p.id === 'hunyuan-hy4')!

function mountDialog(preset: ModelPreset | null = hy4) {
  return mount(ModelConnectDialog, { props: { preset, currentModel: '内置演示模型 · 离线可跑' } })
}

function submit(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('button').find((b) => b.text().includes('测试并连接'))!
}

function okResult(): ConnectModelResult {
  return {
    ok: true,
    connectorId: 'c-new',
    name: '混元 Hy4 连接',
    model: 'hy4',
    models: ['hy4'],
  }
}

describe('模型连接向导', () => {
  beforeEach(() => mocks.connectModel.mockReset())

  it('按预设预填端点、模型名与连接名，并给出申请 Key 的入口', () => {
    const wrapper = mountDialog()
    const inputs = wrapper.findAll('input')
    expect((inputs[0].element as HTMLInputElement).value).toBe('混元 Hy4 连接')
    expect((inputs[1].element as HTMLInputElement).value).toBe('https://api.hunyuan.cloud.tencent.com/v1')
    expect((inputs[2].element as HTMLInputElement).value).toBe('hy4')
    expect(wrapper.text()).toContain('去申请 Key')
    expect(wrapper.text()).toContain('AES-256-GCM')
  })

  it('没填 Key 时先拦下，不发起无效请求', async () => {
    const wrapper = mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请填写 API Key')
    expect(mocks.connectModel).not.toHaveBeenCalled()
  })

  it('验证通过即抛出 connected，父级可直接写回节点', async () => {
    mocks.connectModel.mockResolvedValue(okResult())
    const wrapper = mountDialog()
    await wrapper.findAll('input')[3].setValue('sk-live-key')
    await submit(wrapper).trigger('click')
    await flushPromises()

    expect(mocks.connectModel).toHaveBeenCalledTimes(1)
    expect(mocks.connectModel.mock.calls[0][0]).toMatchObject({
      model: 'hy4',
      secret: 'sk-live-key',
      baseUrl: 'https://api.hunyuan.cloud.tencent.com/v1',
      allowedHosts: ['api.hunyuan.cloud.tencent.com'],
    })
    expect(wrapper.emitted('connected')?.[0][0]).toMatchObject({ ok: true, connectorId: 'c-new' })
  })

  it('连接失败时展示人话原因并留在向导里可修改', async () => {
    mocks.connectModel.mockResolvedValue({
      ok: false,
      connectorId: '',
      name: '',
      model: 'hy4',
      models: [],
      message: '端点拒绝了请求，检查 Base URL 路径与 API Key',
    })
    const wrapper = mountDialog()
    await wrapper.findAll('input')[3].setValue('wrong-key')
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('没能连上')
    expect(wrapper.text()).toContain('检查 Base URL 路径与 API Key')
    expect(wrapper.emitted('connected')).toBeUndefined()
  })

  it('自定义端点不预填任何值，标题与说明对应', () => {
    const wrapper = mountDialog(null)
    expect(wrapper.text()).toContain('自定义 OpenAI 兼容端点')
    expect((wrapper.findAll('input')[1].element as HTMLInputElement).value).toBe('')
    expect((wrapper.findAll('input')[2].element as HTMLInputElement).value).toBe('')
  })

  it('本地预设明确提示需要服务端放行开关', () => {
    const ollama = MODEL_PRESETS.find((p) => p.id === 'ollama')!
    const wrapper = mountDialog(ollama)
    expect(wrapper.text()).toContain('AF_SANDBOX_ALLOW_LOOPBACK')
    expect(wrapper.text()).toContain('AF_SANDBOX_ALLOW_HTTP')
  })

  it('高级选项可展开，允许域名 / 模型清单 / 兼容开关都在', async () => {
    const wrapper = mountDialog()
    await wrapper.find('.af-connect__advanced').trigger('click')
    expect(wrapper.text()).toContain('允许域名')
    expect(wrapper.text()).toContain('候选模型清单')
    expect(wrapper.text()).toContain('端点不提供 GET /models')
  })
})
