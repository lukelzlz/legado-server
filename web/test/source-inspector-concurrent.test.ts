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

test('source inspector - modal open throttled inspection & on-demand inspection flow', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalContent = api.content
  const originalProgress = api.progress

  try {
    const networkCallsBySource: Record<string, number> = {}

    api.details = async (src, book) => {
      networkCallsBySource[src] = (networkCallsBySource[src] || 0) + 1
      return { sourceId: src, name: '凡人修仙传', tocUrl: `${book}/toc` }
    }
    api.chapters = async (src) => {
      networkCallsBySource[src] = (networkCallsBySource[src] || 0) + 1
      return Array.from({ length: 20 }, (_, i) => ({
        index: i,
        title: `第${i + 1}章 试炼`,
        url: `/c${i + 1}`,
      }))
    }
    api.content = async (src) => {
      networkCallsBySource[src] = (networkCallsBySource[src] || 0) + 1
      return { content: '修仙正文字数很多测试内容'.repeat(100) }
    }
    api.progress = async (src) => {
      networkCallsBySource[src] = (networkCallsBySource[src] || 0) + 1
      return undefined
    }

    const rawSources: SearchResult[] = Array.from({ length: 12 }, (_, i) => ({
      sourceId: `source-${i}`,
      name: '凡人修仙传',
      bookUrl: `https://s${i}.com/book/1`,
    }))

    // 1. Initial modal open state: all 12 initialized to idle
    const inspections = createInitialInspections(rawSources)
    assert.equal(inspections.size, 12)
    for (const s of rawSources) {
      const key = `${s.sourceId}\u0000${s.bookUrl}`
      assert.equal(inspections.get(key)?.status, 'idle')
    }
    assert.equal(Object.keys(networkCallsBySource).length, 0, 'No network requests on initial map creation')

    // 2. On modal open: ONLY the active source (e.g. source-0) is inspected
    const activeResult = rawSources[0]
    const activeKey = `${activeResult.sourceId}\u0000${activeResult.bookUrl}`
    const singleRes = await inspectSingleSource(activeResult, rawSources, partial => {
      const existing = inspections.get(activeKey)!
      inspections.set(activeKey, { ...existing, ...partial })
    })
    inspections.set(activeKey, singleRes)

    assert.equal(inspections.get(activeKey)?.status, 'valid')
    assert.ok(networkCallsBySource['source-0'] > 0)
    for (let i = 1; i < 12; i++) {
      const idleKey = `${rawSources[i].sourceId}\u0000${rawSources[i].bookUrl}`
      assert.equal(inspections.get(idleKey)?.status, 'idle')
      assert.equal(networkCallsBySource[`source-${i}`], undefined, `Candidate source-${i} must have zero network calls upfront`)
    }

    // 3. User hovers or clicks on candidate source-5 on demand
    const candResult = rawSources[5]
    const candKey = `${candResult.sourceId}\u0000${candResult.bookUrl}`
    assert.equal(inspections.get(candKey)?.status, 'idle')

    const candRes = await inspectSingleSource(candResult, rawSources, partial => {
      const existing = inspections.get(candKey)!
      inspections.set(candKey, { ...existing, ...partial })
    })
    inspections.set(candKey, candRes)

    assert.equal(inspections.get(candKey)?.status, 'valid')
    assert.ok(networkCallsBySource['source-5'] > 0)
    // Other candidate sources still untouched
    assert.equal(inspections.get(`${rawSources[1].sourceId}\u0000${rawSources[1].bookUrl}`)?.status, 'idle')
    assert.equal(networkCallsBySource['source-1'], undefined)

    // 4. User clicks full re-check: runs concurrent inspection on all sources
    const fullMap = await inspectAllSourcesConcurrently(rawSources, 3, undefined, undefined, inspections)
    assert.equal(fullMap.size, 12)
    for (let i = 0; i < 12; i++) {
      const key = `${rawSources[i].sourceId}\u0000${rawSources[i].bookUrl}`
      assert.equal(fullMap.get(key)?.status, 'valid')
    }
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.content = originalContent
    api.progress = originalProgress
  }
})
