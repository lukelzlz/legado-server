import test from 'node:test'
import assert from 'node:assert/strict'
import { BookDetails, BookshelfItem, Chapter, ReadingProgress } from '../src/api'
import { cleanAuthor, cleanTitle } from '../src/searchFilters'
import { OpenBook } from '../src/ReaderScreen'

/**
 * Simulates openShelfItem logic to test instant bookshelf open behavior and details call elimination.
 */
async function simulateOpenShelfItem(
  item: BookshelfItem,
  mockApi: {
    detailsCallCount: number
    details: (sourceId: string, bookUrl: string) => Promise<BookDetails>
    chapters: (sourceId: string, tocUrl: string) => Promise<Chapter[]>
    progress: (sourceId: string, bookUrl: string) => Promise<ReadingProgress | undefined>
    cover: (key: string) => string
  },
  onOpenReader: (openBook: OpenBook, resumeIndex: number, origin: string) => void,
  onNavigate: (page: string) => void
) {
  const fallbackCover = item.coverKey
    ? mockApi.cover(item.coverKey)
    : (item.alternateSources?.find(s => s.coverUrl?.trim())?.coverUrl?.trim() || undefined)

  const safeDetails: BookDetails = {
    sourceId: item.sourceId,
    name: cleanTitle(item.name) || item.name,
    author: cleanAuthor(item.author) || item.author,
    coverUrl: fallbackCover,
    intro: undefined,
    tocUrl: item.tocUrl,
    alternateSources: item.alternateSources,
  }

  try {

    const [chapters, progress] = await Promise.all([
      mockApi.chapters(safeDetails.sourceId, safeDetails.tocUrl),
      mockApi.progress(safeDetails.sourceId, item.bookUrl).catch(() => undefined),
    ])

    const resumeIdx = progress?.chapterIndex ?? item.chapterIndex ?? 0
    onOpenReader(
      {
        details: safeDetails,
        bookUrl: item.bookUrl,
        chapters,
        progress: progress || (item.chapterIndex !== undefined ? {
          sourceId: item.sourceId,
          bookUrl: item.bookUrl,
          chapterUrl: chapters[resumeIdx]?.url || '',
          chapterIndex: resumeIdx,
          scrollPosition: item.scrollPosition ?? 0,
          updatedAt: item.lastReadAt,
        } : undefined),
      },
      resumeIdx,
      'shelf'
    )
  } catch {
    const resumeIdx = item.chapterIndex ?? 0
    const fallbackChapters: Chapter[] = [
      { index: resumeIdx, title: `第 ${resumeIdx + 1} 章`, url: item.tocUrl }
    ]
    onOpenReader(
      {
        details: safeDetails,
        bookUrl: item.bookUrl,
        chapters: fallbackChapters,
        progress: {
          sourceId: item.sourceId,
          bookUrl: item.bookUrl,
          chapterUrl: item.tocUrl,
          chapterIndex: resumeIdx,
          scrollPosition: item.scrollPosition ?? 0,
          updatedAt: item.lastReadAt,
        },
      },
      resumeIdx,
      'shelf'
    )
  }
}

test('ShelfInstantOpen - eliminates redundant api.details network call', async () => {
  let detailsCalled = 0
  let chaptersCalled = 0
  let progressCalled = 0

  const mockApi = {
    detailsCallCount: 0,
    details: async (_sourceId: string, _bookUrl: string) => {
      detailsCalled++
      return { sourceId: 'src-1', name: 'Details Name', tocUrl: 'https://example.com/toc' }
    },
    chapters: async (_sourceId: string, _tocUrl: string) => {
      chaptersCalled++
      return [
        { index: 0, title: '第1章 开始', url: 'https://example.com/c1' },
        { index: 1, title: '第2章 发展', url: 'https://example.com/c2' },
      ]
    },
    progress: async (_sourceId: string, _bookUrl: string) => {
      progressCalled++
      return undefined
    },
    cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
  }

  const shelfItem: BookshelfItem = {
    sourceId: 'src-1',
    bookUrl: 'https://example.com/book1',
    name: '《道诡异仙》',
    author: '【狐尾的笔】',
    tocUrl: 'https://example.com/toc1',
    coverKey: 'cover_123',
    chapterIndex: 1,
    scrollPosition: 0.5,
    lastReadAt: 1700000000,
    cachedChapters: 2,
    totalChapters: 2,
    cacheState: 'ready',
    completed: false,
  }

  let openedBook: OpenBook | null = null
  let openedIndex = -1
  let openedOrigin = ''

  await simulateOpenShelfItem(
    shelfItem,
    mockApi,
    (book, idx, origin) => {
      openedBook = book
      openedIndex = idx
      openedOrigin = origin
    },
    () => {}
  )

  // Verify 0 calls to api.details (instant open)
  assert.equal(detailsCalled, 0, 'api.details MUST NOT be called when opening from shelf')
  assert.equal(chaptersCalled, 1, 'api.chapters should be fetched')
  assert.equal(progressCalled, 1, 'api.progress should be checked in parallel')

  // Verify safeDetails properly constructed
  assert.ok(openedBook)
  assert.equal((openedBook as OpenBook).details.sourceId, 'src-1')
  assert.equal((openedBook as OpenBook).details.name, '道诡异仙')
  assert.equal((openedBook as OpenBook).details.author, '狐尾的笔')
  assert.equal((openedBook as OpenBook).details.coverUrl, '/api/covers/cover_123')
  assert.equal((openedBook as OpenBook).details.tocUrl, 'https://example.com/toc1')

  // Verify resume index and progress fallback from BookshelfItem
  assert.equal(openedIndex, 1)
  assert.equal(openedOrigin, 'shelf')
  assert.deepEqual((openedBook as OpenBook).progress, {
    sourceId: 'src-1',
    bookUrl: 'https://example.com/book1',
    chapterUrl: 'https://example.com/c2',
    chapterIndex: 1,
    scrollPosition: 0.5,
    updatedAt: 1700000000,
  })
})

test('ShelfInstantOpen - cover fallback resolves alternateSources when coverKey is absent', async () => {
  const mockApi = {
    detailsCallCount: 0,
    details: async () => { throw new Error('should not call') },
    chapters: async () => [{ index: 0, title: '第1章', url: 'https://example.com/c1' }],
    progress: async () => undefined,
    cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
  }

  const shelfItem: BookshelfItem = {
    sourceId: 'src-2',
    bookUrl: 'https://example.com/book2',
    name: '诡秘之主',
    author: '爱潜水的乌贼',
    tocUrl: 'https://example.com/toc2',
    lastReadAt: 1700000000,
    cachedChapters: 0,
    totalChapters: 100,
    cacheState: 'idle',
    completed: false,
    alternateSources: [
      { sourceId: 'alt-1', name: '诡秘之主', bookUrl: 'https://alt1.com/book', coverUrl: 'https://img.com/alt1.jpg' },
    ],
  }

  let openedBook: OpenBook | null = null

  await simulateOpenShelfItem(
    shelfItem,
    mockApi,
    (book) => { openedBook = book },
    () => {}
  )

  assert.ok(openedBook)
  assert.equal((openedBook as OpenBook).details.coverUrl, 'https://img.com/alt1.jpg')
  assert.equal((openedBook as OpenBook).details.name, '诡秘之主')
  assert.equal((openedBook as OpenBook).details.author, '爱潜水的乌贼')
})

test('ShelfInstantOpen - remote reading progress overrides shelf cache when present', async () => {
  const mockApi = {
    detailsCallCount: 0,
    details: async () => { throw new Error('should not call') },
    chapters: async () => [
      { index: 0, title: '第1章', url: 'https://example.com/c1' },
      { index: 1, title: '第2章', url: 'https://example.com/c2' },
      { index: 2, title: '第3章', url: 'https://example.com/c3' },
    ],
    progress: async () => ({
      sourceId: 'src-1',
      bookUrl: 'https://example.com/book1',
      chapterUrl: 'https://example.com/c3',
      chapterIndex: 2,
      scrollPosition: 0.8,
      updatedAt: 1700000099,
    }),
    cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
  }

  const shelfItem: BookshelfItem = {
    sourceId: 'src-1',
    bookUrl: 'https://example.com/book1',
    name: '宿命之环',
    tocUrl: 'https://example.com/toc',
    chapterIndex: 0,
    scrollPosition: 0.1,
    lastReadAt: 1700000000,
    cachedChapters: 0,
    totalChapters: 3,
    cacheState: 'idle',
    completed: false,
  }

  let openedBook: OpenBook | null = null
  let resumeIdx = -1

  await simulateOpenShelfItem(
    shelfItem,
    mockApi,
    (book, idx) => {
      openedBook = book
      resumeIdx = idx
    },
    () => {}
  )

  assert.ok(openedBook)
  assert.equal(resumeIdx, 2, 'Remote progress chapterIndex should take precedence')
  assert.equal((openedBook as OpenBook).progress?.scrollPosition, 0.8)
})

test('ShelfInstantOpen - offline reading fallback on chapters fetch failure', async () => {
  const mockApi = {
    detailsCallCount: 0,
    details: async () => { throw new Error('should not call') },
    chapters: async () => { throw new Error('Network error') },
    progress: async () => undefined,
    cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
  }

  const shelfItem: BookshelfItem = {
    sourceId: 'src-1',
    bookUrl: 'https://example.com/book1',
    name: '离线缓存的书',
    tocUrl: 'https://example.com/toc',
    chapterIndex: 5,
    scrollPosition: 0.3,
    lastReadAt: 1700000000,
    cachedChapters: 10,
    totalChapters: 10,
    cacheState: 'ready',
    completed: false,
  }

  let openedBook: OpenBook | null = null
  let openedIdx = -1

  await simulateOpenShelfItem(
    shelfItem,
    mockApi,
    (book, idx) => {
      openedBook = book
      openedIdx = idx
    },
    () => {}
  )

  assert.ok(openedBook, 'Should open reader even when chapters network call fails')
  assert.equal(openedIdx, 5, 'Should restore user reading index in offline mode')
  assert.equal((openedBook as OpenBook).chapters.length, 1)
})
