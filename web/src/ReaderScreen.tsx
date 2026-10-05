import { CSSProperties, PointerEvent as ReactPointerEvent, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { api, BookDetails, Chapter, ReadingProgress, SearchResult } from './api'
import { Icon } from './icons'
import { calculatePaginationLayout, chapterTurnClassName, findFirstFullyVisibleParagraphIndex, isAtBottomBoundary, isAtTopBoundary, isInteractiveReaderTarget, isTapGesture, paginateTapZone, scrollTapZone, splitParagraphs, swipeDirection, ViewportBounds } from './readerInteractions'
import type { ChapterTurnDirection, ChapterTurnPhase } from './readerInteractions'
import {
  bufferLoadRange,
  chapterIndexAtViewportTop,
  computeFirstRenderedIndex,
  estimateChapterHeight,
  keepRenderedDistance,
  nextAppendRange,
  planPlaceholders,
  prependDistanceTrigger,
  prependScrollFix,
  previousPrependRange,
  scrollSectionKey,
} from './readerScrollStream'
import type { ScrollStreamSlot } from './readerScrollStream'
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
 * 滚动排障用的渲染采样开关（localStorage: `legado-scroll-debug` = '1'）。
 *
 * 连续滚动的问题都在「某一帧的 DOM 状态」上，而 React 的中间态在组件外看不到。
 * 打开后每次 render 往 `window.__scrollDebug` 记一条，探针脚本据此定位是哪一帧塌的。
 * 默认关闭，判断成本只是一次 localStorage 读取。
 */
const SCROLL_DEBUG_KEY = 'legado-scroll-debug'

/** 进入阅读器时先铺当前章前后多少章（连续流的起点）。 */
const SCROLL_STREAM_INITIAL_BACK = 12
const SCROLL_STREAM_INITIAL_FORWARD = 24
/** 尾部区块进入「视口底部 + 这么多像素」以内时，才追加下一批（背压）。 */
const SCROLL_APPEND_LOOKAHEAD_PX = 4000

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
   * 连续滚动：**一条连续流 + 等高占位**（见 [readerScrollStream]）。
   *
   * 不再有「窗口平移 / 保留策略 / 内核判定 / 锚点补偿 / 几何派生当前章」这些机制 ——
   * 它们存在的原因都是「要在滚动过程中移除视口上方的章节」，而那个操作会让文档高度变小、
   * 触发浏览器钳制滚动位置。改成等高占位后，结构变化只发生在视口上方足够远处，
   * 且高度是精确实测值 ⇒ 文档高度恒定、零补偿。
   */
  const [scrollText, setScrollText] = useState<Map<number, string[]>>(new Map())
  /** 被收成占位的章节（正文不在 DOM 里，但高度精确保留）。 */
  const [scrollPlaceholders, setScrollPlaceholders] = useState<Set<number>>(new Set())
  /** 长流已渲染的区间（闭区间）。只向前后扩，不在中间挖洞。 */
  const [scrollStream, setScrollStream] = useState<{ head: number; tail: number }>(() => {
    // ⚠️ 初始区间必须在这里算出来，**不能**交给一个播种 effect。
    // 踩过的坑：播种 effect 与「当前章正文写入流」的 effect 会在同一批提交里竞争，
    // 播种 effect 里的 `setScrollText(new Map())` 后执行 ⇒ 把刚写进去的当前章正文清掉
    // ⇒ 当前章渲染成骨架、还被占位高度实测记成「真实高度」，恢复对齐随后按这个错误高度
    // 把视口滚到几万像素之外（实测 y=125398、liveTop=-6931）。
    const total = currentBook.chapters.length
    if (total === 0) return { head: 0, tail: -1 }
    return {
      head: Math.max(0, chapterIndex - SCROLL_STREAM_INITIAL_BACK),
      tail: Math.min(total - 1, chapterIndex + SCROLL_STREAM_INITIAL_FORWARD),
    }
  })
  /** 收起正文前实测的各章高度（下标 → px）。 */
  const scrollHeightsRef = useRef<Map<number, number>>(new Map())
  /**
   * 已拿到正文的章节下标（镜像 `scrollText` 的键）。
   *
   * ⚠️ 必须同步读取：占位/收起判定若依赖 React 状态提交时机，会出现「正文刚写进 map、
   * 这一帧却读到旧的键集」的错判；直接把键集维护在写入处最稳。
   */
  const scrollTextKeysRef = useRef<Set<number>>(new Set())
  /**
   * 测量阶段量到的章节高度（下标 → px）。
   *
   * 与 [scrollHeightsRef] 的分工：后者是「收起时冻结的高度」（占位块用它撑住文档），
   * 这里存**每次测量的最新值**，用于给还没量到的章节做高度估算、以及在几何变化后补差。
   */
  const scrollLiveHeightsRef = useRef<Map<number, number>>(new Map())
  /**
   * 几何位移同帧修正所需的两份快照。
   *
   * \`scrollHeightRef\`：上一帧的 \`document.scrollHeight\`（任何「上方变高」都必然体现在它上面）；
   * \`scrollAnchorTopsRef\`：上一帧各区块在**文档中的绝对位置**（判断变高发生在视口上方还是下方）。
   *
   * 用户报障「向上划看上面的章节时总会跳动，本该在上一章末尾却直接跳到上一章开头」：
   * 上方章节变高会把视口内容整体推下去，而浏览器不会替你保住它 —— 必须在同一帧补回。
   */
  const scrollHeightRef = useRef<number | null>(null)
  const scrollAnchorTopsRef = useRef<Map<number, number>>(new Map())
  /** 流头部下标（用于「前插后同帧修正滚动位置」时判断上方是否真的插入了内容）。 */
  const streamHeadRef = useRef(0)
  /** 标记「本次换章来自连续流滚动」：加载 effect 不要清空正文、不要回章首。 */
  const streamAdvanceRef = useRef(false)
  /** 背压信号：尾部区块接近视口时置位，驱动「按批追加」的 effect（由滚动处理写入）。 */
  const [scrollAppendSignal, setScrollAppendSignal] = useState(true)
  /** 背压信号的最新值（滚动处理里同步读，避免闭包拿到旧值而误判「从未放行」）。 */
  const scrollAppendSignalRef = useRef(true)
  /** 当前流尾部下标（滚动处理里读，避免把 scrollStream 塞进事件回调依赖）。 */
  const scrollStreamTailRef = useRef(0)
  /** 追加批次里出现「取正文失败」时的重试计数（避免上游永久失败时无限重试）。 */
  const scrollRetryRef = useRef(0)
  /** 各章节区块的 DOM 节点（判定当前章、量高度）。 */
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
   * 连续流的渲染清单：从 `head` 到 `tail` 逐章列出「渲染正文」还是「等高占位」。
   *
   * - 已渲染且未收起的章节 → 用 `scrollText` 里的段落渲染（含当前章）；
   * - 收起 / 从未加载的中间章节 → 等高占位。
   *
   * 当前章一定在 `scrollText` 里（切章时同步写入），所以它永远不会是占位。
   */
  const scrollEntries = useMemo(() => {
    const total = currentBook.chapters.length
    if (total === 0 || scrollStream.tail < scrollStream.head) return []
    const entries: Array<{
      index: number
      title: string
      paragraphs: string[] | null
      height: number
      needsDivider: boolean
      isCurrent: boolean
    }> = []
    for (let index = scrollStream.head; index <= scrollStream.tail && index < total; index++) {
      const chapter = currentBook.chapters[index]
      if (!chapter) continue
      const collapsed = scrollPlaceholders.has(index)
      const text = collapsed ? undefined : scrollText.get(index)
      entries.push({
        index,
        title: chapter.title,
        paragraphs: text ?? null,
        /*
         * ⚠️ 这里只认**真实量到的高度**，绝不能用估算值兜底。
         *
         * 渲染分支用 `height <= 0` 判断「这一章还没量到高度 ⇒ 必须渲染正常区块（骨架）才有机会被加载和测量」。
         * 一旦用估算值把 height 填成正数，未加载的章节会被当成「已量到」而收成占位块 ——
         * 占位块不渲染正文也不会被测量 ⇒ 它们永远不加载，读者看到「往上往下都不加载，只剩空白」
         *（实测复现：全文档只剩当前章一个区块）。
         * 几何稳定性由「测量后的同帧补差」负责，不靠给未测章节编高度。
         */
        height: scrollLiveHeightsRef.current.get(index)
          ?? scrollHeightsRef.current.get(index)
          ?? 0,
        needsDivider: index > scrollStream.head,
        isCurrent: index === chapterIndex,
      })
    }
    return entries
  }, [scrollStream, scrollText, scrollPlaceholders, currentBook.chapters, chapterIndex])

  /** 当前章在流里的段落（TTS / 高亮 / 点选的唯一权威）。 */
  const liveScrollParagraphs = useMemo(
    () => scrollText.get(chapterIndex) ?? paragraphs,
    [scrollText, chapterIndex, paragraphs],
  )

  /**
   * 流里最靠下的一章是否**已经加载完成**（正文在手里，或已收成占位块）。
   *
   * 与 `scrollTailReady` 的区别：占位也算「完成」—— 占位块撑住了精确高度，
   * 是设计内的稳定状态，不该继续显示「正在加载下一章…」。
   */
  const scrollTailLoaded = useMemo(() => {
    const tail = scrollStream.tail
    if (tail < 0) return false
    return scrollText.has(tail) || scrollPlaceholders.has(tail)
  }, [scrollStream.tail, scrollText, scrollPlaceholders])

  /** 流尾部下标同步给 ref（供滚动处理读取，避免把 scrollStream 塞进事件回调依赖）。 */
  useEffect(() => {
    scrollStreamTailRef.current = scrollStream.tail
  }, [scrollStream.tail])

  /** 背压信号同步给 ref（滚动处理里要同步判断「是否已放行」）。 */
  useEffect(() => {
    scrollAppendSignalRef.current = scrollAppendSignal
  }, [scrollAppendSignal])

  // 排障采样（默认关闭）：把每次 render 的关键状态记进 window.__scrollDebug
  useEffect(() => {
    if (typeof localStorage === 'undefined' || localStorage.getItem(SCROLL_DEBUG_KEY) !== '1') return
    const store = ((window as unknown as { __scrollDebug?: unknown[] }).__scrollDebug ??= [])
    const liveEl = scrollSectionRefs.current.get(chapterIndex)
    store.push({
      t: Math.round(performance.now()),
      chapterIndex,
      currentRefIndex: currentRef.current?.chapter.index ?? null,
      contentLen: content.length,
      loading,
      msg: message,
      loadedUrlTail: loadedChapterUrl.slice(-24),
      isLoaded: isCurrentChapterLoaded,
      paraCount: paragraphs.length,
      stream: `${scrollStream.head}..${scrollStream.tail}`,
      textCount: scrollText.size,
      /** 排障：正文表里每章的段落数与前几章首句长度（判断「200 但内容为空」还是「写进去又被清掉」）。 */
      textPairs: [...scrollText.entries()].slice(0, 24).map(([i, ps]) => `${i}:${ps.length}:${ps[0]?.length ?? 0}`).join(' '),
      placeholderCount: scrollPlaceholders.size,
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
  /**
   * 把某一章的正文同时写入「当前章正文」状态（供 TTS / 进度 / 标题使用）。
   *
   * 连续流里正文有两个消费者：流里的章节区块（按 index 取 `scrollText`）与
   * 当前章权威状态（`content`/`loadedChapterUrl`）。切章时两者必须一起更新，
   * 否则会出现「`chapterIndex` 已变、`content` 还是旧的」那一帧 ——
   * 实测那一帧会把当前章区块渲染成骨架、高度塌约 8000px，浏览器随即钳制 `scrollY`。
   */
  const applyLiveChapterText = useCallback((index: number, text: string): boolean => {
    const target = currentBook.chapters[index]
    if (!target) return false
    currentRef.current = { chapter: target, position: 0 }
    flushSync(() => {
      setContent(text)
      setLoadedChapterUrl(target.url)
    })
    return true
  }, [currentBook.chapters, setContent, setLoadedChapterUrl])

  /**
   * 换书时重置流状态（同一本书内不动）。
   *
   * 初始区间由 `scrollStream` 的 state 初始化函数算出，这里只处理「换了另一本书」的情况：
   * 清掉上一本的正文/占位/高度缓存，并把流重新起在当前章附近。
   */
  const streamBookRef = useRef(currentBook.bookUrl)
  useEffect(() => {
    if (settings.pageMode !== 'scroll') return
    const total = currentBook.chapters.length
    if (total === 0) return
    if (streamBookRef.current === currentBook.bookUrl) return
    streamBookRef.current = currentBook.bookUrl
    const head = Math.max(0, chapterIndex - SCROLL_STREAM_INITIAL_BACK)
    const tail = Math.min(total - 1, chapterIndex + SCROLL_STREAM_INITIAL_FORWARD)
    setScrollStream({ head, tail })
    streamHeadRef.current = head
    setScrollText(new Map())
    scrollTextKeysRef.current = new Set()
    setScrollPlaceholders(new Set())
    scrollHeightsRef.current = new Map()
  }, [chapterIndex, currentBook.bookUrl, currentBook.chapters.length, settings.pageMode])

  /**
   * 按批向下追加章节（**只追加，绝不改动上方结构**）。
   *
   * 这是连续流模型的核心：坐标以已经渲染出来的内容为准，读到接近底部就把后面的章节接上，
   * 而不是靠窗口平移去换章。
   *
   * ⚠️ 必须有**背压**：只在「尾部区块已经接近视口」时才追加下一批，判据由滚动处理给出
   *（`scrollAppendSignal`）。否则会被下一批触发的重渲染再次唤醒，一秒内把整本书 700+ 章
   * 全甩进 DOM（实测：文档高度从 24 万涨到 269 万像素、段数 241，滚动条完全失控）。
   */
  useEffect(() => {
    const node = typeof window === 'undefined' ? null : window
    if (node && typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
      const store = ((node as unknown as { __appendDebug?: unknown[] }).__appendDebug ??= [])
      store.push({ kind: 'effect-run', t: Math.round(performance.now()),
        signal: scrollAppendSignal, tail: scrollStream.tail,
        total: currentBook.chapters.length, mode: settings.pageMode })
      if (store.length > 80) store.splice(0, store.length - 80)
    }
    if (settings.pageMode !== 'scroll') return
    const total = currentBook.chapters.length
    if (total === 0) return
    if (!scrollAppendSignal) return
    const range = nextAppendRange(scrollStream.tail, total)
    if (!range) return

    /**
     * ⚠️ 每拿到一章就立即写入 `scrollText`，**不做整批提交**。
     *
     * 踩过的坑：原先是「按顺序 await 12 章，最后一起提交」，而 effect 的清理函数会把
     * `cancelled` 置真 —— 只要这批还在跑的时候发生一次重渲染（`tail` 变化、测量触发等），
     * 整个批次就被丢弃，正文永远不落库。表现就是：接口全部 200，但页面上没有任何正文
     *（实测：45 次正文请求全成功，`scrollText` 里却始终只有当前章一章）。
     */
    let cancelled = false
    const run = async () => {
      const transient: number[] = []
      for (const index of Array.from({ length: range.to - range.from + 1 }, (_, i) => range.from + i)) {
        const text = await loadChapterText(index)
        // ⚠️ 这里**不能**因为「已取消」就丢弃拿到的正文：每次重渲染都会换一次批次，
        // 丢掉等于白请求（实测：接口全 200 但页面上没有正文）。
        if (text === null) {
          if (cancelled) return
          // 取失败不能当成「这一章是空的」：既不给空正文，也不越过它推进 tail（等重试）
          transient.push(index)
          continue
        }
        const paragraphs = splitParagraphs(text)
        scrollTextKeysRef.current.add(index)
        setScrollText(prev => {
          if (prev.has(index)) return prev
          const next = new Map(prev)
          next.set(index, paragraphs)
          return next
        })
      }
      if (cancelled) return
      if (transient.length > 0 && transient.length === range.to - range.from + 1) {
        // 整批都失败：退避重试（上限几次，避免上游永久失败时无限重试）
        if (scrollRetryRef.current < 4) {
          scrollRetryRef.current += 1
          window.setTimeout(() => setScrollAppendSignal(true), 700)
          return
        }
      }
      scrollRetryRef.current = 0
      /*
       * 只推进到「连续成功」的那一段尾部：中间有失败就不越过它，等重试。
       */
      let newTail = range.to
      if (transient.length > 0) newTail = Math.min(...transient) - 1
      if (newTail >= range.from) {
        setScrollStream(prev => ({ head: Math.min(prev.head, range.from), tail: Math.max(prev.tail, newTail) }))
      }
      // 排障采样：把这一批的失败章数与具体下标记下来
      if (typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
        const store = ((window as unknown as { __appendDebug?: unknown[] }).__appendDebug ??= [])
        store.push({ t: Math.round(performance.now()), range: `${range.from}..${range.to}`,
          ok: range.to - range.from + 1 - transient.length, failed: transient.length,
          failedIdx: transient.slice(0, 8),
          tail: newTail, y: Math.round(window.scrollY), sh: document.documentElement.scrollHeight })
        if (store.length > 60) store.splice(0, store.length - 60)
      }
      /**
       * ⚠️ 追加完一批必须**立刻把背压信号置回 false**。
       *
       * 否则信号会一直是 true，而下一次追加更新 `scrollStream.tail` 会让这个 effect 重跑，
       * 于是又追加一批 —— 级联下去一秒内把整本书 700+ 章全甩进 DOM
       *（实测：文档高度从 24 万涨到 331 万像素、段数 385）。
       * 重新放行只能由滚动处理里的哨兵判定（尾部区块接近视口）给出。
       */
      setScrollAppendSignal(false)
    }
    void run()
    return () => { cancelled = true }
  }, [currentBook.chapters.length, loadChapterText, scrollAppendSignal, scrollStream.tail, settings.pageMode])

  /**
   * 当前章正文写入流 + **上下各 2 章预载**。
   *
   * 用户定调：「章节加载时不要只加载看的这一章节，上下加载 2 章」。
   * 为什么这样做能治跳动：正文章节在**跨越章界之前**就已经在手里，
   * 而不是「读到章界那一刻才开始加载」—— 后者会出现空骨架帧，紧接着内容到位、
   * 文档位移，观感就是跳。
   *
   * 顺序很重要：**先写当前章**（立刻可读），再补邻居。
   */
  useEffect(() => {
    if (settings.pageMode !== 'scroll') return
    if (!isCurrentChapterLoaded || !content) return
    const liveParagraphs = splitParagraphs(content)
    setScrollText(prev => {
      const existing = prev.get(chapterIndex)
      if (existing && existing.length === liveParagraphs.length && existing[0] === liveParagraphs[0]) return prev
      const next = new Map(prev)
      next.set(chapterIndex, liveParagraphs)
      scrollTextKeysRef.current.add(chapterIndex)
      return next
    })
    setScrollPlaceholders(prev => {
      if (!prev.has(chapterIndex)) return prev
      const next = new Set(prev)
      next.delete(chapterIndex)
      return next
    })

    // 邻居预载：失败静默（下一次章界/滚动还会再试），绝不因为邻居失败影响当前章
    const buffer = bufferLoadRange(chapterIndex, currentBook.chapters.length)
    if (!buffer) return
    let cancelled = false
    const run = async () => {
      for (let index = buffer.from; index <= buffer.to; index++) {
        if (index === chapterIndex) continue
        if (scrollTextKeysRef.current.has(index)) continue
        const text = await loadChapterText(index)
        if (text === null) continue
        const paragraphs = splitParagraphs(text)
        scrollTextKeysRef.current.add(index)
        setScrollText(prev => {
          if (prev.has(index)) return prev
          const next = new Map(prev)
          next.set(index, paragraphs)
          return next
        })
        // 把预载到的章节纳入已渲染区间（否则它们不在 head..tail 内、不会被渲染）
        setScrollStream(prev => ({
          head: Math.min(prev.head, index),
          tail: Math.max(prev.tail, index),
        }))
      }
      if (cancelled) return
    }
    void run()
    return () => { cancelled = true }
  }, [chapterIndex, content, currentBook.chapters.length, isCurrentChapterLoaded, loadChapterText, settings.pageMode])

  /**
   * 等高占位：把视口上方足够远的章节正文收成「精确高度」的占位块。
   *
   * 与旧的回收机制本质不同：
   * - 旧机制**移除**章节 ⇒ 文档变矮 ⇒ 浏览器钳制滚动位置 ⇒ 必须补偿 + 重试；
   * - 现在只是把正文换成同高度的占位块（高度取收起前实测值）⇒ `scrollHeight` 恒定，
   *   既不会被钳制、也不需要任何补偿。
   *
   * 结构变化只发生在视口上方 `keepRenderedDistance` 之外，因此永远不会出现在屏幕里。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    if (scrollStream.tail < scrollStream.head) return

    const viewportHeight = window.innerHeight
    const keepDistance = keepRenderedDistance(viewportHeight)
    const slots: Array<{ index: number; top: number; height: number }> = []
    for (let index = scrollStream.head; index <= scrollStream.tail; index++) {
      const el = scrollSectionRefs.current.get(index)
      if (!el) continue
      const rect = el.getBoundingClientRect()
      slots.push({ index, top: rect.top, height: rect.height })
      /**
       * ⚠️ 只给**有正文**的章节记高度。
       *
       * 骨架态区块的高度不是这一章的最终高度（骨架只有一屏左右，正文有几千像素），
       * 记进去会让「只收起已量高度的章节」这道守卫失效 —— 章节被按骨架高度收成占位，
       * 文档里就出现整块空洞（实测：`0` 后面直接跳到 `25`，中间 24 章、约 13 万像素凭空消失）。
       */
      const hasText = scrollText.has(index) || index === chapterIndex
      if (hasText && !scrollPlaceholders.has(index)) scrollHeightsRef.current.set(index, rect.height)
    }
    const firstRendered = computeFirstRenderedIndex(slots, keepDistance)
    if (!Number.isFinite(firstRendered)) return

    const modes: ScrollStreamSlot[] = slots.map(slot => ({
      index: slot.index,
      mode: scrollPlaceholders.has(slot.index) ? 'placeholder' : 'text',
      height: slot.height,
    }))
    const plan = planPlaceholders(modes, firstRendered, new Set(scrollHeightsRef.current.keys()))
    /**
     * 换回正文需要正文在手里。
     *
     * ⚠️ 这里**优先用同步可得的正文**（`scrollText` → 预加载缓存），而不是「拿不到就继续占位」：
     * 会话内读过的章节都在预加载缓存里，向上滚回时同一帧就能把正文装回去，
     * 占位块与正文块高度一致 ⇒ 视觉零位移。只有真的从没加载过的章节才继续当占位。
     */
    const restore = new Map<number, string[]>()
    for (const index of plan.toText) {
      if (scrollText.has(index)) continue
      const chapter = currentBook.chapters[index]
      if (!chapter) continue
      const cached = preloadedContentRef.current.get(chapter.url)
      if (cached === undefined) continue
      restore.set(index, splitParagraphs(cached))
    }
    const backToText = plan.toText.filter(index => scrollText.has(index) || restore.has(index))
    if (plan.toPlaceholder.length === 0 && backToText.length === 0) return

    if (restore.size > 0) {
      setScrollText(prev => {
        const next = new Map(prev)
        for (const [index, paragraphs] of restore) {
          next.set(index, paragraphs)
          scrollTextKeysRef.current.add(index)
        }
        return next
      })
    }

    setScrollPlaceholders(prev => {
      const next = new Set(prev)
      for (const index of plan.toPlaceholder) next.add(index)
      for (const index of backToText) { next.delete(index); scrollTextKeysRef.current.delete(index) }
      if (next.size === prev.size) {
        let same = true
        for (const index of next) if (!prev.has(index)) { same = false; break }
        if (same) return prev
      }
      return next
    })
    if (plan.toPlaceholder.length > 0) {
      setScrollText(prev => {
        const next = new Map(prev)
        for (const index of plan.toPlaceholder) {
          // 当前章永不收起：TTS、进度、标题都依赖它的正文
          if (index === chapterIndex) continue
          next.delete(index)
          scrollTextKeysRef.current.delete(index)
        }
        return next.size === prev.size ? prev : next
      })
      if (typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
        const store = ((window as unknown as { __trimDebug?: unknown[] }).__trimDebug ??= [])
        store.push({ kind: 'virtualize', toPlaceholder: plan.toPlaceholder.length,
          toText: plan.toText.length, firstRendered: Math.round(firstRendered),
          stream: `${scrollStream.head}..${scrollStream.tail}`,
          heights: scrollHeightsRef.current.size, y: Math.round(window.scrollY), t: Math.round(performance.now()) })
        if (store.length > 60) store.splice(0, store.length - 60)
      }
    }
  }, [scrollStream, scrollText, scrollPlaceholders, chapterIndex, settings.pageMode])

  /**
   * 高度测量 + **几何位移的同帧修正**。
   *
   * 每一步做三件事：
   *   ① 在 DOM 更新**之前**记下「锚点」：scrollY + 各区块在文档中的绝对位置；
   *   ② 量一遍所有已渲染区块的高度，写进 `scrollLiveHeightsRef`（一个像素都没变时直接返回）；
   *   ③ 如果高度变化让某个锚点区块的文档位置移动了（上面某章变高/变矮），
   *      在**同一帧**把 `scrollY` 补回差值 —— 你正看着的那一行纹丝不动。
   *
   * 为什么必须有 ③（用户报障：「向上划看上面的章节时总会跳动，本该在上一章末尾却直接跳到上一章开头」）：
   * 上方那些「还没量过高度」的章节起初只能占位估算，一旦被量到真实高度（约 8500px/章），
   * 文档会在这一帧整体变长几千像素，而浏览器不会替你保住视口内容 —— 必须自己补。
   * 等所有上方章节都量过之后，几何就稳定了，这个补偿自然变成 0。
   */
  /**
   * 几何位移的同帧修正（**基于文档高度差**，必然捕获任何上方几何变化）。
   *
   * 用户报障的形态（双视角取证确认）：向上划时**没有任何 scrollTo 调用**，
   * 但文档高度连续暴涨（实测 178327 → 203019 → 211992 → 219857 → 227919，约 5 万像素），
   * 而 `scrollY` 只微微反向 ⇒ 「上方章节变高把内容整体推下去，却没人补偿」，
   * 读者看到的就是「本该在上一章末尾，却跳到上一章开头」。
   *
   * 为什么用**文档高度差**而不是「锚点区块位移」：上方章节一旦被收成占位块就不再被测量，
   * 它的高度变化会绕过纯锚点方案，而 `scrollHeight` 的变化必然包含它。
   *
   * 补偿方向：只有确认是**视口上方在长高**（与视口相交的锚点区块在文档里的位置下移了）
   * 才把 `scrollY` 加上该位移；视口下方长高不需要补偿。两条一起看，才不会把读者推走。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    if (scrollStream.tail < scrollStream.head) return

    const height = document.documentElement.scrollHeight
    const previous = scrollHeightRef.current
    const tops = new Map<number, number>()
    for (const [index, el] of scrollSectionRefs.current) {
      if (!el.isConnected) continue
      tops.set(index, el.getBoundingClientRect().top + window.scrollY)
    }

    if (previous !== null && height !== previous && height > previous) {
      // 找与视口相交的锚点区块：它在文档里的位置下移了多少 = 上方长高了多少
      for (const [index, docTopBefore] of scrollAnchorTopsRef.current) {
        const docTopAfter = tops.get(index)
        if (docTopAfter === undefined) continue
        const rectTop = docTopAfter - window.scrollY
        if (rectTop + 160 < 0 || rectTop > window.innerHeight) continue
        const delta = docTopAfter - docTopBefore
        if (delta === 0) continue
        window.scrollTo({ top: Math.max(0, window.scrollY + delta), behavior: 'auto' })
        lastScrollYRef.current = window.scrollY
        if (typeof localStorage !== 'undefined' && localStorage.getItem(SCROLL_DEBUG_KEY) === '1') {
          const store = ((window as unknown as { __anchorFixDebug?: unknown[] }).__anchorFixDebug ??= [])
          store.push({ t: Math.round(performance.now()), index, delta: Math.round(delta),
            growth: height - previous, y: Math.round(window.scrollY), sh: height })
          if (store.length > 80) store.splice(0, store.length - 80)
        }
        break
      }
    }

    scrollHeightRef.current = height
    scrollAnchorTopsRef.current = tops
  }, [scrollEntries, scrollText, scrollPlaceholders, chapterIndex, settings.pageMode])

  /**
   * 文档高度**只增不减**的兜底。
   *
   * 为什么必须有：浏览器在文档变矮时会**钳制** `scrollY`（把读者往回推），
   * 而且这个钳制发生在引擎内部、发生在任何 layout effect 之前 —— 应用层补偿来不及。
   * 双视角取证实测到它的签名：向上划时出现 29 次「scrollY 变大」且伴随文档高度突变，
   * 而应用侧 `scrollTo/scrollBy` 调用次数为 **0**（说明是引擎自己改的）。
   *
   * 做法：给滚动容器一个「历史最大高度」的下限。某帧内容变矮时容器不跟着变矮，
   * 于是不触发钳制；下一帧真实内容补上后自然恢复。多出来的空白在**视口下方**，读者看不到。
   */
  const [scrollStreamMinHeight, setScrollStreamMinHeight] = useState(0)
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    const height = document.querySelector<HTMLElement>('.reading-scroll-window')?.scrollHeight ?? 0
    if (height <= scrollStreamMinHeight) return
    setScrollStreamMinHeight(height)
  }, [scrollEntries, settings.pageMode, scrollStreamMinHeight])

  /**
   * 向上补齐：从目录直接跳到很后面的章节时，上方章节从未加载过。
   *
   * 只在「已经贴到流的顶部」时按批前插；前插会让文档变高（不是变矮），
   * 因此永远不会触发浏览器的滚动位置钳制 —— 由下面的同帧修正补回位移即可。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    if (scrollStream.head <= 0) return
    const firstEl = scrollSectionRefs.current.get(scrollStream.head)
    if (!firstEl) return
    if (firstEl.getBoundingClientRect().top > prependDistanceTrigger(window.innerHeight)) return

    const range = previousPrependRange(scrollStream.head)
    if (!range) return
    let cancelled = false
    const run = async () => {
      for (let index = range.from; index <= range.to; index++) {
        const text = await loadChapterText(index)
        // 同「按批追加」：拿到正文就立即写入，不因重渲染取消而丢弃
        if (text === null) continue
        const paragraphs = splitParagraphs(text)
        scrollTextKeysRef.current.add(index)
        setScrollText(prev => {
          if (prev.has(index)) return prev
          const next = new Map(prev)
          next.set(index, paragraphs)
          return next
        })
      }
      if (cancelled) return
      setScrollStream(prev => ({ head: Math.min(prev.head, range.from), tail: Math.max(prev.tail, range.to) }))
    }
    void run()
    return () => { cancelled = true }
  }, [currentBook.chapters.length, loadChapterText, scrollStream, settings.pageMode])

  /**
   * 前插后的同帧滚动修正：把插入的高度补回 `scrollY`，视觉位置纹丝不动。
   *
   * 只在「流头部下标变小」时执行（真的插入了新章节）。
   */
  useLayoutEffect(() => {
    if (settings.pageMode !== 'scroll') return
    const prevHead = streamHeadRef.current
    const head = scrollStream.head
    if (head >= prevHead) {
      streamHeadRef.current = head
      return
    }
    let inserted = 0
    for (let index = head; index < prevHead; index++) {
      const el = scrollSectionRefs.current.get(index)
      if (!el) continue
      inserted += el.getBoundingClientRect().height
    }
    streamHeadRef.current = head
    if (inserted <= 0) return
    window.scrollTo({ top: window.scrollY + prependScrollFix(inserted), behavior: 'auto' })
    lastScrollYRef.current = window.scrollY
  }, [scrollStream.head, settings.pageMode])


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
     * 本次换章是否来自**连续流滚动**（而不是目录/滑块跳章）。
     *
     * 连续流里换章只是「视口滚到了下一章」，正文早就在文档里 ——
     * 此时必须**保留**现有正文、也不能把滚动位置重置回章首，否则会闪白并跳位。
     * 标志在此消费一次。
     */
    const seamlessShift = streamAdvanceRef.current
    streamAdvanceRef.current = false
    /**
     * 已经把正文拿在手里的章节**绝不能走「清空 + loading」这条路**。
     *
     * 事故（WebKit 实测）：换章时先清空正文会让当前章区块高度从约 8000px 掉到几百像素，
     * 文档高度同步塌陷，浏览器把 `scrollY` 钳到缩小后的高度上（实测一次性位移 −711px）。
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
      /**
       * 连续流里「能否开始对齐」只有一个条件：**目标章的区块已经在 DOM 里**。
       *
       * 旧实现还要判「窗口是否铺齐」（按首/末章动态算期望段数），因为那时的窗口只有 3 段、
       * 段数不对就意味着落点不可信。现在是一条连续流、每章一块，没有「铺齐」这个概念。
       */
      const liveRendered = Boolean(scrollSectionRefs.current.get(chapterIndex))
        || Boolean(document.querySelector(`.reading-scroll-window > [data-chapter-index="${chapterIndex}"]`))
      const complete = liveRendered
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
    // chapterIndex / scrollStream 入依赖：跳章或流铺开之后都要按**新章区块**重新定位
  }, [chapterIndex, content, loading, scrollStream, settings.pageMode])

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
  /**
   * 连续流里「当前章」自然演进到 `nextIndex`。
   *
   * 与旧的窗口平移最大的区别：**什么都不用动**。
   * 那一章的正文早就在文档里，视口只是滚到了它那里；这里只更新权威状态
   *（进度、标题、TTS 的当前章）。既没有 DOM 结构变化，也没有滚动补偿 ——
   * 因此不存在「章界那一帧」的任何风险。
   */
  const advanceStreamChapter = useCallback((nextIndex: number) => {
    if (nextIndex < 0 || nextIndex >= currentBook.chapters.length) return
    const previous = currentRef.current?.chapter
    if (previous && nextIndex === previous.index) return
    const target = currentBook.chapters[nextIndex]
    if (!target) return
    // 标记「这次换章来自滚动」：加载 effect 里不要清空正文/不要回章首
    streamAdvanceRef.current = true
    currentRef.current = { chapter: target, position: currentRef.current?.position ?? 0 }
    // 与手动换章保持同一语义：朗读中换章必须停掉朗读（否则高亮与文本错位）
    if (!autoPlayNextChapterRef.current) {
      stopTts()
    } else if (settings.ttsEngine === 'webSpeech') {
      stopAllEngines()
    }
    setChapterIndex(nextIndex)
  }, [currentBook.chapters, settings.ttsEngine, stopAllEngines, stopTts])

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

        // 正文区间：流里已经渲染出来的章节区块
        const slots = [...document.querySelectorAll<HTMLElement>('.reading-scroll-window > .reading-content[data-chapter-index]')]
          .filter(node => node.isConnected)
          .map(node => ({
            index: Number(node.dataset.chapterIndex),
            top: node.getBoundingClientRect().top,
          }))
          .filter(slot => Number.isInteger(slot.index))

        const sectionEl = slots.length > 0
          ? (scrollSectionRefs.current.get(current.chapter.index)
            ?? document.querySelector<HTMLElement>(`.reading-scroll-window > .reading-content[data-chapter-index="${current.chapter.index}"]`))
          : null
        if (sectionEl?.isConnected) {
          const rect = sectionEl.getBoundingClientRect()
          const range = rect.height - clientHeight
          current.position = range > 0 ? Math.min(1, Math.max(0, -rect.top / range)) : 0
          if (current.position >= 0.7) preloadNextChapter(current.chapter.index)
          else if (current.position <= 0.3) preloadPrevChapter(current.chapter.index)
        } else {
          current.position = scrollPosition(currentY, document.documentElement.scrollHeight, clientHeight)
        }

        /**
         * 当前章 = **视口顶部所在的那一章**。
         *
         * 连续流里这个判定天然正确（内容就在文档里、坐标就是文档坐标），
         * 不需要旧实现那套「几何派生 + 单帧最多收敛一章」的收敛逻辑 ——
         * 那套东西存在的原因是窗口里只有 3 段、视口可能与记录章差好几章。
         */
        const viewportTopThreshold = Math.min(clientHeight * 0.25, 240)
        const atTop = chapterIndexAtViewportTop(slots, viewportTopThreshold)
        if (atTop !== null && atTop !== current.chapter.index) {
          advanceStreamChapter(atTop)
        }

        /**
         * 背压哨兵：只有「尾部区块已经接近视口」才允许追加下一批（避免级联把整本书甩进 DOM）。
         *
         * 判据用**文档绝对位置**（`rect.top + scrollY`），因为这里拿到的是滚动事件的最新 scrollY。
         * 每次滚动都会重新评估 ⇒ 信号一旦被关闭，读者继续往下滚就会重新打开，
         * 不会出现「关了就再也没人打开」的死锁（实测踩过：关掉之后往上往下都不再加载、只剩空白）。
         */
        const tailEl = scrollSectionRefs.current.get(scrollStreamTailRef.current)
        if (tailEl) {
          const tailDocTop = tailEl.getBoundingClientRect().top + window.scrollY
          const nearTail = tailDocTop < window.scrollY + clientHeight * 2 + SCROLL_APPEND_LOOKAHEAD_PX
          setScrollAppendSignal(prev => (prev === nearTail ? prev : nearTail))
        } else {
          // 尾部区块压根不在 DOM 里（流还没铺开）⇒ 必须放行，否则首次加载就被卡死
          setScrollAppendSignal(true)
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
  }, [advanceStreamChapter, persist, preloadNextChapter, preloadPrevChapter, settings.pageMode])

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
         * 滚动模式：**一条连续流**（对标 X 时间线的滚动观感）。
         *
         * 从头到尾依次渲染已加载的章节区块；离屏很远的章节收成**等高占位**，
         * 因此文档高度恒定、滚动坐标天然稳定。章界处不再有任何窗口平移或补偿，
         * 「当前章」由滚动处理按视口顶部判定，正文早就在文档里 ——
         * 这正是「章末上滑跳回上一章开头」这一类问题的根除方式。
         */
        <div
          className="reading-scroll-window"
          style={scrollStreamMinHeight > 0 ? { minHeight: `${scrollStreamMinHeight}px` } : undefined}
        >
          {scrollEntries.map(entry => {
            if (entry.paragraphs === null) {
              /*
               * 正文不在手里的章节有两种，必须区别对待（⚠️ 这里踩过一个大坑）：
               *
               * ① **已经量过高度**的已读章节 ⇒ 收成等高占位块（不渲染正文、撑住精确高度）。
               * ② **还没量过高度**的章节 ⇒ 必须渲染成正常区块（骨架/正文）。
               *    绝不能一律用占位块：占位块既不渲染正文、也不会被量到高度，
               *    于是这些章节**永远停在占位态**、读者看到的就是「往上往下都不加载，只有空白」
               *   （实测复现：把未加载章节也换成占位块后，整本书只剩当前章有内容）。
               */
              if (entry.height <= 0) {
                return (
                  <article
                    key={scrollSectionKey(entry.index)}
                    data-chapter-index={entry.index}
                    className={`reading-content is-scroll-neighbor font-${settings.font}`}
                    ref={getScrollSectionRef(entry.index)}
                    aria-hidden="true"
                  >
                    {entry.needsDivider && (
                      <div className="reader-chapter-stream-divider" aria-hidden="true">
                        <span className="divider-line" />
                        <span className="divider-badge">{entry.title}</span>
                        <span className="divider-line" />
                      </div>
                    )}
                    <h1>{entry.title}</h1>
                    <ReaderContentSkeleton />
                  </article>
                )
              }
              return (
                <div
                  key={scrollSectionKey(entry.index)}
                  data-chapter-index={entry.index}
                  data-scroll-placeholder="true"
                  className="reading-scroll-placeholder"
                  ref={getScrollSectionRef(entry.index)}
                  style={{
                    height: `${entry.height}px`,
                    containIntrinsicSize: `${entry.height}px`,
                  }}
                  aria-hidden="true"
                />
              )
            }
            return (
              <article
                data-chapter-index={entry.index}
                key={scrollSectionKey(entry.index)}
                className={`reading-content font-${settings.font}${entry.isCurrent ? '' : ' is-scroll-neighbor'}`}
                ref={getScrollSectionRef(entry.index)}
                aria-hidden={entry.isCurrent ? undefined : 'true'}
                onPointerDown={entry.isCurrent ? onPointerDown : undefined}
                onPointerUp={entry.isCurrent ? onPointerUp : undefined}
              >
                {entry.needsDivider && (
                  <div className="reader-chapter-stream-divider" aria-hidden="true">
                    <span className="divider-line" />
                    <span className="divider-badge">{entry.title}</span>
                    <span className="divider-line" />
                  </div>
                )}
                <h1>{entry.title}</h1>
                {entry.isCurrent && loading && !content && liveScrollParagraphs.length === 0 && <ReaderContentSkeleton />}
                {entry.isCurrent && message && <p className="reader-error">{message}</p>}
                {entry.isCurrent
                  ? liveScrollParagraphs.map((line, index) => (
                    <p
                      key={index}
                      data-paragraph-index={index}
                      className={`reader-paragraph ${ttsActive && activeParagraphIndex === index ? 'tts-active-paragraph' : ''}`}
                      onClick={() => { if (ttsActive) handleParagraphClick(index) }}
                    >
                      {renderParagraphContent(line, index)}
                    </p>
                  ))
                  : entry.paragraphs.map((line, index) => (
                    <p key={index} className="reader-paragraph">{line}</p>
                  ))}
              </article>
            )
          })}
          {/*
            流末尾三态：
            ① 尾部正文还没接上 ⇒ 「正在加载下一章…」（提示读者继续往下滚不会撞到空白）；
            ② 已经铺到全书最后一章 ⇒ 「全书完」收尾提示；
            ③ 还有后续章节但正文已就绪 ⇒ 什么都不显示，继续往下读即可。
          */}
          {!scrollTailLoaded ? (
            <div className="reader-stream-bottom-loader" role="status">
              <span className="reader-loading-spinner-ring" />
              <span>{t('reader.loadingNextChapter', '正在加载下一章...')}</span>
            </div>
          ) : scrollStream.tail >= currentBook.chapters.length - 1 ? (
            <div className="reader-stream-end-notice" role="status">
              <span>{t('reader.lastChapterNotice', '— 全书完 · 已读至最后一章 —')}</span>
            </div>
          ) : null}
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
