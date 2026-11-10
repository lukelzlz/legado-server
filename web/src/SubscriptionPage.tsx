import React, { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api, rssBrowserId, type RssSourceSummary, type SourceSubscription } from './api'
import { ButtonSpinner } from './ButtonSpinner'
import { Icon } from './icons'
import { useOnlineImport } from './onlineImport'
import { SourceWebViewModal } from './SourceWebViewModal'
import { toast } from './Toast'

/** 订阅条目类型（开源阅读 RuleSub.type）→ 一键导入类型。 */
export const SUBSCRIPTION_KINDS = ['bookSource', 'rssSource', 'replaceRule'] as const

/**
 * 订阅源图标只接受 http(s) 地址与**位图** data URI。
 * 刻意不放行 `data:image/svg+xml`：SVG 可内嵌脚本，而图标来自第三方订阅源文件。
 */
export function rssIconSrc(icon: string | null | undefined): string | null {
  const value = (icon ?? '').trim()
  if (/^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(value)) return value
  if (/^https?:\/\//i.test(value)) {
    try { return new URL(value).href } catch { return null }
  }
  return null
}

const notify = (message: string, type?: 'info' | 'success' | 'error') => {
  if (type === 'error') toast.error(message)
  else if (type === 'success') toast.success(message)
  else toast.info(message)
}

const messageOf = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback

/**
 * 订阅源（开源阅读 RssSource）：图标宫格，点开即在内置浏览器里打开它的页面；
 * 页面上的一键导入链接由代理层拦下，交给 `useOnlineImport` 按类型导入。
 */
function RssSourceSection({ onSourcesChange, reloadKey }: { onSourcesChange: () => void; reloadKey: number }) {
  const { t } = useTranslation()
  const [items, setItems] = useState<RssSourceSummary[]>([])
  const [url, setUrl] = useState('')
  const [managing, setManaging] = useState(false)
  const [opened, setOpened] = useState<RssSourceSummary | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    try { setItems(await api.rssSources()) } catch (error) { notify(messageOf(error, t('rss.cannotLoad', '无法载入订阅源')), 'error') }
  }, [t])
  useEffect(() => { void load() }, [load, reloadKey])

  const onlineImport = useOnlineImport({
    onToast: notify,
    onImported: kind => { if (kind === 'rssSource') void load(); else onSourcesChange() },
  })

  const importUrl = (event: FormEvent) => {
    event.preventDefault()
    if (!url.trim()) return
    void onlineImport.start(url, 'rssSource').then(() => setUrl(''))
  }

  const importFile = async (file: File | undefined) => {
    if (!file) return
    try {
      const result = await api.importRssSources(await file.text())
      const errors = result.errors?.length ?? 0
      notify(t('rss.importSummary', '订阅源导入完成：新增 {{imported}}，更新 {{updated}}，失败 {{failed}}', {
        imported: result.imported || 0, updated: result.updated || 0, failed: errors,
      }), errors > 0 && !(result.imported || result.updated) ? 'error' : 'success')
      await load()
    } catch (error) {
      notify(messageOf(error, t('rss.importFailed', '导入订阅源失败')), 'error')
    } finally {
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  const open = (item: RssSourceSummary) => {
    if (!item.openUrl) { notify(t('rss.noOpenUrl', '该订阅源没有可打开的网页地址'), 'error'); return }
    setOpened(item)
  }
  const toggle = async (item: RssSourceSummary) => {
    try { await api.setRssSourceEnabled(item.id, !(item.enabled ?? true)); await load() } catch (error) { notify(messageOf(error, t('subscription.updateFailed', '更新失败')), 'error') }
  }
  const remove = async (item: RssSourceSummary) => {
    if (!confirm(t('rss.deleteConfirm', '删除订阅源“{{name}}”？', { name: item.name }))) return
    try { await api.removeRssSource(item.id); await load() } catch (error) { notify(messageOf(error, t('subscription.deleteFailed', '删除失败')), 'error') }
  }

  const visible = managing ? items : items.filter(item => item.enabled ?? true)

  return (
    <section className="subscription-panel rss-source-section">
      <header>
        <div>
          <span className="section-kicker">{t('rss.kicker', '网页入口')}</span>
          <h2>{t('rss.title', '订阅源')}</h2>
          <p>{t('rss.desc', '点开即在内置浏览器中打开订阅源的页面；页面上的「一键导入」按钮可直接导入书源、订阅源、替换规则与朗读引擎。')}</p>
        </div>
        {items.length > 0 && (
          <button type="button" className="subtle-button" onClick={() => setManaging(value => !value)}>
            {managing ? t('rss.manageDone', '完成') : t('rss.manage', '管理')}
          </button>
        )}
      </header>
      <form onSubmit={importUrl}>
        <input value={url} onChange={event => setUrl(event.target.value)} placeholder={t('rss.urlPlaceholder', '订阅源 JSON 地址')} inputMode="url" />
        <button className={`primary-button ${onlineImport.busy ? 'is-loading' : ''}`} disabled={onlineImport.busy || !url.trim()} aria-busy={onlineImport.busy}>
          {onlineImport.busy && <ButtonSpinner size={12} />}{t('rss.importUrl', '网络导入')}
        </button>
        <button type="button" className="subtle-button" onClick={() => fileRef.current?.click()}>{t('rss.importFile', '导入文件')}</button>
        <input ref={fileRef} type="file" accept=".json,application/json,text/plain" hidden onChange={event => void importFile(event.target.files?.[0])} />
      </form>
      {visible.length === 0 ? (
        <p className="rss-source-empty">{items.length === 0 ? t('rss.empty', '还没有订阅源。') : t('rss.allDisabled', '订阅源均已停用，点「管理」可重新启用。')}</p>
      ) : (
        <div className="rss-source-grid">
          {visible.map(item => {
            const icon = rssIconSrc(item.icon)
            const enabled = item.enabled ?? true
            return (
              <article key={item.id} className={`rss-source-card ${enabled ? '' : 'is-disabled'}`}>
                <button type="button" className="rss-source-open" title={item.openUrl ?? item.id} onClick={() => open(item)}>
                  {icon
                    ? <img className="rss-source-icon" src={icon} alt="" loading="lazy" referrerPolicy="no-referrer" />
                    : <span className="rss-source-icon is-letter" aria-hidden="true">{item.name.trim().charAt(0) || '?'}</span>}
                  <span className="rss-source-name">{item.name}</span>
                </button>
                {managing && (
                  <div className="rss-source-manage">
                    <button type="button" className="subtle-button" onClick={() => void toggle(item)}>{enabled ? t('common.enabled', '已启用') : t('common.disabled', '已停用')}</button>
                    <button type="button" className="shelf-remove" aria-label={t('rss.delete', '删除订阅源')} onClick={() => void remove(item)}><Icon name="close" /></button>
                  </div>
                )}
              </article>
            )
          })}
        </div>
      )}
      {opened && (
        <SourceWebViewModal
          sourceId={rssBrowserId(opened.id)}
          sourceName={opened.name}
          onClose={() => setOpened(null)}
          onToast={notify}
          onImportOnline={(target, kind) => { void onlineImport.start(target, kind) }}
        />
      )}
      {onlineImport.element}
    </section>
  )
}

type Draft = { url: string; name: string; type: number; autoUpdate: boolean; updateInterval: number }
const EMPTY_DRAFT: Draft = { url: '', name: '', type: 0, autoUpdate: false, updateInterval: 24 }

/**
 * 规则订阅（开源阅读 RuleSub）：一条 = 名称 + 地址 + 类型。
 * 「导入」走预览 / 确认；开启自动更新的条目由服务端按间隔直接更新。
 */
function RuleSubscriptionSection({ onSourcesChange, onRssChange }: { onSourcesChange: () => void; onRssChange: () => void }) {
  const { t } = useTranslation()
  const [items, setItems] = useState<SourceSubscription[]>([])
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT)
  const [busy, setBusy] = useState<number | 'all' | 'save' | null>(null)
  /** 正在走预览 / 确认的订阅 id：导入落库后据此回报「最近更新」。 */
  const importingRef = useRef<number | null>(null)

  const typeLabel = (type: number | undefined) =>
    type === 1 ? t('onlineImport.kindRssSource', '订阅源') : type === 2 ? t('onlineImport.kindReplaceRule', '替换规则') : t('onlineImport.kindBookSource', '书源')

  const load = useCallback(async () => {
    try { setItems(await api.subscriptions()) } catch (error) { notify(messageOf(error, t('subscription.cannotLoad', '无法载入订阅')), 'error') }
  }, [t])
  useEffect(() => { void load() }, [load])

  const refreshTargets = (kind: string) => { if (kind === 'rssSource') onRssChange(); else onSourcesChange() }

  const onlineImport = useOnlineImport({
    onToast: notify,
    onImported: (kind, count) => {
      refreshTargets(kind)
      const id = importingRef.current
      importingRef.current = null
      if (id !== null) void api.markSubscriptionSynced(id, count).then(load).catch(() => undefined)
    },
  })

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!draft.url.trim()) return
    setBusy('save')
    try {
      await api.saveSubscription({ ...draft, url: draft.url.trim(), name: draft.name.trim(), updateInterval: draft.autoUpdate ? draft.updateInterval : 0 })
      setDraft(EMPTY_DRAFT)
      notify(t('subscription.saved', '订阅已保存'), 'success')
      await load()
    } catch (error) {
      notify(messageOf(error, t('subscription.saveFailed', '保存订阅失败')), 'error')
    } finally { setBusy(null) }
  }

  const edit = (item: SourceSubscription) => setDraft({
    url: item.url, name: item.name ?? '', type: item.type ?? 0,
    autoUpdate: item.autoUpdate ?? false, updateInterval: item.updateInterval || 24,
  })

  const importNow = (item: SourceSubscription) => {
    importingRef.current = item.id
    void onlineImport.start(item.url, SUBSCRIPTION_KINDS[item.type ?? 0] ?? 'bookSource')
  }

  const updateAll = async () => {
    setBusy('all')
    try {
      const result = await api.updateSubscriptions()
      notify(t('subscription.syncAllSummary', '全部更新完成：成功 {{updated}}，失败 {{failed}}', { updated: result.updated, failed: result.failed }), result.failed > 0 ? 'error' : 'success')
      onSourcesChange(); onRssChange(); await load()
    } catch (error) {
      notify(messageOf(error, t('subscription.syncFailed', '更新失败')), 'error')
    } finally { setBusy(null) }
  }

  const toggle = async (item: SourceSubscription) => {
    setBusy(item.id)
    try {
      await api.saveSubscription({ url: item.url, enabled: !item.enabled, name: item.name, type: item.type, autoUpdate: item.autoUpdate, updateInterval: item.updateInterval })
      await load()
    } catch (error) { notify(messageOf(error, t('subscription.updateFailed', '更新失败')), 'error') } finally { setBusy(null) }
  }

  const remove = async (item: SourceSubscription) => {
    if (!confirm(t('subscription.deleteConfirm', '删除订阅“{{url}}”？已导入的内容会保留。', { url: item.name || item.url }))) return
    setBusy(item.id)
    try { await api.removeSubscription(item.id); await load() } catch (error) { notify(messageOf(error, t('subscription.deleteFailed', '删除失败')), 'error') } finally { setBusy(null) }
  }

  const status = (item: SourceSubscription) => {
    if (item.lastError) return t('subscription.lastErrorPrefix', '最近失败：{{error}}', { error: item.lastError })
    if (item.lastSuccessAt) return t('subscription.lastSyncPrefix', '最近更新：{{time}}，处理 {{count}} 条', { time: new Date(item.lastSuccessAt).toLocaleString(), count: item.lastImported })
    return t('subscription.notSyncedYet', '尚未更新')
  }

  return (
    <section className="subscription-panel">
      <header>
        <div>
          <span className="section-kicker">{t('subscription.autoUpdateKicker', '规则订阅')}</span>
          <h2>{t('subscription.subscriptionTitle', '规则订阅')}</h2>
          <p>{t('subscription.subscriptionDesc', '订阅书源、订阅源或替换规则的地址。「导入」会先预览再确认；开启自动更新后由服务端按间隔直接更新。')}</p>
        </div>
        <button type="button" className={`subtle-button ${busy === 'all' ? 'is-loading' : ''}`} onClick={() => void updateAll()} disabled={busy !== null || items.length === 0} aria-busy={busy === 'all'}>
          {busy === 'all' && <ButtonSpinner size={12} />}{busy === 'all' ? t('subscription.syncing', '更新中...') : t('subscription.syncAll', '全部更新')}
        </button>
      </header>
      <form className="rule-sub-form" onSubmit={save}>
        <select aria-label={t('subscription.typeLabel', '订阅类型')} value={draft.type} onChange={event => setDraft({ ...draft, type: Number(event.target.value) })}>
          <option value={0}>{typeLabel(0)}</option>
          <option value={1}>{typeLabel(1)}</option>
          <option value={2}>{typeLabel(2)}</option>
        </select>
        <input className="rule-sub-name" value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} placeholder={t('subscription.namePlaceholder', '名称（可选）')} />
        <input value={draft.url} onChange={event => setDraft({ ...draft, url: event.target.value })} placeholder="https://example.com/sources.json" inputMode="url" />
        <label className="rule-sub-auto">
          <input type="checkbox" checked={draft.autoUpdate} onChange={event => setDraft({ ...draft, autoUpdate: event.target.checked })} />
          {t('subscription.autoUpdate', '自动更新')}
        </label>
        {draft.autoUpdate && (
          <label className="rule-sub-interval">
            <input type="number" min={1} max={8760} value={draft.updateInterval} onChange={event => setDraft({ ...draft, updateInterval: Math.max(1, Number(event.target.value) || 1) })} />
            {t('subscription.intervalHours', '小时')}
          </label>
        )}
        <button className={`primary-button ${busy === 'save' ? 'is-loading' : ''}`} disabled={busy !== null || !draft.url.trim()} aria-busy={busy === 'save'}>
          {busy === 'save' && <ButtonSpinner size={12} />}{t('subscription.addSubscription', '保存订阅')}
        </button>
      </form>
      <div className="subscription-list">
        {items.length === 0 ? <p>{t('subscription.emptySubscriptions', '还没有订阅。')}</p> : items.map(item => (
          <article key={item.id}>
            <div>
              <strong><span className="rule-sub-type">{typeLabel(item.type)}</span>{item.name || item.url}</strong>
              <small>{item.name ? `${item.url} · ` : ''}{item.autoUpdate ? t('subscription.autoEvery', '每 {{hours}} 小时自动更新', { hours: item.updateInterval ?? 0 }) : t('subscription.manualOnly', '仅手动导入')} · {status(item)}</small>
            </div>
            <div className="subscription-actions">
              <button type="button" className="subtle-button" onClick={() => importNow(item)} disabled={busy !== null || onlineImport.busy}>{t('subscription.syncNow', '导入')}</button>
              <button type="button" className="subtle-button" onClick={() => edit(item)} disabled={busy !== null}>{t('subscription.edit', '编辑')}</button>
              <button type="button" className="subtle-button" onClick={() => void toggle(item)} disabled={busy !== null}>{item.enabled ? t('common.enabled', '已启用') : t('common.disabled', '已停用')}</button>
              <button type="button" className="shelf-remove" aria-label={t('subscription.deleteSubscription', '删除订阅')} onClick={() => void remove(item)} disabled={busy !== null}><Icon name="close" /></button>
            </div>
          </article>
        ))}
      </div>
      {onlineImport.element}
    </section>
  )
}

export function SubscriptionPage({ onSourcesChange }: { onSourcesChange: () => void }) {
  const { t } = useTranslation()
  const [rssReloadKey, setRssReloadKey] = useState(0)
  return (
    <main className="subscription-page">
      <header className="page-title">
        <div>
          <span className="section-kicker">{t('subscription.pageKicker', '订阅')}</span>
          <h1>{t('subscription.pageTitle', '订阅')}</h1>
          <p>{t('subscription.subscriptionPageDesc', '管理订阅源与规则订阅，与开源阅读的「订阅」页一致。')}</p>
        </div>
      </header>
      <RssSourceSection onSourcesChange={onSourcesChange} reloadKey={rssReloadKey} />
      <RuleSubscriptionSection onSourcesChange={onSourcesChange} onRssChange={() => setRssReloadKey(key => key + 1)} />
    </main>
  )
}
