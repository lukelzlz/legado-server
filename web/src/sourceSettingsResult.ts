/**
 * 书源自生成页面（书源设置中心 / 段评气泡）的结果回传工具。
 *
 * 页面契约（`html/书源设置.html` 快照分析）：
 * - 设置页把当前 state 写进 `#source-settings-final-result` / `#source-settings-result`（URL 编码 JSON）；
 * - 气泡页把 `{pstyle, _bubble_nonce, _bubble_applied}` 写进 `#bubble-settings-result`；
 * - 代理层在渲染时注入采集脚本，把这些容器 postMessage 给宿主（`legado-webview` / `settings-result`），
 *   宿主校验后交给服务端写入源变量 —— 等价于安卓端 `startBrowserAwait` 的返回体通道。
 */

/** 结果容器 id（必须与 `WebViewProxy.RESULT_CONTAINER_IDS` 保持一致）。 */
export const SETTINGS_RESULT_IDS = ['source-settings-final-result', 'source-settings-result', 'bubble-settings-result'] as const

export type SettingsPayload = Record<string, unknown>

export type SettingsResultMessage = {
  resultId: string
  settings: SettingsPayload
}

/**
 * 内置浏览器只认代理可渲染的 http(s) 地址。
 * `data:` 等内联地址必须留在 iframe 里（`window.open` 会开出无意义的空白页/新标签），
 * 书源自生成页面全部属于这一类。
 */
export function isInlineBrowserTarget(url: string): boolean {
  const value = String(url || '').trim()
  return value.length > 0 && !/^https?:\/\//i.test(value)
}

/** 校验 iframe postMessage 过来的设置结果（`source` / `type` 由调用方在监听器里先行校验）。 */
export function readSettingsResultMessage(data: unknown): SettingsResultMessage | null {
  if (!data || typeof data !== 'object') return null
  const record = data as Record<string, unknown>
  if (record.source !== 'legado-webview' || record.type !== 'settings-result') return null
  const settings = record.settings
  if (!settings || typeof settings !== 'object' || Array.isArray(settings)) return null
  const payload = settings as SettingsPayload
  if (Object.keys(payload).length === 0) return null
  return {
    resultId: typeof record.resultId === 'string' ? record.resultId : '',
    settings: payload,
  }
}

/** 判断某个消息是否为气泡页的结果（用于与设置结果区分合并）。 */
export function isBubbleResult(resultId: string): boolean {
  return resultId.startsWith('bubble-')
}

/**
 * 段评气泡结果是设置结果的「子结果」：书源 JS 仅在
 * `_bubble_applied === '1'` 且 `_bubble_nonce === _settings_nonce` 时认定它属于本次设置会话，
 * 并且只取其中的 `pstyle`（样式值必须形如 `[A-Za-z0-9_-]{1,32}`）。
 */
export function mergeBubbleResult(
  settings: SettingsPayload | null,
  bubble: SettingsPayload | null,
): SettingsPayload | null {
  if (!settings) return null
  if (!bubble) return settings
  if (String(bubble._bubble_applied ?? '') !== '1') return settings
  const nonce = String(settings._settings_nonce ?? '')
  if (!nonce || String(bubble._bubble_nonce ?? '') !== nonce) return settings
  const style = String(bubble.pstyle ?? '')
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(style)) return settings
  return { ...settings, pstyle: style }
}
