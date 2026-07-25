import { FormEvent, useCallback, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api, BookDetails, Chapter, SearchResult, setCsrfToken, SourceRecord, SourceSummary } from './api'
import './styles.css'

function Login({ onLogin }: { onLogin: () => void }) {
  const [password, setPassword] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(''); try { const result = await api.login(password); setCsrfToken(result.csrfToken); onLogin() } catch (cause) { setError(cause instanceof Error ? cause.message : '登录失败') } finally { setBusy(false) } }
  return <main className="login-shell"><form className="login-card" onSubmit={submit}><div className="brand">阅读服务器</div><h1>继续管理你的书源</h1><p>使用部署时配置的单用户密码登录。</p><label>密码<input autoFocus type="password" value={password} onChange={e => setPassword(e.target.value)} minLength={12} required /></label>{error && <div className="error" role="alert">{error}</div>}<button disabled={busy}>{busy ? '正在验证…' : '登录'}</button></form></main>
}

function Editor({ selected, onSaved }: { selected: SourceSummary | null; onSaved: () => void }) {
  const [record, setRecord] = useState<SourceRecord | null>(null); const [text, setText] = useState(''); const [status, setStatus] = useState('')
  useEffect(() => { if (!selected) { setRecord(null); setText(''); return }; api.source(selected.id).then(value => { setRecord(value); setText(value.json); setStatus('') }).catch(error => setStatus(error.message)) }, [selected])
  const save = async () => { if (!record || !selected) return; try { const next = await api.save(selected.id, text, record.version); setRecord(next); setStatus('已保存'); onSaved() } catch (error) { setStatus(error instanceof Error ? error.message : '保存失败') } }
  const validate = async () => { if (!selected) return; try { const result = await api.validate(selected.id); setStatus(result.valid ? result.warnings.join('；') || '结构校验通过' : result.errors.join('；')) } catch (error) { setStatus(error instanceof Error ? error.message : '校验失败') } }
  if (!selected) return <section className="editor empty"><h2>选择一个书源</h2><p>从左侧列表选择，或导入新的书源 JSON。</p></section>
  return <section className="editor"><header><div><h2>{selected.name}</h2><small>{selected.url}</small></div><div className="actions"><button className="secondary" onClick={validate}>校验</button><button onClick={save}>保存</button></div></header><textarea aria-label="书源 JSON 编辑器" value={text} onChange={e => setText(e.target.value)} spellCheck={false} />{status && <footer className={status.includes('失败') || status.includes('错误') ? 'error' : ''}>{status}</footer>}</section>
}

function Reader({ selected }: { selected: SourceSummary | null }) {
  const [keyword, setKeyword] = useState(''); const [results, setResults] = useState<SearchResult[]>([]); const [book, setBook] = useState<BookDetails | null>(null); const [bookUrl, setBookUrl] = useState(''); const [chapters, setChapters] = useState<Chapter[]>([]); const [content, setContent] = useState(''); const [message, setMessage] = useState(''); const [loading, setLoading] = useState(false)
  const search = async (event: FormEvent) => { event.preventDefault(); if (!keyword.trim()) return; setLoading(true); setMessage(''); setBook(null); setChapters([]); setContent(''); try { setResults(await api.search(keyword, selected ? [selected.id] : undefined)) } catch (error) { setMessage(error instanceof Error ? error.message : '搜索失败') } finally { setLoading(false) } }
  const open = async (result: SearchResult) => { setLoading(true); setMessage(''); try { const details = await api.details(result.sourceId, result.bookUrl); setBook(details); setBookUrl(result.bookUrl); setChapters(await api.chapters(details.sourceId, details.tocUrl)); setContent('') } catch (error) { setMessage(error instanceof Error ? error.message : '无法读取书籍') } finally { setLoading(false) } }
  const read = async (chapter: Chapter) => { if (!book) return; setLoading(true); try { setContent((await api.content(book.sourceId, chapter.url)).content); await api.saveProgress(book.sourceId, bookUrl, chapter.url, chapter.index) } catch (error) { setMessage(error instanceof Error ? error.message : '无法读取正文') } finally { setLoading(false) } }
  return <section className="reader"><header><h2>阅读预览</h2><form onSubmit={search}><input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="输入书名或作者" /><button disabled={loading}>{loading ? '请求中…' : '搜索'}</button></form></header>{message && <p className="error">{message}</p>}<div className="reader-grid"><div className="results">{results.map(result => <button key={`${result.sourceId}-${result.bookUrl}`} onClick={() => void open(result)}><strong>{result.name}</strong><small>{result.author || result.sourceId}</small></button>)}</div>{book && <div className="toc"><h3>{book.name}</h3><p>{book.author}</p>{chapters.map(chapter => <button key={chapter.url} onClick={() => void read(chapter)}>{chapter.title}</button>)}</div>}{content && <article className="reading-content">{content.split('\n').filter(Boolean).map((line, index) => <p key={index}>{line}</p>)}</article>}</div></section>
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
