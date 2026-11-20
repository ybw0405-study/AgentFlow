import { describe, expect, it } from 'vitest'
import {
  REASONING_MAX_TOKENS,
  factsSummary,
  formatTokens,
  inferFacts,
  maxTokensSuggestion,
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
    expect(modelFacts('   ')).toBeNull()
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
