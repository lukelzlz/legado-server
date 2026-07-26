import { FormEvent, useCallback, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, SearchResult, setCsrfToken, SourceRecord, SourceSummary } from './api'
import { Icon } from './icons'
import { OpenBook, ReaderScreen } from './ReaderScreen'
import { loadReaderSettings, ReaderSettings, saveReaderSettings } from './readerSettings'
import './styles.css'

function ToolButton({ label, icon, onClick, className = '' }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void; className?: string }) {
  return <button className={`tool-button ${className}`} title={label} aria-label={label} onClick={onClick}><Icon name={icon} /></button>
}

function Login({ onLogin }: { onLogin: () => void }) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError('')
    try { const result = await api.login(password); setCsrfToken(result.csrfToken); onLogin() } catch (cause) { setError(cause instanceof Error ? cause.message : '登录失败') } finally { setBusy(false) }
  }
  return <main className="login-shell"><form className="login-panel" onSubmit={submit}><div className="login-mark"><Icon name="book" /><strong>阅读服务器</strong></div><h1>回到你的阅读工作台</h1><p>输入部署时设置的单用户密码继续。</p><label>密码<input autoFocus type="password" value={password} onChange={event => setPassword(event.target.value)} minLength={12} required /></label>{error && <p className="form-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy ? '正在验证...' : '登录'}</button></form></main>
}

function SourceEditor({ selected, onSaved }: { selected: SourceSummary | null; onSaved: () => void }) {
  const [record, setRecord] = useState<SourceRecord | null>(null)
  const [text, setText] = useState('')
  const [status, setStatus] = useState('')
  useEffect(() => {
    if (!selected) { setRecord(null); setText(''); return }
    void api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message))
  }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="source-editor empty-editor"><Icon name="book" /><h2>选择一个书源</h2><p>从左侧列表选择书源，或导入一个 JSON 文件。</p></section>
  return <section className="source-editor"><header><div><span className="section-kicker">书源编辑</span><h2>{selected.name}</h2><small>{selected.url}</small></div><div className="editor-actions"><button className="subtle-button" onClick={() => void validate}>校验</button><button className="primary-button" onClick={() => void save}>保存</button></div></header><textarea aria-label="书源 JSON 编辑器" value={text} onChange={event => setText(event.target.value)} spellCheck={false} />{status && <footer className={status.includes('失败') || status.includes('错误') ? 'form-error' : ''}>{status}</footer>}</section>
}

function ReadingDesk({ selected, settings, onSettingsChange }: { selected: SourceSummary | null; settings: ReaderSettings; onSettingsChange: (next: ReaderSettings) => void }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<SearchResult[]>([])
  const [openBook, setOpenBook] = useState<OpenBook | null>(null)
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)
  const [readerStart, setReaderStart] = useState<number | null>(null)
  const search = async (event: FormEvent) => { event.preventDefault(); if (!keyword.trim()) return; setLoading(true); setMessage(''); setOpenBook(null); try { setResults(await api.search(keyword, selected ? [selected.id] : undefined)) } catch (error) { setMessage(error instanceof Error ? error.message : '搜索失败') } finally { setLoading(false) } }
  const open = async (result: SearchResult) => { setLoading(true); setMessage(''); try { const details = await api.details(result.sourceId, result.bookUrl); const [chapters, progress] = await Promise.all([api.chapters(details.sourceId, details.tocUrl), api.progress(details.sourceId, result.bookUrl)]); setOpenBook({ details, bookUrl: result.bookUrl, chapters, progress }) } catch (error) { setMessage(error instanceof Error ? error.message : '无法读取书籍') } finally { setLoading(false) } }
  if (openBook && readerStart !== null) return <ReaderScreen key={`${openBook.details.sourceId}-${openBook.bookUrl}`} openBook={openBook} startIndex={readerStart} settings={settings} onSettingsChange={onSettingsChange} onClose={() => setReaderStart(null)} />
  const resumeIndex = openBook?.progress ? Math.min(Math.max(openBook.progress.chapterIndex, 0), Math.max(0, openBook.chapters.length - 1)) : 0
  return <section className="reading-desk"><header className="desk-header"><div><span className="section-kicker">在线阅读</span><h2>搜索与阅读</h2></div><form onSubmit={search}><Icon name="search" /><input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入书名或作者" /><button className="primary-button" disabled={loading}>{loading ? '搜索中...' : '搜索'}</button></form></header>{message && <p className="form-error desk-message">{message}</p>}{results.length > 0 && <div className="search-results">{results.map(result => <button key={`${result.sourceId}-${result.bookUrl}`} onClick={() => void open(result)}><span className="result-mark"><Icon name="book" /></span><span><strong>{result.name}</strong><small>{result.author || result.sourceId}</small></span><Icon name="arrowRight" /></button>)}</div>}{openBook && <section className="book-preview"><header><div><span className="section-kicker">书籍详情</span><h3>{openBook.details.name}</h3><p>{openBook.details.author || '未知作者'}</p></div><button className="primary-button" onClick={() => setReaderStart(resumeIndex)}>{openBook.progress ? '继续阅读' : '开始阅读'}<Icon name="arrowRight" /></button></header>{openBook.details.intro && <p className="book-intro">{openBook.details.intro}</p>}<div className="preview-chapters">{openBook.chapters.slice(0, 16).map(item => <button className={item.index === resumeIndex ? 'resume-chapter' : ''} key={item.url} onClick={() => setReaderStart(item.index)}><span>{item.title}</span>{item.index === resumeIndex && openBook.progress && <small>上次阅读</small>}</button>)}</div>{openBook.chapters.length > 16 && <p className="chapter-count">共 {openBook.chapters.length} 章，进入阅读器查看完整目录</p>}</section>}</section>
}

function Workspace({ settings, onSettingsChange, onLogout }: { settings: ReaderSettings; onSettingsChange: (next: ReaderSettings) => void; onLogout: () => void }) {
  const [sources, setSources] = useState<SourceSummary[]>([])
  const [selected, setSelected] = useState<SourceSummary | null>(null)
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState('')
  const load = useCallback(async () => { try { setSources(await api.sources(query)) } catch (error) { setNotice(error instanceof Error ? error.message : '无法载入书源') } }, [query])
  useEffect(() => { const timer = window.setTimeout(() => { void load() }, 180); return () => window.clearTimeout(timer) }, [load])
  const importSources = async (file: File | undefined) => { if (!file) return; try { const parsed = JSON.parse(await file.text()); const values = Array.isArray(parsed) ? parsed : [parsed]; const result = await api.import(values.map(value => JSON.stringify(value))); setNotice(`已导入 ${result.imported} 个书源${result.skipped ? `，跳过 ${result.skipped} 个` : ''}`); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '导入失败') } }
  const remove = async () => { if (!selected || !confirm(`删除“${selected.name}”？`)) return; try { await api.remove(selected.id); setSelected(null); await load() } catch (error) { setNotice(error instanceof Error ? error.message : '删除失败') } }
  const logout = async () => { try { await api.logout() } finally { setCsrfToken(null); onLogout() } }
  return <main className="app-workspace"><aside className="source-sidebar"><header><div className="workspace-brand"><Icon name="book" /><strong>阅读服务器</strong></div><ToolButton label="退出登录" icon="more" onClick={() => void logout()} /></header><div className="source-filter"><Icon name="search" /><input placeholder="筛选书源" value={query} onChange={event => setQuery(event.target.value)} /></div><label className="import-button"><Icon name="upload" />导入 JSON<input type="file" accept="application/json,.json" onChange={event => void importSources(event.target.files?.[0])} /></label>{notice && <p className="sidebar-notice">{notice}</p>}<div className="source-list-label">书源列表 <span>{sources.length}</span></div><nav className="source-list">{sources.map(source => <button className={selected?.id === source.id ? 'selected' : ''} key={source.id} onClick={() => setSelected(source)}><span>{source.name}</span><small>{source.group || (source.isJsSource ? 'JS 书源' : '书源')}</small></button>)}</nav><footer><span>当前主题</span><div className="workspace-themes">{(['light', 'paper', 'dark'] as const).map(theme => <button key={theme} className={`theme-${theme} ${settings.theme === theme ? 'selected' : ''}`} aria-label={theme === 'light' ? '晓白' : theme === 'paper' ? '护眼' : '夜读'} onClick={() => onSettingsChange({ ...settings, theme })} />)}</div></footer></aside><section className="workspace-content"><header className="workspace-topbar"><div><span>书源管理</span><strong>{selected?.name || '阅读工作台'}</strong></div>{selected && <button className="danger-button" onClick={() => void remove()}>删除书源</button>}</header><div className="workspace-scroll"><SourceEditor selected={selected} onSaved={() => void load()} /><ReadingDesk selected={selected} settings={settings} onSettingsChange={onSettingsChange} /></div></section></main>
}

function App() {
  const [ready, setReady] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [settings, setSettings] = useState<ReaderSettings>(loadReaderSettings)
  useEffect(() => { saveReaderSettings(settings) }, [settings])
  useEffect(() => { void api.session().then(result => { setAuthenticated(result.authenticated); setCsrfToken(result.csrfToken ?? null) }).finally(() => setReady(true)) }, [])
  if (!ready) return <main className={`app-loading theme-${settings.theme}`}><span>正在打开阅读工作台...</span></main>
  return <div className={`app-shell theme-${settings.theme}`}>{authenticated ? <Workspace settings={settings} onSettingsChange={setSettings} onLogout={() => setAuthenticated(false)} /> : <Login onLogin={() => setAuthenticated(true)} />}</div>
}

createRoot(document.getElementById('root')!).render(<App />)
