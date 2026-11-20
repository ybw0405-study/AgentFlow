import { describe, expect, it } from 'vitest'
import {
  MODEL_PRESETS,
  MODEL_PRESET_GROUPS,
  displayModelName,
  findPresetByModel,
  isLegacyOfflineModel,
  searchModelPresets,
} from '@/dsl/model-presets'

describe('常见模型预设清单', () => {
  it('覆盖主流服务，且每条都带可用端点与模型别名', () => {
    expect(MODEL_PRESETS.length).toBeGreaterThanOrEqual(10)
    const ids = new Set<string>()
    for (const p of MODEL_PRESETS) {
      expect(p.id).toBeTruthy()
      expect(ids.has(p.id)).toBe(false)
      ids.add(p.id)
      expect(p.label).toBeTruthy()
      expect(p.vendor).toBeTruthy()
      expect(p.mark).toBeTruthy()
      expect(p.models.length).toBeGreaterThan(0)
      expect(p.models).toContain(p.model)
      expect(p.baseUrl).toMatch(/^https?:\/\//)
      expect(MODEL_PRESET_GROUPS.some((g) => g.id === p.group)).toBe(true)
    }
  })

  it('混元 Hy4 的端点与模型名与官方 OpenAI 兼容文档一致', () => {
    const hy4 = MODEL_PRESETS.find((p) => p.id === 'hunyuan-hy4')
    expect(hy4?.model).toBe('hy4')
    expect(hy4?.baseUrl).toBe('https://api.hunyuan.cloud.tencent.com/v1')
  })

  it('中文名、英文别名、厂商、端点都能搜到', () => {
    expect(searchModelPresets('hy4').some((p) => p.id === 'hunyuan-hy4')).toBe(true)
    expect(searchModelPresets('DeepSeek').length).toBeGreaterThan(0)
    expect(searchModelPresets('智谱').some((p) => p.id === 'zhipu-glm')).toBe(true)
    expect(searchModelPresets('api.moonshot.cn').some((p) => p.id === 'moonshot-kimi')).toBe(true)
    expect(searchModelPresets('不存在的模型xyz')).toEqual([])
  })

  it('模型名反查展示名', () => {
    expect(findPresetByModel('hy4')?.label).toBe('混元 Hy4')
    expect(findPresetByModel('glm-5.3')?.id).toBe('zhipu-glm')
    expect(findPresetByModel('some-custom-model')).toBeNull()
  })

  it('本地预设明确标注需要服务端放行，且无需 API Key', () => {
    const local = MODEL_PRESETS.filter((p) => p.needsServerOptIn)
    expect(local.length).toBeGreaterThan(0)
    for (const p of local) {
      expect(p.noKey).toBe(true)
      expect(p.note).toContain('AF_SANDBOX_ALLOW_LOOPBACK')
    }
  })
})

describe('卡片上的模型展示', () => {
  it('旧版离线模型给出需要重新选择的提示', () => {
    const cfg = { provider: 'mock', model: 'mock-large' }
    expect(isLegacyOfflineModel(cfg)).toBe(true)
    expect(displayModelName(cfg)).toContain('旧版离线模型')
  })

  it('预设模型显示中文名，自定义模型保留原值', () => {
    expect(displayModelName({ provider: 'openai-compatible', model: 'hy4' })).toBe('混元 Hy4')
    expect(displayModelName({ provider: 'openai-compatible', model: 'my-own-model' }))
      .toBe('my-own-model')
  })

  it('未填模型时给出可操作提示', () => {
    expect(displayModelName({ provider: 'openai-compatible' })).toBe('未选择模型')
  })
})
