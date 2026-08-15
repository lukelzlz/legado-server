import test from 'node:test'
import assert from 'node:assert/strict'

export type VirtualSliceConfig = {
  totalCount: number
  itemHeight: number
  scrollTop: number
  viewportHeight: number
  overscan?: number
}

export type VirtualSliceResult = {
  totalHeight: number
  startIndex: number
  endIndex: number
  renderedCount: number
  offsetY: number
}

/**
 * Computes the virtualized window slice for a list of items with fixed height.
 * Guarantees renderedCount is strictly bounded by viewport size + 2 * overscan.
 */
export function computeVirtualSlice(config: VirtualSliceConfig): VirtualSliceResult {
  const { totalCount, itemHeight, scrollTop, viewportHeight, overscan = 6 } = config
  const totalHeight = totalCount * itemHeight
  if (totalCount <= 0 || itemHeight <= 0) {
    return { totalHeight: 0, startIndex: 0, endIndex: 0, renderedCount: 0, offsetY: 0 }
  }

  const maxScroll = Math.max(0, totalHeight - viewportHeight)
  const clampedScrollTop = Math.max(0, Math.min(maxScroll, Number.isFinite(scrollTop) ? scrollTop : 0))

  const rawStart = Math.floor(clampedScrollTop / itemHeight) - overscan
  const startIndex = Math.max(0, Math.min(totalCount - 1, rawStart))

  const rawEnd = Math.ceil((clampedScrollTop + viewportHeight) / itemHeight) + overscan
  const endIndex = Math.min(totalCount, Math.max(startIndex, rawEnd))

  const renderedCount = Math.max(0, endIndex - startIndex)
  const offsetY = startIndex * itemHeight

  return { totalHeight, startIndex, endIndex, renderedCount, offsetY }
}

/**
 * Computes the target scrollTop offset required to center a specific chapter in the viewport.
 */
export function computeAutoScrollOffset(
  chapterIndex: number,
  itemHeight: number,
  viewportHeight: number,
  totalCount: number
): number {
  if (totalCount <= 0 || itemHeight <= 0 || viewportHeight <= 0) return 0
  const totalHeight = totalCount * itemHeight
  const maxScroll = Math.max(0, totalHeight - viewportHeight)
  const clampedIndex = Math.max(0, Math.min(totalCount - 1, chapterIndex))
  const targetTop = clampedIndex * itemHeight - (viewportHeight / 2 - itemHeight / 2)
  return Math.max(0, Math.min(maxScroll, Math.round(targetTop)))
}

// -----------------------------------------------------------------------------
// Tier 1: Core Feature Tests (5,000 Chapter TOC & Virtualization Math)
// -----------------------------------------------------------------------------

test('VirtualChapterList - Tier 1: Total scroll height linear scaling (5,000 chapters)', () => {
  const slice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: 0,
    viewportHeight: 500,
  })
  assert.equal(slice.totalHeight, 180000, '5000 * 36px must equal 180,000px')
})

test('VirtualChapterList - Tier 1: Initial viewport renders bounded slice (<= 25-30 nodes)', () => {
  const slice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: 0,
    viewportHeight: 500,
    overscan: 6,
  })
  assert.equal(slice.startIndex, 0)
  assert.equal(slice.endIndex, 20) // ceil(500/36) + 6 = 14 + 6 = 20
  assert.equal(slice.renderedCount, 20)
  assert.equal(slice.offsetY, 0)
  assert.ok(slice.renderedCount <= 30, `Expected <= 30 nodes, got ${slice.renderedCount}`)
})

test('VirtualChapterList - Tier 1: Middle-scroll slice calculation (chapter 1,000) and item offset', () => {
  const chapter1000ScrollTop = 1000 * 36 // 36,000px
  const slice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: chapter1000ScrollTop,
    viewportHeight: 500,
    overscan: 6,
  })
  assert.equal(slice.startIndex, 994) // 1000 - 6
  assert.equal(slice.endIndex, 1020) // ceil(36500/36) + 6 = 1014 + 6 = 1020
  assert.equal(slice.renderedCount, 26)
  assert.equal(slice.offsetY, 994 * 36) // 35,784px
  assert.ok(slice.renderedCount <= 30)

  // Verify specific item absolute position
  const itemIndex = 1000
  const itemTop = itemIndex * 36
  assert.equal(itemTop, 36000)
  assert.ok(itemIndex >= slice.startIndex && itemIndex < slice.endIndex, 'Target chapter 1000 must be in slice')
})

test('VirtualChapterList - Tier 1: End-of-list scroll clamping & boundary protection', () => {
  const maxScrollTop = 5000 * 36 - 500 // 179,500px
  const slice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: maxScrollTop,
    viewportHeight: 500,
    overscan: 6,
  })
  assert.equal(slice.endIndex, 5000)
  assert.equal(slice.startIndex, 4980) // floor(179500/36) - 6 = 4986 - 6 = 4980
  assert.equal(slice.renderedCount, 20)
  assert.equal(slice.offsetY, 4980 * 36)
  assert.ok(slice.renderedCount <= 30)
})

test('VirtualChapterList - Tier 1: Auto-scroll viewport centering math', () => {
  // Center chapter 2,500 in a 500px viewport with 36px item height
  const offset = computeAutoScrollOffset(2500, 36, 500, 5000)
  // Expected: 2500 * 36 - (500/2 - 36/2) = 90000 - (250 - 18) = 90000 - 232 = 89,768px
  assert.equal(offset, 89768)

  // Validate centering condition: scrollTop + halfViewport == itemCenter
  const viewportCenterInContainer = offset + 500 / 2
  const itemCenterInContainer = 2500 * 36 + 36 / 2
  assert.equal(viewportCenterInContainer, itemCenterInContainer)

  // Near top boundary: chapter 2 should clamp cleanly without negative offset
  const topOffset = computeAutoScrollOffset(2, 36, 500, 5000)
  // 2 * 36 - 232 = 72 - 232 = -160 => clamped to 0
  assert.equal(topOffset, 0)

  // Near bottom boundary: chapter 4998 should clamp to maxScroll (179,500px)
  const bottomOffset = computeAutoScrollOffset(4998, 36, 500, 5000)
  assert.equal(bottomOffset, 179500)
})

// -----------------------------------------------------------------------------
// Tier 2: Boundary & Corner Cases
// -----------------------------------------------------------------------------

test('VirtualChapterList - Tier 2: Zero chapters handling (empty list)', () => {
  const slice = computeVirtualSlice({
    totalCount: 0,
    itemHeight: 36,
    scrollTop: 0,
    viewportHeight: 500,
  })
  assert.equal(slice.totalHeight, 0)
  assert.equal(slice.startIndex, 0)
  assert.equal(slice.endIndex, 0)
  assert.equal(slice.renderedCount, 0)
  assert.equal(slice.offsetY, 0)

  const offset = computeAutoScrollOffset(0, 36, 500, 0)
  assert.equal(offset, 0)
})

test('VirtualChapterList - Tier 2: Single chapter boundary handling', () => {
  const slice = computeVirtualSlice({
    totalCount: 1,
    itemHeight: 36,
    scrollTop: 0,
    viewportHeight: 500,
  })
  assert.equal(slice.totalHeight, 36)
  assert.equal(slice.startIndex, 0)
  assert.equal(slice.endIndex, 1)
  assert.equal(slice.renderedCount, 1)
  assert.equal(slice.offsetY, 0)

  const offset = computeAutoScrollOffset(0, 36, 500, 1)
  assert.equal(offset, 0)
})

test('VirtualChapterList - Tier 2: Viewport height exceeding total content height', () => {
  const slice = computeVirtualSlice({
    totalCount: 10,
    itemHeight: 36,
    scrollTop: 0,
    viewportHeight: 1000, // content height is 360px < 1000px
  })
  assert.equal(slice.totalHeight, 360)
  assert.equal(slice.startIndex, 0)
  assert.equal(slice.endIndex, 10)
  assert.equal(slice.renderedCount, 10)
  assert.equal(slice.offsetY, 0)
})

test('VirtualChapterList - Tier 2: Negative/overscroll scrollTop & NaN defense', () => {
  // Negative scrollTop (e.g. mobile rubber-band bounce)
  const negSlice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: -350,
    viewportHeight: 500,
  })
  assert.equal(negSlice.startIndex, 0)
  assert.equal(negSlice.offsetY, 0)

  // Excessive overscroll past bottom
  const overSlice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: 999999,
    viewportHeight: 500,
  })
  assert.equal(overSlice.endIndex, 5000)
  assert.ok(overSlice.renderedCount <= 30)

  // NaN scrollTop defense
  const nanSlice = computeVirtualSlice({
    totalCount: 5000,
    itemHeight: 36,
    scrollTop: NaN,
    viewportHeight: 500,
  })
  assert.equal(nanSlice.startIndex, 0)
  assert.equal(nanSlice.renderedCount, 20)
})

test('VirtualChapterList - Tier 2: Extreme scale benchmark (50,000 chapters)', () => {
  const start = performance.now()
  const iterations = 1000
  for (let i = 0; i < iterations; i++) {
    const scrollTop = (i / iterations) * (50000 * 36 - 600)
    const slice = computeVirtualSlice({
      totalCount: 50000,
      itemHeight: 36,
      scrollTop,
      viewportHeight: 600,
      overscan: 6,
    })
    assert.ok(slice.renderedCount <= 32, `Rendered count ${slice.renderedCount} must stay bounded`)
    assert.ok(slice.startIndex >= 0 && slice.endIndex <= 50000)
  }
  const elapsed = performance.now() - start
  assert.ok(elapsed < 20, `1,000 slice computations on 50,000 items took ${elapsed.toFixed(2)}ms (target < 20ms)`)
})
