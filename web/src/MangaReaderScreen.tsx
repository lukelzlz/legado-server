import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type MangaImageItem } from './api'
import { Icon } from './icons'
import { getMangaManifest, putMangaManifest, warmChapterImages } from './mangaOffline'
import { OfflineCacheModal } from './OfflineCacheModal'
import { OpenBook, VirtualChapterList } from './ReaderScreen'
import { toast } from './Toast'
import type { ReaderTheme } from './readerSettings'

/**
 * 漫画阅读器（方案 E）。
 *
 * ## UI 取向：借参考实现的**交互**，穿本项目阅读器的**外壳**
 * 参考实现（外部站点）里值得抄的是四件事，其余（用户中心/书架/云同步）与本项目无关：
 * ① **图片加载进度**：`正在加载图片 (n/N)`，n 由 `img.complete && naturalHeight > 0` 累加；
 * ② **等图就绪再恢复滚动**：图片高度未知时先定位必然漂移，它是等加载完才 `scrollTo`；
 * ③ **控制条自动隐藏**：沉浸式看漫画时首屏只有图，点一下才出工具条；
 * ④ **目录/设置用弹窗或抽屉**，当前章高亮。
 *
 * 本项目这边的差异（更稳/更省）：
 * - manifest 已给**真实宽高** ⇒ 用 `aspect-ratio` 占位，**首屏不跳动**，因此恢复进度不必死等全部图片（只需目标图有高度）；
 * - 图片走 **服务端直出**（`/api/images/{key}`，ETag + immutable）⇒ 浏览器/SW 可长期缓存，也解决了防盗链；
 * - 外壳、抽屉、设置项一律复用 `ReaderScreen` 的既有 class（`reader-workspace` / `reader-drawer` /
 *   `setting-section` / `page-modes` 等）⇒ **主题切换（light/paper/dark）零额外适配**，
 *   因为本项目主题只靠 `--surface/--ink/--line/--muted/--accent/--canvas` 这套 CSS 变量。
 *
 * 刻意**不做**：TTS 朗读（漫画没有可朗读文本）、替换净化（对图片无意义）、
 * 分页/双页/右到左模式（Non-Goals）。
 */
type MangaReaderScreenProps = {
  openBook: OpenBook
  startIndex: number
  onClose: () => void
  /**
   * 主题（light/paper/dark）。与 `ReaderScreen` 同一约定：**根节点自带 `theme-*` class**，
   * 而不是只依赖外层 `.app-shell`。外层已带有同名 class（main.tsx 两处都套了 app-shell），
   * 这里再写一次是冗余但免费的第二道保险——将来若漫画阅读器被挂到别处（如全屏 portal），
   * 主题变量仍然生效。全文件只允许用 CSS 变量取色，严禁写死颜色。
   */
  theme: ReaderTheme
}

/** 图片适配方式：`width` = 适宽（默认，条漫/单页通用）；`original` = 原始像素（跨页大图可横向滚动查看）。 */
type MangaFitMode = 'width' | 'original'

type MangaUiSettings = {
  fit: MangaFitMode
  autoHideToolbars: boolean
}

const UI_SETTINGS_KEY = 'legado_manga_ui'
const AUTO_HIDE_DELAY_MS = 3200
/** 「整本离线」进行中时刷新绿点的间隔（给用户看得见的进度，而不是点了没反应）。 */
const OFFLINE_POLL_MS = 3000
/** 滚动到本章这个比例时，提前预取下一章（环节 E8 ③）。 */
const NEXT_CHAPTER_PREFETCH_RATIO = 0.7

function loadUiSettings(): MangaUiSettings {
  try {
    const raw = typeof localStorage === 'undefined' ? null : localStorage.getItem(UI_SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<MangaUiSettings>
      return { fit: parsed.fit === 'original' ? 'original' : 'width', autoHideToolbars: parsed.autoHideToolbars !== false }
    }
  } catch {
    // 设置读不出来就用默认值：这属于增强项，绝不能因为它让阅读器打不开
  }
  return { fit: 'width', autoHideToolbars: true }
}

function IconButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return (
    <button type="button" className="reader-icon-button" aria-label={label} title={label} onClick={onClick}>
      <Icon name={icon} />
    </button>
  )
}

/**
 * 图片框的 class。
 *
 * `is-loaded` 决定"60vh 占位高度还在不在"（见 `styles.css` 的无缝拼接规则）：加载完必须去掉占位，
 * 否则每张短图上下都会空出一截底色 —— 纵向连读时那就是一道道"接缝"。
 *
 * 单独抽成纯函数，是为了让"**加载后确实带上了这个 class**"可被测试：
 * 只改 CSS、而 TSX 忘了加 class 的话，无缝修复会**静默失效**（页面看起来毫无变化）。
 */
export function mangaImageFrameClass(isLoaded: boolean): string {
  return isLoaded ? 'manga-image-frame is-loaded' : 'manga-image-frame'
}

/**
 * 目录底栏「缓存进行中」那一态（进度条 + 「取消缓存」）。
 *
 * **为什么单独抽成一个纯函数**：本仓库的渲染断言用 `renderToStaticMarkup`，而它
 * **不执行 effect**（服务端预取进度是进页面之后才异步取回来的）⇒ 组件初始态永远是空闲的，
 * "进行中"那一态在静态渲染里根本出不来。抽成纯函数后，测试可以**直接喂一个 running 状态**渲染断言，
 * 而不是退化成只对源码 grep。
 *
 * class 与文案键与小说阅读器 `ReaderScreen` 完全一致
 * （`.cache-progress-bar-track/fill`、`.cache-progress-text`、`.cache-cancel-btn`、`reader.cancelCache`）。
 */
export function mangaCacheProgressMarkup(props: {
  percent: number
  done: number
  planned: number
  serverCachingLabel: string
  unitLabel: string
  cancelLabel: string
  onCancel: () => void
}): React.ReactElement {
  // 夹紧到 0..100：`planned` 还没回填（0）时绝不出 NaN% 或负宽度
  const percent = Math.min(100, Math.max(0, Number.isFinite(props.percent) ? props.percent : 0))
  return (
    <div className="drawer-cache-progress">
      <div className="cache-progress-text">
        <span>{props.serverCachingLabel}</span>
        <strong>{percent}% ({props.done}/{props.planned}{props.unitLabel})</strong>
      </div>
      <div className="cache-progress-bar-track">
        <div className="cache-progress-bar-fill" style={{ width: `${percent}%` }} />
      </div>
      <button type="button" className="cache-cancel-btn" onClick={props.onCancel}>{props.cancelLabel}</button>
    </div>
  )
}

export function MangaReaderScreen({ openBook, startIndex, onClose, theme }: MangaReaderScreenProps) {
  const { t } = useTranslation()
  const chapters = openBook.chapters
  const [chapterIndex, setChapterIndex] = useState(() => Math.max(0, Math.min(startIndex, Math.max(0, chapters.length - 1))))
  const [images, setImages] = useState<MangaImageItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [failed, setFailed] = useState<Set<number>>(() => new Set())
  const [activeImage, setActiveImage] = useState(0)
  const [activeDrawer, setActiveDrawer] = useState<'toc' | 'settings' | null>(null)
  const [chapterQuery, setChapterQuery] = useState('')
  const [settings, setSettings] = useState<MangaUiSettings>(loadUiSettings)
  const [toolbarsVisible, setToolbarsVisible] = useState(true)
  const [retryTokens, setRetryTokens] = useState<Record<number, number>>({})
  /** 已成功解码的图片序号（`onLoad` 累加；重试 remount 不会重复计数）。 */
  const [loaded, setLoaded] = useState<Set<number>>(() => new Set())

  const scrollRef = useRef<HTMLElement | null>(null)
  const imageRefs = useRef<Array<HTMLDivElement | null>>([])
  const restoredRef = useRef(false)
  const saveTimerRef = useRef<number | null>(null)
  const hideTimerRef = useRef<number | null>(null)
  /** 已请求过"下一章预取"的章节下标（滚动到 70% 只触发一次）。 */
  const prefetchedNextRef = useRef<Set<number>>(new Set())
  /** 已整章就绪的章节 url（目录绿点 `●`）。 */
  const [cachedChapters, setCachedChapters] = useState<Set<string>>(() => new Set())
  /** 用户手动"整本离线"进行中（只用于显示进度与轮询绿点）。 */
  const [offlineRunning, setOfflineRunning] = useState(false)
  /**
   * 服务端预取进度（`done/planned`）。
   *
   * **不新加轮询循环**：它由下面那个既有的绿点轮询顺手回填；投递后先给 `null`，
   * 首次回填前进度条按 0% 渲染（与小说阅读器「正在服务端缓存」那一态的观感一致）。
   */
  const [prefetchProgress, setPrefetchProgress] = useState<{ done: number; planned: number } | null>(null)
  /** 「自定义…」范围缓存（与小说同款交互：第 X 至 Y 章 → 开始缓存）。 */
  const [customRangeOpen, setCustomRangeOpen] = useState(false)
  const [customRangeStart, setCustomRangeStart] = useState(1)
  const [customRangeEnd, setCustomRangeEnd] = useState(50)
  /** 「管理本设备离线缓存」弹窗（与小说**同一个** `OfflineCacheModal`）。 */
  const [offlineStatsModal, setOfflineStatsModal] = useState(false)
  /** 是否已在书架（与小说同一套 API：`api.bookshelf()` 查在架 + add/remove 改）。 */
  const [inShelf, setInShelf] = useState(true)
  /** 当前渲染用的是**本地清单快照**（脱网回退），用于顶部横幅与失败文案区分。 */
  const [offlineSnapshot, setOfflineSnapshot] = useState(false)

  const chapter = chapters[chapterIndex]
  const total = images.length
  const loadedCount = loaded.size
  const progressPercent = total > 0 ? Math.round((loadedCount / total) * 100) : 0
  /** 服务端预取的百分比（`planned` 为 0 时按 0% 渲染，绝不出 NaN%）。 */
  const prefetchPercent = prefetchProgress && prefetchProgress.planned > 0
    ? Math.min(100, Math.round((prefetchProgress.done / prefetchProgress.planned) * 100))
    : 0

  const filteredChapters = useMemo(() => {
    const query = chapterQuery.trim()
    if (!query) return chapters
    return chapters.filter(item => item.title.includes(query))
  }, [chapters, chapterQuery])

  const updateSettings = useCallback((patch: Partial<MangaUiSettings>) => {
    setSettings(prev => {
      const next = { ...prev, ...patch }
      try {
        localStorage.setItem(UI_SETTINGS_KEY, JSON.stringify(next))
      } catch {
        // 存不下就算了，本次会话内仍然生效
      }
      return next
    })
  }, [])

  // ---- 控制条自动隐藏（参考实现的沉浸式做法：无操作 N 秒后收起，点画面再唤出） ----
  const scheduleHide = useCallback(() => {
    if (hideTimerRef.current) window.clearTimeout(hideTimerRef.current)
    if (!settings.autoHideToolbars) return
    hideTimerRef.current = window.setTimeout(() => {
      // 抽屉开着时绝不能收：否则用户正翻目录，工具条自己消失了
      setActiveDrawer(current => {
        if (current === null) setToolbarsVisible(false)
        return current
      })
    }, AUTO_HIDE_DELAY_MS)
  }, [settings.autoHideToolbars])

  useEffect(() => {
    if (toolbarsVisible) scheduleHide()
    return () => {
      if (hideTimerRef.current) window.clearTimeout(hideTimerRef.current)
    }
  }, [toolbarsVisible, scheduleHide])

  const revealToolbars = useCallback(() => {
    setToolbarsVisible(true)
    scheduleHide()
  }, [scheduleHide])

  // ---- 进度保存：scrollPosition 语义 = (当前图片序号 + 1) / 总张数 ----
  // 刻意不用像素偏移：图片懒加载会让容器高度持续变化，像素偏移必然漂移。
  const persist = useCallback((index: number, imageIndex: number, imageTotal: number) => {
    const target = chapters[index]
    if (!target) return
    const position = imageTotal > 0 ? Math.min(1, Math.max(0, (imageIndex + 1) / imageTotal)) : 0
    void api.saveProgress(
      openBook.details.sourceId,
      openBook.bookUrl,
      target.url,
      target.index,
      position,
      target.title,
    ).catch(() => undefined)
  }, [chapters, openBook.bookUrl, openBook.details.sourceId])

  // ---- 载入章节清单（L2 命中时服务端不会再抓章节页） ----
  useEffect(() => {
    const target = chapters[chapterIndex]
    if (!target) {
      setImages([])
      setError(t('manga.noChapters', '本书没有可阅读的章节'))
      setLoading(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setFailed(new Set())
    setLoaded(new Set())
    setRetryTokens({})
    setActiveImage(0)
    restoredRef.current = false
    void api.mangaManifest(openBook.details.sourceId, target.url, openBook.bookUrl, target.index, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return
        const list = result?.images ?? []
        setImages(list)
        setOfflineSnapshot(false)
        // 服务端已标记过 failed 的图直接亮重试按钮，别让用户每次都白等一轮
        setFailed(new Set(list.filter(item => item.status === 'failed').map(item => item.index)))
        if (list.length === 0) {
          // 明确报错，绝不静默留白（AGENTS.md：「静默丢数据」比抛错危险）
          setError(t('manga.emptyManifest', '图片规则未提取到任何图片，请检查该书源的正文规则'))
        } else {
          persist(chapterIndex, 0, list.length)
          // 清单快照 + **整章预热**（P3）：快照让脱网时还能渲染，预热把图片塞进 SW 缓存。
          // 两者都失败静默：离线能力是增强项，绝不能在联网阅读时碍事。
          void putMangaManifest({
            sourceId: openBook.details.sourceId,
            bookUrl: openBook.bookUrl,
            chapterUrl: target.url,
            chapterIndex: target.index,
            title: target.title,
            images: list,
          })
          void warmChapterImages(list.map(item => item.key), { signal: controller.signal })
        }
        // 后台预取本章（环节 E8 ②）。**刻意放在清单加载之后**：此刻 L2 清单已经落库，
        // 预取走的是缓存路径，不会再去解析一遍章节页（否则同一章会被抓两次）。
        void api.mangaPrefetch(openBook.details.sourceId, openBook.bookUrl, target.index, 0).catch(() => undefined)
      })
      .catch(async (err: unknown) => {
        if (controller.signal.aborted) return
        // 脱网 / 服务不可达时的兜底：用本地清单快照照常渲染。
        // 不做这一步的话，最难受的形态会出现——**图都在 SW 缓存里，界面却因为拿不到清单而全白**。
        const snapshot = await getMangaManifest(openBook.details.sourceId, target.url)
        if (controller.signal.aborted) return
        if (snapshot && snapshot.images.length > 0) {
          setImages(snapshot.images)
          setFailed(new Set())
          setOfflineSnapshot(true)
          setError('')
          return
        }
        setImages([])
        setOfflineSnapshot(false)
        setError(err instanceof Error ? err.message : t('manga.loadFailed', '图片清单加载失败'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [chapterIndex, chapters, openBook.details.sourceId, openBook.bookUrl, persist, t])

  // ---- 目录绿点：整章就绪的唯一口径在服务端 ----
  // `/api/bookshelf/cached-chapters` 契约不变，服务端按 book_kind 换源（漫画看 manga_chapter_cache）。
  const refreshCachedChapters = useCallback(() => {
    void api.getCachedChapters(openBook.details.sourceId, openBook.bookUrl)
      .then(result => setCachedChapters(new Set(result?.cachedChapterUrls ?? [])))
      .catch(() => undefined) // 绿点只是提示：读不到就当没有，绝不能因此打断阅读
  }, [openBook.details.sourceId, openBook.bookUrl])

  useEffect(() => {
    refreshCachedChapters()
  }, [refreshCachedChapters])

  // 本章图**全部解码** ⇒ 服务端此刻已把它标成 ready：立刻刷一次，绿点当场就亮
  useEffect(() => {
    if (total > 0 && loadedCount === total) refreshCachedChapters()
  }, [total, loadedCount, refreshCachedChapters])

  // 「整本离线」进行中：轻量轮询绿点，并在服务端任务跑完时自动收尾
  // （**复用这一个既有轮询**顺带回填 `done/planned`，绝不新开第二个定时器）
  useEffect(() => {
    if (!offlineRunning) return
    const timer = window.setInterval(() => {
      refreshCachedChapters()
      void api.mangaPrefetchStatus()
        .then(list => {
          const mine = (list ?? []).find(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl)
          if (mine) setPrefetchProgress({ done: mine.done, planned: mine.planned })
          else {
            setOfflineRunning(false)
            setPrefetchProgress(null)
          }
        })
        .catch(() => undefined)
    }, OFFLINE_POLL_MS)
    return () => window.clearInterval(timer)
  }, [offlineRunning, refreshCachedChapters, openBook.details.sourceId, openBook.bookUrl])

  /**
   * 进阅读器时**一次性**对齐服务端上已有的预取任务（刷新页面/从书架重进时，
   * 底栏要当场显示进度条与「取消缓存」，而不是等用户再点一次缓存）。
   *
   * 刻意**不用** `setInterval`：这不是新的轮询循环——只读一次，后续进度由上面那个既有轮询负责。
   */
  useEffect(() => {
    let cancelled = false
    void api.mangaPrefetchStatus()
      .then(list => {
        if (cancelled) return
        const mine = (list ?? []).find(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl)
        if (mine) {
          setOfflineRunning(true)
          setPrefetchProgress({ done: mine.done, planned: mine.planned })
        }
      })
      .catch(() => undefined) // 状态接口读不到只是没有进度条，绝不能打断阅读
    return () => { cancelled = true }
  }, [openBook.details.sourceId, openBook.bookUrl])

  // ---- 书架状态与「加书架 / 已在书架」：复用小说阅读器同一套 API ----
  const refreshShelfState = useCallback(() => {
    void api.bookshelf()
      .then(list => setInShelf(list.some(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl)))
      .catch(() => undefined) // 书架状态只是提示：读不到就当未收藏，绝不影响阅读
  }, [openBook.details.sourceId, openBook.bookUrl])

  useEffect(() => {
    refreshShelfState()
  }, [refreshShelfState])

  const toggleShelf = useCallback(async () => {
    const details = openBook.details
    try {
      if (inShelf) {
        if (!confirm(t('shelf.removeFromShelfConfirm', { name: details.name, defaultValue: `移出“${details.name}”将清除书架、阅读进度和缓存封面，确定继续吗？` }))) return
        await api.removeFromBookshelf(details.sourceId, openBook.bookUrl)
        setInShelf(false)
        toast.info(t('reader.removedFromShelfToast', { name: details.name, defaultValue: `《${details.name}》已移出书架` }))
        return
      }
      // 与小说同款兜底：自身没有封面时，从候选书源里挑一个真实封面 URL（服务端会物化成副本）
      const fallbackCover = details.coverUrl || details.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()
      await api.addToBookshelf({
        sourceId: details.sourceId,
        bookUrl: openBook.bookUrl,
        name: details.name,
        author: details.author,
        tocUrl: details.tocUrl,
        coverUrl: fallbackCover || undefined,
        alternateSources: details.alternateSources,
      })
      setInShelf(true)
      toast.success(t('reader.addedToShelfToast', { name: details.name, defaultValue: `《${details.name}》已加入书架` }))
    } catch {
      toast.error(t('shelf.toggleShelfFailed', '操作书架失败'))
    }
  }, [inShelf, openBook, t])

  /**
   * 用户手动离线：`ahead` = 从 `from`（默认当前章）起**再往后多少章**（0 = 只本章）；
   * 覆盖到书末即视为"整本" ⇒ 打 pinned 免死金牌。
   *
   * `from` 参数是给「自定义…」范围用的：既有语义不变（默认仍是当前章），
   * 这样四按钮的调用点一个字都不用改。
   */
  const requestOffline = useCallback((ahead: number, from = chapterIndex) => {
    const remaining = chapters.length - 1 - from
    const span = Math.min(ahead, Math.max(0, remaining))
    const whole = span >= remaining
    void api.mangaPrefetch(openBook.details.sourceId, openBook.bookUrl, Math.max(0, from), span, whole)
      .then(result => {
        if (result?.queued) {
          setOfflineRunning(true)
          // 真实进度等既有轮询回填；先给个"进行中"的壳，不让用户点了没反应
          setPrefetchProgress(null)
          toast.success(
            whole
              ? t('manga.offlineQueuedAll', '已加入整本离线队列（后台缓存，可继续阅读）')
              : t('manga.offlineQueued', { count: span + 1, defaultValue: `已加入离线队列（共 ${span + 1} 章）` }),
          )
        } else {
          toast.info(t('manga.offlineNothing', '没有需要缓存的内容'))
        }
      })
      .catch(() => toast.error(t('manga.offlineFailed', '离线缓存请求失败')))
  }, [chapters.length, chapterIndex, openBook.details.sourceId, openBook.bookUrl, t])

  /**
   * 「自定义…」范围缓存：把「第 X 至 Y 章」（1 起、含两端）落到既有的
   * `api.mangaPrefetch(fromChapterIndex, span)` —— 与四按钮走**同一条**投递路径，不新造接口。
   */
  const requestOfflineRange = useCallback((fromChapter: number, toChapter: number) => {
    const total = chapters.length
    if (total === 0) return
    const from = Math.min(Math.max(1, Math.floor(fromChapter) || 1), total)
    const to = Math.min(Math.max(from, Math.floor(toChapter) || from), total)
    requestOffline(to - from, from - 1)
  }, [chapters.length, requestOffline])

  const cancelOffline = useCallback(async () => {
    try {
      // 只取消、不删缓存：`clearImageCache`（DELETE /books/images/cache）会把已下好的图一起删掉
      await api.cancelMangaPrefetch(openBook.details.sourceId, openBook.bookUrl)
      setOfflineRunning(false)
      setPrefetchProgress(null)
      toast.info(t('reader.cancelledCacheToast', { name: openBook.details.name, defaultValue: `已取消《${openBook.details.name}》的离线缓存` }))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('reader.cancelCacheFailed', '无法取消缓存'))
    }
  }, [openBook.details.sourceId, openBook.bookUrl, openBook.details.name, t])

  /**
   * 「下载到本设备（离线脱机）」。
   *
   * ## 语义如实说明（与小说那个按钮**不一样**）
   * 小说那边是前端把**正文文本**写进本机 IndexedDB（真正脱机可读，不依赖服务端）；
   * 漫画的图片字节则统一落在**服务端**图片缓存里，浏览器侧由 Service Worker 在阅读
   * （以及清单加载时的整章预热 `warmChapterImages`）过程中按需写入。
   * 因此这里落地为**触发本书的既有缓存动作**：从第 1 章覆盖到书末 ⇒ 等价于「全本缓存」
   * （还会打上 pinned 免死金牌）。它不会像小说那个按钮那样一次性把整本写进本机离线库。
   */
  const downloadToDevice = useCallback(() => {
    requestOffline(chapters.length, 0)
  }, [chapters.length, requestOffline])

  // ---- 恢复进度：定位到上次看到的那一张 ----
  // 借鉴参考实现「等图就绪再定位」：只有当目标图**已经有高度**时才滚，
  // 否则（宽高未知、图还没解码）先定位会漂到别处。manifest 给了宽高时这里一次就成。
  useEffect(() => {
    if (restoredRef.current || loading || total === 0) return
    const progress = openBook.progress
    if (!progress || progress.chapterUrl !== chapter?.url || !progress.scrollPosition) {
      restoredRef.current = true
      return
    }
    const targetIndex = Math.min(total - 1, Math.max(0, Math.round(progress.scrollPosition * total) - 1))
    if (targetIndex <= 0) {
      restoredRef.current = true
      return
    }
    const node = imageRefs.current[targetIndex]
    const hasHeight = !!node && node.offsetHeight > 0
    // 目标图还没有高度就等下一轮（`loaded` 变化会重新触发本 effect）
    if (!hasHeight && loadedCount === 0) return
    restoredRef.current = true
    setActiveImage(targetIndex)
    node?.scrollIntoView?.({ block: 'start' })
  }, [loading, total, loadedCount, openBook.progress, chapter?.url])

  // ---- 滚动时更新「当前第几张」并防抖保存进度 ----
  useEffect(() => {
    const container = scrollRef.current
    if (!container || total === 0) return
    let frame = 0
    const onScroll = () => {
      if (frame) return
      frame = window.requestAnimationFrame(() => {
        frame = 0
        const anchor = container.scrollTop + container.clientHeight * 0.4
        let current = 0
        for (let i = 0; i < imageRefs.current.length; i++) {
          const node = imageRefs.current[i]
          if (!node) continue
          if (node.offsetTop <= anchor) current = i
          else break
        }
        setActiveImage(current)
        if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current)
        saveTimerRef.current = window.setTimeout(() => persist(chapterIndex, current, total), 800)
        // 读到 70% 就提前把下一章抓起来（环节 E8 ③）：等用户真翻过去时已经就绪。
        // 只认"这一章触发过一次"，不重复投递（服务端那边同一本书本来就只有一条任务）。
        const scrollable = container.scrollHeight - container.clientHeight
        if (scrollable > 0 && container.scrollTop / scrollable >= NEXT_CHAPTER_PREFETCH_RATIO
          && !prefetchedNextRef.current.has(chapterIndex)) {
          prefetchedNextRef.current.add(chapterIndex)
          if (chapters[chapterIndex + 1]) {
            void api.mangaPrefetch(openBook.details.sourceId, openBook.bookUrl, chapterIndex + 1, 0).catch(() => undefined)
          }
        }
      })
    }
    container.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      container.removeEventListener('scroll', onScroll)
      if (frame) window.cancelAnimationFrame(frame)
      if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current)
    }
  }, [chapterIndex, total, persist, chapters, openBook.details.sourceId, openBook.bookUrl])

  const changeChapter = useCallback((next: number) => {
    if (next < 0 || next >= chapters.length || next === chapterIndex) return
    persist(chapterIndex, activeImage, Math.max(1, total))
    setChapterIndex(next)
    scrollRef.current?.scrollTo({ top: 0 })
    revealToolbars()
  }, [chapters.length, chapterIndex, persist, activeImage, total, revealToolbars])

  const retryImage = useCallback((index: number) => {
    setFailed(prev => {
      const next = new Set(prev)
      next.delete(index)
      return next
    })
    setLoaded(prev => {
      const next = new Set(prev)
      next.delete(index)
      return next
    })
    // 通过 remount 该 <img> 强制浏览器重新发起请求（服务端会把 failed 的行重抓一次）。
    // 刻意**不加** cache-busting 查询串：那会破坏 ETag/immutable 与 SW 的缓存键。
    setRetryTokens(prev => ({ ...prev, [index]: (prev[index] ?? 0) + 1 }))
  }, [])

  const markLoaded = useCallback((index: number) => {
    setLoaded(prev => (prev.has(index) ? prev : new Set(prev).add(index)))
  }, [])

  const toggleToolbars = useCallback(() => {
    if (activeDrawer !== null) return
    setToolbarsVisible(prev => {
      const next = !prev
      if (next) scheduleHide()
      return next
    })
  }, [activeDrawer, scheduleHide])

  const fitClass = settings.fit === 'original' ? 'is-fit-original' : 'is-fit-width'

  return (
    <main className={`reader-workspace manga-workspace theme-${theme} ${toolbarsVisible ? 'toolbars-open' : 'toolbars-hidden'} ${fitClass}`}>
      {/* 悬浮顶栏：复用文本阅读器的 header 结构与 class（主题变量天然生效） */}
      <header className="reader-header">
        <div className="reader-header-left">
          <IconButton
            label={t('manga.back', '返回')}
            icon="arrowLeft"
            onClick={() => { persist(chapterIndex, activeImage, Math.max(1, total)); onClose() }}
          />
          <IconButton label={t('reader.catalog', '目录')} icon="list" onClick={() => { setActiveDrawer(d => d === 'toc' ? null : 'toc'); revealToolbars() }} />
        </div>
        <strong className="reader-header-title" title={openBook.details.name}>
          {openBook.details.name}
          {chapter && <small className="manga-header-chapter">{chapter.title}</small>}
        </strong>
        <div className="reader-header-actions">
          {total > 0 && (
            <span className="manga-counter" title={t('manga.imageProgressHint', '已加载图片数 / 总张数')}>
              {loadedCount < total ? `${loadedCount}/${total}` : `${activeImage + 1}/${total}`}
            </span>
          )}
          <IconButton label={t('reader.settings', '阅读设置')} icon="settings" onClick={() => { setActiveDrawer(d => d === 'settings' ? null : 'settings'); revealToolbars() }} />
        </div>
      </header>

      {/* 图片加载进度条（借鉴参考实现：让用户看到"在加载第几张"而不是干等） */}
      {!loading && !error && total > 0 && loadedCount < total && (
        <div className="manga-progress-bar" role="progressbar" aria-valuenow={progressPercent} aria-valuemin={0} aria-valuemax={100}>
          <i style={{ width: `${progressPercent}%` }} />
        </div>
      )}

      {/* 离线横幅：**必须显式告诉用户"现在是脱网读的本地快照"**，否则缺图会被当成 bug */}
      {offlineSnapshot && (
        <div className="manga-offline-banner" role="status">
          <Icon name="cloudDownload" />
          <span>{t('manga.offlineBanner', '离线阅读：本章用的是本机已缓存的图片，缺图会明确标出')}</span>
        </div>
      )}

      {/* 目录抽屉：复用虚拟章节列表（当前章高亮由组件自己处理） */}
      <aside className={`reader-drawer reader-drawer-left ${activeDrawer === 'toc' ? 'open' : ''}`} aria-label={t('reader.tocDrawer', '目录抽屉')}>
        <header className="drawer-header">
          <div className="drawer-title">
            <Icon name="list" />
            <strong>{t('reader.toc', '目录')}</strong>
            <small>{t('reader.totalChapters', { count: chapters.length, defaultValue: `共 ${chapters.length} 章` })}</small>
          </div>
          <div className="drawer-header-actions">
            <IconButton label={t('reader.closeToc', '关闭目录')} icon="close" onClick={() => setActiveDrawer(null)} />
          </div>
        </header>
        <div className="chapter-search">
          <Icon name="search" />
          <input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder={t('reader.searchChapter', '搜索章节')} />
        </div>
        <VirtualChapterList
          chapters={filteredChapters}
          activeChapterIndex={chapterIndex}
          onSelect={index => { changeChapter(index); setActiveDrawer(null) }}
          itemHeight={40}
          overscan={8}
          autoScrollKey={activeDrawer === 'toc' ? 1 : 0}
          cachedUrlsSet={cachedChapters}
        />
        {/* 缓存底栏：把「离线缓存」搬到**目录抽屉**里（与小说阅读器的目录底栏同构）。
            放在列表之后 = 底部固定，不用滚动就能点；点击**不关抽屉**，这样后台缓存跑起来时
            列表里的绿点当场可见（用户要的就是这个观感："像小说的那个一样"）。 */}
        <footer className="drawer-footer">
          <div className="drawer-cache-section">
            {offlineRunning ? (
              /* 进行中：与小说同款的进度条 + 「取消缓存」。
                 进度数据直接来自既有状态接口（`done/planned`），不新造状态机。 */
              mangaCacheProgressMarkup({
                percent: prefetchPercent,
                done: prefetchProgress?.done ?? 0,
                planned: prefetchProgress?.planned ?? 0,
                serverCachingLabel: t('reader.serverCaching', '正在服务端缓存'),
                unitLabel: t('reader.cacheCustomUnit', '章'),
                cancelLabel: t('reader.cancelCache', '取消缓存'),
                onCancel: () => void cancelOffline(),
              })
            ) : (
              <div className="drawer-cache-idle">
                <div className="cache-range-btn-grid">
                  <button
                    type="button"
                    className="cache-range-btn"
                    disabled={offlineRunning}
                    title={t('manga.offlineCurrentTitle', '缓存当前这一章的全部图片')}
                    onClick={() => requestOffline(0)}
                  >
                    {t('manga.offlineCurrent', '本章')}
                  </button>
                  <button
                    type="button"
                    className="cache-range-btn"
                    disabled={offlineRunning}
                    title={t('reader.cacheNext50Title', '缓存当前章节后续 50 章')}
                    onClick={() => requestOffline(50)}
                  >
                    {t('reader.cacheNext50', '后 50 章')}
                  </button>
                  <button
                    type="button"
                    className="cache-range-btn"
                    disabled={offlineRunning}
                    title={t('reader.cacheNext100Title', '缓存当前章节后续 100 章')}
                    onClick={() => requestOffline(100)}
                  >
                    {t('reader.cacheNext100', '后 100 章')}
                  </button>
                  <button
                    type="button"
                    className="cache-range-btn"
                    disabled={offlineRunning}
                    title={t('manga.offlineAllTitle', '缓存整本漫画（GC 永不自动淘汰）')}
                    onClick={() => requestOffline(chapters.length)}
                  >
                    {t('reader.cacheAll', '全本缓存')}
                  </button>
                  {/* 第 5 格：自定义范围（复用小说那套 class 与文案键） */}
                  <button
                    type="button"
                    className="cache-range-btn"
                    disabled={offlineRunning}
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
                        max={Math.max(1, chapters.length)}
                        value={customRangeStart}
                        onChange={event => setCustomRangeStart(Number(event.target.value))}
                      />
                      <span>{t('reader.cacheCustomTo', '至')}</span>
                      <input
                        type="number"
                        min={customRangeStart}
                        max={Math.max(1, chapters.length)}
                        value={customRangeEnd}
                        onChange={event => setCustomRangeEnd(Number(event.target.value))}
                      />
                      <span>{t('reader.cacheCustomUnit', '章')}</span>
                    </div>
                    <button
                      type="button"
                      className="primary-button range-start-btn"
                      onClick={() => {
                        setCustomRangeOpen(false)
                        requestOfflineRange(customRangeStart, customRangeEnd)
                      }}
                    >
                      {t('reader.startCache', '开始缓存')}
                    </button>
                  </div>
                )}

                <div className="cache-local-sync-row">
                  {/* 语义见 `downloadToDevice` 的注释：漫画落到**服务端**缓存，不是本机离线库 */}
                  <button
                    type="button"
                    className="cache-sync-btn"
                    onClick={downloadToDevice}
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

            {/* 绿点图例**必须留着**：它是"哪些章已可离线读"的唯一解释 */}
            <small className="setting-hint">
              {offlineRunning
                ? t('manga.offlineRunning', '正在后台缓存，目录里绿点表示该章已可离线阅读')
                : t('manga.offlineHint', { count: cachedChapters.size, defaultValue: `已离线 ${cachedChapters.size} 章 · 绿点表示整章可离线阅读` })}
            </small>
          </div>
        </footer>

        {/* 目录底栏的**动作区**：与小说阅读器同构（`drawer-footer` 放缓存动作，
            其下 `drawer-footer-actions` 放换源 + 加书架）。
            ⚠️ 换源按钮由 `feat/shared-source-switch` 那个 PR 加在**同一锚点**上；合并时
            两者必须落在**同一个** div 里（顺序照小说：换源在前、加书架在后），
            因为这个 div 是 `grid-template-columns: 1fr 1fr` ⇒ 两个 div 会变成两行、各占左半格。
            本 PR 刻意不重复实现换源（那是另一个 PR 的能力），只补用户反馈缺失的这一格。 */}
        <div className="drawer-footer-actions">
          <button type="button" className="shelf-button" onClick={() => void toggleShelf()}>
            <Icon name={inShelf ? 'check' : 'plus'} />{inShelf ? t('reader.inShelf', '已在书架') : t('reader.addToShelf', '加书架')}
          </button>
        </div>
      </aside>

      {/* 设置抽屉：直接复用文本阅读器的设置排版（setting-section / page-modes / setting-hint） */}
      <aside className={`reader-drawer reader-drawer-right ${activeDrawer === 'settings' ? 'open' : ''}`} aria-label={t('manga.settingsDrawer', '漫画设置抽屉')}>
        <header className="drawer-header">
          <div className="drawer-title"><Icon name="settings" /><strong>{t('manga.settings', '漫画设置')}</strong></div>
          <div className="drawer-header-actions">
            <IconButton label={t('common.close', '关闭')} icon="close" onClick={() => setActiveDrawer(null)} />
          </div>
        </header>
        <div className="drawer-scroll-content">
          <section className="setting-section">
            <span className="setting-label">{t('manga.imageFit', '图片适配')}</span>
            <div className="page-modes">
              <button type="button" className={settings.fit === 'width' ? 'selected' : ''} onClick={() => updateSettings({ fit: 'width' })}>
                {t('manga.fitWidth', '适宽')}
              </button>
              <button type="button" className={settings.fit === 'original' ? 'selected' : ''} onClick={() => updateSettings({ fit: 'original' })}>
                {t('manga.fitOriginal', '原始尺寸')}
              </button>
            </div>
            <small className="setting-hint">
              {settings.fit === 'width'
                ? t('manga.fitWidthHint', '图片撑满屏幕宽度，适合条漫与单页阅读')
                : t('manga.fitOriginalHint', '按原始像素显示，大图可左右拖动查看细节')}
            </small>
          </section>
          <section className="setting-section">
            <span className="setting-label">{t('manga.immersive', '沉浸阅读')}</span>
            <div className="page-modes">
              <button type="button" className={settings.autoHideToolbars ? 'selected' : ''} onClick={() => updateSettings({ autoHideToolbars: true })}>
                {t('manga.autoHideOn', '自动隐藏工具条')}
              </button>
              <button type="button" className={!settings.autoHideToolbars ? 'selected' : ''} onClick={() => updateSettings({ autoHideToolbars: false })}>
                {t('manga.autoHideOff', '常驻显示')}
              </button>
            </div>
            <small className="setting-hint">{t('manga.immersiveHint', '自动隐藏时单击画面可唤出工具条')}</small>
          </section>
        </div>
      </aside>

      {(activeDrawer !== null) && (
        <div className="reader-drawer-backdrop" onClick={() => setActiveDrawer(null)} aria-hidden="true" />
      )}

      {/* 阅读区：点击切换工具条显隐（沉浸式，对齐参考实现） */}
      <section className="manga-scroll" ref={scrollRef} onClick={toggleToolbars}>
        {loading && (
          <div className="reader-loading-container" aria-label={t('manga.loading', '正在加载图片清单')}>
            <div className="reader-loading-badge"><span className="reader-loading-spinner-ring" />{t('manga.loading', '正在加载图片清单')}</div>
          </div>
        )}
        {!loading && error && (
          <div className="manga-error-box">
            <p className="reader-error">{error}</p>
            <div className="manga-error-actions">
              <button type="button" className="subtle-button" onClick={() => setChapterIndex(current => current)}>{t('manga.retry', '重试')}</button>
              <button type="button" className="subtle-button" onClick={() => changeChapter(chapterIndex + 1)} disabled={chapterIndex >= chapters.length - 1}>{t('manga.nextChapter', '下一章')}</button>
            </div>
          </div>
        )}
        {!loading && !error && images.map(item => (
          <div
            key={`${item.index}-${retryTokens[item.index] ?? 0}`}
            // is-loaded 让**占位高度只在图片真正加载出来之前生效**：
            // 否则 60vh 的占位会在每张短图上下留出色带，纵向连续阅读时就是一道道"接缝"。
            className={mangaImageFrameClass(loaded.has(item.index))}
            ref={node => { imageRefs.current[item.index] = node }}
            // manifest 给了真实宽高 ⇒ 占位就按真实比例，**首屏不跳动**（未知时退回 CSS 的 60vh 兜底）
            style={item.width && item.height ? { aspectRatio: `${item.width} / ${item.height}`, minHeight: 0 } : undefined}
          >
            {failed.has(item.index) ? (
              <button type="button" className="manga-image-failed" onClick={event => { event.stopPropagation(); retryImage(item.index) }}>
                <Icon name="image" />
                <span>{t('manga.imageFailed', { index: item.index + 1, defaultValue: `第 ${item.index + 1} 张加载失败，点击重试` })}</span>
                {/* 两种失败必须给不同的话：让人去"重新登录/换源"和让人去"联网"是完全不同的动作 */}
                <small>
                  {offlineSnapshot
                    ? t('manga.imageMissingOffline', '该图没有缓存到本机（离线状态下拉不到）。联网后重试即可补上。')
                    : t('manga.imageFailedHint', '服务端已尝试带书源的 Referer/Cookie 代抓；反复失败通常意味着该源需要登录，或图床链接已失效')}
                </small>
              </button>
            ) : (
              <img
                src={api.imageUrl(item.key)}
                alt={t('manga.imageAlt', { index: item.index + 1, defaultValue: `第 ${item.index + 1} 张` })}
                loading={item.index < 3 ? 'eager' : 'lazy'}
                decoding="async"
                onLoad={() => markLoaded(item.index)}
                onError={() => setFailed(prev => new Set(prev).add(item.index))}
              />
            )}
          </div>
        ))}
        {!loading && !error && total > 0 && (
          <div className="manga-end-of-chapter">
            <button type="button" className="subtle-button" onClick={event => { event.stopPropagation(); changeChapter(chapterIndex + 1) }} disabled={chapterIndex >= chapters.length - 1}>
              {t('manga.nextChapter', '下一章')}
            </button>
          </div>
        )}
      </section>

      {/* 悬浮底栏：复用文本阅读器的章节控制条 */}
      <footer className="reader-floating-footer" onClick={event => event.stopPropagation()}>
        <div className="reader-chapter-control-bar">
          <button type="button" className="reader-chapter-btn prev" disabled={chapterIndex <= 0} onClick={() => changeChapter(chapterIndex - 1)} aria-label={t('manga.prevChapter', '上一章')}>
            <Icon name="arrowLeft" />
          </button>
          <div className="reader-chapter-slider-wrap">
            <div className="reader-chapter-info">
              <span className="reader-chapter-title">{chapter?.title ?? ''}</span>
              <small>{chapterIndex + 1} / {chapters.length}</small>
            </div>
            <input
              type="range"
              className="reader-chapter-slider"
              min={0}
              max={Math.max(0, chapters.length - 1)}
              value={chapterIndex}
              onChange={event => changeChapter(Number(event.target.value))}
              aria-label={t('manga.chapterSlider', '章节进度')}
            />
          </div>
          <button type="button" className="reader-chapter-btn next" disabled={chapterIndex >= chapters.length - 1} onClick={() => changeChapter(chapterIndex + 1)} aria-label={t('manga.nextChapter', '下一章')}>
            <Icon name="arrowRight" />
          </button>
        </div>
      </footer>

      {/* 「管理本设备离线缓存」：接的是小说阅读器**同一个** `OfflineCacheModal`
          （它自带本机 IndexedDB 统计 + 服务端图片缓存统计，不需要额外 props） */}
      {offlineStatsModal && (
        <OfflineCacheModal
          onClose={() => {
            setOfflineStatsModal(false)
            refreshCachedChapters()
          }}
        />
      )}
    </main>
  )
}
