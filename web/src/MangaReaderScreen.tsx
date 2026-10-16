import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type MangaImageItem } from './api'
import { Icon } from './icons'
import { getMangaManifest, putMangaManifest, warmChapterImages } from './mangaOffline'
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
  /** 当前渲染用的是**本地清单快照**（脱网回退），用于顶部横幅与失败文案区分。 */
  const [offlineSnapshot, setOfflineSnapshot] = useState(false)

  const chapter = chapters[chapterIndex]
  const total = images.length
  const loadedCount = loaded.size
  const progressPercent = total > 0 ? Math.round((loadedCount / total) * 100) : 0

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
  useEffect(() => {
    if (!offlineRunning) return
    const timer = window.setInterval(() => {
      refreshCachedChapters()
      void api.mangaPrefetchStatus()
        .then(list => {
          const stillRunning = (list ?? []).some(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl)
          if (!stillRunning) setOfflineRunning(false)
        })
        .catch(() => undefined)
    }, OFFLINE_POLL_MS)
    return () => window.clearInterval(timer)
  }, [offlineRunning, refreshCachedChapters, openBook.details.sourceId, openBook.bookUrl])

  /** 用户手动离线：`ahead` = 再往后多少章（0 = 只本章）；覆盖到书末即视为"整本" ⇒ 打 pinned 免死金牌。 */
  const requestOffline = useCallback((ahead: number) => {
    const remaining = chapters.length - 1 - chapterIndex
    const span = Math.min(ahead, Math.max(0, remaining))
    const whole = span >= remaining
    void api.mangaPrefetch(openBook.details.sourceId, openBook.bookUrl, chapterIndex, span, whole)
      .then(result => {
        if (result?.queued) {
          setOfflineRunning(true)
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
          </div>
          {/* 绿点图例**必须留着**：它是"哪些章已可离线读"的唯一解释 */}
          <small className="setting-hint">
            {offlineRunning
              ? t('manga.offlineRunning', '正在后台缓存，目录里绿点表示该章已可离线阅读')
              : t('manga.offlineHint', { count: cachedChapters.size, defaultValue: `已离线 ${cachedChapters.size} 章 · 绿点表示整章可离线阅读` })}
          </small>
        </footer>
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
    </main>
  )
}
