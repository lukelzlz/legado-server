import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, RssArticle, RssSource } from './api'
import { toast } from './Toast'
import { Icon } from './icons'

/**
 * 订阅源（RSS）页面。
 *
 * ## 与「书源订阅」的区别（两件事，别混）
 *
 * - 本页 = **订阅源**：一个能抓文章列表的源 + 抓到的文章（对应 Legado 手机版「订阅」）；
 * - `#subscriptions` = **书源订阅**：订阅一个书源 JSON 的 URL，用于更新书源表。
 *
 * ## 设计要点
 *
 * 1. **只打开网页的源如实提示，不假装能抓文章**：真实 `rssSources.json` 里 8 条有 7 条
 *    没有 `ruleArticles`（手机版里它们只是「点开网页」的链接），因此这类源在列表里
 *    显式标注「仅打开网页」，点它直接用内置浏览器打开 `sourceUrl`，而不是给一个空文章列表。
 * 2. **刷新失败必须看得见**：源卡片展示 `lastError`，刷新结果带 message 时用 toast 报出。
 *    绝不把「上游挂了」表现成「0 篇文章」。
 * 3. **外观沿用 Web 版**：复用 `.rules-page-container` / `.rules-sidebar` 等既有布局类，
 *    不照搬手机版视觉。
 */
export function RssPage() {
  const { t } = useTranslation()
  const [sources, setSources] = useState<RssSource[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedUrl, setSelectedUrl] = useState<string | null>(null)
  const [articles, setArticles] = useState<RssArticle[]>([])
  const [articlesLoading, setArticlesLoading] = useState(false)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshingAll, setRefreshingAll] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [importing, setImporting] = useState(false)
  const [notice, setNotice] = useState('')
  const importFileRef = useRef<HTMLInputElement>(null)

  const selected = useMemo(
    () => sources.find(source => source.sourceUrl === selectedUrl) ?? null,
    [sources, selectedUrl],
  )

  /** 该源是否配了文章列表规则。没有 ⇒ 它是「只打开网页」的源（真实数据里占多数）。 */
  const hasArticleRule = useCallback((source: RssSource) => (source.ruleArticles ?? '').trim().length > 0, [])

  const loadSources = useCallback(async () => {
    setLoading(true)
    try {
      const data = await api.getRssSources()
      setSources(data)
      // 默认选中第一条启用且真的能抓文章的源（否则用户一进来看到的就是空列表）
      setSelectedUrl(prev => {
        if (prev && data.some(source => source.sourceUrl === prev)) return prev
        const preferred = data.find(source => source.enabled !== false && hasArticleRule(source)) ?? data[0]
        return preferred?.sourceUrl ?? null
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.loadSourcesFailed', { defaultValue: '加载订阅源失败' }))
      setNotice(t('rss.cannotLoadSources', { defaultValue: '无法载入订阅源' }))
    } finally {
      setLoading(false)
    }
  }, [hasArticleRule, t])

  const loadArticles = useCallback(async (sourceUrl: string, onlyUnread: boolean) => {
    setArticlesLoading(true)
    try {
      setArticles(await api.getRssArticles(sourceUrl, onlyUnread))
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.loadArticlesFailed', { defaultValue: '加载文章失败' }))
      setArticles([])
    } finally {
      setArticlesLoading(false)
    }
  }, [t])

  useEffect(() => {
    void loadSources()
  }, [loadSources])

  useEffect(() => {
    if (!selectedUrl) {
      setArticles([])
      return
    }
    void loadArticles(selectedUrl, unreadOnly)
  }, [selectedUrl, unreadOnly, loadArticles])

  const handleRefresh = useCallback(async () => {
    if (!selectedUrl) return
    setRefreshing(true)
    try {
      const result = await api.refreshRssSource(selectedUrl)
      if (result.failed) {
        // 失败必须可见：明确报出上游/规则原因，绝不静默成 0 条
        toast.error(
          t('rss.refreshFailedWithReason', {
            defaultValue: '刷新失败：{{reason}}',
            reason: result.message ?? t('rss.unknownReason', { defaultValue: '未知原因' }),
          }),
        )
      } else {
        toast.success(
          t('rss.refreshSummary', {
            defaultValue: '刷新完成：共 {{count}} 篇，新增 {{added}} 篇',
            count: result.articles,
            added: result.newArticles,
          }),
        )
      }
      await loadSources()
      await loadArticles(selectedUrl, unreadOnly)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.refreshFailed', { defaultValue: '刷新失败' }))
    } finally {
      setRefreshing(false)
    }
  }, [selectedUrl, unreadOnly, loadSources, loadArticles, t])

  const handleRefreshAll = useCallback(async () => {
    setRefreshingAll(true)
    try {
      const targets = sources.filter(source => source.enabled !== false).map(source => source.sourceUrl)
      const results = await api.refreshAllRssSources(targets)
      const failed = results.filter(result => result.failed)
      const added = results.reduce((sum, result) => sum + result.newArticles, 0)
      if (failed.length > 0) {
        // 部分失败要如实报出数量与首个原因（全部成功才用 success）
        toast.error(
          t('rss.refreshAllPartial', {
            defaultValue: '刷新完成：{{ok}} 个成功，{{bad}} 个失败（{{reason}}）',
            ok: results.length - failed.length,
            bad: failed.length,
            reason: failed[0].message ?? t('rss.unknownReason', { defaultValue: '未知原因' }),
          }),
        )
      } else {
        toast.success(
          t('rss.refreshAllSummary', { defaultValue: '全部刷新完成：{{count}} 个源，新增 {{added}} 篇', count: results.length, added }),
        )
      }
      await loadSources()
      if (selectedUrl) await loadArticles(selectedUrl, unreadOnly)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.refreshAllFailed', { defaultValue: '批量刷新失败' }))
    } finally {
      setRefreshingAll(false)
    }
  }, [sources, selectedUrl, unreadOnly, loadSources, loadArticles, t])

  const handleOpenArticle = useCallback(async (article: RssArticle) => {
    // 打开原文（新标签，避免离开当前页丢失上下文）
    if (article.link) window.open(article.link, '_blank', 'noopener,noreferrer')
    if (article.read) return
    // 乐观更新 + 失败回滚：点了却没标上已读会让「只看未读」混乱
    setArticles(prev => prev.map(item => (item.id === article.id ? { ...item, read: true } : item)))
    try {
      await api.markRssArticleRead(article.id, true)
      setSources(prev => prev.map(source =>
        source.sourceUrl === article.sourceUrl
          ? { ...source, unreadCount: Math.max(0, (source.unreadCount ?? 1) - 1) }
          : source,
      ))
      if (unreadOnly) setArticles(prev => prev.filter(item => item.id !== article.id))
    } catch (error) {
      setArticles(prev => prev.map(item => (item.id === article.id ? { ...item, read: false } : item)))
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.markReadFailed', { defaultValue: '标记已读失败' }))
    }
  }, [unreadOnly, t])

  const handleOpenSourcePage = useCallback((source: RssSource) => {
    // 「只打开网页」的源：直接开它的地址（手机版点这类源就是这个行为）
    if (source.sourceUrl.startsWith('http://') || source.sourceUrl.startsWith('https://')) {
      window.open(source.sourceUrl, '_blank', 'noopener,noreferrer')
    } else {
      // 自定义 scheme（如 snssdk1128://）浏览器打不开，如实提示而不是静默无反应
      toast.error(t('rss.cannotOpenSource', { defaultValue: '该订阅源地址无法在浏览器中打开：{{url}}', url: source.sourceUrl }))
    }
  }, [t])

  const handleToggleEnabled = useCallback(async (source: RssSource) => {
    const next = !(source.enabled ?? true)
    setSources(prev => prev.map(item => (item.sourceUrl === source.sourceUrl ? { ...item, enabled: next } : item)))
    try {
      await api.updateRssSource(source.sourceUrl, { ...source, enabled: next })
    } catch (error) {
      setSources(prev => prev.map(item => (item.sourceUrl === source.sourceUrl ? { ...item, enabled: !next } : item)))
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.toggleFailed', { defaultValue: '切换启用状态失败' }))
    }
  }, [t])

  const handleDelete = useCallback(async (source: RssSource) => {
    const confirmed = window.confirm(
      t('rss.deleteConfirm', { defaultValue: '删除订阅源「{{name}}」？它抓到的文章也会一并删除。', name: source.sourceName || source.sourceUrl }),
    )
    if (!confirmed) return
    try {
      await api.deleteRssSource(source.sourceUrl)
      toast.success(t('rss.deleted', { defaultValue: '订阅源已删除' }))
      if (selectedUrl === source.sourceUrl) setSelectedUrl(null)
      await loadSources()
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.deleteFailed', { defaultValue: '删除失败' }))
    }
  }, [selectedUrl, loadSources, t])

  const handleMarkAllRead = useCallback(async () => {
    if (!selectedUrl) return
    try {
      const result = await api.markRssSourceRead(selectedUrl)
      toast.success(t('rss.readAllSummary', { defaultValue: '已把 {{count}} 篇标为已读', count: result.updated }))
      await loadSources()
      await loadArticles(selectedUrl, unreadOnly)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.readAllFailed', { defaultValue: '标记全部已读失败' }))
    }
  }, [selectedUrl, unreadOnly, loadSources, loadArticles, t])

  const handleImport = useCallback(async () => {
    const text = importText.trim()
    if (!text) {
      toast.error(t('rss.inputRequired', { defaultValue: '请粘贴订阅源 JSON 或选择文件' }))
      return
    }
    setImporting(true)
    try {
      // 服务端接受单条对象；数组则由服务端逐条判断前先剥成单条
      const payload = extractSingleSource(text)
      const saved = await api.importRssSourceText(payload)
      toast.success(t('rss.importedOne', { defaultValue: '已导入订阅源「{{name}}」', name: saved.sourceName || saved.sourceUrl }))
      setImportOpen(false)
      setImportText('')
      await loadSources()
      setSelectedUrl(saved.sourceUrl)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      toast.error(message || t('rss.importFailed', { defaultValue: '导入失败，请检查 JSON 格式' }))
    } finally {
      setImporting(false)
    }
  }, [importText, loadSources, t])

  return (
    <main className="rules-page-container rss-page">
      <aside className="rules-sidebar">
        <header className="rules-sidebar-header">
          <div className="rules-sidebar-title">
            <Icon name="list" />
            <span>{t('rss.sourceListTitle', { defaultValue: '订阅源' })}</span>
            <span className="rules-count-badge">{sources.length}</span>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              type="button"
              className="subtle-button compact"
              disabled={refreshingAll || sources.length === 0}
              onClick={() => void handleRefreshAll()}
              title={t('rss.refreshAll', { defaultValue: '全部刷新' })}
            >
              <Icon name="refresh" />
              <span>{refreshingAll ? t('rss.refreshing', { defaultValue: '刷新中...' }) : t('rss.refreshAll', { defaultValue: '全部刷新' })}</span>
            </button>
            <button
              type="button"
              className="primary-button"
              style={{ padding: '4px 10px', height: 28, fontSize: 12 }}
              onClick={() => setImportOpen(true)}
            >
              <Icon name="plus" />
              <span>{t('rss.importSource', { defaultValue: '导入' })}</span>
            </button>
          </div>
        </header>

        {notice && <p className="sidebar-notice">{notice}</p>}

        <nav className="rules-list" aria-label={t('rss.sourceListTitle', { defaultValue: '订阅源' })}>
          {loading && sources.length === 0 && (
            <p className="sidebar-notice">{t('rss.loadingSources', { defaultValue: '正在加载订阅源...' })}</p>
          )}
          {!loading && sources.length === 0 && (
            <p className="sidebar-notice">{t('rss.emptySources', { defaultValue: '还没有订阅源。点右上角「导入」粘贴订阅源 JSON，或从备份包导入。' })}</p>
          )}
          {sources.map(source => {
            const canFetch = hasArticleRule(source)
            const unread = source.unreadCount ?? 0
            return (
              <div
                key={source.sourceUrl}
                className={`rule-list-card${source.sourceUrl === selectedUrl ? ' selected' : ''}`}
                onClick={() => setSelectedUrl(source.sourceUrl)}
                role="button"
                tabIndex={0}
                onKeyDown={event => {
                  if (event.key === 'Enter' || event.key === ' ') setSelectedUrl(source.sourceUrl)
                }}
              >
                <div className="rule-card-header">
                  <span className="rule-name-text">{source.sourceName || source.sourceUrl}</span>
                  {unread > 0 && <span className="rules-count-badge">{unread}</span>}
                </div>
                <div className="rule-meta-tags">
                  {source.sourceGroup ? <span className="rule-tag">{source.sourceGroup}</span> : null}
                  {/* 没有文章规则的源如实标注 —— 它在手机版里只是「打开网页」 */}
                  {canFetch ? (
                    <span className="rule-tag tag-js">{t('rss.hasRule', { defaultValue: '可抓取' })}</span>
                  ) : (
                    <span className="rule-tag">{t('rss.webOnly', { defaultValue: '仅打开网页' })}</span>
                  )}
                  {(source.enabled ?? true) ? null : (
                    <span className="rule-tag">{t('rss.disabled', { defaultValue: '已停用' })}</span>
                  )}
                </div>
                {/* 失败原因直接展示，绝不隐藏 */}
                {source.lastError ? (
                  <p className="sidebar-notice" style={{ margin: '6px 0 0', padding: 0 }}>
                    {t('rss.lastErrorPrefix', { defaultValue: '最近失败：' })}{source.lastError}
                  </p>
                ) : null}
                <div className="rule-accordion-actions">
                  <button
                    type="button"
                    className="subtle-button compact"
                    onClick={event => {
                      event.stopPropagation()
                      void handleToggleEnabled(source)
                    }}
                  >
                    {source.enabled ?? true
                      ? t('rss.disable', { defaultValue: '停用' })
                      : t('rss.enable', { defaultValue: '启用' })}
                  </button>
                  <button
                    type="button"
                    className="subtle-button compact"
                    onClick={event => {
                      event.stopPropagation()
                      void handleDelete(source)
                    }}
                  >
                    <Icon name="trash" />
                  </button>
                </div>
              </div>
            )
          })}
        </nav>
      </aside>

      <section className="rules-content-area">
        <header className="page-title">
          <div>
            <span className="section-kicker">{t('rss.sectionKicker', { defaultValue: '订阅' })}</span>
            <h1>{selected ? selected.sourceName : t('rss.mainTitle', { defaultValue: '订阅源' })}</h1>
            <p>{t('rss.pageDesc', { defaultValue: '抓取订阅源的文章列表，未读一眼可见；点开即标记已读。' })}</p>
          </div>
          <div className="page-title-actions">
            <button
              type="button"
              className={`subtle-button${unreadOnly ? ' active' : ''}`}
              onClick={() => setUnreadOnly(prev => !prev)}
            >
              <Icon name="check" />
              <span>{unreadOnly ? t('rss.showAll', { defaultValue: '显示全部' }) : t('rss.unreadOnly', { defaultValue: '只看未读' })}</span>
            </button>
            <button type="button" className="subtle-button" disabled={!selectedUrl} onClick={() => void handleMarkAllRead()}>
              {t('rss.markAllRead', { defaultValue: '全部已读' })}
            </button>
            <button
              type="button"
              className="primary-button"
              disabled={!selectedUrl || refreshing}
              onClick={() => void handleRefresh()}
            >
              <Icon name="refresh" />
              <span>{refreshing ? t('rss.refreshing', { defaultValue: '刷新中...' }) : t('rss.refreshNow', { defaultValue: '立即刷新' })}</span>
            </button>
          </div>
        </header>

        {!selected && (
          <div className="empty-editor">
            <Icon name="list" />
            <p>{t('rss.selectSourceHint', { defaultValue: '在左侧选择一个订阅源，或先导入一份订阅源 JSON。' })}</p>
          </div>
        )}

        {selected && (
          <div className="rules-detail-card">
            {/* 无规则的源如实说明：它在手机版里是「打开网页」的订阅，服务端只能靠默认 feed 解析碰运气。
                但**仍然提供刷新** —— 若它其实是个标准 RSS/Atom 地址（很常见），默认解析就能抓到文章。 */}
            {!hasArticleRule(selected) && (
              <header className="rules-detail-header">
                <div className="rules-detail-title-group">
                  <h2>{t('rss.webOnlyTitle', { defaultValue: '此订阅源没有文章规则' })}</h2>
                </div>
                <div className="rules-detail-actions">
                  <button type="button" className="primary-button" onClick={() => handleOpenSourcePage(selected)}>
                    {t('rss.openWebPage', { defaultValue: '打开网页' })}
                  </button>
                </div>
              </header>
            )}
            {!hasArticleRule(selected) && (
              <p className="sidebar-notice" style={{ margin: '0 0 12px' }}>
                {t('rss.webOnlyDesc', {
                  defaultValue: '它在手机版里是「打开网页」的订阅：没有 ruleArticles 规则，因此服务端只能尝试按标准 RSS/Atom 解析它的地址。若它其实是个 feed，点「立即刷新」就能抓到文章；否则请点「打开网页」浏览。',
                })}
              </p>
            )}
            <header className="rules-detail-header">
              <div className="rules-detail-title-group">
                <h2>{t('rss.articleListTitle', { defaultValue: '文章列表' })}</h2>
                <span className="rules-count-badge">{articles.length}</span>
              </div>
            </header>
            {articlesLoading && <p className="sidebar-notice">{t('rss.loadingArticles', { defaultValue: '正在加载文章...' })}</p>}
            {!articlesLoading && articles.length === 0 && (
              <p className="sidebar-notice" style={{ margin: 0 }}>
                {unreadOnly
                  ? t('rss.emptyUnread', { defaultValue: '没有未读文章。' })
                  : hasArticleRule(selected)
                    ? t('rss.emptyArticles', { defaultValue: '还没有文章，点右上角「立即刷新」抓取。' })
                    : t('rss.emptyArticlesNoRule', {
                        defaultValue: '没有解析出文章。这个源没有 ruleArticles 规则，服务端已按标准 RSS/Atom 尝试解析；若它的地址不是 feed，请点上面的「打开网页」浏览。',
                      })}
              </p>
            )}
            {articles.length > 0 && (
              <ul className="rss-article-list">
                {articles.map(article => (
                  <li key={article.id} className={`rss-article-item${article.read ? ' read' : ''}`}>
                    <button type="button" className="rss-article-link" onClick={() => void handleOpenArticle(article)}>
                      <span className="rss-article-title">
                        {!article.read && <span className="rss-unread-dot" aria-hidden="true" />}
                        {article.title}
                      </span>
                      <span className="rss-article-meta">
                        {article.sortName ? <span className="rule-tag">{article.sortName}</span> : null}
                        {article.pubDate ? (
                          <span>
                            <Icon name="clock" />
                            {article.pubDate}
                          </span>
                        ) : null}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      {importOpen && (
        <div className="modal-backdrop" onClick={() => setImportOpen(false)}>
          <div className="rule-modal-card" style={{ maxWidth: 560 }} onClick={event => event.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">{t('rss.importSource', { defaultValue: '导入' })}</h3>
              <button type="button" className="icon-btn" onClick={() => setImportOpen(false)}>
                <Icon name="close" />
              </button>
            </header>
            <p className="sidebar-notice" style={{ marginTop: 0 }}>
              {t('rss.importHint', {
                defaultValue: '粘贴单个订阅源 JSON（或整包 rssSources.json 数组，取第一条），也可以直接选择文件。',
              })}
            </p>
            <textarea
              className="rule-form-textarea code-font"
              rows={10}
              value={importText}
              placeholder={t('rss.importPlaceholder', { defaultValue: '{"sourceUrl":"https://example.com/feed","sourceName":"示例","ruleArticles":"$.items[*]"}' })}
              onChange={event => setImportText(event.target.value)}
            />
            <div style={{ display: 'flex', gap: 10, marginTop: 12, alignItems: 'center' }}>
              <button type="button" className="primary-button" disabled={importing} onClick={() => void handleImport()}>
                {importing ? t('rss.importing', { defaultValue: '导入中...' }) : t('rss.startImport', { defaultValue: '开始导入' })}
              </button>
              <button type="button" className="subtle-button" disabled={importing} onClick={() => importFileRef.current?.click()}>
                <Icon name="upload" />
                <span>{t('rss.chooseFile', { defaultValue: '选择文件' })}</span>
              </button>
              <input
                ref={importFileRef}
                type="file"
                accept="application/json,.json,text/plain,.txt,*"
                style={{ display: 'none' }}
                onChange={event => {
                  const file = event.target.files?.[0]
                  // 清空 value 以便重复选择同一个文件
                  event.target.value = ''
                  if (!file) return
                  void file.text().then(text => setImportText(text))
                }}
              />
            </div>
          </div>
        </div>
      )}
    </main>
  )
}

/**
 * 从粘贴内容里取出**单个**订阅源对象的 JSON 文本。
 *
 * 三种常见输入都要能吃下：单对象、`[ ... ]` 数组（整包 `rssSources.json`）、
 * 以及带外层包装的 `{ "rssSources": [...] }`。解析失败时原样返回，
 * 由服务端给出明确错误（避免前端与后端两套判据不一致）。
 */
function extractSingleSource(text: string): string {
  const trimmed = text.trim().replace(/^\uFEFF/, '')
  try {
    const parsed: unknown = JSON.parse(trimmed)
    if (Array.isArray(parsed)) {
      if (parsed.length === 0) return trimmed
      return JSON.stringify(parsed[0])
    }
    if (parsed && typeof parsed === 'object') {
      const record = parsed as Record<string, unknown>
      for (const key of ['rssSources', 'sources', 'data']) {
        const nested = record[key]
        if (Array.isArray(nested) && nested.length > 0) return JSON.stringify(nested[0])
      }
    }
    return trimmed
  } catch {
    return trimmed
  }
}
