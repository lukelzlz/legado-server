import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, BookDetails, BookshelfItem, Chapter, SearchResult, SearchStreamEvent, setCsrfToken, SourceRecord, SourceSubscription, SourceSummary, streamSearch } from './api'
import { Icon } from './icons'
import { OpenBook, ReaderScreen } from './ReaderScreen'
import { loadReaderSettings, ReaderSettings, saveReaderSettings } from './readerSettings'
import { AppHeader } from './AppHeader'
import { defaultSearchFilters, filterSearchGroups, SearchFilters, SearchGroup } from './searchFilters'
import { SourceSwitchModal } from './SourceSwitchModal'
import { toast, ToastContainer } from './Toast'
import './styles.css'

type Page = 'sources' | 'subscriptions' | 'library' | 'shelf' | 'reader'
const readerStorageKey = 'legado-open-book-v1'
const pageFromHash = (): Page => location.hash === '#sources' ? 'sources' : location.hash === '#subscriptions' ? 'subscriptions' : location.hash === '#shelf' ? 'shelf' : location.hash === '#reader' ? 'reader' : 'library'
export type SourceChoiceStatus = 'idle' | 'loading' | 'loaded' | 'error'
export type SourceChoice = { result: SearchResult; status: SourceChoiceStatus; book?: OpenBook; error?: string }
const bookKey = (name: string, author?: string) => `${name.replace(/[\s\p{P}]/gu, '').toLocaleLowerCase()}\u0000${(author ?? '').replace(/[\s\p{P}]/gu, '').toLocaleLowerCase()}`
const groupSearchResults = (results: SearchResult[]): SearchGroup[] => Array.from(results.reduce((groups, result) => { const key = bookKey(result.name, result.author); const current = groups.get(key) ?? { key, name: result.name, author: result.author, sources: [] }; current.sources.push(result); groups.set(key, current); return groups }, new Map<string, SearchGroup>()).values())
const loadSourceBook = async (result: SearchResult, alternateSources?: SearchResult[]): Promise<OpenBook> => {
  const details = await api.details(result.sourceId, result.bookUrl)
  const safeDetails: BookDetails = {
    ...details,
    name: details.name?.trim() || result.name || '未知书名',
    author: details.author?.trim() || result.author,
    coverUrl: details.coverUrl || result.coverUrl,
    intro: details.intro || result.intro,
    alternateSources: alternateSources?.filter(s => s.sourceId !== result.sourceId || s.bookUrl !== result.bookUrl),
  }
  const [chapters, progress] = await Promise.all([api.chapters(safeDetails.sourceId, safeDetails.tocUrl), api.progress(safeDetails.sourceId, result.bookUrl)])
  return { details: safeDetails, bookUrl: result.bookUrl, chapters, progress }
}

function SourceChoiceList({ choices, active, onChoose }: { choices: SourceChoice[]; active?: string; onChoose: (choice: SourceChoice) => void }) {
  return <section className="source-choice-list"><header><span>选择书源</span><small>{choices.length} 个可用来源</small></header>{choices.map(choice => {
    const isSelected = active === choice.result.bookUrl
    const isLoading = choice.status === 'loading'
    const isLoaded = choice.status === 'loaded' && choice.book
    const isError = choice.status === 'error'
    let statusText = '点击切换为此书源'
    if (isLoading) {
      statusText = '正在读取目录...'
    } else if (isError) {
      statusText = choice.error || '无法读取此书源'
    } else if (isLoaded) {
      statusText = `已加载 · 共 ${choice.book!.chapters.length} 章`
    } else if (choice.result.intro) {
      statusText = choice.result.intro
    }
    return <button key={`${choice.result.sourceId}-${choice.result.bookUrl}`} className={`${isSelected ? 'selected' : ''} ${isLoading ? 'loading' : ''}`} disabled={isLoading} onClick={() => onChoose(choice)}><strong>{choice.result.sourceId}</strong><small>{statusText}</small></button>
  })}</section>
}

function BookInfoSummary({ book, choices, resumeIndex, onOpen, onChooseSource }: { book: OpenBook; choices: SourceChoice[]; resumeIndex: number; onOpen: (index: number) => void; onChooseSource: (choice: SourceChoice) => void }) {
  const [introExpanded, setIntroExpanded] = useState(false)
  const latestChapter = book.chapters.at(-1)
  const availableSources = choices.length
  return <section className="library-book-detail"><header><div className="book-detail-heading">{book.details.coverUrl && <img className="book-detail-cover" src={book.details.coverUrl} alt="" referrerPolicy="no-referrer" />}<div><span className="section-kicker">书籍详情</span><h2>{book.details.name}</h2><p>{book.details.author || '未知作者'}</p><div className="book-detail-stats"><span>{book.chapters.length} 章</span><span>{availableSources || 1} 个可用书源</span>{latestChapter && <span>最新：{latestChapter.title}</span>}</div></div></div><button className="primary-button" onClick={() => onOpen(resumeIndex)}>{book.progress ? '继续阅读' : '开始阅读'}<Icon name="arrowRight" /></button></header>{choices.length > 1 && <SourceChoiceList choices={choices} active={book.bookUrl} onChoose={onChooseSource} />}{book.details.intro && <div className={`book-intro ${introExpanded ? 'expanded' : ''}`}><p>{book.details.intro}</p>{book.details.intro.length > 120 && <button className="intro-toggle" onClick={() => setIntroExpanded(value => !value)}>{introExpanded ? '收起简介' : '展开简介'}</button>}</div>}<div className="preview-chapters">{book.chapters.slice(0, 16).map(item => <button className={item.index === resumeIndex ? 'resume-chapter' : ''} key={item.url} onClick={() => onOpen(item.index)}><span>{item.title}</span>{item.index === resumeIndex && book.progress && <small>上次阅读</small>}</button>)}</div>{book.chapters.length > 16 && <p className="chapter-count">共 {book.chapters.length} 章，进入阅读器查看完整目录</p>}</section>
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(''); try { const result = await api.login(password); setCsrfToken(result.csrfToken); onLogin() } catch (cause) { setError(cause instanceof Error ? cause.message : '登录失败') } finally { setBusy(false) } }
  return <main className="login-shell"><form className="login-panel" onSubmit={submit}><div className="login-mark"><Icon name="book" /><strong>阅读服务器</strong></div><h1>回到你的阅读空间</h1><p>输入部署时设置的单用户密码继续。</p><label>密码<input autoFocus type="password" value={password} onChange={event => setPassword(event.target.value)} required /></label>{error && <p className="form-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy ? '正在验证...' : '登录'}</button></form></main>
}

function SourceEditor({ selected, onSaved }: { selected: SourceSummary | null; onSaved: () => void }) {
  const [record, setRecord] = useState<SourceRecord | null>(null); const [text, setText] = useState(''); const [status, setStatus] = useState('')
  useEffect(() => { if (!selected) { setRecord(null); setText(''); return }; void api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message)) }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="source-editor empty-editor"><Icon name="book" /><h2>选择一个书源</h2><p>从列表选择书源，或导入一个 JSON 文件。</p></section>
  return <section className="source-editor"><header><div><span className="section-kicker">书源编辑</span><h2>{selected.name}</h2><small>{selected.url}</small></div><div className="editor-actions"><button className="subtle-button" onClick={() => void validate}>校验</button><button className="primary-button" onClick={() => void save}>保存</button></div></header><textarea aria-label="书源 JSON 编辑器" value={text} onChange={event => setText(event.target.value)} spellCheck={false} />{status && <footer className={status.includes('失败') || status.includes('错误') ? 'form-error' : ''}>{status}</footer>}</section>
}

function SubscriptionPanel({ onSourcesChange }: { onSourcesChange: () => void }) {
  const [items, setItems] = useState<SourceSubscription[]>([]); const [url, setUrl] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState<number | 'all' | null>(null)
  const load = useCallback(async () => { try { setItems(await api.subscriptions()) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入订阅') } }, [])
  useEffect(() => { void load() }, [load])
  const add = async (event: FormEvent) => { event.preventDefault(); if (!url.trim()) return; setBusy('all'); try { await api.saveSubscription(url.trim()); setUrl(''); setNotice('订阅已保存'); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '保存订阅失败') } finally { setBusy(null) } }
  const update = async (id: number) => { setBusy(id); try { const result = await api.updateSubscription(id); setNotice(`同步完成：新增 ${result.imported}，更新 ${result.updated}，跳过 ${result.skipped}`); onSourcesChange(); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '同步失败') } finally { setBusy(null) } }
  const updateAll = async () => { setBusy('all'); try { const result = await api.updateSubscriptions(); setNotice(`全部同步完成：成功 ${result.updated}，失败 ${result.failed}`); onSourcesChange(); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '同步失败') } finally { setBusy(null) } }
  const toggle = async (item: SourceSubscription) => { setBusy(item.id); try { await api.saveSubscription(item.url, !item.enabled); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '更新失败') } finally { setBusy(null) } }
  const remove = async (item: SourceSubscription) => { if (!confirm(`删除订阅“${item.url}”？已导入的书源会保留。`)) return; setBusy(item.id); try { await api.removeSubscription(item.id); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } finally { setBusy(null) } }
  return <section className="subscription-panel"><header><div><span className="section-kicker">自动更新</span><h2>书源订阅</h2><p>每 6 小时自动同步；同一地址的书源会统一覆盖更新。</p></div><button className="subtle-button" onClick={() => void updateAll()} disabled={busy !== null}>{busy === 'all' ? '同步中...' : '全部同步'}</button></header><form onSubmit={add}><input value={url} onChange={event => setUrl(event.target.value)} placeholder="https://example.com/sources.json" inputMode="url" /><button className="primary-button" disabled={busy !== null}>添加订阅</button></form>{notice && <p className="sidebar-notice">{notice}</p>}<div className="subscription-list">{items.length === 0 ? <p>还没有书源订阅。</p> : items.map(item => <article key={item.id}><div><strong>{item.url}</strong><small>{item.lastError ? `最近失败：${item.lastError}` : item.lastSuccessAt ? `最近同步：${new Date(item.lastSuccessAt).toLocaleString()}，处理 ${item.lastImported} 个书源` : '尚未同步'}</small></div><div className="subscription-actions"><button className="subtle-button" onClick={() => void toggle(item)} disabled={busy !== null}>{item.enabled ? '已启用' : '已停用'}</button><button className="subtle-button" onClick={() => void update(item.id)} disabled={busy !== null || !item.enabled}>{busy === item.id ? '同步中...' : '立即同步'}</button><button className="shelf-remove" aria-label="删除订阅" onClick={() => void remove(item)} disabled={busy !== null}><Icon name="close" /></button></div></article>)}</div></section>
}

function SubscriptionPage({ onSourcesChange }: { onSourcesChange: () => void }) {
  return <main className="subscription-page"><header className="page-title"><div><span className="section-kicker">自动更新</span><h1>书源订阅</h1><p>从远程 JSON 订阅书源，并定时保持最新。</p></div></header><SubscriptionPanel onSourcesChange={onSourcesChange} /></main>
}

function SourcesPage({ selected, onSelect, onSourcesChange }: { selected: SourceSummary | null; onSelect: (source: SourceSummary | null) => void; onSourcesChange: (sources: SourceSummary[]) => void }) {
  const [sources, setSources] = useState<SourceSummary[]>([]); const [query, setQuery] = useState(''); const [notice, setNotice] = useState('')
  const load = useCallback(async () => { try { const values = await api.sources(query); setSources(values); onSourcesChange(values) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入书源') } }, [onSourcesChange, query])
  useEffect(() => { const timer = window.setTimeout(() => { void load() }, 180); return () => window.clearTimeout(timer) }, [load])
  const importSources = async (file: File | undefined) => { if (!file) return; try { const parsed = JSON.parse(await file.text()); const values = Array.isArray(parsed) ? parsed : [parsed]; const result = await api.import(values.map(value => JSON.stringify(value))); setNotice(`新增 ${result.imported} 个，更新 ${result.updated} 个${result.skipped ? `，跳过 ${result.skipped} 个` : ''}`); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '导入失败') } }
  const remove = async () => { if (!selected || !confirm(`删除“${selected.name}”？`)) return; try { await api.remove(selected.id); onSelect(null); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } }
  return <main className="sources-page"><aside className="source-sidebar"><div className="source-sidebar-heading"><span>书源</span><small>{sources.length}</small></div><div className="source-filter"><Icon name="search" /><input placeholder="筛选书源" value={query} onChange={event => setQuery(event.target.value)} /></div><label className="import-button"><Icon name="upload" />导入 JSON<input type="file" accept="application/json,.json" onChange={event => void importSources(event.target.files?.[0])} /></label>{notice && <p className="sidebar-notice">{notice}</p>}<nav className="source-list">{sources.map(source => <button className={selected?.id === source.id ? 'selected' : ''} key={source.id} onClick={() => onSelect(source)}><span>{source.name}</span><small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small></button>)}</nav></aside><section className="sources-content"><header className="page-title"><div><span className="section-kicker">阅读服务器</span><h1>书源管理</h1><p>导入、校验与维护你的阅读来源。</p></div>{selected && <button className="danger-button" onClick={() => void remove()}>删除书源</button>}</header><SourceEditor selected={selected} onSaved={() => void load()} /></section></main>
}

function LibraryPage({ selected, sources, onSelect, onOpen }: { selected: SourceSummary | null; sources: SourceSummary[]; onSelect: (source: SourceSummary | null) => void; onOpen: (book: OpenBook, index: number) => void }) {
  const [keyword, setKeyword] = useState(''); const [results, setResults] = useState<SearchResult[]>([]); const [choices, setChoices] = useState<SourceChoice[]>([]); const [openBook, setOpenBook] = useState<OpenBook | null>(null); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(false); const [stopped, setStopped] = useState(false); const [progress, setProgress] = useState<SearchStreamEvent | null>(null); const [filters, setFilters] = useState<SearchFilters>(defaultSearchFilters)
  const socketRef = useRef<WebSocket | null>(null)
  const stopSearch = useCallback(() => {
    const socket = socketRef.current
    if (!socket) return
    socketRef.current = null
    setStopped(true)
    setLoading(false)
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'cancel' }))
    window.setTimeout(() => socket.close(), 100)
  }, [])
  useEffect(() => () => { socketRef.current?.close(); socketRef.current = null }, [])
  const search = (event: FormEvent) => { event.preventDefault(); const value = keyword.trim(); if (!value) return; socketRef.current?.close(); setLoading(true); setStopped(false); setMessage(''); setProgress(null); setOpenBook(null); setChoices([]); setResults([]); setFilters(defaultSearchFilters); const socket = streamSearch(value, selected ? [selected.id] : undefined, packet => {
    if (socketRef.current !== socket) return
    if (packet.type === 'start' || packet.type === 'progress' || packet.type === 'done') setProgress(packet)
    if (packet.type === 'results') setResults(previous => { const known = new Set(previous.map(item => `${item.sourceId}\u0000${item.bookUrl}`)); const additions = packet.results.filter(item => !known.has(`${item.sourceId}\u0000${item.bookUrl}`)); return additions.length ? [...previous, ...additions] : previous })
    if (packet.type === 'error') { setMessage(packet.message || '搜索失败'); setLoading(false); socketRef.current = null }
    if (packet.type === 'done') { setProgress(packet); setLoading(false); socketRef.current = null }
  }, error => { if (socketRef.current === socket) { setMessage(error); setLoading(false); socketRef.current = null } }, () => { if (socketRef.current === socket) { setMessage('搜索连接已关闭'); setLoading(false); socketRef.current = null } }); socketRef.current = socket }
  const open = async (group: SearchGroup) => {
    setLoading(true); setMessage(''); setOpenBook(null)
    const initialChoices: SourceChoice[] = group.sources.map(result => ({ result, status: 'idle' }))
    setChoices(initialChoices)
    let loadedBook: OpenBook | null = null
    for (let i = 0; i < group.sources.length; i++) {
      const candidate = group.sources[i]
      setChoices(prev => prev.map((c, idx) => idx === i ? { ...c, status: 'loading' } : c))
      try {
        const book = await loadSourceBook(candidate, group.sources)
        setChoices(prev => prev.map((c, idx) => idx === i ? { ...c, book, status: 'loaded' } : c))
        loadedBook = book
        break
      } catch (err) {
        const errMsg = err instanceof Error ? err.message : '无法读取此书源'
        setChoices(prev => prev.map((c, idx) => idx === i ? { ...c, status: 'error', error: errMsg } : c))
      }
    }
    if (loadedBook) {
      setOpenBook(loadedBook)
    } else {
      setMessage('所有书源均无法读取')
    }
    setLoading(false)
  }
  const handleChooseSource = async (choice: SourceChoice, groupSources?: SearchResult[]) => {
    if (choice.book) {
      setOpenBook(choice.book)
      return
    }
    setChoices(prev => prev.map(c => c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl ? { ...c, status: 'loading', error: undefined } : c))
    try {
      const book = await loadSourceBook(choice.result, groupSources)
      setChoices(prev => prev.map(c => c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl ? { ...c, book, status: 'loaded' } : c))
      setOpenBook(book)
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : '无法读取此书源'
      setChoices(prev => prev.map(c => c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl ? { ...c, status: 'error', error: errMsg } : c))
    }
  }
  const resumeIndex = openBook?.progress ? Math.min(Math.max(openBook.progress.chapterIndex, 0), Math.max(0, openBook.chapters.length - 1)) : 0
  const groups = useMemo(() => groupSearchResults(results), [results])
  const visibleGroups = useMemo(() => filterSearchGroups(groups, filters), [filters, groups])
  const hasFilters = filters.query || filters.minimumSources > 1 || filters.withIntro || filters.withCover
  const completed = progress?.completedSources ?? 0
  const total = progress?.totalSources ?? 0
  const failures = progress?.failedSources ?? 0
  const empty = progress?.emptySources ?? 0
  return <main className="library-page"><section className="library-hero"><span className="section-kicker">在线书库</span><h1>找一本书，安静地读下去。</h1><p>从已配置的书源搜索并继续上次阅读。</p><form onSubmit={search}><Icon name="search" /><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入书名或作者" />{loading ? <button type="button" className="primary-button" onClick={stopSearch}>停止搜索</button> : <button type="submit" className="primary-button">搜索</button>}</form><label className="library-source-select">搜索范围<select value={selected?.id ?? ''} onChange={event => onSelect(sources.find(source => source.id === event.target.value) ?? null)}><option value="">全部书源</option>{sources.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}</select></label>{(loading || stopped) && <p className="library-search-status">{stopped ? `已停止，已获得 ${groups.length} 本书。` : `已获得 ${groups.length} 本书，已检查 ${completed} / ${total} 个书源${failures || empty ? `，失败 ${failures} 个、无结果 ${empty} 个` : ''}。`}</p>}</section>{message && <p className="form-error library-message">{message}</p>}{groups.length > 0 && <><section className="library-result-filters" aria-label="筛选搜索结果"><label><Icon name="search" /><input value={filters.query} onChange={event => setFilters(current => ({ ...current, query: event.target.value }))} placeholder="筛选书名或作者" /></label><label>书源<select value={filters.minimumSources} onChange={event => setFilters(current => ({ ...current, minimumSources: Number(event.target.value) as SearchFilters['minimumSources'] }))}><option value={1}>全部</option><option value={2}>2 个及以上</option><option value={3}>3 个及以上</option></select></label><label><input type="checkbox" checked={filters.withIntro} onChange={event => setFilters(current => ({ ...current, withIntro: event.target.checked }))} />有简介</label><label><input type="checkbox" checked={filters.withCover} onChange={event => setFilters(current => ({ ...current, withCover: event.target.checked }))} />有封面</label></section><section className="library-results"><header><h2>搜索结果</h2><small>{visibleGroups.length} 本书{hasFilters && ` / ${groups.length}`}</small></header><div>{visibleGroups.map(group => <button key={group.key} onClick={() => void open(group)}><span className="result-mark"><Icon name="book" /></span><span><strong>{group.name}</strong><small>{group.author || '未知作者'} · {group.sources.length} 个书源</small></span><Icon name="arrowRight" /></button>)}</div>{visibleGroups.length === 0 && <p className="library-filter-empty">没有符合当前筛选条件的书籍。</p>}</section></>}{openBook && <BookInfoSummary book={openBook} choices={choices} resumeIndex={resumeIndex} onOpen={index => onOpen(openBook, index)} onChooseSource={choice => void handleChooseSource(choice)} />}</main>
}

function BookManageModal({
  item,
  onClose,
  onSwitchSource,
  onCache,
  onCancelCache,
  onToggleCompleted,
  onRemove,
}: {
  item: BookshelfItem
  onClose: () => void
  onSwitchSource: () => void
  onCache: () => void
  onCancelCache: () => void
  onToggleCompleted: () => void
  onRemove: () => void
}) {
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
          <button className="manage-action-row" onClick={() => { onClose(); onSwitchSource() }}>
            <div className="action-icon"><Icon name="sliders" /></div>
            <div className="action-text">
              <strong>切换书源</strong>
              <small>{item.alternateSources?.length ? `已有 ${item.alternateSources.length} 个备选书源，可全网检索` : '在其他书源中搜索匹配并无缝替换'}</small>
            </div>
            <Icon name="arrowRight" />
          </button>

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
              <small>{item.completed ? '移回「正在阅读」分组' : '移至「已读完」分组'}</small>
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
    </div>
  )
}

function ShelfPage({ onOpen }: { onOpen: (item: BookshelfItem) => void }) {
  const [items, setItems] = useState<BookshelfItem[]>([])
  const [message, setMessage] = useState('')
  const [switchingItem, setSwitchingItem] = useState<BookshelfItem | null>(null)
  const [managingItem, setManagingItem] = useState<BookshelfItem | null>(null)
  const [view, setView] = useState<'reading' | 'completed' | 'all'>('reading')
  const previousStateRef = useRef<Map<string, string>>(new Map())

  const load = useCallback(async () => {
    try {
      const list = await api.bookshelf()
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
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '无法载入书架')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

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
    await api.switchBookshelfSource({
      oldSourceId: switchingItem.sourceId,
      oldBookUrl: switchingItem.bookUrl,
      book: {
        sourceId: details.sourceId,
        bookUrl: chosen.result.bookUrl,
        name: details.name?.trim() || switchingItem.name,
        author: details.author?.trim() || switchingItem.author,
        tocUrl: details.tocUrl,
        coverUrl: details.coverUrl,
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

  const visibleItems = useMemo(() => view === 'all' ? items : items.filter(item => view === 'completed' ? item.completed : !item.completed), [items, view])
  const counts = { reading: items.filter(item => !item.completed).length, completed: items.filter(item => item.completed).length, all: items.length }

  return (
    <main className="shelf-page">
      <header className="page-title">
        <div>
          <span className="section-kicker">我的阅读</span>
          <h1>书架</h1>
          <p>继续上次未读完的故事。</p>
        </div>
        <small>{visibleItems.length} 本书</small>
      </header>

      <nav className="shelf-tabs" aria-label="书架分组">
        {([['reading', '正在阅读'], ['completed', '已读完'], ['all', '全部']] as const).map(([key, label]) => (
          <button key={key} className={view === key ? 'active' : ''} onClick={() => setView(key)}>
            {label}<small>{counts[key]}</small>
          </button>
        ))}
      </nav>

      {message && <p className="form-error">{message}</p>}

      {items.length === 0 && !message ? (
        <section className="shelf-empty">
          <Icon name="book" />
          <h2>书架还是空的</h2>
          <p>打开一本书开始阅读，它会自动出现在这里。</p>
        </section>
      ) : visibleItems.length === 0 ? (
        <section className="shelf-empty">
          <Icon name="book" />
          <h2>这个分组还是空的</h2>
        </section>
      ) : (
        <section className="shelf-grid">
          {visibleItems.map(item => {
            const badge = cacheBadge(item)
            const isCaching = item.cacheState === 'caching'
            const percent = Math.min(100, Math.round((item.cachedChapters / Math.max(1, item.totalChapters || 1)) * 100))

            return (
              <article key={`${item.sourceId}-${item.bookUrl}`} className="shelf-card">
                {/* Upper Half: Large Cover directly opens reader */}
                <div
                  className="shelf-card-cover"
                  onClick={() => onOpen(item)}
                  role="button"
                  tabIndex={0}
                  aria-label={`继续阅读 ${item.name}`}
                >
                  {item.coverKey ? <img src={api.cover(item.coverKey)} alt="" /> : <span className="cover-fallback">{item.name.slice(0, 1)}</span>}
                  {badge && <span className={`shelf-card-badge ${item.cacheState}`}>{badge}</span>}
                  {isCaching && (
                    <div className="shelf-card-progress-track">
                      <div className="shelf-card-progress-fill" style={{ width: `${percent}%` }} />
                    </div>
                  )}
                </div>

                {/* Middle: Title, Author, Reading Progress */}
                <div className="shelf-card-info">
                  <h3 className="shelf-card-name" onClick={() => onOpen(item)} title={item.name}>{item.name}</h3>
                  <p className="shelf-card-author">{item.author || '未知作者'}</p>
                  <div className="shelf-card-meta">
                    <span className={`shelf-meta-chapter ${item.completed ? 'completed' : ''}`}>
                      {item.completed ? '已读完' : item.chapterIndex === undefined ? '刚加入书架' : `第 ${item.chapterIndex + 1} 章`}
                    </span>
                  </div>
                </div>

                {/* Bottom Action Section: Clear Read Button & Manage Button */}
                <div className="shelf-card-footer">
                  <button className="shelf-btn-read" onClick={() => onOpen(item)}>
                    {item.completed ? '重新阅读' : '继续阅读'}
                  </button>
                  <button
                    className="shelf-btn-manage"
                    onClick={() => setManagingItem(item)}
                    aria-label={`管理 ${item.name}`}
                    title="书籍管理"
                  >
                    <Icon name="more" />
                  </button>
                </div>
              </article>
            )
          })}
        </section>
      )}

      {/* Book Management Modal */}
      {managingItem && (
        <BookManageModal
          item={managingItem}
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
    </main>
  )
}

function App() {
  const [ready, setReady] = useState(false); const [authenticated, setAuthenticated] = useState(false); const [settings, setSettings] = useState<ReaderSettings>(loadReaderSettings); const [page, setPage] = useState<Page>(pageFromHash); const [sources, setSources] = useState<SourceSummary[]>([]); const [selected, setSelected] = useState<SourceSummary | null>(null); const [reader, setReader] = useState<{ book: OpenBook; index: number } | null>(() => { try { return JSON.parse(sessionStorage.getItem(readerStorageKey) ?? 'null') } catch { return null } })
  useEffect(() => { saveReaderSettings(settings) }, [settings])
  useEffect(() => { void api.session().then(result => { setAuthenticated(result.authenticated); setCsrfToken(result.csrfToken ?? null); if (result.authenticated) void api.sources().then(setSources).catch(() => undefined) }).finally(() => setReady(true)) }, [])
  useEffect(() => { const sync = () => setPage(pageFromHash()); addEventListener('hashchange', sync); return () => removeEventListener('hashchange', sync) }, [])
  const navigate = (next: Page) => { if (next === 'reader' && !reader) return; location.hash = `#${next}`; setPage(next) }
  const openReader = (book: OpenBook, index: number) => {
    void api.addToBookshelf({
      sourceId: book.details.sourceId,
      bookUrl: book.bookUrl,
      name: book.details.name,
      author: book.details.author,
      tocUrl: book.details.tocUrl,
      coverUrl: book.details.coverUrl,
      alternateSources: book.details.alternateSources,
    }).catch(() => undefined)
    const value = { book, index }
    setReader(value)
    sessionStorage.setItem(readerStorageKey, JSON.stringify(value))
    location.hash = '#reader'
    setPage('reader')
  }
  const openShelfItem = async (item: BookshelfItem) => {
    try {
      const details = await api.details(item.sourceId, item.bookUrl)
      const safeDetails: BookDetails = {
        ...details,
        name: details.name?.trim() || item.name || '未知书名',
        author: details.author?.trim() || item.author,
        alternateSources: item.alternateSources,
      }
      const [chapters, progress] = await Promise.all([api.chapters(safeDetails.sourceId, safeDetails.tocUrl), api.progress(details.sourceId, item.bookUrl)])
      openReader({ details: safeDetails, bookUrl: item.bookUrl, chapters, progress }, progress?.chapterIndex ?? 0)
    } catch {
      navigate('shelf')
    }
  }
  const logout = async () => { try { await api.logout() } finally { setCsrfToken(null); setAuthenticated(false) } }
  if (!ready) return <main className={`app-loading theme-${settings.theme}`}><span>正在打开阅读空间...</span></main>
  if (!authenticated) return <div className={`app-shell theme-${settings.theme}`}><ToastContainer /><Login onLogin={() => { setAuthenticated(true); void api.sources().then(setSources).catch(() => undefined) }} /></div>
  if (page === 'reader' && reader) return <div className={`app-shell theme-${settings.theme}`}><ToastContainer /><ReaderScreen openBook={reader.book} startIndex={reader.index} settings={settings} onSettingsChange={setSettings} onClose={() => navigate('shelf')} /></div>
  const refreshSources = () => { void api.sources().then(setSources).catch(() => undefined) }
  return (
    <div className={`app-shell theme-${settings.theme}`}>
      <ToastContainer />
      <AppHeader page={page} settings={settings} onSettingsChange={setSettings} onNavigate={navigate} onLogout={() => void logout()} />
      {page === 'sources' ? <SourcesPage selected={selected} onSelect={setSelected} onSourcesChange={setSources} /> : page === 'subscriptions' ? <SubscriptionPage onSourcesChange={refreshSources} /> : page === 'shelf' ? <ShelfPage onOpen={item => void openShelfItem(item)} /> : <LibraryPage selected={selected} sources={sources} onSelect={setSelected} onOpen={openReader} />}
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<App />)
