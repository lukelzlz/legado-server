import { CSSProperties, FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, BookDetails, Chapter, ReadingProgress, SearchResult, setCsrfToken, SourceRecord, SourceSummary } from './api'
import { clampScrollPosition, loadReaderSettings, ReaderSettings, saveReaderSettings, scrollPosition } from './readerSettings'
import './styles.css'

function Login({ onLogin }: { onLogin: () => void }) {
  const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(''); try { const result = await api.login(password); setCsrfToken(result.csrfToken); onLogin() } catch (cause) { setError(cause instanceof Error ? cause.message : '登录失败') } finally { setBusy(false) } }
  return <main className="login-shell"><form className="login-card" onSubmit={submit}><div className="brand">阅读服务器</div><h1>继续管理你的书源</h1><p>使用部署时配置的单用户密码登录。</p><label>密码<input autoFocus type="password" value={password} onChange={e => setPassword(e.target.value)} minLength={12} required /></label>{error && <div className="error" role="alert">{error}</div>}<button disabled={busy}>{busy ? '正在验证...' : '登录'}</button></form></main>
}

function Editor({ selected, onSaved }: { selected: SourceSummary | null; onSaved: () => void }) {
  const [record, setRecord] = useState<SourceRecord | null>(null); const [text, setText] = useState(''); const [status, setStatus] = useState('')
  useEffect(() => { if (!selected) { setRecord(null); setText(''); return }; api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message)) }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="editor empty"><h2>选择一个书源</h2><p>从左侧列表选择，或导入新的书源 JSON。</p></section>
  return <section className="editor"><header><div><h2>{selected.name}</h2><small>{selected.url}</small></div><div className="actions"><button className="secondary" onClick={validate}>校验</button><button onClick={save}>保存</button></div></header><textarea aria-label="书源 JSON 编辑器" value={text} onChange={e => setText(e.target.value)} spellCheck={false} />{status && <footer className={status.includes('失败') || status.includes('错误') ? 'error' : ''}>{status}</footer>}</section>
}

type OpenBook = { details: BookDetails; bookUrl: string; chapters: Chapter[]; progress?: ReadingProgress }

function ReaderScreen({ openBook, startIndex, onClose }: { openBook: OpenBook; startIndex: number; onClose: () => void }) {
  const [chapterIndex, setChapterIndex] = useState(startIndex); const [content, setContent] = useState(''); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(true)
  const [showToc, setShowToc] = useState(false); const [showSettings, setShowSettings] = useState(false); const [settings, setSettings] = useState<ReaderSettings>(loadReaderSettings)
  const currentRef = useRef<{ chapter: Chapter; position: number } | null>(null); const timerRef = useRef<number | null>(null); const restoredRef = useRef(false)
  const chapter = openBook.chapters[chapterIndex]
  const persist = useCallback(() => {
    const current = currentRef.current
    if (!current) return
    void api.saveProgress(openBook.details.sourceId, openBook.bookUrl, current.chapter.url, current.chapter.index, clampScrollPosition(current.position)).catch(() => undefined)
  }, [openBook.bookUrl, openBook.details.sourceId])
  const changeChapter = useCallback((nextIndex: number) => {
    if (nextIndex < 0 || nextIndex >= openBook.chapters.length || nextIndex === chapterIndex) return
    persist(); setChapterIndex(nextIndex); setShowToc(false)
  }, [chapterIndex, openBook.chapters.length, persist])

  useEffect(() => { saveReaderSettings(settings) }, [settings])
  useEffect(() => {
    let cancelled = false
    setLoading(true); setMessage(''); setContent('')
    void api.content(openBook.details.sourceId, chapter.url).then(result => {
      if (cancelled) return
      setContent(result.content)
      const position = !restoredRef.current && chapter.index === startIndex ? openBook.progress?.scrollPosition ?? 0 : 0
      restoredRef.current = true
      currentRef.current = { chapter, position }
      window.requestAnimationFrame(() => {
        const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
        window.scrollTo({ top: maxScroll * position, behavior: 'auto' })
      })
    }).catch(error => { if (!cancelled) setMessage(error instanceof Error ? error.message : '无法读取正文') }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [chapter, openBook.details.sourceId, openBook.progress?.scrollPosition, startIndex])
  useEffect(() => {
    const saveAfterScroll = () => {
      const current = currentRef.current
      if (!current) return
      current.position = scrollPosition(window.scrollY, document.documentElement.scrollHeight, window.innerHeight)
      if (timerRef.current !== null) return
      timerRef.current = window.setTimeout(() => { timerRef.current = null; persist() }, 1200)
    }
    const onVisibilityChange = () => { if (document.visibilityState === 'hidden') persist() }
    window.addEventListener('scroll', saveAfterScroll, { passive: true }); document.addEventListener('visibilitychange', onVisibilityChange)
    return () => { window.removeEventListener('scroll', saveAfterScroll); document.removeEventListener('visibilitychange', onVisibilityChange); if (timerRef.current !== null) window.clearTimeout(timerRef.current); persist() }
  }, [persist])
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, button')) return
      if (event.key === 'ArrowLeft') { event.preventDefault(); changeChapter(chapterIndex - 1) }
      if (event.key === 'ArrowRight') { event.preventDefault(); changeChapter(chapterIndex + 1) }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [chapterIndex, changeChapter])
  const readerStyle = { '--reader-font-size': `${settings.fontSize}px`, '--reader-line-height': settings.lineHeight } as CSSProperties

  return <main className={`reader-page theme-${settings.theme}`} style={readerStyle}>
    <header className="reader-topbar"><button className="icon-button" title="返回书籍详情" aria-label="返回书籍详情" onClick={() => { persist(); onClose() }}>返回</button><div className="reader-heading"><strong>{openBook.details.name}</strong><span>{chapter?.title}</span></div><div className="reader-top-actions"><button className="icon-button" title="目录" aria-label="目录" onClick={() => setShowToc(true)}>目录</button><button className="icon-button" title="阅读设置" aria-label="阅读设置" onClick={() => setShowSettings(true)}>设置</button></div></header>
    <aside className={`reader-drawer toc-drawer ${showToc ? 'open' : ''}`} aria-label="章节目录"><header><strong>目录</strong><button className="text-button" onClick={() => setShowToc(false)}>关闭</button></header><div className="drawer-book"><strong>{openBook.details.name}</strong><small>{openBook.details.author || '未知作者'} · {openBook.chapters.length} 章</small></div><nav>{openBook.chapters.map(item => <button className={item.index === chapterIndex ? 'current' : ''} key={item.url} onClick={() => changeChapter(item.index)}>{item.title}</button>)}</nav></aside>
    <aside className={`reader-drawer settings-drawer ${showSettings ? 'open' : ''}`} aria-label="阅读设置"><header><strong>阅读设置</strong><button className="text-button" onClick={() => setShowSettings(false)}>关闭</button></header><div className="settings-body"><span>主题</span><div className="theme-options">{(['light', 'paper', 'dark'] as const).map(theme => <button className={settings.theme === theme ? 'selected' : ''} key={theme} onClick={() => setSettings(value => ({ ...value, theme }))}>{theme === 'light' ? '浅色' : theme === 'paper' ? '护眼' : '深色'}</button>)}</div><label>字号 <output>{settings.fontSize}px</output><input type="range" min="15" max="28" value={settings.fontSize} onChange={e => setSettings(value => ({ ...value, fontSize: Number(e.target.value) }))} /></label><label>行距 <output>{settings.lineHeight.toFixed(2)}</output><input type="range" min="1.45" max="2.4" step="0.05" value={settings.lineHeight} onChange={e => setSettings(value => ({ ...value, lineHeight: Number(e.target.value) }))} /></label></div></aside>
    {(showToc || showSettings) && <button className="drawer-backdrop" aria-label="关闭面板" onClick={() => { setShowToc(false); setShowSettings(false) }} />}
    <article className="reader-body">{loading && <p className="reader-status">正在加载正文...</p>}{message && <p className="error reader-status">{message}</p>}{content && <>{content.split('\n').filter(Boolean).map((line, index) => <p key={index}>{line}</p>)}<footer className="chapter-navigation"><button className="secondary" disabled={chapterIndex === 0 || loading} onClick={() => changeChapter(chapterIndex - 1)}>上一章</button><span>{chapterIndex + 1} / {openBook.chapters.length}</span><button disabled={chapterIndex === openBook.chapters.length - 1 || loading} onClick={() => changeChapter(chapterIndex + 1)}>下一章</button></footer></>}</article>
  </main>
}

function Reader({ selected }: { selected: SourceSummary | null }) {
  const [keyword, setKeyword] = useState(''); const [results, setResults] = useState<SearchResult[]>([]); const [openBook, setOpenBook] = useState<OpenBook | null>(null); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(false); const [readerStart, setReaderStart] = useState<number | null>(null)
  const search = async (event: FormEvent) => { event.preventDefault(); if (!keyword.trim()) return; setLoading(true); setMessage(''); setOpenBook(null); try { setResults(await api.search(keyword, selected ? [selected.id] : undefined)) } catch (error) { setMessage(error instanceof Error ? error.message : '搜索失败') } finally { setLoading(false) } }
  const open = async (result: SearchResult) => { setLoading(true); setMessage(''); try { const details = await api.details(result.sourceId, result.bookUrl); const [chapters, progress] = await Promise.all([api.chapters(details.sourceId, details.tocUrl), api.progress(details.sourceId, result.bookUrl)]); setOpenBook({ details, bookUrl: result.bookUrl, chapters, progress }) } catch (error) { setMessage(error instanceof Error ? error.message : '无法读取书籍') } finally { setLoading(false) } }
  if (openBook && readerStart !== null) return <ReaderScreen key={`${openBook.details.sourceId}-${openBook.bookUrl}`} openBook={openBook} startIndex={readerStart} onClose={() => setReaderStart(null)} />
  const resumeIndex = openBook?.progress ? Math.min(Math.max(openBook.progress.chapterIndex, 0), Math.max(0, openBook.chapters.length - 1)) : 0
  return <section className="reader-panel"><header><div><h2>书籍阅读</h2><small>搜索、选择章节后进入沉浸式阅读</small></div><form onSubmit={search}><input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="输入书名或作者" /><button disabled={loading}>{loading ? '请求中...' : '搜索'}</button></form></header>{message && <p className="error panel-message">{message}</p>}<div className="book-search-results">{results.map(result => <button key={`${result.sourceId}-${result.bookUrl}`} onClick={() => void open(result)}><strong>{result.name}</strong><span>{result.author || result.sourceId}</span></button>)}</div>{openBook && <section className="book-detail"><header><div><h3>{openBook.details.name}</h3><p>{openBook.details.author || '未知作者'}</p></div><button onClick={() => setReaderStart(resumeIndex)}>{openBook.progress ? '继续阅读' : '开始阅读'}</button></header>{openBook.details.intro && <p className="book-intro">{openBook.details.intro}</p>}<div className="chapter-list">{openBook.chapters.map(item => <button className={item.index === resumeIndex ? 'resume-chapter' : ''} key={item.url} onClick={() => setReaderStart(item.index)}><span>{item.title}</span>{item.index === resumeIndex && openBook.progress && <small>上次阅读</small>}</button>)}</div></section>}</section>
}

function Workspace({ onLogout }: { onLogout: () => void }) {
  const [sources, setSources] = useState<SourceSummary[]>([]); const [selected, setSelected] = useState<SourceSummary | null>(null); const [query, setQuery] = useState(''); const [notice, setNotice] = useState('')
  const load = useCallback(async () => { try { setSources(await api.sources(query)) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入书源') } }, [query])
  useEffect(() => { const timer = window.setTimeout(() => { void load() }, 180); return () => window.clearTimeout(timer) }, [load])
  const importSources = async (file: File | undefined) => { if (!file) return; try { const parsed = JSON.parse(await file.text()); const values = Array.isArray(parsed) ? parsed : [parsed]; const result = await api.import(values.map(value => JSON.stringify(value))); setNotice(`已导入 ${result.imported} 个书源${result.skipped ? `，跳过 ${result.skipped} 个` : ''}`); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '导入失败') } }
  const remove = async () => { if (!selected || !confirm(`删除“${selected.name}”？`)) return; try { await api.remove(selected.id); setSelected(null); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } }
  const logout = async () => { try { await api.logout() } finally { setCsrfToken(null); onLogout() } }
  return <main className="workspace"><aside><header><div className="brand">阅读服务器</div><button className="text-button" onClick={logout}>退出</button></header><div className="toolbar"><input placeholder="筛选书源" value={query} onChange={e => setQuery(e.target.value)} /><label className="import">导入 JSON<input type="file" accept="application/json,.json" onChange={e => void importSources(e.target.files?.[0])} /></label></div>{notice && <div className="notice">{notice}</div>}<nav>{sources.map(source => <button className={selected?.id === source.id ? 'source selected' : 'source'} key={source.id} onClick={() => setSelected(source)}><span>{source.name}</span><small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small></button>)}</nav></aside><div className="content"><Editor selected={selected} onSaved={() => void load()} />{selected && <button className="danger" onClick={remove}>删除当前书源</button>}<Reader selected={selected} /></div></main>
}

function App() { const [ready, setReady] = useState(false); const [authenticated, setAuthenticated] = useState(false); useEffect(() => { api.session().then(result => { setAuthenticated(result.authenticated); setCsrfToken(result.csrfToken ?? null) }).finally(() => setReady(true)) }, []); if (!ready) return null; return authenticated ? <Workspace onLogout={() => setAuthenticated(false)} /> : <Login onLogin={() => setAuthenticated(true)} /> }

createRoot(document.getElementById('root')!).render(<App />)
