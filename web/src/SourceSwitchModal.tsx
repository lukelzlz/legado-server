import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, Chapter, SearchResult, SearchStreamEvent, streamSearch } from './api'
import { Icon } from './icons'
import { toast } from './Toast'

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

const normalizeTitle = (title: string): string =>
  title
    .replace(/^第[0-9一二三四五六七八九十百千万]+[章回节集卷]\s*/gu, '')
    .replace(/^chapter\s*[0-9]+/iu, '')
    .replace(/[\s\p{P}\p{S}]/gu, '')
    .toLowerCase()

export function matchBestChapter(
  chapters: Chapter[],
  currentTitle?: string,
  currentIndex?: number
): { index: number; title: string } {
  if (chapters.length === 0) return { index: 0, title: '' }

  if (currentTitle) {
    const normCurrent = normalizeTitle(currentTitle)
    if (normCurrent.length >= 2) {
      // 1. Exact match on raw title
      const exact = chapters.find(c => c.title.trim() === currentTitle.trim())
      if (exact) return { index: exact.index, title: exact.title }

      // 2. Exact match on normalized title
      const normExact = chapters.find(c => normalizeTitle(c.title) === normCurrent)
      if (normExact) return { index: normExact.index, title: normExact.title }

      // 3. Substring inclusion
      const substr = chapters.find(c => {
        const norm = normalizeTitle(c.title)
        return norm.length >= 2 && (norm.includes(normCurrent) || normCurrent.includes(norm))
      })
      if (substr) return { index: substr.index, title: substr.title }
    }
  }

  // Fallback to index
  const safeIdx = Math.min(Math.max(0, currentIndex ?? 0), chapters.length - 1)
  return { index: safeIdx, title: chapters[safeIdx]?.title || '' }
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
      const errorMsg = err instanceof Error ? err.message : '无法获取目录'
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
  }, [currentChapterIndex, currentChapterTitle])

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
      toast.info('当前已是该书源')
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

      const targetTitle = chapters[targetIndex]?.title || `第 ${targetIndex + 1} 章`
      toast.success(`已切换至【${candidate.result.sourceId}】，定位至：${targetTitle}`)
      onClose()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '切换书源失败')
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
        aria-label={`切换书源: ${bookName}`}
      >
        <header className="source-switch-header">
          <div>
            <div className="source-switch-kicker">
              <Icon name="sliders" />
              <span>切换书源</span>
            </div>
            <h2>{bookName}</h2>
            <small>{author ? `作者: ${author}` : '未知作者'} · 当前：{currentSourceId}</small>
          </div>
          <button className="subtle-button close-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>

        {/* Search Stream Progress Banner */}
        <div className="source-search-banner">
          <div className="search-banner-text">
            <span>
              {searching ? (
                <>正在全源检索《{bookName}》...</>
              ) : (
                <>全源检索完成，共找到 {candidates.length} 个书源</>
              )}
            </span>
            <small>
              {searchProgress
                ? `已扫描 ${searchProgress.completedSources} / ${searchProgress.totalSources} 个书源`
                : '准备检索...'}
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

            let metaText = '点击拉取目录并切换'
            if (isCurrent) {
              metaText = '当前正在阅读的书源'
            } else if (isLoading) {
              metaText = '正在提取目录与章节...'
            } else if (isError) {
              metaText = c.error || '获取目录失败'
            } else if (c.status === 'loaded') {
              metaText = `共 ${c.totalChapters ?? 0} 章 · 最新：${c.latestChapter || '无'}`
            }

            return (
              <article
                key={`${c.result.sourceId}-${c.result.bookUrl}`}
                className={`source-candidate-card ${isCurrent ? 'current' : ''} ${isError ? 'error' : ''}`}
              >
                <div className="candidate-main">
                  <div className="candidate-title-row">
                    <strong>{c.result.sourceId}</strong>
                    {isCurrent && <span className="source-badge current">当前书源</span>}
                    {c.isKnownAlternate && !isCurrent && (
                      <span className="source-badge alternate">已收录候选</span>
                    )}
                  </div>
                  <p className="candidate-meta">{metaText}</p>
                  {c.matchedChapterTitle && !isCurrent && (
                    <small className="candidate-match">
                      智能定位至：第 {(c.matchedChapterIndex ?? 0) + 1} 章 · {c.matchedChapterTitle}
                    </small>
                  )}
                </div>

                <div className="candidate-action">
                  {isCurrent ? (
                    <span className="current-indicator">使用中</span>
                  ) : (
                    <button
                      className="primary-button switch-btn"
                      disabled={isSwitching}
                      onClick={() => void handleSelect(c)}
                    >
                      {isSwitching ? '切换中...' : '切换'}
                    </button>
                  )}
                </div>
              </article>
            )
          })}

          {sortedCandidates.length === 0 && !searching && (
            <div className="source-switch-empty">
              <Icon name="book" />
              <p>未在其他书源中发现匹配的《{bookName}》</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
