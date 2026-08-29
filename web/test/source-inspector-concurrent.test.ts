import test from 'node:test'
import assert from 'node:assert/strict'
import { api, Chapter, SearchResult } from '../src/api.ts'
import {
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
