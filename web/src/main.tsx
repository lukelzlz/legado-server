import { FormEvent, useCallback, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, SearchResult, setCsrfToken, SourceRecord, SourceSummary } from './api'
import { Icon } from './icons'
import { OpenBook, ReaderScreen } from './ReaderScreen'
import { loadReaderSettings, ReaderSettings, saveReaderSettings } from './readerSettings'
import './styles.css'

type Page = 'sources' | 'library' | 'reader'
const readerStorageKey = 'legado-open-book-v1'
const pageFromHash = (): Page => location.hash === '#sources' ? 'sources' : location.hash === '#reader' ? 'reader' : 'library'

function ToolButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return <button className="tool-button" title={label} aria-label={label} onClick={onClick}><Icon name={icon} /></button>
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(''); try { const result = await api.login(password); setCsrfToken(result.csrfToken); onLogin() } catch (cause) { setError(cause instanceof Error ? cause.message : '登录失败') } finally { setBusy(false) } }
  return <main className="login-shell"><form className="login-panel" onSubmit={submit}><div className="login-mark"><Icon name="book" /><strong>阅读服务器</strong></div><h1>回到你的阅读空间</h1><p>输入部署时设置的单用户密码继续。</p><label>密码<input autoFocus type="password" value={password} onChange={event => setPassword(event.target.value)} minLength={12} required /></label>{error && <p className="form-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy ? '正在验证...' : '登录'}</button></form></main>
}

function AppHeader({ page, settings, onSettingsChange, onNavigate, onLogout }: { page: Page; settings: ReaderSettings; onSettingsChange: (next: ReaderSettings) => void; onNavigate: (page: Page) => void; onLogout: () => void }) {
  return <header className="app-page-header"><button className="app-brand" onClick={() => onNavigate('library')}><Icon name="book" /><strong>阅读服务器</strong></button><nav><button className={page === 'library' ? 'active' : ''} onClick={() => onNavigate('library')}>书库</button><button className={page === 'sources' ? 'active' : ''} onClick={() => onNavigate('sources')}>书源</button></nav><div className="header-actions"><div className="header-themes" aria-label="全站主题">{(['light', 'paper', 'dark'] as const).map(theme => <button key={theme} className={`theme-${theme} ${settings.theme === theme ? 'selected' : ''}`} aria-label={theme === 'light' ? '晓白' : theme === 'paper' ? '护眼' : '夜读'} onClick={() => onSettingsChange({ ...settings, theme })} />)}</div><ToolButton label="退出登录" icon="more" onClick={onLogout} /></div></header>
}

function SourceEditor({ selected, onSaved }: { selected: SourceSummary | null; onSaved: () => void }) {
  const [record, setRecord] = useState<SourceRecord | null>(null); const [text, setText] = useState(''); const [status, setStatus] = useState('')
  useEffect(() => { if (!selected) { setRecord(null); setText(''); return }; void api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message)) }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="source-editor empty-editor"><Icon name="book" /><h2>选择一个书源</h2><p>从列表选择书源，或导入一个 JSON 文件。</p></section>
  return <section className="source-editor"><header><div><span className="section-kicker">书源编辑</span><h2>{selected.name}</h2><small>{selected.url}</small></div><div className="editor-actions"><button className="subtle-button" onClick={() => void validate}>校验</button><button className="primary-button" onClick={() => void save}>保存</button></div></header><textarea aria-label="书源 JSON 编辑器" value={text} onChange={event => setText(event.target.value)} spellCheck={false} />{status && <footer className={status.includes('失败') || status.includes('错误') ? 'form-error' : ''}>{status}</footer>}</section>
}

function SourcesPage({ selected, onSelect, onSourcesChange }: { selected: SourceSummary | null; onSelect: (source: SourceSummary | null) => void; onSourcesChange: (sources: SourceSummary[]) => void }) {
  const [sources, setSources] = useState<SourceSummary[]>([]); const [query, setQuery] = useState(''); const [notice, setNotice] = useState('')
  const load = useCallback(async () => { try { const values = await api.sources(query); setSources(values); onSourcesChange(values) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入书源') } }, [onSourcesChange, query])
  useEffect(() => { const timer = window.setTimeout(() => { void load() }, 180); return () => window.clearTimeout(timer) }, [load])
  const importSources = async (file: File | undefined) => { if (!file) return; try { const parsed = JSON.parse(await file.text()); const values = Array.isArray(parsed) ? parsed : [parsed]; const result = await api.import(values.map(value => JSON.stringify(value))); setNotice(`已导入 ${result.imported} 个书源${result.skipped ? `，跳过 ${result.skipped} 个` : ''}`); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '导入失败') } }
  const remove = async () => { if (!selected || !confirm(`删除“${selected.name}”？`)) return; try { await api.remove(selected.id); onSelect(null); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } }
  return <main className="sources-page"><aside className="source-sidebar"><div className="source-sidebar-heading"><span>书源</span><small>{sources.length}</small></div><div className="source-filter"><Icon name="search" /><input placeholder="筛选书源" value={query} onChange={event => setQuery(event.target.value)} /></div><label className="import-button"><Icon name="upload" />导入 JSON<input type="file" accept="application/json,.json" onChange={event => void importSources(event.target.files?.[0])} /></label>{notice && <p className="sidebar-notice">{notice}</p>}<nav className="source-list">{sources.map(source => <button className={selected?.id === source.id ? 'selected' : ''} key={source.id} onClick={() => onSelect(source)}><span>{source.name}</span><small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small></button>)}</nav></aside><section className="sources-content"><header className="page-title"><div><span className="section-kicker">阅读服务器</span><h1>书源管理</h1><p>导入、校验与维护你的阅读来源。</p></div>{selected && <button className="danger-button" onClick={() => void remove()}>删除书源</button>}</header><SourceEditor selected={selected} onSaved={() => void load()} /></section></main>
}

function LibraryPage({ selected, sources, onSelect, onOpen }: { selected: SourceSummary | null; sources: SourceSummary[]; onSelect: (source: SourceSummary | null) => void; onOpen: (book: OpenBook, index: number) => void }) {
  const [keyword, setKeyword] = useState(''); const [results, setResults] = useState<SearchResult[]>([]); const [openBook, setOpenBook] = useState<OpenBook | null>(null); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(false)
  const search = async (event: FormEvent) => { event.preventDefault(); if (!keyword.trim()) return; setLoading(true); setMessage(''); setOpenBook(null); try { setResults(await api.search(keyword, selected ? [selected.id] : undefined)) } catch (error) { setMessage(error instanceof Error ? error.message : '搜索失败') } finally { setLoading(false) } }
  const open = async (result: SearchResult) => { setLoading(true); setMessage(''); try { const details = await api.details(result.sourceId, result.bookUrl); const [chapters, progress] = await Promise.all([api.chapters(details.sourceId, details.tocUrl), api.progress(details.sourceId, result.bookUrl)]); setOpenBook({ details, bookUrl: result.bookUrl, chapters, progress }) } catch (error) { setMessage(error instanceof Error ? error.message : '无法读取书籍') } finally { setLoading(false) } }
  const resumeIndex = openBook?.progress ? Math.min(Math.max(openBook.progress.chapterIndex, 0), Math.max(0, openBook.chapters.length - 1)) : 0
  return <main className="library-page"><section className="library-hero"><span className="section-kicker">在线书库</span><h1>找一本书，安静地读下去。</h1><p>从已配置的书源搜索并继续上次阅读。</p><form onSubmit={search}><Icon name="search" /><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入书名或作者" /><button className="primary-button" disabled={loading}>{loading ? '搜索中...' : '搜索'}</button></form><label className="library-source-select">搜索范围<select value={selected?.id ?? ''} onChange={event => onSelect(sources.find(source => source.id === event.target.value) ?? null)}><option value="">全部书源</option>{sources.map(source => <option key={source.id} value={source.id}>{source.name}</option>)}</select></label></section>{message && <p className="form-error library-message">{message}</p>}{results.length > 0 && <section className="library-results"><header><h2>搜索结果</h2><small>{results.length} 本书</small></header><div>{results.map(result => <button key={`${result.sourceId}-${result.bookUrl}`} onClick={() => void open(result)}><span className="result-mark"><Icon name="book" /></span><span><strong>{result.name}</strong><small>{result.author || result.sourceId}</small></span><Icon name="arrowRight" /></button>)}</div></section>}{openBook && <section className="library-book-detail"><header><div><span className="section-kicker">书籍详情</span><h2>{openBook.details.name}</h2><p>{openBook.details.author || '未知作者'}</p></div><button className="primary-button" onClick={() => onOpen(openBook, resumeIndex)}>{openBook.progress ? '继续阅读' : '开始阅读'}<Icon name="arrowRight" /></button></header>{openBook.details.intro && <p className="book-intro">{openBook.details.intro}</p>}<div className="preview-chapters">{openBook.chapters.slice(0, 16).map(item => <button className={item.index === resumeIndex ? 'resume-chapter' : ''} key={item.url} onClick={() => onOpen(openBook, item.index)}><span>{item.title}</span>{item.index === resumeIndex && openBook.progress && <small>上次阅读</small>}</button>)}</div>{openBook.chapters.length > 16 && <p className="chapter-count">共 {openBook.chapters.length} 章，进入阅读器查看完整目录</p>}</section>}</main>
}

function App() {
  const [ready, setReady] = useState(false); const [authenticated, setAuthenticated] = useState(false); const [settings, setSettings] = useState<ReaderSettings>(loadReaderSettings); const [page, setPage] = useState<Page>(pageFromHash); const [sources, setSources] = useState<SourceSummary[]>([]); const [selected, setSelected] = useState<SourceSummary | null>(null); const [reader, setReader] = useState<{ book: OpenBook; index: number } | null>(() => { try { return JSON.parse(sessionStorage.getItem(readerStorageKey) ?? 'null') } catch { return null } })
  useEffect(() => { saveReaderSettings(settings) }, [settings])
  useEffect(() => { void api.session().then(result => { setAuthenticated(result.authenticated); setCsrfToken(result.csrfToken ?? null); if (result.authenticated) void api.sources().then(setSources).catch(() => undefined) }).finally(() => setReady(true)) }, [])
  useEffect(() => { const sync = () => setPage(pageFromHash()); addEventListener('hashchange', sync); return () => removeEventListener('hashchange', sync) }, [])
  const navigate = (next: Page) => { if (next === 'reader' && !reader) return; location.hash = `#${next}`; setPage(next) }
  const openReader = (book: OpenBook, index: number) => { const value = { book, index }; setReader(value); sessionStorage.setItem(readerStorageKey, JSON.stringify(value)); location.hash = '#reader'; setPage('reader') }
  const logout = async () => { try { await api.logout() } finally { setCsrfToken(null); setAuthenticated(false) } }
  if (!ready) return <main className={`app-loading theme-${settings.theme}`}><span>正在打开阅读空间...</span></main>
  if (!authenticated) return <div className={`app-shell theme-${settings.theme}`}><Login onLogin={() => setAuthenticated(true)} /></div>
  if (page === 'reader' && reader) return <div className={`app-shell theme-${settings.theme}`}><ReaderScreen openBook={reader.book} startIndex={reader.index} settings={settings} onSettingsChange={setSettings} onClose={() => navigate('library')} /></div>
  return <div className={`app-shell theme-${settings.theme}`}><AppHeader page={page} settings={settings} onSettingsChange={setSettings} onNavigate={navigate} onLogout={() => void logout()} />{page === 'sources' ? <SourcesPage selected={selected} onSelect={setSelected} onSourcesChange={setSources} /> : <LibraryPage selected={selected} sources={sources} onSelect={setSelected} onOpen={openReader} />}</div>
}

createRoot(document.getElementById('root')!).render(<App />)
