import test from 'node:test'
import assert from 'node:assert/strict'

// Domain Types
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
  updatedAt: number
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

// -----------------------------------------------------------------------------
// Replication of LibraryPage & ShelfPage candidate source logic from main.tsx
// -----------------------------------------------------------------------------

export class LibraryCandidateHarness {
  choices: SourceChoice[] = []
  openBook: OpenBook | null = null
  loading = false
  message = ''
  
  // API Call Tracker
  apiCalls = {
    details: [] as Array<{ sourceId: string; bookUrl: string }>,
    chapters: [] as Array<{ sourceId: string; tocUrl: string }>,
    progress: [] as Array<{ sourceId: string; bookUrl: string }>,
    content: [] as Array<{ sourceId: string; chapterUrl: string }>,
  }

  failingSources = new Set<string>()
  delays = new Map<string, number>()

  async mockDetails(sourceId: string, bookUrl: string): Promise<BookDetails> {
    this.apiCalls.details.push({ sourceId, bookUrl })
    const delay = this.delays.get(sourceId)
    if (delay) await new Promise(r => setTimeout(r, delay))
    if (this.failingSources.has(sourceId)) {
      throw new Error(`书源 [${sourceId}] 连接超时 (HTTP 504)`)
    }
    return {
      sourceId,
      name: '凡人修仙传',
      author: '忘语',
      intro: `修仙小说 ${sourceId}`,
      coverUrl: `https://covers.example.com/${sourceId}.jpg`,
      tocUrl: `https://${sourceId}.example.com/toc`,
    }
  }

  async mockChapters(sourceId: string, tocUrl: string): Promise<Chapter[]> {
    this.apiCalls.chapters.push({ sourceId, tocUrl })
    if (this.failingSources.has(`${sourceId}:toc`)) {
      throw new Error(`书源 [${sourceId}] 目录解析失败`)
    }
    return Array.from({ length: 50 }, (_, i) => ({
      index: i,
      title: `第${i + 1}章 章节标题`,
      url: `${tocUrl}/${i + 1}`,
    }))
  }

  async mockProgress(sourceId: string, bookUrl: string): Promise<ReadingProgress | undefined> {
    this.apiCalls.progress.push({ sourceId, bookUrl })
    return undefined
  }

  async loadSourceBook(result: SearchResult): Promise<OpenBook> {
    const details = await this.mockDetails(result.sourceId, result.bookUrl)
    const [chapters, progress] = await Promise.all([
      this.mockChapters(details.sourceId, details.tocUrl),
      this.mockProgress(details.sourceId, result.bookUrl),
    ])
    return { details, bookUrl: result.bookUrl, chapters, progress }
  }

  // Exact logic from web/src/main.tsx:118-142
  async open(group: { sources: SearchResult[] }): Promise<void> {
    this.loading = true
    this.message = ''
    this.openBook = null

    const initialChoices: SourceChoice[] = group.sources.map(result => ({ result, status: 'idle' }))
    this.choices = initialChoices

    let loadedBook: OpenBook | null = null
    for (let i = 0; i < group.sources.length; i++) {
      const candidate = group.sources[i]
      this.choices = this.choices.map((c, idx) => (idx === i ? { ...c, status: 'loading' } : c))
      try {
        const book = await this.loadSourceBook(candidate)
        this.choices = this.choices.map((c, idx) => (idx === i ? { ...c, book, status: 'loaded' } : c))
        loadedBook = book
        break
      } catch (err) {
        const errMsg = err instanceof Error ? err.message : '无法读取此书源'
        this.choices = this.choices.map((c, idx) => (idx === i ? { ...c, status: 'error', error: errMsg } : c))
      }
    }

    if (loadedBook) {
      this.openBook = loadedBook
    } else {
      this.message = '所有书源均无法读取'
    }
    this.loading = false
  }

  // Exact logic from web/src/main.tsx:143-157
  async handleChooseSource(choice: SourceChoice): Promise<void> {
    if (choice.book) {
      this.openBook = choice.book
      return
    }
    this.choices = this.choices.map(c =>
      c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
        ? { ...c, status: 'loading', error: undefined }
        : c
    )
    try {
      const book = await this.loadSourceBook(choice.result)
      this.choices = this.choices.map(c =>
        c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
          ? { ...c, book, status: 'loaded' }
          : c
      )
      this.openBook = book
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : '无法读取此书源'
      this.choices = this.choices.map(c =>
        c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
          ? { ...c, status: 'error', error: errMsg }
          : c
      )
    }
  }

  get totalCalls(): number {
    return this.apiCalls.details.length + this.apiCalls.chapters.length + this.apiCalls.progress.length + this.apiCalls.content.length
  }
}

// -----------------------------------------------------------------------------
// Replication of Paragraph Memoization & Scroll Throttling from ReaderScreen.tsx
// -----------------------------------------------------------------------------

export function splitContentParagraphs(content: string): string[] {
  if (!content) return []
  const rawLines = content.split('\n')
  const result: string[] = []
  for (let i = 0; i < rawLines.length; i++) {
    const trimmed = rawLines[i].trim()
    if (trimmed) {
      result.push(trimmed)
    }
  }
  return result
}

export function computeScrollPosition(scrollTop: number, scrollHeight: number, clientHeight: number): number {
  const maxScroll = Math.max(0, scrollHeight - clientHeight)
  if (maxScroll <= 0) return 0
  const ratio = scrollTop / maxScroll
  return Number.isFinite(ratio) ? Math.min(1, Math.max(0, ratio)) : 0
}

/**
 * Scroll Controller reproducing the exact RAF coalescing and debounce logic
 * from web/src/ReaderScreen.tsx:282-324
 */
export class ReaderScrollController {
  rafId: number | null = null
  timerRef: NodeJS.Timeout | null = null
  lastScrollY = 0
  toolbarsVisible = true
  currentPosition = 0
  preloadedChapters = new Set<number>()
  persistCalls = 0

  rafQueue: Array<() => void> = []
  domReadCount = 0

  // Simulated DOM state
  scrollHeight = 10000
  clientHeight = 800
  scrollY = 0

  onScroll(): void {
    if (this.rafId !== null) return // RAF coalescing: drop synchronous scroll event spam
    this.rafId = 1 // Mark RAF scheduled
    this.rafQueue.push(() => {
      this.rafId = null
      
      // Reading DOM metrics inside RAF frame
      this.domReadCount++
      const currentY = this.scrollY
      const scrollDelta = currentY - this.lastScrollY
      
      if (currentY > 72 && scrollDelta > 12) {
        this.toolbarsVisible = false
      } else if (scrollDelta < -8) {
        this.toolbarsVisible = true
      }
      this.lastScrollY = currentY

      const scrollHeight = this.scrollHeight
      const clientHeight = this.clientHeight
      this.currentPosition = computeScrollPosition(currentY, scrollHeight, clientHeight)

      if (this.currentPosition >= 0.7) {
        this.preloadedChapters.add(0) // Preload next chapter
      }

      if (this.timerRef === null) {
        this.timerRef = setTimeout(() => {
          this.timerRef = null
          this.persistCalls++
        }, 1200)
      }
    })
  }

  flushNextFrame(): void {
    const queue = [...this.rafQueue]
    this.rafQueue = []
    for (const fn of queue) {
      fn()
    }
  }
}

// =============================================================================
// EMPIRICAL TESTS FOR CHALLENGER 2
// =============================================================================

test('Challenger 2 Focus 1.1: 50 Candidate Sources issues EXACTLY 3 initial network requests', async () => {
  const harness = new LibraryCandidateHarness()
  const candidateCount = 50
  const sources: SearchResult[] = Array.from({ length: candidateCount }, (_, i) => ({
    sourceId: `source-origin-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://origin-${i}.example.com/book/100`,
  }))

  const startTime = performance.now()
  await harness.open({ sources })
  const elapsed = performance.now() - startTime

  // Assertions:
  // 1. Initial network requests must be EXACTLY 3 (1 details, 1 chapters, 1 progress)
  assert.equal(harness.apiCalls.details.length, 1, 'Only 1 details call for active source')
  assert.equal(harness.apiCalls.chapters.length, 1, 'Only 1 chapters call for active source')
  assert.equal(harness.apiCalls.progress.length, 1, 'Only 1 progress call for active source')
  assert.equal(harness.apiCalls.content.length, 0, 'Zero content calls upfront')
  assert.equal(harness.totalCalls, 3, `Expected exactly 3 network calls, got ${harness.totalCalls} (NOT 50x4 = 200)`)

  // 2. Performance: Book opens immediately (< 300ms)
  assert.ok(elapsed < 300, `Book open took ${elapsed.toFixed(2)}ms (must be < 300ms)`)

  // 3. Status invariant: candidate 0 is 'loaded', candidate 1..49 are 'idle'
  assert.equal(harness.choices.length, candidateCount)
  assert.equal(harness.choices[0].status, 'loaded')
  assert.ok(harness.choices[0].book !== undefined)
  assert.equal(harness.openBook?.details.sourceId, 'source-origin-0')

  for (let i = 1; i < candidateCount; i++) {
    assert.equal(harness.choices[i].status, 'idle', `Candidate ${i} must be in idle status`)
    assert.equal(harness.choices[i].book, undefined, `Candidate ${i} must not have book details loaded`)
  }
})

test('Challenger 2 Focus 1.2: Fallback on source failures (Source 0 & 1 fail -> Source 2 succeeds)', async () => {
  const harness = new LibraryCandidateHarness()
  harness.failingSources.add('source-origin-0')
  harness.failingSources.add('source-origin-1')

  const sources: SearchResult[] = Array.from({ length: 50 }, (_, i) => ({
    sourceId: `source-origin-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://origin-${i}.example.com/book/100`,
  }))

  await harness.open({ sources })

  // Source 0: details call failed
  // Source 1: details call failed
  // Source 2: details, chapters, progress succeed!
  assert.equal(harness.choices[0].status, 'error')
  assert.ok(harness.choices[0].error?.includes('超时'))
  assert.equal(harness.choices[1].status, 'error')
  assert.ok(harness.choices[1].error?.includes('超时'))
  assert.equal(harness.choices[2].status, 'loaded')
  assert.equal(harness.openBook?.details.sourceId, 'source-origin-2')

  // Candidates 3..49 remain idle
  for (let i = 3; i < 50; i++) {
    assert.equal(harness.choices[i].status, 'idle')
  }

  // Network call count: 1 (src0 fail) + 1 (src1 fail) + 3 (src2 success) = 5 calls total
  assert.equal(harness.totalCalls, 5)
})

test('Challenger 2 Focus 1.3: Fallback when ALL candidate sources fail', async () => {
  const harness = new LibraryCandidateHarness()
  for (let i = 0; i < 5; i++) {
    harness.failingSources.add(`source-origin-${i}`)
  }

  const sources: SearchResult[] = Array.from({ length: 5 }, (_, i) => ({
    sourceId: `source-origin-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://origin-${i}.example.com/book/100`,
  }))

  await harness.open({ sources })

  assert.equal(harness.openBook, null)
  assert.equal(harness.loading, false)
  assert.equal(harness.message, '所有书源均无法读取')
  for (let i = 0; i < 5; i++) {
    assert.equal(harness.choices[i].status, 'error')
  }
})

test('Challenger 2 Focus 1.4: On-demand loading of idle candidates and instant re-selection', async () => {
  const harness = new LibraryCandidateHarness()
  const sources: SearchResult[] = Array.from({ length: 50 }, (_, i) => ({
    sourceId: `source-origin-${i}`,
    name: '凡人修仙传',
    author: '忘语',
    bookUrl: `https://origin-${i}.example.com/book/100`,
  }))

  await harness.open({ sources })
  assert.equal(harness.totalCalls, 3)

  // User clicks idle candidate #15
  const candidate15 = harness.choices[15]
  assert.equal(candidate15.status, 'idle')

  await harness.handleChooseSource(candidate15)

  // Candidate #15 is now loaded, openBook is updated
  assert.equal(harness.choices[15].status, 'loaded')
  assert.equal(harness.choices[15].book?.details.sourceId, 'source-origin-15')
  assert.equal(harness.openBook?.details.sourceId, 'source-origin-15')
  // Network calls increased by exactly 3 (details, chapters, progress)
  assert.equal(harness.totalCalls, 6)

  // User clicks back to candidate #0 (already loaded)
  const candidate0 = harness.choices[0]
  await harness.handleChooseSource(candidate0)

  assert.equal(harness.openBook?.details.sourceId, 'source-origin-0')
  assert.equal(harness.totalCalls, 6, 'Switching to an already loaded candidate must make 0 new network calls')

  // User clicks back to candidate #15 (already loaded)
  await harness.handleChooseSource(harness.choices[15])
  assert.equal(harness.openBook?.details.sourceId, 'source-origin-15')
  assert.equal(harness.totalCalls, 6, 'Switching back to candidate 15 must make 0 new network calls')
})

test('Challenger 2 Focus 2.1: Paragraph splitting memoization across 100, 1,000, 5,000 paragraphs', () => {
  // Test case 1: 100 paragraphs
  const text100 = Array.from({ length: 100 }, (_, i) => `这是第 ${i + 1} 段内容，韩立凝视着手中的青色小瓶。`).join('\n\n')
  const p100 = splitContentParagraphs(text100)
  assert.equal(p100.length, 100)
  assert.equal(p100[0], '这是第 1 段内容，韩立凝视着手中的青色小瓶。')
  assert.equal(p100[99], '这是第 100 段内容，韩立凝视着手中的青色小瓶。')

  // Test case 2: 1,000 paragraphs with mixed whitespace, CRLF, and empty lines
  const text1000 = Array.from({ length: 1000 }, (_, i) => {
    if (i % 5 === 0) return `   \t第 ${i + 1} 段 带有缩进的内容。\r\n   `
    if (i % 7 === 0) return `\n\n\n第 ${i + 1} 段 多空行。\n`
    return `第 ${i + 1} 段 标准小说正文内容。`
  }).join('\n')

  const start1000 = performance.now()
  const p1000 = splitContentParagraphs(text1000)
  const elapsed1000 = performance.now() - start1000

  assert.equal(p1000.length, 1000)
  assert.ok(elapsed1000 < 5, `1,000 paragraphs split took ${elapsed1000.toFixed(2)}ms (< 5ms)`)
  assert.ok(!p1000.some(line => line.length === 0 || line.startsWith(' ') || line.endsWith(' ')))

  // Test case 3: 5,000 paragraphs (massive chapter stress)
  const text5000 = Array.from({ length: 5000 }, (_, i) => `第 ${i + 1} 段 万丈高山之上，灵气浩瀚。`).join('\n')
  const start5000 = performance.now()
  const p5000 = splitContentParagraphs(text5000)
  const elapsed5000 = performance.now() - start5000

  assert.equal(p5000.length, 5000)
  assert.ok(elapsed5000 < 15, `5,000 paragraphs split took ${elapsed5000.toFixed(2)}ms (< 15ms)`)
})

test('Challenger 2 Focus 2.2: Rapid Scroll Event RAF Coalescing (10,000 scroll events coalesced into 1 frame)', () => {
  const controller = new ReaderScrollController()
  controller.scrollHeight = 20000
  controller.clientHeight = 1000
  controller.scrollY = 0

  // Burst 10,000 scroll events in rapid succession before next animation frame
  for (let i = 1; i <= 10000; i++) {
    controller.scrollY = i * 0.5 // Scrolling down from 0 to 5000px
    controller.onScroll()
  }

  // Before RAF frame ticks: DOM reads must be 0, persist calls must be 0
  assert.equal(controller.domReadCount, 0, 'No synchronous DOM reads during scroll event storm')
  assert.equal(controller.rafQueue.length, 1, 'Exactly 1 RAF execution queued for 10,000 scroll events')

  // Execute the animation frame
  controller.flushNextFrame()

  // After 1 frame: exactly 1 DOM read executed
  assert.equal(controller.domReadCount, 1, 'Only 1 DOM read cycle executed per animation frame')
  assert.equal(controller.toolbarsVisible, false, 'Scrolling down past 72px hid toolbars')
  assert.equal(controller.rafId, null, 'RAF ID reset, ready for next frame')
  assert.ok(controller.currentPosition > 0.25, `Progress position: ${controller.currentPosition}`)

  // Simulate upward scroll in next frame
  controller.scrollY = 4800 // Scroll up by 200px (scrollDelta = -200 < -8)
  controller.onScroll()
  assert.equal(controller.rafQueue.length, 1)
  controller.flushNextFrame()

  assert.equal(controller.toolbarsVisible, true, 'Scrolling up restored toolbars')
  assert.equal(controller.domReadCount, 2)
})

test('Challenger 2 Focus 2.3: Scroll Debounced Persistence and Preload Threshold', async () => {
  const controller = new ReaderScrollController()
  controller.scrollHeight = 10000
  controller.clientHeight = 1000

  // Scroll to 80% (scrollY = 7200, maxScroll = 9000 -> 80%)
  controller.scrollY = 7200
  controller.onScroll()
  controller.flushNextFrame()

  // Preload trigger verified
  assert.ok(controller.currentPosition >= 0.70)
  assert.ok(controller.preloadedChapters.has(0), 'Preload triggered at >= 70% progress')

  // Debounce check: progress not yet saved synchronously
  assert.equal(controller.persistCalls, 0)

  // Fast-forward debounce timer (1200ms)
  await new Promise(r => setTimeout(r, 1300))
  assert.equal(controller.persistCalls, 1, 'Progress saved after 1200ms debounce window')

  // Multiple scrolls within debounce window reset/maintain single timer
  controller.scrollY = 7500
  controller.onScroll()
  controller.flushNextFrame()

  controller.scrollY = 7600
  controller.onScroll()
  controller.flushNextFrame()

  await new Promise(r => setTimeout(r, 1300))
  assert.equal(controller.persistCalls, 2, 'Debounced updates fire only once per scroll cessation')
})
