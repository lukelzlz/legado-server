import test from 'node:test'
import assert from 'node:assert/strict'

export type Chapter = {
  index: number
  title: string
  url: string
}

/**
 * Direct replication of VirtualChapterList virtual window and spacer calculation
 * from web/src/ReaderScreen.tsx:107-113
 */
export function calculateVirtualChapterListState({
  count,
  itemHeight = 36,
  overscan = 6,
  scrollTop,
  containerHeight = 600,
}: {
  count: number
  itemHeight?: number
  overscan?: number
  scrollTop: number
  containerHeight?: number
}) {
  const startIndex = Math.max(0, Math.floor(scrollTop / itemHeight) - overscan)
  const endIndex = Math.min(count, Math.ceil((scrollTop + containerHeight) / itemHeight) + overscan)
  const topSpacer = startIndex * itemHeight
  const bottomSpacer = Math.max(0, (count - endIndex) * itemHeight)
  const renderedCount = Math.max(0, endIndex - startIndex)
  const renderedItemsHeight = renderedCount * itemHeight
  const totalVirtualHeight = topSpacer + renderedItemsHeight + bottomSpacer

  return {
    startIndex,
    endIndex,
    renderedCount,
    topSpacer,
    bottomSpacer,
    renderedItemsHeight,
    totalVirtualHeight,
  }
}

/**
 * Direct replication of scrollToActive calculation from web/src/ReaderScreen.tsx:80-94
 */
export function calculateAutoScroll({
  targetIndex,
  itemHeight = 36,
  containerHeight = 600,
  totalCount,
}: {
  targetIndex: number
  itemHeight?: number
  containerHeight?: number
  totalCount: number
}) {
  if (targetIndex < 0 || targetIndex >= totalCount) return 0
  const itemTop = targetIndex * itemHeight
  const targetScroll = Math.max(0, itemTop - (containerHeight - itemHeight) / 2)
  const maxScroll = Math.max(0, totalCount * itemHeight - containerHeight)
  const finalScroll = Math.min(targetScroll, maxScroll)
  return finalScroll
}

/**
 * Direct replication of chapter search filtering from web/src/ReaderScreen.tsx:173-182
 */
export function executeChapterFilter(chapters: Chapter[], deferredQuery: string): Chapter[] {
  const query = deferredQuery.trim().toLowerCase()
  if (!query) return chapters
  const numQuery = /^\d+$/.test(query) ? parseInt(query, 10) : null
  return chapters.filter(item => {
    if (item.title.toLowerCase().includes(query)) return true
    if (numQuery !== null && (item.index === numQuery - 1 || item.index === numQuery)) return true
    return false
  })
}

// =============================================================================
// CHALLENGER EMPIRICAL TEST SUITE
// =============================================================================

test('Challenger 1: Bounded DOM Node Count across 0, 1, 50, 5,000, 20,000 chapters', () => {
  const testCounts = [0, 1, 50, 5000, 20000]
  const containerHeight = 600
  const itemHeight = 36
  const overscan = 6

  for (const count of testCounts) {
    const totalHeight = count * itemHeight
    const maxScroll = Math.max(0, totalHeight - containerHeight)
    
    // Sample 50 scroll positions between 0 and maxScroll
    const samplePoints = count <= 1 ? [0] : Array.from({ length: 50 }, (_, i) => (i / 49) * maxScroll)
    
    let maxRendered = 0
    for (const scrollTop of samplePoints) {
      const state = calculateVirtualChapterListState({
        count,
        itemHeight,
        overscan,
        scrollTop,
        containerHeight,
      })

      if (state.renderedCount > maxRendered) {
        maxRendered = state.renderedCount
      }

      // Assert DOM rendered count <= 35 elements
      assert.ok(
        state.renderedCount <= 35,
        `For count=${count} at scrollTop=${scrollTop}, renderedCount=${state.renderedCount} exceeded limit of 35`
      )

      // Assert total virtual height invariant
      assert.equal(
        state.totalVirtualHeight,
        totalHeight,
        `Virtual height sum must equal total height (${totalHeight}px), got ${state.totalVirtualHeight}px`
      )
    }

    if (count === 0) {
      assert.equal(maxRendered, 0)
    } else if (count === 1) {
      assert.equal(maxRendered, 1)
    } else {
      // With viewport=600, itemHeight=36 (16.67 items visible) + 2*overscan (12) = ~29 items max
      assert.ok(maxRendered <= 30, `Max rendered elements for ${count} items was ${maxRendered} (expected <= 30)`)
    }
  }
})

test('Challenger 1: Spacer Formulas Precision at scrollTop = 0, mid-list, and end-of-list', () => {
  const count = 5000
  const itemHeight = 36
  const containerHeight = 600
  const overscan = 6
  const totalHeight = count * itemHeight // 180,000px
  const maxScroll = totalHeight - containerHeight // 179,400px

  // 1. Top of list: scrollTop = 0
  {
    const topState = calculateVirtualChapterListState({ count, itemHeight, overscan, scrollTop: 0, containerHeight })
    assert.equal(topState.startIndex, 0, 'Top startIndex must be 0')
    assert.equal(topState.topSpacer, 0, 'Top topSpacer must be 0')
    // ceil(600/36) + 6 = 17 + 6 = 23
    assert.equal(topState.endIndex, 23, 'Top endIndex must be 23')
    assert.equal(topState.renderedCount, 23)
    assert.equal(topState.bottomSpacer, (5000 - 23) * 36, 'Bottom spacer must cover remaining items')
    assert.equal(topState.topSpacer + topState.renderedItemsHeight + topState.bottomSpacer, 180000)
  }

  // 2. Mid-list: scrollTop = 90,000px (around chapter 2500)
  {
    const midScroll = 90000
    const midState = calculateVirtualChapterListState({ count, itemHeight, overscan, scrollTop: midScroll, containerHeight })
    // floor(90000/36) - 6 = 2500 - 6 = 2494
    assert.equal(midState.startIndex, 2494)
    assert.equal(midState.topSpacer, 2494 * 36) // 89,784px
    // ceil((90000+600)/36) + 6 = ceil(90600/36) + 6 = 2517 + 6 = 2523
    assert.equal(midState.endIndex, 2523)
    assert.equal(midState.renderedCount, 2523 - 2494) // 29 items
    assert.equal(midState.bottomSpacer, (5000 - 2523) * 36) // 2477 * 36 = 89,172px
    assert.equal(midState.topSpacer + midState.renderedItemsHeight + midState.bottomSpacer, 180000)
  }

  // 3. End of list: scrollTop = maxScroll (179,400px)
  {
    const endState = calculateVirtualChapterListState({ count, itemHeight, overscan, scrollTop: maxScroll, containerHeight })
    assert.equal(endState.endIndex, 5000, 'End endIndex must be clamped to count')
    assert.equal(endState.bottomSpacer, 0, 'End bottomSpacer must be 0')
    // floor(179400/36) - 6 = 4983 - 6 = 4977
    assert.equal(endState.startIndex, 4977)
    assert.equal(endState.topSpacer, 4977 * 36) // 179,172px
    assert.equal(endState.renderedCount, 5000 - 4977) // 23 items
    assert.equal(endState.topSpacer + endState.renderedItemsHeight + endState.bottomSpacer, 180000)
  }
})

test('Challenger 1: Centered Auto-Scroll Target Calculations (targetIndex = 0, 2500, 4999)', () => {
  const count = 5000
  const itemHeight = 36
  const containerHeight = 600
  const maxScroll = count * itemHeight - containerHeight // 179,400px

  // Case A: targetIndex = 0 (Start of book)
  // itemTop = 0, (600 - 36)/2 = 282. targetScroll = max(0, 0 - 282) = 0.
  const scroll0 = calculateAutoScroll({ targetIndex: 0, itemHeight, containerHeight, totalCount: count })
  assert.equal(scroll0, 0, 'targetIndex=0 autoScroll must be 0')

  // Case B: targetIndex = 2500 (Mid book)
  // itemTop = 2500 * 36 = 90,000. targetScroll = 90,000 - 282 = 89,718.
  const scroll2500 = calculateAutoScroll({ targetIndex: 2500, itemHeight, containerHeight, totalCount: count })
  assert.equal(scroll2500, 89718, 'targetIndex=2500 autoScroll must be 89,718px')
  
  // Verify geometric centering:
  // Item center in container coordinate: itemTop + 18 = 90018
  // Viewport center in container coordinate: scrollTop + 300 = 89718 + 300 = 90018
  const itemCenter = 2500 * itemHeight + itemHeight / 2
  const viewportCenter = scroll2500 + containerHeight / 2
  assert.equal(viewportCenter, itemCenter, 'Item must be exactly centered in container viewport')

  // Case C: targetIndex = 4999 (End of book)
  // itemTop = 4999 * 36 = 179,964. targetScroll = 179,964 - 282 = 179,682 > maxScroll(179,400)
  // clamped to maxScroll = 179,400.
  const scroll4999 = calculateAutoScroll({ targetIndex: 4999, itemHeight, containerHeight, totalCount: count })
  assert.equal(scroll4999, maxScroll, `targetIndex=4999 must clamp to maxScroll (${maxScroll})`)
})

test('Challenger 1: Search Filtering Speed Benchmark across 5,000 and 20,000 chapters', () => {
  // Generate 5,000 chapters
  const chapters5k: Chapter[] = Array.from({ length: 5000 }, (_, i) => ({
    index: i,
    title: `第${i + 1}章 宇宙星神·修真大道之卷第${i + 1}节`,
    url: `/book/mock/c-${i}`,
  }))

  const testQueries = ['100', '2500', '4999', '宇宙星神', '第3888章', '不存在的关键字', '']

  // Pre-test warm up
  for (const q of testQueries) {
    executeChapterFilter(chapters5k, q)
  }

  for (const q of testQueries) {
    const times: number[] = []
    let result: Chapter[] = []
    for (let iter = 0; iter < 5; iter++) {
      const start = performance.now()
      result = executeChapterFilter(chapters5k, q)
      times.push(performance.now() - start)
    }
    const avgTime = times.reduce((a, b) => a + b, 0) / times.length
    const minTime = Math.min(...times)
    
    // In steady state, filtering 5,000 items in V8 takes < 5ms (min: ~2.5-4ms, avg < 20ms)
    assert.ok(
      minTime < 10,
      `Filtering "${q}" across 5,000 items min took ${minTime.toFixed(3)}ms (target min < 10ms)`
    )
    assert.ok(
      avgTime < 25,
      `Filtering "${q}" across 5,000 items avg took ${avgTime.toFixed(3)}ms (target avg < 25ms)`
    )

    if (q === '100') {
      // Should match chapter index 99 (title has 100), index 100 (numQuery-1 or numQuery)
      assert.ok(result.length > 0)
    } else if (q === '2500') {
      assert.ok(result.some(c => c.index === 2499 || c.index === 2500))
    }
  }

  // Extreme stress: 20,000 chapters
  const chapters20k: Chapter[] = Array.from({ length: 20000 }, (_, i) => ({
    index: i,
    title: `第${i + 1}章 绝世神通·万古长青第${i + 1}章`,
    url: `/book/mock/c-${i}`,
  }))

  for (const q of testQueries) {
    const times: number[] = []
    let result: Chapter[] = []
    for (let iter = 0; iter < 5; iter++) {
      const start = performance.now()
      result = executeChapterFilter(chapters20k, q)
      times.push(performance.now() - start)
    }
    const avgTime = times.reduce((a, b) => a + b, 0) / times.length
    
    // For 20,000 items, average time should be under 50ms (W3C Long Task boundary)
    assert.ok(
      avgTime < 50,
      `Filtering "${q}" across 20,000 items avg took ${avgTime.toFixed(3)}ms (target < 50ms)`
    )
    assert.ok(Array.isArray(result))
  }
})

test('Challenger 1: Mobile TOC Sheet with itemHeight=44px', () => {
  // Mobile drawer uses itemHeight=44, viewport height typically 56vh ~ 450px
  const count = 5000
  const itemHeight = 44
  const containerHeight = 450
  const overscan = 6

  const state = calculateVirtualChapterListState({
    count,
    itemHeight,
    overscan,
    scrollTop: 22000,
    containerHeight,
  })

  // ceil(450 / 44) = 11. 11 + 2*6 = 23 nodes.
  assert.ok(state.renderedCount <= 25, `Mobile sheet renderedCount=${state.renderedCount} (expected <= 25)`)
  assert.equal(state.totalVirtualHeight, 5000 * 44)
})

test('Challenger 1: Robustness against extreme overscroll and negative scrollTop', () => {
  const count = 5000
  const itemHeight = 36
  const containerHeight = 600

  // Negative scrollTop (iOS elastic bounce)
  const negState = calculateVirtualChapterListState({
    count,
    itemHeight,
    overscan: 6,
    scrollTop: -200,
    containerHeight,
  })
  assert.equal(negState.startIndex, 0)
  assert.equal(negState.topSpacer, 0)
  assert.ok(negState.renderedCount <= 30)

  // Massive scrollTop (> maxScroll)
  const overflowState = calculateVirtualChapterListState({
    count,
    itemHeight,
    overscan: 6,
    scrollTop: 9999999,
    containerHeight,
  })
  assert.equal(overflowState.endIndex, 5000)
  assert.equal(overflowState.bottomSpacer, 0)
})
