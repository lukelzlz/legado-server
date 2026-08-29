import { CSSProperties, PointerEvent as ReactPointerEvent, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, BookDetails, Chapter, ReadingProgress, SearchResult } from './api'
import { Icon } from './icons'
import { calculatePaginationLayout, isAtBottomBoundary, isAtTopBoundary, isInteractiveReaderTarget, isTapGesture, paginateTapZone, scrollTapZone, swipeDirection } from './readerInteractions'
import { clampScrollPosition, defaultReaderSettings, getReaderFontFamily, ReaderSettings, scrollPosition } from './readerSettings'
import { SourceSwitchModal } from './SourceSwitchModal'
import { cleanAuthor, cleanTitle } from './searchFilters'
import { toast } from './Toast'

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

function ReaderSettingsControls({ settings, onChange }: { settings: ReaderSettings; onChange: (next: ReaderSettings) => void }) {
  const update = (value: Partial<ReaderSettings>) => onChange({ ...settings, ...value })
  return <div className="drawer-scroll-content">
    <section className="setting-section"><span className="setting-label">主题</span><div className="theme-grid">{themes.map(([theme, label]) => <button key={theme} className={`theme-choice theme-${theme} ${settings.theme === theme ? 'selected' : ''}`} onClick={() => update({ theme })}><i>{settings.theme === theme && <Icon name="check" />}</i><small>{label}</small></button>)}</div></section>
    <section className="setting-section"><span className="setting-label">字体</span><label className="font-select"><select value={settings.font} onChange={event => update({ font: event.target.value as ReaderSettings['font'] })}><option value="song">思源宋体</option><option value="hei">黑体 / 苹方</option><option value="kai">华文楷体</option><option value="fangsong">华文仿宋</option><option value="system">系统字体</option></select><Icon name="chevronDown" /></label></section>
    <section className="setting-section compact-settings"><div className="font-stepper"><span>字号</span><button aria-label="减小字号" onClick={() => update({ fontSize: Math.max(15, settings.fontSize - 1) })}>−</button><output>{settings.fontSize}</output><button aria-label="增大字号" onClick={() => update({ fontSize: Math.min(28, settings.fontSize + 1) })}>+</button></div><SettingRange label="字间距" value={settings.letterSpacing} min={-.25} max={1.5} step={.05} display={settings.letterSpacing.toFixed(2)} onChange={letterSpacing => update({ letterSpacing })} /><SettingRange label="行间距" value={settings.lineHeight} min={1.45} max={2.4} step={.05} display={settings.lineHeight.toFixed(2)} onChange={lineHeight => update({ lineHeight })} /><SettingRange label="段间距" value={settings.paragraphSpacing} min={.7} max={2} step={.05} display={settings.paragraphSpacing.toFixed(2)} onChange={paragraphSpacing => update({ paragraphSpacing })} /><SettingRange label="左右边距" value={settings.contentPadding} min={20} max={120} step={2} display={`${settings.contentPadding}`} onChange={contentPadding => update({ contentPadding })} /><SettingRange label="版心宽度" value={settings.maxWidth} min={560} max={1400} step={20} display={`${settings.maxWidth}px`} onChange={maxWidth => update({ maxWidth })} /></section>
    <section className="setting-section"><span className="setting-label">翻页方式</span><div className="page-modes"><button className={settings.pageMode === 'scroll' ? 'selected' : ''} onClick={() => update({ pageMode: 'scroll' })}>连续滚动</button><button className={settings.pageMode === 'paginate' ? 'selected' : ''} onClick={() => update({ pageMode: 'paginate' })}>平移分页</button></div><small className="setting-hint">{settings.pageMode === 'paginate' ? '左右轻扫或点击屏幕两侧平滑翻页' : '垂直滚动阅读，点击上下可快速翻滚'}</small></section>
    <section className="setting-section"><span className="setting-label">分栏排版</span><div className="page-modes column-modes"><button className={settings.columnMode === 'auto' ? 'selected' : ''} onClick={() => update({ columnMode: 'auto' })}>自适应</button><button className={settings.columnMode === 'single' ? 'selected' : ''} onClick={() => update({ columnMode: 'single' })}>单栏</button><button className={settings.columnMode === 'double' ? 'selected' : ''} onClick={() => update({ columnMode: 'double' })}>双栏</button></div><small className="setting-hint">{settings.columnMode === 'auto' ? '宽屏 (≥800px) 自动开启双页分栏' : settings.columnMode === 'double' ? '固定双栏双页排版' : '固定单栏排版'}</small></section>
    <button className="reset-settings" onClick={() => onChange({ ...defaultReaderSettings, theme: settings.theme })}>恢复默认设置</button>
  </div>
}

function ReaderContentSkeleton() {
  return (
    <div className="reader-loading-container" aria-label="正在加载正文">
      <div className="reader-loading-badge">
        <span className="reader-loading-spinner-ring" />
        <span>正在从书源拉取正文...</span>
      </div>
      <div className="reader-skeleton-paragraphs">
        <div className="skeleton-line" style={{ width: '100%' }} />
        <div className="skeleton-line" style={{ width: '94%' }} />
        <div className="skeleton-line" style={{ width: '98%' }} />
        <div className="skeleton-line" style={{ width: '91%' }} />
        <div className="skeleton-line" style={{ width: '62%' }} />
        <div className="skeleton-gap" />
        <div className="skeleton-line" style={{ width: '100%' }} />
        <div className="skeleton-line" style={{ width: '96%' }} />
        <div className="skeleton-line" style={{ width: '92%' }} />
        <div className="skeleton-line" style={{ width: '48%' }} />
      </div>
    </div>
  )
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
  itemHeight = 38,
  overscan = 8,
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
  const [currentBook, setCurrentBook] = useState<OpenBook>(openBook)
  const [chapterIndex, setChapterIndex] = useState(startIndex)
  const [content, setContent] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [chapterQuery, setChapterQuery] = useState('')
  const deferredQuery = useDeferredValue(chapterQuery)
  const [activeDrawer, setActiveDrawer] = useState<'toc' | 'settings' | null>(null)
  const [toolbarsVisible, setToolbarsVisible] = useState(false)
  const [boundaryMessage, setBoundaryMessage] = useState('')
  const [inShelf, setInShelf] = useState(true)
  const [speechState, setSpeechState] = useState<'idle' | 'speaking' | 'paused'>('idle')
  const [showSourceSwitch, setShowSourceSwitch] = useState(false)
  const [cacheStatus, setCacheStatus] = useState<{ state: string; cached: number; total: number; error?: string }>({
    state: 'idle',
    cached: 0,
    total: openBook.chapters.length,
  })

  // Pagination states
  const [pageIndex, setPageIndex] = useState(0)
  const [pageCount, setPageCount] = useState(1)
  const [columnWidth, setColumnWidth] = useState(600)
  const [stride, setStride] = useState(640)
  const [isDoubleColumn, setIsDoubleColumn] = useState(false)
  const columnGap = 40

  const currentRef = useRef<{ chapter: Chapter; position: number } | null>(null)
  const timerRef = useRef<number | null>(null)
  const restoredRef = useRef(false)
  const speechRef = useRef<SpeechSynthesisUtterance | null>(null)
  const preloadedContentRef = useRef(new Map<string, string>())
  const preloadingRef = useRef(new Set<string>())
  const lastScrollYRef = useRef(0)
  const pointerStartRef = useRef<{ x: number; y: number; target: EventTarget | null } | null>(null)
  const boundaryTimerRef = useRef<number | null>(null)
  const targetInitialPageRef = useRef<'first' | 'last' | null>(null)
  const initialPagePositionRef = useRef<number | null>(null)
  const wheelTimerRef = useRef<number | null>(null)

  const viewportRef = useRef<HTMLDivElement | null>(null)
  const bodyRef = useRef<HTMLDivElement | null>(null)

  const chapter = currentBook.chapters[chapterIndex]
  const bookName = currentBook.details.name || '书籍正文'

  // Sync cache status with bookshelf
  const syncCacheStatus = useCallback(async () => {
    try {
      const shelf = await api.bookshelf()
      const item = shelf.find(s => s.sourceId === currentBook.details.sourceId && s.bookUrl === currentBook.bookUrl)
      if (item) {
        setInShelf(true)
        setCacheStatus(prev => {
          // If state changed to ready or failed, show notification
          if (prev.state === 'caching' && item.cacheState === 'ready') {
            toast.success(`《${bookName}》全本离线缓存完成（共 ${item.cachedChapters} 章）`)
          } else if (prev.state === 'caching' && item.cacheState === 'failed') {
            toast.warning(`《${bookName}》缓存中断：${item.cacheError || '部分章节未下载'}`)
          }
          return {
            state: item.cacheState,
            cached: item.cachedChapters,
            total: item.totalChapters || currentBook.chapters.length,
            error: item.cacheError,
          }
        })
      } else {
        setInShelf(false)
      }
    } catch {
      // ignore
    }
  }, [bookName, currentBook.bookUrl, currentBook.chapters.length, currentBook.details.sourceId])

  useEffect(() => {
    void syncCacheStatus()
  }, [syncCacheStatus])

  useEffect(() => {
    if (cacheStatus.state !== 'caching') return
    const timer = window.setInterval(syncCacheStatus, 1200)
    return () => window.clearInterval(timer)
  }, [cacheStatus.state, syncCacheStatus])

  useEffect(() => {
    const title = chapter?.title ? `${bookName} - ${chapter.title} | 阅读服务器` : `${bookName} | 阅读服务器`
    document.title = title
    return () => {
      document.title = '阅读服务器'
    }
  }, [bookName, chapter?.title])

  const filteredChapters = useMemo(() => {
    const query = deferredQuery.trim().toLowerCase()
    if (!query) return currentBook.chapters
    const numQuery = /^\d+$/.test(query) ? parseInt(query, 10) : null
    return currentBook.chapters.filter(item => {
      if (item.title.toLowerCase().includes(query)) return true
      if (numQuery !== null && (item.index === numQuery - 1 || item.index === numQuery)) return true
      return false
    })
  }, [deferredQuery, currentBook.chapters])

  const chapterProgress = currentBook.chapters.length > 1 ? (chapterIndex / (currentBook.chapters.length - 1)) * 100 : 0

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
    void api.saveProgress(currentBook.details.sourceId, currentBook.bookUrl, current.chapter.url, current.chapter.index, clampScrollPosition(current.position)).catch(() => undefined)
  }, [currentBook.bookUrl, currentBook.details.sourceId])

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
    const next = currentBook.chapters[index + 1]
    if (!next || preloadedContentRef.current.has(next.url) || preloadingRef.current.has(next.url)) return
    preloadingRef.current.add(next.url)
    void api.content(currentBook.details.sourceId, next.url, currentBook.bookUrl)
      .then(result => preloadedContentRef.current.set(next.url, result.content))
      .catch(() => undefined)
      .finally(() => preloadingRef.current.delete(next.url))
  }, [currentBook.bookUrl, currentBook.chapters, currentBook.details.sourceId])

  const showBoundaryNotice = useCallback((msg: string) => {
    setBoundaryMessage(msg)
    if (boundaryTimerRef.current !== null) window.clearTimeout(boundaryTimerRef.current)
    boundaryTimerRef.current = window.setTimeout(() => {
      boundaryTimerRef.current = null
      setBoundaryMessage('')
    }, 1400)
  }, [])

  const changeChapter = useCallback((nextIndex: number, targetPage: 'first' | 'last' | 'auto' = 'auto') => {
    if (nextIndex < 0 || nextIndex >= currentBook.chapters.length || nextIndex === chapterIndex) {
      if (nextIndex < 0 || nextIndex >= currentBook.chapters.length) {
        showBoundaryNotice(nextIndex < 0 ? '已是第一章' : '已是最后一章')
      }
      return
    }
    persist()
    stopSpeech()
    targetInitialPageRef.current = targetPage === 'auto' ? null : targetPage
    setChapterIndex(nextIndex)
    setActiveDrawer(null)
  }, [chapterIndex, currentBook.chapters.length, persist, showBoundaryNotice, stopSpeech])

  const toggleShelf = async () => {
    if (inShelf) {
      if (!confirm(`移出“${bookName}”将清除书架、阅读进度和缓存封面，确定继续吗？`)) return
      await api.removeFromBookshelf(currentBook.details.sourceId, currentBook.bookUrl)
      setInShelf(false)
      toast.info(`《${bookName}》已移出书架`)
      return
    }
    const fallbackCover = currentBook.details.coverUrl || currentBook.details.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()
    await api.addToBookshelf({
      sourceId: currentBook.details.sourceId,
      bookUrl: currentBook.bookUrl,
      name: bookName,
      author: currentBook.details.author,
      tocUrl: currentBook.details.tocUrl,
      coverUrl: fallbackCover || undefined,
      alternateSources: currentBook.details.alternateSources,
    })
    setInShelf(true)
    toast.success(`《${bookName}》已加入书架`)
  }

  const handleCacheBook = async () => {
    try {
      await api.cacheBookshelfBook(currentBook.details.sourceId, currentBook.bookUrl)
      setCacheStatus(prev => ({ ...prev, state: 'caching', error: undefined, total: currentBook.chapters.length }))
      toast.info(`已加入离线缓存队列，正在下载《${bookName}》...`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '无法开始缓存')
    }
  }

  const handleCancelCache = async () => {
    try {
      await api.cancelBookCache(currentBook.details.sourceId, currentBook.bookUrl)
      setCacheStatus(prev => ({ ...prev, state: 'failed', error: '已取消缓存' }))
      toast.info(`已取消《${bookName}》的离线缓存`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '无法取消缓存')
    }
  }

  const handleSwitchSource = async (chosen: { result: SearchResult; chapters: Chapter[]; targetChapterIndex: number }) => {
    const details = await api.details(chosen.result.sourceId, chosen.result.bookUrl)
    const fallbackCover = details.coverUrl?.trim() ||
      chosen.result.coverUrl?.trim() ||
      currentBook.details.coverUrl?.trim() ||
      currentBook.details.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()

    const safeDetails: BookDetails = {
      ...details,
      name: cleanTitle(details.name?.trim() || currentBook.details.name || '未知书名') || currentBook.details.name,
      author: cleanAuthor(details.author?.trim() || currentBook.details.author) || currentBook.details.author,
      coverUrl: fallbackCover || undefined,
      intro: details.intro || currentBook.details.intro,
      alternateSources: currentBook.details.alternateSources,
    }

    await api.switchBookshelfSource({
      oldSourceId: currentBook.details.sourceId,
      oldBookUrl: currentBook.bookUrl,
      book: {
        sourceId: safeDetails.sourceId,
        bookUrl: chosen.result.bookUrl,
        name: safeDetails.name,
        author: safeDetails.author,
        tocUrl: safeDetails.tocUrl,
        coverUrl: safeDetails.coverUrl,
      },
      alternateSources: currentBook.details.alternateSources,
    })

    const newOpenBook: OpenBook = {
      details: safeDetails,
      bookUrl: chosen.result.bookUrl,
      chapters: chosen.chapters,
      progress: {
        sourceId: safeDetails.sourceId,
        bookUrl: chosen.result.bookUrl,
        chapterUrl: chosen.chapters[chosen.targetChapterIndex]?.url || '',
        chapterIndex: chosen.targetChapterIndex,
        scrollPosition: 0,
        updatedAt: Date.now(),
      },
    }

    setCurrentBook(newOpenBook)
    setChapterIndex(chosen.targetChapterIndex)
    preloadedContentRef.current.clear()
    sessionStorage.setItem('legado-open-book-v1', JSON.stringify({ book: newOpenBook, index: chosen.targetChapterIndex }))
  }

  // Load chapter content
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setMessage('')
    setContent('')
    const applyContent = (nextContent: string) => {
      if (cancelled) return
      setContent(nextContent)
      const position = !restoredRef.current && chapter.index === startIndex ? currentBook.progress?.scrollPosition ?? 0 : 0
      restoredRef.current = true
      currentRef.current = { chapter, position }
      initialPagePositionRef.current = position

      if (settings.pageMode === 'scroll') {
        window.requestAnimationFrame(() => {
          const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
          const targetScroll = targetInitialPageRef.current === 'last' ? maxScroll : targetInitialPageRef.current === 'first' ? 0 : maxScroll * position
          targetInitialPageRef.current = null
          lastScrollYRef.current = targetScroll
          window.scrollTo({ top: targetScroll, behavior: 'auto' })
        })
      }
      setLoading(false)
    }
    const preloaded = preloadedContentRef.current.get(chapter.url)
    if (preloaded) {
      applyContent(preloaded)
    } else {
      void api.content(currentBook.details.sourceId, chapter.url, currentBook.bookUrl)
        .then(result => applyContent(result.content))
        .catch(error => {
          if (!cancelled) setMessage(error instanceof Error ? error.message : '无法读取正文')
        })
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }
    return () => {
      cancelled = true
    }
  }, [chapter, currentBook.bookUrl, currentBook.details.sourceId, currentBook.progress?.scrollPosition, settings.pageMode, startIndex])

  // Pagination measurement
  const measurePagination = useCallback(() => {
    const viewport = viewportRef.current
    const body = bodyRef.current
    if (!viewport || !body) return
    const w = viewport.clientWidth
    if (w <= 0) return

    const totalScrollWidth = body.scrollWidth
    const layout = calculatePaginationLayout({
      viewportWidth: w,
      totalScrollWidth,
      columnGap,
      columnMode: settings.columnMode,
    })

    setColumnWidth(layout.columnWidth)
    setStride(layout.stride)
    setIsDoubleColumn(layout.isDoubleColumn)
    setPageCount(layout.pageCount)

    if (targetInitialPageRef.current === 'last') {
      targetInitialPageRef.current = null
      setPageIndex(layout.pageCount - 1)
    } else if (targetInitialPageRef.current === 'first') {
      targetInitialPageRef.current = null
      setPageIndex(0)
    } else if (initialPagePositionRef.current !== null) {
      const pos = initialPagePositionRef.current
      initialPagePositionRef.current = null
      const target = Math.min(layout.pageCount - 1, Math.max(0, Math.round(pos * (layout.pageCount - 1))))
      setPageIndex(target)
    } else {
      setPageIndex(curr => Math.min(curr, layout.pageCount - 1))
    }
  }, [columnGap, settings.columnMode])

  useLayoutEffect(() => {
    if (settings.pageMode !== 'paginate') return
    measurePagination()
    const viewport = viewportRef.current
    if (!viewport) return
    const observer = new ResizeObserver(() => measurePagination())
    observer.observe(viewport)
    return () => observer.disconnect()
  }, [
    settings.pageMode,
    content,
    settings.fontSize,
    settings.lineHeight,
    settings.letterSpacing,
    settings.paragraphSpacing,
    settings.contentPadding,
    settings.maxWidth,
    settings.columnMode,
    settings.sidebarPinned,
    settings.font,
    measurePagination,
  ])

  const goNextPage = useCallback(() => {
    if (pageIndex < pageCount - 1) {
      setPageIndex(p => p + 1)
    } else {
      changeChapter(chapterIndex + 1, 'first')
    }
  }, [chapterIndex, changeChapter, pageCount, pageIndex])

  const goPrevPage = useCallback(() => {
    if (pageIndex > 0) {
      setPageIndex(p => p - 1)
    } else {
      changeChapter(chapterIndex - 1, 'last')
    }
  }, [chapterIndex, changeChapter, pageIndex])

  // Sync scroll / page progress
  useEffect(() => {
    if (settings.pageMode !== 'scroll') return
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
          setToolbarsVisible(false)
        } else if (scrollDelta < -8) {
          setToolbarsVisible(true)
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
  }, [persist, preloadNextChapter, settings.pageMode])

  useEffect(() => {
    if (settings.pageMode !== 'paginate') return
    const position = pageCount > 1 ? pageIndex / (pageCount - 1) : 0
    const current = currentRef.current
    if (current && chapter) {
      current.position = position
      if (position >= 0.7) {
        preloadNextChapter(chapter.index)
      }
      if (timerRef.current === null) {
        timerRef.current = window.setTimeout(() => {
          timerRef.current = null
          persist()
        }, 1200)
      }
    }
  }, [chapter, pageCount, pageIndex, persist, preloadNextChapter, settings.pageMode])

  useEffect(() => () => stopSpeech(), [stopSpeech])
  useEffect(() => () => { if (boundaryTimerRef.current !== null) window.clearTimeout(boundaryTimerRef.current) }, [])

  // Keyboard navigation
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, button')) return
      if (event.key === 'Escape') {
        if (activeDrawer) {
          setActiveDrawer(null)
          return
        }
      }
      if (settings.pageMode === 'paginate') {
        if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
          event.preventDefault()
          goPrevPage()
        } else if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
          event.preventDefault()
          goNextPage()
        }
      } else {
        if (event.key === 'ArrowLeft') { event.preventDefault(); changeChapter(chapterIndex - 1) }
        if (event.key === 'ArrowRight') { event.preventDefault(); changeChapter(chapterIndex + 1) }
        if (event.key === 'PageDown' || event.key === ' ') {
          event.preventDefault()
          if (isAtBottomBoundary(window.scrollY, document.documentElement.scrollHeight, window.innerHeight)) {
            changeChapter(chapterIndex + 1, 'first')
          } else {
            window.scrollBy({ top: window.innerHeight * 0.85, behavior: 'smooth' })
          }
        }
        if (event.key === 'PageUp') {
          event.preventDefault()
          if (isAtTopBoundary(window.scrollY)) {
            changeChapter(chapterIndex - 1, 'last')
          } else {
            window.scrollBy({ top: -window.innerHeight * 0.85, behavior: 'smooth' })
          }
        }
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [activeDrawer, chapterIndex, changeChapter, goNextPage, goPrevPage, settings.pageMode])

  const readerStyle = {
    '--reader-font-size': `${settings.fontSize}px`,
    '--reader-line-height': settings.lineHeight,
    '--reader-letter-spacing': `${settings.letterSpacing}px`,
    '--reader-paragraph-spacing': `${settings.paragraphSpacing}em`,
    '--reader-content-padding': `${settings.contentPadding}px`,
    '--reader-max-width': `${settings.maxWidth}px`,
    '--reader-font-family': getReaderFontFamily(settings.font),
  } as CSSProperties

  // Pointer & Gesture interactions
  const onPointerDown = (event: ReactPointerEvent<HTMLElement>) => {
    if (loading || activeDrawer || isInteractiveReaderTarget(event.target)) return
    pointerStartRef.current = { x: event.clientX, y: event.clientY, target: event.target }
  }

  const onPointerUp = (event: ReactPointerEvent<HTMLElement>) => {
    const start = pointerStartRef.current
    pointerStartRef.current = null
    if (!start || loading || isInteractiveReaderTarget(event.target) || isInteractiveReaderTarget(start.target) || window.getSelection()?.toString()) return

    if (activeDrawer) {
      setActiveDrawer(null)
      return
    }

    if (settings.pageMode === 'paginate') {
      const swipe = swipeDirection(start.x, start.y, event.clientX, event.clientY)
      if (swipe === 'left') {
        goNextPage()
        return
      }
      if (swipe === 'right') {
        goPrevPage()
        return
      }
      if (isTapGesture(start.x, start.y, event.clientX, event.clientY)) {
        const zone = paginateTapZone(event.clientX, window.innerWidth)
        if (zone === 'previous') goPrevPage()
        else if (zone === 'next') goNextPage()
        else setToolbarsVisible(visible => !visible)
      }
    } else {
      if (isTapGesture(start.x, start.y, event.clientX, event.clientY)) {
        const zone = scrollTapZone(event.clientY, window.innerHeight)
        if (zone === 'previous') {
          if (isAtTopBoundary(window.scrollY)) {
            changeChapter(chapterIndex - 1, 'last')
          } else {
            window.scrollBy({ top: -window.innerHeight * 0.85, behavior: 'smooth' })
          }
        } else if (zone === 'next') {
          if (isAtBottomBoundary(window.scrollY, document.documentElement.scrollHeight, window.innerHeight)) {
            changeChapter(chapterIndex + 1, 'first')
          } else {
            window.scrollBy({ top: window.innerHeight * 0.85, behavior: 'smooth' })
          }
        } else {
          setToolbarsVisible(visible => !visible)
        }
      }
    }
  }

  const onWheelPaginate = (event: React.WheelEvent) => {
    if (settings.pageMode !== 'paginate' || activeDrawer || wheelTimerRef.current !== null) return
    const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
    if (Math.abs(delta) < 25) return
    wheelTimerRef.current = window.setTimeout(() => { wheelTimerRef.current = null }, 220)
    if (delta > 0) goNextPage()
    else goPrevPage()
  }

  const cachePercent = Math.min(100, Math.round((cacheStatus.cached / Math.max(1, cacheStatus.total || currentBook.chapters.length)) * 100))

  return <main className={`reader-workspace theme-${settings.theme} ${toolbarsVisible ? 'toolbars-open' : 'toolbars-hidden'} ${settings.sidebarPinned ? 'sidebar-pinned' : ''}`} style={readerStyle}>
    {/* Floating Header */}
    <header className="reader-header">
      <div className="reader-header-left">
        <IconButton label="返回书架" icon="arrowLeft" onClick={() => { persist(); stopSpeech(); onClose() }} />
        <IconButton
          label="目录"
          icon="list"
          onClick={() => {
            if (settings.sidebarPinned) {
              onSettingsChange({ ...settings, sidebarPinned: false })
              setActiveDrawer(null)
            } else {
              setActiveDrawer(d => d === 'toc' ? null : 'toc')
            }
          }}
        />
      </div>
      <strong className="reader-header-title" title={bookName}>{bookName}</strong>
      <div className="reader-header-actions">
        <IconButton label="切换书源" icon="sliders" onClick={() => setShowSourceSwitch(true)} />
        <IconButton label="阅读设置" icon="settings" onClick={() => setActiveDrawer(d => d === 'settings' ? null : 'settings')} />
        <IconButton label={speechState === 'speaking' ? '暂停朗读' : speechState === 'paused' ? '继续朗读' : '朗读本章'} icon="volume2" onClick={toggleSpeech} />
      </div>
    </header>

    {/* TOC Drawer (Left) */}
    <aside className={`reader-drawer reader-drawer-left ${activeDrawer === 'toc' ? 'open' : ''} ${settings.sidebarPinned ? 'pinned' : ''}`} aria-label="目录抽屉">
      <header className="drawer-header">
        <div className="drawer-title"><Icon name="list" /><strong>目录</strong><small>共 {currentBook.chapters.length} 章</small></div>
        <div className="drawer-header-actions">
          <IconButton
            className={`desktop-pin-btn ${settings.sidebarPinned ? 'pinned' : ''}`}
            label={settings.sidebarPinned ? '取消固定目录' : '固定目录到侧边栏'}
            icon={settings.sidebarPinned ? 'pinOff' : 'pin'}
            onClick={() => onSettingsChange({ ...settings, sidebarPinned: !settings.sidebarPinned })}
          />
          <IconButton
            label="关闭目录"
            icon="close"
            onClick={() => {
              if (settings.sidebarPinned) {
                onSettingsChange({ ...settings, sidebarPinned: false })
              }
              setActiveDrawer(null)
            }}
          />
        </div>
      </header>
      <div className="chapter-search">
        <Icon name="search" />
        <input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder="搜索章节" />
      </div>
      <VirtualChapterList
        chapters={filteredChapters}
        activeChapterIndex={chapterIndex}
        onSelect={index => {
          changeChapter(index)
          if (!settings.sidebarPinned) {
            setActiveDrawer(null)
          }
        }}
        itemHeight={40}
        overscan={8}
        autoScrollKey={activeDrawer === 'toc' || settings.sidebarPinned ? 1 : 0}
      />
      <footer className="drawer-footer">
        {/* Cache Control Section */}
        <div className="drawer-cache-section">
          {cacheStatus.state === 'caching' ? (
            <div className="drawer-cache-progress">
              <div className="cache-progress-text">
                <span>正在离线缓存</span>
                <strong>{cachePercent}% ({cacheStatus.cached}/{cacheStatus.total || currentBook.chapters.length}章)</strong>
              </div>
              <div className="cache-progress-bar-track">
                <div className="cache-progress-bar-fill" style={{ width: `${cachePercent}%` }} />
              </div>
              <button className="cache-cancel-btn" onClick={() => void handleCancelCache()}>取消缓存</button>
            </div>
          ) : (
            <div className="drawer-cache-idle">
              <button className="cache-action-btn" onClick={() => void handleCacheBook()}>
                <Icon name="download" />
                <span>
                  {cacheStatus.state === 'ready'
                    ? `已离线缓存 ${cacheStatus.cached} 章 (重新缓存)`
                    : cacheStatus.state === 'failed'
                    ? `重试离线缓存 (${cacheStatus.cached}/${cacheStatus.total || currentBook.chapters.length})`
                    : '下载全本离线缓存'}
                </span>
              </button>
            </div>
          )}
        </div>

        {/* Action Buttons */}
        <div className="drawer-footer-actions">
          <button className="shelf-button switch-source-btn" onClick={() => { setActiveDrawer(null); setShowSourceSwitch(true) }}>
            <Icon name="sliders" />换源
          </button>
          <button className="shelf-button" onClick={() => void toggleShelf()}>
            <Icon name={inShelf ? 'check' : 'plus'} />{inShelf ? '已在书架' : '加书架'}
          </button>
        </div>
      </footer>
    </aside>

    {/* Settings Drawer (Right) */}
    <aside className={`reader-drawer reader-drawer-right ${activeDrawer === 'settings' ? 'open' : ''}`} aria-label="阅读设置">
      <header className="drawer-header">
        <div className="drawer-title"><Icon name="settings" /><strong>阅读设置</strong></div>
        <IconButton label="关闭设置" icon="close" onClick={() => setActiveDrawer(null)} />
      </header>
      <ReaderSettingsControls settings={settings} onChange={onSettingsChange} />
    </aside>

    {/* Backdrop for Drawers */}
    {activeDrawer && (activeDrawer !== 'toc' || !settings.sidebarPinned) && (
      <div className="reader-drawer-backdrop" onClick={() => setActiveDrawer(null)} aria-hidden="true" />
    )}

    {/* Central Reading Canvas */}
    <section className="reader-main">
      {settings.pageMode === 'paginate' ? (
        <div className="reader-paginated-wrap" onPointerDown={onPointerDown} onPointerUp={onPointerUp} onWheel={onWheelPaginate}>
          <div ref={viewportRef} className={`reader-paginated-viewport ${isDoubleColumn ? 'is-double-column' : ''}`}>
            <div className="reader-paginated-track" style={{ transform: `translateX(-${pageIndex * stride}px)` }}>
              <article ref={bodyRef} className={`reader-paginated-column-body font-${settings.font}`} style={{ columnWidth: `${columnWidth}px`, columnGap: `${columnGap}px` }}>
                <h1>{chapter?.title}</h1>
                {loading && <ReaderContentSkeleton />}
                {message && <p className="reader-error">{message}</p>}
                {paragraphs.map((line, index) => <p key={index}>{line}</p>)}
              </article>
            </div>
          </div>
          <footer className="reader-paginated-footer">
            <span>{chapter?.title || ''}</span>
            <span>{pageIndex + 1} / {pageCount}</span>
          </footer>
        </div>
      ) : (
        <article className={`reading-content font-${settings.font}`} onPointerDown={onPointerDown} onPointerUp={onPointerUp}>
          <h1>{chapter?.title}</h1>
          {loading && <ReaderContentSkeleton />}
          {message && <p className="reader-error">{message}</p>}
          {paragraphs.map((line, index) => <p key={index}>{line}</p>)}
          {content && <footer className="reader-navigation"><button disabled={chapterIndex === 0 || loading} onClick={() => changeChapter(chapterIndex - 1)}><Icon name="arrowLeft" />上一章</button><div className="chapter-progress"><i style={{ width: `${chapterProgress}%` }} /><span>{chapterIndex + 1} / {currentBook.chapters.length}</span></div><button disabled={chapterIndex === currentBook.chapters.length - 1 || loading} onClick={() => changeChapter(chapterIndex + 1)}>下一章<Icon name="arrowRight" /></button></footer>}
        </article>
      )}
      {boundaryMessage && <p className="reader-boundary-message" role="status">{boundaryMessage}</p>}
    </section>

    {/* Floating Mobile Bottom Nav */}
    <nav className="mobile-reader-nav">
      <button onClick={() => setActiveDrawer('toc')}><Icon name="list" /><span>目录</span></button>
      <button onClick={() => setShowSourceSwitch(true)}><Icon name="sliders" /><span>换源</span></button>
      <button onClick={() => setActiveDrawer('settings')}><span className="aa">Aa</span><span>设置</span></button>
      <button onClick={() => onSettingsChange({ ...settings, theme: settings.theme === 'dark' ? 'light' : 'dark' })}><Icon name="moon" /><span>夜间</span></button>
    </nav>

    {/* In-reader Source Switch Modal */}
    {showSourceSwitch && (
      <SourceSwitchModal
        bookName={bookName}
        author={currentBook.details.author}
        currentSourceId={currentBook.details.sourceId}
        currentBookUrl={currentBook.bookUrl}
        currentChapterTitle={chapter?.title}
        currentChapterIndex={chapterIndex}
        knownAlternateSources={currentBook.details.alternateSources}
        onSwitch={handleSwitchSource}
        onClose={() => setShowSourceSwitch(false)}
      />
    )}
  </main>
}
