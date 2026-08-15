import { CSSProperties, PointerEvent as ReactPointerEvent, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, BookDetails, Chapter, ReadingProgress } from './api'
import { Icon } from './icons'
import { isInteractiveReaderTarget, isTapGesture, mobileTapZone } from './readerInteractions'
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

export interface VirtualChapterListProps {
  chapters: Chapter[]
  activeChapterIndex: number
  onSelect: (index: number) => void
  itemHeight?: number
  overscan?: number
  className?: string
  autoScrollKey?: string | number
}

export function VirtualChapterList({
  chapters,
  activeChapterIndex,
  onSelect,
  itemHeight = 36,
  overscan = 6,
  className = '',
  autoScrollKey,
}: VirtualChapterListProps) {
  const containerRef = useRef<HTMLElement | null>(null)
  const [scrollTop, setScrollTop] = useState(0)
  const [containerHeight, setContainerHeight] = useState(600)

  useLayoutEffect(() => {
    const el = containerRef.current
    if (!el) return
    setContainerHeight(el.clientHeight || 600)

    const observer = new ResizeObserver(entries => {
      for (const entry of entries) {
        if (entry.contentRect.height > 0) {
          setContainerHeight(entry.contentRect.height)
        }
      }
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const scrollToActive = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = containerRef.current
    if (!el) return
    const targetIndex = chapters.findIndex(c => c.index === activeChapterIndex)
    if (targetIndex < 0) return

    const itemTop = targetIndex * itemHeight
    const targetScroll = Math.max(0, itemTop - (el.clientHeight - itemHeight) / 2)
    const maxScroll = Math.max(0, chapters.length * itemHeight - el.clientHeight)
    const finalScroll = Math.min(targetScroll, maxScroll)

    if (Math.abs(el.scrollTop - finalScroll) > 1) {
      el.scrollTo({ top: finalScroll, behavior })
    }
  }, [activeChapterIndex, chapters, itemHeight])

  useEffect(() => {
    const timer = window.requestAnimationFrame(() => {
      scrollToActive('auto')
    })
    return () => window.cancelAnimationFrame(timer)
  }, [scrollToActive, autoScrollKey])

  const handleScroll = useCallback((event: React.UIEvent<HTMLElement>) => {
    setScrollTop(event.currentTarget.scrollTop)
  }, [])

  const count = chapters.length
  const startIndex = Math.max(0, Math.floor(scrollTop / itemHeight) - overscan)
  const endIndex = Math.min(count, Math.ceil((scrollTop + containerHeight) / itemHeight) + overscan)

  const topSpacer = startIndex * itemHeight
  const bottomSpacer = Math.max(0, (count - endIndex) * itemHeight)

  const visibleChapters = useMemo(() => {
    const items: Array<{ chapter: Chapter; index: number }> = []
    for (let i = startIndex; i < endIndex; i++) {
      if (chapters[i]) {
        items.push({ chapter: chapters[i], index: i })
      }
    }
    return items
  }, [chapters, startIndex, endIndex])

  return (
    <nav
      ref={containerRef}
      className={`reader-chapters ${className}`}
      aria-label="章节目录"
      onScroll={handleScroll}
    >
      {topSpacer > 0 && <div style={{ height: topSpacer }} aria-hidden="true" />}
      {visibleChapters.map(({ chapter }) => (
        <button
          key={chapter.url}
          style={{ height: itemHeight }}
          className={`reader-chapter-item ${chapter.index === activeChapterIndex ? 'current' : ''}`}
          onClick={() => onSelect(chapter.index)}
          title={chapter.title}
        >
          <i />
          <span className="reader-chapter-title">{chapter.title}</span>
        </button>
      ))}
      {bottomSpacer > 0 && <div style={{ height: bottomSpacer }} aria-hidden="true" />}
      {count === 0 && <p className="reader-status" style={{ padding: '24px 0' }}>无匹配章节</p>}
    </nav>
  )
}

export function ReaderScreen({ openBook, startIndex, settings, onSettingsChange, onClose }: ReaderScreenProps) {
  const [chapterIndex, setChapterIndex] = useState(startIndex)
  const [content, setContent] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [chapterQuery, setChapterQuery] = useState('')
  const deferredQuery = useDeferredValue(chapterQuery)
  const [mobilePanel, setMobilePanel] = useState<'toc' | 'settings' | null>(null)
  const [toolbarsVisible, setToolbarsVisible] = useState(true)
  const [boundaryMessage, setBoundaryMessage] = useState('')
  const [inShelf, setInShelf] = useState(true)
  const [speechState, setSpeechState] = useState<'idle' | 'speaking' | 'paused'>('idle')
  const currentRef = useRef<{ chapter: Chapter; position: number } | null>(null)
  const timerRef = useRef<number | null>(null)
  const restoredRef = useRef(false)
  const speechRef = useRef<SpeechSynthesisUtterance | null>(null)
  const preloadedContentRef = useRef(new Map<string, string>())
  const preloadingRef = useRef(new Set<string>())
  const lastScrollYRef = useRef(0)
  const pointerStartRef = useRef<{ x: number; y: number; target: EventTarget | null } | null>(null)
  const boundaryTimerRef = useRef<number | null>(null)
  const chapter = openBook.chapters[chapterIndex]

  const filteredChapters = useMemo(() => {
    const query = deferredQuery.trim().toLowerCase()
    if (!query) return openBook.chapters
    const numQuery = /^\d+$/.test(query) ? parseInt(query, 10) : null
    return openBook.chapters.filter(item => {
      if (item.title.toLowerCase().includes(query)) return true
      if (numQuery !== null && (item.index === numQuery - 1 || item.index === numQuery)) return true
      return false
    })
  }, [deferredQuery, openBook.chapters])

  const chapterProgress = openBook.chapters.length > 1 ? (chapterIndex / (openBook.chapters.length - 1)) * 100 : 0

  const paragraphs = useMemo(() => {
    if (!content) return []
    const rawLines = content.split('\n')
    const result: string[] = []
    for (let i = 0; i < rawLines.length; i++) {
      const trimmed = rawLines[i].trim()
      if (trimmed) {
        result.push(trimmed)
      }
    }
    return result
  }, [content])

  const persist = useCallback(() => {
    const current = currentRef.current
    if (!current) return
    void api.saveProgress(openBook.details.sourceId, openBook.bookUrl, current.chapter.url, current.chapter.index, clampScrollPosition(current.position)).catch(() => undefined)
  }, [openBook.bookUrl, openBook.details.sourceId])

  const stopSpeech = useCallback(() => {
    if (!('speechSynthesis' in window)) return
    window.speechSynthesis.cancel()
    speechRef.current = null
    setSpeechState('idle')
  }, [])

  const toggleSpeech = useCallback(() => {
    if (!content || !chapter || !('speechSynthesis' in window)) return
    if (speechState === 'speaking') { window.speechSynthesis.pause(); setSpeechState('paused'); return }
    if (speechState === 'paused') { window.speechSynthesis.resume(); setSpeechState('speaking'); return }
    const utterance = new SpeechSynthesisUtterance(`${chapter.title}。${content}`)
    utterance.lang = 'zh-CN'
    utterance.onend = () => setSpeechState('idle')
    utterance.onerror = () => setSpeechState('idle')
    speechRef.current = utterance
    window.speechSynthesis.speak(utterance)
    setSpeechState('speaking')
  }, [chapter, content, speechState])

  const preloadNextChapter = useCallback((index: number) => {
    const next = openBook.chapters[index + 1]
    if (!next || preloadedContentRef.current.has(next.url) || preloadingRef.current.has(next.url)) return
    preloadingRef.current.add(next.url)
    void api.content(openBook.details.sourceId, next.url, openBook.bookUrl)
      .then(result => preloadedContentRef.current.set(next.url, result.content))
      .catch(() => undefined)
      .finally(() => preloadingRef.current.delete(next.url))
  }, [openBook.bookUrl, openBook.chapters, openBook.details.sourceId])

  const changeChapter = useCallback((nextIndex: number) => {
    if (nextIndex < 0 || nextIndex >= openBook.chapters.length || nextIndex === chapterIndex) {
      if (nextIndex < 0 || nextIndex >= openBook.chapters.length) {
        setBoundaryMessage(nextIndex < 0 ? '已是第一章' : '已是最后一章')
        if (boundaryTimerRef.current !== null) window.clearTimeout(boundaryTimerRef.current)
        boundaryTimerRef.current = window.setTimeout(() => { boundaryTimerRef.current = null; setBoundaryMessage('') }, 1400)
      }
      return
    }
    persist()
    stopSpeech()
    setChapterIndex(nextIndex)
    setMobilePanel(null)
    setToolbarsVisible(true)
  }, [chapterIndex, openBook.chapters.length, persist, stopSpeech])

  const toggleShelf = async () => {
    if (inShelf) {
      if (!confirm(`移出“${openBook.details.name}”将清除书架、阅读进度和缓存封面，确定继续吗？`)) return
      await api.removeFromBookshelf(openBook.details.sourceId, openBook.bookUrl); setInShelf(false); return
    }
    await api.addToBookshelf({ sourceId: openBook.details.sourceId, bookUrl: openBook.bookUrl, name: openBook.details.name, author: openBook.details.author, tocUrl: openBook.details.tocUrl, coverUrl: openBook.details.coverUrl }); setInShelf(true)
  }

  useEffect(() => {
    let cancelled = false
    setLoading(true); setMessage(''); setContent('')
    const applyContent = (nextContent: string) => {
      if (cancelled) return
      setContent(nextContent)
      const position = !restoredRef.current && chapter.index === startIndex ? openBook.progress?.scrollPosition ?? 0 : 0
      restoredRef.current = true
      currentRef.current = { chapter, position }
      window.requestAnimationFrame(() => {
        const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
        const targetScroll = maxScroll * position
        lastScrollYRef.current = targetScroll
        window.scrollTo({ top: targetScroll, behavior: 'auto' })
      })
      setLoading(false)
    }
    const preloaded = preloadedContentRef.current.get(chapter.url)
    if (preloaded) applyContent(preloaded)
    else void api.content(openBook.details.sourceId, chapter.url, openBook.bookUrl).then(result => applyContent(result.content)).catch(error => { if (!cancelled) setMessage(error instanceof Error ? error.message : '无法读取正文') }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [chapter, openBook.details.sourceId, openBook.progress?.scrollPosition, startIndex])

  useEffect(() => {
    let rafId: number | null = null
    const onScroll = () => {
      if (rafId !== null) return
      rafId = window.requestAnimationFrame(() => {
        rafId = null
        const current = currentRef.current
        if (!current) return
        const currentY = window.scrollY
        const scrollDelta = currentY - lastScrollYRef.current
        if (currentY > 72 && scrollDelta > 12) {
          setToolbarsVisible(visible => visible ? false : visible)
        } else if (scrollDelta < -8) {
          setToolbarsVisible(visible => !visible ? true : visible)
        }
        lastScrollYRef.current = currentY
        const scrollHeight = document.documentElement.scrollHeight
        const clientHeight = window.innerHeight
        current.position = scrollPosition(currentY, scrollHeight, clientHeight)
        if (current.position >= 0.7) {
          preloadNextChapter(current.chapter.index)
        }
        if (timerRef.current === null) {
          timerRef.current = window.setTimeout(() => {
            timerRef.current = null
            persist()
          }, 1200)
        }
      })
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') persist()
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.removeEventListener('scroll', onScroll)
      document.removeEventListener('visibilitychange', onVisibilityChange)
      if (rafId !== null) window.cancelAnimationFrame(rafId)
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
      persist()
    }
  }, [persist, preloadNextChapter])

  useEffect(() => () => stopSpeech(), [stopSpeech])
  useEffect(() => () => { if (boundaryTimerRef.current !== null) window.clearTimeout(boundaryTimerRef.current) }, [])
  useEffect(() => { if (mobilePanel) setToolbarsVisible(true) }, [mobilePanel])

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

  const onReadingPointerDown = (event: ReactPointerEvent<HTMLElement>) => {
    if (window.innerWidth > 720 || loading || mobilePanel || isInteractiveReaderTarget(event.target)) return
    pointerStartRef.current = { x: event.clientX, y: event.clientY, target: event.target }
  }
  const onReadingPointerUp = (event: ReactPointerEvent<HTMLElement>) => {
    const start = pointerStartRef.current
    pointerStartRef.current = null
    if (!start || window.innerWidth > 720 || loading || mobilePanel || isInteractiveReaderTarget(event.target) || isInteractiveReaderTarget(start.target) || !isTapGesture(start.x, start.y, event.clientX, event.clientY) || window.getSelection()?.toString()) return
    const zone = mobileTapZone(event.clientY, window.innerHeight)
    if (zone === 'previous') changeChapter(chapterIndex - 1)
    else if (zone === 'next') changeChapter(chapterIndex + 1)
    else setToolbarsVisible(visible => !visible)
  }

  return <main className={`reader-workspace theme-${settings.theme} ${toolbarsVisible ? '' : 'toolbars-hidden'}`} style={readerStyle}>
    <aside className="reader-sidebar"><header className="reader-brand"><strong>{openBook.details.name}</strong><Icon name="chevronDown" /></header><div className="chapter-search"><Icon name="search" /><input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder="搜索章节" /></div><div className="reader-side-title"><Icon name="list" /><span>目录</span></div><VirtualChapterList chapters={filteredChapters} activeChapterIndex={chapterIndex} onSelect={changeChapter} itemHeight={36} overscan={6} /><footer><button className="shelf-button" onClick={() => void toggleShelf()}><Icon name={inShelf ? 'check' : 'plus'} />{inShelf ? '已加入书架' : '添加到书架'}</button></footer></aside>
    <section className="reader-main"><header className="reader-header"><div className="reader-header-left"><IconButton label="返回书籍详情" icon="arrowLeft" onClick={() => { persist(); stopSpeech(); onClose() }} /><IconButton label="目录" icon="list" onClick={() => setMobilePanel('toc')} /></div><strong>{openBook.details.name}</strong><div className="reader-header-actions"><IconButton label="阅读进度" icon="bookmark" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })} /><IconButton label="阅读设置" icon="settings" onClick={() => setMobilePanel('settings')} /><IconButton label={speechState === 'speaking' ? '暂停朗读' : speechState === 'paused' ? '继续朗读' : '朗读本章'} icon="volume2" onClick={toggleSpeech} /></div></header><article className={`reading-content font-${settings.font}`} onPointerDown={onReadingPointerDown} onPointerUp={onReadingPointerUp}><h1>{chapter?.title}</h1>{loading && <p className="reader-status">正在加载正文...</p>}{message && <p className="reader-error">{message}</p>}{paragraphs.map((line, index) => <p key={index}>{line}</p>)}{content && <footer className="reader-navigation"><button disabled={chapterIndex === 0 || loading} onClick={() => changeChapter(chapterIndex - 1)}><Icon name="arrowLeft" />上一章</button><div className="chapter-progress"><i style={{ width: `${chapterProgress}%` }} /><span>{chapterIndex + 1} / {openBook.chapters.length}</span></div><button disabled={chapterIndex === openBook.chapters.length - 1 || loading} onClick={() => changeChapter(chapterIndex + 1)}>下一章<Icon name="arrowRight" /></button></footer>}</article>{boundaryMessage && <p className="reader-boundary-message" role="status">{boundaryMessage}</p>}</section>
    <ReaderSettingsPanel settings={settings} onChange={onSettingsChange} />
    <nav className="mobile-reader-nav"><button onClick={() => setMobilePanel('toc')}><Icon name="list" /><span>目录</span></button><button onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}><Icon name="sliders" /><span>进度</span></button><button onClick={() => setMobilePanel('settings')}><span className="aa">Aa</span><span>设置</span></button><button onClick={() => onSettingsChange({ ...settings, theme: settings.theme === 'dark' ? 'light' : 'dark' })}><Icon name="moon" /><span>夜间</span></button></nav>
    {mobilePanel && <><button className="reader-overlay" aria-label="关闭面板" onClick={() => setMobilePanel(null)} /><section className={`mobile-reader-sheet ${mobilePanel}`}><header><strong>{mobilePanel === 'toc' ? '选择章节' : '阅读设置'}</strong><IconButton label="关闭" icon="close" onClick={() => setMobilePanel(null)} /></header>{mobilePanel === 'toc' ? <><div className="sheet-volume">共 {openBook.chapters.length} 章</div><div className="chapter-search" style={{ margin: '0 16px 12px' }}><Icon name="search" /><input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder="搜索章节" /></div><VirtualChapterList chapters={filteredChapters} activeChapterIndex={chapterIndex} onSelect={changeChapter} itemHeight={44} overscan={6} autoScrollKey={mobilePanel} /></> : <ReaderSettingsPanel settings={settings} onChange={onSettingsChange} onClose={() => setMobilePanel(null)} />}</section></>}
  </main>
}
