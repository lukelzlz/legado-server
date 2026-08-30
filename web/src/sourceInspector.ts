import { api, Chapter, SearchResult } from './api'
import { OpenBook } from './ReaderScreen'

export type SourceHealthStatus = 'idle' | 'checking' | 'valid' | 'vip_restricted' | 'incomplete' | 'error'

export interface ChapterSampleInfo {
  index: number
  title: string
  length: number
  isVipKeyword: boolean
  isTruncated: boolean
  isNotice: boolean
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

// Regex identifying notice chapters, author notes, leave requests, speeches, extras or non-main announcements
export const NON_MAIN_CHAPTER_TITLE_REGEX = /(?:请假|请个假|鸽一天|推迟|晚点更|无更|感言|后记|结语|总结|番外说明|重要通知|通知|作者的话|心里话|单章|求月票|求推荐|上架感言|完本感言|完结感言|封推感言|三江感言|设定|附录|公告|推书|读者群|楔子|序言|引言|写在最后)/i

// Regex identifying content text typical of author notices rather than novel story text
export const NOTICE_CONTENT_REGEX = /(?:请假一天|卡文|明天补上|今天无更|身体不适|阳了|发烧|去医院|加班|祝大家|感谢各位读者|新书已发|新书发布|月票榜|加更规则|读者群|请假说明|完本了|写完了)/i

// Thresholds for novel chapter length & VIP detection
export const MIN_LEGITIMATE_CHAPTER_LENGTH = 450 // Normal chapters are 1500~4000 chars. Below 450 is almost always a truncated VIP preview/stub.
export const VIP_DROP_RATIO_THRESHOLD = 0.35 // If late chapter length drops to < 35% of early chapter length, it's considered truncated.

export function isNoticeOrNonMainChapter(title: string, content = ''): boolean {
  if (NON_MAIN_CHAPTER_TITLE_REGEX.test(title)) return true
  const trimmed = content.trim()
  if (trimmed.length < 600 && NOTICE_CONTENT_REGEX.test(trimmed) && !VIP_DETECTION_REGEX.test(trimmed)) {
    return true
  }
  return false
}

export function findMainChapter(chapters: Chapter[], preferredIndex: number): Chapter | undefined {
  if (chapters.length === 0) return undefined
  const safeIdx = Math.max(0, Math.min(preferredIndex, chapters.length - 1))

  // If preferred chapter is not a notice title, use it
  if (!NON_MAIN_CHAPTER_TITLE_REGEX.test(chapters[safeIdx].title)) {
    return chapters[safeIdx]
  }

  // Search backwards first (since leave notes or speeches often appear at the end)
  for (let offset = 1; offset <= 5; offset++) {
    const prevIdx = safeIdx - offset
    if (prevIdx >= 0 && !NON_MAIN_CHAPTER_TITLE_REGEX.test(chapters[prevIdx].title)) {
      return chapters[prevIdx]
    }
  }

  // Search forwards
  for (let offset = 1; offset <= 5; offset++) {
    const nextIdx = safeIdx + offset
    if (nextIdx < chapters.length && !NON_MAIN_CHAPTER_TITLE_REGEX.test(chapters[nextIdx].title)) {
      return chapters[nextIdx]
    }
  }

  return chapters[safeIdx]
}

export function inspectChapterContent(
  title: string,
  content: string,
  earlyBaselineLength = 0,
  isLateChapter = false
): {
  isVip: boolean
  isValid: boolean
  isTruncated: boolean
  isNotice: boolean
  length: number
} {
  const trimmed = content.trim()
  const length = trimmed.length
  if (length === 0) {
    return { isVip: false, isValid: false, isTruncated: true, isNotice: false, length: 0 }
  }

  const isNotice = isNoticeOrNonMainChapter(title, trimmed)
  const isVipKeyword = VIP_DETECTION_REGEX.test(trimmed)

  // If chapter is an author note / leave note / speech, and does NOT have explicit VIP paywall blocks,
  // it is valid notice and should not be penalized as VIP truncation
  if (isNotice && !isVipKeyword) {
    return {
      isVip: false,
      isValid: true,
      isTruncated: false,
      isNotice: true,
      length,
    }
  }

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

  return { isVip, isValid, isTruncated, isNotice, length }
}

export function createInitialInspections(sources: SearchResult[]): Map<string, SourceHealthInspection> {
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
  return map
}

export async function inspectSingleSource(
  result: SearchResult,
  alternateSources?: SearchResult[],
  onProgress?: (partial: Partial<SourceHealthInspection>) => void,
  isCancelled?: () => boolean
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
  if (isCancelled?.()) {
    return { ...base, status: 'idle', summaryText: '待校验' }
  }
  onProgress?.(base)

  try {
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
    const details = await api.details(result.sourceId, result.bookUrl)
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
    const chapters = await api.chapters(result.sourceId, details.tocUrl)
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
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
      if (!isCancelled?.()) {
        onProgress?.(errRes)
      }
      return errRes
    }

    // 1. Find early baseline sample (avoiding notice chapters if possible)
    const earlyChapter = findMainChapter(chapters, 0) || chapters[0]
    let earlyBaselineLength = 0
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
    try {
      const earlyRes = await api.content(result.sourceId, earlyChapter.url, result.bookUrl)
      if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
      const checkEarly = inspectChapterContent(earlyChapter.title, earlyRes.content || '', 0, false)
      if (!checkEarly.isNotice) {
        earlyBaselineLength = checkEarly.length
      }
    } catch {
      // Ignore early error
    }
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }

    // 2. Select late sampling points (automatically filtering out notice/leave chapters)
    const lateTargetIndices = [
      totalChapters > 10 ? Math.floor(totalChapters * 0.5) : null,
      totalChapters > 5 ? Math.max(0, totalChapters - 3) : null,
      totalChapters > 1 ? totalChapters - 1 : null,
    ].filter((v): v is number => v !== null)

    const sampledChapterUrls = new Set<string>()
    if (earlyChapter) sampledChapterUrls.add(earlyChapter.url)

    const lateChaptersToSample: Chapter[] = []
    for (const targetIdx of lateTargetIndices) {
      const candidateChapter = findMainChapter(chapters, targetIdx)
      if (candidateChapter && !sampledChapterUrls.has(candidateChapter.url)) {
        sampledChapterUrls.add(candidateChapter.url)
        lateChaptersToSample.push(candidateChapter)
      }
    }

    let validLateCount = 0
    let vipHit = false
    let totalSampleChecked = 1 // including early chapter
    let lateMainStoryLengths: number[] = []

    for (const sampleChapter of lateChaptersToSample) {
      if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
      totalSampleChecked++
      try {
        const contentRes = await api.content(result.sourceId, sampleChapter.url, result.bookUrl)
        if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
        const check = inspectChapterContent(sampleChapter.title, contentRes.content || '', earlyBaselineLength, true)
        
        if (!check.isNotice) {
          lateMainStoryLengths.push(check.length)
        }

        if (check.isVip) {
          vipHit = true
        } else if (check.isValid) {
          validLateCount++
        }
      } catch {
        if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }
        // Late chapter fetch failed
        lateMainStoryLengths.push(0)
        vipHit = true
      }
    }

    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }

    const avgLateLength = lateMainStoryLengths.length > 0
      ? Math.round(lateMainStoryLengths.reduce((a, b) => a + b, 0) / lateMainStoryLengths.length)
      : earlyBaselineLength

    const progress = await api.progress(result.sourceId, result.bookUrl).catch(() => undefined)
    if (isCancelled?.()) return { ...base, status: 'idle', summaryText: '待校验' }

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
    if (!isCancelled?.()) {
      onProgress?.(finalResult)
    }
    return finalResult
  } catch (error) {
    if (isCancelled?.()) {
      return { ...base, status: 'idle', summaryText: '待校验' }
    }
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
    if (!isCancelled?.()) {
      onProgress?.(failedResult)
    }
    return failedResult
  }
}

export async function inspectAllSourcesConcurrently(
  sources: SearchResult[],
  maxConcurrency = 3,
  onUpdate?: (inspections: Map<string, SourceHealthInspection>) => void,
  isCancelled?: () => boolean,
  initialInspections?: Map<string, SourceHealthInspection>
): Promise<Map<string, SourceHealthInspection>> {
  const map = new Map<string, SourceHealthInspection>(initialInspections)

  for (const s of sources) {
    const key = `${s.sourceId}\u0000${s.bookUrl}`
    if (!map.has(key)) {
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
  }
  if (!isCancelled?.()) {
    onUpdate?.(new Map(map))
  }

  let cursor = 0
  const workers = Array.from({ length: Math.min(maxConcurrency, sources.length) }, async () => {
    while (cursor < sources.length) {
      if (isCancelled?.()) return
      const idx = cursor++
      const source = sources[idx]
      const key = `${source.sourceId}\u0000${source.bookUrl}`

      await inspectSingleSource(
        source,
        sources,
        partial => {
          if (isCancelled?.()) return
          const existing = map.get(key)
          if (existing) {
            map.set(key, { ...existing, ...partial })
            onUpdate?.(new Map(map))
          }
        },
        isCancelled
      )
      if (isCancelled?.()) return
    }
  })

  await Promise.all(workers)
  return map
}
