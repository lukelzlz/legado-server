import { CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, BookDetails, Chapter, ReadingProgress } from './api'
import { Icon } from './icons'
import { clampScrollPosition, ReaderSettings, scrollPosition } from './readerSettings'

export type OpenBook = { details: BookDetails; bookUrl: string; chapters: Chapter[]; progress?: ReadingProgress }

type ReaderScreenProps = {
  openBook: OpenBook
  startIndex: number
  settings: ReaderSettings
  onSettingsChange: (next: ReaderSettings) => void
  onClose: () => void
}

const themes = [
  ['light', '晓白'], ['paper', '护眼'], ['dark', '夜读'],
] as const

function IconButton({ label, icon, onClick, className = '' }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void; className?: string }) {
  return <button className={`reader-icon-button ${className}`} title={label} aria-label={label} onClick={onClick}><Icon name={icon} /></button>
}

function SettingRange({ label, value, min, max, step, onChange, display }: { label: string; value: number; min: number; max: number; step: number; onChange: (value: number) => void; display: string }) {
  return <label className="setting-range"><span>{label}</span><output>{display}</output><input type="range" min={min} max={max} step={step} value={value} onChange={event => onChange(Number(event.target.value))} /></label>
}

function ReaderSettingsPanel({ settings, onChange, onClose }: { settings: ReaderSettings; onChange: (next: ReaderSettings) => void; onClose?: () => void }) {
  const update = (value: Partial<ReaderSettings>) => onChange({ ...settings, ...value })
  return <aside className="reader-settings-panel" aria-label="阅读设置">
    <header><strong>阅读设置</strong>{onClose && <IconButton label="关闭设置" icon="close" onClick={onClose} />}</header>
    <section className="setting-section"><span className="setting-label">主题</span><div className="theme-grid">{themes.map(([theme, label]) => <button key={theme} className={`theme-choice theme-${theme} ${settings.theme === theme ? 'selected' : ''}`} onClick={() => update({ theme })}><i>{settings.theme === theme && <Icon name="check" />}</i><small>{label}</small></button>)}</div></section>
    <section className="setting-section"><span className="setting-label">字体</span><label className="font-select"><select value={settings.font} onChange={event => update({ font: event.target.value as ReaderSettings['font'] })}><option value="song">思源宋体</option><option value="serif">经典衬线</option><option value="system">系统字体</option></select><Icon name="chevronDown" /></label></section>
    <section className="setting-section compact-settings"><div className="font-stepper"><span>字号</span><button aria-label="减小字号" onClick={() => update({ fontSize: Math.max(15, settings.fontSize - 1) })}>−</button><output>{settings.fontSize}</output><button aria-label="增大字号" onClick={() => update({ fontSize: Math.min(28, settings.fontSize + 1) })}>+</button></div><SettingRange label="字间距" value={settings.letterSpacing} min={-.25} max={1.5} step={.05} display={settings.letterSpacing.toFixed(2)} onChange={letterSpacing => update({ letterSpacing })} /><SettingRange label="行间距" value={settings.lineHeight} min={1.45} max={2.4} step={.05} display={settings.lineHeight.toFixed(2)} onChange={lineHeight => update({ lineHeight })} /><SettingRange label="段间距" value={settings.paragraphSpacing} min={.7} max={2} step={.05} display={settings.paragraphSpacing.toFixed(2)} onChange={paragraphSpacing => update({ paragraphSpacing })} /><SettingRange label="左右边距" value={settings.contentPadding} min={36} max={120} step={2} display={`${settings.contentPadding}`} onChange={contentPadding => update({ contentPadding })} /></section>
    <section className="setting-section"><span className="setting-label">翻页方式</span><div className="page-modes"><button disabled>仿真</button><button className="selected">滚动</button><button disabled>平移</button></div><small className="setting-hint">当前版本支持连续滚动阅读</small></section>
    <button className="reset-settings" onClick={() => onChange({ ...settings, fontSize: 19, lineHeight: 1.95, letterSpacing: 0, paragraphSpacing: 1.15, contentPadding: 80, font: 'song' })}>恢复默认设置</button>
  </aside>
}

function ChapterList({ chapters, chapterIndex, onSelect }: { chapters: Chapter[]; chapterIndex: number; onSelect: (index: number) => void }) {
  return <nav className="reader-chapters" aria-label="章节目录">{chapters.map(chapter => <button key={chapter.url} className={chapter.index === chapterIndex ? 'current' : ''} onClick={() => onSelect(chapter.index)}><i />{chapter.title}</button>)}</nav>
}

export function ReaderScreen({ openBook, startIndex, settings, onSettingsChange, onClose }: ReaderScreenProps) {
  const [chapterIndex, setChapterIndex] = useState(startIndex)
  const [content, setContent] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [chapterQuery, setChapterQuery] = useState('')
  const [mobilePanel, setMobilePanel] = useState<'toc' | 'settings' | null>(null)
  const currentRef = useRef<{ chapter: Chapter; position: number } | null>(null)
  const timerRef = useRef<number | null>(null)
  const restoredRef = useRef(false)
  const chapter = openBook.chapters[chapterIndex]
  const filteredChapters = useMemo(() => openBook.chapters.filter(item => item.title.toLowerCase().includes(chapterQuery.trim().toLowerCase())), [chapterQuery, openBook.chapters])
  const chapterProgress = openBook.chapters.length > 1 ? (chapterIndex / (openBook.chapters.length - 1)) * 100 : 0

  const persist = useCallback(() => {
    const current = currentRef.current
    if (!current) return
    void api.saveProgress(openBook.details.sourceId, openBook.bookUrl, current.chapter.url, current.chapter.index, clampScrollPosition(current.position)).catch(() => undefined)
  }, [openBook.bookUrl, openBook.details.sourceId])

  const changeChapter = useCallback((nextIndex: number) => {
    if (nextIndex < 0 || nextIndex >= openBook.chapters.length || nextIndex === chapterIndex) return
    persist()
    setChapterIndex(nextIndex)
    setMobilePanel(null)
  }, [chapterIndex, openBook.chapters.length, persist])

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

  const readerStyle = {
    '--reader-font-size': `${settings.fontSize}px`, '--reader-line-height': settings.lineHeight, '--reader-letter-spacing': `${settings.letterSpacing}px`, '--reader-paragraph-spacing': `${settings.paragraphSpacing}em`, '--reader-content-padding': `${settings.contentPadding}px`,
  } as CSSProperties

  return <main className={`reader-workspace theme-${settings.theme}`} style={readerStyle}>
    <aside className="reader-sidebar"><header className="reader-brand"><strong>{openBook.details.name}</strong><Icon name="chevronDown" /></header><div className="chapter-search"><Icon name="search" /><input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder="搜索章节" /></div><div className="reader-side-title"><Icon name="list" /><span>目录</span></div><ChapterList chapters={filteredChapters} chapterIndex={chapterIndex} onSelect={changeChapter} /><footer><button className="shelf-button"><Icon name="plus" />添加到书架</button></footer></aside>
    <section className="reader-main"><header className="reader-header"><div className="reader-header-left"><IconButton label="返回书籍详情" icon="arrowLeft" onClick={() => { persist(); onClose() }} /><IconButton label="目录" icon="list" onClick={() => setMobilePanel('toc')} /></div><strong>{openBook.details.name}</strong><div className="reader-header-actions"><IconButton label="阅读进度" icon="bookmark" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })} /><IconButton label="阅读设置" icon="settings" onClick={() => setMobilePanel('settings')} /><IconButton label="更多操作" icon="more" onClick={() => undefined} /></div></header><article className={`reading-content font-${settings.font}`}><h1>{chapter?.title}</h1>{loading && <p className="reader-status">正在加载正文...</p>}{message && <p className="reader-error">{message}</p>}{content && content.split('\n').filter(Boolean).map((line, index) => <p key={index}>{line}</p>)}{content && <footer className="reader-navigation"><button disabled={chapterIndex === 0 || loading} onClick={() => changeChapter(chapterIndex - 1)}><Icon name="arrowLeft" />上一章</button><div className="chapter-progress"><i style={{ width: `${chapterProgress}%` }} /><span>{chapterIndex + 1} / {openBook.chapters.length}</span></div><button disabled={chapterIndex === openBook.chapters.length - 1 || loading} onClick={() => changeChapter(chapterIndex + 1)}>下一章<Icon name="arrowRight" /></button></footer>}</article></section>
    <ReaderSettingsPanel settings={settings} onChange={onSettingsChange} />
    <nav className="mobile-reader-nav"><button onClick={() => setMobilePanel('toc')}><Icon name="list" /><span>目录</span></button><button onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}><Icon name="sliders" /><span>进度</span></button><button onClick={() => setMobilePanel('settings')}><span className="aa">Aa</span><span>设置</span></button><button onClick={() => onSettingsChange({ ...settings, theme: settings.theme === 'dark' ? 'light' : 'dark' })}><Icon name="moon" /><span>夜间</span></button></nav>
    {mobilePanel && <><button className="reader-overlay" aria-label="关闭面板" onClick={() => setMobilePanel(null)} /><section className={`mobile-reader-sheet ${mobilePanel}`}><header><strong>{mobilePanel === 'toc' ? '选择章节' : '阅读设置'}</strong><IconButton label="关闭" icon="close" onClick={() => setMobilePanel(null)} /></header>{mobilePanel === 'toc' ? <><div className="sheet-volume">共 {openBook.chapters.length} 章</div><ChapterList chapters={openBook.chapters} chapterIndex={chapterIndex} onSelect={changeChapter} /></> : <ReaderSettingsPanel settings={settings} onChange={onSettingsChange} onClose={() => setMobilePanel(null)} />}</section></>}
  </main>
}
