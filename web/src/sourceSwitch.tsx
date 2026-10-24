// 与仓库里其它组件一致地引入 React：测试用 tsx 跑的是 classic JSX 变换
// （会找 `React` 这个标识符），而 `web/tsconfig.json` 是 automatic 变换。
// 少这一行会让 `renderToStaticMarkup` 抛 `ReferenceError: React is not defined`。
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, BookDetails, Chapter, SearchResult, SearchStreamEvent, streamSearch } from './api'
import { Icon } from './icons'
import { cleanAuthor, cleanTitle } from './searchFilters'
import { matchBestChapter, normalizeChapterTitle, remapChapterAfterSourceSwitch } from './sourceSwitchRemap'
import { ButtonSpinner } from './ButtonSpinner'
import { toast } from './Toast'

/**
 * 换源（书源切换）的**单一实现**：弹窗 + 候选源编排 + 换源后的书籍落库。
 *
 * ## 为什么要有这个模块
 * 小说阅读器原先自带一份换源实现，漫画 / 有声阅读器也要"像小说的那个一样"。
 * 若各自再写一份，就会出现三份**候选源排序**与**重映射规则**——它们在"看起来没问题"时
 * 完全一致，只在边界（同名书、序号错位、目录更短）上分叉，属于最难查的一类缺陷。
 * 因此这里收口成一处，三端只是**接线方式**不同（换源后重新拉什么清单、清什么缓存）。
 *
 * ## 分层
 * - `sourceSwitchRemap.ts` —— 纯函数（重映射规则），可单测、无副作用；
 * - 本文件 —— 弹窗 UI + 编排（搜索 / 拉目录 / 落库 / 提示）；
 * - 各阅读器 —— 只负责"换源之后我这边要做什么"（正文 / 图片清单 / 音频清单）。
 */

export type SourceCandidate = {
  result: SearchResult
  isKnownAlternate?: boolean
  isCurrent?: boolean
  status: 'idle' | 'loading' | 'loaded' | 'error'
  latestChapter?: string
  totalChapters?: number
  matchedChapterIndex?: number
  matchedChapterTitle?: string
  chapters?: Chapter[]
  error?: string
}

export type SourceSwitchModalProps = {
  bookName: string
  author?: string
  currentSourceId: string
  currentBookUrl: string
  currentChapterTitle?: string
  currentChapterIndex?: number
  knownAlternateSources?: SearchResult[]
  onSwitch: (chosen: { result: SearchResult; chapters: Chapter[]; targetChapterIndex: number }) => Promise<void>
  onClose: () => void
}

export function SourceSwitchModal({
  bookName,
  author,
  currentSourceId,
  currentBookUrl,
  currentChapterTitle,
  currentChapterIndex,
  knownAlternateSources = [],
  onSwitch,
  onClose,
}: SourceSwitchModalProps) {
  const { t } = useTranslation()
  const [candidates, setCandidates] = useState<SourceCandidate[]>([])
  const [searchProgress, setSearchProgress] = useState<SearchStreamEvent | null>(null)
  const [searching, setSearching] = useState(true)
  const [switchingSourceId, setSwitchingSourceId] = useState<string | null>(null)

  const socketRef = useRef<WebSocket | null>(null)
  const fetchingTocRef = useRef<Set<string>>(new Set())

  const fetchCandidateToc = useCallback(async (candidate: SourceCandidate) => {
    const key = `${candidate.result.sourceId}\u0000${candidate.result.bookUrl}`
    if (fetchingTocRef.current.has(key)) return
    fetchingTocRef.current.add(key)

    setCandidates(prev =>
      prev.map(c =>
        c.result.sourceId === candidate.result.sourceId && c.result.bookUrl === candidate.result.bookUrl
          ? { ...c, status: 'loading', error: undefined }
          : c
      )
    )

    try {
      const details = await api.details(candidate.result.sourceId, candidate.result.bookUrl)
      const chapters = await api.chapters(candidate.result.sourceId, details.tocUrl)
      const latest = chapters.at(-1)?.title
      const best = matchBestChapter(chapters, currentChapterTitle, currentChapterIndex)

      setCandidates(prev =>
        prev.map(c =>
          c.result.sourceId === candidate.result.sourceId && c.result.bookUrl === candidate.result.bookUrl
            ? {
                ...c,
                status: 'loaded',
                chapters,
                totalChapters: chapters.length,
                latestChapter: latest,
                matchedChapterIndex: best.index,
                matchedChapterTitle: best.title,
              }
            : c
        )
      )
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : t('source.unableToGetToc', '无法获取目录')
      setCandidates(prev =>
        prev.map(c =>
          c.result.sourceId === candidate.result.sourceId && c.result.bookUrl === candidate.result.bookUrl
            ? { ...c, status: 'error', error: errorMsg }
            : c
        )
      )
    } finally {
      fetchingTocRef.current.delete(key)
    }
  }, [currentChapterIndex, currentChapterTitle, t])

  // Initialize known alternates
  useEffect(() => {
    const initialList: SourceCandidate[] = []

    // 1. Current source (if available)
    initialList.push({
      result: {
        sourceId: currentSourceId,
        bookUrl: currentBookUrl,
        name: bookName,
        author,
      },
      isCurrent: true,
      status: 'idle',
    })

    // 2. Known alternate sources
    for (const alt of knownAlternateSources) {
      if (alt.sourceId === currentSourceId && alt.bookUrl === currentBookUrl) continue
      initialList.push({
        result: alt,
        isKnownAlternate: true,
        status: 'idle',
      })
    }

    setCandidates(initialList)

    // Trigger TOC fetch for known alternates
    initialList.forEach(cand => {
      if (!cand.isCurrent) {
        void fetchCandidateToc(cand)
      }
    })
  }, [author, bookName, currentBookUrl, currentSourceId, fetchCandidateToc, knownAlternateSources])

  // Start streaming search for other sources
  useEffect(() => {
    let cancelled = false
    setSearching(true)

    const socket = streamSearch(
      bookName,
      undefined,
      event => {
        if (cancelled) return
        if (event.type === 'start' || event.type === 'progress' || event.type === 'done') {
          setSearchProgress(event)
        }
        if (event.type === 'results' && event.results.length > 0) {
          setCandidates(prev => {
            const existingKeys = new Set(prev.map(c => `${c.result.sourceId}\u0000${c.result.bookUrl}`))
            const newCandidates: SourceCandidate[] = []

            for (const r of event.results) {
              const key = `${r.sourceId}\u0000${r.bookUrl}`
              // Filter by book name similarity
              if (
                !existingKeys.has(key) &&
                r.name.trim().toLowerCase() === bookName.trim().toLowerCase()
              ) {
                existingKeys.add(key)
                const candidate: SourceCandidate = {
                  result: r,
                  status: 'idle',
                }
                newCandidates.push(candidate)
                // Automatically fetch TOC for newly found candidates
                void fetchCandidateToc(candidate)
              }
            }

            return newCandidates.length > 0 ? [...prev, ...newCandidates] : prev
          })
        }
        if (event.type === 'done' || event.type === 'error') {
          setSearching(false)
        }
      },
      () => {
        if (!cancelled) setSearching(false)
      },
      () => {
        if (!cancelled) setSearching(false)
      }
    )

    socketRef.current = socket

    return () => {
      cancelled = true
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [bookName, fetchCandidateToc])

  const handleSelect = async (candidate: SourceCandidate) => {
    if (candidate.isCurrent) {
      toast.info(t('source.alreadyCurrentSource', '当前已是该书源'))
      return
    }

    setSwitchingSourceId(candidate.result.sourceId)

    try {
      let chapters = candidate.chapters
      let targetIndex = candidate.matchedChapterIndex ?? 0

      if (!chapters) {
        const details = await api.details(candidate.result.sourceId, candidate.result.bookUrl)
        chapters = await api.chapters(candidate.result.sourceId, details.tocUrl)
        const best = matchBestChapter(chapters, currentChapterTitle, currentChapterIndex)
        targetIndex = best.index
      }

      await onSwitch({
        result: candidate.result,
        chapters,
        targetChapterIndex: targetIndex,
      })

      const targetTitle = chapters[targetIndex]?.title || t('reader.chapterIndex', { index: targetIndex + 1, defaultValue: `第 ${targetIndex + 1} 章` })
      toast.success(t('source.switchSourceSuccess', { source: candidate.result.sourceId, title: targetTitle, defaultValue: `已切换至【${candidate.result.sourceId}】，定位至：${targetTitle}` }))
      onClose()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('source.switchSourceFailed', '切换书源失败'))
    } finally {
      setSwitchingSourceId(null)
    }
  }

  const sortedCandidates = useMemo(() => {
    return [...candidates].sort((a, b) => {
      // 1. Current source first
      if (a.isCurrent) return -1
      if (b.isCurrent) return 1
      // 2. Known alternate sources second
      if (a.isKnownAlternate && !b.isKnownAlternate) return -1
      if (!a.isKnownAlternate && b.isKnownAlternate) return 1
      // 3. Loaded sources with more chapters first
      if (a.status === 'loaded' && b.status !== 'loaded') return -1
      if (a.status !== 'loaded' && b.status === 'loaded') return 1
      if (a.totalChapters && b.totalChapters) return b.totalChapters - a.totalChapters
      return 0
    })
  }, [candidates])

  const progressPercent = searchProgress?.totalSources
    ? Math.round((searchProgress.completedSources / searchProgress.totalSources) * 100)
    : 0

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="source-switch-dialog"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('source.switchSourceForBook', { name: bookName, defaultValue: `切换书源: ${bookName}` })}
      >
        <header className="source-switch-header">
          <div>
            <div className="source-switch-kicker">
              <Icon name="sliders" />
              <span>{t('source.switchSourceTitle', '切换书源')}</span>
            </div>
            <h2>{bookName}</h2>
            <small>{author ? `${t('library.author', '作者')}: ${author}` : t('library.unknownAuthor', '未知作者')} · {t('source.currentSourceBadge', '当前书源')}：{currentSourceId}</small>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        {/* Search Stream Progress Banner */}
        <div className="source-search-banner">
          <div className="search-banner-text">
            <span>
              {searching ? (
                <>{t('source.searchingSourcesForBook', { name: bookName, defaultValue: `正在全源检索《${bookName}》...` })}</>
              ) : (
                <>{t('source.searchSourcesCompleted', { count: candidates.length, defaultValue: `全源检索完成，共找到 ${candidates.length} 个书源` })}</>
              )}
            </span>
            <small>
              {searchProgress
                ? t('source.scannedSourcesProgress', { completed: searchProgress.completedSources, total: searchProgress.totalSources, defaultValue: `已扫描 ${searchProgress.completedSources} / ${searchProgress.totalSources} 个书源` })
                : t('source.preparingSearch', '准备检索...')}
            </small>
          </div>
          {searching && (
            <div className="search-progress-bar-track">
              <div
                className="search-progress-bar-fill"
                style={{ width: `${Math.max(5, progressPercent)}%` }}
              />
            </div>
          )}
        </div>

        {/* Candidate List */}
        <div className="source-candidate-list">
          {sortedCandidates.map(c => {
            const isSwitching = switchingSourceId === c.result.sourceId
            const isLoading = c.status === 'loading'
            const isError = c.status === 'error'
            const isCurrent = c.isCurrent

            let metaText = t('source.clickToFetchTocAndSwitch', '点击拉取目录并切换')
            if (isCurrent) {
              metaText = t('source.currentReadingSource', '当前正在阅读的书源')
            } else if (isLoading) {
              metaText = t('source.extractingTocAndChapters', '正在提取目录与章节...')
            } else if (isError) {
              metaText = c.error || t('source.unableToGetToc', '获取目录失败')
            } else if (c.status === 'loaded') {
              metaText = t('source.totalChaptersAndLatest', { count: c.totalChapters ?? 0, latest: c.latestChapter || t('common.none', '无'), defaultValue: `共 ${c.totalChapters ?? 0} 章 · 最新：${c.latestChapter || '无'}` })
            }

            return (
              <article
                key={`${c.result.sourceId}-${c.result.bookUrl}`}
                className={`source-candidate-card ${isCurrent ? 'current' : ''} ${isError ? 'error' : ''}`}
              >
                <div className="candidate-main">
                  <div className="candidate-title-row">
                    <strong>{c.result.sourceId}</strong>
                    {isCurrent && <span className="source-badge current">{t('source.currentSourceBadge', '当前书源')}</span>}
                    {c.isKnownAlternate && !isCurrent && (
                      <span className="source-badge alternate">{t('source.candidateBadge', '已收录候选')}</span>
                    )}
                  </div>
                  <p className="candidate-meta">{metaText}</p>
                  {c.matchedChapterTitle && !isCurrent && (
                    <small className="candidate-match">
                      {t('source.smartMatchedPosition', { index: (c.matchedChapterIndex ?? 0) + 1, title: c.matchedChapterTitle, defaultValue: `智能定位至：第 ${(c.matchedChapterIndex ?? 0) + 1} 章 · ${c.matchedChapterTitle}` })}
                    </small>
                  )}
                </div>

                <div className="candidate-action">
                  {isCurrent ? (
                    <span className="current-indicator">{t('source.inUse', '使用中')}</span>
                  ) : (
                    <button
                      className={`primary-button switch-btn ${isSwitching ? 'is-loading' : ''}`}
                      disabled={isSwitching || switchingSourceId !== null}
                      aria-busy={isSwitching}
                      onClick={() => void handleSelect(c)}
                    >
                      {isSwitching && <ButtonSpinner size={12} />}
                      {isSwitching ? t('source.switching', '切换中...') : t('source.switch', '切换')}
                    </button>
                  )}
                </div>
              </article>
            )
          })}

          {sortedCandidates.length === 0 && !searching && (
            <div className="source-switch-empty">
              <Icon name="book" />
              <p>{t('source.noMatchingBookFound', { name: bookName, defaultValue: `未在其他书源中发现匹配的《${bookName}》` })}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/* -------------------------------------------------------------------------- */
/*                          换源后的"落库"编排                                  */
/* -------------------------------------------------------------------------- */

export type SwitchBookSourceChosen = {
  result: SearchResult
  chapters: Chapter[]
  targetChapterIndex: number
}

/**
 * `switchBookSource` 需要的最小书籍形状。
 *
 * 刻意**不**引用 `ReaderScreen` 的 `OpenBook`：`ReaderScreen` 会 import 本模块，
 * 反向再 import 它的类型会形成循环（类型层面无害，但会让"谁依赖谁"变得难读）。
 * 这里只声明真正读到的字段，三端现有的 `OpenBook` 都天然满足。
 */
export type SourceSwitchBook = {
  details: BookDetails
  bookUrl: string
  /** 原书 kind（漫画/有声分派靠它，可选）。 */
  bookKind?: string
}

/** 换源落库的结果：调用方拿它替换自己持有的"当前书籍"。 */
export type SwitchBookSourceResult = {
  /** 更换书源后的书籍（`details` / `bookUrl` / `chapters` 已指向新源）。 */
  book: SourceSwitchBook & { chapters: Chapter[] }
  /** 新目录里的落点下标（由纯函数算出）。 */
  targetChapterIndex: number
  /** 旧源的标识，调用方用它清理"按 [sourceId, bookUrl] 建键"的本地缓存。 */
  previous: { sourceId: string; bookUrl: string }
}

/**
 * 换源的**共用编排**：重新解析新源详情 → 写回书架 → 返回新的当前书籍。
 *
 * 与抽取前 `ReaderScreen.handleSwitchSource` 的行为逐字一致（含封面回退顺序、
 * 书名/作者清洗、`alternateSources` 保留），因此小说阅读器只是把这段搬了个家。
 *
 * 各阅读器的**差异部分**不在函数里，而在调用方：
 * 换源成功后要重新拉什么清单（正文 / 图片 / 音频）、要清掉什么旧源缓存。
 *
 * @param current 换源前的书籍（只需要 `details` 与 `bookUrl`）
 * @param chosen  弹窗选定的候选源（含已拉取的新目录与落点下标）
 * @param unknownName 拿不到书名时的兜底文案（已翻译）
 */
export async function switchBookSource(
  current: SourceSwitchBook,
  chosen: SwitchBookSourceChosen,
  unknownName: string,
): Promise<SwitchBookSourceResult> {
  const previous = { sourceId: current.details.sourceId, bookUrl: current.bookUrl }
  const details = await api.details(chosen.result.sourceId, chosen.result.bookUrl)
  const fallbackCover = details.coverUrl?.trim() ||
    chosen.result.coverUrl?.trim() ||
    current.details.coverUrl?.trim() ||
    current.details.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim()

  const safeDetails: BookDetails = {
    ...details,
    name: cleanTitle(details.name?.trim() || current.details.name || unknownName) || current.details.name,
    author: cleanAuthor(details.author?.trim() || current.details.author) || current.details.author,
    coverUrl: fallbackCover || undefined,
    intro: details.intro || current.details.intro,
    bookKind: details.bookKind || chosen.result.bookKind || current.bookKind,
    alternateSources: current.details.alternateSources,
  }

  await api.switchBookshelfSource({
    oldSourceId: previous.sourceId,
    oldBookUrl: previous.bookUrl,
    book: {
      sourceId: safeDetails.sourceId,
      bookUrl: chosen.result.bookUrl,
      name: safeDetails.name,
      author: safeDetails.author,
      tocUrl: safeDetails.tocUrl,
      coverUrl: safeDetails.coverUrl,
    },
    alternateSources: current.details.alternateSources,
  })

  return {
    book: {
      details: safeDetails,
      bookUrl: chosen.result.bookUrl,
      chapters: chosen.chapters,
      bookKind: safeDetails.bookKind,
    },
    targetChapterIndex: chosen.targetChapterIndex,
    previous,
  }
}

/**
 * 换源后的落点提示。
 *
 * 标题完全匹配不到时会**静默**停在章节序号处——用户只看到"怎么跳到这一章了"。
 * 这里补一句提示，把"为什么是这个位置"讲清楚。找到匹配时不打扰（正常流程）。
 */
export function notifySourceSwitchFallback(
  remapped: { matched: boolean },
  t: (key: string, options?: Record<string, unknown>) => string,
): void {
  if (remapped.matched) return
  toast.info(t('reader.switchSourceNearestPosition', { defaultValue: '新书源目录没有同名章节，已定位到最接近的位置' }))
}

/** 供 `remapChapterAfterSourceSwitch` 复用的再导出，避免调用方多引一个模块。 */
export { matchBestChapter, normalizeChapterTitle, remapChapterAfterSourceSwitch }
export type { SourceRemapResult } from './sourceSwitchRemap'
