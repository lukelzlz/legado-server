import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, BookDetails, BookGroup, BookshelfItem, Chapter, SearchResult, SearchStreamEvent, setCsrfToken, SourceRecord, SourceSubscription, SourceSummary } from './api'
import { Icon } from './icons'
import { Logo } from './Logo'
import { Login } from './Login'
import { OpenBook, ReaderScreen } from './ReaderScreen'
import { loadReaderSettings, ReaderSettings, saveReaderSettings } from './readerSettings'
import { AppHeader } from './AppHeader'
import { cleanAuthor, cleanTitle, defaultSearchFilters, filterSearchGroups, isExactMatch, isPopularMatch, SearchFilters, SearchGroup, SortMode } from './searchFilters'
import { groupSearchResults, SourceChoice, SourceChoiceStatus, useSearchStore } from './searchStore'
import { SourceSwitchModal } from './SourceSwitchModal'
import { SourceLoginModal } from './SourceLoginModal'
import { ReplaceRulesModal } from './ReplaceRulesModal'
import { ReplaceRulesPage } from './ReplaceRulesPage'
import { WebDavSettingsPage } from './WebDavSettingsPage'
import { OfflineCacheModal } from './OfflineCacheModal'
import { SourceHealthModal } from './SourceHealthModal'
import { SourceGroupModal } from './SourceGroupModal'
import { PwaManager } from './PwaManager'
import { flushOfflineProgress } from './offlineStorage'
import { toast, ToastContainer } from './Toast'
import './styles.css'

import { clearStoredInspections, getInitialOrStoredInspections, inspectAllSourcesConcurrently, SourceHealthInspection } from './sourceInspector'
import { parseSourceJsonText, extractSourcesFromRaw, sanitizeImageUrl } from './sourceImport'

export { extractSourcesFromRaw, parseSourceJsonText, sanitizeImageUrl }
export type { SourceChoice, SourceChoiceStatus }

type Page = 'sources' | 'subscriptions' | 'library' | 'shelf' | 'reader' | 'rules' | 'webdav'
const readerStorageKey = 'legado-open-book-v1'
const pageFromHash = (): Page => location.hash === '#sources' ? 'sources' : location.hash === '#subscriptions' ? 'subscriptions' : location.hash === '#rules' ? 'rules' : location.hash === '#webdav' ? 'webdav' : location.hash === '#shelf' ? 'shelf' : location.hash === '#reader' ? 'reader' : 'library'

function SourceChoiceList({
  choices,
  inspections,
  active,
  onChoose,
  onRecheck,
  onInspectSingle,
  checking,
}: {
  choices: SourceChoice[]
  inspections: Map<string, SourceHealthInspection>
  active?: string
  onChoose: (choice: SourceChoice) => void
  onRecheck: () => void
  onInspectSingle?: (choice: SourceChoice) => void
  checking: boolean
}) {
  const hoverTimerRef = useRef<Record<string, number>>({})

  const handleMouseEnter = (choice: SourceChoice) => {
    const key = `${choice.result.sourceId}\u0000${choice.result.bookUrl}`
    const insp = inspections.get(key)
    if (!insp || insp.status === 'idle') {
      hoverTimerRef.current[key] = window.setTimeout(() => {
        onInspectSingle?.(choice)
      }, 100)
    }
  }

  const handleMouseLeave = (choice: SourceChoice) => {
    const key = `${choice.result.sourceId}\u0000${choice.result.bookUrl}`
    if (hoverTimerRef.current[key]) {
      clearTimeout(hoverTimerRef.current[key])
      delete hoverTimerRef.current[key]
    }
  }

  const sortedChoices = useMemo(() => {
    return [...choices].sort((a, b) => {
      const keyA = `${a.result.sourceId}\u0000${a.result.bookUrl}`
      const keyB = `${b.result.sourceId}\u0000${b.result.bookUrl}`
      const inspA = inspections.get(keyA)
      const inspB = inspections.get(keyB)
      const scoreA = inspA ? inspA.score : (a.status === 'loaded' ? 5000 : 0)
      const scoreB = inspB ? inspB.score : (b.status === 'loaded' ? 5000 : 0)
      if (scoreB !== scoreA) return scoreB - scoreA
      return (inspB?.totalChapters ?? 0) - (inspA?.totalChapters ?? 0)
    })
  }, [choices, inspections])

  const completedCount = useMemo(() => {
    let count = 0
    for (const choice of choices) {
      const insp = inspections.get(`${choice.result.sourceId}\u0000${choice.result.bookUrl}`)
      if (insp && insp.status !== 'checking' && insp.status !== 'idle') {
        count++
      }
    }
    return count
  }, [choices, inspections])

  return (
    <section className="source-choice-list" aria-label="可用书源切换列表">
      <header>
        <div className="source-choice-header-left">
          <span>可用书源 ({choices.length})</span>
          <small>
            {checking
              ? `正在并发校验书源健康度 (${completedCount}/${choices.length})...`
              : `${completedCount}/${choices.length} 个书源已完成质量检测`}
          </small>
        </div>
        <button
          type="button"
          className="subtle-button source-recheck-btn"
          onClick={onRecheck}
          disabled={checking}
          title="探测所有书源的章节可用性与VIP限制"
        >
          <Icon name="refresh" className={checking ? 'spin' : ''} />
          <span>{checking ? '探测中' : '重新校验'}</span>
        </button>
      </header>
      <div className="source-choice-items">
        {sortedChoices.map(choice => {
          const key = `${choice.result.sourceId}\u0000${choice.result.bookUrl}`
          const isSelected = active === choice.result.bookUrl
          const isLoading = choice.status === 'loading'
          const inspection = inspections.get(key)

          let badge = null
          let statusText = '点击切换为此书源'

          if (inspection) {
            if (inspection.status === 'checking') {
              badge = <span className="source-health-badge health-checking">探测中...</span>
              statusText = '正在抽检章节正文与VIP限制...'
            } else if (inspection.status === 'valid') {
              badge = <span className="source-health-badge health-valid">✓ 完整可读</span>
              statusText = inspection.summaryText
            } else if (inspection.status === 'vip_restricted') {
              badge = <span className="source-health-badge health-vip">⚠ VIP/付费拦截</span>
              statusText = inspection.summaryText
            } else if (inspection.status === 'incomplete') {
              badge = <span className="source-health-badge health-incomplete">疑似残卷</span>
              statusText = inspection.summaryText
            } else if (inspection.status === 'error') {
              badge = <span className="source-health-badge health-error">✕ 无法读取</span>
              statusText = inspection.summaryText
            }
          } else if (isLoading) {
            statusText = '正在读取目录...'
          } else if (choice.status === 'loaded' && choice.book) {
            statusText = `已载入 · 共 ${choice.book.chapters.length} 章`
          }

          return (
            <button
              key={key}
              className={`source-choice-item ${isSelected ? 'selected' : ''} ${isLoading ? 'loading' : ''}`}
              disabled={isLoading}
              onClick={() => onChoose(choice)}
            >
              <div className="source-choice-top">
                <strong>{choice.result.sourceId}</strong>
                {badge}
              </div>
              <small>{statusText}</small>
            </button>
          )
        })}
      </div>
    </section>
  )
}

function BookDetailModal({
  book,
  choices,
  resumeIndex,
  onOpen,
  onChooseSource,
  onClose,
}: {
  book: OpenBook
  choices: SourceChoice[]
  resumeIndex: number
  onOpen: (index: number) => void
  onChooseSource: (choice: SourceChoice) => void
  onClose: () => void
}) {
  const [introExpanded, setIntroExpanded] = useState(false)
  const [inspections, setInspections] = useState<Map<string, SourceHealthInspection>>(() =>
    getInitialOrStoredInspections(choices.map(c => c.result))
  )
  const [checking, setChecking] = useState(false)
  const [inShelf, setInShelf] = useState<boolean | null>(null)
  const [shelfBusy, setShelfBusy] = useState(false)
  const abortControllerRef = useRef<AbortController | null>(null)
  const cancelInspectRef = useRef(false)

  const latestChapter = book.chapters.at(-1)
  const availableSources = choices.length

  const runInspection = useCallback((forceRefresh = false) => {
    abortControllerRef.current?.abort()
    const controller = new AbortController()
    abortControllerRef.current = controller
    cancelInspectRef.current = false
    const rawSources = choices.map(c => c.result)
    if (forceRefresh) {
      clearStoredInspections(rawSources)
      setInspections(getInitialOrStoredInspections(rawSources))
    }
    setChecking(true)
    void inspectAllSourcesConcurrently(
      rawSources,
      3,
      updated => {
        if (!cancelInspectRef.current && !controller.signal.aborted) {
          setInspections(updated)
        }
      },
      () => cancelInspectRef.current || controller.signal.aborted,
      undefined,
      controller.signal
    ).finally(() => {
      if (!cancelInspectRef.current && !controller.signal.aborted) {
        setChecking(false)
      }
    })
  }, [choices])

  useEffect(() => {
    runInspection()
    return () => {
      cancelInspectRef.current = true
      abortControllerRef.current?.abort()
    }
  }, [runInspection])

  const handleClose = useCallback(() => {
    cancelInspectRef.current = true
    abortControllerRef.current?.abort()
    onClose()
  }, [onClose])

  const handleOpenReader = (idx: number) => {
    cancelInspectRef.current = true
    abortControllerRef.current?.abort()
    onOpen(idx)
  }

  useEffect(() => {
    let active = true
    void api.bookshelf().then(items => {
      if (!active) return
      const found = items.some(
        item => (item.sourceId === book.details.sourceId && item.bookUrl === book.bookUrl) ||
                (cleanTitle(item.name).toLowerCase() === cleanTitle(book.details.name).toLowerCase() &&
                 (cleanAuthor(item.author) || '').toLowerCase() === (cleanAuthor(book.details.author) || '').toLowerCase())
      )
      setInShelf(found)
    }).catch(() => {
      if (active) setInShelf(false)
    })
    return () => {
      active = false
    }
  }, [book.details.sourceId, book.bookUrl, book.details.name, book.details.author])

  const toggleShelf = async () => {
    if (shelfBusy) return
    setShelfBusy(true)
    try {
      if (inShelf) {
        await api.removeFromBookshelf(book.details.sourceId, book.bookUrl)
        setInShelf(false)
        toast.info(`《${book.details.name}》已移出书架`)
      } else {
        const fallbackCover = book.details.coverUrl || choices.find(c => c.result.coverUrl?.trim())?.result.coverUrl?.trim()
        await api.addToBookshelf({
          sourceId: book.details.sourceId,
          bookUrl: book.bookUrl,
          name: book.details.name,
          author: book.details.author,
          tocUrl: book.details.tocUrl,
          coverUrl: fallbackCover || undefined,
          alternateSources: choices.map(c => c.result).filter(s => s.sourceId !== book.details.sourceId || s.bookUrl !== book.bookUrl),
        })
        setInShelf(true)
        toast.success(`《${book.details.name}》已加入书架`)
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '操作书架失败')
    } finally {
      setShelfBusy(false)
    }
  }

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        handleClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [handleClose])

  const handleSelectChoice = (choice: SourceChoice) => {
    const key = `${choice.result.sourceId}\u0000${choice.result.bookUrl}`
    const insp = inspections.get(key)
    const fallbackCover = book.details.coverUrl || choices.find(c => c.result.coverUrl?.trim())?.result.coverUrl?.trim()
    const fallbackIntro = book.details.intro || choices.find(c => c.result.intro?.trim())?.result.intro?.trim()

    if (insp?.book) {
      const mergedBook: OpenBook = {
        ...insp.book,
        details: {
          ...insp.book.details,
          name: (cleanTitle(insp.book.details.name) && cleanTitle(insp.book.details.name) !== '未命名书籍' && cleanTitle(insp.book.details.name) !== '未知书名')
            ? cleanTitle(insp.book.details.name)
            : book.details.name,
          author: cleanAuthor(insp.book.details.author) || book.details.author,
          coverUrl: insp.book.details.coverUrl || fallbackCover || undefined,
          intro: insp.book.details.intro || fallbackIntro || undefined,
          alternateSources: choices.map(c => c.result).filter(s => s.sourceId !== choice.result.sourceId || s.bookUrl !== choice.result.bookUrl),
        }
      }
      onChooseSource({ ...choice, book: mergedBook, status: 'loaded' })
    } else {
      onChooseSource(choice)
    }
  }

  return (
    <div className="modal-backdrop" onClick={handleClose}>
      <div
        className="book-detail-modal"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`书籍详情: ${book.details.name}`}
      >
        <header className="book-detail-modal-header">
          <div className="book-detail-heading">
            {sanitizeImageUrl(book.details.coverUrl) ? (
              <img className="book-detail-cover" src={sanitizeImageUrl(book.details.coverUrl)!} alt="" referrerPolicy="no-referrer" />
            ) : (
              <div className="book-detail-cover-placeholder">{book.details.name.slice(0, 1)}</div>
            )}
            <div className="book-detail-info">
              <span className="section-kicker">书籍详情</span>
              <h2>{book.details.name}</h2>
              <p>{book.details.author || '未知作者'}</p>
              <div className="book-detail-stats">
                <span>{book.chapters.length} 章</span>
                <span>{availableSources || 1} 个可用书源</span>
                {latestChapter && <span>最新：{latestChapter.title}</span>}
              </div>
            </div>
          </div>
          <div className="book-detail-header-actions">
            <button
              type="button"
              className={`subtle-button shelf-toggle-btn ${inShelf ? 'in-shelf' : ''}`}
              onClick={toggleShelf}
              disabled={shelfBusy || inShelf === null}
              title={inShelf ? '从书架中移出' : '加入书架'}
            >
              <Icon name="book" />
              <span>{inShelf ? '已在书架' : '加入书架'}</span>
            </button>
            <button className="primary-button read-btn" onClick={() => handleOpenReader(resumeIndex)}>
              {book.progress ? '继续阅读' : '开始阅读'}
              <Icon name="arrowRight" />
            </button>
            <button className="subtle-button close-btn" onClick={handleClose} aria-label="关闭">
              <Icon name="close" />
            </button>
          </div>
        </header>

        <div className="book-detail-modal-body">
          {choices.length > 1 && (
            <div className="book-detail-section">
              <SourceChoiceList
                choices={choices}
                inspections={inspections}
                active={book.bookUrl}
                onChoose={handleSelectChoice}
                onRecheck={() => runInspection(true)}
                checking={checking}
              />
            </div>
          )}

          {book.details.intro && (
            <div className="book-detail-section">
              <div className={`book-intro ${introExpanded ? 'expanded' : ''}`}>
                <p>{book.details.intro}</p>
                {book.details.intro.length > 120 && (
                  <button className="intro-toggle" onClick={() => setIntroExpanded(value => !value)}>
                    {introExpanded ? '收起简介' : '展开简介'}
                  </button>
                )}
              </div>
            </div>
          )}

          <div className="book-detail-section">
            <div className="book-detail-section-title">
              <span>目录预览</span>
              <small>共 {book.chapters.length} 章</small>
            </div>
            <div className="preview-chapters">
              {book.chapters.slice(0, 16).map(item => (
                <button
                  className={item.index === resumeIndex ? 'resume-chapter' : ''}
                  key={item.url}
                  onClick={() => handleOpenReader(item.index)}
                >
                  <span>{item.title}</span>
                  {item.index === resumeIndex && book.progress && <small>上次阅读</small>}
                </button>
              ))}
            </div>
            {book.chapters.length > 16 && (
              <p className="chapter-count">共 {book.chapters.length} 章，进入阅读器查看完整目录</p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}




function SourceEditor({
  selected,
  onSaved,
  onOpenLogin,
}: {
  selected: SourceSummary | null
  onSaved: () => void
  onOpenLogin?: (source: SourceSummary) => void
}) {
  const [record, setRecord] = useState<SourceRecord | null>(null); const [text, setText] = useState(''); const [status, setStatus] = useState('')
  useEffect(() => { if (!selected) { setRecord(null); setText(''); return }; void api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message)) }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="source-editor empty-editor"><Icon name="book" /><h2>选择一个书源</h2><p>从列表选择书源，或导入一个 JSON 文件。</p></section>
  return (
    <section className="source-editor">
      <header>
        <div>
          <span className="section-kicker">书源编辑</span>
          <div className="source-editor-name-row">
            <h2>{selected.name}</h2>
            {selected.hasLogin && <span className="source-login-badge" title="支持登录鉴权">登录</span>}
          </div>
          <small>{selected.url}</small>
        </div>
        <div className="editor-actions">
          {selected.hasLogin && (
            <button
              type="button"
              className="subtle-button source-login-entry-btn"
              onClick={() => onOpenLogin?.(selected)}
            >
              登录
            </button>
          )}
          <button type="button" className="subtle-button" onClick={() => void validate()}>校验</button>
          <button type="button" className="primary-button" onClick={() => void save()}>保存</button>
        </div>
      </header>
      <textarea aria-label="书源 JSON 编辑器" value={text} onChange={event => setText(event.target.value)} spellCheck={false} />
      {status && <footer className={status.includes('失败') || status.includes('错误') ? 'form-error' : ''}>{status}</footer>}
    </section>
  )
}

function SubscriptionPanel({ onSourcesChange }: { onSourcesChange: () => void }) {
  const [items, setItems] = useState<SourceSubscription[]>([]); const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState<number | 'all' | null>(null)
  const load = useCallback(async () => { try { setItems(await api.subscriptions()) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入订阅') } }, [])
  useEffect(() => { void load() }, [load])
  const add = async (event: FormEvent) => { event.preventDefault(); if (!url.trim()) return; setBusy('all'); try { await api.saveSubscription(url.trim()); setUrl(''); setNotice('订阅已保存'); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '保存订阅失败') } finally { setBusy(null) } }
  const update = async (id: number) => { setBusy(id); try { const result = await api.updateSubscription(id); setNotice(`同步完成：新增 ${result.imported || 0}，更新 ${result.updated || 0}，跳过 ${result.skipped || 0}`); onSourcesChange(); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '同步失败') } finally { setBusy(null) } }
  const updateAll = async () => { setBusy('all'); try { const result = await api.updateSubscriptions(); setNotice(`全部同步完成：成功 ${result.updated}，失败 ${result.failed}`); onSourcesChange(); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '同步失败') } finally { setBusy(null) } }
  const toggle = async (item: SourceSubscription) => { setBusy(item.id); try { await api.saveSubscription(item.url, !item.enabled); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '更新失败') } finally { setBusy(null) } }
  const remove = async (item: SourceSubscription) => { if (!confirm(`删除订阅“${item.url}”？已导入的书源会保留。`)) return; setBusy(item.id); try { await api.removeSubscription(item.id); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } finally { setBusy(null) } }
  return <section className="subscription-panel"><header><div><span className="section-kicker">自动更新</span><h2>书源订阅</h2><p>每 6 小时自动同步；同一地址的书源会统一覆盖更新。</p></div><button className="subtle-button" onClick={() => void updateAll()} disabled={busy !== null}>{busy === 'all' ? '同步中...' : '全部同步'}</button></header><form onSubmit={add}><input value={url} onChange={event => setUrl(event.target.value)} placeholder="https://example.com/sources.json" inputMode="url" /><button className="primary-button" disabled={busy !== null}>添加订阅</button></form>{notice && <p className="sidebar-notice">{notice}</p>}<div className="subscription-list">{items.length === 0 ? <p>还没有书源订阅。</p> : items.map(item => <article key={item.id}><div><strong>{item.url}</strong><small>{item.lastError ? `最近失败：${item.lastError}` : item.lastSuccessAt ? `最近同步：${new Date(item.lastSuccessAt).toLocaleString()}，处理 ${item.lastImported} 个书源` : '尚未同步'}</small></div><div className="subscription-actions"><button className="subtle-button" onClick={() => void toggle(item)} disabled={busy !== null}>{item.enabled ? '已启用' : '已停用'}</button><button className="subtle-button" onClick={() => void update(item.id)} disabled={busy !== null || !item.enabled}>{busy === item.id ? '同步中...' : '立即同步'}</button><button className="shelf-remove" aria-label="删除订阅" onClick={() => void remove(item)} disabled={busy !== null}><Icon name="close" /></button></div></article>)}</div></section>
}

function SubscriptionPage({ onSourcesChange }: { onSourcesChange: () => void }) {
  return <main className="subscription-page"><header className="page-title"><div><span className="section-kicker">自动更新</span><h1>书源订阅</h1><p>从远程 JSON 订阅书源，并定时保持最新。</p></div></header><SubscriptionPanel onSourcesChange={onSourcesChange} /></main>
}

function SourcesPage({ selected, onSelect, onSourcesChange }: { selected: SourceSummary | null; onSelect: (source: SourceSummary | null) => void; onSourcesChange: (sources: SourceSummary[]) => void }) {
  const [sources, setSources] = useState<SourceSummary[]>([])
  const [query, setQuery] = useState('')
  const [groupFilter, setGroupFilter] = useState('')
  const [notice, setNotice] = useState('')
  const [importing, setImporting] = useState(false)
  const [isBatchMode, setIsBatchMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [showHealthModal, setShowHealthModal] = useState(false)
  const [showGroupModal, setShowGroupModal] = useState(false)
  const [busyBatch, setBusyBatch] = useState(false)
  const [loginModalSource, setLoginModalSource] = useState<SourceSummary | null>(null)

  const load = useCallback(async () => {
    try {
      const values = await api.sources(query)
      setSources(values)
      onSourcesChange(values)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '无法载入书源')
    }
  }, [onSourcesChange, query])

  useEffect(() => {
    const timer = window.setTimeout(() => { void load() }, 180)
    return () => window.clearTimeout(timer)
  }, [load])

  const allGroups = useMemo(() => {
    const set = new Set<string>()
    for (const s of sources) {
      if (s.group && s.group.trim()) set.add(s.group.trim())
    }
    return Array.from(set).sort()
  }, [sources])

  const filteredSources = useMemo(() => {
    return sources.filter(source => {
      if (groupFilter) {
        if (groupFilter === '__none__' && source.group) return false
        if (groupFilter !== '__none__' && (source.group ?? '') !== groupFilter) return false
      }
      if (query) {
        const q = query.toLowerCase()
        return source.name.toLowerCase().includes(q) || source.url.toLowerCase().includes(q) || (source.group ?? '').toLowerCase().includes(q)
      }
      return true
    })
  }, [sources, groupFilter, query])

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleSelectAll = () => {
    setSelectedIds(new Set(filteredSources.map(s => s.id)))
  }

  const handleClearAll = () => {
    setSelectedIds(new Set())
  }

  const handleInvertSelection = () => {
    setSelectedIds(prev => {
      const next = new Set<string>()
      for (const s of filteredSources) {
        if (!prev.has(s.id)) next.add(s.id)
      }
      return next
    })
  }

  const handleBatchEnable = async () => {
    if (selectedIds.size === 0) return
    setBusyBatch(true)
    try {
      const resp = await api.batchSources('enable', Array.from(selectedIds))
      toast.success(resp.message || `已批量启用 ${resp.affected} 个书源`)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '启用失败')
    } finally {
      setBusyBatch(false)
    }
  }

  const handleBatchDisable = async () => {
    if (selectedIds.size === 0) return
    setBusyBatch(true)
    try {
      const resp = await api.batchSources('disable', Array.from(selectedIds))
      toast.success(resp.message || `已批量停用 ${resp.affected} 个书源`)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '停用失败')
    } finally {
      setBusyBatch(false)
    }
  }

  const handleBatchDelete = async () => {
    if (selectedIds.size === 0) return
    const selectedSources = sources.filter(s => selectedIds.has(s.id))
    const namesSummary = selectedSources.slice(0, 3).map(s => s.name).join('、') + (selectedSources.length > 3 ? ` 等共 ${selectedSources.length} 项` : '')
    if (!confirm(`确定要彻底删除选中的 ${selectedIds.size} 个书源吗？\n（${namesSummary}）\n删除后不可恢复！`)) return
    setBusyBatch(true)
    try {
      const resp = await api.batchSources('delete', Array.from(selectedIds))
      toast.success(resp.message || `已删除 ${resp.affected} 个书源`)
      setSelectedIds(new Set())
      if (selected && selectedIds.has(selected.id)) {
        onSelect(null)
      }
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '删除失败')
    } finally {
      setBusyBatch(false)
    }
  }

  const handleBatchExport = async () => {
    if (selectedIds.size === 0) return
    setBusyBatch(true)
    try {
      const exportStrings = await api.exportSources(Array.from(selectedIds))
      const parsedList = exportStrings.map(str => {
        try { return JSON.parse(str) } catch { return null }
      }).filter(Boolean)
      const blob = new Blob([JSON.stringify(parsedList, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `legado_sources_export_${new Date().toISOString().slice(0, 10)}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      toast.success(`已成功导出 ${parsedList.length} 个书源`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '导出失败')
    } finally {
      setBusyBatch(false)
    }
  }

  const handleBatchGroupConfirm = async (newGroup: string | null) => {
    if (selectedIds.size === 0) return
    setBusyBatch(true)
    try {
      const resp = await api.batchSources('set_group', Array.from(selectedIds), newGroup ?? undefined)
      toast.success(resp.message || `已更新 ${resp.affected} 个书源的分组`)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '修改分组失败')
    } finally {
      setBusyBatch(false)
    }
  }

  const importSources = async (file: File | undefined) => {
    if (!file) return
    setImporting(true)
    try {
      const rawText = await file.text()
      const rawList = parseSourceJsonText(rawText)
      if (rawList.length === 0) {
        toast.error('未在文件中识别到有效的书源配置')
        setNotice('导入失败：未在文件中识别到有效的书源配置')
        return
      }
      const serialized = rawList
        .filter(item => item && typeof item === 'object')
        .map(item => JSON.stringify(item))
      if (serialized.length === 0) {
        toast.error('书源格式无效')
        setNotice('导入失败：书源格式无效')
        return
      }
      const result = await api.import(serialized)
      const importedCount = result.imported || 0
      const updatedCount = result.updated || 0
      const skippedCount = result.skipped || 0
      const parts = [`新增 ${importedCount} 个`, `更新 ${updatedCount} 个`]
      if (skippedCount > 0) parts.push(`跳过 ${skippedCount} 个`)
      let msg = `导入完成：${parts.join('，')}`
      if (result.errors && result.errors.length > 0) {
        const errorSummary = result.errors.slice(0, 3).join('；') + (result.errors.length > 3 ? ` 等共 ${result.errors.length} 项错误` : '')
        msg += ` (${errorSummary})`
      }
      setNotice(msg)
      if (importedCount > 0 || updatedCount > 0) {
        toast.success(`已导入/更新 ${importedCount + updatedCount} 个书源`)
      } else if (skippedCount > 0) {
        toast.error(result.errors?.[0] || '书源未能导入，请检查格式')
      }
      await load()
    } catch (error) {
      const errMsg = error instanceof Error ? error.message : '导入失败'
      toast.error(errMsg)
      setNotice(`导入失败：${errMsg}`)
    } finally {
      setImporting(false)
    }
  }

  const remove = async () => {
    if (!selected || !confirm(`删除“${selected.name}”？`)) return
    try {
      await api.remove(selected.id)
      onSelect(null)
      await load()
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '删除失败')
    }
  }

  return (
    <main className={`sources-page ${isBatchMode ? 'batch-mode-active' : ''}`}>
      <aside className="source-sidebar">
        <div className="source-sidebar-heading">
          <div className="source-sidebar-title-row">
            <span>书源</span>
            <small>{sources.length}</small>
          </div>
          <div className="source-sidebar-top-actions">
            <button
              type="button"
              className="subtle-button icon-button health-probe-btn"
              title="书源连通性体检"
              onClick={() => setShowHealthModal(true)}
            >
              <Icon name="activity" />
              <span>体检</span>
            </button>
            <button
              type="button"
              className={`subtle-button ${isBatchMode ? 'active-batch-btn' : ''}`}
              onClick={() => {
                setIsBatchMode(prev => !prev)
                setSelectedIds(new Set())
              }}
            >
              {isBatchMode ? '退出批量' : '批量管理'}
            </button>
          </div>
        </div>

        {allGroups.length > 0 && (
          <div className="source-group-filter-bar">
            <select
              className="source-group-select"
              value={groupFilter}
              onChange={e => setGroupFilter(e.target.value)}
              aria-label="按分组筛选书源"
            >
              <option value="">全部分组 ({sources.length})</option>
              {allGroups.map(g => (
                <option key={g} value={g}>
                  {g} ({sources.filter(s => s.group === g).length})
                </option>
              ))}
              <option value="__none__">
                未分组 ({sources.filter(s => !s.group).length})
              </option>
            </select>
          </div>
        )}

        <div className="source-filter">
          <Icon name="search" />
          <input placeholder="筛选书源" value={query} onChange={event => setQuery(event.target.value)} />
        </div>

        <label className={`import-button ${importing ? 'disabled' : ''}`}>
          <Icon name="upload" />
          {importing ? '导入中...' : '导入 JSON'}
          <input
            type="file"
            accept="application/json,.json,text/plain,.txt,*"
            disabled={importing}
            onChange={event => {
              const file = event.target.files?.[0]
              event.target.value = ''
              void importSources(file)
            }}
          />
        </label>

        {notice && <p className="sidebar-notice">{notice}</p>}

        <nav className="source-list">
          {filteredSources.map(source => {
            const isChecked = selectedIds.has(source.id)
            const isCurrent = selected?.id === source.id

            if (isBatchMode) {
              return (
                <div
                  key={source.id}
                  className={`source-list-item batch-item ${isChecked ? 'checked' : ''}`}
                  onClick={() => toggleSelect(source.id)}
                >
                  <label className="batch-checkbox-wrap" onClick={e => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={isChecked}
                      onChange={() => toggleSelect(source.id)}
                    />
                  </label>
                  <div className="source-list-item-content">
                    <div className="source-list-item-title">
                      <span>{source.name}</span>
                      {!source.enabled && <span className="source-disabled-badge">已停用</span>}
                      {source.hasLogin && <span className="source-login-badge" title="支持登录鉴权">登录</span>}
                    </div>
                    <small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small>
                  </div>
                </div>
              )
            }

            return (
              <button
                className={isCurrent ? 'selected' : ''}
                key={source.id}
                type="button"
                onClick={() => onSelect(source)}
              >
                <div className="source-list-item-title">
                  <span>{source.name}</span>
                  {!source.enabled && <span className="source-disabled-badge">已停用</span>}
                  {source.hasLogin && <span className="source-login-badge" title="支持登录鉴权">登录</span>}
                </div>
                <small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small>
              </button>
            )
          })}
        </nav>
      </aside>

      <section className="sources-content">
        <header className="page-title">
          <div>
            <span className="section-kicker">阅读服务器</span>
            <h1>书源管理</h1>
            <p>导入、校验与维护你的阅读来源。</p>
          </div>
          <div className="page-title-actions">
            {selected && (
              <button type="button" className="danger-button" onClick={() => void remove()}>
                删除书源
              </button>
            )}
          </div>
        </header>
        <SourceEditor
          selected={selected}
          onSaved={() => void load()}
          onOpenLogin={source => setLoginModalSource(source)}
        />
      </section>

      {/* Floating Batch Action Bar */}
      {isBatchMode && (
        <aside className="source-batch-bar" aria-label="书源批量操作工具栏">
          <div className="batch-bar-left">
            <span className="batch-bar-count">
              已选 <strong>{selectedIds.size}</strong> / {filteredSources.length}
            </span>
            <div className="batch-select-helpers">
              <button type="button" className="subtle-button compact" onClick={handleSelectAll}>
                全选
              </button>
              <button type="button" className="subtle-button compact" onClick={handleClearAll}>
                全不选
              </button>
              <button type="button" className="subtle-button compact" onClick={handleInvertSelection}>
                反选
              </button>
            </div>
          </div>
          <div className="batch-bar-right">
            <button
              type="button"
              className="subtle-button"
              disabled={selectedIds.size === 0 || busyBatch}
              onClick={() => void handleBatchEnable()}
            >
              批量启用
            </button>
            <button
              type="button"
              className="subtle-button"
              disabled={selectedIds.size === 0 || busyBatch}
              onClick={() => void handleBatchDisable()}
            >
              批量停用
            </button>
            <button
              type="button"
              className="subtle-button"
              disabled={selectedIds.size === 0 || busyBatch}
              onClick={() => setShowGroupModal(true)}
            >
              修改分组
            </button>
            <button
              type="button"
              className="subtle-button"
              disabled={selectedIds.size === 0 || busyBatch}
              onClick={() => void handleBatchExport()}
            >
              导出选中
            </button>
            <button
              type="button"
              className="danger-button"
              disabled={selectedIds.size === 0 || busyBatch}
              onClick={() => void handleBatchDelete()}
            >
              批量删除
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={() => {
                setIsBatchMode(false)
                setSelectedIds(new Set())
              }}
            >
              完成
            </button>
          </div>
        </aside>
      )}

      {showHealthModal && (
        <SourceHealthModal
          sources={sources}
          onClose={() => setShowHealthModal(false)}
          onSourcesChange={() => void load()}
          onToast={(msg, type) => {
            if (type === 'error') toast.error(msg)
            else if (type === 'success') toast.success(msg)
            else toast.info(msg)
          }}
        />
      )}

      {showGroupModal && (
        <SourceGroupModal
          selectedCount={selectedIds.size}
          existingGroups={allGroups}
          onConfirm={handleBatchGroupConfirm}
          onClose={() => setShowGroupModal(false)}
        />
      )}

      {loginModalSource && (
        <SourceLoginModal
          sourceId={loginModalSource.id}
          sourceName={loginModalSource.name}
          onClose={() => setLoginModalSource(null)}
          onToast={(msg, type) => {
            if (type === 'error') toast.error(msg)
            else if (type === 'success') toast.success(msg)
            else toast.info(msg)
          }}
        />
      )}
    </main>
  )
}

function HighlightText({ text, keyword }: { text: string; keyword: string }) {
  const cleanK = cleanTitle(keyword).trim()
  if (!cleanK || !text) return <>{text}</>

  const escaped = cleanK.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const parts = text.split(new RegExp(`(${escaped})`, 'gi'))

  return (
    <>
      {parts.map((part, i) =>
        part.toLowerCase() === cleanK.toLowerCase() ? (
          <mark key={i} className="search-highlight">{part}</mark>
        ) : (
          part
        )
      )}
    </>
  )
}

function LibraryPage({ sources, onOpen }: { sources: SourceSummary[]; onOpen: (book: OpenBook, index: number) => void }) {
  const search = useSearchStore()
  const handleSearch = (event: FormEvent) => {
    event.preventDefault()
    search.startSearch()
  }
  const resumeIndex = search.openBook?.progress ? Math.min(Math.max(search.openBook.progress.chapterIndex, 0), Math.max(0, search.openBook.chapters.length - 1)) : 0
  const groups = useMemo(() => groupSearchResults(search.results), [search.results])
  const visibleGroups = useMemo(() => filterSearchGroups(groups, search.filters, search.keyword), [groups, search.filters, search.keyword])
  const hasFilters = search.filters.query || search.filters.minimumSources > 1 || search.filters.withIntro || search.filters.withCover || search.filters.sortMode !== 'smart'
  const completed = search.progress?.completedSources ?? 0
  const total = search.progress?.totalSources ?? 0
  const failures = search.progress?.failedSources ?? 0
  const empty = search.progress?.emptySources ?? 0

  return (
    <main className="library-page">
      <section className="library-hero">
        <span className="section-kicker">在线书库</span>
        <h1>找一本书，安静地读下去。</h1>
        <p>从已配置的书源搜索并继续上次阅读。</p>
        <form onSubmit={handleSearch}>
          <Icon name="search" />
          <input
            value={search.keyword}
            onChange={event => search.setKeyword(event.target.value)}
            placeholder="输入书名或作者"
          />
          {search.loading ? (
            <button
              key="stop-search-btn"
              type="button"
              className="primary-button"
              onClick={e => {
                e.preventDefault()
                e.stopPropagation()
                search.stopSearch()
              }}
            >
              停止搜索
            </button>
          ) : (
            <button
              key="start-search-btn"
              type="submit"
              className="primary-button"
            >
              搜索
            </button>
          )}
        </form>
        <label className="library-source-select">
          搜索范围
          <select
            value={search.selectedSourceId}
            onChange={event => search.setSelectedSourceId(event.target.value)}
          >
            <option value="">全部书源</option>
            {sources.map(source => (
              <option key={source.id} value={source.id}>{source.name}</option>
            ))}
          </select>
        </label>
        {(search.loading || search.stopped) && (
          <p className="library-search-status">
            {search.stopped
              ? `已停止，已获得 ${groups.length} 本书。`
              : `已获得 ${groups.length} 本书，已检查 ${completed} / ${total} 个书源${failures || empty ? `，失败 ${failures} 个、无结果 ${empty} 个` : ''}。`}
          </p>
        )}
      </section>
      {search.message && <p className="form-error library-message">{search.message}</p>}
      {groups.length > 0 && (
        <>
          <section className="library-result-filters" aria-label="筛选搜索结果">
            <label>
              <Icon name="search" />
              <input
                value={search.filters.query}
                onChange={event => search.setFilters(current => ({ ...current, query: event.target.value }))}
                placeholder="筛选书名或作者"
              />
            </label>
            <label>
              排序
              <select
                value={search.filters.sortMode}
                onChange={event => search.setFilters(current => ({ ...current, sortMode: event.target.value as SortMode }))}
              >
                <option value="smart">智能推荐</option>
                <option value="sources">书源最多</option>
                <option value="exact">精准优先</option>
                <option value="name">书名 A-Z</option>
              </select>
            </label>
            <label>
              书源
              <select
                value={search.filters.minimumSources}
                onChange={event => search.setFilters(current => ({ ...current, minimumSources: Number(event.target.value) as SearchFilters['minimumSources'] }))}
              >
                <option value={1}>全部</option>
                <option value={2}>2 个及以上</option>
                <option value={3}>3 个及以上</option>
              </select>
            </label>
            <label>
              <input
                type="checkbox"
                checked={search.filters.withIntro}
                onChange={event => search.setFilters(current => ({ ...current, withIntro: event.target.checked }))}
              />
              有简介
            </label>
            <label>
              <input
                type="checkbox"
                checked={search.filters.withCover}
                onChange={event => search.setFilters(current => ({ ...current, withCover: event.target.checked }))}
              />
              有封面
            </label>
          </section>
          <section className="library-results">
            <header>
              <h2>搜索结果</h2>
              <small>{visibleGroups.length} 本书{hasFilters && ` / ${groups.length}`}</small>
            </header>
            <div className="library-results-grid">
              {visibleGroups.map(group => {
                const isExact = isExactMatch(group, search.keyword)
                const isPopular = isPopularMatch(group)
                return (
                  <button
                    key={group.key}
                    className={`search-result-item ${isExact ? 'exact-match' : ''}`}
                    onClick={() => void search.openGroup(group)}
                  >
                    <span className="result-mark"><Icon name="book" /></span>
                    <span className="result-main-info">
                      <div className="result-title-row">
                        <strong><HighlightText text={group.name} keyword={search.keyword} /></strong>
                        {isExact && <span className="result-badge result-badge-exact">精准匹配</span>}
                        {isPopular && <span className="result-badge result-badge-popular">{group.sources.length} 源</span>}
                      </div>
                      <small>
                        <HighlightText text={group.author || '未知作者'} keyword={search.keyword} />
                        {!isPopular && ` · ${group.sources.length} 个书源`}
                      </small>
                    </span>
                    <Icon name="arrowRight" />
                  </button>
                )
              })}
            </div>
            {visibleGroups.length === 0 && <p className="library-filter-empty">没有符合当前筛选条件的书籍。</p>}
          </section>
        </>
      )}
      {search.openBook && (
        <BookDetailModal
          book={search.openBook}
          choices={search.choices}
          resumeIndex={resumeIndex}
          onOpen={index => onOpen(search.openBook!, index)}
          onChooseSource={choice => void search.chooseSource(choice)}
          onClose={() => search.setOpenBook(null)}
        />
      )}
    </main>
  )
}

function BookInfoEditModal({
  item,
  groups,
  onSaved,
  onClose,
}: {
  item: BookshelfItem
  groups: BookGroup[]
  onSaved: (updated: BookshelfItem) => void
  onClose: () => void
}) {
  const [name, setName] = useState(item.name)
  const [author, setAuthor] = useState(item.author || '')
  const [groupName, setGroupName] = useState<string | undefined>(item.groupName)
  const [coverUrl, setCoverUrl] = useState<string | null>(null) // null = keep existing, '' = clear, string = new URL
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const candidateCovers = useMemo(() => {
    const map = new Map<string, { sourceId: string; coverUrl: string }>()
    for (const alt of item.alternateSources || []) {
      const url = sanitizeImageUrl(alt.coverUrl)
      if (url && !map.has(url)) {
        map.set(url, { sourceId: alt.sourceId, coverUrl: url })
      }
    }
    return Array.from(map.values())
  }, [item.alternateSources])

  const previewSrc = useMemo(() => {
    if (coverUrl === '') return null
    if (coverUrl) return sanitizeImageUrl(coverUrl)
    if (item.coverKey) return api.cover(item.coverKey)
    return null
  }, [coverUrl, item.coverKey])

  const handleSave = async (e: FormEvent) => {
    e.preventDefault()
    const trimmedName = name.trim()
    if (!trimmedName) {
      setError('书名不能为空')
      return
    }

    setSaving(true)
    setError('')
    try {
      const updated = await api.updateBookshelfInfo({
        sourceId: item.sourceId,
        bookUrl: item.bookUrl,
        name: trimmedName,
        author: author.trim() || undefined,
        coverUrl: coverUrl === null ? undefined : coverUrl,
        groupName: groupName || undefined,
      })
      toast.success(`《${updated.name}》信息已更新`)
      onSaved(updated)
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : '更新书籍信息失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div
        className="book-info-edit-modal"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`编辑书籍信息: ${item.name}`}
      >
        <header className="book-info-edit-header">
          <div>
            <span className="section-kicker">书籍信息</span>
            <h2>编辑书籍信息</h2>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>

        <form onSubmit={handleSave} className="book-info-edit-form">
          <div className="book-info-edit-body">
            {/* Cover Preview & Options */}
            <div className="edit-cover-section">
              <div className="edit-cover-preview-box">
                {previewSrc && sanitizeImageUrl(previewSrc) ? (
                  <img src={sanitizeImageUrl(previewSrc)!} alt="封面预览" referrerPolicy="no-referrer" />
                ) : (
                  <div className="edit-cover-fallback">
                    <span>{name.trim().slice(0, 1) || '书'}</span>
                  </div>
                )}
                {coverUrl !== '' && (item.coverKey || coverUrl) && (
                  <button
                    type="button"
                    className="subtle-button clear-cover-btn"
                    onClick={() => setCoverUrl('')}
                    title="清除并使用文字占位封面"
                  >
                    清除封面
                  </button>
                )}
                {coverUrl === '' && (
                  <button
                    type="button"
                    className="subtle-button reset-cover-btn"
                    onClick={() => setCoverUrl(null)}
                    title="恢复原封面"
                  >
                    恢复原封面
                  </button>
                )}
              </div>

              <div className="edit-cover-inputs">
                <label className="edit-form-label">
                  <span>封面图片 URL</span>
                  <div className="input-with-icon">
                    <Icon name="image" />
                    <input
                      type="url"
                      placeholder="https://example.com/cover.jpg"
                      value={coverUrl === null ? '' : coverUrl}
                      onChange={e => setCoverUrl(e.target.value)}
                    />
                  </div>
                  <small>可粘贴图片网络地址，保存时将自动拉取并缓存到服务器</small>
                </label>

                {candidateCovers.length > 0 && (
                  <div className="candidate-covers-picker">
                    <span className="candidate-covers-title">从备选书源选择封面 ({candidateCovers.length})：</span>
                    <div className="candidate-covers-grid">
                      {candidateCovers.map(c => {
                        const isSelected = coverUrl === c.coverUrl
                        return (
                          <button
                            key={c.coverUrl}
                            type="button"
                            className={`candidate-cover-card ${isSelected ? 'selected' : ''}`}
                            onClick={() => setCoverUrl(c.coverUrl)}
                            title={`使用来自【${c.sourceId}】的封面`}
                          >
                            {sanitizeImageUrl(c.coverUrl) ? (
                              <img src={sanitizeImageUrl(c.coverUrl)!} alt={c.sourceId} referrerPolicy="no-referrer" />
                            ) : (
                              <div className="candidate-cover-fallback">
                                <span>{(c.sourceId || '书').slice(0, 1)}</span>
                              </div>
                            )}
                            <small>{c.sourceId}</small>
                          </button>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Title & Author & Group Fields */}
            <div className="edit-fields-section">
              <label className="edit-form-label">
                <span>书名 <span className="required-mark">*</span></span>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="请输入书名"
                />
              </label>

              <label className="edit-form-label">
                <span>作者</span>
                <input
                  type="text"
                  value={author}
                  onChange={e => setAuthor(e.target.value)}
                  placeholder="作者（可选）"
                />
              </label>

              <label className="edit-form-label">
                <span>所属分组</span>
                <select
                  value={groupName || ''}
                  onChange={e => setGroupName(e.target.value || undefined)}
                  className="edit-select-input"
                >
                  <option value="">(未分组)</option>
                  {groups.map(g => (
                    <option key={g.id} value={g.name}>{g.name}</option>
                  ))}
                </select>
              </label>
            </div>

            {error && <p className="form-error">{error}</p>}
          </div>

          <footer className="book-info-edit-footer">
            <button type="button" className="subtle-button" onClick={onClose} disabled={saving}>
              取消
            </button>
            <button type="submit" className="primary-button" disabled={saving}>
              {saving ? '保存中...' : '保存修改'}
            </button>
          </footer>
        </form>
      </div>
    </div>
  )
}

function BookManageModal({
  item,
  groups,
  onClose,
  onSwitchSource,
  onCache,
  onCancelCache,
  onToggleCompleted,
  onUpdateInfo,
  onRemove,
}: {
  item: BookshelfItem
  groups: BookGroup[]
  onClose: () => void
  onSwitchSource: () => void
  onCache: () => void
  onCancelCache: () => void
  onToggleCompleted: () => void
  onUpdateInfo: (updated: BookshelfItem) => void
  onRemove: () => void
}) {
  const [editingInfo, setEditingInfo] = useState(false)
  const isCaching = item.cacheState === 'caching'
  const isReady = item.cacheState === 'ready'
  const isFailed = item.cacheState === 'failed'
  const percent = Math.min(100, Math.round((item.cachedChapters / Math.max(1, item.totalChapters || 1)) * 100))

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="book-manage-sheet" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={`书籍管理: ${item.name}`}>
        <header className="manage-sheet-header">
          <div className="manage-sheet-cover">
            {item.coverKey ? <img src={api.cover(item.coverKey)} alt="" /> : <span>{item.name.slice(0, 1)}</span>}
          </div>
          <div className="manage-sheet-info">
            <h3>{item.name}</h3>
            <p>{item.author || '未知作者'}</p>
            <small>{item.completed ? '已读完' : item.chapterIndex === undefined ? '刚加入书架' : `阅读至第 ${item.chapterIndex + 1} 章`}</small>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label="关闭"><Icon name="close" /></button>
        </header>

        <div className="manage-sheet-actions">
          {/* Quick Group Selector */}
          <div className="manage-group-row">
            <div className="action-icon"><Icon name="bookmark" /></div>
            <div className="action-text">
              <strong>所属分组</strong>
              <small>当前：{item.groupName || '未分组'}</small>
            </div>
            <select
              value={item.groupName || ''}
              onChange={async (e) => {
                const newGroup = e.target.value || null
                try {
                  const updated = await api.updateBookGroup(item.sourceId, item.bookUrl, newGroup)
                  onUpdateInfo(updated)
                  toast.success(newGroup ? `已移入「${newGroup}」分组` : '已移入「未分组」')
                } catch (err) {
                  toast.error(err instanceof Error ? err.message : '更新分组失败')
                }
              }}
              className="manage-group-select"
              aria-label="修改所属分组"
            >
              <option value="">未分组</option>
              {groups.map(g => (
                <option key={g.id} value={g.name}>{g.name}</option>
              ))}
            </select>
          </div>

          <button className="manage-action-row" onClick={() => setEditingInfo(true)}>
            <div className="action-icon"><Icon name="edit" /></div>
            <div className="action-text">
              <strong>编辑书籍信息</strong>
              <small>修改书名、作者或更换封面</small>
            </div>
            <Icon name="arrowRight" />
          </button>

          {item.sourceId !== 'loc_book' && (
            <button className="manage-action-row" onClick={() => { onClose(); onSwitchSource() }}>
              <div className="action-icon"><Icon name="sliders" /></div>
              <div className="action-text">
                <strong>切换书源</strong>
                <small>{item.alternateSources?.length ? `已有 ${item.alternateSources.length} 个备选书源，可全网检索` : '在其他书源中搜索匹配并无缝替换'}</small>
              </div>
              <Icon name="arrowRight" />
            </button>
          )}

          {isCaching ? (
            <div className="manage-cache-box">
              <div className="manage-cache-info-row">
                <div className="action-icon"><Icon name="download" /></div>
                <div className="action-text">
                  <strong>正在离线缓存全本</strong>
                  <small>{percent}% ({item.cachedChapters} / {item.totalChapters || '?'}) 章</small>
                </div>
                <button className="subtle-button cancel-cache-link" onClick={onCancelCache}>取消缓存</button>
              </div>
              <div className="manage-cache-bar-track">
                <div className="manage-cache-bar-fill" style={{ width: `${percent}%` }} />
              </div>
            </div>
          ) : (
            <button className="manage-action-row" onClick={() => onCache()}>
              <div className="action-icon"><Icon name="download" /></div>
              <div className="action-text">
                <strong>{isReady ? '重新缓存 / 校验全本' : isFailed ? '重试离线缓存' : '离线缓存全本'}</strong>
                <small>{isReady ? `已离线缓存 ${item.cachedChapters} 章 ✓` : isFailed ? (item.cacheError || '部分章节未缓存，点击重试') : '预先下载全书正文以供离线阅读'}</small>
              </div>
              <Icon name="arrowRight" />
            </button>
          )}

          <button className="manage-action-row" onClick={() => { onClose(); onToggleCompleted() }}>
            <div className="action-icon"><Icon name="check" /></div>
            <div className="action-text">
              <strong>{item.completed ? '恢复为正在阅读' : '标记为已读完'}</strong>
              <small>{item.completed ? '状态恢复为正在阅读' : '状态标记为已读完'}</small>
            </div>
            <Icon name="arrowRight" />
          </button>

          <button className="manage-action-row danger" onClick={() => { onClose(); onRemove() }}>
            <div className="action-icon"><Icon name="close" /></div>
            <div className="action-text">
              <strong>移出书架</strong>
              <small>清除阅读进度与离线缓存</small>
            </div>
            <Icon name="arrowRight" />
          </button>
        </div>
      </div>

      {editingInfo && (
        <BookInfoEditModal
          item={item}
          groups={groups}
          onSaved={updated => {
            onUpdateInfo(updated)
          }}
          onClose={() => setEditingInfo(false)}
        />
      )}
    </div>
  )
}

function GroupManageModal({
  groups,
  onClose,
  onGroupsChanged,
}: {
  groups: BookGroup[]
  onClose: () => void
  onGroupsChanged: () => Promise<void>
}) {
  const [newGroupName, setNewGroupName] = useState('')
  const [adding, setAdding] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editingName, setEditingName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleAddGroup = async (e: FormEvent) => {
    e.preventDefault()
    const trimmed = newGroupName.trim()
    if (!trimmed) return
    setAdding(true)
    setError('')
    try {
      await api.createBookGroup(trimmed)
      setNewGroupName('')
      await onGroupsChanged()
      toast.success(`分组「${trimmed}」创建成功`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建分组失败')
    } finally {
      setAdding(false)
    }
  }

  const handleRename = async (group: BookGroup) => {
    const trimmed = editingName.trim()
    if (!trimmed || trimmed === group.name) {
      setEditingId(null)
      return
    }
    setBusy(true)
    setError('')
    try {
      await api.renameBookGroup(group.name, trimmed)
      setEditingId(null)
      await onGroupsChanged()
      toast.success(`分组已重命名为「${trimmed}」`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '重命名失败')
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (group: BookGroup) => {
    const confirmMsg = group.bookCount > 0
      ? `确定删除分组「${group.name}」吗？组内 ${group.bookCount} 本书籍将自动归入“未分组”，书籍不会被删除。`
      : `确定删除分组「${group.name}」吗？`
    if (!confirm(confirmMsg)) return
    setBusy(true)
    setError('')
    try {
      await api.deleteBookGroup(group.name)
      await onGroupsChanged()
      toast.info(`已删除分组「${group.name}」`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  const handleMove = async (index: number, direction: 'up' | 'down') => {
    const targetIndex = direction === 'up' ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= groups.length) return
    const reordered = [...groups]
    const temp = reordered[index]
    reordered[index] = reordered[targetIndex]
    reordered[targetIndex] = temp
    setBusy(true)
    try {
      await api.reorderBookGroups(reordered.map(g => g.name))
      await onGroupsChanged()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '调整排序失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div className="group-manage-modal" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="管理书架分组">
        <header className="group-manage-header">
          <div>
            <span className="section-kicker">书架分类</span>
            <h2>书架分组管理</h2>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label="关闭"><Icon name="close" /></button>
        </header>

        <div className="group-manage-body">
          <form className="group-add-form" onSubmit={handleAddGroup}>
            <input
              type="text"
              value={newGroupName}
              onChange={e => setNewGroupName(e.target.value)}
              placeholder="输入新分组名称..."
              maxLength={20}
              disabled={adding || busy}
            />
            <button type="submit" className="primary-button" disabled={!newGroupName.trim() || adding || busy}>
              {adding ? '创建中...' : '新建分组'}
            </button>
          </form>

          {error && <p className="form-error">{error}</p>}

          <div className="group-list">
            {groups.length === 0 ? (
              <p className="group-list-empty">暂无自定义分组，创建后可自由归类书籍</p>
            ) : (
              groups.map((group, idx) => (
                <div key={group.id} className="group-item-row">
                  {editingId === group.id ? (
                    <div className="group-item-editing">
                      <input
                        type="text"
                        value={editingName}
                        onChange={e => setEditingName(e.target.value)}
                        autoFocus
                        maxLength={20}
                        onKeyDown={e => {
                          if (e.key === 'Enter') void handleRename(group)
                          if (e.key === 'Escape') setEditingId(null)
                        }}
                      />
                      <button type="button" className="subtle-button confirm-btn" onClick={() => void handleRename(group)} disabled={busy} title="保存">
                        <Icon name="check" />
                      </button>
                      <button type="button" className="subtle-button" onClick={() => setEditingId(null)} title="取消">
                        <Icon name="close" />
                      </button>
                    </div>
                  ) : (
                    <>
                      <div className="group-item-info">
                        <span className="group-item-name">{group.name}</span>
                        <span className="group-item-count">{group.bookCount} 本</span>
                      </div>
                      <div className="group-item-actions">
                        <button
                          type="button"
                          className="subtle-button"
                          disabled={idx === 0 || busy}
                          onClick={() => void handleMove(idx, 'up')}
                          title="上移"
                        >
                          ↑
                        </button>
                        <button
                          type="button"
                          className="subtle-button"
                          disabled={idx === groups.length - 1 || busy}
                          onClick={() => void handleMove(idx, 'down')}
                          title="下移"
                        >
                          ↓
                        </button>
                        <button
                          type="button"
                          className="subtle-button"
                          onClick={() => {
                            setEditingId(group.id)
                            setEditingName(group.name)
                          }}
                          title="重命名"
                        >
                          <Icon name="edit" />
                        </button>
                        <button
                          type="button"
                          className="subtle-button danger-icon-btn"
                          onClick={() => void handleDelete(group)}
                          title="删除分组"
                        >
                          <Icon name="close" />
                        </button>
                      </div>
                    </>
                  )}
                </div>
              ))
            )}
          </div>
        </div>

        <footer className="group-manage-footer">
          <button type="button" className="primary-button" onClick={onClose}>完成</button>
        </footer>
      </div>
    </div>
  )
}

function BatchMoveGroupModal({
  groups,
  selectedCount,
  onClose,
  onSelectGroup,
}: {
  groups: BookGroup[]
  selectedCount: number
  onClose: () => void
  onSelectGroup: (groupName: string | null) => Promise<void>
}) {
  const [busy, setBusy] = useState(false)

  const handleSelect = async (groupName: string | null) => {
    setBusy(true)
    try {
      await onSelectGroup(groupName)
      onClose()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div className="batch-move-modal" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="移动书籍至分组">
        <header className="group-manage-header">
          <div>
            <span className="section-kicker">批量操作</span>
            <h2>移动到分组</h2>
            <small>已选择 {selectedCount} 本书籍</small>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label="关闭"><Icon name="close" /></button>
        </header>

        <div className="batch-move-list">
          <button type="button" className="batch-move-option" disabled={busy} onClick={() => void handleSelect(null)}>
            <div className="option-name">未分组</div>
            <small>清除当前分组归属</small>
          </button>
          {groups.map(g => (
            <button key={g.id} type="button" className="batch-move-option" disabled={busy} onClick={() => void handleSelect(g.name)}>
              <div className="option-name">{g.name}</div>
              <small>{g.bookCount} 本书</small>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

function ShelfPage({ onOpen }: { onOpen: (item: BookshelfItem) => void }) {
  const [items, setItems] = useState<BookshelfItem[]>([])
  const [groups, setGroups] = useState<BookGroup[]>([])
  const [selectedGroup, setSelectedGroup] = useState<string>('all') // 'all' | '__ungrouped__' | custom group name
  const [statusFilter, setStatusFilter] = useState<'all' | 'reading' | 'completed'>('all')
  const [message, setMessage] = useState('')
  const [switchingItem, setSwitchingItem] = useState<BookshelfItem | null>(null)
  const [managingItem, setManagingItem] = useState<BookshelfItem | null>(null)
  const [uploading, setUploading] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [managingGroups, setManagingGroups] = useState(false)
  const [batchMode, setBatchMode] = useState(false)
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [batchMoving, setBatchMoving] = useState(false)
  const previousStateRef = useRef<Map<string, string>>(new Map())

  const load = useCallback(async () => {
    try {
      const [list, groupList] = await Promise.all([
        api.bookshelf(),
        api.bookGroups(),
      ])
      list.forEach(curr => {
        const key = `${curr.sourceId}\u0000${curr.bookUrl}`
        const prevState = previousStateRef.current.get(key)
        if (prevState === 'caching' && curr.cacheState === 'ready') {
          toast.success(`《${curr.name}》全本离线缓存完成（共 ${curr.cachedChapters} 章）`)
        } else if (prevState === 'caching' && curr.cacheState === 'failed') {
          toast.warning(`《${curr.name}》缓存中断：${curr.cacheError || '未全部完成'}`)
        }
        previousStateRef.current.set(key, curr.cacheState)
      })
      setItems(list)
      setGroups(groupList)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '无法载入书架')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const handleImportFiles = async (files: FileList | File[]) => {
    const list = Array.from(files).filter(f => {
      const n = f.name.toLowerCase()
      return n.endsWith('.txt') || n.endsWith('.epub') || n.endsWith('.text')
    })
    if (list.length === 0) {
      toast.warning('请选择 .txt 或 .epub 格式的电子书文件')
      return
    }
    setUploading(true)
    toast.info(`正在解析并导入 ${list.length} 本本地书籍...`)
    try {
      const resp = await api.importLocalBooks(list)
      if (resp.imported > 0) {
        toast.success(`成功导入 ${resp.imported} 本本地书籍！`)
      }
      if (resp.failed > 0) {
        const errorMsg = resp.results.filter(r => !r.success).map(r => `${r.filename}: ${r.error}`).join('; ')
        toast.error(`部分书籍导入失败 (${resp.failed} 本): ${errorMsg}`)
      }
      await load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '导入本地书籍失败')
    } finally {
      setUploading(false)
    }
  }

  const caching = items.some(item => item.cacheState === 'caching')
  useEffect(() => {
    if (!caching) return
    const timer = window.setInterval(load, 1200)
    return () => window.clearInterval(timer)
  }, [caching, load])

  const remove = async (item: BookshelfItem) => {
    if (!confirm(`移出“${item.name}”将清除书架、阅读进度和缓存封面，确定继续吗？`)) return
    try {
      await api.removeFromBookshelf(item.sourceId, item.bookUrl)
      setItems(values => values.filter(value => value.sourceId !== item.sourceId || value.bookUrl !== item.bookUrl))
      toast.info(`《${item.name}》已移出书架`)
      void load()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '移出失败')
    }
  }

  const cache = async (item: BookshelfItem) => {
    try {
      await api.cacheBookshelfBook(item.sourceId, item.bookUrl)
      setItems(values => values.map(value => value.sourceId === item.sourceId && value.bookUrl === item.bookUrl ? { ...value, cacheState: 'caching', cacheError: undefined } : value))
      previousStateRef.current.set(`${item.sourceId}\u0000${item.bookUrl}`, 'caching')
      toast.info(`已加入离线缓存队列，正在下载《${item.name}》...`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '无法开始缓存')
    }
  }

  const cancelCache = async (item: BookshelfItem) => {
    try {
      await api.cancelBookCache(item.sourceId, item.bookUrl)
      setItems(values => values.map(value => value.sourceId === item.sourceId && value.bookUrl === item.bookUrl ? { ...value, cacheState: 'failed', cacheError: '已取消缓存' } : value))
      previousStateRef.current.set(`${item.sourceId}\u0000${item.bookUrl}`, 'failed')
      toast.info(`已取消《${item.name}》的离线缓存`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '取消缓存失败')
    }
  }

  const handleSwitchShelfSource = async (chosen: { result: SearchResult; chapters: Chapter[]; targetChapterIndex: number }) => {
    if (!switchingItem) return
    const details = await api.details(chosen.result.sourceId, chosen.result.bookUrl)
    const fallbackCover = details.coverUrl?.trim() ||
      chosen.result.coverUrl?.trim() ||
      switchingItem.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()

    await api.switchBookshelfSource({
      oldSourceId: switchingItem.sourceId,
      oldBookUrl: switchingItem.bookUrl,
      book: {
        sourceId: details.sourceId,
        bookUrl: chosen.result.bookUrl,
        name: cleanTitle(details.name?.trim() || switchingItem.name) || switchingItem.name,
        author: cleanAuthor(details.author?.trim() || switchingItem.author) || switchingItem.author,
        tocUrl: details.tocUrl,
        coverUrl: fallbackCover || undefined,
        groupName: switchingItem.groupName,
      },
      alternateSources: switchingItem.alternateSources,
    })
    setSwitchingItem(null)
    await load()
  }

  const setCompleted = async (item: BookshelfItem, completed: boolean) => {
    try {
      const updated = await api.setBookshelfCompleted(item.sourceId, item.bookUrl, completed)
      setItems(values => values.map(value => value.sourceId === item.sourceId && value.bookUrl === item.bookUrl ? updated : value))
      toast.success(completed ? `已将《${item.name}》标记为已读完` : `已将《${item.name}》恢复为正在阅读`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '更新阅读状态失败')
    }
  }

  const cacheBadge = (item: BookshelfItem) => {
    if (item.cacheState === 'caching') return `${Math.min(100, Math.round((item.cachedChapters / Math.max(1, item.totalChapters || 1)) * 100))}% 缓存中`
    if (item.cacheState === 'ready') return `${item.cachedChapters}章已缓存`
    if (item.cacheState === 'failed') return '缓存中断'
    return null
  }

  const groupFilteredItems = useMemo(() => {
    if (selectedGroup === 'all') return items
    if (selectedGroup === '__ungrouped__') return items.filter(i => !i.groupName)
    return items.filter(i => i.groupName === selectedGroup)
  }, [items, selectedGroup])

  const visibleItems = useMemo(() => {
    if (statusFilter === 'all') return groupFilteredItems
    if (statusFilter === 'completed') return groupFilteredItems.filter(i => i.completed)
    return groupFilteredItems.filter(i => !i.completed)
  }, [groupFilteredItems, statusFilter])

  const groupCounts = useMemo(() => {
    let ungrouped = 0
    items.forEach(i => {
      if (!i.groupName) ungrouped++
    })
    return { ungrouped, all: items.length }
  }, [items])

  const statusCounts = useMemo(() => ({
    all: groupFilteredItems.length,
    reading: groupFilteredItems.filter(i => !i.completed).length,
    completed: groupFilteredItems.filter(i => i.completed).length,
  }), [groupFilteredItems])

  const toggleSelectKey = (key: string) => {
    setSelectedKeys(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const selectAllVisible = () => {
    const keys = visibleItems.map(i => `${i.sourceId}\u0000${i.bookUrl}`)
    setSelectedKeys(new Set(keys))
  }

  const deselectAll = () => {
    setSelectedKeys(new Set())
  }

  const getSelectedBookKeys = () => {
    return Array.from(selectedKeys).map(k => {
      const parts = k.split('\u0000')
      return { sourceId: parts[0], bookUrl: parts[1] }
    })
  }

  const handleBatchMoveGroup = async (targetGroup: string | null) => {
    const keys = getSelectedBookKeys()
    if (keys.length === 0) return
    try {
      await api.batchBookshelf({
        action: 'move_group',
        items: keys,
        targetGroup: targetGroup || undefined,
      })
      toast.success(targetGroup ? `已将 ${keys.length} 本书籍移动至「${targetGroup}」` : `已将 ${keys.length} 本书籍移至「未分组」`)
      setSelectedKeys(new Set())
      setBatchMode(false)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量移动失败')
    }
  }

  const handleBatchMarkCompleted = async (completed: boolean) => {
    const keys = getSelectedBookKeys()
    if (keys.length === 0) return
    try {
      await api.batchBookshelf({
        action: 'mark_completed',
        items: keys,
        completed,
      })
      toast.success(completed ? `已将 ${keys.length} 本书籍标记为已读完` : `已将 ${keys.length} 本书籍恢复为正在阅读`)
      setSelectedKeys(new Set())
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量更新阅读状态失败')
    }
  }

  const handleBatchDelete = async () => {
    const keys = getSelectedBookKeys()
    if (keys.length === 0) return
    if (!confirm(`确定将选中的 ${keys.length} 本书籍移出书架吗？将清除阅读进度与离线缓存。`)) return
    try {
      await api.batchBookshelf({
        action: 'delete',
        items: keys,
      })
      toast.info(`已移出 ${keys.length} 本书籍`)
      setSelectedKeys(new Set())
      setBatchMode(false)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '批量移出失败')
    }
  }

  return (
    <main
      className={`shelf-page ${dragOver ? 'drag-active' : ''}`}
      onDragOver={e => { e.preventDefault(); setDragOver(true) }}
      onDragLeave={e => {
        if (!e.currentTarget.contains(e.relatedTarget as Node)) {
          setDragOver(false)
        }
      }}
      onDrop={e => {
        e.preventDefault()
        setDragOver(false)
        if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
          void handleImportFiles(e.dataTransfer.files)
        }
      }}
    >
      <header className="page-title shelf-page-header">
        <div>
          <span className="section-kicker">我的阅读</span>
          <h1>书架</h1>
          <p>继续上次未读完的故事，或导入本地 TXT / EPUB 电子书。</p>
        </div>
        <div className="shelf-header-actions">
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept=".txt,.epub,.text,application/epub+zip,text/plain"
            style={{ display: 'none' }}
            onChange={e => {
              if (e.target.files && e.target.files.length > 0) {
                void handleImportFiles(e.target.files)
                e.target.value = ''
              }
            }}
          />
          <button
            type="button"
            className="shelf-import-btn"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
            title="导入本地 TXT 或 EPUB 小说"
          >
            <Icon name="upload" />
            <span>{uploading ? '导入中...' : '导入本地'}</span>
          </button>
          <small>{visibleItems.length} 本书</small>
          <button
            type="button"
            className={`shelf-batch-toggle-btn ${batchMode ? 'active' : ''}`}
            onClick={() => {
              if (batchMode) {
                setBatchMode(false)
                setSelectedKeys(new Set())
              } else {
                setBatchMode(true)
              }
            }}
          >
            <Icon name={batchMode ? 'check' : 'list'} />
            {batchMode ? '完成' : '批量管理'}
          </button>
        </div>
      </header>

      {/* Primary Group Tabs */}
      <nav className="shelf-tabs shelf-group-tabs" aria-label="书架分组">
        <button
          type="button"
          className={selectedGroup === 'all' ? 'active' : ''}
          onClick={() => setSelectedGroup('all')}
        >
          全部<small>{groupCounts.all}</small>
        </button>
        <button
          type="button"
          className={selectedGroup === '__ungrouped__' ? 'active' : ''}
          onClick={() => setSelectedGroup('__ungrouped__')}
        >
          未分组<small>{groupCounts.ungrouped}</small>
        </button>
        {groups.map(g => (
          <button
            key={g.id}
            type="button"
            className={selectedGroup === g.name ? 'active' : ''}
            onClick={() => setSelectedGroup(g.name)}
          >
            {g.name}<small>{g.bookCount}</small>
          </button>
        ))}
        <button
          type="button"
          className="shelf-manage-group-btn"
          onClick={() => setManagingGroups(true)}
          title="管理与创建分组"
        >
          <Icon name="settings" />
          <span>管理分组</span>
        </button>
      </nav>

      {/* Secondary Status Filter Pills */}
      <div className="shelf-subfilter-bar">
        <div className="shelf-status-pills">
          {([['all', '全部'], ['reading', '正在阅读'], ['completed', '已读完']] as const).map(([key, label]) => (
            <button
              key={key}
              type="button"
              className={`shelf-pill-btn ${statusFilter === key ? 'active' : ''}`}
              onClick={() => setStatusFilter(key)}
            >
              {label} ({statusCounts[key]})
            </button>
          ))}
        </div>
      </div>

      {message && <p className="form-error">{message}</p>}

      {items.length === 0 && !message ? (
        <section className="shelf-empty">
          <Icon name="book" />
          <h2>书架还是空的</h2>
          <p>在书库检索添加在线小说，或直接导入本地 TXT / EPUB 文件。</p>
          <button
            type="button"
            className="shelf-import-btn"
            style={{ marginTop: '12px' }}
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
          >
            <Icon name="upload" />
            <span>{uploading ? '正在解析导入...' : '导入本地 TXT / EPUB'}</span>
          </button>
        </section>
      ) : visibleItems.length === 0 ? (
        <section className="shelf-empty">
          <Icon name="book" />
          <h2>当前分组与筛选下没有书籍</h2>
        </section>
      ) : (
        <section className="shelf-grid">
          {visibleItems.map(item => {
            const badge = cacheBadge(item)
            const isCaching = item.cacheState === 'caching'
            const isLocal = item.sourceId === 'loc_book'
            const percent = Math.min(100, Math.round((item.cachedChapters / Math.max(1, item.totalChapters || 1)) * 100))
            const itemKey = `${item.sourceId}\u0000${item.bookUrl}`
            const isSelected = selectedKeys.has(itemKey)

            return (
              <article
                key={itemKey}
                className={`shelf-card ${batchMode ? 'batch-selectable' : ''} ${isSelected ? 'selected' : ''}`}
                onClick={batchMode ? () => toggleSelectKey(itemKey) : undefined}
              >
                {/* Upper Half: Large Cover directly opens reader */}
                <div
                  className="shelf-card-cover"
                  onClick={batchMode ? (e) => { e.stopPropagation(); toggleSelectKey(itemKey) } : () => onOpen(item)}
                  role="button"
                  tabIndex={0}
                  aria-label={batchMode ? `选择 ${item.name}` : `继续阅读 ${item.name}`}
                >
                  {batchMode && (
                    <div className={`shelf-card-checkbox ${isSelected ? 'checked' : ''}`}>
                      {isSelected && <Icon name="check" />}
                    </div>
                  )}
                  {item.coverKey ? <img src={api.cover(item.coverKey)} alt="" /> : <span className="cover-fallback">{item.name.slice(0, 1)}</span>}
                  {isLocal && <span className="shelf-card-tag-local">本地</span>}
                  {badge && <span className={`shelf-card-badge ${item.cacheState}`}>{badge}</span>}
                  {isCaching && (
                    <div className="shelf-card-progress-track">
                      <div className="shelf-card-progress-fill" style={{ width: `${percent}%` }} />
                    </div>
                  )}
                </div>

                {/* Middle: Title, Author, Reading Progress, Group Tag */}
                <div className="shelf-card-info">
                  <h3
                    className="shelf-card-name"
                    onClick={batchMode ? (e) => { e.stopPropagation(); toggleSelectKey(itemKey) } : () => onOpen(item)}
                    title={item.name}
                  >
                    {item.name}
                  </h3>
                  <p className="shelf-card-author">{item.author || '未知作者'}</p>
                  <div className="shelf-card-meta">
                    <span className={`shelf-meta-chapter ${item.completed ? 'completed' : ''}`}>
                      {item.completed ? '已读完' : item.chapterIndex === undefined ? '刚加入书架' : `第 ${item.chapterIndex + 1} 章`}
                    </span>
                    {item.groupName && (
                      <span className="shelf-card-group-tag" title={`分组：${item.groupName}`}>
                        {item.groupName}
                      </span>
                    )}
                  </div>
                </div>

                {/* Bottom Action Section: Clear Read Button & Manage Button */}
                {!batchMode && (
                  <div className="shelf-card-footer">
                    <button type="button" className="shelf-btn-read" onClick={() => onOpen(item)}>
                      {item.completed ? '重新阅读' : '继续阅读'}
                    </button>
                    <button
                      type="button"
                      className="shelf-btn-manage"
                      onClick={() => setManagingItem(item)}
                      aria-label={`管理 ${item.name}`}
                      title="书籍管理"
                    >
                      <Icon name="more" />
                    </button>
                  </div>
                )}
              </article>
            )
          })}
        </section>
      )}

      {/* Floating Batch Action Toolbar */}
      {batchMode && (
        <aside className="shelf-batch-bar" role="toolbar" aria-label="批量操作栏">
          <div className="shelf-batch-info">
            <strong>已选 {selectedKeys.size} 本</strong>
            <button
              type="button"
              className="subtle-button"
              onClick={selectedKeys.size === visibleItems.length ? deselectAll : selectAllVisible}
            >
              {selectedKeys.size === visibleItems.length && visibleItems.length > 0 ? '取消全选' : '全选当前'}
            </button>
          </div>
          <div className="shelf-batch-buttons">
            <button
              type="button"
              className="shelf-batch-action-btn"
              disabled={selectedKeys.size === 0}
              onClick={() => setBatchMoving(true)}
            >
              <Icon name="bookmark" />
              <span>移动分组</span>
            </button>
            <button
              type="button"
              className="shelf-batch-action-btn"
              disabled={selectedKeys.size === 0}
              onClick={() => void handleBatchMarkCompleted(true)}
            >
              <Icon name="check" />
              <span>标为已读</span>
            </button>
            <button
              type="button"
              className="shelf-batch-action-btn"
              disabled={selectedKeys.size === 0}
              onClick={() => void handleBatchMarkCompleted(false)}
            >
              <Icon name="refresh" />
              <span>标为在读</span>
            </button>
            <button
              type="button"
              className="shelf-batch-action-btn danger"
              disabled={selectedKeys.size === 0}
              onClick={() => void handleBatchDelete()}
            >
              <Icon name="close" />
              <span>移出书架</span>
            </button>
            <button
              type="button"
              className="shelf-batch-action-btn complete-btn"
              onClick={() => {
                setBatchMode(false)
                setSelectedKeys(new Set())
              }}
            >
              完成
            </button>
          </div>
        </aside>
      )}

      {/* Book Management Modal */}
      {managingItem && (
        <BookManageModal
          item={managingItem}
          groups={groups}
          onClose={() => setManagingItem(null)}
          onSwitchSource={() => {
            const target = managingItem
            setManagingItem(null)
            setSwitchingItem(target)
          }}
          onCache={() => {
            void cache(managingItem)
            setManagingItem(prev => prev ? { ...prev, cacheState: 'caching' } : null)
          }}
          onCancelCache={() => {
            void cancelCache(managingItem)
            setManagingItem(prev => prev ? { ...prev, cacheState: 'failed', cacheError: '已取消缓存' } : null)
          }}
          onToggleCompleted={() => setCompleted(managingItem, !managingItem.completed)}
          onUpdateInfo={updated => {
            setItems(values => values.map(v => v.sourceId === updated.sourceId && v.bookUrl === updated.bookUrl ? updated : v))
            setManagingItem(updated)
            void load()
          }}
          onRemove={() => remove(managingItem)}
        />
      )}

      {/* Standalone Source Switch Modal for Shelf Book */}
      {switchingItem && (
        <SourceSwitchModal
          bookName={switchingItem.name}
          author={switchingItem.author}
          currentSourceId={switchingItem.sourceId}
          currentBookUrl={switchingItem.bookUrl}
          currentChapterIndex={switchingItem.chapterIndex}
          knownAlternateSources={switchingItem.alternateSources}
          onSwitch={handleSwitchShelfSource}
          onClose={() => setSwitchingItem(null)}
        />
      )}

      {/* Group Management Modal */}
      {managingGroups && (
        <GroupManageModal
          groups={groups}
          onClose={() => setManagingGroups(false)}
          onGroupsChanged={load}
        />
      )}

      {/* Batch Move Group Modal */}
      {batchMoving && (
        <BatchMoveGroupModal
          groups={groups}
          selectedCount={selectedKeys.size}
          onClose={() => setBatchMoving(false)}
          onSelectGroup={handleBatchMoveGroup}
        />
      )}
    </main>
  )
}

function App() {
  const [ready, setReady] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [settings, setSettings] = useState<ReaderSettings>(loadReaderSettings)
  const [page, setPage] = useState<Page>(pageFromHash)
  const [readerReturnPage, setReaderReturnPage] = useState<Page>('shelf')
  const [sources, setSources] = useState<SourceSummary[]>([])
  const [selected, setSelected] = useState<SourceSummary | null>(null)
  const [reader, setReader] = useState<{ book: OpenBook; index: number } | null>(() => {
    try {
      return JSON.parse(sessionStorage.getItem(readerStorageKey) ?? 'null')
    } catch {
      return null
    }
  })
  const [showReplaceRules, setShowReplaceRules] = useState(false)
  const [showOfflineCache, setShowOfflineCache] = useState(false)
  const search = useSearchStore()

  useEffect(() => {
    saveReaderSettings(settings)
  }, [settings])

  useEffect(() => {
    const handleOnline = () => {
      void flushOfflineProgress(async (item) => {
        await api.saveProgress(item.sourceId, item.bookUrl, item.chapterUrl, item.chapterIndex, item.scrollPosition)
      })
    }
    window.addEventListener('online', handleOnline)
    return () => window.removeEventListener('online', handleOnline)
  }, [])

  useEffect(() => {
    void api.session().then(result => {
      setAuthenticated(result.authenticated)
      setCsrfToken(result.csrfToken ?? null)
      if (result.authenticated) void api.sources().then(setSources).catch(() => undefined)
    }).finally(() => setReady(true))
  }, [])

  useEffect(() => {
    const sync = () => setPage(pageFromHash())
    addEventListener('hashchange', sync)
    return () => removeEventListener('hashchange', sync)
  }, [])

  const navigate = (next: Page) => {
    if (next === 'reader' && !reader) return
    location.hash = `#${next}`
    setPage(next)
  }

  const openReader = (book: OpenBook, index: number, origin: Page = 'library') => {
    const fallbackCover = book.details.coverUrl || book.details.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()
    void api.addToBookshelf({
      sourceId: book.details.sourceId,
      bookUrl: book.bookUrl,
      name: book.details.name,
      author: book.details.author,
      tocUrl: book.details.tocUrl,
      coverUrl: fallbackCover || undefined,
      alternateSources: book.details.alternateSources,
    }).catch(() => undefined)
    const value = { book, index }
    setReader(value)
    setReaderReturnPage(origin)
    sessionStorage.setItem(readerStorageKey, JSON.stringify(value))
    location.hash = '#reader'
    setPage('reader')
  }

  const openShelfItem = async (item: BookshelfItem) => {
    const fallbackCover = item.coverKey ? api.cover(item.coverKey) : (item.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim() || undefined)
    const safeDetails: BookDetails = {
      sourceId: item.sourceId,
      name: cleanTitle(item.name) || item.name,
      author: cleanAuthor(item.author) || item.author,
      coverUrl: fallbackCover,
      intro: undefined,
      tocUrl: item.tocUrl,
      alternateSources: item.alternateSources,
    }

    try {
      const [chapters, progress] = await Promise.all([
        api.chapters(safeDetails.sourceId, safeDetails.tocUrl),
        api.progress(safeDetails.sourceId, item.bookUrl).catch(() => undefined),
      ])
      const resumeIdx = progress?.chapterIndex ?? item.chapterIndex ?? 0
      openReader({
        details: safeDetails,
        bookUrl: item.bookUrl,
        chapters,
        progress: progress || (item.chapterIndex !== undefined ? {
          sourceId: item.sourceId,
          bookUrl: item.bookUrl,
          chapterUrl: chapters[resumeIdx]?.url || '',
          chapterIndex: resumeIdx,
          scrollPosition: item.scrollPosition ?? 0,
          updatedAt: item.lastReadAt,
        } : undefined),
      }, resumeIdx, 'shelf')
    } catch {
      // Offline / network fallback: allow entering reader so user can read local cached chapters
      const resumeIdx = item.chapterIndex ?? 0
      const fallbackChapters: Chapter[] = [
        { index: resumeIdx, title: `第 ${resumeIdx + 1} 章`, url: item.tocUrl }
      ]
      openReader({
        details: safeDetails,
        bookUrl: item.bookUrl,
        chapters: fallbackChapters,
        progress: {
          sourceId: item.sourceId,
          bookUrl: item.bookUrl,
          chapterUrl: item.tocUrl,
          chapterIndex: resumeIdx,
          scrollPosition: item.scrollPosition ?? 0,
          updatedAt: item.lastReadAt,
        },
      }, resumeIdx, 'shelf')
      toast.warning('书源网络较慢，已为您进入离线阅读模式')
    }
  }

  const logout = async () => {
    try {
      await api.logout()
    } finally {
      setCsrfToken(null)
      setAuthenticated(false)
    }
  }

  if (!ready) return <main className={`app-loading theme-${settings.theme}`}><span>正在打开阅读空间...</span></main>
  if (!authenticated) return <div className={`app-shell theme-${settings.theme}`}><ToastContainer /><PwaManager /><Login onLogin={() => { setAuthenticated(true); void api.sources().then(setSources).catch(() => undefined) }} /></div>

  if (page === 'reader' && reader) {
    return (
      <div className={`app-shell theme-${settings.theme}`}>
        <ToastContainer />
        <ReaderScreen
          openBook={reader.book}
          startIndex={reader.index}
          settings={settings}
          onSettingsChange={setSettings}
          onClose={() => navigate(readerReturnPage)}
        />
      </div>
    )
  }

  const refreshSources = () => {
    void api.sources().then(setSources).catch(() => undefined)
  }

  return (
    <div className={`app-shell theme-${settings.theme}`}>
      <ToastContainer />
      <PwaManager />
      <AppHeader
        page={page}
        settings={settings}
        searching={search.loading}
        onSettingsChange={setSettings}
        onNavigate={navigate}
        onOpenReplaceRules={() => setShowReplaceRules(true)}
        onOpenOfflineCache={() => setShowOfflineCache(true)}
        onLogout={() => void logout()}
      />
      {page === 'sources' ? (
        <SourcesPage selected={selected} onSelect={setSelected} onSourcesChange={setSources} />
      ) : page === 'subscriptions' ? (
        <SubscriptionPage onSourcesChange={refreshSources} />
      ) : page === 'rules' ? (
        <ReplaceRulesPage />
      ) : page === 'webdav' ? (
        <WebDavSettingsPage />
      ) : page === 'shelf' ? (
        <ShelfPage onOpen={item => void openShelfItem(item)} />
      ) : (
        <LibraryPage sources={sources} onOpen={(book, index) => openReader(book, index, 'library')} />
      )}
      <ReplaceRulesModal
        isOpen={showReplaceRules}
        onClose={() => setShowReplaceRules(false)}
      />
      {showOfflineCache && (
        <OfflineCacheModal onClose={() => setShowOfflineCache(false)} />
      )}
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<App />)
