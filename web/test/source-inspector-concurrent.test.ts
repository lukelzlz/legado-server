import test from 'node:test'
import assert from 'node:assert/strict'
import { api, Chapter, SearchResult } from '../src/api.ts'
import {
  createInitialInspections,
  inspectSingleSource,
  inspectAllSourcesConcurrently,
  findMainChapter,
  isNoticeOrNonMainChapter,
} from '../src/sourceInspector.ts'

test('source inspector - notice detection and main chapter fallback', () => {
  assert.equal(isNoticeOrNonMainChapter('完结感言'), true)
  assert.equal(isNoticeOrNonMainChapter('请假一天'), true)
  assert.equal(isNoticeOrNonMainChapter('第1章 惊变'), false)

  const chapters: Chapter[] = [
    { index: 0, title: '重要通知：新书说明', url: '/c0' },
    { index: 1, title: '第一章 启程', url: '/c1' },
    { index: 2, title: '第二章 风云', url: '/c2' },
    { index: 3, title: '请假条', url: '/c3' },
  ]

  // When preferred index is a notice, findMainChapter finds nearest non-notice
  const main0 = findMainChapter(chapters, 0)
  assert.equal(main0?.title, '第一章 启程')

  const main3 = findMainChapter(chapters, 3)
  assert.equal(main3?.title, '第二章 风云')
})

test('source inspector - inspectSingleSource with valid full chapters', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalContent = api.content
  const originalProgress = api.progress

  try {
    api.details = async () => ({
      sourceId: 'https://src1.com',
      name: '万古神话',
      author: '神笔',
      tocUrl: '/toc',
    })

    api.chapters = async () => Array.from({ length: 20 }, (_, i) => ({
      index: i,
      title: `第${i + 1}章 修仙`,
      url: `/c${i + 1}`,
    }))

    api.content = async () => ({
      content: '正文内容'.repeat(500), // > 2000 chars
    })

    api.progress = async () => undefined


    const searchResult: SearchResult = {
      sourceId: 'https://src1.com',
      name: '万古神话',
      bookUrl: 'https://src1.com/book/1',
    }

    const progressUpdates: any[] = []
    const inspection = await inspectSingleSource(searchResult, undefined, update => {
      progressUpdates.push(update)
    })

    assert.equal(inspection.status, 'valid')
    assert.equal(inspection.vipBlocked, false)
    assert.ok(inspection.score > 12000)
    assert.ok(inspection.book !== undefined)
    assert.equal(inspection.book?.chapters.length, 20)
    assert.ok(progressUpdates.length >= 2)
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.content = originalContent
    api.progress = originalProgress
  }
})

test('source inspector - inspectSingleSource with empty TOC returns error', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters

  try {
    api.details = async () => ({
      sourceId: 'https://empty.com',
      name: '空书',
      tocUrl: '/toc',
    })
    api.chapters = async () => []

    const inspection = await inspectSingleSource({
      sourceId: 'https://empty.com',
      name: '空书',
      bookUrl: 'https://empty.com/book/1',
    })

    assert.equal(inspection.status, 'error')
    assert.equal(inspection.totalChapters, 0)
    assert.equal(inspection.summaryText, '目录为空')
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
  }
})

test('source inspector - inspectAllSourcesConcurrently executes bounded concurrent checks', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalContent = api.content
  const originalProgress = api.progress

  try {
    api.details = async (src, book) => ({
      sourceId: src,
      name: '测试书',
      tocUrl: `${book}/toc`,
    })
    api.chapters = async () => [
      { index: 0, title: '第一章', url: '/c1' },
      { index: 1, title: '第二章', url: '/c2' },
    ]
    api.content = async () => ({ content: '正文字数充足测试'.repeat(200) })
    api.progress = async () => undefined

    const sources: SearchResult[] = [1, 2, 3, 4, 5, 6].map(i => ({
      sourceId: `https://src${i}.com`,
      name: `测试书${i}`,
      bookUrl: `https://src${i}.com/book/${i}`,
    }))

    let updateCount = 0
    const resultMap = await inspectAllSourcesConcurrently(sources, 3, () => {
      updateCount++
    })

    assert.equal(resultMap.size, 6)
    assert.ok(updateCount > 0)

    // Test cancellation flag
    let cancelled = false
    const cancelMap = await inspectAllSourcesConcurrently(
      sources,
      2,
      () => {
        cancelled = true
      },
      () => true // immediate cancel
    )
    assert.equal(cancelMap.size, 6)
    // First source will be marked idle since worker aborted before processing
    assert.equal(cancelMap.get('https://src1.com\u0000https://src1.com/book/1')?.status, 'idle')
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.content = originalContent
    api.progress = originalProgress
  }
})

test('source inspector - createInitialInspections creates idle entries for all sources', () => {
  const sources: SearchResult[] = [
    { sourceId: 'src-1', name: '书名', bookUrl: 'http://src1.com/1' },
    { sourceId: 'src-2', name: '书名', bookUrl: 'http://src2.com/2' },
  ]
  const map = createInitialInspections(sources)
  assert.equal(map.size, 2)
  const item1 = map.get('src-1\u0000http://src1.com/1')
  assert.equal(item1?.status, 'idle')
  assert.equal(item1?.summaryText, '待校验')
  const item2 = map.get('src-2\u0000http://src2.com/2')
  assert.equal(item2?.status, 'idle')
  assert.equal(item2?.summaryText, '待校验')
})

test('source inspector - inspectSingleSource respects isCancelled at every network hop', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalContent = api.content
  const originalProgress = api.progress

  try {
    let detailsCalls = 0
    let chaptersCalls = 0
    let contentCalls = 0

    api.details = async () => {
      detailsCalls++
      return { sourceId: 'src-1', name: '书名', tocUrl: '/toc' }
    }
    api.chapters = async () => {
      chaptersCalls++
      return [
        { index: 0, title: '第1章', url: '/c1' },
        { index: 1, title: '第2章', url: '/c2' },
      ]
    }
    api.content = async () => {
      contentCalls++
      return { content: '正文'.repeat(200) }
    }
    api.progress = async () => undefined

    const searchResult: SearchResult = {
      sourceId: 'src-1',
      name: '书名',
      bookUrl: 'http://src1.com/1',
    }

    // 1. Cancelled immediately before start
    const res1 = await inspectSingleSource(searchResult, undefined, undefined, () => true)
    assert.equal(res1.status, 'idle')
    assert.equal(detailsCalls, 0)
    assert.equal(chaptersCalls, 0)
    assert.equal(contentCalls, 0)

    // 2. Cancelled right after details resolves
    let cancelAfterDetails = false
    api.details = async () => {
      detailsCalls++
      cancelAfterDetails = true
      return { sourceId: 'src-1', name: '书名', tocUrl: '/toc' }
    }
    const res2 = await inspectSingleSource(searchResult, undefined, undefined, () => cancelAfterDetails)
    assert.equal(res2.status, 'idle')
    assert.equal(detailsCalls, 1)
    assert.equal(chaptersCalls, 0)
    assert.equal(contentCalls, 0)

    // 3. Cancelled right after chapters resolves
    let cancelAfterChapters = false
    api.chapters = async () => {
      chaptersCalls++
      cancelAfterChapters = true
      return [
        { index: 0, title: '第1章', url: '/c1' },
        { index: 1, title: '第2章', url: '/c2' },
      ]
    }
    const res3 = await inspectSingleSource(searchResult, undefined, undefined, () => cancelAfterChapters)
    assert.equal(res3.status, 'idle')
    assert.equal(chaptersCalls, 1)
    assert.equal(contentCalls, 0)
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.content = originalContent
    api.progress = originalProgress
  }
})

test('source inspector - fast concurrent inspection and modal close AbortSignal cancellation', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalContent = api.content
  const originalProgress = api.progress

  try {
    const activeCalls = new Set<string>()
    let abortedCallCount = 0

    api.details = async (src, book, signal) => {
      activeCalls.add(`details:${src}`)
      if (signal?.aborted) {
        abortedCallCount++
        throw new DOMException('Aborted', 'AbortError')
      }
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          activeCalls.delete(`details:${src}`)
          resolve({ sourceId: src, name: '凡人修仙传', tocUrl: `${book}/toc` })
        }, 50)
        signal?.addEventListener('abort', () => {
          clearTimeout(timer)
          activeCalls.delete(`details:${src}`)
          abortedCallCount++
          reject(new DOMException('Aborted', 'AbortError'))
        })
      })
    }

    api.chapters = async (src, _book, signal) => {
      if (signal?.aborted) {
        abortedCallCount++
        throw new DOMException('Aborted', 'AbortError')
      }
      return Array.from({ length: 20 }, (_, i) => ({
        index: i,
        title: `第${i + 1}章 试炼`,
        url: `/c${i + 1}`,
      }))
    }

    api.content = async (_src, _ch, _b, signal) => {
      if (signal?.aborted) {
        abortedCallCount++
        throw new DOMException('Aborted', 'AbortError')
      }
      return { content: '修仙正文字数很多测试内容'.repeat(100) }
    }

    api.progress = async () => undefined

    const rawSources: SearchResult[] = Array.from({ length: 8 }, (_, i) => ({
      sourceId: `source-${i}`,
      name: '凡人修仙传',
      bookUrl: `https://s${i}.com/book/1`,
    }))

    // 1. Fast concurrent inspection on modal open
    const controller = new AbortController()
    const promise = inspectAllSourcesConcurrently(
      rawSources,
      3,
      undefined,
      undefined,
      undefined,
      controller.signal
    )

    // Verify workers started concurrently
    assert.ok(activeCalls.size > 0, 'Concurrent workers must start immediately')

    // 2. User closes modal / enters reader after 10ms -> abort all in-flight requests
    await new Promise(r => setTimeout(r, 10))
    controller.abort()

    const map = await promise
    assert.ok(abortedCallCount > 0, 'In-flight HTTP requests must be aborted')
    assert.equal(activeCalls.size, 0, 'No remaining active network calls after modal close abort')
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.content = originalContent
    api.progress = originalProgress
  }
})
