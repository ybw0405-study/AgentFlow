import { describe, expect, it } from 'vitest'
import {
  REASONING_MAX_TOKENS,
  factsFromProbe,
  factsSummary,
  formatTokens,
  inferFacts,
  maxTokensSuggestion,
  mergeFacts,
  modelFacts,
  tagLabel,
} from '@/dsl/model-capabilities'

describe('模型能力标注', () => {
  it('按模型名识别推理型，普通对话模型不误判', () => {
    expect(inferFacts('deepseek-reasoner').tags).toContain('reasoning')
    expect(inferFacts('deepseek-r1-0528').tags).toContain('reasoning')
    expect(inferFacts('o3-mini').tags).toContain('reasoning')
    expect(inferFacts('gpt-4o-mini').tags).not.toContain('reasoning')
    expect(inferFacts('deepseek-chat').tags).not.toContain('reasoning')
  })

  it('DeepSeek 的 flash / v4 变体按家族名 + 变体名识别，别家 v4 不误判', () => {
    expect(inferFacts('deepseek-flash').tags).toContain('reasoning')
    expect(inferFacts('deepseek-v4-pro').tags).toContain('reasoning')
    expect(inferFacts('glm-v4-pro').tags).not.toContain('reasoning')
    expect(inferFacts('acme-v4').tags).not.toContain('reasoning')
    expect(maxTokensSuggestion(inferFacts('deepseek-flash'))).toBe(REASONING_MAX_TOKENS)
  })

  it('引擎给的能力优先，缺字段时回落到模型名推断', () => {
    const facts = modelFacts('deepseek-reasoner', [
      {
        id: 'deepseek-reasoner',
        tags: [],
        contextWindow: 128_000,
        maxOutputTokens: 8_192,
        source: 'upstream',
      },
    ])
    expect(facts).not.toBeNull()
    expect(facts!.contextWindow).toBe(128_000)
    expect(facts!.tags).toContain('reasoning')
    expect(facts!.source).toBe('upstream')
  })

  it('没有引擎数据时按模型名推断，并标明是推断', () => {
    const facts = modelFacts('qwq-32b')
    expect(facts!.source).toBe('inferred')
    expect(facts!.tags).toContain('reasoning')
  })

  it('模型名为空时返回 null，界面不出空标签', () => {
    expect(modelFacts('')).toBeNull()
    expect(modelFacts(undefined)).toBeNull()
    expect(modelFacts(' ')).toBeNull()
  })

  it('推理型给出 maxTokens 建议，普通模型不给', () => {
    expect(maxTokensSuggestion(inferFacts('deepseek-reasoner'))).toBe(REASONING_MAX_TOKENS)
    expect(maxTokensSuggestion(inferFacts('deepseek-chat'))).toBeNull()
  })

  it('上游输出上限比建议值小的时候按上限给', () => {
    const facts = modelFacts('o3-mini', [
      { id: 'o3-mini', tags: ['reasoning'], contextWindow: null, maxOutputTokens: 2048, source: 'upstream' },
    ])
    expect(maxTokensSuggestion(facts)).toBe(2048)
  })

  it('标签与数字都翻成人话', () => {
    expect(tagLabel('reasoning')).toBe('推理型')
    expect(tagLabel('long-context')).toBe('长上下文')
    expect(tagLabel('something-new')).toBe('something-new')
    expect(formatTokens(128_000)).toBe('128k')
    expect(formatTokens(1_048_576)).toBe('1M')
    expect(factsSummary(modelFacts('m', [
      { id: 'm', tags: [], contextWindow: 128_000, maxOutputTokens: 8_192, source: 'upstream' },
    ]))).toBe('128k 上下文 · 输出上限 8k')
  })
})

describe('连接器探测回来的能力字段', () => {
  it('上游字段直接变成标签与数字，标 upstream', () => {
    const facts = factsFromProbe({
      id: 'some-gateway-model',
      contextWindow: 1_000_000,
      maxOutputTokens: 393_216,
      inputModalities: ['text', 'image'],
      supportedParameters: ['reasoning', 'response_format'],
      reasoningEffort: true,
    })
    expect(facts.tags).toEqual(['reasoning', 'json', 'vision', 'long-context'])
    expect(facts.contextWindow).toBe(1_000_000)
    expect(facts.maxOutputTokens).toBe(393_216)
    expect(facts.source).toBe('upstream')
  })

  it('只有思考强度开关也算推理型（不按名字猜）', () => {
    const facts = factsFromProbe({ id: 'opaque-1', reasoningEffort: true })
    expect(facts.tags).toContain('reasoning')
    expect(facts.source).toBe('upstream')
  })

  it('上游一个字段都没给时退回按名字推断，并标 inferred', () => {
    const facts = factsFromProbe({ id: 'deepseek-reasoner' })
    expect(facts.tags).toContain('reasoning')
    expect(facts.contextWindow).toBeNull()
    expect(facts.source).toBe('inferred')
  })

  it('0 / 负数当成没给，不显示「上下文 0」', () => {
    const facts = factsFromProbe({ id: 'm', contextWindow: 0, maxOutputTokens: -1 })
    expect(facts.contextWindow).toBeNull()
    expect(facts.maxOutputTokens).toBeNull()
    expect(facts.source).toBe('inferred')
  })

  it('连接的结果优先，引擎清单只补连接里没有的模型', () => {
    const merged = mergeFacts(
      [{ id: 'shared', contextWindow: 200_000, maxOutputTokens: 4_096 }],
      [
        { id: 'shared', tags: [], contextWindow: 32_000, maxOutputTokens: 1_024, source: 'upstream' },
        { id: 'engine-only', tags: ['json'], contextWindow: 8_192, maxOutputTokens: null, source: 'upstream' },
      ],
    )
    expect(merged).toHaveLength(2)
    expect(merged[0].contextWindow).toBe(200_000)
    expect(merged[1].id).toBe('engine-only')
  })

  it('两个来源都为空时不报错', () => {
    expect(mergeFacts(undefined, undefined)).toEqual([])
    expect(mergeFacts([], [])).toEqual([])
  })
})
