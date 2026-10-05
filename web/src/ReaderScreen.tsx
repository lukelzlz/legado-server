import { CSSProperties, PointerEvent as ReactPointerEvent, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { api, BookDetails, Chapter, ReadingProgress, SearchResult } from './api'
import { Icon } from './icons'
import { calculatePaginationLayout, chapterTurnClassName, dominantScrollSection, findFirstFullyVisibleParagraphIndex, isAtBottomBoundary, isAtTopBoundary, isInteractiveReaderTarget, isScrollSectionTransitionAllowed, isTapGesture, paginateTapZone, scrollCompensation, scrollDominantThreshold, scrollTapZone, splitParagraphs, swipeDirection, ViewportBounds } from './readerInteractions'
import type { ChapterTurnDirection, ChapterTurnPhase } from './readerInteractions'
import {
  DEFAULT_KEEP_BOUNDS,
  desiredScrollWindow,
  scrollSectionKey,
  scrollWindowTrimPlan,
} from './readerScrollWindow'
import type { ScrollMountStrategy, ScrollWindowRange } from './readerScrollWindow'
import { decideScrollStrategy } from './readerScrollStrategy'
import { clampScrollPosition, defaultReaderSettings, getReaderFontFamily, ReaderSettings, scrollPosition, TtsEngineType } from './readerSettings'
import { SourceSwitchModal } from './SourceSwitchModal'
import { cleanAuthor, cleanTitle } from './searchFilters'
import { toast } from './Toast'
import { processChapterForTts, TtsChapterData } from './ttsTextProcessor'
import { ITtsEngine, WebSpeechEngine, HttpAudioTtsEngine, TtsPlayState, TtsSpeakMode, isPlayInterruptedError } from './ttsEngine'
import { TtsSettingsModal, SleepTimerOption } from './TtsSettingsModal'
import { TtsPlayerBar } from './TtsPlayerBar'
import { ReplaceRulesModal } from './ReplaceRulesModal'
import { OfflineCacheModal } from './OfflineCacheModal'
import {
  downloadChaptersToOffline,
  getOfflineChaptersSet,
  putOfflineChapter,
} from './offlineStorage'

export type OpenBook = { details: BookDetails; bookUrl: string; chapters: Chapter[]; progress?: ReadingProgress }

/**
 * keep 策略回收章节前的「滚动静默期」。
 *
 * 手势/惯性仍在进行时改动视口上方的 DOM 并 `scrollTo`，会与 WebKit 的滚动状态互相干扰；
 * 而回收没有时效性（被回收的章节离视口至少一个视口高），等滚动停下来再做完全无损。
 */
const SCROLL_IDLE_BEFORE_TRIM_MS = 220

/**
 * 滚动排障用的渲染采样开关（localStorage: `legado-scroll-debug` = '1'）。
 *
 * 连续滚动的问题都在「某一帧的 DOM 状态」上，而 React 的中间态在组件外看不到。
 * 打开后每次 render 往 `window.__scrollDebug` 记一条，探针脚本据此定位是哪一帧塌的。
 * 默认关闭，判断成本只是一次 localStorage 读取。
 */
const SCROLL_DEBUG_KEY = 'legado-scroll-debug'

/**
 * 滚动模式里已挂载的章节区块。
 *
 * - `strategy === 'window'`（其他引擎）：只有相邻两章在这里，当前章由 `content`/`paragraphs` 提供。
 * - `strategy === 'keep'`（WebKit）：已读章节**长期驻留**在这里，跨章不再从视口上方摘除，
 *   因此不需要滚动补偿（见 [readerScrollWindow] 的推导）。
 */
type ScrollSectionEntry = {
  index: number
  title: string
  paragraphs: string[]
  /** 该章的正文是否已就绪；未就绪时渲染骨架而不是空白（空白 = 高度突变）。 */
  ready: boolean
}

type ReaderScreenProps = {
  openBook: OpenBook
  startIndex: number
  settings: ReaderSettings
  onSettingsChange: (next: ReaderSettings) => void
  onClose: () => void
}

function IconButton({ label, icon, onClick, className = '' }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void; className?: string }) {
  return <button className={`reader-icon-button ${className}`} title={label} aria-label={label} onClick={onClick}><Icon name={icon} /></button>
}

function SettingRange({ label, value, min, max, step, onChange, display }: { label: string; value: number; min: number; max: number; step: number; onChange: (value: number) => void; display: string }) {
  return <label className="setting-range"><span>{label}</span><output>{display}</output><input type="range" min={min} max={max} step={step} value={value} onChange={event => onChange(Number(event.target.value))} /></label>
}

function ReaderSettingsControls({
  settings,
  onChange,
  onOpenReplaceRules,
}: {
  settings: ReaderSettings
  onChange: (next: ReaderSettings) => void
  onOpenReplaceRules?: () => void
}) {
  const { t } = useTranslation()
  const themes = [
    ['light', t('reader.themeLight', '晓白')],
    ['paper', t('reader.themePaper', '护眼')],
    ['dark', t('reader.themeDark', '夜读')],
  ] as const
  const update = (value: Partial<ReaderSettings>) => onChange({ ...settings, ...value })
  return <div className="drawer-scroll-content">
    <section className="setting-section"><span className="setting-label">{t('reader.themeSection', '主题')}</span><div className="theme-grid">{themes.map(([theme, label]) => <button key={theme} className={`theme-choice theme-${theme} ${settings.theme === theme ? 'selected' : ''}`} onClick={() => update({ theme })}><i>{settings.theme === theme && <Icon name="check" />}</i><small>{label}</small></button>)}</div></section>
    <section className="setting-section"><span className="setting-label">{t('reader.fontSection', '字体')}</span><label className="font-select"><select value={settings.font} onChange={event => update({ font: event.target.value as ReaderSettings['font'] })}><option value="song">{t('reader.fontSong', '思源宋体')}</option><option value="hei">{t('reader.fontHei', '黑体 / 苹方')}</option><option value="kai">{t('reader.fontKai', '华文楷体')}</option><option value="fangsong">{t('reader.fontFangsong', '华文仿宋')}</option><option value="system">{t('reader.fontSystem', '系统字体')}</option></select><Icon name="chevronDown" /></label></section>
    <section className="setting-section compact-settings">
      <div className="font-stepper">
        <span>{t('reader.fontSize', '字号')}</span>
        <button
          type="button"
          className="subtle-button font-step-btn"
          aria-label={t('reader.decreaseFontSize', '减小字号')}
          onClick={() => update({ fontSize: Math.max(15, settings.fontSize - 1) })}
        >−</button>
        <output>{settings.fontSize}</output>
        <button
          type="button"
          className="subtle-button font-step-btn"
          aria-label={t('reader.increaseFontSize', '增大字号')}
          onClick={() => update({ fontSize: Math.min(28, settings.fontSize + 1) })}
        >+</button>
      </div>
      <SettingRange label={t('reader.letterSpacing', '字间距')} value={settings.letterSpacing} min={-.25} max={1.5} step={.05} display={settings.letterSpacing.toFixed(2)} onChange={letterSpacing => update({ letterSpacing })} />
      <SettingRange label={t('reader.lineHeight', '行间距')} value={settings.lineHeight} min={1.45} max={2.4} step={.05} display={settings.lineHeight.toFixed(2)} onChange={lineHeight => update({ lineHeight })} />
      <SettingRange label={t('reader.paragraphSpacing', '段间距')} value={settings.paragraphSpacing} min={.7} max={2} step={.05} display={settings.paragraphSpacing.toFixed(2)} onChange={paragraphSpacing => update({ paragraphSpacing })} />
      <SettingRange label={t('reader.contentPadding', '左右边距')} value={settings.contentPadding} min={20} max={120} step={2} display={`${settings.contentPadding}`} onChange={contentPadding => update({ contentPadding })} />
      <SettingRange label={t('reader.maxWidth', '版心宽度')} value={settings.maxWidth} min={560} max={1400} step={20} display={`${settings.maxWidth}px`} onChange={maxWidth => update({ maxWidth })} />
    </section>
    <section className="setting-section"><span className="setting-label">{t('reader.pageMode', '翻页方式')}</span><div className="page-modes"><button className={settings.pageMode === 'scroll' ? 'selected' : ''} onClick={() => update({ pageMode: 'scroll' })}>{t('reader.pageScroll', '连续滚动')}</button><button className={settings.pageMode === 'paginate' ? 'selected' : ''} onClick={() => update({ pageMode: 'paginate' })}>{t('reader.pagePaginate', '平移分页')}</button></div><small className="setting-hint">{settings.pageMode === 'paginate' ? t('reader.pagePaginateHint', '左右轻扫或点击屏幕两侧平滑翻页') : t('reader.pageScrollHint', '垂直滚动阅读，点击上下可快速翻滚')}</small></section>
    <section className="setting-section"><span className="setting-label">{t('reader.columnMode', '分栏排版')}</span><div className="page-modes column-modes"><button className={settings.columnMode === 'auto' ? 'selected' : ''} onClick={() => update({ columnMode: 'auto' })}>{t('reader.columnAuto', '自适应')}</button><button className={settings.columnMode === 'single' ? 'selected' : ''} onClick={() => update({ columnMode: 'single' })}>{t('reader.columnSingle', '单栏')}</button><button className={settings.columnMode === 'double' ? 'selected' : ''} onClick={() => update({ columnMode: 'double' })}>{t('reader.columnDouble', '双栏')}</button></div><small className="setting-hint">{settings.columnMode === 'auto' ? t('reader.columnAutoHint', '宽屏 (≥800px) 自动开启双页分栏') : settings.columnMode === 'double' ? t('reader.columnDoubleHint', '固定双栏双页排版') : t('reader.columnSingleHint', '固定单栏排版')}</small></section>
    {onOpenReplaceRules && (
      <section className="setting-section">
        <span className="setting-label">{t('reader.contentPurify', '内容净化')}</span>
        <button
          type="button"
          className="rules-setting-btn"
          onClick={onOpenReplaceRules}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            width: '100%',
            padding: '10px 14px',
            border: '1px solid var(--line)',
            borderRadius: '8px',
            background: 'var(--surface-muted)',
            color: 'var(--ink)',
            cursor: 'pointer',
            fontSize: '14px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Icon name="edit" />
            <span>{t('reader.replaceRulesManage', '替换净化规则管理')}</span>
          </div>
          <Icon name="arrowRight" />
        </button>
        <small className="setting-hint">{t('reader.replaceRulesHint', '针对当前书籍/书源过滤广告与特定字符')}</small>
      </section>
    )}
    <button className="reset-settings" onClick={() => onChange({ ...defaultReaderSettings, theme: settings.theme })}>{t('reader.restoreDefaults', '恢复默认设置')}</button>
  </div>
}

function ReaderContentSkeleton() {
  const { t } = useTranslation()
  return (
    <div className="reader-loading-container" aria-label={t('reader.loadingChapter', '正在加载正文')}>
      <div className="reader-loading-badge">
        <span className="reader-loading-spinner-ring" />
        <span>{t('reader.fetchingContent', '正在从书源拉取正文...')}</span>
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
  cachedUrlsSet?: Set<string>
  onSelect: (index: number) => void
  itemHeight?: number
  overscan?: number
  className?: string
  autoScrollKey?: string | number
}

export function VirtualChapterList({
  chapters,
  activeChapterIndex,
  cachedUrlsSet,
  onSelect,
  itemHeight = 38,
  overscan = 8,
  className = '',
  autoScrollKey,
}: VirtualChapterListProps) {
  const { t } = useTranslation()
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
      aria-label={t('reader.catalog', '章节目录')}
      onScroll={handleScroll}
    >
      {topSpacer > 0 && <div style={{ height: topSpacer }} aria-hidden="true" />}
      {visibleChapters.map(({ chapter }) => {
        const isCached = cachedUrlsSet ? cachedUrlsSet.has(chapter.url) : false
        return (
          <button
            key={chapter.url}
            style={{ height: itemHeight }}
            className={`reader-chapter-item ${chapter.index === activeChapterIndex ? 'current' : ''} ${isCached ? 'cached' : ''}`}
            onClick={() => onSelect(chapter.index)}
            title={`${chapter.title}${isCached ? ' ' + t('reader.cachedBadgeParens', '(已离线缓存)') : ''}`}
          >
            <i />
            <span className="reader-chapter-title">{chapter.title}</span>
            {isCached && <span className="chapter-cached-badge" title={t('reader.cachedBadge', '已离线缓存')} />}
          </button>
        )
      })}
      {bottomSpacer > 0 && <div style={{ height: bottomSpacer }} aria-hidden="true" />}
      {count === 0 && <p className="reader-status" style={{ padding: '24px 0' }}>{t('reader.noMatchingChapters', '无匹配章节')}</p>}
    </nav>
  )
}

export function ReaderScreen({ openBook, startIndex, settings, onSettingsChange, onClose }: ReaderScreenProps) {
  const { t } = useTranslation()
  const [currentBook, setCurrentBook] = useState<OpenBook>(openBook)
  const [chapterIndex, setChapterIndex] = useState(startIndex)
  const [content, setContent] = useState('')
  const [loadedChapterUrl, setLoadedChapterUrl] = useState('')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(true)
  const [chapterQuery, setChapterQuery] = useState('')
  const deferredQuery = useDeferredValue(chapterQuery)
  const [activeDrawer, setActiveDrawer] = useState<'toc' | 'settings' | null>(null)
  const [toolbarsVisible, setToolbarsVisible] = useState(false)
  const [sliderChapterIndex, setSliderChapterIndex] = useState(chapterIndex)
  const [isDraggingSlider, setIsDraggingSlider] = useState(false)
  const [boundaryMessage, setBoundaryMessage] = useState('')
  const [inShelf, setInShelf] = useState(true)
  const [ttsActive, setTtsActive] = useState(false)
  const [ttsPlayState, setTtsPlayState] = useState<TtsPlayState>('idle')
  const [currentChunkIndex, setCurrentChunkIndex] = useState(0)
  const [sleepTimer, setSleepTimer] = useState<SleepTimerOption>('off')
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null)
  const [showTtsSettings, setShowTtsSettings] = useState(false)
  const [showSourceSwitch, setShowSourceSwitch] = useState(false)
  const [showReplaceRules, setShowReplaceRules] = useState(false)
  const [cachedChapterUrls, setCachedChapterUrls] = useState<Set<string>>(new Set())
  const [offlineStatsModal, setOfflineStatsModal] = useState(false)
  const [localSyncing, setLocalSyncing] = useState(false)
  const [localSyncProgress, setLocalSyncProgress] = useState({ current: 0, total: 0 })
  const localSyncAbortRef = useRef<AbortController | null>(null)
  const [customRangeOpen, setCustomRangeOpen] = useState(false)
  const [customRangeStart, setCustomRangeStart] = useState(1)
  const [customRangeEnd, setCustomRangeEnd] = useState(50)
  const [offlineTtsConfirmOpen, setOfflineTtsConfirmOpen] = useState(false)

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

  /**
   * 跨章翻页的过渡阶段（`null` = 静止）。
   *
   * ⚠️ 旧实现的观感缺陷：章末翻页时 `changeChapter` 先把内容换掉、`pageIndex` 再由
   * 「本章最后一页」直接变成 `0`，而轨道带着 `.reader-paginated-track` 的 CSS 过渡，
   * 于是从 `-N*stride` **动画退回 `0`** —— 方向与手势相反，用户看到的是「弹回第一页」。
   *
   * 现在改为：跨章时**抑制轨道过渡**（瞬时归位，见 `.is-turning`），
   * 由外层 `.reader-chapter-turn` 播一段**沿手势方向**的滑入（向后翻从右进、向前翻从左进），
   * 观感就是「接着往下翻」。
   *
   * `pending` 表示已决定跨章、但新章内容还未就位（预加载命中时几乎同帧），
   * 此时不能开始动画 —— 否则滑入的会是**旧章**那一屏。
   */
  const [chapterTurn, setChapterTurn] = useState<ChapterTurnPhase | null>(null)
  const [chapterTurnDirection, setChapterTurnDirection] = useState<ChapterTurnDirection>('next')
  /** 用 ref 让 `measurePagination` 不必依赖 `chapterTurn` state（避免回调重建引发排版重跑）。 */
  const chapterTurnPendingRef = useRef(false)

  const currentRef = useRef<{ chapter: Chapter; position: number } | null>(null)
  const timerRef = useRef<number | null>(null)
  const restoredRef = useRef(false)
  const autoPlayNextChapterRef = useRef(false)
  const webSpeechEngineRef = useRef<WebSpeechEngine | null>(null)
  const httpAudioEngineRef = useRef<HttpAudioTtsEngine | null>(null)
  const wakeLockRef = useRef<WakeLockSentinel | null>(null)
  const preloadedContentRef = useRef(new Map<string, string>())
  /** 正在回源的章节正文（url → Promise），用于让并发请求共用同一次加载。 */
  const contentLoadsRef = useRef(new Map<string, Promise<string | null>>())
  const lastScrollYRef = useRef(0)
  /** 最近一次非零滚动方向（+1 向下 / -1 向上）；用于判定「向后收敛章节」是否被允许。 */
  const scrollDirectionRef = useRef(0)
  /** 最近一次 scroll 事件的时间戳（用于 keep 策略的「滚动停下再回收」判定）。 */
  const lastScrollEventAtRef = useRef(0)
  const pointerStartRef = useRef<{ x: number; y: number; target: EventTarget | null } | null>(null)
  const boundaryTimerRef = useRef<number | null>(null)
  const targetInitialPageRef = useRef<'first' | 'last' | null>(null)
  const initialPagePositionRef = useRef<number | null>(null)
  const wheelTimerRef = useRef<number | null>(null)
  /**
   * 连续滚动的**挂载策略**（见 [readerScrollStrategy]）。
   *
   * - `window`：现状的三章窗口，跨章平移并在同一帧内补偿；
   * - `keep`：WebKit 专用 —— 只追加不摘章，跨章不改动视口上方结构。
   *
   * 策略在挂载时判定一次：特征探测可能触发一次微型布局实验，不适合每帧重跑。
   */
  const [scrollStrategy] = useState<ScrollMountStrategy>(() => decideScrollStrategy().strategy)
  /**
   * 已挂载的章节区块（按 index 升序、区间连续）。
   *
   * 与 `scrollPrev`/`scrollNext` 的区别：后者固定只有相邻两章（window 策略口径），
   * 这里承载 keep 策略下长期驻留的已读章节，并且是**渲染的唯一来源**。
   */
  const [scrollSections, setScrollSections] = useState<ScrollSectionEntry[]>([])
  /** 上一轮已挂载的区间；keep 策略据此做单调向前扩张。 */
  const scrollRangeRef = useRef<ScrollWindowRange | null>(null)
  /**
   * keep 策略回收前量到的章节高度快照（章节下标 → 高度）。
   *
   * 必须在 DOM 变更**之前**测量：变更之后被移除的章节已经量不到了。
   * 由维护 effect 写入、由同帧补偿的 layout effect 消费（一次性）。
   */
  const scrollTrimHeightsRef = useRef<Map<number, number> | null>(null)
  /** keep 策略下当前章之前保留多少章（见 `DEFAULT_KEEP_BOUNDS.before`）。 */
  const scrollKeepFloor = DEFAULT_KEEP_BOUNDS.before
  /** keep 策略下当前章之后至少保留多少章（跳章时夹层尾部据此回收，见 [scrollWindowTrimPlan]）。 */
  const scrollKeepCeil = Math.max(1, DEFAULT_KEEP_BOUNDS.after)
  /** 滚动静默后递增，用来把「延迟的回收」重新敲一次（见回收 layout effect）。 */
  const [scrollTrimSignal, setScrollTrimSignal] = useState(0)
  /** 各章节区块的 DOM 节点，用于判定「当前读的是哪一章」与位置补偿。 */
  const scrollSectionRefs = useRef(new Map<number, HTMLElement>())
  /**
   * React callback ref 在章节窗口平移时会先以 null 清理旧节点，再登记新节点。
   * 回调若直接 delete(index)，旧回调的清理可能误删刚登记的新节点。
   */
  const scrollSectionRefCallbacks = useRef(new Map<number, (el: HTMLElement | null) => void>())
  const getScrollSectionRef = useCallback((index: number) => {
    const existing = scrollSectionRefCallbacks.current.get(index)
    if (existing) return existing
    let lastNode: HTMLElement | null = null
    let callback: (el: HTMLElement | null) => void
    callback = el => {
      if (el) {
        lastNode = el
        scrollSectionRefs.current.set(index, el)
      } else {
        if (scrollSectionRefs.current.get(index) === lastNode) scrollSectionRefs.current.delete(index)
        if (scrollSectionRefCallbacks.current.get(index) === callback) scrollSectionRefCallbacks.current.delete(index)
      }
    }
    scrollSectionRefCallbacks.current.set(index, callback)
    return callback
  }, [])
  /**
   * 窗口平移的滚动锚点。
   *
   * 平移会从文档顶部移除/插入区块，文档高度随之变化 ⇒ 内容整体位移。
   * 平移前记下**目标章**区块的视口 top，平移后的 layout effect 里再量一次，
   * 差值补回 `scrollBy`，读者就察觉不到窗口换了。
   */
  const scrollShiftAnchorRef = useRef<{ index: number; top: number } | null>(null)
  /** 标记「本次换章是滚动窗口平移」，让加载逻辑不要把滚动位置重置到章首。 */
  const scrollShiftNoJumpRef = useRef(false)
  // Ref to the latest playTtsChunk so that async onEnd callbacks always invoke the freshest version,
  // avoiding stale closure issues when cross-chapter auto-play triggers after chapter content reloads.
  const playTtsChunkRef = useRef<(idx: number, mode?: TtsSpeakMode) => void>(() => undefined)

  const viewportRef = useRef<HTMLDivElement | null>(null)
  const bodyRef = useRef<HTMLDivElement | null>(null)

  // 章节下标先做越界钳制：目录可能被换源/刷新缩水，直接用 chapterIndex 会取到 undefined
  const safeChapterIndex = Math.max(0, Math.min(Math.max(0, currentBook.chapters.length - 1), chapterIndex))
  const chapter = currentBook.chapters[safeChapterIndex]
  const bookName = currentBook.details.name || t('reader.defaultBookName', '书籍正文')

  // 当章节数过少时（如历史旧缓存残留），自动从服务端对齐最新全量目录
  useEffect(() => {
    if (currentBook.chapters.length <= 1) {
      void api.chapters(currentBook.details.sourceId, currentBook.bookUrl)
        .then(latestChapters => {
          if (latestChapters.length > currentBook.chapters.length) {
            setCurrentBook(prev => ({ ...prev, chapters: latestChapters }))
          }
        })
        .catch(() => undefined)
    }
  }, [currentBook.bookUrl, currentBook.chapters.length, currentBook.details.sourceId])

  // Sync cache status with bookshelf and local IndexedDB
  const syncCacheStatus = useCallback(async () => {
    try {
      const [shelf, cachedUrlsRes, localOfflineSet] = await Promise.all([
        api.bookshelf().catch(() => []),
        api.getCachedChapters(currentBook.details.sourceId, currentBook.bookUrl).catch(() => null),
        getOfflineChaptersSet(currentBook.details.sourceId, currentBook.bookUrl).catch(() => new Set<string>()),
      ])

      const mergedSet = new Set<string>(localOfflineSet)
      if (cachedUrlsRes?.cachedChapterUrls) {
        for (const u of cachedUrlsRes.cachedChapterUrls) {
          mergedSet.add(u)
        }
      }
      setCachedChapterUrls(mergedSet)

      const item = shelf.find(s => s.sourceId === currentBook.details.sourceId && s.bookUrl === currentBook.bookUrl)
      if (item) {
        setInShelf(true)
        setCacheStatus(prev => {
          // If state changed to ready or failed, show notification
          if (prev.state === 'caching' && item.cacheState === 'ready') {
            toast.success(t('reader.cacheCompleteToast', { name: bookName, count: item.cachedChapters, defaultValue: `《${bookName}》离线缓存完成（共 ${item.cachedChapters} 章）` }))
          } else if (prev.state === 'caching' && item.cacheState === 'failed') {
            toast.warning(t('reader.cacheInterruptedToast', { name: bookName, error: item.cacheError || t('reader.someChaptersFailed', '部分章节未下载'), defaultValue: `《${bookName}》缓存中断：${item.cacheError || '部分章节未下载'}` }))
          }
          return {
            state: item.cacheState,
            cached: Math.max(item.cachedChapters, mergedSet.size),
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
  }, [bookName, currentBook.bookUrl, currentBook.chapters.length, currentBook.details.sourceId, t])

  useEffect(() => {
    void syncCacheStatus()
  }, [syncCacheStatus])

  useEffect(() => {
    if (cacheStatus.state !== 'caching') return
    const timer = window.setInterval(syncCacheStatus, 1200)
    return () => window.clearInterval(timer)
  }, [cacheStatus.state, syncCacheStatus])

  useEffect(() => {
    const title = chapter?.title ? `${bookName} - ${chapter.title} | ${t('reader.serverTitle', '阅读服务器')}` : `${bookName} | ${t('reader.serverTitle', '阅读服务器')}`
    document.title = title
    return () => {
      document.title = t('reader.serverTitle', '阅读服务器')
    }
  }, [bookName, chapter?.title, t])

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

  const isCurrentChapterLoaded = loadedChapterUrl === chapter?.url && !loading

  const paragraphs = useMemo(() => {
    if (!content || !isCurrentChapterLoaded) return []
    // 与相邻章共用同一个切分实现：两边口径必须同源，否则窗口平移时段落会跳
    return splitParagraphs(content)
  }, [content, isCurrentChapterLoaded])

  /**
   * 挂载窗口里「不是当前章」的两半（当前章单独渲染在它们之间）。
   *
   * ⚠️ 去重是必须的：挂载列表本身包含当前章（[desiredScrollWindow] 的区间一定覆盖 live），
   * 而当前章还要单独渲染一份（它是 TTS / 进度 / 标题的唯一权威）。
   * 漏掉这次去重就会把同一章渲染两遍，形成「重复章节 + 高度翻倍」——
   * 实测表现就是 `chaps=[2,3,4,5,4]` 与随之而来的滚动位移错乱。
   */
  const scrollSectionsBeforeLive = useMemo(
    () => scrollSections.filter(section => section.index < chapterIndex),
    [scrollSections, chapterIndex],
  )
  const scrollSectionsAfterLive = useMemo(
    () => scrollSections.filter(section => section.index > chapterIndex),
    [scrollSections, chapterIndex],
  )

  /**
   * 当前章区块真正要渲染的正文。
   *
   * 优先用挂载列表里**已经准备好**的那一份（keep 策略下正文在切章之前就已驻留），
   * 否则回退到 `content` 派生的段落。这样「live 段高度」在任何一帧都有确定来源，
   * 不会出现「`chapterIndex` 已变、`content` 还没到」的骨架帧 ——
   * 那一帧的高度塌陷会被浏览器换算成一次滚动位置钳制（实测 −711px，用户看到的就是跳动）。
   */
  const liveScrollParagraphs = useMemo(() => {
    const prepared = scrollSections.find(section => section.index === chapterIndex && section.ready)
    return prepared ? prepared.paragraphs : paragraphs
  }, [scrollSections, chapterIndex, paragraphs])

  /**
   * 窗口里最先（最靠上）被挂载的章节下标 —— 它前面不需要隔断，其余章节都需要。
   *
   * ⚠️ 隔断必须**只由章节下标**决定，绝不能依赖「它此刻是不是当前章」。
   * 当前章是单独渲染的、邻居来自挂载列表；若隔断随身份变化，同一章在「当前章 ⇄ 邻居」
   * 之间切换时高度会差一个隔断（约 139px），它在文档里的绝对位置随之改变
   * ⇒ 跨章那一帧视口内容被整体推走一段（实测 139px 的可见位移）。
   */
  const firstMountedIndex = useMemo(() => {
    const indexes = [
      ...scrollSections.map(section => section.index),
      chapterIndex,
    ].filter(index => index >= 0 && index < currentBook.chapters.length)
    return indexes.length > 0 ? Math.min(...indexes) : chapterIndex
  }, [scrollSections, chapterIndex, currentBook.chapters.length])

  /** 挂载窗口里最靠下的章节下标（只统计当前章之后的那些）。 */
  const scrollBottomIndex = useMemo(() => {
    return scrollSectionsAfterLive.length > 0
      ? scrollSectionsAfterLive[scrollSectionsAfterLive.length - 1].index
      : chapterIndex
  }, [scrollSectionsAfterLive, chapterIndex])

  /** 列表里最靠下的那一章是否已经拿到正文 —— 决定「正在加载下一章…」是否该出现。 */
  const scrollNextReady = useMemo(() => {
    const target = scrollSections.find(section => section.index === scrollBottomIndex)
    return target ? target.ready : false
  }, [scrollSections, scrollBottomIndex])

  // 排障采样（默认关闭）：把每次 render 的关键状态记进 window.__scrollDebug
  useEffect(() => {
    if (typeof localStorage === 'undefined' || localStorage.getItem(SCROLL_DEBUG_KEY) !== '1') return
    const store = ((window as unknown as { __scrollDebug?: unknown[] }).__scrollDebug ??= [])
    const liveEl = scrollSectionRefs.current.get(chapterIndex)
    const liveSection = scrollSections.find(section => section.index === chapterIndex)
    store.push({
      t: Math.round(performance.now()),
      chapterIndex,
      liveSectionIndex: scrollSections.find(section => section.index === chapterIndex) ? chapterIndex : null,
      currentRefIndex: currentRef.current?.chapter.index ?? null,
      contentLen: content.length,
      loading,
      msg: message,
      loadedUrlTail: loadedChapterUrl.slice(-24),
      isLoaded: isCurrentChapterLoaded,
      paraCount: paragraphs.length,
      sections: scrollSections.map(section => `${section.index}${section.ready ? 'r' : '-'}`).join(','),
      liveSectionReady: liveSection ? liveSection.ready : null,
      y: Math.round(window.scrollY),
      sh: document.documentElement.scrollHeight,
      liveH: liveEl ? Math.round(liveEl.getBoundingClientRect().height) : null,
    })
    if (store.length > 120) store.splice(0, store.length - 120)
  })

  const persist = useCallback(() => {
    const current = currentRef.current
    if (!current) return
    // 一并提交章节标题：服务端写 bookProgress 进度文件时直接使用，
    // 避免再回查目录缓存（拿不到标题时会放弃写文件）。
    void api.saveProgress(
      currentBook.details.sourceId,
      currentBook.bookUrl,
      current.chapter.url,
      current.chapter.index,
      clampScrollPosition(current.position),
      current.chapter.title,
    ).catch(() => undefined)
  }, [currentBook.bookUrl, currentBook.details.sourceId])

  const ttsData: TtsChapterData = useMemo(() => {
    return processChapterForTts(chapter?.title || '', paragraphs, settings.ttsFilterSymbols)
  }, [chapter?.title, paragraphs, settings.ttsFilterSymbols])

  const currentChunk = ttsData.chunks[currentChunkIndex] ?? null
  const activeParagraphIndex = currentChunk ? currentChunk.paragraphIndex : -1
  const activeSentenceText = currentChunk?.text || ''

  const getTtsEngine = useCallback((type: TtsEngineType): ITtsEngine => {
    if (type === 'webSpeech') {
      if (!webSpeechEngineRef.current) {
        webSpeechEngineRef.current = new WebSpeechEngine()
      }
      return webSpeechEngineRef.current
    }
    if (!httpAudioEngineRef.current) {
      httpAudioEngineRef.current = new HttpAudioTtsEngine()
    }
    return httpAudioEngineRef.current
  }, [])

  const stopAllEngines = useCallback(() => {
    webSpeechEngineRef.current?.stop()
    httpAudioEngineRef.current?.stop()
  }, [])

  const stopTts = useCallback(() => {
    stopAllEngines()
    setTtsPlayState('idle')
    setTtsActive(false)
  }, [stopAllEngines])

  const pauseTts = useCallback(() => {
    const engine = getTtsEngine(settings.ttsEngine)
    engine.pause()
    setTtsPlayState('paused')
  }, [getTtsEngine, settings.ttsEngine])

  const resumeTts = useCallback(() => {
    const engine = getTtsEngine(settings.ttsEngine)
    engine.resume()
    setTtsPlayState('playing')
  }, [getTtsEngine, settings.ttsEngine])

  /**
   * 取一章的正文：优先用预加载缓存，未命中才回源。
   *
   * 同一章的并发加载**共用一个 Promise**：快速来回滚动时窗口会反复重建，
   * 若每次都独立回源就会对同一章发多份重复请求（参考实现的 `isLoading` + `loadedChapters` 也是这个用意）。
   */
  const loadChapterText = useCallback((index: number): Promise<string | null> => {
    const target = currentBook.chapters[index]
    if (!target) return Promise.resolve(null)
    const cached = preloadedContentRef.current.get(target.url)
    if (cached !== undefined) return Promise.resolve(cached)
    const inflight = contentLoadsRef.current.get(target.url)
    if (inflight) return inflight
    const task = api.content(currentBook.details.sourceId, target.url, currentBook.bookUrl)
      .then(result => {
        preloadedContentRef.current.set(target.url, result.content)
        return result.content
      })
      .catch(() => null)
      .finally(() => { contentLoadsRef.current.delete(target.url) })
    contentLoadsRef.current.set(target.url, task)
    return task
  }, [currentBook.bookUrl, currentBook.chapters, currentBook.details.sourceId])

  const preloadNextChapter = useCallback((index: number) => {
    if (!currentBook.chapters[index + 1]) return
    void loadChapterText(index + 1)
  }, [currentBook.chapters, loadChapterText])

  const preloadPrevChapter = useCallback((index: number) => {
    if (!currentBook.chapters[index - 1]) return
    void loadChapterText(index - 1)
  }, [currentBook.chapters, loadChapterText])

  /**
   * 把「目标章正文」**在切章之前**同步准备好：填 `content` + `loadedChapterUrl`。
   *
   * 为什么必须同步（WebKit 实测 2026-10-04）：`chapterIndex` 一旦先变、正文后到，
   * 中间就会有一帧 live 段渲染成骨架 —— 高度 8011 → 327、文档高度 32609 → 24542。
   * 浏览器随即把 `scrollY` **钳制**到 `24542 - 932 = 23610`（实测正好差 −711px），
   * 文档高度恢复后这个位置也回不去了 ⇒ 用户看到的就是「跳动」。
   *
   * 三条来源按可靠性排序：
   *   ① 已挂载章节列表（keep 策略下必然有，且与渲染用的是同一份数据）；
   *   ② 预加载缓存（同一次会话里加载过就命中）；
   *   ③ 兜底：保持原状，让加载 effect 按老路径处理（不支持 keep 的场景）。
   */
  const applyScrollSectionContent = useCallback((index: number): boolean => {
    const target = currentBook.chapters[index]
    if (!target) return false
    const entry = scrollSections.find(section => section.index === index)
    const text = entry?.ready
      ? entry.paragraphs.join('\n')
      : preloadedContentRef.current.get(target.url)
    if (text === undefined) return false
    currentRef.current = { chapter: target, position: currentRef.current?.position ?? 0 }
    flushSync(() => {
      setContent(text)
      setLoadedChapterUrl(target.url)
    })
    // flushSync 期间 `content`/`loadedChapterUrl` 已经上屏，骨架那一帧根本不会出现。
    return true
  }, [currentBook.chapters, scrollSections, setContent, setLoadedChapterUrl])

  /**
   * 连续滚动：维护**已挂载章节列表**，并按策略决定回收。
   *
   * 两条策略共用这一个 effect：
   *
   * - `window`：列表恒为 [上一章, 当前章, 下一章]（当前章由 `content`/`paragraphs` 提供）。
   * - `keep`：列表单调向前扩张（已读章节留着不动），只在章节离视口足够远时才回收，
   *   且**回收与滚动补偿发生在同一帧内**（见下一个 layout effect）——这是与现状最本质的区别：
   *   现状的补偿要等两个 rAF，中间那一帧是「内容已位移、位置没补」，用户看到的就是跳动。
   */
  useEffect(() => {
    // ⚠️ 刻意**不**要求「当前章正文已加载」：挂载窗口的维护与当前章正文是否到位无关，
    // 而一旦把两者绑在一起，正文加载期间挂载列表就会冻结、换章随之整体中止
    //（实测：滚动到一半卡住不动，live 章与 DOM 段数都停在原地）。
    if (settings.pageMode !== 'scroll') return

    const total = currentBook.chapters.length
    const clampedLive = Math.max(0, Math.min(Math.max(0, total - 1), chapterIndex))
    const range = desiredScrollWindow(scrollStrategy, clampedLive, total, scrollRangeRef.current)
    scrollRangeRef.current = range

    // 列出需要挂载/补齐正文的章节：策略给出的区间 ∪ {当前章±1}。
    // 相邻两章是「接着往下读」的必需品，任何策略下都不能缺。
    const targets: number[] = []
    for (let index = range.head; index <= range.tail && index < total; index++) targets.push(index)
    for (const neighbor of [clampedLive - 1, clampedLive + 1]) {
      if (neighbor >= 0 && neighbor < total && !targets.includes(neighbor)) targets.push(neighbor)
    }
    targets.sort((a, b) => a - b)

    let cancelled = false
    const run = async () => {
      const entries: ScrollSectionEntry[] = []
      for (const index of targets) {
        const target = currentBook.chapters[index]
        if (!target) continue
        const text = await loadChapterText(index)
        if (cancelled) return
        entries.push({
          index,
          title: target.title,
          paragraphs: text === null ? [] : splitParagraphs(text),
          ready: text !== null,
        })
      }
      if (cancelled) return
      // 先量高度（此时 DOM 还没变），回收与补偿都由下面的 layout effect 用这份快照完成。
      const heights = new Map<number, number>()
      for (const entry of entries) {
        const el = scrollSectionRefs.current.get(entry.index)
        if (el) heights.set(entry.index, el.getBoundingClientRect().height)
      }
      scrollTrimHeightsRef.current = heights
      setScrollSections(prev => {
        if (prev.length === entries.length && prev.every((item, i) =>
          item.index === entries[i].index && item.ready === entries[i].ready && item.title === entries[i].title
        )) {
          return prev
        }
        return entries
      })
    }
    void run()
    return () => { cancelled = true }
  }, [chapterIndex, currentBook.chapters, loadChapterText, scrollStrategy, settings.pageMode])

  /**
   * 滚动静默后重跑一次回收判定。
   *
   * 维护 effect 可能在读者还在滚时就写好了高度快照，那一轮回收会主动让路（见下）。
   * 这里在「最后一次 scroll 事件之后静默 220ms」再敲一次，保证延迟的回收一定会发生；
   * 否则挂载列表会只增不减（实测：3 段连读 10 章后涨到 6 段、文档高度 32609 → 50080）。
   *
   * 依赖里刻意不放 `scrollTrimSignal`：否则每回收一次都会重置计时器。
   */
  useEffect(() => {
    if (settings.pageMode !== 'scroll' || scrollStrategy !== 'keep') return
    const timer = window.setTimeout(() => setScrollTrimSignal(value => value + 1), SCROLL_IDLE_BEFORE_TRIM_MS)
    return () => window.clearTimeout(timer)
  }, [scrollSections, settings.pageMode, scrollStrategy])

  /**
   * keep 策略的回收 + 同帧滚动补偿。
   *
   * 回收的一定是**视口上方**的章节（见 [scrollWindowTrimPlan]），因此：
   * - 文档高度只减不增，`scrollTo` 不会被浏览器钳制；
   * - 补偿量 = 被移除章节的高度和，方向恒为「往上补」，不需要读锚点几何。
   *
   * 这一步在 DOM 更新后、绘制前执行，所以任何一帧都不会出现「内容已位移、位置没补」。
   *
   * 时机上再加一道闸：**只在滚动停下来之后回收**。
   * 手势/惯性仍在进行时做结构性 DOM 改动 + `scrollTo` 会与 WebKit 的滚动状态相互干扰，
   * 而回收本来就没有时效性（那些章节离视口至少一个视口高），等一秒完全无损。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll' || scrollStrategy !== 'keep') return
    const range = scrollRangeRef.current
    if (!range) return
    const heights = scrollTrimHeightsRef.current
    scrollTrimHeightsRef.current = null
    if (!heights) return
    if (Date.now() - lastScrollEventAtRef.current < SCROLL_IDLE_BEFORE_TRIM_MS) {
      // 读者还在滚：把快照放回去，等滚动停下来的那次 effect 再回收。
      scrollTrimHeightsRef.current = heights
      return
    }
    const rects = new Map<number, { top: number; height: number }>()
    for (const index of heights.keys()) {
      const el = scrollSectionRefs.current.get(index)
      if (!el) continue
      const r = el.getBoundingClientRect()
      rects.set(index, { top: r.top, height: r.height })
    }
    const plan = scrollWindowTrimPlan(range, chapterIndex, window.innerHeight, scrollKeepFloor, rects, scrollKeepCeil)
    if (plan.remove.length === 0) return
    // 只有**视口上方**被移除的章节才需要滚动补偿：移除它们会让文档整体上移。
    // 视口下方的移除与 scrollY 无关（那是跳章留下的夹层），补偿反而是错的。
    let removedAboveHeight = 0
    for (const index of plan.remove) {
      const rect = rects.get(index)
      if (!rect) continue
      if (rect.top + rect.height <= 0) removedAboveHeight += heights.get(index) ?? rect.height
    }
    setScrollSections(prev => prev.filter(entry => !plan.remove.includes(entry.index)))
    if (removedAboveHeight > 0) {
      window.scrollTo({ top: Math.max(0, window.scrollY - removedAboveHeight), behavior: 'auto' })
      lastScrollYRef.current = window.scrollY
    }
  }, [scrollSections, scrollTrimSignal, chapterIndex, scrollStrategy, settings.pageMode])

  const playTtsChunk = useCallback((idx: number, mode: TtsSpeakMode = 'replace') => {
    if (!ttsData.chunks || ttsData.chunks.length === 0) return
    if (idx < 0) idx = 0
    if (idx >= ttsData.chunks.length) {
      // Reached end of current chapter
      if (sleepTimer === 'chapter') {
        toast.info(t('reader.chapterFinishedSleep', '已读完本章，睡眠定时已触发'))
        stopTts()
        return
      }
      if (settings.ttsAutoNextChapter && chapterIndex < currentBook.chapters.length - 1) {
        persist()
        autoPlayNextChapterRef.current = true
        changeChapter(chapterIndex + 1)
        return
      }
      stopTts()
      return
    }

    setCurrentChunkIndex(idx)
    setTtsPlayState('playing')

    const chunk = ttsData.chunks[idx]
    const engine = getTtsEngine(settings.ttsEngine)

    engine.speak(
      chunk.text,
      settings,
      () => {
        const nextIdx = idx + 1
        if (sleepTimer === 'paragraph') {
          const nextChunk = ttsData.chunks[nextIdx]
          if (!nextChunk || nextChunk.paragraphIndex !== chunk.paragraphIndex) {
            toast.info(t('reader.paragraphFinishedSleep', '当前段落已读完，睡眠定时已触发'))
            stopTts()
            return
          }
        }
        playTtsChunkRef.current(nextIdx, 'continue')
      },
      (err) => {
        if (isPlayInterruptedError(err)) return
        toast.warning(err.message || t('reader.ttsInterrupted', '朗读中断'))
        setTtsPlayState('paused')
      },
      mode,
      { chunkId: `${chapterIndex}:${chunk.globalIndex}`, chapterIndex, paragraphIndex: chunk.paragraphIndex },
    )
    if (settings.ttsEngine !== 'webSpeech') {
      const httpEngine = engine as HttpAudioTtsEngine
      for (let lookahead = 1; lookahead <= 5; lookahead++) {
        const nextIdx = idx + lookahead
        if (nextIdx < ttsData.chunks.length) {
          const nextChunk = ttsData.chunks[nextIdx]
          void httpEngine.prefetch(
            nextChunk.text,
            settings,
            { chunkId: `${chapterIndex}:${nextChunk.globalIndex}`, chapterIndex, paragraphIndex: nextChunk.paragraphIndex },
          )
        }
      }
    }
    if (settings.ttsAutoNextChapter && idx >= ttsData.chunks.length - 5) {
      preloadNextChapter(chapterIndex)
    }
  }, [ttsData, sleepTimer, settings, chapterIndex, currentBook.chapters.length, persist, stopTts, getTtsEngine, preloadNextChapter])

  playTtsChunkRef.current = playTtsChunk

  const getInitialTtsChunkIndex = useCallback((): number => {
    if (!ttsData.chunks || ttsData.chunks.length === 0) return 0

    let viewportBounds: ViewportBounds
    if (settings.pageMode === 'paginate') {
      const vEl = viewportRef.current
      if (vEl) {
        const r = vEl.getBoundingClientRect()
        viewportBounds = { top: r.top, bottom: r.bottom, left: r.left, right: r.right }
      } else {
        viewportBounds = { top: 0, bottom: window.innerHeight, left: 0, right: window.innerWidth }
      }
    } else {
      // In scroll mode, header bar is fixed at 56px height (top boundary)
      const topBarHeight = 56
      viewportBounds = { top: topBarHeight, bottom: window.innerHeight, left: 0, right: window.innerWidth }
    }

    // 1. Check if chapter title <h1> is fully visible on the current screen
    const h1El = document.querySelector(
      settings.pageMode === 'paginate'
        ? '.reader-paginated-column-body h1'
        : '.reading-content h1'
    ) as HTMLElement | null

    if (h1El) {
      const h1Rect = h1El.getBoundingClientRect()
      const isH1FullyVisible =
        h1Rect.top >= viewportBounds.top - 2 &&
        h1Rect.bottom <= viewportBounds.bottom + 2 &&
        h1Rect.left >= viewportBounds.left - 2 &&
        h1Rect.right <= viewportBounds.right + 2
      if (isH1FullyVisible) {
        const titleChunk = ttsData.chunks.find(c => c.paragraphIndex === -1)
        if (titleChunk) return titleChunk.globalIndex
      }
    }

    // 2. Query all paragraph elements with data-paragraph-index
    const paragraphEls = Array.from(
      document.querySelectorAll<HTMLElement>(
        settings.pageMode === 'paginate'
          ? '.reader-paginated-column-body [data-paragraph-index]'
          : '.reading-content [data-paragraph-index]'
      )
    )

    if (paragraphEls.length === 0) return 0

    const paragraphItems = paragraphEls.map(el => {
      const pIdx = parseInt(el.getAttribute('data-paragraph-index') || '0', 10)
      const r = el.getBoundingClientRect()
      return {
        index: pIdx,
        rect: { top: r.top, bottom: r.bottom, left: r.left, right: r.right },
      }
    })

    const targetPIndex = findFirstFullyVisibleParagraphIndex(paragraphItems, viewportBounds)

    const targetChunk = ttsData.chunks.find(c => c.paragraphIndex === targetPIndex)
    return targetChunk ? targetChunk.globalIndex : 0
  }, [ttsData.chunks, settings.pageMode])

  const toggleTts = useCallback(() => {
    if (!ttsActive) {
      if (typeof navigator !== 'undefined' && !navigator.onLine && settings.ttsEngine !== 'webSpeech') {
        setOfflineTtsConfirmOpen(true)
        return
      }
      setTtsActive(true)
      const startIdx = getInitialTtsChunkIndex()
      playTtsChunk(startIdx)
    } else if (ttsPlayState === 'playing') {
      pauseTts()
    } else if (ttsPlayState === 'paused') {
      resumeTts()
    } else {
      playTtsChunk(currentChunkIndex)
    }
  }, [ttsActive, ttsPlayState, playTtsChunk, currentChunkIndex, pauseTts, resumeTts, getInitialTtsChunkIndex, settings.ttsEngine])

  const handleParagraphClick = useCallback((pIdx: number) => {
    // Only jump to paragraph if TTS is already active; don't auto-start reading on arbitrary clicks
    if (!ttsActive) return
    const targetChunk = ttsData.chunks.find(c => c.paragraphIndex === pIdx)
    if (targetChunk) {
      playTtsChunk(targetChunk.globalIndex)
    }
  }, [ttsData.chunks, ttsActive, playTtsChunk])

  const handlePrevChunk = useCallback(() => {
    const prevIdx = Math.max(0, currentChunkIndex - 1)
    playTtsChunk(prevIdx)
  }, [currentChunkIndex, playTtsChunk])

  const handleNextChunk = useCallback(() => {
    const nextIdx = Math.min(ttsData.chunks.length - 1, currentChunkIndex + 1)
    playTtsChunk(nextIdx)
  }, [currentChunkIndex, ttsData.chunks.length, playTtsChunk])

  const handlePrevChapterTts = useCallback(() => {
    if (chapterIndex > 0) {
      persist()
      autoPlayNextChapterRef.current = true
      changeChapter(chapterIndex - 1)
    }
  }, [chapterIndex, persist])

  const handleNextChapterTts = useCallback(() => {
    if (chapterIndex < currentBook.chapters.length - 1) {
      persist()
      autoPlayNextChapterRef.current = true
      changeChapter(chapterIndex + 1)
    }
  }, [chapterIndex, currentBook.chapters.length, persist])

  const renderParagraphContent = (rawLine: string, pIndex: number) => {
    if (!ttsActive || activeParagraphIndex !== pIndex || !currentChunk) {
      return rawLine
    }
    const sentenceText = currentChunk.text
    const sIdx = rawLine.indexOf(sentenceText)
    if (sIdx >= 0) {
      const before = rawLine.slice(0, sIdx)
      const after = rawLine.slice(sIdx + sentenceText.length)
      return <>
        {before}
        <span className="tts-active-sentence">{sentenceText}</span>
        {after}
      </>
    }
    return rawLine
  }

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
        showBoundaryNotice(nextIndex < 0 ? t('reader.firstChapter', '已是第一章') : t('reader.lastChapter', '已是最后一章'))
      }
      return
    }
    persist()
    if (!autoPlayNextChapterRef.current) {
      stopTts()
    } else if (settings.ttsEngine === 'webSpeech') {
      stopAllEngines()
    }
    targetInitialPageRef.current = targetPage === 'auto' ? null : targetPage
    setLoadedChapterUrl('')
    setContent('')
    setLoading(true)
    setChapterIndex(nextIndex)
    setActiveDrawer(null)
  }, [chapterIndex, currentBook.chapters.length, persist, settings.ttsEngine, showBoundaryNotice, stopTts, stopAllEngines, t])

  const isLocalBook = currentBook.details.sourceId === 'loc_book' || currentBook.bookUrl.startsWith('local://')

  const toggleShelf = async () => {
    if (inShelf) {
      if (!confirm(t('shelf.removeFromShelfConfirm', { name: bookName, defaultValue: `移出“${bookName}”将清除书架、阅读进度和缓存封面，确定继续吗？` }))) return
      await api.removeFromBookshelf(currentBook.details.sourceId, currentBook.bookUrl)
      setInShelf(false)
      toast.info(t('reader.removedFromShelfToast', { name: bookName, defaultValue: `《${bookName}》已移出书架` }))
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
    toast.success(t('reader.addedToShelfToast', { name: bookName, defaultValue: `《${bookName}》已加入书架` }))
  }

  const handleCacheBook = async () => {
    await handleCacheRange('all')
  }

  const handleCacheRange = async (mode: 'next50' | 'next100' | 'all' | 'custom', start?: number, end?: number) => {
    let s = chapterIndex
    let count: number | undefined
    let e: number | undefined

    if (mode === 'next50') {
      count = 50
    } else if (mode === 'next100') {
      count = 100
    } else if (mode === 'all') {
      s = 0
      count = undefined
    } else if (mode === 'custom') {
      s = Math.max(0, (start ?? 1) - 1)
      e = Math.max(s, (end ?? currentBook.chapters.length) - 1)
      count = undefined
    }

    try {
      await api.cacheBookshelfRange({
        sourceId: currentBook.details.sourceId,
        bookUrl: currentBook.bookUrl,
        startIndex: s,
        endIndex: e,
        count,
      })
      toast.success(
        mode === 'all'
          ? t('shelf.addedToCacheQueueAll', '已加入全本离线缓存队列')
          : mode === 'next50'
          ? t('shelf.startedCacheNext50', '已开始缓存后续 50 章')
          : mode === 'next100'
          ? t('shelf.startedCacheNext100', '已开始缓存后续 100 章')
          : t('shelf.startedCacheRange', { start: s + 1, end: (e ?? 0) + 1, defaultValue: `已开始缓存第 ${s + 1} ~ ${(e ?? 0) + 1} 章` })
      )
      setCacheStatus(prev => ({ ...prev, state: 'caching', error: undefined }))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('shelf.cannotStartCache', '发起缓存失败'))
    }
  }

  const handleSyncToLocal = async (rangeChapters?: Chapter[]) => {
    const targetChapters = rangeChapters || currentBook.chapters.slice(chapterIndex, chapterIndex + 50)
    localSyncAbortRef.current?.abort()
    const controller = new AbortController()
    localSyncAbortRef.current = controller

    setLocalSyncing(true)
    setLocalSyncProgress({ current: 0, total: targetChapters.length })

    try {
      const res = await downloadChaptersToOffline(
        currentBook.details.sourceId,
        currentBook.bookUrl,
        targetChapters,
        async (chUrl) => {
          return api.content(currentBook.details.sourceId, chUrl, currentBook.bookUrl, controller.signal)
        },
        (comp, tot) => {
          setLocalSyncProgress({ current: comp, total: tot })
        },
        controller.signal
      )
      if (!controller.signal.aborted) {
        toast.success(t('reader.offlineSyncSuccess', { success: res.successful, skipped: targetChapters.length - res.failed - res.successful, defaultValue: `离线同步完成：成功 ${res.successful} 章，跳过/已缓存 ${targetChapters.length - res.failed - res.successful} 章` }))
        const offlineSet = await getOfflineChaptersSet(currentBook.details.sourceId, currentBook.bookUrl)
        setCachedChapterUrls(offlineSet)
      }
    } catch (err) {
      if (!controller.signal.aborted) {
        toast.error(t('reader.offlineSyncInterrupted', '离线同步中断'))
      }
    } finally {
      if (!controller.signal.aborted) {
        setLocalSyncing(false)
      }
    }
  }

  const handleCancelCache = async () => {
    try {
      await api.cancelBookCache(currentBook.details.sourceId, currentBook.bookUrl)
      setCacheStatus(prev => ({ ...prev, state: 'failed', error: t('reader.cancelledCache', '已取消缓存') }))
      toast.info(t('reader.cancelledCacheToast', { name: bookName, defaultValue: `已取消《${bookName}》的离线缓存` }))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('reader.cancelCacheFailed', '无法取消缓存'))
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
      name: cleanTitle(details.name?.trim() || currentBook.details.name || t('common.unknown', '未知书名')) || currentBook.details.name,
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
    /**
     * 本次换章是否来自「滚动窗口平移」。
     *
     * 平移时必须**保留**现有正文（清空会让中间区块塌成空白 ⇒ 闪白 + 高度突变 + 滚动跳），
     * 也不能把滚动位置重置回章首。标志在此消费一次。
     */
    const seamlessShift = scrollShiftNoJumpRef.current
    scrollShiftNoJumpRef.current = false
    /**
     * 已经把正文拿在手里的章节**绝不能走「清空 + loading」这条路**。
     *
     * 事故（WebKit 实测 2026-10-04）：keep 策略下跨章时 `shiftScrollWindow` 会先同步把新章正文
     * 填进 live 段（`flushSync`），紧接着这个 effect 因为 `startIndex` 等依赖变化又跑了一次，
     * 此时 `seamlessShift` 已被上一次消费掉 ⇒ 走进清空分支 ⇒ live 段高度 8011 → 327，
     * 文档高度同步塌 8000px，WebKit 把 `scrollY` 钳到这个新高度上（实测 −461px 的一次性位移）。
     * 预加载缓存命中的章节本来就是「同步可用」的，不该有任何清空中间态。
     */
    const cachedText = preloadedContentRef.current.get(chapter.url)
    if (typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
      // 排障：把「加载 effect 每次运行时的章节 / 是否走清空路径 / 从哪触发」记下来
      const store = ((window as unknown as { __loadDebug?: unknown[] }).__loadDebug ??= [])
      store.push({
        t: Math.round(performance.now()), chapterIndex, chapterIndexProp: chapter.index, title: chapter.title.slice(0, 14),
        seamlessShift, fromCache: cachedText !== undefined, stack: (new Error('load').stack || '').split('\n').slice(1, 4).join(' | '),
      })
      if (store.length > 120) store.splice(0, store.length - 120)
    }
    if (cachedText === undefined && !seamlessShift) {
      setLoading(true)
      setContent('')
    }
    setMessage('')
    const applyContent = (nextContent: string) => {
      if (cancelled) return
      setContent(nextContent)
      setLoadedChapterUrl(chapter.url)
      const position = !restoredRef.current && chapter.index === startIndex ? currentBook.progress?.scrollPosition ?? 0 : 0
      restoredRef.current = true
      currentRef.current = { chapter, position }
      // 平移时不设滚动目标：位置由窗口补偿与「当前章」判定接管，设了会被拉回章首
      initialPagePositionRef.current = seamlessShift ? null : position
      setLoading(false)
    }
    const preloaded = preloadedContentRef.current.get(chapter.url)
    if (preloaded) {
      applyContent(preloaded)
    } else {
      void api.content(currentBook.details.sourceId, chapter.url, currentBook.bookUrl)
        .then(result => applyContent(result.content))
        .catch(error => {
          if (!cancelled) setMessage(error instanceof Error ? error.message : t('reader.cannotReadContent', '无法读取正文'))
        })
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }
    return () => {
      cancelled = true
    }
  }, [chapter, currentBook.bookUrl, currentBook.details.sourceId, currentBook.progress?.scrollPosition, startIndex])

  // Scroll mode layout effect to restore position or jump to start/end
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll' || loading || !content) return
    /**
     * ⚠️ 这里**不能**把恢复目标取一次就置 null。
     *
     * 这个 effect 会因为 `scrollSections` 变化而重跑（相邻章是异步挂载的），
     * 而重跑时相邻章刚进 DOM、会把目标章往下推 —— 正是最需要重新对齐的时刻。
     * 早期实现「取一次就清空」，第二次运行拿不到目标（`wanted = null`）⇒ 对齐循环失去目标、
     * 目标章被推到视口下方而无人纠正（实测：记录第 4 章、视口停在文档顶部、liveTop=16992）。
     *
     * 改为：只在「真正对齐到目标」之后才清空，保证重跑期间目标始终可用。
     */
    const targetMode = targetInitialPageRef.current
    const initialPos = initialPagePositionRef.current
    const clearRestoreTarget = () => {
      targetInitialPageRef.current = null
      initialPagePositionRef.current = null
    }

    /** 目标章区块（ref 未注册时按结构兜底，与注册时机解耦）。 */
    const liveSectionEl = () => scrollSectionRefs.current.get(chapterIndex)
      ?? document.querySelector<HTMLElement>(`.reading-scroll-window > .reading-content[data-chapter-index="${chapterIndex}"]`)

    /**
     * 目标章区块的视口 top 应该是多少（相对当前布局）。
     *
     * - `first`：章首对齐视口顶部 ⇒ 0
     * - `last`：章尾对齐视口底部 ⇒ 区块底部贴住视口底部（等价于把「章尾-视口高」放在视口顶部）
     * - 按百分比恢复：`-range * position`（区块高度超出视口的那部分按比例滚过）
     */
    const computeTargetViewportTop = (): number | null => {
      const el = liveSectionEl()
      if (!el) return null
      const range = el.getBoundingClientRect().height - window.innerHeight
      if (targetMode === 'first') return 0
      if (targetMode === 'last') return range > 0 ? -range : 0
      if (initialPos !== null) return range > 0 ? -Math.round(range * initialPos) : 0
      return null
    }

    /**
     * 把目标章的**视口位置**摆到期望值上。
     *
     * ⚠️ 这里刻意不保留「目标 scrollY」做绝对落点：相邻章正文晚进 DOM 时，
     * 目标章的**绝对位置会变大**，而 `scrollY` 是文档绝对坐标 ⇒
     * 只比较 `scrollY` 会误判成「已到位」并停止纠正，结果视口停在上一章、进度却记着当前章
     * （实测就是这样出现「记录第 7 章、视口显示第 5 章」的长期错位，用户一上滑就跳回上一章）。
     * 因此每帧都重新量一次目标章的视口 top，并按差值重摆。
     */
    const alignTargetSection = () => {
      const el = liveSectionEl()
      const wanted = computeTargetViewportTop()
      if (!el || wanted === null) return null
      const actualTop = el.getBoundingClientRect().top
      const targetScroll = Math.max(0, window.scrollY + (actualTop - wanted))
      lastScrollYRef.current = targetScroll
      window.scrollTo({ top: targetScroll, behavior: 'auto' })
      return { el, wanted }
    }

    // ⚠️ 「对齐成功」才算完成：目标章区块还没进 DOM 时 `alignTargetSection()` 返回 null，
    // 若把 null 当作「已到位」就会立刻停手 —— 而相邻章随后才挂载、把目标章推到更下面，
    // 结果是视口停在上一章、进度却记着当前章（实测：记录第 4 章、视口显示第 2 章正文）。
    // 因此用一个显式标志：只有真正摆到期望位置才收工。
    let aligned = false
    const initial = alignTargetSection()
    if (initial !== null) aligned = true
    if (typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
      const store = ((window as unknown as { __alignDebug?: unknown[] }).__alignDebug ??= [])
      store.push({
        t: Math.round(performance.now()), chapterIndex, targetMode, initialPos,
        hadSection: initial !== null, y: Math.round(window.scrollY),
        liveTop: initial ? Math.round(initial.el.getBoundingClientRect().top) : null,
        wanted: initial ? Math.round(initial.wanted) : null,
        paragraphs: paragraphs.length,
      })
      if (store.length > 60) store.splice(0, store.length - 60)
    }
    // 恢复位置必须**重试到真正到位**为止：恢复那一次相邻章正文往往还没进 DOM，
    // 文档高度不够 ⇒ `scrollTo(目标)` 被浏览器**钳制**到 0（实测钩子：参数 0、scrollY 保持 0），
    // 之后再没人纠正 ⇒ 视口停在文档顶部（= 插入上方的上一章），而进度记着当前章；
    // 用户一滚动，视口中心落在上一章 ⇒ 窗口回退一章并保存 ⇒ 反复进出就是一路往回跳。
    // 判据用「还没到目标就继续」：目标会随相邻章入 DOM 而变大，到位即停（上限约 10 秒）。
    //
    // 两个边界（维护者评审指出）：
    // ① 期望段数必须**按首/末章动态算**：首章没有上一章、末章没有下一章，
    //    写死 3 会让这两种情况永远判为「没铺齐」而空转满 10 秒。
    // ② 重试期间读者一旦主动操作（滚轮/触摸/按下指针）必须**立刻让路**，
    //    否则会和用户手势抢夺滚动条。
    let frames = 0
    let rafId: number | null = null
    const cancelRetry = () => {
      if (rafId !== null) {
        window.cancelAnimationFrame(rafId)
        rafId = null
      }
    }
    window.addEventListener('wheel', cancelRetry, { passive: true, once: true })
    window.addEventListener('touchstart', cancelRetry, { passive: true, once: true })
    window.addEventListener('pointerdown', cancelRetry, { passive: true, once: true })

    rafId = window.requestAnimationFrame(function retry() {
      frames += 1
      // 「窗口是否已铺齐」必须**先判段数，再看落点**。
      // 只比较滚动量会被骗：相邻章还没进 DOM 时当前章恰好落在文档顶部，
      // 目标算出来正是 0，于是 `0+2 >= 0` 判定「已到位」立刻停手 —— 实测就卡在这里。
      // 段数按策略动态算：`window` 是「当前章 ± 1」，`keep` 是已读章节驻留后的实际挂载数。
      const hasPrev = chapterIndex > 0
      const hasNext = chapterIndex < currentBook.chapters.length - 1
      const expected = scrollStrategy === 'keep'
        ? scrollSections.filter(section => section.index !== chapterIndex).length + (hasNext && !scrollSections.some(section => section.index === chapterIndex + 1) ? 1 : 0)
        : 1 + (hasPrev ? 1 : 0) + (hasNext ? 1 : 0)
      const rendered = document.querySelectorAll('.reading-scroll-window > .reading-content').length
      // 当前章区块必须已经存在，否则任何落点计算都不可信。
      const liveRendered = Boolean(scrollSectionRefs.current.get(chapterIndex))
        || Boolean(document.querySelector(`.reading-scroll-window > .reading-content[data-chapter-index="${chapterIndex}"]`))
      const complete = rendered >= expected && liveRendered
      let reached = false
      if (complete) {
        const result = alignTargetSection()
        if (result !== null) {
          aligned = true
          // 判据落在「目标章的视口位置」上，而不是「滚动量」：
          // 这样即使上方章节在滚动之后才挂载（目标章绝对位置变大），也会继续纠正到正确落点。
          // 浏览器到达文档边界时无法再对齐，此时认账停手，避免空转。
          const actualTop = result.el.getBoundingClientRect().top
          const atDocumentEdge = (actualTop - result.wanted) > 0 &&
            window.scrollY >= document.documentElement.scrollHeight - window.innerHeight - 1
          reached = Math.abs(actualTop - result.wanted) <= 2 || atDocumentEdge
        }
      } else if (!aligned) {
        // 还没对齐过，而且窗口也没铺齐：继续等（不能因为「没铺齐」就永久停手）
        reached = false
      } else {
        reached = true
      }
      // 对齐成功之后仍要多守一段时间：相邻章是**异步**挂载的，它们一进 DOM 就会把目标章推下去，
      // 若此刻已经收工就再也没人纠正（实测：第一次对齐 liveTop=0 正确，约 150ms 后相邻章挂载，
      // 目标章被推到 16992，而重试已停手 ⇒ 视口停在上一章、进度却记着当前章）。
      const MIN_GUARD_FRAMES = 120
      if (!reached || frames < MIN_GUARD_FRAMES) {
        if (frames < 600) {
          rafId = window.requestAnimationFrame(retry)
        } else {
          rafId = null
          clearRestoreTarget()
        }
      } else {
        rafId = null
        // 真正稳定对齐之后才清空恢复目标（见上方注释：提前清空会让重跑失去目标）。
        clearRestoreTarget()
      }
    })
    return () => {
      cancelRetry()
      window.removeEventListener('wheel', cancelRetry)
      window.removeEventListener('touchstart', cancelRetry)
      window.removeEventListener('pointerdown', cancelRetry)
    }
    // chapterIndex 入依赖：从目录/滑块跳章后要按**新章区块**重新定位
  }, [chapterIndex, content, loading, scrollSections, scrollStrategy, settings.pageMode])

  // Pagination measurement
  const measurePagination = useCallback(() => {
    const viewport = viewportRef.current
    const body = bodyRef.current
    if (!viewport || !body) return
    const w = viewport.clientWidth
    if (w <= 0) return
    if (loading || !content) return

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

    /**
     * 新章的分页已经量好 ⇒ 内容真的就位了，可以开始「滑入」。
     *
     * 刻意用 ref 而不是读 `chapterTurn` state：那样会把 `chapterTurn` 塞进本回调的依赖，
     * 每次过渡都重建 `measurePagination`，进而让上层 layout effect 重跑一遍排版。
     */
    const startChapterTurnIn = () => {
      if (!chapterTurnPendingRef.current) return
      chapterTurnPendingRef.current = false
      setChapterTurn('in')
    }

    if (targetInitialPageRef.current === 'last') {
      targetInitialPageRef.current = null
      initialPagePositionRef.current = null
      setPageIndex(layout.pageCount - 1)
      startChapterTurnIn()
    } else if (targetInitialPageRef.current === 'first') {
      targetInitialPageRef.current = null
      initialPagePositionRef.current = null
      setPageIndex(0)
      startChapterTurnIn()
    } else if (initialPagePositionRef.current !== null) {
      const pos = initialPagePositionRef.current
      initialPagePositionRef.current = null
      const target = Math.min(layout.pageCount - 1, Math.max(0, Math.round(pos * (layout.pageCount - 1))))
      setPageIndex(target)
    } else {
      setPageIndex(curr => Math.min(curr, layout.pageCount - 1))
    }
  }, [columnGap, content, loading, settings.columnMode])

  useLayoutEffect(() => {
    if (settings.pageMode !== 'paginate') return
    if (loading || !content) return
    measurePagination()
    const viewport = viewportRef.current
    if (!viewport) return
    const observer = new ResizeObserver(() => measurePagination())
    observer.observe(viewport)
    return () => observer.disconnect()
  }, [
    settings.pageMode,
    content,
    loading,
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

  /**
   * 跨章过渡的兜底解除。
   *
   * 正常情况下由 `measurePagination` 推进到「滑入」、再由动画 `animationend` 收尾。
   * 但**新章内容取失败**（报错、超时）时排版永远不会发生，过渡会一直停在 `pending`，
   * 而 `pending` 期间轨道是「不带动画」的 —— 结果是从此每次翻页都不再有过渡。
   * 因此必须有一条不依赖内容成功的退路。
   */
  useEffect(() => {
    if (chapterTurn !== 'pending') return
    const timer = window.setTimeout(() => {
      chapterTurnPendingRef.current = false
      setChapterTurn(null)
    }, 4000)
    return () => window.clearTimeout(timer)
  }, [chapterTurn])

  /**
   * 章末翻页时开启方向性过渡。
   *
   * 只置 `pending`：真正的滑入要等新章内容测量完成（见 [measurePagination]），
   * 否则动画滑进来的还是旧章那一屏。
   */
  const beginChapterTurn = useCallback((direction: ChapterTurnDirection) => {
    chapterTurnPendingRef.current = true
    setChapterTurnDirection(direction)
    setChapterTurn('pending')
  }, [])

  const goNextPage = useCallback(() => {
    if (pageIndex < pageCount - 1) {
      setPageIndex(p => p + 1)
    } else {
      // 章末向后翻：新章从右侧滑入（接着往下翻），而不是让轨道倒退回第一页
      if (chapterIndex < currentBook.chapters.length - 1) beginChapterTurn('next')
      changeChapter(chapterIndex + 1, 'first')
    }
  }, [beginChapterTurn, chapterIndex, changeChapter, currentBook.chapters.length, pageCount, pageIndex])

  const goPrevPage = useCallback(() => {
    if (pageIndex > 0) {
      setPageIndex(p => p - 1)
    } else {
      // 章首向前翻：新章从左侧滑入（与手势同向）
      if (chapterIndex > 0) beginChapterTurn('prev')
      changeChapter(chapterIndex - 1, 'last')
    }
  }, [beginChapterTurn, chapterIndex, changeChapter, pageIndex])

  // Sync scroll / page progress
  /**
   * 滚动模式的跨章切换：把「当前章」换成 `nextIndex`。
   *
   * ⚠️ 刻意**不走 `changeChapter`**：那条路会 `setContent('')` + `setLoading(true)`，
   * 在连续滚动里表现为「内容闪白 + 滚动位置跳回章首」。
   *
   * 两条策略在这里分道：
   *
   * - `keep`（WebKit）：目标章**已经在文档里**（由挂载列表单调扩张保证），
   *   所以只改 `chapterIndex` 与 `currentRef`，**DOM 一个字节都不动** ⇒ 无需任何补偿。
   *   实测数据：现状 path 在这一步会 `scrollTo` 位移 −8238px（文档同时塌 8400px），
   *   这正是 iOS 上抖动的来源。
   * - `window`（其他引擎）：三章窗口必须同步平移 + 记锚点，由 layout effect 补偿。
   */
  const shiftScrollWindow = useCallback((nextIndex: number) => {
    if (nextIndex < 0 || nextIndex >= currentBook.chapters.length) return
    const previousChapter = currentRef.current?.chapter
    if (!previousChapter || nextIndex === previousChapter.index) return
    const target = currentBook.chapters[nextIndex]
    if (!target) return
    const distance = Math.abs(nextIndex - previousChapter.index)

    if (scrollStrategy === 'keep') {
      // keep 策略下「当前章」由几何派生（见 scroll 处理），跨度可能是多章
      //（快速滑动/跳章/几何纠正都会出现）。只要目标章正文已挂载即可安全切换 ——
      // 结构不动、高度不变，因此多章跨度同样不会带来跳动。
      const entry = scrollSections.find(section => section.index === nextIndex)
      if (!entry?.ready) return
      scrollShiftNoJumpRef.current = true
      currentRef.current = { chapter: target, position: currentRef.current?.position ?? 0 }
      // 与 changeChapter 的手动换章保持同一语义：朗读中换章必须停掉朗读。
      if (!autoPlayNextChapterRef.current) {
        stopTts()
      } else if (settings.ttsEngine === 'webSpeech') {
        stopAllEngines()
      }
      // 把目标章正文**同步**提升为当前章正文：否则 `chapterIndex` 先变、`content` 后到，
      // 中间会有一帧骨架（高度 8011 → 327），浏览器把 scrollY 钳到缩小后的文档高度上
      // ⇒ 一次性位移（实测 −711px）。
      applyScrollSectionContent(nextIndex)
      setChapterIndex(nextIndex)
      return
    }

    // window 策略：只允许逐章平移。若旧的 ref/滚动回调在 Safari 中滞后，
    // 禁止一次把当前章直接替换成相隔多章的过期区块（这条路径要动 DOM，必须保守）。
    if (distance !== 1) return

    // 锚点：目标章区块当前的视口 top，平移后补回同一位置。
    const anchorEl = document.querySelector<HTMLElement>(`.reading-scroll-window > .reading-content[data-chapter-index="${nextIndex}"]`)
      ?? scrollSectionRefs.current.get(nextIndex)
    // React commit 尚未完成或 iOS Safari 短暂清理 callback ref 时，不能无锚点平移。
    // 否则章节高度变化会交给 Safari 的 scroll anchoring 处理，视口可能回跳整章。
    if (!anchorEl?.isConnected) return
    // 一次窗口平移尚未完成时不要覆盖旧锚点。快速惯性滚动在 iOS 上可能
    // 连续触发多个 scroll 回调，覆盖锚点会让一次补偿变成跨多章的错误补偿。
    if (scrollShiftAnchorRef.current) return
    scrollShiftAnchorRef.current = { index: nextIndex, top: anchorEl.getBoundingClientRect().top }
    scrollShiftNoJumpRef.current = true
    currentRef.current = { chapter: target, position: currentRef.current?.position ?? 0 }

    // 与 changeChapter 的手动换章保持**同一语义**：朗读中换章必须停掉朗读。
    // 否则 ttsData 会重算成新章的 chunk 列表，而正在播的仍是旧章音频 ⇒ 高亮与文本错位。
    // （TTS 自动续章走 autoPlayNextChapterRef，不受影响。）
    if (!autoPlayNextChapterRef.current) {
      stopTts()
    } else if (settings.ttsEngine === 'webSpeech') {
      stopAllEngines()
    }

    // 相邻章若已预加载，同帧把正文填好（多一次空区块渲染都会让补偿量测错）
    const cached = preloadedContentRef.current.get(target.url)
    if (cached !== undefined) {
      setContent(cached)
      setLoadedChapterUrl(target.url)
    }
    setChapterIndex(nextIndex)
  }, [applyScrollSectionContent, currentBook.chapters, scrollSections, scrollStrategy, settings.ttsEngine, stopAllEngines, stopTts])

  /**
   * 窗口平移后的滚动补偿 + 进度校正（在 DOM 更新后、绘制前执行）。
   *
   * 平移会从文档里增删区块 ⇒ 内容整体位移；把锚点区块的视口 top 补回原值即可「视觉不动」。
   * 补偿量与区块高度无关，因此不等新章加载完也能算准。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    if (!scrollShiftAnchorRef.current) return

    // Safari 的原生 scroll anchoring 可能在 React commit 后、layout effect 之后才完成。
    // 连续等待两帧，确保测到最终布局，只补偿浏览器实际留下的残差，避免同一平移被校正两次。
    let rafId: number | null = null
    let secondRafId: number | null = null
    const measureAfterLayout = () => {
      secondRafId = window.requestAnimationFrame(() => {
        secondRafId = null
        const anchor = scrollShiftAnchorRef.current
        if (!anchor) return
        const el = scrollSectionRefs.current.get(anchor.index)
          ?? document.querySelector<HTMLElement>(`.reading-scroll-window > .reading-content[data-chapter-index="${anchor.index}"]`)
        if (!el || !el.isConnected) return
        scrollShiftAnchorRef.current = null
        const delta = scrollCompensation(anchor.top, el.getBoundingClientRect().top)
        if (Math.abs(delta) > 0.5) {
          window.scrollTo({ top: Math.max(0, window.scrollY + delta), behavior: 'auto' })
        }
        // 平移后立刻按新区块校正一次进度，避免 persist 用加载逻辑写入的「章首 0」把进度带偏
        const current = currentRef.current
        if (current) {
          const rect = el.getBoundingClientRect()
          const range = rect.height - window.innerHeight
          current.position = range > 0 ? Math.min(1, Math.max(0, -rect.top / range)) : 0
        }
        lastScrollYRef.current = window.scrollY
      })
    }
    rafId = window.requestAnimationFrame(() => {
      rafId = null
      measureAfterLayout()
    })
    return () => {
      if (rafId !== null) window.cancelAnimationFrame(rafId)
      if (secondRafId !== null) window.cancelAnimationFrame(secondRafId)
    }
  }, [chapterIndex, content, scrollSections, settings.pageMode])

  useEffect(() => {
    if (settings.pageMode !== 'scroll') return
    let rafId: number | null = null
    const onScroll = () => {
      if (rafId !== null) return
      rafId = window.requestAnimationFrame(() => {
        rafId = null
        lastScrollEventAtRef.current = Date.now()
        let current = currentRef.current
        if (!current) return
        const currentY = window.scrollY
        const scrollDelta = currentY - lastScrollYRef.current
        if (scrollDelta > 0) scrollDirectionRef.current = 1
        else if (scrollDelta < 0) scrollDirectionRef.current = -1
        if (currentY > 72 && scrollDelta > 12) {
          setToolbarsVisible(false)
        } else if (scrollDelta < -8) {
          setToolbarsVisible(true)
        }
        lastScrollYRef.current = currentY
        const clientHeight = window.innerHeight
        // 三章窗口下不能用整文档的 scrollHeight：进度必须相对**当前章区块**计算
          const sectionEl = document.querySelector<HTMLElement>(`.reading-scroll-window > .reading-content[data-chapter-index="${current.chapter.index}"]`)
            ?? scrollSectionRefs.current.get(current.chapter.index)
          if (sectionEl?.isConnected) {
          const rect = sectionEl.getBoundingClientRect()
          const range = rect.height - clientHeight
          current.position = range > 0 ? Math.min(1, Math.max(0, -rect.top / range)) : 0
          // 读到本章头部/尾部附近时，把更远的章节也拉进预加载缓存
          if (current.position >= 0.7) preloadNextChapter(current.chapter.index)
          else if (current.position <= 0.3) preloadPrevChapter(current.chapter.index)
          // 视口越过了相邻章开头 ⇒ 换章，实现「一直往下滚就接着读下一章」
          //
          // 参考线按策略选：`keep` 用「视口顶部下方约 1/4 屏」（跨章不动 DOM，可以更早、更准地换章）；
          // `window` 沿用视口中心（换章要平移 DOM，判定必须保守）。详见 [scrollDominantThreshold]。
          const anchorThreshold = scrollDominantThreshold(clientHeight, scrollStrategy === 'keep' ? 'top' : 'centre')
          const rects = [...document.querySelectorAll<HTMLElement>('.reading-scroll-window > .reading-content[data-chapter-index]')]
            .filter(node => node.isConnected)
            .map(node => ({
              index: Number(node.dataset.chapterIndex),
              top: node.getBoundingClientRect().top,
            }))
            .filter(rect => Number.isInteger(rect.index))
          /**
           * 从**几何**派生「读者此刻真正在读哪一章」，它就是当前章的唯一权威。
           *
           * 为什么以几何为准（WebKit 实测 2026-10-04）：恢复进度时 `chapterIndex` 取的是进度里的章，
           * 滚动位置却是按百分比换算的；两者在不同排版/高度估算下会对不上，出现
           * 「记录当前章 = 第 7 章，而视口里显示的正文是第 5 章」这种长期脱节。
           * 脱节之后，凡是以记录章为基准的判定都会自我一致地错下去（此前实测：每步 250px
           * 连滚 10000px 全程不换章；只改 `currentRef`、不改 state 还会把记录章彻底冻死）。
           *
           * 派生规则：取「顶部 ≤ 参考线」里最靠下的那一段；一段都没有时取第一段。
           * 该值是**单调**的（随向下滚动只增不减），所以不需要再用滚动方向做二次守卫。
           */
          let derivedIndex: number | null = current.chapter.index
          if (rects.length > 0) {
            let best: number | null = null
            let bestTop = Number.NEGATIVE_INFINITY
            let nextUp: number | null = null
            let nextUpTop = Number.NEGATIVE_INFINITY
            for (const rect of rects) {
              if (!Number.isFinite(rect.top)) continue
              if (rect.top > nextUpTop) { nextUpTop = rect.top; nextUp = rect.index }
              if (rect.top <= anchorThreshold && rect.top > bestTop) { bestTop = rect.top; best = rect.index }
            }
            // 没有任何段满足参考线条件（视口落在两章之间的空隙、而下一章已在 DOM 里）时，
            // 归属**最靠下的那一段**（= 读者即将读到的下一章）。
            // ⚠️ 不能取「最靠上」的那段：那是视口上方最远的段，会把当前章往回退（实测直接退了两章）。
            derivedIndex = best ?? nextUp
          }
          /**
           * 两条策略的换章判定不同：
           * - `keep`：以**几何派生值**为准（它是单调的，不需要方向守卫；且跨章不动 DOM，多章跨度也安全）。
           * - `window`：保持原行为 —— 视口中心命中 + 方向守卫，只在逐章平移时生效（这条路径要动 DOM，必须保守）。
           */
          const keepStrategy = scrollStrategy === 'keep'
          const geometric = derivedIndex
          /**
           * 收敛步长限制为**一帧最多一章**。
           *
           * 几何派生值有时会离记录值很远（例如打开书时进度里的章与滚动位置换算结果不一致），
           * 一步跳过去就是「一下子跳好几章」——用户报障的「跳到上一章开头」正是这种大跨度回退。
           * 逐帧挪一章则：① 单帧之内永远看不出跳变；② 连续滚动时几帧内自然收敛到正确章节。
           */
          const stepToward = (target: number) =>
            target > current.chapter.index ? current.chapter.index + 1
              : target < current.chapter.index ? current.chapter.index - 1
                : current.chapter.index
          /**
           * 向前收敛随时允许；**向后收敛只允许在用户确实往上滚的时候**。
           *
           * 打开书时「进度里的章」与「按百分比换算出的滚动位置」本来就可能对不上
           * （实测：记录第 4 章、视口显示第 3 章），此时几何会觉得应该往回退。
           * 不加这条守卫，读者一进书就会被逐帧拖回前几章 —— 正是报障的「跳到上一章开头」。
           * 反之读者主动往上滑时几何一定可信（他就在往上看），该退就退。
           */
          const canMoveBackward = scrollDirectionRef.current < 0
          const dominant = geometric === null
            ? null
            : stepToward(geometric) < current.chapter.index && !canMoveBackward
              ? current.chapter.index
              : stepToward(geometric)
          const shouldShift = dominant !== null && dominant !== current.chapter.index &&
            (keepStrategy || isScrollSectionTransitionAllowed(current.chapter.index, dominant, scrollDelta))
          const traceShift = typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1'
          if (traceShift) {
            const store = ((window as unknown as { __scrollShiftDebug?: unknown[] }).__scrollShiftDebug ??= [])
            store.push({
              t: Math.round(performance.now()), current: current.chapter.index, derived: derivedIndex,
              dominant, delta: Math.round(scrollDelta), threshold: Math.round(anchorThreshold), y: currentY,
              allowed: shouldShift,
              // 判定用的几何快照：确认「参考线该落在哪一段」是不是与 DOM 一致
              rects: rects.map(rect => `${rect.index}@${Math.round(rect.top)}`).join(' '),
              refKeys: [...scrollSectionRefs.current.keys()].join(','),
              found: document.querySelectorAll('.reading-scroll-window > .reading-content[data-chapter-index]').length,
            })
            if (store.length > 200) store.splice(0, store.length - 200)
          }
          if (shouldShift && derivedIndex !== null) {
            shiftScrollWindow(derivedIndex)
          }
        } else {
          current.position = scrollPosition(currentY, document.documentElement.scrollHeight, clientHeight)
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
  }, [applyScrollSectionContent, currentBook.chapters, persist, preloadNextChapter, preloadPrevChapter, scrollSections, settings.pageMode, shiftScrollWindow])

  // 进度里的「当前章」必须与**正在显示的章**锁死同步，不能等正文加载完再更新。
  //
  // 事故（实测 2026-10-03）：切章时 `changeChapter` 先保存旧章、再 `setChapterIndex`，
  // 而 `currentRef.current.chapter` 只在正文到达后才在 `applyContent` 里更新；
  // 与此同时翻页/滚动效果是按「正在显示的章」在改 `current.position`。
  // 于是正文还在路上时一旦触发保存（防抖 1.2s、退出阅读器、页面隐藏），
  // 写进库里的就是「**旧章节 + 新位置**」的错配 ⇒ 退出再进入会往前跳好几章。
  // 实测：滑块跳到第 100 章后退出，服务端记的是第 2 章。
  //
  // 位置语义：切到新章时位置归 0（新章从头开始）；正文到达后 `applyContent` 再按需
  // 覆盖成恢复进度。窗口平移那颗路径不受影响 —— 它已经把 current 设成目标章，
  // 这里判断 index 相同即跳过，保留平移要用的位置。
  useEffect(() => {
    if (!chapter) return
    const current = currentRef.current
    if (current?.chapter.index === chapter.index) return
    currentRef.current = { chapter, position: 0 }
  }, [chapter])

  useEffect(() => {
    if (settings.pageMode !== 'paginate') return
    const position = pageCount > 1 ? pageIndex / (pageCount - 1) : 0
    const current = currentRef.current
    if (current && chapter) {
      current.position = position
      if (position >= 0.7) {
        preloadNextChapter(chapter.index)
      } else if (position <= 0.3) {
        preloadPrevChapter(chapter.index)
      }
      if (timerRef.current === null) {
        timerRef.current = window.setTimeout(() => {
          timerRef.current = null
          persist()
        }, 1200)
      }
    }
  }, [chapter, pageCount, pageIndex, persist, preloadNextChapter, preloadPrevChapter, settings.pageMode])

  // Stop TTS on unmount
  useEffect(() => () => {
    stopAllEngines()
  }, [stopAllEngines])

  // Chapter content load effect for auto continuous next chapter
  useEffect(() => {
    if (!content || loading || loadedChapterUrl !== chapter?.url) return
    if (autoPlayNextChapterRef.current) {
      autoPlayNextChapterRef.current = false
      setTtsActive(true)
      playTtsChunkRef.current(0, 'continue')
    }
  }, [content, loading, loadedChapterUrl, chapter?.url])

  // Sleep Timer countdown effect
  useEffect(() => {
    if (sleepTimer === 'off' || sleepTimer === 'chapter' || sleepTimer === 'paragraph') {
      setRemainingSeconds(null)
      return
    }
    const totalSecs = parseInt(sleepTimer, 10) * 60
    setRemainingSeconds(totalSecs)
    const timer = window.setInterval(() => {
      setRemainingSeconds(prev => {
        if (prev === null || prev <= 1) {
          window.clearInterval(timer)
          toast.info(t('reader.sleepTimerEnded', '睡眠定时时间到，已停止朗读'))
          stopTts()
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [sleepTimer, stopTts, t])

  // MediaSession API integration
  useEffect(() => {
    if (!('mediaSession' in navigator) || !ttsActive) return
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: chapter?.title || t('reader.listen', '正文朗读'),
        artist: currentBook.details.author || currentBook.details.name,
        album: bookName,
        artwork: currentBook.details.coverUrl ? [{ src: currentBook.details.coverUrl }] : undefined,
      })
      navigator.mediaSession.playbackState = ttsPlayState === 'playing' ? 'playing' : 'paused'

      navigator.mediaSession.setActionHandler('play', () => resumeTts())
      navigator.mediaSession.setActionHandler('pause', () => pauseTts())
      navigator.mediaSession.setActionHandler('previoustrack', () => handlePrevChunk())
      navigator.mediaSession.setActionHandler('nexttrack', () => handleNextChunk())
    } catch {
      // Ignore mediaSession errors
    }
  }, [ttsActive, ttsPlayState, chapter?.title, bookName, currentBook.details.author, currentBook.details.coverUrl, resumeTts, pauseTts, handlePrevChunk, handleNextChunk])

  // Wake Lock API integration
  useEffect(() => {
    if (!('wakeLock' in navigator)) return
    if (ttsActive && ttsPlayState === 'playing') {
      navigator.wakeLock.request('screen').then(lock => {
        wakeLockRef.current = lock
      }).catch(() => undefined)
    } else {
      wakeLockRef.current?.release().catch(() => undefined)
      wakeLockRef.current = null
    }
    return () => {
      wakeLockRef.current?.release().catch(() => undefined)
      wakeLockRef.current = null
    }
  }, [ttsActive, ttsPlayState])

  // Auto-scroll / highlight follower effect
  useEffect(() => {
    if (!ttsActive || activeParagraphIndex < 0) return

    if (settings.pageMode === 'scroll') {
      const el = document.querySelector(`[data-paragraph-index="${activeParagraphIndex}"]`)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }
    } else if (settings.pageMode === 'paginate') {
      const el = bodyRef.current?.querySelector(`[data-paragraph-index="${activeParagraphIndex}"]`) as HTMLElement | null
      if (el && stride > 0) {
        const targetPage = Math.min(pageCount - 1, Math.max(0, Math.floor(el.offsetLeft / stride)))
        setPageIndex(targetPage)
      }
    }
  }, [activeParagraphIndex, ttsActive, settings.pageMode, stride, pageCount])

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
        if (event.key === 'ArrowLeft') { event.preventDefault(); changeChapter(chapterIndex - 1, 'last') }
        if (event.key === 'ArrowRight') { event.preventDefault(); changeChapter(chapterIndex + 1, 'first') }
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

  useEffect(() => {
    if (!isDraggingSlider) {
      setSliderChapterIndex(chapterIndex)
    }
  }, [chapterIndex, isDraggingSlider])

  const totalChapters = currentBook.chapters.length
  const displayChapterIndex = isDraggingSlider ? sliderChapterIndex : chapterIndex
  const displayChapterTitle = currentBook.chapters[displayChapterIndex]?.title || ''

  const handleSliderChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10)
    if (!isNaN(val)) {
      setSliderChapterIndex(val)
    }
  }

  const handleSliderCommit = () => {
    setIsDraggingSlider(false)
    if (sliderChapterIndex !== chapterIndex && sliderChapterIndex >= 0 && sliderChapterIndex < totalChapters) {
      changeChapter(sliderChapterIndex, 'first')
    }
  }

  const cachePercent = Math.min(100, Math.round((cacheStatus.cached / Math.max(1, cacheStatus.total || currentBook.chapters.length)) * 100))

  return <main className={`reader-workspace theme-${settings.theme} ${toolbarsVisible ? 'toolbars-open' : 'toolbars-hidden'} ${settings.sidebarPinned ? 'sidebar-pinned' : ''}`} style={readerStyle}>
    {/* Floating Header */}
    <header className="reader-header">
      <div className="reader-header-left">
        <IconButton label={t('reader.backToShelf', '返回书架')} icon="arrowLeft" onClick={() => { persist(); stopTts(); onClose() }} />
        <IconButton
          label={t('reader.catalog', '目录')}
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
        <IconButton label={t('reader.switchSource', '切换书源')} icon="sliders" onClick={() => setShowSourceSwitch(true)} />
        <IconButton label={t('reader.settings', '阅读设置')} icon="settings" onClick={() => setActiveDrawer(d => d === 'settings' ? null : 'settings')} />
        <IconButton
          label={!ttsActive ? t('reader.readChapter', '朗读本章') : ttsPlayState === 'playing' ? t('reader.pauseReading', '暂停朗读') : t('reader.resumeReading', '继续朗读')}
          icon={ttsActive && ttsPlayState === 'playing' ? 'pause' : 'volume2'}
          onClick={toggleTts}
        />
      </div>
    </header>

    {/* TOC Drawer (Left) */}
    <aside className={`reader-drawer reader-drawer-left ${activeDrawer === 'toc' ? 'open' : ''} ${settings.sidebarPinned ? 'pinned' : ''}`} aria-label={t('reader.tocDrawer', '目录抽屉')}>
      <header className="drawer-header">
        <div className="drawer-title"><Icon name="list" /><strong>{t('reader.toc', '目录')}</strong><small>{t('reader.totalChapters', { count: currentBook.chapters.length, defaultValue: `共 ${currentBook.chapters.length} 章` })}</small></div>
        <div className="drawer-header-actions">
          <IconButton
            className={`desktop-pin-btn ${settings.sidebarPinned ? 'pinned' : ''}`}
            label={settings.sidebarPinned ? t('reader.unpinSidebar', '取消固定目录') : t('reader.pinSidebar', '固定目录到侧边栏')}
            icon={settings.sidebarPinned ? 'pinOff' : 'pin'}
            onClick={() => onSettingsChange({ ...settings, sidebarPinned: !settings.sidebarPinned })}
          />
          <IconButton
            label={t('reader.closeToc', '关闭目录')}
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
        <input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder={t('reader.searchChapter', '搜索章节')} />
      </div>
      <VirtualChapterList
        chapters={filteredChapters}
        activeChapterIndex={chapterIndex}
        cachedUrlsSet={cachedChapterUrls}
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
                <span>{t('reader.serverCaching', '正在服务端缓存')}</span>
                <strong>{cachePercent}% ({cacheStatus.cached}/{cacheStatus.total || currentBook.chapters.length}{t('reader.cacheCustomUnit', '章')})</strong>
              </div>
              <div className="cache-progress-bar-track">
                <div className="cache-progress-bar-fill" style={{ width: `${cachePercent}%` }} />
              </div>
              <button className="cache-cancel-btn" onClick={() => void handleCancelCache()}>{t('reader.cancelCache', '取消缓存')}</button>
            </div>
          ) : localSyncing ? (
            <div className="drawer-cache-progress">
              <div className="cache-progress-text">
                <span>{t('reader.localDownloading', '正在下载到本设备 (PWA)')}</span>
                <strong>{localSyncProgress.current} / {localSyncProgress.total} {t('reader.cacheCustomUnit', '章')}</strong>
              </div>
              <div className="cache-progress-bar-track">
                <div
                  className="cache-progress-bar-fill"
                  style={{ width: `${Math.min(100, Math.round((localSyncProgress.current / Math.max(1, localSyncProgress.total)) * 100))}%` }}
                />
              </div>
              <button className="cache-cancel-btn" onClick={() => { localSyncAbortRef.current?.abort(); setLocalSyncing(false) }}>{t('reader.cancelLocalDownload', '取消本地下载')}</button>
            </div>
          ) : (
            <div className="drawer-cache-idle">
              {!isLocalBook && (
                <>
                  <div className="cache-range-btn-grid">
                    <button
                      type="button"
                      className="cache-range-btn"
                      title={t('reader.cacheNext50Title', '缓存当前章节后续 50 章')}
                      onClick={() => void handleCacheRange('next50')}
                    >
                      {t('reader.cacheNext50', '后 50 章')}
                    </button>
                    <button
                      type="button"
                      className="cache-range-btn"
                      title={t('reader.cacheNext100Title', '缓存当前章节后续 100 章')}
                      onClick={() => void handleCacheRange('next100')}
                    >
                      {t('reader.cacheNext100', '后 100 章')}
                    </button>
                    <button
                      type="button"
                      className="cache-range-btn"
                      title={t('reader.cacheAllTitle', '缓存全本小说')}
                      onClick={() => void handleCacheRange('all')}
                    >
                      {t('reader.cacheAll', '全本缓存')}
                    </button>
                    <button
                      type="button"
                      className="cache-range-btn"
                      title={t('reader.cacheCustomTitle', '自定义章节范围')}
                      onClick={() => setCustomRangeOpen(prev => !prev)}
                    >
                      {t('reader.cacheCustom', '自定义...')}
                    </button>
                  </div>

                  {customRangeOpen && (
                    <div className="cache-custom-range-box">
                      <div className="range-inputs">
                        <span>{t('reader.cacheCustomFrom', '第')}</span>
                        <input
                          type="number"
                          min={1}
                          max={currentBook.chapters.length}
                          value={customRangeStart}
                          onChange={e => setCustomRangeStart(Number(e.target.value))}
                        />
                        <span>{t('reader.cacheCustomTo', '至')}</span>
                        <input
                          type="number"
                          min={customRangeStart}
                          max={currentBook.chapters.length}
                          value={customRangeEnd}
                          onChange={e => setCustomRangeEnd(Number(e.target.value))}
                        />
                        <span>{t('reader.cacheCustomUnit', '章')}</span>
                      </div>
                      <button
                        type="button"
                        className="primary-button range-start-btn"
                        onClick={() => {
                          setCustomRangeOpen(false)
                          void handleCacheRange('custom', customRangeStart, customRangeEnd)
                        }}
                      >
                        {t('reader.startCache', '开始缓存')}
                      </button>
                    </div>
                  )}
                </>
              )}

              <div className="cache-local-sync-row">
                <button
                  type="button"
                  className="cache-sync-btn"
                  onClick={() => void handleSyncToLocal()}
                  title={t('reader.downloadToDeviceTitle', '将已缓存章节下载到手机或电脑本地，断网脱机可读')}
                >
                  <Icon name="download" />
                  <span>{t('reader.downloadToDevice', '下载到本设备 (离线脱机)')}</span>
                </button>
                <button
                  type="button"
                  className="cache-manage-btn"
                  onClick={() => setOfflineStatsModal(true)}
                  title={t('reader.manageOfflineCache', '管理本设备离线缓存')}
                >
                  <Icon name="sliders" />
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Action Buttons */}
        <div className="drawer-footer-actions">
          <button className="shelf-button switch-source-btn" onClick={() => { setActiveDrawer(null); setShowSourceSwitch(true) }}>
            <Icon name="sliders" />{t('reader.switchSource', '换源')}
          </button>
          <button className="shelf-button" onClick={() => void toggleShelf()}>
            <Icon name={inShelf ? 'check' : 'plus'} />{inShelf ? t('reader.inShelf', '已在书架') : t('reader.addToShelf', '加书架')}
          </button>
        </div>
      </footer>
    </aside>

    {/* Settings Drawer (Right) */}
    <aside className={`reader-drawer reader-drawer-right ${activeDrawer === 'settings' ? 'open' : ''}`} aria-label={t('reader.settingsDrawer', '阅读设置')}>
      <header className="drawer-header">
        <div className="drawer-title"><Icon name="settings" /><strong>{t('reader.settingsDrawer', '阅读设置')}</strong></div>
        <IconButton label={t('reader.closeSettings', '关闭设置')} icon="close" onClick={() => setActiveDrawer(null)} />
      </header>
      <ReaderSettingsControls
        settings={settings}
        onChange={onSettingsChange}
        onOpenReplaceRules={() => setShowReplaceRules(true)}
      />
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
            {/*
              跨章过渡层：分页位移仍由内层 track 负责，本层只播「沿手势方向滑入」。
              `chapterTurn` 非空期间内层 track 抑制过渡（`.is-turning`），
              否则跨章时 `pageIndex` 由「本章最后一页」变 `0` 会动画**倒退**（弹回第一页）。
            */}
            <div
              className={chapterTurnClassName(chapterTurn, chapterTurnDirection)}
              onAnimationEnd={event => {
                // 只认「滑入」动画；`animationName` 用来避开子元素动画冒泡上来的事件
                if (event.animationName === 'reader-turn-in-next' || event.animationName === 'reader-turn-in-prev') {
                  setChapterTurn(null)
                }
              }}
            >
              <div className={`reader-paginated-track ${chapterTurn ? 'is-turning' : ''}`} style={{ transform: `translateX(-${pageIndex * stride}px)` }}>
                <article ref={bodyRef} className={`reader-paginated-column-body font-${settings.font}`} style={{ columnWidth: `${columnWidth}px`, columnGap: `${columnGap}px` }}>
                  <h1>{chapter?.title}</h1>
                  {loading && <ReaderContentSkeleton />}
                  {message && <p className="reader-error">{message}</p>}
                  {paragraphs.map((line, index) => (
                    <p
                      key={index}
                      data-paragraph-index={index}
                      className={`reader-paragraph ${ttsActive && activeParagraphIndex === index ? 'tts-active-paragraph' : ''}`}
                      onClick={() => { if (ttsActive) handleParagraphClick(index) }}
                    >
                      {renderParagraphContent(line, index)}
                    </p>
                  ))}
                </article>
              </div>
            </div>
          </div>
          <footer className="reader-paginated-footer">
            <span>{chapter?.title || ''}</span>
            <span>{pageIndex + 1} / {pageCount}</span>
          </footer>
        </div>
      ) : (
        /*
         * 滚动模式：连续滚动。
         *
         * 窗口由挂载策略决定（`window` = 三章；`keep` = 已读章节驻留），
         * 但渲染形态完全一致：一串 `data-chapter-index` 升序排列的 `.reading-content`。
         * 章节交界处的「线 —— 章节名 —— 线」隔断挂在**每一章的头部**，
         * 由「该章之前是否还有前一段」决定，因此章节在窗口里换位置也不会丢隔断。
         */
        <div className="reading-scroll-window" data-scroll-strategy={scrollStrategy}>
          {scrollSectionsBeforeLive.map(section => (
            <article
              data-chapter-index={section.index}
              key={scrollSectionKey(section.index)}
              className={`reading-content is-scroll-neighbor font-${settings.font}`}
              ref={getScrollSectionRef(section.index)}
              aria-hidden="true"
            >
              {section.index > firstMountedIndex && (
                <div className="reader-chapter-stream-divider" aria-hidden="true">
                  <span className="divider-line" />
                  <span className="divider-badge">{section.title}</span>
                  <span className="divider-line" />
                </div>
              )}
              <h1>{section.title}</h1>
              {!section.ready && <ReaderContentSkeleton />}
              {section.paragraphs.map((line, index) => (
                <p key={index} className="reader-paragraph">{line}</p>
              ))}
            </article>
          ))}
          <article
            data-chapter-index={chapterIndex}
            key={scrollSectionKey(chapterIndex)}
            className={`reading-content font-${settings.font}`}
            onPointerDown={onPointerDown}
            onPointerUp={onPointerUp}
            ref={getScrollSectionRef(chapterIndex)}
          >
            {/*
              两章之间的隔断：线 —— 章节名 —— 线（沿用上游同款外观）。
              只在「本段不是窗口里第一段」时渲染，所以它恰好落在章节交界处。
            */}
            {chapterIndex > firstMountedIndex && (
              <div className="reader-chapter-stream-divider" aria-hidden="true">
                <span className="divider-line" />
                <span className="divider-badge">{chapter?.title}</span>
                <span className="divider-line" />
              </div>
            )}
            <h1>{chapter?.title}</h1>
            {loading && !content && liveScrollParagraphs.length === 0 && <ReaderContentSkeleton />}
            {message && <p className="reader-error">{message}</p>}
            {liveScrollParagraphs.map((line, index) => (
              <p
                key={index}
                data-paragraph-index={index}
                className={`reader-paragraph ${ttsActive && activeParagraphIndex === index ? 'tts-active-paragraph' : ''}`}
                onClick={() => { if (ttsActive) handleParagraphClick(index) }}
              >
                {renderParagraphContent(line, index)}
              </p>
            ))}
          </article>
          {scrollSectionsAfterLive.map(section => (
            <article
              data-chapter-index={section.index}
              key={scrollSectionKey(section.index)}
              className={`reading-content is-scroll-neighbor font-${settings.font}`}
              ref={getScrollSectionRef(section.index)}
              aria-hidden="true"
            >
              {/* 同上：隔断只由章节下标决定，保证同一章在「当前章 ⇄ 邻居」两种身份下结构一致 */}
              {section.index > firstMountedIndex && (
                <div className="reader-chapter-stream-divider" aria-hidden="true">
                  <span className="divider-line" />
                  <span className="divider-badge">{section.title}</span>
                  <span className="divider-line" />
                </div>
              )}
              <h1>{section.title}</h1>
              {!section.ready && <ReaderContentSkeleton />}
              {section.paragraphs.map((line, index) => (
                <p key={index} className="reader-paragraph">{line}</p>
              ))}
            </article>
          ))}
          {/*
            窗口末尾两态（按「窗口里最后一段是第几章」判断）：
            ① 最后一段已经是全书最后一章 ⇒ 「全书完」收尾提示 —— 不论它是中间段还是下一段。
            ② 后面还有章、但下一章正文还没到 ⇒ 「正在加载下一章…」；否则冷缓存时往下滚
               会撞到空白且毫无反馈。
            下一章已经躺在窗口里且不是末章 ⇒ 什么都不显示，继续往下读即可。
          */}
          {scrollBottomIndex >= currentBook.chapters.length - 1 ? (
            <div className="reader-stream-end-notice" role="status">
              <span>{t('reader.lastChapterNotice', '— 全书完 · 已读至最后一章 —')}</span>
            </div>
          ) : scrollNextReady ? null : (
            <div className="reader-stream-bottom-loader" role="status">
              <span className="reader-loading-spinner-ring" />
              <span>{t('reader.loadingNextChapter', '正在加载下一章...')}</span>
            </div>
          )}
        </div>
      )}
      {boundaryMessage && <p className="reader-boundary-message" role="status">{boundaryMessage}</p>}
    </section>

    {/* Floating Bottom Toolbar (Desktop Chapter Bar + Mobile Nav) */}
    <footer className="reader-floating-footer" onClick={e => e.stopPropagation()} onPointerDown={e => e.stopPropagation()}>
      <div className="reader-chapter-control-bar">
        <button
          type="button"
          className="reader-chapter-btn prev"
          disabled={chapterIndex === 0 || loading}
          onClick={() => changeChapter(chapterIndex - 1, 'first')}
          title={t('reader.prevChapter', '上一章')}
        >
          <Icon name="arrowLeft" />
          <span>{t('reader.prevChapter', '上一章')}</span>
        </button>

        <div className="reader-chapter-slider-wrap">
          <div className="reader-chapter-info">
            <span className="chapter-num">{displayChapterIndex + 1} / {Math.max(1, totalChapters)}</span>
            {displayChapterTitle && <span className="chapter-name" title={displayChapterTitle}>{displayChapterTitle}</span>}
          </div>
          <input
            type="range"
            className="reader-chapter-slider"
            min={0}
            max={Math.max(0, totalChapters - 1)}
            value={displayChapterIndex}
            disabled={totalChapters <= 1 || loading}
            onPointerDown={() => setIsDraggingSlider(true)}
            onChange={handleSliderChange}
            onPointerUp={handleSliderCommit}
            onTouchEnd={handleSliderCommit}
            onKeyUp={e => {
              if (e.key === 'Enter' || e.key === ' ') {
                handleSliderCommit()
              }
            }}
            aria-label={t('reader.chapterProgress', '章节进度')}
            style={{
              '--slider-percent': `${totalChapters > 1 ? (displayChapterIndex / (totalChapters - 1)) * 100 : 100}%`
            } as React.CSSProperties}
          />
        </div>

        <button
          type="button"
          className="reader-chapter-btn next"
          disabled={chapterIndex >= totalChapters - 1 || loading}
          onClick={() => changeChapter(chapterIndex + 1, 'first')}
          title={t('reader.nextChapter', '下一章')}
        >
          <span>{t('reader.nextChapter', '下一章')}</span>
          <Icon name="arrowRight" />
        </button>
      </div>

      <nav className="mobile-reader-nav">
        <button type="button" className="subtle-button mobile-nav-btn" onClick={() => setActiveDrawer('toc')}><Icon name="list" /><span>{t('reader.catalog', '目录')}</span></button>
        <button type="button" className="subtle-button mobile-nav-btn" onClick={() => setShowSourceSwitch(true)}><Icon name="sliders" /><span>{t('reader.switchSource', '换源')}</span></button>
        <button type="button" className="subtle-button mobile-nav-btn" onClick={toggleTts}><Icon name={ttsActive && ttsPlayState === 'playing' ? 'pause' : 'volume2'} /><span>{ttsActive ? (ttsPlayState === 'playing' ? t('common.pause', '暂停') : t('common.continue', '继续')) : t('reader.listen', '朗读')}</span></button>
        <button type="button" className="subtle-button mobile-nav-btn" onClick={() => setActiveDrawer('settings')}><span className="aa">Aa</span><span>{t('reader.settings', '设置')}</span></button>
      </nav>
    </footer>

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

    {/* TTS Floating Player Bar */}
    {ttsActive && (
      <TtsPlayerBar
        playState={ttsPlayState}
        currentParagraphIndex={activeParagraphIndex}
        totalParagraphs={paragraphs.length}
        currentChunkIndex={currentChunkIndex}
        totalChunks={ttsData.chunks.length}
        activeSentenceText={activeSentenceText}
        sleepTimer={sleepTimer}
        remainingSeconds={remainingSeconds}
        onTogglePlay={toggleTts}
        onPrevChunk={handlePrevChunk}
        onNextChunk={handleNextChunk}
        onPrevChapter={handlePrevChapterTts}
        onNextChapter={handleNextChapterTts}
        onOpenSettings={() => setShowTtsSettings(true)}
        onClose={stopTts}
      />
    )}

    {/* TTS Settings Modal */}
    {showTtsSettings && (
      <TtsSettingsModal
        settings={settings}
        onChange={onSettingsChange}
        sleepTimer={sleepTimer}
        onSleepTimerChange={setSleepTimer}
        remainingSeconds={remainingSeconds}
        onClose={() => setShowTtsSettings(false)}
      />
    )}

    {/* Replace Rules Modal */}
    {showReplaceRules && (
      <ReplaceRulesModal
        isOpen={showReplaceRules}
        onClose={() => setShowReplaceRules(false)}
        currentBookName={bookName}
        currentSourceUrl={currentBook.details.sourceId}
        onRulesChanged={() => {
          if (chapter) {
            setLoadedChapterUrl('')
            setContent('')
            setLoading(true)
            api.content(currentBook.details.sourceId, chapter.url, currentBook.bookUrl)
              .then(res => {
                setContent(res.content)
                setLoadedChapterUrl(chapter.url)
              })
              .catch(err => {
                setMessage(err.message || t('reader.chapterLoadFailed', '加载章节内容失败'))
              })
              .finally(() => {
                setLoading(false)
              })
          }
        }}
      />
    )}

    {/* Offline Cache Stats Modal */}
    {offlineStatsModal && (
      <OfflineCacheModal
        onClose={() => {
          setOfflineStatsModal(false)
          void syncCacheStatus()
        }}
      />
    )}

    {/* Offline TTS Fallback Confirmation Dialog */}
    {offlineTtsConfirmOpen && (
      <div className="modal-backdrop" onClick={() => setOfflineTtsConfirmOpen(false)}>
        <div
          className="source-login-modal"
          style={{ maxWidth: '420px' }}
          onClick={e => e.stopPropagation()}
          role="dialog"
          aria-modal="true"
          aria-label={t('reader.offlineTtsTitle', '离线语音提示')}
        >
          <header className="source-login-header">
            <div className="source-login-title">
              <span className="source-login-icon"><Icon name="volume2" /></span>
              <div className="source-login-heading">
                <h2>{t('reader.offlineTtsTitle', '离线语音提示')}</h2>
                <small>{t('reader.offlineTtsDesc', '当前设备处于离线状态')}</small>
              </div>
            </div>
            <button type="button" className="subtle-button close-btn" onClick={() => setOfflineTtsConfirmOpen(false)} aria-label={t('common.close', '关闭')}>
              <Icon name="close" />
            </button>
          </header>
          <div className="source-login-body">
            <p style={{ margin: '8px 0', fontSize: '14px', lineHeight: '1.6', color: 'var(--ink)' }}>
              {t('reader.offlineTtsMessage', '当前未连接到互联网，云端 Edge-TTS 朗读不可用。是否切换使用设备本地自带的系统语音 (Web Speech) 进行脱机朗读？')}
            </p>
          </div>
          <footer className="source-login-footer">
            <button type="button" className="subtle-button" onClick={() => setOfflineTtsConfirmOpen(false)}>
              {t('common.cancel', '取消')}
            </button>
            <button
              type="button"
              className="primary-button"
              onClick={() => {
                setOfflineTtsConfirmOpen(false)
                onSettingsChange({ ...settings, ttsEngine: 'webSpeech' })
                setTtsActive(true)
                const startIdx = getInitialTtsChunkIndex()
                playTtsChunk(startIdx)
              }}
            >
              {t('reader.useOfflineVoice', '使用系统离线语音')}
            </button>
          </footer>
        </div>
      </div>
    )}
  </main>
}
