import { api, Chapter, SearchResult } from './api'
import { OpenBook } from './ReaderScreen'

export type SourceHealthStatus = 'idle' | 'checking' | 'valid' | 'vip_restricted' | 'incomplete' | 'error'

export interface ChapterSampleInfo {
  index: number
  title: string
  length: number
  isVipKeyword: boolean
  isTruncated: boolean
}

export interface SourceHealthInspection {
  sourceId: string
  bookUrl: string
  status: SourceHealthStatus
  score: number
  totalChapters?: number
  latestChapterTitle?: string
  checkedChaptersCount: number
  validChaptersCount: number
  vipBlocked: boolean
  avgLateChapterLength?: number
  error?: string
  summaryText: string
  checkedAt?: number
  book?: OpenBook
}

// Regex matching explicit paywall / VIP / anti-crawler / stub patterns
export const VIP_DETECTION_REGEX = /(?:VIP章节|付费章节|本章为付费章节|需购买后阅读|请前往APP阅读|下载.*(?:APP|客户端).*阅读|试读结束|支持正版|订阅后可阅读|扫码阅读|关注微信公众号|防盗章节|防盗锁|充值.*书币|购买本章|登录后继续阅读|本章字数过少|此章节为付费内容|点此继续阅读下一页)/i

// Thresholds for novel chapter length & VIP detection
export const MIN_LEGITIMATE_CHAPTER_LENGTH = 450 // Normal chapters are 1500~4000 chars. Below 450 is almost always a truncated VIP preview/stub.
export const VIP_DROP_RATIO_THRESHOLD = 0.35 // If late chapter length drops to < 35% of early chapter length, it's considered truncated.

export function inspectChapterContent(
  content: string,
  earlyBaselineLength = 0,
  isLateChapter = false
): {
  isVip: boolean
  isValid: boolean
  isTruncated: boolean
  length: number
} {
  const trimmed = content.trim()
  const length = trimmed.length
  if (length === 0) {
    return { isVip: false, isValid: false, isTruncated: true, length: 0 }
  }

  const isVipKeyword = VIP_DETECTION_REGEX.test(trimmed)

  let isTruncated = false
  if (isLateChapter) {
    if (length < MIN_LEGITIMATE_CHAPTER_LENGTH) {
      isTruncated = true
    } else if (earlyBaselineLength >= 1000 && length < earlyBaselineLength * VIP_DROP_RATIO_THRESHOLD) {
      isTruncated = true
    }
  } else {
    if (length < 200) {
      isTruncated = true
    }
  }

  const isVip = isVipKeyword || (isLateChapter && isTruncated)
  const isValid = !isVip && !isTruncated && length >= (isLateChapter ? MIN_LEGITIMATE_CHAPTER_LENGTH : 200)

  return { isVip, isValid, isTruncated, length }
}

export async function inspectSingleSource(
  result: SearchResult,
  alternateSources?: SearchResult[],
  onProgress?: (partial: Partial<SourceHealthInspection>) => void
): Promise<SourceHealthInspection> {
  const base: SourceHealthInspection = {
    sourceId: result.sourceId,
    bookUrl: result.bookUrl,
    status: 'checking',
    score: 0,
    checkedChaptersCount: 0,
    validChaptersCount: 0,
    vipBlocked: false,
    summaryText: '正在探测目录与正文...',
  }
  onProgress?.(base)

  try {
    const details = await api.details(result.sourceId, result.bookUrl)
    const chapters = await api.chapters(result.sourceId, details.tocUrl)
    const totalChapters = chapters.length
    const latestChapterTitle = chapters.at(-1)?.title

    if (totalChapters === 0) {
      const errRes: SourceHealthInspection = {
        ...base,
        status: 'error',
        score: 0,
        totalChapters: 0,
        summaryText: '目录为空',
        error: '目录为空',
      }
      onProgress?.(errRes)
      return errRes
    }

    // 1. Early baseline sample (Chapter 0)
    let earlyBaselineLength = 0
    try {
      const earlyRes = await api.content(result.sourceId, chapters[0].url, result.bookUrl)
      earlyBaselineLength = earlyRes.content ? earlyRes.content.trim().length : 0
    } catch {
      // Ignore early error
    }

    // 2. Late / VIP zone sampling points
    const lateSampleIndices = new Set<number>()
    if (totalChapters > 10) {
      lateSampleIndices.add(Math.floor(totalChapters * 0.5)) // mid-point
    }
    if (totalChapters > 5) {
      lateSampleIndices.add(Math.max(0, totalChapters - 3)) // near-end
    }
    if (totalChapters > 1) {
      lateSampleIndices.add(totalChapters - 1) // latest chapter
    }

    let validLateCount = 0
    let vipHit = false
    let totalSampleChecked = 1 // including early chapter
    let lateLengths: number[] = []

    for (const lateIdx of lateSampleIndices) {
      totalSampleChecked++
      const sampleChapter = chapters[lateIdx]
      if (!sampleChapter) continue

      try {
        const contentRes = await api.content(result.sourceId, sampleChapter.url, result.bookUrl)
        const check = inspectChapterContent(contentRes.content || '', earlyBaselineLength, true)
        lateLengths.push(check.length)
        if (check.isVip) {
          vipHit = true
        } else if (check.isValid) {
          validLateCount++
        }
      } catch {
        // Late chapter fetch failed
        lateLengths.push(0)
        vipHit = true
      }
    }

    const avgLateLength = lateLengths.length > 0
      ? Math.round(lateLengths.reduce((a, b) => a + b, 0) / lateLengths.length)
      : earlyBaselineLength

    const progress = await api.progress(result.sourceId, result.bookUrl).catch(() => undefined)
    const loadedBook: OpenBook = {
      details: {
        ...details,
        name: details.name?.trim() || result.name || '未知书名',
        author: details.author?.trim() || result.author,
        coverUrl: details.coverUrl || result.coverUrl,
        intro: details.intro || result.intro,
        alternateSources: alternateSources?.filter(s => s.sourceId !== result.sourceId || s.bookUrl !== result.bookUrl),
      },
      bookUrl: result.bookUrl,
      chapters,
      progress,
    }

    let status: SourceHealthStatus = 'valid'
    let score = 12000 + (totalChapters * 3) + Math.min(3000, avgLateLength)
    let summaryText = `共 ${totalChapters} 章 · 全本可读 (后段字数充足 均${avgLateLength}字)`

    if (vipHit) {
      status = 'vip_restricted'
      score = 2000 + totalChapters + Math.min(500, avgLateLength)
      summaryText = `共 ${totalChapters} 章 · 后期章节较短/疑似VIP截断 (抽检均${avgLateLength}字)`
    } else if (validLateCount === 0 && totalChapters > 5) {
      status = 'error'
      score = 100
      summaryText = `正文读取异常/后期无内容`
    } else if (totalChapters < 15) {
      status = 'incomplete'
      score = 1000 + totalChapters
      summaryText = `仅 ${totalChapters} 章 · 章节严重缺失`
    }

    const finalResult: SourceHealthInspection = {
      sourceId: result.sourceId,
      bookUrl: result.bookUrl,
      status,
      score,
      totalChapters,
      latestChapterTitle,
      checkedChaptersCount: totalSampleChecked,
      validChaptersCount: validLateCount + (earlyBaselineLength >= 200 ? 1 : 0),
      vipBlocked: vipHit,
      avgLateChapterLength: avgLateLength,
      summaryText,
      checkedAt: Date.now(),
      book: loadedBook,
    }
    onProgress?.(finalResult)
    return finalResult
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : '书源连接失败'
    const failedResult: SourceHealthInspection = {
      sourceId: result.sourceId,
      bookUrl: result.bookUrl,
      status: 'error',
      score: 0,
      checkedChaptersCount: 0,
      validChaptersCount: 0,
      vipBlocked: false,
      error: errorMsg,
      summaryText: `书源异常: ${errorMsg}`,
      checkedAt: Date.now(),
    }
    onProgress?.(failedResult)
    return failedResult
  }
}

export async function inspectAllSourcesConcurrently(
  sources: SearchResult[],
  maxConcurrency = 3,
  onUpdate?: (inspections: Map<string, SourceHealthInspection>) => void,
  isCancelled?: () => boolean
): Promise<Map<string, SourceHealthInspection>> {
  const map = new Map<string, SourceHealthInspection>()

  for (const s of sources) {
    const key = `${s.sourceId}\u0000${s.bookUrl}`
    map.set(key, {
      sourceId: s.sourceId,
      bookUrl: s.bookUrl,
      status: 'idle',
      score: 0,
      checkedChaptersCount: 0,
      validChaptersCount: 0,
      vipBlocked: false,
      summaryText: '待校验',
    })
  }
  onUpdate?.(new Map(map))

  let cursor = 0
  const workers = Array.from({ length: Math.min(maxConcurrency, sources.length) }, async () => {
    while (cursor < sources.length) {
      if (isCancelled?.()) return
      const idx = cursor++
      const source = sources[idx]
      const key = `${source.sourceId}\u0000${source.bookUrl}`

      await inspectSingleSource(source, sources, partial => {
        if (isCancelled?.()) return
        const existing = map.get(key)
        if (existing) {
          map.set(key, { ...existing, ...partial })
          onUpdate?.(new Map(map))
        }
      })
    }
  })

  await Promise.all(workers)
  return map
}
