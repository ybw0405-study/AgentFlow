/** 模型连接的建立与校验。
 *
 * 复用现有连接器 API（create → authorize → probeModels → update），不新增后端接口，
 * 也不改变「端点与密钥只进服务端、画布只持 connectionId」的安全边界。
 * 探测是真实出网：验证网络可达、沙箱放行、凭据正确、端点符合 OpenAI 兼容格式。
 * 任何一步失败都会删掉刚建的临时连接，避免列表里留下连不上的脏记录。
 */

import { connectorApi } from '@/api'
import { errorText } from '@/api/errors'
import type { ProbeModelItem } from '@/types/api'

/** 从 Base URL 推导允许域名，省掉一次手填；用户仍可改成多个。 */
export function hostOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).hostname
  } catch {
    return ''
  }
}

/** 合并手填清单与探测结果，去重且保持「先填的优先」顺序。 */
export function mergeModels(...groups: string[][]): string[] {
  const seen = new Set<string>()
  for (const group of groups) {
    for (const m of group) {
      const trimmed = m.trim()
      if (trimmed) seen.add(trimmed)
    }
  }
  return [...seen]
}

/** LLM 连接的标准 config。与连接器页保持完全一致，避免两条入口行为漂移。 */
export function buildLlmConfig(input: {
  baseUrl: string
  allowedHosts?: string[]
  models?: string[]
  /** 探测到的能力字段；手填清单时没有，缺省不写进 config。 */
  modelItems?: ProbeModelItem[]
  defaultModel?: string
}): Record<string, unknown> {
  const models = (input.models ?? []).filter(Boolean)
  const modelItems = input.modelItems ?? []
  const defaultModel = input.defaultModel?.trim() ?? ''
  const hosts = (input.allowedHosts ?? []).map((h) => h.trim()).filter(Boolean)
  return {
    baseUrl: input.baseUrl.trim(),
    allowedHosts: hosts.length ? hosts : [hostOf(input.baseUrl)].filter(Boolean),
    ...(models.length ? { models } : {}),
    ...(modelItems.length ? { modelItems } : {}),
    ...(defaultModel ? { defaultModel } : {}),
    // OpenAI 兼容端点的凭据形态固定为 Authorization: Bearer <key>
    auth: { headerName: 'Authorization', valuePrefix: 'Bearer ' },
    operations: {
      chat: { method: 'POST', path: '/chat/completions' },
      models: { method: 'GET', path: '/models' },
    },
  }
}

/**
 * 用一次新的探测结果覆盖已有 config 里的能力字段。
 *
 * 整份替换而不是合并：上游这轮没给的字段就是「没有」，留着上一轮的旧数字会让界面
 * 长期显示一个早已不成立的上限。探测没回任何能力字段时把整段删掉。
 */
export function withProbeItems<T extends object>(
  config: T,
  items: ProbeModelItem[],
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(config as Record<string, unknown>) }
  if (items.length) {
    next.modelItems = items
  } else {
    delete next.modelItems
  }
  return next
}

/** 探测失败码翻成人话：这些错误多半是填错了，不该只甩一个内部编码。 */
export function probeErrorMessage(code: string | null): string {
  switch (code) {
    case 'CONN-HTTP-4XX':
      return '端点拒绝了请求，检查 Base URL 路径与 API Key'
    case 'CONN-HTTP-5XX':
      return '端点暂时不可用，稍后重试'
    case 'CONN-CREDENTIAL-001':
      return '还没有保存 API Key'
    case 'CONN-SANDBOX-001':
      return '出网被沙箱拦截，检查允许域名与服务端放行开关'
    case 'CONN-TIMEOUT':
      return '请求超时，检查网络或换一个端点'
    case 'CONN-RESPONSE-001':
      return '端点不像 OpenAI 兼容服务（/models 没有返回模型列表）'
    case 'CONN-MCP-001':
      return '端点不像 MCP 服务（协议握手失败），检查 Base URL 是否指向 MCP 端点'
    case 'CONN-MCP-002':
      return 'MCP 服务端拒绝了请求，检查工具名与入参'
    case 'CONN-TOOL-001':
      return '工具执行返回错误，查看工作流节点输出里的 content'
    default:
      return code ? `连接失败：${code}` : '连接失败'
  }
}

export interface ConnectModelInput {
  /** 连接显示名，也会出现在「模型来源」下拉里。 */
  name: string
  baseUrl: string
  allowedHosts: string[]
  /** 预填模型清单；探测结果会并进来。 */
  models: string[]
  /** 节点实际下发的模型名。 */
  model: string
  /** 明文 Key，只在本次请求里出现，随后立即丢弃。 */
  secret: string
  /** 本地模型无需 Key（Ollama 等）。 */
  noKeyAllowed?: boolean
  /** 部分端点不提供 GET /models，勾选后按预填模型名保存。 */
  allowWithoutProbe: boolean
}

export interface ConnectModelResult {
  ok: boolean
  connectorId: string
  name: string
  model: string
  models: string[]
  /** 探测未通过但按降级策略保留时的提示。 */
  warning?: string
  errorCode?: string | null
  message?: string
}

/** 校验向导输入，尽量在出网前把明显的填错拦下来。 */
export function validateConnectInput(input: ConnectModelInput): string | null {
  if (!input.name.trim()) return '请填写连接名称'
  if (!input.model.trim()) return '请填写模型名'
  let parsed: URL
  try {
    parsed = new URL(input.baseUrl.trim())
  } catch {
    return 'Base URL 不是合法的 URL'
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) return 'Base URL 只支持 http/https'
  if (!parsed.hostname) return 'Base URL 缺少主机名'
  if (!input.secret.trim() && !input.noKeyAllowed) return '请填写 API Key'
  return null
}

/**
 * 建立连接并做真实连通性校验。成功后返回 connectionId 与模型名，调用方直接写回节点。
 * 失败时自动清理刚创建的连接，返回可操作的错误文案。
 */
export async function connectModel(input: ConnectModelInput): Promise<ConnectModelResult> {
  const base: ConnectModelResult = {
    ok: false,
    connectorId: '',
    name: input.name.trim(),
    model: input.model.trim(),
    models: input.models,
  }
  const problem = validateConnectInput(input)
  if (problem) return { ...base, message: problem }

  const secret = input.secret.trim()
  let createdId = ''
  try {
    const created = await connectorApi.create({
      name: input.name.trim(),
      type: 'LLM',
      authType: secret ? 'API_KEY' : 'NONE',
      config: buildLlmConfig({
        baseUrl: input.baseUrl,
        allowedHosts: input.allowedHosts,
        models: input.models,
        defaultModel: input.model,
      }),
    })
    createdId = created.id
    if (secret) await connectorApi.authorize(createdId, secret)

    // 服务端解密凭据后出网，前端只拿模型 id；同时验证端点、密钥与兼容格式。
    // 本地服务没有 Key，无法走带凭据的探测，此时不算失败，只提示用户运行工作流时确认。
    const probeAttempted = Boolean(secret)
    const probe = probeAttempted
      ? await connectorApi.probeModels(createdId)
      : { ok: false, models: [] as string[], items: [] as ProbeModelItem[], errorCode: null }

    if (probeAttempted && !probe.ok && !input.allowWithoutProbe) {
      await safeRemove(createdId)
      return {
        ...base,
        errorCode: probe.errorCode,
        message: probeErrorMessage(probe.errorCode),
      }
    }

    const models = mergeModels(input.models, probe.models)
    await connectorApi.update(createdId, {
      config: buildLlmConfig({
        baseUrl: input.baseUrl,
        allowedHosts: input.allowedHosts,
        models,
        modelItems: probe.items,
        defaultModel: input.model,
      }),
    })

    return {
      ok: true,
      connectorId: createdId,
      name: input.name.trim(),
      model: input.model.trim(),
      models,
      ...(probeAttempted
        ? probe.ok
          ? {}
          : {
              warning: `端点未提供模型清单（${probeErrorMessage(probe.errorCode)}），已按预填模型名保存，请运行工作流确认`,
            }
        : { warning: '未使用 API Key，跳过自动探测，请运行工作流确认' }),
    }
  } catch (e) {
    await safeRemove(createdId)
    return { ...base, message: errorText(e) }
  }
}

async function safeRemove(id: string): Promise<void> {
  if (!id) return
  try {
    await connectorApi.remove(id)
  } catch {
    // 清理失败不影响把真实错误告知用户；残留连接可在「连接器」页删除
  }
}
