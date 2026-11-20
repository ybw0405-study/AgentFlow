/**
 * 错误文案层：把后端 problem+json 的机器错误码翻译成用户能读懂的中文。
 *
 * 三条原则：
 * 1. 面向用户只给「发生了什么 + 该怎么做」，不暴露内部码；
 * 2. 内部码与 traceId 保留在第二行，便于support定位，但不干扰阅读；
 * 3. 未收录的码按前缀归类兜底，绝不把原始码当正文显示。
 */

import { ApiError } from './http'

export interface DescribedError {
  /** 面向用户的一句话说明 */
  text: string
  /** 具体处置建议；无建议时为 null */
  hint: string | null
  /** 后端给出的具体原因（problem+json 的 detail），作为第二行补充；没有则为 null */
  detail: string | null
  /** 内部错误码，仅用于排查展示 */
  code: string | null
  traceId: string | null
}

const MESSAGES: Record<string, { text: string; hint?: string }> = {
  // ---- 账号与登录 ----
  'USR-AUTH-001': { text: '邮箱或密码不正确', hint: '请确认账号信息后重试' },
  'USR-TOKEN-001': { text: '登录状态已过期', hint: '请重新登录' },
  'AGT-AUTH-001': { text: '请先登录', hint: '登录后可继续操作' },
  'USR-RATE_LIMIT-001': { text: '尝试次数过多', hint: '请稍候一分钟再试' },

  // ---- 权限与资源 ----
  'WF-FORBIDDEN-001': { text: '当前账号没有此操作权限', hint: '请联系空间管理员开通' },
  'WF-NOT_FOUND-001': { text: '目标不存在或已被删除' },
  'COM-NOT_FOUND-001': { text: '请求的资源不存在' },
  'COM-VALIDATION-001': { text: '提交的内容不符合要求', hint: '请按提示修改后重试' },
  'COM-CONFLICT-001': { text: '数据已被其他人修改', hint: '刷新页面后重新提交' },
  'COM-INTERNAL-001': { text: '服务暂时不可用', hint: '请稍后重试' },
  'AGT-RATE_LIMIT-001': { text: '操作过于频繁', hint: '请稍候再试' },
  'AGT-UPSTREAM-001': { text: '下游服务无响应', hint: '请稍后重试' },
  'AGT-UPSTREAM-003': { text: '下游服务返回异常', hint: '请稍后重试' },

  // ---- 工作流定义 ----
  'DSL-VALIDATION-001': { text: '工作流定义不合法', hint: '请根据校验结果修正后保存' },
  'DSL-CYCLE-001': { text: '流程中存在环', hint: '连线不能形成循环依赖' },
  'DSL-ORPHAN-001': { text: '存在未连线的游离节点', hint: '请为每个节点接上入边与出边' },
  'DSL-BRANCH-001': { text: '分支节点配置不完整', hint: '条件分支需要配置全部出口' },
  'DSL-REF-001': { text: '引用了不存在的节点', hint: '请检查表达式中的节点名' },
  'WF-IMMUTABLE-001': { text: '该记录为只追加数据，不允许修改或删除' },
  'WF-STATE-001': { text: '当前状态不允许此操作', hint: '请刷新后查看最新状态' },
  'WF-POLICY_CLAMPED-001': { text: '超出策略上限，已按上限执行' },

  // ---- 执行 ----
  'EXEC-BUDGET_EXHAUSTED': { text: 'Token 预算已用尽', hint: '可在工作流策略中调高预算' },
  'EXEC-CANCELLED': { text: '执行已取消' },
  'EXEC-DEADLINE_EXCEEDED': { text: '执行超时', hint: '可拆分流程或调高超时时间' },
  'EXEC-DISPATCH-LOST': { text: '执行没有送达引擎', hint: '请重新运行一次' },
  'EXEC-LOOP_LIMIT_EXCEEDED': { text: '循环次数超过上限', hint: '请检查循环终止条件' },
  'EXEC-MAX_STEPS_EXCEEDED': { text: '执行步数超过上限', hint: '请检查是否存在异常循环' },
  'NODE-INTERNAL-001': { text: '执行引擎内部错误', hint: '请稍后重试；持续出现请联系运维' },
  'AUD-APPENDONLY-001': { text: '审计记录不可删除或修改' },

  // ---- 连接器 ----
  'CONN-CONFIG-001': { text: '连接器配置不完整', hint: '请补齐服务地址后再试' },
  'CONN-DISABLED-001': { text: '连接器已停用', hint: '请在连接器页面启用' },
  'CONN-CREDENTIAL-001': { text: '连接器凭据未配置', hint: '请先录入访问凭据' },
  'CONN-CREDENTIAL-002': { text: '连接器凭据无法解密', hint: '可能已轮换主密钥，请重新录入' },
  'CONN-CREDENTIAL-EXPIRED': { text: '连接器凭据已过期', hint: '请重新授权' },
  'CONN-SANDBOX-001': { text: '目标地址不在允许的出网范围', hint: '请将域名加入白名单' },
  'CONN-REDIRECT-001': { text: '外部服务返回重定向，出于安全已阻止' },
  'CONN-HTTP-4XX': { text: '外部服务拒绝了请求', hint: '请检查参数与凭据' },
  'CONN-HTTP-5XX': { text: '外部服务内部错误', hint: '请稍后重试' },
  'CONN-UPSTREAM-001': { text: '外部服务调用失败', hint: '请检查服务地址与网络' },
  'CONN-TIMEOUT': { text: '外部服务响应超时', hint: '请稍后重试或调高超时' },
  'CONN-INFLIGHT': { text: '相同请求正在处理中', hint: '请稍候重试' },
  'CONN-RESPONSE-TOO_LARGE': { text: '外部服务返回内容过大', hint: '请缩小查询范围' },
}

/** 前缀兜底：未收录的码至少给出可读的归类，而不是把码直接甩给用户。 */
const PREFIX_FALLBACK: { prefix: string; text: string }[] = [
  { prefix: 'USR-', text: '账号相关操作未成功' },
  { prefix: 'AGT-', text: '网关处理请求失败' },
  { prefix: 'WF-', text: '工作流操作未成功' },
  { prefix: 'DSL-', text: '工作流定义校验未通过' },
  { prefix: 'EXEC-', text: '执行未成功' },
  { prefix: 'NODE-', text: '节点执行未成功' },
  { prefix: 'CONN-', text: '连接器调用未成功' },
  { prefix: 'AUD-', text: '审计操作未成功' },
  { prefix: 'COM-', text: '请求处理失败' },
]

const STATUS_FALLBACK: Record<number, string> = {
  400: '请求参数有误',
  401: '登录状态已失效',
  403: '没有访问权限',
  404: '请求的内容不存在',
  409: '数据状态冲突，请刷新后重试',
  413: '提交内容过大',
  422: '提交内容未通过校验',
  429: '操作过于频繁',
  500: '服务内部错误',
  502: '服务暂时不可达',
  503: '服务正在维护',
  504: '服务响应超时',
}

function lookup(code: string): { text: string; hint: string | null } {
  const exact = MESSAGES[code]
  if (exact) {
    return { text: exact.text, hint: exact.hint ?? null }
  }
  const byPrefix = PREFIX_FALLBACK.find((f) => code.startsWith(f.prefix))
  return { text: byPrefix?.text ?? '操作未成功', hint: null }
}

/** detail 只做补充，不参与正文；过长的截断，避免把后端整段日志摆到用户面前。 */
const MAX_DETAIL = 300

/**
 * 取后端写的具体原因。只有 problem+json 的 detail 才算数：
 * 它是业务代码显式写给人看的一句话（例如「cron 写法不合法」「触发已停用」），
 * 与正文的固定文案互补；普通异常的 message 一律不透出。
 */
function serverDetail(error: ApiError, text: string): string | null {
  const raw = error.message?.trim()
  if (!raw || raw === text) {
    return null
  }
  // ApiError 在 detail/title 都缺失时用 HTTP 状态码兜底，这种不算业务原因
  if (/^HTTP[\s_-]?\d{3}$/.test(raw)) {
    return null
  }
  return raw.length > MAX_DETAIL ? `${raw.slice(0, MAX_DETAIL)}…` : raw
}

/** 把任意异常归一为可直接展示的文案。 */
export function describeError(error: unknown): DescribedError {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return { text: '网络不可达', hint: '请检查网络连接后重试', detail: null, code: null, traceId: null }
    }
    const { text, hint } = lookup(error.code)
    const traceId = error.traceId ?? null
    // 后端未给业务码时（ApiError 兜底为 HTTP-xxx），按状态码给出更贴近场景的说明
    if (text === '操作未成功') {
      const byStatus = STATUS_FALLBACK[error.status]
      if (byStatus) {
        return {
          text: byStatus,
          hint: null,
          detail: serverDetail(error, byStatus),
          code: error.code,
          traceId,
        }
      }
    }
    return { text, hint, detail: serverDetail(error, text), code: error.code, traceId }
  }
  if (error instanceof Error && error.message === 'Network Error') {
    return { text: '网络不可达', hint: '请检查网络连接后重试', detail: null, code: null, traceId: null }
  }
  return { text: '操作未成功', hint: '请稍后重试', detail: null, code: null, traceId: null }
}

/** 仅取一句话说明（适合放在提示条、toast 里）。 */
export function errorText(error: unknown): string {
  const d = describeError(error)
  return d.hint ? `${d.text}，${d.hint}` : d.text
}

/** 排查用的次要信息：内部码 + traceId，没有则返回空串。 */
export function errorDebugInfo(error: unknown): string {
  const d = describeError(error)
  const parts = [d.code, d.traceId].filter(Boolean)
  return parts.length ? parts.join(' · ') : ''
}
