import test from 'node:test'
import assert from 'node:assert/strict'

export type SearchResult = {
  sourceId: string
  name: string
  author?: string
  bookUrl: string
  coverUrl?: string
  intro?: string
}

export type BookDetails = {
  sourceId: string
  name: string
  author?: string
  intro?: string
  coverUrl?: string
  tocUrl: string
}

export type Chapter = {
  index: number
  title: string
  url: string
}

export type ReadingProgress = {
  sourceId: string
  bookUrl: string
  chapterUrl: string
  chapterIndex: number
  scrollPosition: number
}

export type OpenBook = {
  details: BookDetails
  bookUrl: string
  chapters: Chapter[]
  progress?: ReadingProgress
}

export type SourceChoiceStatus = 'idle' | 'loading' | 'loaded' | 'error'

export type SourceChoice = {
  result: SearchResult
  status: SourceChoiceStatus
  book?: OpenBook
  error?: string
}

/**
 * Deduplicates search results by (sourceId, bookUrl) composite key.
 */
export function deduplicateSearchResults(results: SearchResult[]): SearchResult[] {
  const seen = new Set<string>()
  const deduplicated: SearchResult[] = []
  for (const item of results) {
    const key = `${item.sourceId}\0${item.bookUrl}`
    if (!seen.has(key)) {
      seen.add(key)
      deduplicated.push(item)
    }
  }
  return deduplicated
}

/**
 * Manager orchestrating lazy candidate source loading, on-demand fetching,
 * candidate state transitions, and race condition defense.
 */
export class LazyCandidateManager {
  choices: SourceChoice[] = []
  activeBook: OpenBook | null = null
  networkCalls = {
    details: 0,
    chapters: 0,
    progress: 0,
    content: 0,
  }

  // Generation counter to defend against race conditions during rapid switching
  private activeSwitchGeneration = 0

  fetchDetails: (sourceId: string, bookUrl: string) => Promise<BookDetails>
  fetchChapters: (sourceId: string, tocUrl: string) => Promise<Chapter[]>
  fetchProgress: (sourceId: string, bookUrl: string) => Promise<ReadingProgress | undefined>
  fetchContent?: (sourceId: string, chapterUrl: string) => Promise<string>

  constructor(
    fetchDetails: (sourceId: string, bookUrl: string) => Promise<BookDetails>,
    fetchChapters: (sourceId: string, tocUrl: string) => Promise<Chapter[]>,
    fetchProgress: (sourceId: string, bookUrl: string) => Promise<ReadingProgress | undefined>,
    fetchContent?: (sourceId: string, chapterUrl: string) => Promise<string>
  ) {
    this.fetchDetails = fetchDetails
    this.fetchChapters = fetchChapters
    this.fetchProgress = fetchProgress
    this.fetchContent = fetchContent
  }

  get totalNetworkCalls(): number {
    return this.networkCalls.details + this.networkCalls.chapters + this.networkCalls.progress + this.networkCalls.content
  }

  async openGroup(rawSources: SearchResult[]): Promise<void> {
    const sources = deduplicateSearchResults(rawSources)
    if (sources.length === 0) {
      throw new Error('没有可用的书源')
    }

    const currentGen = ++this.activeSwitchGeneration
    const primary = sources[0]

    // Initialize state: primary source loading, all candidates idle
    this.choices = sources.map((result, index) => ({
      result,
      status: index === 0 ? 'loading' : 'idle',
    }))

    try {
      this.networkCalls.details++
      const details = await this.fetchDetails(primary.sourceId, primary.bookUrl)

      this.networkCalls.chapters++
      this.networkCalls.progress++
      const [chapters, progress] = await Promise.all([
        this.fetchChapters(details.sourceId, details.tocUrl),
        this.fetchProgress(details.sourceId, primary.bookUrl),
      ])

      const book: OpenBook = { details, bookUrl: primary.bookUrl, chapters, progress }

      if (currentGen === this.activeSwitchGeneration) {
        this.activeBook = book
      }
      this.choices[0] = { result: primary, status: 'loaded', book }
    } catch (err) {
      this.choices[0] = {
        result: primary,
        status: 'error',
        error: err instanceof Error ? err.message : '书源加载失败',
      }
      throw err
    }
  }

  async loadCandidate(index: number): Promise<SourceChoice> {
    const choice = this.choices[index]
    if (!choice) throw new Error(`Invalid candidate index: ${index}`)
    if (choice.status === 'loaded' || choice.status === 'loading') return choice

    choice.status = 'loading'
    choice.error = undefined

    try {
      this.networkCalls.details++
      const details = await this.fetchDetails(choice.result.sourceId, choice.result.bookUrl)

      this.networkCalls.chapters++
      this.networkCalls.progress++
      const [chapters, progress] = await Promise.all([
        this.fetchChapters(details.sourceId, details.tocUrl),
        this.fetchProgress(details.sourceId, choice.result.bookUrl),
      ])

      const book: OpenBook = { details, bookUrl: choice.result.bookUrl, chapters, progress }
      choice.status = 'loaded'
      choice.book = book
      return choice
    } catch (err) {
      choice.status = 'error'
      choice.error = err instanceof Error ? err.message : '书源加载失败'
      return choice
    }
  }

  async selectCandidate(index: number): Promise<void> {
    const choice = this.choices[index]
    if (!choice) throw new Error(`Invalid candidate index: ${index}`)

    const currentGen = ++this.activeSwitchGeneration

    if (choice.status === 'loaded' && choice.book) {
      this.activeBook = choice.book
      return
    }

    const loaded = await this.loadCandidate(index)
    if (currentGen === this.activeSwitchGeneration && loaded.status === 'loaded' && loaded.book) {
      this.activeBook = loaded.book
    }
  }
}

// Mock factory helper
function createMockApi(options?: {
  failingSources?: Set<string>
  delays?: Record<string, number>
}) {
  const failing = options?.failingSources ?? new Set<string>()
  const delays = options?.delays ?? {}

  const maybeDelay = async (sourceId: string) => {
    const ms = delays[sourceId]
    if (ms) await new Promise(r => setTimeout(r, ms))
  }

  return {
    fetchDetails: async (sourceId: string, bookUrl: string): Promise<BookDetails> => {
      await maybeDelay(sourceId)
      if (failing.has(sourceId)) {
        throw new Error(`Source ${sourceId} network timeout or HTTP 500`)
      }
      return {
        sourceId,
        name: '凡人修仙传',
        author: '忘语',
        tocUrl: `${bookUrl}/toc`,
        intro: '一个普通的山村穷小子，偶然之下跨入修仙门派...',
        coverUrl: `https://covers.example.com/${sourceId}.jpg`,
      }
    },
    fetchChapters: async (sourceId: string, tocUrl: string): Promise<Chapter[]> => {
      await maybeDelay(sourceId)
      if (failing.has(sourceId)) {
        throw new Error(`Source ${sourceId} chapter list fetch failed`)
      }
      return Array.from({ length: 50 }, (_, i) => ({
        index: i,
        title: `第${i + 1}章 章节标题`,
        url: `${tocUrl}/chapter-${i + 1}`,
      }))
    },
    fetchProgress: async (sourceId: string, bookUrl: string): Promise<ReadingProgress | undefined> => {
      await maybeDelay(sourceId)
      return undefined
    },
    fetchContent: async (sourceId: string, chapterUrl: string): Promise<string> => {
      await maybeDelay(sourceId)
      return '正文内容...'
    },
  }
}

// -----------------------------------------------------------------------------
// Tier 1: Feature Coverage (Lazy Candidate Source Loading)
// -----------------------------------------------------------------------------

test('LazySourceLoading - Tier 1: Single active source fetch on book open (0 candidate calls upfront)', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress, api.fetchContent)

  const sources: SearchResult[] = Array.from({ length: 40 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)

  // Must only make 3 calls (1 details, 1 chapters, 1 progress) for primary source
  assert.equal(manager.networkCalls.details, 1)
  assert.equal(manager.networkCalls.chapters, 1)
  assert.equal(manager.networkCalls.progress, 1)
  assert.equal(manager.networkCalls.content, 0, 'Must not download chapter content upfront')
  assert.equal(manager.totalNetworkCalls, 3)

  assert.equal(manager.activeBook?.details.sourceId, 'source-0')
})

test('LazySourceLoading - Tier 1: Initial candidate choices state (sources[0] loaded, sources[1..N] idle)', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = Array.from({ length: 40 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)

  assert.equal(manager.choices.length, 40)
  assert.equal(manager.choices[0].status, 'loaded')
  assert.ok(manager.choices[0].book !== undefined)

  for (let i = 1; i < 40; i++) {
    assert.equal(manager.choices[i].status, 'idle', `Candidate ${i} must be idle`)
    assert.equal(manager.choices[i].book, undefined, `Candidate ${i} must not have book loaded upfront`)
  }
})

test('LazySourceLoading - Tier 1: On-demand candidate source fetch on expand/select', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = Array.from({ length: 40 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)
  assert.equal(manager.totalNetworkCalls, 3)

  // User expands candidate list and selects candidate 7
  await manager.loadCandidate(7)

  assert.equal(manager.totalNetworkCalls, 6) // Exactly +3 calls for candidate 7
  assert.equal(manager.choices[7].status, 'loaded')
  assert.equal(manager.choices[7].book?.details.sourceId, 'source-7')

  // Other candidates remain untouched
  assert.equal(manager.choices[6].status, 'idle')
  assert.equal(manager.choices[8].status, 'idle')
})

test('LazySourceLoading - Tier 1: Instant source switching for loaded candidates (0 extra network calls)', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = Array.from({ length: 10 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)
  await manager.loadCandidate(2)
  const callsBeforeSwitch = manager.totalNetworkCalls

  // Switch to pre-loaded candidate 2
  await manager.selectCandidate(2)

  assert.equal(manager.activeBook?.details.sourceId, 'source-2')
  assert.equal(manager.totalNetworkCalls, callsBeforeSwitch, 'Switching to loaded candidate must incur 0 network calls')

  // Switch back to primary source 0
  await manager.selectCandidate(0)
  assert.equal(manager.activeBook?.details.sourceId, 'source-0')
  assert.equal(manager.totalNetworkCalls, callsBeforeSwitch, 'Switching back to primary source must incur 0 network calls')
})

test('LazySourceLoading - Tier 1: Zero chapter content preview downloads during book open or candidate expansion', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress, api.fetchContent)

  const sources: SearchResult[] = Array.from({ length: 5 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)
  await manager.loadCandidate(1)
  await manager.loadCandidate(2)

  assert.equal(manager.networkCalls.content, 0, 'No chapter content requests should be made during source management')
})

// -----------------------------------------------------------------------------
// Tier 2: Boundary & Corner Cases
// -----------------------------------------------------------------------------

test('LazySourceLoading - Tier 2: Candidate failure isolation', async () => {
  const api = createMockApi({
    failingSources: new Set(['source-3']),
  })
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = Array.from({ length: 5 }, (_, i) => ({
    sourceId: `source-${i}`,
    name: '凡人修仙传',
    bookUrl: `https://s${i}.example.com/book/1`,
  }))

  await manager.openGroup(sources)
  assert.equal(manager.choices[0].status, 'loaded')
  assert.equal(manager.activeBook?.details.sourceId, 'source-0')

  // Load failing candidate 3
  await manager.loadCandidate(3)

  assert.equal(manager.choices[3].status, 'error')
  assert.ok(manager.choices[3].error?.includes('timeout') || manager.choices[3].error?.includes('500'))
  // Primary active reading session must remain completely intact
  assert.equal(manager.activeBook?.details.sourceId, 'source-0')
})

test('LazySourceLoading - Tier 2: Primary source failure fallback', async () => {
  const api = createMockApi({
    failingSources: new Set(['source-0']),
  })
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = [
    { sourceId: 'source-0', name: '凡人修仙传', bookUrl: 'url-0' },
    { sourceId: 'source-1', name: '凡人修仙传', bookUrl: 'url-1' },
  ]

  await assert.rejects(async () => {
    await manager.openGroup(sources)
  }, /Source source-0/)

  assert.equal(manager.choices[0].status, 'error')
  assert.equal(manager.activeBook, null)
})

test('LazySourceLoading - Tier 2: Duplicate search result deduplication', () => {
  const rawResults: SearchResult[] = [
    { sourceId: 'src-1', name: '凡人修仙传', bookUrl: 'url-1' },
    { sourceId: 'src-1', name: '凡人修仙传', bookUrl: 'url-1' }, // duplicate
    { sourceId: 'src-2', name: '凡人修仙传', bookUrl: 'url-2' },
    { sourceId: 'src-1', name: '凡人修仙传', bookUrl: 'url-3' }, // same source, different bookUrl -> keep
    { sourceId: 'src-2', name: '凡人修仙传', bookUrl: 'url-2' }, // duplicate
  ]

  const deduped = deduplicateSearchResults(rawResults)
  assert.equal(deduped.length, 3)
  assert.deepEqual(deduped.map(d => `${d.sourceId}:${d.bookUrl}`), [
    'src-1:url-1',
    'src-2:url-2',
    'src-1:url-3',
  ])
})

test('LazySourceLoading - Tier 2: Rapid switching race condition defense', async () => {
  // Source 1 is slow (100ms), Source 2 is fast (10ms)
  const api = createMockApi({
    delays: {
      'source-1': 80,
      'source-2': 10,
    },
  })
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  const sources: SearchResult[] = [
    { sourceId: 'source-0', name: '凡人修仙传', bookUrl: 'url-0' },
    { sourceId: 'source-1', name: '凡人修仙传', bookUrl: 'url-1' },
    { sourceId: 'source-2', name: '凡人修仙传', bookUrl: 'url-2' },
  ]

  await manager.openGroup(sources)
  assert.equal(manager.activeBook?.details.sourceId, 'source-0')

  // User rapidly clicks candidate 1 (slow), then immediately candidate 2 (fast)
  const promise1 = manager.selectCandidate(1)
  const promise2 = manager.selectCandidate(2)

  await Promise.all([promise1, promise2])

  // Despite candidate 1 resolving later, activeBook MUST be candidate 2 (the latest user intent)
  assert.equal(manager.activeBook?.details.sourceId, 'source-2')
  // Candidate 1 was still fetched in background and cached as loaded
  assert.equal(manager.choices[1].status, 'loaded')
  assert.equal(manager.choices[2].status, 'loaded')
})

test('LazySourceLoading - Tier 2: Empty sources array handling', async () => {
  const api = createMockApi()
  const manager = new LazyCandidateManager(api.fetchDetails, api.fetchChapters, api.fetchProgress)

  await assert.rejects(async () => {
    await manager.openGroup([])
  }, /没有可用的书源/)

  assert.equal(manager.choices.length, 0)
  assert.equal(manager.activeBook, null)
})
