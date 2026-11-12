import React, { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type OnlineImportKind, type OnlineImportResult } from './api'
import { ButtonSpinner } from './ButtonSpinner'
import { NetworkImportModal } from './NetworkImportModal'

type Toast = (message: string, type?: 'info' | 'success' | 'error') => void

/** 本服务能导入的类型；其余（主题、字典规则、目录规则…）开源阅读支持而这里没有对应功能。 */
const SUPPORTED: readonly string[] = ['bookSource', 'rssSource', 'replaceRule', 'httpTts']

/**
 * 一键导入链接的去向（对齐开源阅读 OnLineImportActivity 的分发）：
 * - `preview`：书源，走「预览勾选」弹窗；
 * - `detect`：类型未知（`auto`），先让服务端按内容判别；
 * - `confirm`：订阅源 / 替换规则 / 朗读引擎，回报条数后确认导入；
 * - `unsupported`：本服务没有对应功能。
 */
export function onlineImportRoute(kind: string | undefined): 'preview' | 'detect' | 'confirm' | 'unsupported' {
  if (!kind || kind === 'auto') return 'detect'
  if (kind === 'bookSource') return 'preview'
  return SUPPORTED.includes(kind) ? 'confirm' : 'unsupported'
}

type Pending = { url: string; info: OnlineImportResult }

interface Options {
  onToast: Toast
  /** 导入落库后回调：`count` 为新增 + 更新的条数。 */
  onImported?: (kind: OnlineImportKind, count: number) => void
  /** 书源预览弹窗的目标分组候选。 */
  groups?: string[]
}

/**
 * 一键导入的统一入口：内置浏览器里点到的导入链接、订阅条目的「导入」、手填地址，全部走这里。
 *
 * 返回的 `element` 必须挂在调用方弹窗的**平级**位置（严禁嵌进另一个 `.modal-backdrop`）。
 */
export function useOnlineImport({ onToast, onImported, groups }: Options) {
  const { t } = useTranslation()
  const [bookUrl, setBookUrl] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [busy, setBusy] = useState(false)

  const kindLabel = useCallback((kind: string) => {
    if (kind === 'rssSource') return t('onlineImport.kindRssSource', '订阅源')
    if (kind === 'replaceRule') return t('onlineImport.kindReplaceRule', '替换规则')
    if (kind === 'httpTts') return t('onlineImport.kindHttpTts', '朗读引擎')
    return t('onlineImport.kindBookSource', '书源')
  }, [t])

  const start = useCallback(async (rawUrl: string, kind?: string) => {
    const url = rawUrl.trim()
    if (!url) return
    const route = onlineImportRoute(kind)
    if (route === 'unsupported') {
      onToast(t('onlineImport.unsupported', '暂不支持导入该类型（{{kind}}）', { kind }), 'error')
      return
    }
    if (route === 'preview') { setBookUrl(url); return }
    setBusy(true)
    try {
      const info = await api.onlineImport(url, route === 'detect' ? undefined : kind, true)
      if (info.kind === 'bookSource') setBookUrl(url)
      else setPending({ url, info })
    } catch (error) {
      onToast(error instanceof Error ? error.message : t('onlineImport.failed', '导入失败'), 'error')
    } finally {
      setBusy(false)
    }
  }, [onToast, t])

  const confirm = async () => {
    if (!pending) return
    setBusy(true)
    try {
      const result = await api.onlineImport(pending.url, pending.info.kind, false)
      const count = (result.imported ?? 0) + (result.updated ?? 0)
      onToast(t('onlineImport.done', '{{kind}}导入完成：新增 {{imported}}，更新 {{updated}}，跳过 {{skipped}}', {
        kind: kindLabel(result.kind), imported: result.imported ?? 0, updated: result.updated ?? 0, skipped: result.skipped ?? 0,
      }), count > 0 ? 'success' : 'info')
      setPending(null)
      onImported?.(result.kind, count)
    } catch (error) {
      onToast(error instanceof Error ? error.message : t('onlineImport.failed', '导入失败'), 'error')
    } finally {
      setBusy(false)
    }
  }

  const names = pending?.info.names ?? []
  const element = (
    <>
      {bookUrl !== null && (
        <NetworkImportModal
          initialUrl={bookUrl}
          groups={groups}
          onClose={() => setBookUrl(null)}
          onImported={result => onImported?.('bookSource', (result.imported || 0) + (result.updated || 0))}
          onToast={onToast}
        />
      )}
      {pending && (
        <div className="modal-backdrop top-layer-modal-backdrop" onClick={event => { event.stopPropagation(); if (!busy) setPending(null) }}>
          <div className="online-import-modal" role="dialog" aria-modal="true" onClick={event => event.stopPropagation()}>
            <h3>{t('onlineImport.confirmTitle', '导入{{kind}}', { kind: kindLabel(pending.info.kind) })}</h3>
            <p className="online-import-url">{pending.url}</p>
            <p>{t('onlineImport.confirmCount', '共 {{total}} 条，已存在的会被覆盖更新。', { total: pending.info.total })}</p>
            {names.length > 0 && (
              <ul className="online-import-names">
                {names.map((name, index) => <li key={`${index}-${name}`}>{name}</li>)}
                {pending.info.total > names.length && <li className="more">…</li>}
              </ul>
            )}
            <div className="online-import-actions">
              <button type="button" className="subtle-button" disabled={busy} onClick={() => setPending(null)}>{t('common.cancel', '取消')}</button>
              <button type="button" className={`primary-button ${busy ? 'is-loading' : ''}`} disabled={busy} aria-busy={busy} onClick={() => void confirm()}>
                {busy && <ButtonSpinner size={12} />}{t('onlineImport.confirm', '确认导入')}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )

  return { start, element, busy }
}
