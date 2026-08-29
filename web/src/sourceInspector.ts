import { api, Chapter, SearchResult } from './api'
import { OpenBook } from './ReaderScreen'

export type SourceHealthStatus = 'idle' | 'checking' | 'valid' | 'vip_restricted' | 'incomplete' | 'error'

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
  error?: string
  summaryText: string
  checkedAt?: number
  book?: OpenBook
}

// Regex matching common paywall / VIP / anti-crawler stub patterns
export const VIP_DETECTION_REGEX = /(?:VIP章节|付费章节|本章为付费章节|需购买后阅读|请前往APP阅读|下载.*(?:APP|客户端).*阅读|试读结束|支持正版|订阅后可阅读|扫码阅读|关注微信公众号|防盗章节|防盗锁|充值.*书币|购买本章|登录后继续阅读|本章字数过少|此章节为付费内容)/i

export function inspectChapterContent(content: string): { isVip: boolean; isValid: boolean; length: number } {
  const trimmed = content.trim()
  const length = trimmed.length
  if (length === 0) {
    return { isVip: false, isValid: false, length: 0 }
  }

  const isVip = VIP_DETECTION_REGEX.test(trimmed)
  const isValid = !isVip && length >= 250

  return { isVip, isValid, length }
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

    // Multi-point chapter sampling: early, mid, late/VIP
    const sampleIndices = new Set<number>()
    sampleIndices.add(0)
    if (totalChapters > 10) {
      sampleIndices.add(Math.floor(totalChapters * 0.5))
    }
    if (totalChapters > 3) {
      sampleIndices.add(Math.max(0, totalChapters - 2))
    }
    const sampleArray = Array.from(sampleIndices).map(idx => chapters[idx]).filter(Boolean)

    let validCount = 0
    let vipHit = false
    let totalSampleChecked = 0

    for (const sampleChapter of sampleArray) {
      totalSampleChecked++
      try {
        const contentRes = await api.content(result.sourceId, sampleChapter.url, result.bookUrl)
        const check = inspectChapterContent(contentRes.content || '')
        if (check.isVip) {
          vipHit = true
        } else if (check.isValid) {
          validCount++
        }
      } catch {
        // Sample chapter error
      }
    }

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
    let score = 10000 + (totalChapters * 2) + (validCount * 500)
    let summaryText = `共 ${totalChapters} 章 · 全本可读 (${validCount}章抽检通过)`

    if (vipHit) {
      status = 'vip_restricted'
      score = 2000 + totalChapters
      summaryText = `共 ${totalChapters} 章 · 后续含VIP/收费拦截`
    } else if (validCount === 0) {
      status = 'error'
      score = 100
      summaryText = `正文读取异常`
    } else if (totalChapters < 15) {
      status = 'incomplete'
      score = 1000 + totalChapters
      summaryText = `仅 ${totalChapters} 章 · 章节不全`
    } else if (validCount < totalSampleChecked) {
      status = 'valid'
      score = 8000 + totalChapters
      summaryText = `共 ${totalChapters} 章 · 抽检 ${validCount}/${totalSampleChecked} 章可用`
    }

    const finalResult: SourceHealthInspection = {
      sourceId: result.sourceId,
      bookUrl: result.bookUrl,
      status,
      score,
      totalChapters,
      latestChapterTitle,
      checkedChaptersCount: totalSampleChecked,
      validChaptersCount: validCount,
      vipBlocked: vipHit,
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
