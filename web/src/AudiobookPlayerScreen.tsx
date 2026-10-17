import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type AudioCacheStats, type AudioTrackItem } from './api'
import { Icon } from './icons'
import { getAudioManifest, putAudioManifest, warmChapterAudio } from './audioOffline'
import { OpenBook, VirtualChapterList } from './ReaderScreen'
import { toast } from './Toast'
import type { ReaderTheme } from './readerSettings'

/**
 * 有声书播放器（方案 G2 · P0–P3）。
 *
 * ## UI 取向：借参考实现的**版式**，穿本项目阅读器的**外壳**
 * 参考稿（`有声书阅读器参考/` 的外部站点）里值得抄的是四件事，其余（用户中心/收藏/云同步）
 * 与本项目无关：
 * ① **封面为视觉主体**：大圆角方形封面 + 深色渐隐叠层 + 播放中的跳动条；
 * ② **信息区**：章节标题（两行截断）/ 书名 / 状态行；
 * ③ **控制区**：`上一条 · 退 15s · 主播放键(64px) · 进 15s · 下一条` + 细进度条与左右时间；
 * ④ **底部功能条**：倍速 / 定时 / 缓存 / 目录 / 设置（参考稿的"音色/收藏"是它自家能力，不抄）。
 *
 * 本项目这边的差异（都是踩过坑之后的取舍）：
 * - **主题完全跟随主 UI**：全文件只允许用 `--canvas/--surface/--ink/--muted/--line/--accent`
 *   这套 CSS 变量，**不写任何写死的颜色**，因此 light/paper/dark 三套主题零额外适配。
 * - **封面的"高斯模糊底衬"用 `filter: blur()` 的独立图层**，不用 `backdrop-filter`
 *   ——AGENTS.md：`backdrop-filter` 会破坏 `position: fixed` 的包含块，弹层会被困在容器里。
 * - **进度语义是毫秒**（`position_ms`），与安卓 `durChapterPos` 同语义 ⇒ 跨端直传、无需换算。
 * - **音频走服务端直出**（`/api/audio/{key}`，Range + ETag + immutable）⇒ 可 seek、可离线、防盗链交给服务端。
 * - 目录/设置抽屉**直接复用文本阅读器与漫画阅读器的既有 class**，观感与操作与全站一致。
 *
 * 刻意**不做**：全局常驻 Mini 播放条（Non-Goal，会污染阅读/漫画用户）、
 * 书签与倍速记忆的多端同步、HLS/`.m3u8` 重写、任何转码。
 */

type AudiobookPlayerScreenProps = {
  openBook: OpenBook
  startIndex: number
  onClose: () => void
  /** 主题（light/paper/dark）。与 `ReaderScreen` 同一约定：**根节点自带 `theme-*` class**。 */
  theme: ReaderTheme
}

type AudioUiSettings = {
  /** 播放倍速（0.5~3.0）。 */
  rate: number
  /** 一章放完自动接下一章。 */
  autoNextChapter: boolean
  /** 快退/快进秒数。 */
  skipSeconds: number
}

const UI_SETTINGS_KEY = 'legado_audiobook_ui'
/** 进度落库节流窗口。音频的 `timeupdate` 约 4 次/秒，不节流会把写路径打满。 */
const PROGRESS_SAVE_INTERVAL_MS = 4000
/** 播放到本章这个比例时预取下一章（G5/G10 的 70% 预取）。 */
const NEXT_CHAPTER_PREFETCH_RATIO = 0.7
/** 睡眠定时的可选分钟数。 */
const SLEEP_MINUTES = [15, 30, 45, 60, 90]
/** 倍速快捷档位。 */
const RATE_PRESETS = [0.5, 0.75, 1, 1.25, 1.5, 2]

function loadUiSettings(): AudioUiSettings {
  try {
    const raw = typeof localStorage === 'undefined' ? null : localStorage.getItem(UI_SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<AudioUiSettings>
      const rate = typeof parsed.rate === 'number' && parsed.rate >= 0.5 && parsed.rate <= 3 ? parsed.rate : 1
      return {
        rate,
        autoNextChapter: parsed.autoNextChapter !== false,
        skipSeconds: parsed.skipSeconds === 30 ? 30 : 15,
      }
    }
  } catch {
    // 设置读不出来就用默认值：它是增强项，绝不能因为它让播放器打不开
  }
  return { rate: 1, autoNextChapter: true, skipSeconds: 15 }
}

/** 毫秒 → `mm:ss` / `h:mm:ss`。**等宽数字**由 CSS 的 `tabular-nums` 保证不跳动。 */
function formatTime(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '00:00'
  const total = Math.floor(ms / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

function IconButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return (
    <button type="button" className="reader-icon-button" aria-label={label} title={label} onClick={onClick}>
      <Icon name={icon} />
    </button>
  )
}

export function AudiobookPlayerScreen({ openBook, startIndex, onClose, theme }: AudiobookPlayerScreenProps) {
  const { t } = useTranslation()
  const chapters = openBook.chapters
  const [chapterIndex, setChapterIndex] = useState(() => Math.max(0, Math.min(startIndex, Math.max(0, chapters.length - 1))))
  const [tracks, setTracks] = useState<AudioTrackItem[]>([])
  const [trackIndex, setTrackIndex] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [playing, setPlaying] = useState(false)
  const [buffering, setBuffering] = useState(false)
  const [currentMs, setCurrentMs] = useState(0)
  const [durationMs, setDurationMs] = useState(0)
  const [settings, setSettings] = useState<AudioUiSettings>(loadUiSettings)
  const [activeDrawer, setActiveDrawer] = useState<'toc' | 'settings' | null>(null)
  const [speedOpen, setSpeedOpen] = useState(false)
  const [chapterQuery, setChapterQuery] = useState('')
  const [cachedChapters, setCachedChapters] = useState<Set<string>>(() => new Set())
  const [offlineSnapshot, setOfflineSnapshot] = useState(false)
  const [stats, setStats] = useState<AudioCacheStats | null>(null)
  const [caching, setCaching] = useState(false)
  /**
   * 本次缓存任务的失败情况（服务端如实带出的首条原因）。
   *
   * 为什么要显示：实测上游音频站会成片地连不上（`HTTP connect timed out` / `Connection reset`），
   * 旧实现只把失败写进库、界面上只有"正在缓存/进度不动"——用户完全无从判断是上游挂了、
   * 要登录、还是分段超限。这里把"失败几段 + 最近一条原因"直接摆在缓存区里。
   */
  const [prefetchFailure, setPrefetchFailure] = useState<{ failed: number; reason: string } | null>(null)
  /** 睡眠定时：剩余毫秒（0 = 未开启）。 */
  const [sleepRemainingMs, setSleepRemainingMs] = useState(0)
  /** 睡眠定时："播完本章即停"。 */
  const [stopAtChapterEnd, setStopAtChapterEnd] = useState(false)
  const [failedKeys, setFailedKeys] = useState<Set<string>>(() => new Set())

  const audioRef = useRef<HTMLAudioElement | null>(null)
  /** 待恢复的毫秒位置（`loadedmetadata` 之前 `currentTime` 写不进去）。 */
  const pendingSeekRef = useRef<number | null>(null)
  const restoredRef = useRef(false)
  const lastSavedRef = useRef(0)
  const prefetchedNextRef = useRef<Set<number>>(new Set())
  /** `play()` 被 pause/换源打断是**正常现象**，绝不能弹错（AGENTS.md 的 AbortError 守卫）。 */
  const playingIntentRef = useRef(false)

  const chapter = chapters[chapterIndex]
  const currentTrack = tracks[Math.min(trackIndex, Math.max(0, tracks.length - 1))]
  const progressPercent = durationMs > 0 ? Math.min(100, Math.max(0, (currentMs / durationMs) * 100)) : 0
  const chapterBookTotal = tracks.length
  const chapterReady = tracks.filter(item => item.status === 'ready').length

  const bookStats = useMemo(
    () => stats?.books.find(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl) ?? null,
    [stats, openBook.details.sourceId, openBook.bookUrl],
  )

  const filteredChapters = useMemo(() => {
    const query = chapterQuery.trim()
    if (!query) return chapters
    return chapters.filter(item => item.title.includes(query))
  }, [chapters, chapterQuery])

  const updateSettings = useCallback((patch: Partial<AudioUiSettings>) => {
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

  // ---- 进度：音频的语义是**毫秒**（`position_ms`），与手机端 `durChapterPos` 同语义 ----
  const persist = useCallback((index: number, positionMs: number) => {
    const target = chapters[index]
    if (!target) return
    lastSavedRef.current = Date.now()
    // `scrollPosition` 对音频**固定 0**：毫秒是唯一权威，绝不把两种语义混在一个字段里
    void api.saveProgress(
      openBook.details.sourceId,
      openBook.bookUrl,
      target.url,
      target.index,
      0,
      target.title,
      Math.max(0, Math.round(positionMs)),
    ).catch(() => undefined)
  }, [chapters, openBook.bookUrl, openBook.details.sourceId])

  // ---- 载入章节清单（命中服务端登记时不会再抓章节页） ----
  useEffect(() => {
    const target = chapters[chapterIndex]
    if (!target) {
      setTracks([])
      setError(t('audiobook.noChapters', '本书没有可播放的章节'))
      setLoading(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setTracks([])
    setTrackIndex(0)
    setFailedKeys(new Set())
    setCurrentMs(0)
    setDurationMs(0)
    restoredRef.current = false
    pendingSeekRef.current = null
    void api.audioManifest(openBook.details.sourceId, target.url, openBook.bookUrl, target.index, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return
        const list = result?.tracks ?? []
        setTracks(list)
        setOfflineSnapshot(false)
        setFailedKeys(new Set(list.filter(item => item.status === 'failed').map(item => item.key)))
        if (list.length === 0) {
          // 明确报错，绝不静默留白（AGENTS.md：「静默丢数据」比抛错危险）
          setError(t('audiobook.emptyManifest', '音频规则未提取到任何地址，请检查该书源的正文规则'))
        } else {
          // 恢复毫秒位置：只认"同一章"的进度，跨章恢复由 main 的 resumeIdx 决定
          const progress = openBook.progress
          if (progress && progress.chapterUrl === target.url && (progress.positionMs ?? 0) > 0) {
            pendingSeekRef.current = progress.positionMs ?? 0
          }
          // 清单快照 + 整章预热：脱网时还能播（两者都失败静默，属增强项）
          void putAudioManifest({
            sourceId: openBook.details.sourceId,
            bookUrl: openBook.bookUrl,
            chapterUrl: target.url,
            chapterIndex: target.index,
            tracks: list,
          })
          void warmChapterAudio(list.filter(item => item.status !== 'failed').map(item => item.key), { signal: controller.signal })
          // 后台缓存本章（服务端 L1）。**放在清单加载之后**：此刻已登记，缓存走本地路径不会重复解析章节页
          void api.audioPrefetch(openBook.details.sourceId, openBook.bookUrl, target.index, 0).catch(() => undefined)
        }
      })
      .catch(async (err: unknown) => {
        if (controller.signal.aborted) return
        // 脱网兜底：用本地清单快照照常渲染（不做这一步就会出现"音频都在缓存里，界面却打不开"）
        const snapshot = await getAudioManifest(openBook.details.sourceId, target.url)
        if (controller.signal.aborted) return
        if (snapshot && snapshot.tracks.length > 0) {
          setTracks(snapshot.tracks)
          setOfflineSnapshot(true)
          setError('')
          setFailedKeys(new Set())
          return
        }
        setTracks([])
        setOfflineSnapshot(false)
        setError(err instanceof Error ? err.message : t('audiobook.loadFailed', '音频清单加载失败'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [chapterIndex, chapters, openBook.details.sourceId, openBook.bookUrl, openBook.progress, t])

  // ---- 目录绿点：整章就绪的唯一口径在服务端（按 book_kind 换数据源） ----
  const refreshCachedChapters = useCallback(() => {
    void api.getCachedChapters(openBook.details.sourceId, openBook.bookUrl)
      .then(result => setCachedChapters(new Set(result?.cachedChapterUrls ?? [])))
      .catch(() => undefined) // 绿点只是提示：读不到就当没有
  }, [openBook.details.sourceId, openBook.bookUrl])

  useEffect(() => {
    refreshCachedChapters()
    void api.audioCacheStats().then(setStats).catch(() => undefined)
  }, [refreshCachedChapters])

  // 后台缓存进行中：轻量轮询绿点与统计，并在服务端任务跑完时自动收尾
  useEffect(() => {
    if (!caching) return
    const timer = window.setInterval(() => {
      refreshCachedChapters()
      void api.audioCacheStats().then(setStats).catch(() => undefined)
      void api.audioPrefetchStatus()
        .then(list => {
          const mine = (list ?? []).find(item => item.sourceId === openBook.details.sourceId && item.bookUrl === openBook.bookUrl)
          if (!mine) setCaching(false)
          // 失败原因**保留到下一次投递**（任务结束后服务端就不再回报这个任务了，清掉会让原因凭空消失）
          const failed = Number(mine?.failed ?? 0)
          if (mine) setPrefetchFailure(failed > 0 ? { failed, reason: mine.lastError ?? '' } : null)
        })
        .catch(() => undefined)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [caching, refreshCachedChapters, openBook.details.sourceId, openBook.bookUrl])

  /** 用户手动缓存：`ahead` = 再往后多少章（0 = 只本章）；覆盖到书末即视为"整本" ⇒ 打 pinned 免死金牌。 */
  const requestCache = useCallback((ahead: number) => {
    const remaining = chapters.length - 1 - chapterIndex
    const span = Math.min(ahead, Math.max(0, remaining))
    const whole = span >= remaining
    void api.audioPrefetch(openBook.details.sourceId, openBook.bookUrl, chapterIndex, span, whole)
      .then(result => {
        if (result?.queued) {
          setCaching(true)
          // 新任务开始 ⇒ 上一次的失败提示作废（否则用户会以为是这次也失败了）
          setPrefetchFailure(null)
          toast.success(
            whole
              ? t('audiobook.cacheQueuedAll', '已加入整本离线队列（后台缓存，可继续播放）')
              : t('audiobook.cacheQueued', { count: span + 1, defaultValue: `已加入离线队列（共 ${span + 1} 章）` }),
          )
        } else {
          toast.info(t('audiobook.cacheNothing', '没有需要缓存的内容'))
        }
      })
      .catch(() => toast.error(t('audiobook.cacheFailed', '离线缓存请求失败')))
  }, [chapters.length, chapterIndex, openBook.details.sourceId, openBook.bookUrl, t])

  // ---- 播放控制 ----
  const safePlay = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    playingIntentRef.current = true
    const result = audio.play()
    if (result && typeof result.catch === 'function') {
      // `pause()`/换源会打断 pending 的 `play()` ⇒ 浏览器抛 AbortError，这属于正常操作，必须静默
      result.catch((err: unknown) => {
        const name = (err as { name?: string } | null)?.name ?? ''
        if (name === 'AbortError' || name === 'NotAllowedError') return
        playingIntentRef.current = false
        setPlaying(false)
      })
    }
  }, [])

  const togglePlay = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    if (audio.paused) safePlay()
    else {
      playingIntentRef.current = false
      audio.pause()
    }
  }, [safePlay])

  const seekTo = useCallback((ms: number) => {
    const audio = audioRef.current
    if (!audio || !Number.isFinite(ms)) return
    const seconds = Math.max(0, ms / 1000)
    try {
      audio.currentTime = seconds
    } catch {
      // 元数据还没到（duration 未知）时写 currentTime 会抛，交给 pendingSeek 处理
      pendingSeekRef.current = ms
    }
    setCurrentMs(Math.max(0, ms))
  }, [])

  const skip = useCallback((deltaSeconds: number) => {
    const audio = audioRef.current
    const base = (audio?.currentTime ?? currentMs / 1000) * 1000
    seekTo(Math.max(0, Math.min(durationMs > 0 ? durationMs : Number.MAX_SAFE_INTEGER, base + deltaSeconds * 1000)))
  }, [currentMs, durationMs, seekTo])

  const changeChapter = useCallback((next: number, keepPlaying = false) => {
    if (next < 0 || next >= chapters.length || next === chapterIndex) return
    const audio = audioRef.current
    persist(chapterIndex, (audio?.currentTime ?? 0) * 1000)
    setChapterIndex(next)
    if (keepPlaying) playingIntentRef.current = true
  }, [chapters.length, chapterIndex, persist])

  const goTrack = useCallback((next: number, autoPlay: boolean) => {
    if (next < 0 || next >= tracks.length) return
    setTrackIndex(next)
    if (autoPlay) playingIntentRef.current = true
  }, [tracks.length])

  // ---- 音频事件 ----
  const handleLoadedMetadata = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    const ms = Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : 0
    setDurationMs(ms)
    setBuffering(false)
    // 恢复毫秒位置：只做一次，且只在同一章内
    if (!restoredRef.current && pendingSeekRef.current != null) {
      const target = Math.max(0, Math.min(pendingSeekRef.current, ms > 0 ? ms - 1000 : pendingSeekRef.current))
      restoredRef.current = true
      try {
        audio.currentTime = target / 1000
      } catch {
        // 极少数容器不支持精确 seek：退化为从头播，绝不因此报错
      }
      setCurrentMs(Math.max(0, target))
      pendingSeekRef.current = null
    }
    if (playingIntentRef.current) safePlay()
    // 时长回传（服务端不引音频解析库）；顺带带上当前毫秒位置，供 GC 判「听完」
    if (currentTrack && ms > 0) {
      void api.audioDuration(currentTrack.key, ms, Math.round(audio.currentTime * 1000)).catch(() => undefined)
    }
  }, [currentTrack, safePlay])

  const handleTimeUpdate = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    const ms = audio.currentTime * 1000
    setCurrentMs(ms)
    // 节流落库（毫秒进度）
    if (Date.now() - lastSavedRef.current > PROGRESS_SAVE_INTERVAL_MS) persist(chapterIndex, ms)
    // 播放到 70% 预取下一章（G5/G10）：等真放过去时已经就绪。只投递一次
    const total = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration * 1000 : durationMs
    if (total > 0 && ms / total >= NEXT_CHAPTER_PREFETCH_RATIO && !prefetchedNextRef.current.has(chapterIndex)) {
      prefetchedNextRef.current.add(chapterIndex)
      if (chapters[chapterIndex + 1]) {
        void api.audioPrefetch(openBook.details.sourceId, openBook.bookUrl, chapterIndex + 1, 0).catch(() => undefined)
      }
    }
  }, [chapterIndex, chapters, durationMs, openBook.details.sourceId, openBook.bookUrl, persist])

  const handleEnded = useCallback(() => {
    // 一章多分段 ⇒ 段内先串播，最后一段结束才轮到换章（G-3：服务端不拼流）
    if (trackIndex < tracks.length - 1) {
      goTrack(trackIndex + 1, true)
      return
    }
    persist(chapterIndex, durationMs)
    if (stopAtChapterEnd) {
      setStopAtChapterEnd(false)
      playingIntentRef.current = false
      setPlaying(false)
      return
    }
    if (settings.autoNextChapter && chapters[chapterIndex + 1]) {
      changeChapter(chapterIndex + 1, true)
      return
    }
    playingIntentRef.current = false
    setPlaying(false)
  }, [trackIndex, tracks.length, durationMs, stopAtChapterEnd, settings.autoNextChapter, chapters, chapterIndex, goTrack, persist, changeChapter])

  const handleTrackError = useCallback((key: string) => {
    setFailedKeys(prev => new Set(prev).add(key))
    setBuffering(false)
  }, [])

  // ---- 媒体会话（锁屏 / 耳机线控 / 车机） ----
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    const session = navigator.mediaSession
    try {
      const artwork = openBook.details.coverUrl ? [{ src: openBook.details.coverUrl }] : []
      if (typeof MediaMetadata !== 'undefined') {
        session.metadata = new MediaMetadata({
          title: chapter?.title ?? openBook.details.name,
          artist: openBook.details.name,
          album: openBook.details.author ?? '',
          artwork,
        })
      }
    } catch {
      // 元数据失败不影响播放
    }
    const handlers: Array<[MediaSessionAction, MediaSessionActionHandler | null]> = [
      ['play', () => safePlay()],
      ['pause', () => { playingIntentRef.current = false; audioRef.current?.pause() }],
      ['seekbackward', details => skip(-(details.seekOffset ?? settings.skipSeconds))],
      ['seekforward', details => skip(details.seekOffset ?? settings.skipSeconds)],
      ['previoustrack', () => (trackIndex > 0 ? goTrack(trackIndex - 1, true) : changeChapter(chapterIndex - 1, true))],
      ['nexttrack', () => (trackIndex < tracks.length - 1 ? goTrack(trackIndex + 1, true) : changeChapter(chapterIndex + 1, true))],
      ['seekto', details => { if (typeof details.seekTime === 'number') seekTo(details.seekTime * 1000) }],
    ]
    handlers.forEach(([action, handler]) => {
      try {
        session.setActionHandler(action, handler)
      } catch {
        // 浏览器不支持某个 action 时直接跳过（部分实现会抛）
      }
    })
    return () => {
      handlers.forEach(([action]) => {
        try {
          session.setActionHandler(action, null)
        } catch {
          // 忽略
        }
      })
    }
  }, [chapter?.title, openBook.details.name, openBook.details.author, openBook.details.coverUrl, safePlay, skip, settings.skipSeconds, trackIndex, tracks.length, chapterIndex, goTrack, changeChapter, seekTo])

  // 锁屏进度条：只在有真实时长时上报（否则 Chrome 会报 InvalidStateError）
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    if (durationMs <= 0 || typeof navigator.mediaSession.setPositionState !== 'function') return
    try {
      navigator.mediaSession.setPositionState({
        duration: durationMs / 1000,
        position: Math.min(Math.max(currentMs / 1000, 0), durationMs / 1000),
        playbackRate: settings.rate,
      })
    } catch {
      // 不支持的浏览器静默忽略
    }
  }, [currentMs, durationMs, settings.rate])

  // 倍速：改 settings 后同步到 audio 元素
  useEffect(() => {
    const audio = audioRef.current
    if (audio) audio.playbackRate = settings.rate
  }, [settings.rate, trackIndex])

  // 换段/换章：保持"本来在播"的意图（`src` 变化会重置元素）
  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    audio.playbackRate = settings.rate
    if (playingIntentRef.current) safePlay()
  }, [trackIndex, chapterIndex, currentTrack?.key, safePlay, settings.rate])

  // 睡眠定时倒计时
  useEffect(() => {
    if (sleepRemainingMs <= 0) return
    const timer = window.setInterval(() => {
      setSleepRemainingMs(prev => {
        const next = prev - 1000
        if (next <= 0) {
          playingIntentRef.current = false
          audioRef.current?.pause()
          return 0
        }
        return next
      })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [sleepRemainingMs])

  // 离开即释放：暂停 + 落一次进度（Non-Goal：不做全局常驻播放条，退出就该停）
  useEffect(() => {
    return () => {
      const audio = audioRef.current
      if (audio) {
        persist(chapterIndex, audio.currentTime * 1000)
        audio.pause()
      }
    }
    // 只在卸载时执行：chapterIndex 变化不该触发"暂停"
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleClose = useCallback(() => {
    persist(chapterIndex, (audioRef.current?.currentTime ?? 0) * 1000)
    onClose()
  }, [chapterIndex, persist, onClose])

  const coverUrl = openBook.details.coverUrl
  const rateLabel = `${Number.isInteger(settings.rate) ? settings.rate.toFixed(1) : settings.rate}x`
  const sleepLabel = sleepRemainingMs > 0 ? formatTime(sleepRemainingMs) : stopAtChapterEnd ? t('audiobook.sleepChapterShort', '本章') : ''

  return (
    <main className={`audiobook-workspace theme-${theme}`}>
      {/* 封面模糊底衬：独立图层 + `filter: blur()`，**不用** backdrop-filter（会破坏 fixed 包含块） */}
      {coverUrl && (
        <div className="audiobook-backdrop" aria-hidden="true">
          <img src={coverUrl} alt="" />
        </div>
      )}

      <header className="reader-header audiobook-header">
        <div className="reader-header-left">
          <IconButton label={t('audiobook.back', '返回')} icon="arrowLeft" onClick={handleClose} />
          <IconButton label={t('reader.catalog', '目录')} icon="list" onClick={() => setActiveDrawer(d => (d === 'toc' ? null : 'toc'))} />
        </div>
        <strong className="reader-header-title" title={openBook.details.name}>{openBook.details.name}</strong>
        <div className="reader-header-actions">
          <IconButton label={t('reader.settings', '播放设置')} icon="settings" onClick={() => setActiveDrawer(d => (d === 'settings' ? null : 'settings'))} />
        </div>
      </header>

      {offlineSnapshot && (
        <div className="manga-offline-banner audiobook-offline-banner" role="status">
          <Icon name="cloudDownload" />
          <span>{t('audiobook.offlineBanner', '离线播放：本章用的是本机已缓存的音频，缺失分段会明确标出')}</span>
        </div>
      )}

      <section className="audiobook-body">
        <div className="audiobook-cover-section">
          <div className="audiobook-cover-wrapper">
            {coverUrl
              ? <img className="audiobook-cover" src={coverUrl} alt={openBook.details.name} />
              : <div className="audiobook-cover audiobook-cover-fallback">{openBook.details.name.slice(0, 1)}</div>}
            <div className="audiobook-cover-overlay" />
            <div className={`audiobook-playing ${playing ? 'active' : ''}`} aria-hidden="true">
              <i className="bar" /><i className="bar" /><i className="bar" /><i className="bar" />
            </div>
          </div>
        </div>

        <div className="audiobook-content">
          <div className="audiobook-info">
            <p className="audiobook-episode-title">{chapter?.title ?? t('audiobook.noChapterTitle', '未命名章节')}</p>
            <p className="audiobook-book-title">
              {openBook.details.author ? `${openBook.details.name} · ${openBook.details.author}` : openBook.details.name}
            </p>
            <p className="audiobook-track-info">
              {t('audiobook.trackInfo', {
                current: trackIndex + 1,
                total: Math.max(1, chapterBookTotal),
                ready: chapterReady,
                defaultValue: `第 ${trackIndex + 1}/${Math.max(1, chapterBookTotal)} 段 · 已缓存 ${chapterReady} 段`,
              })}
            </p>
          </div>

          {loading && (
            <div className="reader-loading-container" aria-label={t('audiobook.loading', '正在解析音频地址')}>
              <div className="reader-loading-badge"><span className="reader-loading-spinner-ring" />{t('audiobook.loading', '正在解析音频地址')}</div>
            </div>
          )}

          {!loading && error && (
            <div className="manga-error-box audiobook-error-box">
              <p className="reader-error">{error}</p>
              <div className="manga-error-actions">
                <button type="button" className="subtle-button" onClick={() => setChapterIndex(current => current)}>{t('audiobook.retry', '重试')}</button>
                <button type="button" className="subtle-button" onClick={() => changeChapter(chapterIndex + 1)} disabled={chapterIndex >= chapters.length - 1}>
                  {t('audiobook.nextChapter', '下一章')}
                </button>
              </div>
            </div>
          )}

          {/* 进度 + 控制区**始终渲染**（加载中也保留占位）：
              否则清单到位的一瞬间整块版式会跳一下，且静态渲染断言拿不到控件壳。 */}
          {!error && (
            <>
              <div className="audiobook-progress-section">
                <div className="audiobook-progress-row">
                  <input
                    type="range"
                    className="audiobook-progress"
                    min={0}
                    max={durationMs > 0 ? durationMs : 100}
                    step={200}
                    value={durationMs > 0 ? Math.min(currentMs, durationMs) : 0}
                    disabled={loading || !currentTrack}
                    onChange={event => seekTo(Number(event.target.value))}
                    aria-label={t('audiobook.progress', '播放进度')}
                    style={{ ['--audiobook-progress' as string]: `${progressPercent}%` }}
                  />
                </div>
                <div className="audiobook-time-display">
                  <span>{formatTime(currentMs)}</span>
                  <span>{durationMs > 0 ? formatTime(durationMs) : '--:--'}</span>
                </div>
              </div>

              <div className="audiobook-controls">
                <button type="button" className="audiobook-control small" disabled={loading} onClick={() => (trackIndex > 0 ? goTrack(trackIndex - 1, true) : changeChapter(chapterIndex - 1, true))} aria-label={t('audiobook.prev', '上一章')}>
                  <Icon name="skipBack" />
                </button>
                <button type="button" className="audiobook-control small audiobook-skip" disabled={loading} onClick={() => skip(-settings.skipSeconds)} aria-label={t('audiobook.skipBack', { seconds: settings.skipSeconds, defaultValue: `快退 ${settings.skipSeconds} 秒` })}>
                  <span className="audiobook-skip-value">{settings.skipSeconds}</span>
                  <Icon name="refresh" />
                </button>
                <button type="button" className="audiobook-control main" disabled={loading || !currentTrack} onClick={togglePlay} aria-label={playing ? t('audiobook.pause', '暂停') : t('audiobook.play', '播放')}>
                  <Icon name={playing ? 'pause' : 'play'} />
                </button>
                <button type="button" className="audiobook-control small audiobook-skip forward" disabled={loading} onClick={() => skip(settings.skipSeconds)} aria-label={t('audiobook.skipForward', { seconds: settings.skipSeconds, defaultValue: `快进 ${settings.skipSeconds} 秒` })}>
                  <span className="audiobook-skip-value">{settings.skipSeconds}</span>
                  <Icon name="refresh" />
                </button>
                <button type="button" className="audiobook-control small" disabled={loading} onClick={() => (trackIndex < tracks.length - 1 ? goTrack(trackIndex + 1, true) : changeChapter(chapterIndex + 1, true))} aria-label={t('audiobook.nextChapter', '下一章')}>
                  <Icon name="skipForward" />
                </button>
              </div>

              {buffering && <p className="audiobook-status">{t('audiobook.buffering', '缓冲中…（首次播放需要服务端抓取并缓存）')}</p>}

              {currentTrack && failedKeys.has(currentTrack.key) && (
                <div className="audiobook-track-failed" role="alert">
                  <Icon name="volumeX" />
                  <span>{offlineSnapshot
                    ? t('audiobook.trackMissingOffline', '这一段没有缓存到本机（离线状态下拉不到）。联网后重试即可补上。')
                    : t('audiobook.trackFailed', '这一段抓取失败：服务端已带书源的 Referer/Cookie 代抓；反复失败通常意味着该源需要登录或链接已失效')}</span>
                </div>
              )}

              {/* 真正的播放元素：`src` 指向服务端直出（Range + ETag + immutable） */}
              {currentTrack && (
                <audio
                  ref={audioRef}
                  className="audiobook-audio"
                  src={api.audioUrl(currentTrack.key)}
                  preload="metadata"
                  playsInline
                  onLoadedMetadata={handleLoadedMetadata}
                  onTimeUpdate={handleTimeUpdate}
                  onPlay={() => { setPlaying(true); playingIntentRef.current = true }}
                  onPause={() => setPlaying(false)}
                  onWaiting={() => setBuffering(true)}
                  onPlaying={() => setBuffering(false)}
                  onStalled={() => {
                    setBuffering(true)
                    // 停滞唤醒（照搬 TTS 引擎的守卫）：非暂停状态的停滞要把解码管道踢一下
                    if (playingIntentRef.current) window.setTimeout(() => { if (playingIntentRef.current) safePlay() }, 600)
                  }}
                  onEnded={handleEnded}
                  onError={() => handleTrackError(currentTrack.key)}
                />
              )}
            </>
          )}
        </div>
      </section>

      <footer className="audiobook-bottom-controls">
        <button type="button" className="audiobook-feature" onClick={() => setSpeedOpen(true)}>
          <span className="audiobook-feature-value">{rateLabel}</span>
          <span>{t('audiobook.speed', '倍速')}</span>
        </button>
        <button
          type="button"
          className={`audiobook-feature ${sleepRemainingMs > 0 || stopAtChapterEnd ? 'active' : ''}`}
          onClick={() => setActiveDrawer(d => (d === 'settings' ? null : 'settings'))}
        >
          <span className="audiobook-feature-value">{sleepLabel || <Icon name="clock" />}</span>
          <span>{t('audiobook.sleepTimer', '定时')}</span>
        </button>
        <button type="button" className={`audiobook-feature ${caching ? 'active' : ''}`} onClick={() => requestCache(0)}>
          <span className="audiobook-feature-value"><Icon name="download" /></span>
          <span>{t('audiobook.cache', '缓存')}</span>
        </button>
        <button type="button" className="audiobook-feature" onClick={() => setActiveDrawer(d => (d === 'toc' ? null : 'toc'))}>
          <span className="audiobook-feature-value"><Icon name="list" /></span>
          <span>{t('reader.toc', '目录')}</span>
        </button>
        <button type="button" className="audiobook-feature" onClick={() => setActiveDrawer(d => (d === 'settings' ? null : 'settings'))}>
          <span className="audiobook-feature-value"><Icon name="settings" /></span>
          <span>{t('reader.settings', '设置')}</span>
        </button>
      </footer>

      {/* 目录抽屉：复用虚拟章节列表（当前章高亮与绿点由组件自己处理） */}
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
          onSelect={index => { changeChapter(index, playing); setActiveDrawer(null) }}
          itemHeight={40}
          overscan={8}
          autoScrollKey={activeDrawer === 'toc' ? 1 : 0}
          cachedUrlsSet={cachedChapters}
        />
      </aside>

      {/* 设置抽屉：直接复用文本阅读器的设置排版（setting-section / page-modes / setting-hint） */}
      <aside className={`reader-drawer reader-drawer-right ${activeDrawer === 'settings' ? 'open' : ''}`} aria-label={t('audiobook.settingsDrawer', '播放设置抽屉')}>
        <header className="drawer-header">
          <div className="drawer-title"><Icon name="settings" /><strong>{t('audiobook.settings', '播放设置')}</strong></div>
          <div className="drawer-header-actions">
            <IconButton label={t('common.close', '关闭')} icon="close" onClick={() => setActiveDrawer(null)} />
          </div>
        </header>
        <div className="drawer-scroll-content">
          <section className="setting-section">
            <span className="setting-label">{t('audiobook.sleepTimer', '定时关闭')}</span>
            <div className="cache-range-btn-grid">
              {SLEEP_MINUTES.map(minutes => (
                <button
                  key={minutes}
                  type="button"
                  className={`cache-range-btn ${sleepRemainingMs > 0 && Math.abs(sleepRemainingMs - minutes * 60000) < 1000 ? 'selected' : ''}`}
                  onClick={() => { setStopAtChapterEnd(false); setSleepRemainingMs(minutes * 60000) }}
                >
                  {t('audiobook.sleepMinutes', { count: minutes, defaultValue: `${minutes} 分钟` })}
                </button>
              ))}
              <button type="button" className={`cache-range-btn ${stopAtChapterEnd ? 'selected' : ''}`} onClick={() => { setSleepRemainingMs(0); setStopAtChapterEnd(true) }}>
                {t('audiobook.sleepChapter', '播完本章')}
              </button>
              <button type="button" className="cache-range-btn" onClick={() => { setSleepRemainingMs(0); setStopAtChapterEnd(false) }}>
                {t('audiobook.sleepCancel', '取消定时')}
              </button>
            </div>
            <small className="setting-hint">
              {sleepRemainingMs > 0
                ? t('audiobook.sleepRemaining', { time: formatTime(sleepRemainingMs), defaultValue: `剩余 ${formatTime(sleepRemainingMs)} 后自动暂停` })
                : stopAtChapterEnd
                  ? t('audiobook.sleepChapterHint', '本章播完后自动暂停')
                  : t('audiobook.sleepHint', '到点自动暂停，避免睡着后一直放')}
            </small>
          </section>

          <section className="setting-section">
            <span className="setting-label">{t('audiobook.skipSeconds', '快进/快退')}</span>
            <div className="page-modes">
              <button type="button" className={settings.skipSeconds === 15 ? 'selected' : ''} onClick={() => updateSettings({ skipSeconds: 15 })}>15s</button>
              <button type="button" className={settings.skipSeconds === 30 ? 'selected' : ''} onClick={() => updateSettings({ skipSeconds: 30 })}>30s</button>
            </div>
            <small className="setting-hint">{t('audiobook.skipHint', '控制条上两个圆形按钮的步长（锁屏/耳机的快进快退同样生效）')}</small>
          </section>

          <section className="setting-section">
            <span className="setting-label">{t('audiobook.continuous', '连续播放')}</span>
            <div className="page-modes">
              <button type="button" className={settings.autoNextChapter ? 'selected' : ''} onClick={() => updateSettings({ autoNextChapter: true })}>
                {t('audiobook.continuousOn', '本章完自动下一章')}
              </button>
              <button type="button" className={!settings.autoNextChapter ? 'selected' : ''} onClick={() => updateSettings({ autoNextChapter: false })}>
                {t('audiobook.continuousOff', '本章完停止')}
              </button>
            </div>
            <small className="setting-hint">{t('audiobook.continuousHint', '一章可能有多个音频分段，分段之间总是连续播放')}</small>
          </section>

          <section className="setting-section">
            <span className="setting-label">{t('audiobook.offlineCache', '离线缓存')}</span>
            <div className="cache-range-btn-grid">
              <button type="button" className="cache-range-btn" title={t('audiobook.cacheCurrentTitle', '缓存当前这一章的全部音频')} onClick={() => requestCache(0)}>
                {t('audiobook.cacheCurrent', '本章')}
              </button>
              <button type="button" className="cache-range-btn" title={t('reader.cacheNext50Title', '缓存当前章节后续 50 章')} onClick={() => requestCache(50)}>
                {t('reader.cacheNext50', '后 50 章')}
              </button>
              <button type="button" className="cache-range-btn" title={t('audiobook.cacheAllTitle', '缓存整本有声书（GC 永不自动淘汰）')} onClick={() => requestCache(chapters.length)}>
                {t('reader.cacheAll', '全本缓存')}
              </button>
            </div>
            <small className="setting-hint">
              {caching
                ? t('audiobook.cacheRunning', '正在后台缓存，目录里绿点表示该章已可离线播放')
                : t('audiobook.cacheHint', {
                  chapters: cachedChapters.size,
                  tracks: bookStats?.trackCount ?? 0,
                  size: bookStats ? `${Math.round(bookStats.totalBytes / 1024 / 1024)} MB` : '0 MB',
                  defaultValue: `已离线 ${cachedChapters.size} 章 · 服务端缓存 ${bookStats?.trackCount ?? 0} 段（${bookStats ? `${Math.round(bookStats.totalBytes / 1024 / 1024)} MB` : '0 MB'}）`,
                })}
            </small>
            {/* 失败如实报出：是"上游连不上"还是"要登录/超限"，用户与排障者都需要这一行 */}
            {prefetchFailure && (
              <small className="setting-hint cache-failure-hint" role="status">
                {t('reader.cacheFailedTracks', {
                  count: prefetchFailure.failed,
                  reason: prefetchFailure.reason || t('common.unknownReason', '未知原因'),
                  defaultValue: `${prefetchFailure.failed} 段音频缓存失败（最近：${prefetchFailure.reason || '未知原因'}），可稍后重试`,
                })}
              </small>
            )}
          </section>
        </div>
      </aside>

      {activeDrawer !== null && <div className="reader-drawer-backdrop" onClick={() => setActiveDrawer(null)} aria-hidden="true" />}

      {/* 倍速弹窗：版式取自参考稿（大号数值 + 滑杆 + 快捷档位）。
          遮罩**复用项目既有的 `.modal-backdrop`**：不新造第二套遮罩实现（复杂度惩罚），
          也天然拿到全站统一的半透明遮罩与动画。 */}
      {speedOpen && (
        <div className="modal-backdrop" onClick={() => setSpeedOpen(false)}>
          <div className={`audiobook-modal theme-${theme}`} onClick={event => event.stopPropagation()} role="dialog" aria-label={t('audiobook.speed', '倍速')}>
            <header className="audiobook-modal-header">
              <strong>{t('audiobook.speedTitle', '播放速度')}</strong>
              <IconButton label={t('common.close', '关闭')} icon="close" onClick={() => setSpeedOpen(false)} />
            </header>
            <div className="audiobook-modal-body">
              <p className="audiobook-rate-value">{settings.rate.toFixed(2)}x</p>
              <input
                type="range"
                className="audiobook-rate-slider"
                min={0.5}
                max={3}
                step={0.05}
                value={settings.rate}
                onChange={event => updateSettings({ rate: Number(event.target.value) })}
                aria-label={t('audiobook.speed', '倍速')}
              />
              <div className="audiobook-rate-presets">
                {RATE_PRESETS.map(rate => (
                  <button
                    key={rate}
                    type="button"
                    className={`cache-range-btn ${Math.abs(settings.rate - rate) < 0.001 ? 'selected' : ''}`}
                    onClick={() => updateSettings({ rate })}
                  >
                    {rate}x
                  </button>
                ))}
              </div>
              <button type="button" className="subtle-button audiobook-rate-reset" onClick={() => updateSettings({ rate: 1 })}>
                {t('audiobook.speedReset', '恢复 1.0x')}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  )
}
