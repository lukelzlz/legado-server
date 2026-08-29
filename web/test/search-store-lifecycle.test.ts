import test from 'node:test'
import assert from 'node:assert/strict'
import { api, SearchResult } from '../src/api.ts'
import {
  searchStore,
  bookKey,
  loadSourceBook,
  SearchGroup,
  SourceChoice,
} from '../src/searchStore.ts'

test('searchStore - bookKey and loadSourceBook helper', async () => {
  assert.equal(bookKey('【校对版】凡人修仙传', '忘语'), '凡人修仙传\u0000忘语')
  assert.equal(bookKey('未知小说', undefined), '未知小说\u0000')

  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalProgress = api.progress

  try {
    api.details = async () => ({
      sourceId: 'https://src1.com',
      name: '凡人修仙传',
      author: '忘语',
      tocUrl: '/toc',
    })
    api.chapters = async () => [{ index: 0, title: '第一章', url: '/c1' }]
    api.progress = async () => ({
      sourceId: 'https://src1.com',
      bookUrl: '/book/1',
      chapterUrl: '/c1',
      chapterIndex: 0,
      scrollPosition: 0.2,
      updatedAt: 123456,
    })

    const book = await loadSourceBook({
      sourceId: 'https://src1.com',
      name: '凡人修仙传',
      bookUrl: '/book/1',
    })

    assert.equal(book.details.name, '凡人修仙传')
    assert.equal(book.chapters.length, 1)
    assert.equal(book.progress?.chapterIndex, 0)

    // Test that placeholder details name "未命名书籍" falls back to search result title
    api.details = async () => ({
      sourceId: 'https://src1.com',
      name: '未命名书籍',
      author: '爱潜水的乌贼',
      tocUrl: '/toc',
    })
    const bookWithFallback = await loadSourceBook({
      sourceId: 'https://src1.com',
      name: '诡秘之主',
      bookUrl: '/book/2',
    })
    assert.equal(bookWithFallback.details.name, '诡秘之主', 'Should fallback to search result title when details.name is 未命名书籍')
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.progress = originalProgress
  }
})

test('searchStore - openGroup and candidate source fallback', async () => {
  const originalDetails = api.details
  const originalChapters = api.chapters
  const originalProgress = api.progress

  try {
    searchStore.reset()

    // 1st candidate fails, 2nd candidate succeeds
    api.details = async (sourceId) => {
      if (sourceId.includes('bad')) throw new Error('网络连接超时')
      return {
        sourceId,
        name: '完美世界',
        author: '辰东',
        tocUrl: '/toc',
      }
    }
    api.chapters = async () => [{ index: 0, title: '第一章 荒村', url: '/c1' }]
    api.progress = async () => undefined

    const group: SearchGroup = {
      key: '完美世界\u0000辰东',
      name: '完美世界',
      author: '辰东',
      sources: [
        { sourceId: 'https://bad.com', name: '完美世界', bookUrl: '/b/bad' },
        { sourceId: 'https://good.com', name: '完美世界', bookUrl: '/b/good' },
      ],
    }

    await searchStore.openGroup(group)

    const state = searchStore.getSnapshot()
    assert.ok(state.openBook !== null)
    assert.equal(state.openBook?.details.sourceId, 'https://good.com')
    assert.equal(state.choices.length, 2)
    assert.equal(state.choices[0].status, 'error')
    assert.equal(state.choices[1].status, 'loaded')

    // Test chooseSource with cached choice
    const goodChoice: SourceChoice = state.choices[1]
    await searchStore.chooseSource(goodChoice, group.sources)
    assert.equal(searchStore.getSnapshot().openBook?.details.sourceId, 'https://good.com')
  } finally {
    api.details = originalDetails
    api.chapters = originalChapters
    api.progress = originalProgress
    searchStore.reset()
  }
})

test('searchStore - state mutators and reset lifecycle', () => {
  searchStore.reset()
  searchStore.setKeyword('大奉打更人')
  searchStore.setSelectedSourceId('https://src.com')
  searchStore.setFilters({
    query: '大奉',
    category: 'all',
    minChapters: 100,
    author: '',
    sortMode: 'relevance',
  })

  let snap = searchStore.getSnapshot()
  assert.equal(snap.keyword, '大奉打更人')
  assert.equal(snap.selectedSourceId, 'https://src.com')
  assert.equal(snap.filters.minChapters, 100)

  searchStore.reset()
  snap = searchStore.getSnapshot()
  assert.equal(snap.keyword, '')
  assert.equal(snap.selectedSourceId, '')
  assert.equal(snap.loading, false)
})
