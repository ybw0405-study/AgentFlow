/**
 * 模型能力标注（前端侧）。
 *
 * 两个来源，按可信度取：
 * 1. 引擎随 `/llm/models` 一起给回来的 `items`（上游 `/models` 明写的字段，或引擎按
 *    模型名推断的结果）；
 * 2. 本地按模型名推断——连接器里的模型清单（`connection.models`）只有 id，没有能力字段，
 *    而「是不是推理型」直接影响 maxTokens 该填多少，值得单独兜一层。
 *
 * 这里只做展示与建议，不参与任何执行决策：真正的模型行为由上游决定，界面标错最多是
 * 提示不准，不会改变下发的请求。
 */

import type { ModelFactsDto } from '@/types/api'

export interface ModelFacts {
  id: string
  tags: string[]
  contextWindow: number | null
  maxOutputTokens: number | null
  source: 'upstream' | 'inferred'
}

/** 推理型模型会先花 token 思考，1024 的默认值很容易只够说半句话。 */
export const REASONING_MAX_TOKENS = 4096

const REASONING_MARKERS = new Set([
  'reasoner',
  'reasoning',
  'thinking',
  'qwq',
  'deepseek-r',
  'o1',
  'o3',
  'o4',
  'r1',
])

const LONG_CONTEXT_MARKERS = ['128k', '200k', '256k', '1m', 'long-context', 'longcontext']

const TAG_LABELS: Record<string, string> = {
  reasoning: '推理型',
  'long-context': '长上下文',
  json: '支持 JSON',
  vision: '图像输入',
}

/** 按非字母数字切词，`gpt-4o-mini` 不会被 `o1` 误判成推理型。 */
function tokens(id: string): string[] {
  return id.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean)
}

export function inferFacts(id: string): ModelFacts {
  const parts = tokens(id)
  const tags: string[] = []
  if (parts.some((part) => REASONING_MARKERS.has(part))) {
    tags.push('reasoning')
  }
  if (LONG_CONTEXT_MARKERS.some((marker) => id.toLowerCase().includes(marker))) {
    tags.push('long-context')
  }
  return {
    id,
    tags,
    contextWindow: null,
    maxOutputTokens: null,
    source: 'inferred',
  }
}

/** 拿到某个模型的能力；引擎给了就用引擎的，没给就本地推断，id 为空返回 null。 */
export function modelFacts(
  id: string | null | undefined,
  items?: ModelFactsDto[] | null,
): ModelFacts | null {
  const name = (id ?? '').trim()
  if (!name) return null
  const known = items?.find((item) => item.id === name)
  if (known) {
    // 引擎只在上游有字段时才标 upstream；推断结果交给本地再补一次，两边规则一致
    const inferred = inferFacts(name)
    return {
      id: name,
      tags: known.tags.length ? known.tags : inferred.tags,
      contextWindow: known.contextWindow ?? null,
      maxOutputTokens: known.maxOutputTokens ?? null,
      source: known.source,
    }
  }
  return inferFacts(name)
}

export function tagLabel(tag: string): string {
  return TAG_LABELS[tag] ?? tag
}

export function isReasoning(facts: ModelFacts | null): boolean {
  return Boolean(facts?.tags.includes('reasoning'))
}

/** 推理型模型建议的 maxTokens；其它模型不给建议（返回 null）。 */
export function maxTokensSuggestion(facts: ModelFacts | null): number | null {
  if (!isReasoning(facts)) return null
  const cap = facts?.maxOutputTokens ?? 0
  return cap > 0 && cap < REASONING_MAX_TOKENS ? cap : REASONING_MAX_TOKENS
}

/** 128000 → `128k`，1048576 → `1M`。 */
export function formatTokens(count: number): string {
  if (count >= 1_000_000) return `${Math.round(count / 100_000) / 10}M`.replace('.0', '')
  if (count >= 1_000) return `${Math.round(count / 1_000)}k`
  return String(count)
}

/** 能力的一句话摘要，例如「128k 上下文 · 输出上限 8k」；没有数字信息则返回空串。 */
export function factsSummary(facts: ModelFacts | null): string {
  if (!facts) return ''
  const parts: string[] = []
  if (facts.contextWindow) parts.push(`${formatTokens(facts.contextWindow)} 上下文`)
  if (facts.maxOutputTokens) parts.push(`输出上限 ${formatTokens(facts.maxOutputTokens)}`)
  return parts.join(' · ')
}
