import test from 'node:test'
import assert from 'node:assert/strict'

/**
 * Splits multiline chapter content into clean, trimmed paragraph strings.
 */
export function splitParagraphs(content: string): string[] {
  if (!content || !content.trim()) return []
  return content
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean)
}

// Bounded in-memory paragraph memoization cache
const paragraphCache = new Map<string, string[]>()
const MAX_MEMO_ENTRIES = 100

/**
 * Memoized paragraph splitter to prevent redundant string allocations in React renders.
 */
export function memoizedSplitParagraphs(content: string): string[] {
  const cached = paragraphCache.get(content)
  if (cached) return cached

  const result = splitParagraphs(content)

  if (paragraphCache.size >= MAX_MEMO_ENTRIES) {
    const oldestKey = paragraphCache.keys().next().value
    if (oldestKey !== undefined) {
      paragraphCache.delete(oldestKey)
    }
  }

  paragraphCache.set(content, result)
  return result
}

/**
 * Clamps numeric scroll position to [0.0, 1.0], defending against NaN/Infinity.
 */
export function clampScrollPosition(value: number): number {
  return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0
}

/**
 * Computes normalized scroll progress [0.0, 1.0] from scroll metrics.
 */
export function scrollPosition(scrollTop: number, scrollHeight: number, clientHeight: number): number {
  const maxScroll = Math.max(0, scrollHeight - clientHeight)
  if (maxScroll <= 0) return 0
  return clampScrollPosition(scrollTop / maxScroll)
}

/**
 * Checks whether scroll position reaches the 70% threshold for next chapter preloading.
 */
export function shouldPreloadNextChapter(progress: number): boolean {
  return progress >= 0.70
}

/**
 * Bounded LRU Preload Cache with explicit maximum size eviction.
 */
export class BoundedPreloadCache<K, V> {
  private cache = new Map<K, V>()
  maxSize: number

  constructor(maxSize = 5) {
    this.maxSize = maxSize
  }

  get(key: K): V | undefined {
    const value = this.cache.get(key)
    if (value !== undefined) {
      // Refresh MRU order
      this.cache.delete(key)
      this.cache.set(key, value)
    }
    return value
  }

  set(key: K, value: V): void {
    if (this.cache.has(key)) {
      this.cache.delete(key)
    } else if (this.cache.size >= this.maxSize) {
      const oldestKey = this.cache.keys().next().value
      if (oldestKey !== undefined) {
        this.cache.delete(oldestKey)
      }
    }
    this.cache.set(key, value)
  }

  has(key: K): boolean {
    return this.cache.has(key)
  }

  get size(): number {
    return this.cache.size
  }

  clear(): void {
    this.cache.clear()
  }
}

// -----------------------------------------------------------------------------
// Tier 1: Feature Coverage (Paragraph Splitting, Memoization & Scroll Preload)
// -----------------------------------------------------------------------------

test('ReaderOptimization - Tier 1: Clean multiline paragraph splitting and whitespace trimming', () => {
  const raw = '   第一段内容。  \n\n\n  第二段内容。\r\n  第三段内容。\n   '
  const result = splitParagraphs(raw)
  assert.deepEqual(result, ['第一段内容。', '第二段内容。', '第三段内容。'])
})

test('ReaderOptimization - Tier 1: Paragraph memoization cache hits return identical array reference', () => {
  const content = '落魄少年韩立，偶得神秘小瓶...\n开始修仙之路。'
  const p1 = memoizedSplitParagraphs(content)
  const p2 = memoizedSplitParagraphs(content)
  assert.equal(p1, p2, 'Repeated calls with identical content must return same array reference')
})

test('ReaderOptimization - Tier 1: Normalized scroll progress calculation', () => {
  // scrollTop = 500px, scrollHeight = 1500px, clientHeight = 500px => maxScroll = 1000px => 50%
  const progress = scrollPosition(500, 1500, 500)
  assert.equal(progress, 0.5)
})

test('ReaderOptimization - Tier 1: Scroll progress clamping [0.0, 1.0]', () => {
  // Negative scroll (mobile pull down)
  assert.equal(scrollPosition(-100, 1500, 500), 0.0)
  // Overscroll beyond bottom
  assert.equal(scrollPosition(2500, 1500, 500), 1.0)
})

test('ReaderOptimization - Tier 1: Next chapter preload trigger at >= 70% scroll position', () => {
  assert.equal(shouldPreloadNextChapter(0.69), false)
  assert.equal(shouldPreloadNextChapter(0.6999), false)
  assert.equal(shouldPreloadNextChapter(0.70), true)
  assert.equal(shouldPreloadNextChapter(0.75), true)
  assert.equal(shouldPreloadNextChapter(1.0), true)
})

// -----------------------------------------------------------------------------
// Tier 2: Boundary & Corner Cases
// -----------------------------------------------------------------------------

test('ReaderOptimization - Tier 2: Empty and whitespace-only text handling', () => {
  assert.deepEqual(splitParagraphs(''), [])
  assert.deepEqual(splitParagraphs('   \n\t  \n  '), [])
  assert.deepEqual(memoizedSplitParagraphs(''), [])
})

test('ReaderOptimization - Tier 2: Single long paragraph without newlines', () => {
  const longParagraph = '这是一段非常非常长的连续小说文本没有换行符'.repeat(200)
  const result = splitParagraphs(longParagraph)
  assert.equal(result.length, 1)
  assert.equal(result[0], longParagraph)
})

test('ReaderOptimization - Tier 2: Zero max-scroll defense (content <= viewport)', () => {
  // Content fits entirely within viewport (e.g. short chapter 400px in 800px window)
  assert.equal(scrollPosition(0, 400, 800), 0.0)
  assert.equal(scrollPosition(100, 800, 800), 0.0)
})

test('ReaderOptimization - Tier 2: NaN, Infinity, and invalid float defense', () => {
  assert.equal(clampScrollPosition(NaN), 0.0)
  assert.equal(clampScrollPosition(Infinity), 0.0)
  assert.equal(clampScrollPosition(-Infinity), 0.0)
  assert.equal(scrollPosition(NaN, 1000, 500), 0.0)
})

test('ReaderOptimization - Tier 2: Bounded preload LRU cache eviction (max 5 items)', () => {
  const cache = new BoundedPreloadCache<string, string>(5)

  for (let i = 1; i <= 10; i++) {
    cache.set(`ch-${i}`, `Chapter ${i} Content`)
  }

  assert.equal(cache.size, 5)
  // Chapters 1..5 should be evicted
  for (let i = 1; i <= 5; i++) {
    assert.equal(cache.has(`ch-${i}`), false, `Chapter ${i} should have been evicted`)
  }
  // Chapters 6..10 should be present
  for (let i = 6; i <= 10; i++) {
    assert.equal(cache.has(`ch-${i}`), true, `Chapter ${i} should be present`)
  }

  // Accessing chapter 6 promotes it to MRU
  assert.equal(cache.get('ch-6'), 'Chapter 6 Content')

  // Adding chapter 11 should now evict ch-7 (not ch-6)
  cache.set('ch-11', 'Chapter 11 Content')
  assert.equal(cache.size, 5)
  assert.equal(cache.has('ch-7'), false, 'Chapter 7 should be evicted next')
  assert.equal(cache.has('ch-6'), true, 'Chapter 6 was accessed and should not be evicted')
  assert.equal(cache.has('ch-11'), true)
})
