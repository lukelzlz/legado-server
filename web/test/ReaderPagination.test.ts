import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  chapterTurnClassName,
  dominantScrollSection,
  isScrollSectionTransitionAllowed,
  findFirstFullyVisibleParagraphIndex,
  calculatePaginationLayout,
  isAtBottomBoundary,
  isAtTopBoundary,
  isDoubleColumnActive,
  isTapGesture,
  paginateTapZone,
  scrollCompensation,
  scrollDominantThreshold,
  scrollTapZone,
  splitParagraphs,
  swipeDirection,
} from '../src/readerInteractions'
import {
  defaultReaderSettings,
  getReaderFontFamily,
  loadReaderSettings,

  parseColumnMode,
  parseMaxWidth,
  parsePageMode,
  parseReaderFont,
  saveReaderSettings,
} from '../src/readerSettings'
import {
  DEFAULT_KEEP_BOUNDS,
  desiredScrollWindow,
  scrollSectionKey,
  scrollWindowInvariantViolations,
  scrollWindowTrimPlan,
  trimCompensation,
} from '../src/readerScrollWindow'
import { classifyScrollEngine, decideScrollStrategy, SCROLL_STRATEGY_STORAGE_KEY } from '../src/readerScrollStrategy'

test('ReaderPagination - Tap Zones: Scroll reading mode tap zone partitions (top 30%, bottom 30%, middle 40%)', () => {
  const vh = 1000
  assert.equal(scrollTapZone(100, vh), 'previous', 'Top 10% should trigger previous')
  assert.equal(scrollTapZone(299, vh), 'previous', 'Top 29.9% should trigger previous')
  assert.equal(scrollTapZone(300, vh), 'toggle', 'At 30% should trigger toggle menu')
  assert.equal(scrollTapZone(500, vh), 'toggle', 'Middle 50% should trigger toggle menu')
  assert.equal(scrollTapZone(699, vh), 'toggle', '69.9% should trigger toggle menu')
  assert.equal(scrollTapZone(700, vh), 'next', '70% should trigger next')
  assert.equal(scrollTapZone(950, vh), 'next', '95% should trigger next')
})

test('ReaderPagination - Tap Zones: Paginated reading mode tap zone partitions (left 30%, right 30%, middle 40%)', () => {
  const vw = 800
  assert.equal(paginateTapZone(50, vw), 'previous', 'Left zone should trigger previous page')
  assert.equal(paginateTapZone(239, vw), 'previous', 'Left boundary should trigger previous page')
  assert.equal(paginateTapZone(240, vw), 'toggle', 'Middle boundary should trigger toggle')
  assert.equal(paginateTapZone(400, vw), 'toggle', 'Center should trigger toggle')
  assert.equal(paginateTapZone(559, vw), 'toggle', 'Middle right boundary should trigger toggle')
  assert.equal(paginateTapZone(560, vw), 'next', 'Right boundary should trigger next page')
  assert.equal(paginateTapZone(780, vw), 'next', 'Far right should trigger next page')
})

test('ReaderPagination - Boundaries: Top and bottom scroll boundary detection', () => {
  // Top boundary (threshold = 5)
  assert.equal(isAtTopBoundary(0), true)
  assert.equal(isAtTopBoundary(4), true)
  assert.equal(isAtTopBoundary(5), true)
  assert.equal(isAtTopBoundary(6), false)
  assert.equal(isAtTopBoundary(500), false)

  // Bottom boundary (scrollHeight = 2000, clientHeight = 800 => maxScroll = 1200, threshold = 20)
  assert.equal(isAtBottomBoundary(1200, 2000, 800), true, 'Exact bottom is at boundary')
  assert.equal(isAtBottomBoundary(1190, 2000, 800), true, 'Within 20px threshold is at boundary')
  assert.equal(isAtBottomBoundary(1180, 2000, 800), true, 'At 20px threshold is at boundary')
  assert.equal(isAtBottomBoundary(1170, 2000, 800), false, '30px above bottom is not at boundary')
  assert.equal(isAtBottomBoundary(500, 2000, 800), false, 'Mid-page is not at boundary')
})

test('ReaderPagination - Gestures: Tap vs swipe gesture recognition', () => {
  // Tap within 10px
  assert.equal(isTapGesture(100, 200, 104, 203), true)
  assert.equal(isTapGesture(100, 200, 120, 200), false)

  // Horizontal swipe gestures
  assert.equal(swipeDirection(300, 200, 200, 205), 'left', 'Swipe left (dx = -100)')
  assert.equal(swipeDirection(100, 200, 220, 195), 'right', 'Swipe right (dx = +120)')

  // Vertical scroll gestures or small movements should not trigger horizontal swipe
  assert.equal(swipeDirection(100, 100, 105, 300), null, 'Vertical drag is not horizontal swipe')
  assert.equal(swipeDirection(100, 100, 115, 110), null, 'Small movement below minDistance is not swipe')
})

test('ReaderPagination - Settings: Font type and pageMode parsing safety', () => {
  assert.equal(parseReaderFont('song'), 'song')
  assert.equal(parseReaderFont('hei'), 'hei')
  assert.equal(parseReaderFont('kai'), 'kai')
  assert.equal(parseReaderFont('fangsong'), 'fangsong')
  assert.equal(parseReaderFont('system'), 'system')
  assert.equal(parseReaderFont('invalid_font'), 'song')
  assert.equal(parseReaderFont(null), 'song')

  assert.equal(parsePageMode('scroll'), 'scroll')
  assert.equal(parsePageMode('paginate'), 'paginate')
  assert.equal(parsePageMode('unknown'), 'scroll')
  assert.equal(parsePageMode(undefined), 'scroll')

  assert.equal(defaultReaderSettings.pageMode, 'scroll')
  assert.equal(defaultReaderSettings.font, 'song')
  assert.equal(defaultReaderSettings.maxWidth, 860)
  assert.equal(defaultReaderSettings.columnMode, 'auto')
  assert.equal(defaultReaderSettings.sidebarPinned, false)
})

test('ReaderPagination - Settings: Max width and column mode parsing safety', () => {
  assert.equal(parseMaxWidth(860), 860)
  assert.equal(parseMaxWidth(560), 560)
  assert.equal(parseMaxWidth(1400), 1400)
  assert.equal(parseMaxWidth(300), 560, 'Clamps below 560 to 560')
  assert.equal(parseMaxWidth(2000), 1400, 'Clamps above 1400 to 1400')
  assert.equal(parseMaxWidth('invalid'), 860, 'Fallback to default 860')
  assert.equal(parseMaxWidth(null), 860, 'Fallback to default 860')
  assert.equal(parseMaxWidth(undefined), 860, 'Fallback to default 860')

  assert.equal(parseColumnMode('auto'), 'auto')
  assert.equal(parseColumnMode('single'), 'single')
  assert.equal(parseColumnMode('double'), 'double')
  assert.equal(parseColumnMode('triple'), 'auto', 'Fallback invalid mode to auto')
  assert.equal(parseColumnMode(null), 'auto', 'Fallback null to auto')
})

test('ReaderPagination - MultiColumn: isDoubleColumnActive condition evaluation', () => {
  // Auto mode: active only when viewport >= 800
  assert.equal(isDoubleColumnActive('auto', 799), false, 'Auto mode < 800px should be single column')
  assert.equal(isDoubleColumnActive('auto', 800), true, 'Auto mode >= 800px should be double column')
  assert.equal(isDoubleColumnActive('auto', 1200), true, 'Auto mode wide screen should be double column')

  // Single mode: always false regardless of viewport
  assert.equal(isDoubleColumnActive('single', 500), false)
  assert.equal(isDoubleColumnActive('single', 800), false)
  assert.equal(isDoubleColumnActive('single', 1400), false)

  // Double mode: always true
  assert.equal(isDoubleColumnActive('double', 600), true)
  assert.equal(isDoubleColumnActive('double', 1000), true)
})

test('ReaderPagination - MultiColumn: calculatePaginationLayout single column layout', () => {
  const layout = calculatePaginationLayout({
    viewportWidth: 600,
    totalScrollWidth: 1880,
    columnGap: 40,
    columnMode: 'single',
  })

  assert.equal(layout.isDoubleColumn, false)
  assert.equal(layout.columnWidth, 600)
  assert.equal(layout.stride, 640) // 600 + 40
  // (1880 + 40) / 640 = 3 pages
  assert.equal(layout.pageCount, 3)
})

test('ReaderPagination - MultiColumn: calculatePaginationLayout double column layout', () => {
  // Viewport 840px, columnGap 40px => columnWidth = floor((840-40)/2) = 400px
  // Stride = 2 * (400 + 40) = 880px
  const layout = calculatePaginationLayout({
    viewportWidth: 840,
    totalScrollWidth: 2600,
    columnGap: 40,
    columnMode: 'double',
  })

  assert.equal(layout.isDoubleColumn, true)
  assert.equal(layout.columnWidth, 400)
  assert.equal(layout.stride, 880)
  // (2600 + 40) / 880 = 3.0 => 3 spreads
  assert.equal(layout.pageCount, 3)
})

test('ReaderPagination - MultiColumn: calculatePaginationLayout auto mode transition', () => {
  // Narrow viewport 700px in auto mode
  const narrowLayout = calculatePaginationLayout({
    viewportWidth: 700,
    totalScrollWidth: 1440,
    columnGap: 40,
    columnMode: 'auto',
  })
  assert.equal(narrowLayout.isDoubleColumn, false)
  assert.equal(narrowLayout.columnWidth, 700)
  assert.equal(narrowLayout.stride, 740)

  // Wide viewport 900px in auto mode
  const wideLayout = calculatePaginationLayout({
    viewportWidth: 900,
    totalScrollWidth: 2660,
    columnGap: 40,
    columnMode: 'auto',
  })
  assert.equal(wideLayout.isDoubleColumn, true)
  assert.equal(wideLayout.columnWidth, 430) // floor((900-40)/2)
  assert.equal(wideLayout.stride, 940) // 2 * (430 + 40) = 940
})

test('ReaderPagination - Math: Page progress calculation and restoration mapping', () => {
  // Single page chapter
  const pageCount1 = 1
  const pos1 = pageCount1 > 1 ? 0 / (pageCount1 - 1) : 0
  assert.equal(pos1, 0)

  // 5 pages chapter
  const pageCount5 = 5
  // Page 0 (1st)
  assert.equal(0 / (pageCount5 - 1), 0.0)
  // Page 2 (3rd)
  assert.equal(2 / (pageCount5 - 1), 0.5)
  // Page 4 (5th)
  assert.equal(4 / (pageCount5 - 1), 1.0)

  // Restoring pageIndex from scrollPosition float
  const restorePage = (progress: number, count: number) => {
    return Math.min(count - 1, Math.max(0, Math.round(progress * (count - 1))))
  }

  assert.equal(restorePage(0.0, 5), 0)
  assert.equal(restorePage(0.24, 5), 1)
  assert.equal(restorePage(0.5, 5), 2)
  assert.equal(restorePage(0.76, 5), 3)
  assert.equal(restorePage(1.0, 5), 4)
  assert.equal(restorePage(1.5, 5), 4, 'Clamps upper bound')
  assert.equal(restorePage(-0.5, 5), 0, 'Clamps lower bound')
})

test('ReaderPagination - Fonts: getReaderFontFamily returns font stacks with fallbacks', () => {
  assert.match(getReaderFontFamily('song'), /Songti/i)
  assert.match(getReaderFontFamily('hei'), /PingFang/i)
  assert.match(getReaderFontFamily('kai'), /Kaiti/i)
  assert.match(getReaderFontFamily('fangsong'), /FangSong/i)
  assert.match(getReaderFontFamily('system'), /Segoe UI/i)
  // Fallback to song
  assert.match(getReaderFontFamily('unknown' as any), /Songti/i)
})

test('ReaderPagination - Persistence: loadReaderSettings and saveReaderSettings with localStorage', () => {
  const store = new Map<string, string>()
  const mockStorage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, val: string) => { store.set(key, val) },
    removeItem: (key: string) => { store.delete(key) },
    clear: () => store.clear(),
  }

  const prevWindow = (globalThis as any).window
  ;(globalThis as any).window = { localStorage: mockStorage }

  try {
    // Initial load returns default settings
    const initial = loadReaderSettings()
    assert.equal(initial.maxWidth, 860)
    assert.equal(initial.columnMode, 'auto')
    assert.equal(initial.sidebarPinned, false)

    // Save customized settings
    saveReaderSettings({
      ...initial,
      maxWidth: 1100,
      columnMode: 'double',
      sidebarPinned: true,
      fontSize: 22,
    })

    // Load customized settings
    const loaded = loadReaderSettings()
    assert.equal(loaded.maxWidth, 1100)
    assert.equal(loaded.columnMode, 'double')
    assert.equal(loaded.sidebarPinned, true)
    assert.equal(loaded.fontSize, 22)

    // Clamping invalid / out-of-range values
    mockStorage.setItem('legado-reader-settings-v2', JSON.stringify({
      maxWidth: 9999,
      columnMode: 'invalid',
      sidebarPinned: 'yes',
      fontSize: 999,
    }))

    const clamped = loadReaderSettings()
    assert.equal(clamped.maxWidth, 1400, 'Max width clamped to 1400')
    assert.equal(clamped.columnMode, 'auto', 'Column mode fallback to auto')
    assert.equal(clamped.sidebarPinned, false, 'Invalid sidebarPinned fallback to false')
    assert.equal(clamped.fontSize, 28, 'Font size clamped to 28')
  } finally {
    ;(globalThis as any).window = prevWindow
  }
})

test('ReaderPagination - Chapter Transition: Turning back from chapter beginning lands on the last page/position', () => {
  // Scenario 1: Paginated mode backward navigation lands on pageCount - 1
  const resolveTargetPageIndex = (targetMode: 'first' | 'last' | null, initialPos: number | null, pageCount: number, currentPageIndex: number) => {
    if (targetMode === 'last') {
      return pageCount - 1
    } else if (targetMode === 'first') {
      return 0
    } else if (initialPos !== null) {
      return Math.min(pageCount - 1, Math.max(0, Math.round(initialPos * (pageCount - 1))))
    }
    return Math.min(currentPageIndex, pageCount - 1)
  }

  // 6-page chapter when navigating backward
  assert.equal(resolveTargetPageIndex('last', null, 6, 0), 5, 'Navigating backward must land on last page (index 5)')
  // 1-page chapter when navigating backward
  assert.equal(resolveTargetPageIndex('last', null, 1, 0), 0, 'Single page chapter lands on page 0')
  // Navigating forward to next chapter
  assert.equal(resolveTargetPageIndex('first', null, 6, 0), 0, 'Navigating forward must land on first page (index 0)')
  // Resuming saved progress at 50%
  assert.equal(resolveTargetPageIndex(null, 0.5, 6, 0), 3, 'Resuming 50% on 6-page chapter lands on page 3')

  // Scenario 2: Scroll mode backward navigation lands on maxScroll
  const resolveTargetScroll = (targetMode: 'first' | 'last' | null, initialPos: number | null, scrollHeight: number, clientHeight: number) => {
    const maxScroll = Math.max(0, scrollHeight - clientHeight)
    if (targetMode === 'last') {
      return maxScroll
    } else if (targetMode === 'first') {
      return 0
    } else if (initialPos !== null) {
      return Math.round(maxScroll * initialPos)
    }
    return 0
  }

  const scrollHeight = 3500
  const clientHeight = 800
  const maxScroll = 2700

  assert.equal(resolveTargetScroll('last', null, scrollHeight, clientHeight), maxScroll, 'Navigating backward in scroll mode must land on maxScroll (chapter bottom)')
  assert.equal(resolveTargetScroll('first', null, scrollHeight, clientHeight), 0, 'Navigating forward in scroll mode lands on top (0)')
  assert.equal(resolveTargetScroll(null, 0.4, scrollHeight, clientHeight), 1080, 'Resuming 40% progress lands on 1080px')
})

test('ReaderPagination - findFirstFullyVisibleParagraphIndex: finds first fully visible paragraph on screen', () => {
  // Case 1: Scroll mode viewport (height = 800, top = 56 header height, bottom = 800)
  // Paragraph 0: top = 10, bottom = 90 (partially cut by top header 56)
  // Paragraph 1: top = 110, bottom = 220 (fully visible!)
  // Paragraph 2: top = 240, bottom = 360 (fully visible)
  const paragraphs1 = [
    { index: 0, rect: { top: 10, bottom: 90, left: 100, right: 700 } },
    { index: 1, rect: { top: 110, bottom: 220, left: 100, right: 700 } },
    { index: 2, rect: { top: 240, bottom: 360, left: 100, right: 700 } },
  ]
  const scrollViewport = { top: 56, bottom: 800, left: 0, right: 800 }
  assert.equal(findFirstFullyVisibleParagraphIndex(paragraphs1, scrollViewport), 1, 'Should pick paragraph 1 as first fully visible')

  // Case 2: Paginated mode viewport (column track: left = 0, right = 600)
  // Paragraph 0: left = -640, right = -40 (previous page)
  // Paragraph 1: left = -40, right = 200 (crossing page boundary, not fully on current page)
  // Paragraph 2: left = 10, right = 590 (fully on current page)
  // Paragraph 3: left = 10, right = 590 (fully on current page)
  const paragraphs2 = [
    { index: 0, rect: { top: 50, bottom: 150, left: -640, right: -40 } },
    { index: 1, rect: { top: 160, bottom: 260, left: -40, right: 200 } },
    { index: 2, rect: { top: 270, bottom: 380, left: 10, right: 590 } },
    { index: 3, rect: { top: 390, bottom: 500, left: 10, right: 590 } },
  ]
  const paginatedViewport = { top: 40, bottom: 700, left: 0, right: 600 }
  assert.equal(findFirstFullyVisibleParagraphIndex(paragraphs2, paginatedViewport), 2, 'Should pick paragraph 2 as first fully visible on current page')

  // Case 3: Empty paragraphs fallback
  assert.equal(findFirstFullyVisibleParagraphIndex([], scrollViewport), 0, 'Empty list defaults to 0')

  // Case 4: No paragraph fully visible (e.g. huge paragraph taller than viewport), fallback to visible one
  const giantParagraph = [
    { index: 3, rect: { top: -200, bottom: 1200, left: 100, right: 700 } }
  ]
  assert.equal(findFirstFullyVisibleParagraphIndex(giantParagraph, scrollViewport), 3, 'Falls back to visible paragraph if taller than screen')
})

/**
 * 跨章翻页过渡（章末翻到下一章）。
 *
 * 旧实现的观感缺陷：内容替换后 `pageIndex` 由「本章最后一页」变 `0`，
 * 轨道带着 CSS 过渡**倒退**回第一页 —— 方向与手势相反。现在改为沿手势方向滑入。
 */
test('ReaderPagination - Chapter Turn: class contract carries both phase and direction', () => {
  assert.equal(chapterTurnClassName(null, 'next'), 'reader-chapter-turn', '静止时只有基类')
  assert.equal(chapterTurnClassName('pending', 'next'), 'reader-chapter-turn is-pending is-next')
  assert.equal(chapterTurnClassName('in', 'next'), 'reader-chapter-turn is-in is-next')
  assert.equal(chapterTurnClassName('in', 'prev'), 'reader-chapter-turn is-in is-prev')
})

test('ReaderPagination - Chapter Turn: every class the contract emits is actually styled', () => {
  const testDir = path.dirname(fileURLToPath(import.meta.url))
  const css = fs.readFileSync(path.resolve(testDir, '../src/styles.css'), 'utf-8')

  // 基类必须有布局声明，否则过渡层不占高度、动画不可见
  assert.ok(/\.reader-chapter-turn\s*\{/.test(css), '缺少 .reader-chapter-turn 基类规则')

  // 「沿手势方向滑入」：向后翻从右侧进、向前翻从左侧进
  for (const [phase, direction] of [['in', 'next'], ['in', 'prev']] as const) {
    const className = chapterTurnClassName(phase, direction)
    const selector = className.split(' ').map(c => `.${c}`).join('')
    assert.ok(css.includes(selector), `CSS 里找不到选择器 ${selector} —— 类名契约与样式已经漂移`)
  }
  assert.ok(/@keyframes\s+reader-turn-in-next/.test(css), '缺少 reader-turn-in-next 关键帧')
  assert.ok(/@keyframes\s+reader-turn-in-prev/.test(css), '缺少 reader-turn-in-prev 关键帧')

  // 向后翻必须从右侧进入（+100%），否则方向仍然是反的
  const nextKeyframes = css.slice(css.indexOf('@keyframes reader-turn-in-next'))
  assert.ok(/translateX\(100%\)/.test(nextKeyframes.slice(0, 200)), 'reader-turn-in-next 应从右侧（+100%）进入')
  const prevKeyframes = css.slice(css.indexOf('@keyframes reader-turn-in-prev'))
  assert.ok(/translateX\(-100%\)/.test(prevKeyframes.slice(0, 200)), 'reader-turn-in-prev 应从左侧（-100%）进入')

  // 跨章期间必须抑制轨道自身的过渡，否则仍会倒退
  assert.ok(/\.reader-paginated-track\.is-turning\s*\{[^}]*transition:\s*none/.test(css), '缺少 .reader-paginated-track.is-turning 的过渡抑制')
})

/**
 * 滚动模式的「三章连续滚动」：窗口平移判定与位置补偿。
 *
 * 目标：读完本章继续往下滚 ⇒ 直接进入下一章（不再有章末换章栏），
 * 且窗口换掉的瞬间**视觉位置不能跳**。
 */
test('ReaderScroll - splitParagraphs matches the single-chapter pipeline', () => {
  assert.deepEqual(splitParagraphs(''), [])
  assert.deepEqual(splitParagraphs('\n\n  \n'), [], '空行与纯空白行都要丢掉')
  assert.deepEqual(splitParagraphs('第一段\n第二段'), ['第一段', '第二段'])
  // 前后空白要去掉，与当前章的 paragraphs 派生口径一致
  assert.deepEqual(splitParagraphs('  缩进段  \n\n\t制表段\t'), ['缩进段', '制表段'])
})

test('ReaderScroll - dominant section switches at the viewport centre, symmetrically', () => {
  // 三章堆叠：上一章 top=-1200、当前章 top=0、下一章 top=800；视口高 800 ⇒ 中心相对页面 top 为 400
  const rects = [
    { index: 4, top: -1200 },
    { index: 5, top: 0 },
    { index: 6, top: 800 },
  ]
  // Ref Map 的插入顺序不等于 DOM 顺序，判定必须按章节顶部的实际位置选择。
  assert.equal(dominantScrollSection([
    { index: 6, top: 800 },
    { index: 4, top: -1200 },
    { index: 5, top: 0 },
  ], 400), 5)
  assert.equal(dominantScrollSection([
    { index: 6, top: 800 },
    { index: 4, top: -1200 },
    { index: 5, top: 0 },
  ], 800), 6)
  assert.equal(dominantScrollSection([{ index: 1, top: Number.NaN }], 400), null)
})

test('ReaderScroll - chapter transition follows the actual scroll direction', () => {
  const source = fs.readFileSync(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/ReaderScreen.tsx'), 'utf-8')
  assert.match(source, /isScrollSectionTransitionAllowed\(current\.chapter\.index, dominant, scrollDelta\)/)
  assert.equal(isScrollSectionTransitionAllowed(5, 6, 120), true)
  assert.equal(isScrollSectionTransitionAllowed(5, 4, -120), true)
  assert.equal(isScrollSectionTransitionAllowed(5, 4, 120), false)
  assert.equal(isScrollSectionTransitionAllowed(5, 6, -120), false)
  assert.equal(isScrollSectionTransitionAllowed(5, 6, 0), false)
})

test('ReaderScroll - dominant section tolerates empty or single-section windows', () => {
  assert.equal(dominantScrollSection([], 400), null, '没有区块时返回 null，调用方跳过平移')
  assert.equal(dominantScrollSection([{ index: 0, top: 0 }], 400), 0)
  // 首章没有上一章、末章没有下一章：窗口只有两段也要正确判定
  const firstChapter = [{ index: 0, top: 0 }, { index: 1, top: 900 }]
  assert.equal(dominantScrollSection(firstChapter, 100), 0)
  assert.equal(dominantScrollSection(firstChapter, 950), 1)
})

test('ReaderScroll - 判定参考线：keep 用视口顶部下方一小段并带兜底，window 沿用中心', () => {
  const vh = 932
  // keep：参考线在视口顶部下方一小段（带宽要够大，否则快速滑动一帧跨过窄带会漏判换章）
  const top = scrollDominantThreshold(vh, 'top')
  assert.ok(top >= 100 && top <= vh / 3, `视口顶部参考线应在合理带宽内，实际 ${top}`)
  // window：必须离边缘足够远（换章要平移 DOM，参考线太靠边会让平移在可见区域发生）
  assert.equal(scrollDominantThreshold(vh, 'centre'), vh / 2)

  // 回归锁：章长 8000 / 视口 932 时，旧的中心参考线要求下一章露出约半屏才换章
  // （实测：连滚 10000px 后 live 章仍停在上一章，进度/标题/TTS 与视口错位）。
  const rects = [
    { index: 3, top: -7008 },
    { index: 4, top: 848 },
  ]
  assert.equal(dominantScrollSection(rects, vh / 2), 3, '中心参考线在下一章露出 848px 时仍判定为上一章')
  assert.equal(dominantScrollSection(rects, top), 3, '参考线带宽内、下一章尚未进入时仍是上一章')
  assert.equal(dominantScrollSection([{ index: 3, top: -7856 }, { index: 4, top: 0 }], top), 4, '下一章到达视口顶部即换章')
  assert.equal(dominantScrollSection([{ index: 3, top: -7860 }, { index: 4, top: -4 }], top), 4, '下一章越过视口顶部仍算当前章')
})

test('ReaderScroll - 参考线带宽必须够大，否则快速滑动会整帧跳过换章判定', () => {
  const vh = 932
  const top = scrollDominantThreshold(vh, 'top')
  // 带宽要显著大于「一帧的滚动量」（惯性滑动一帧可达数百像素），否则条件会被跨过
  assert.ok(top >= 200, `参考线带宽至少应覆盖常见的一帧滚动量，实际 ${top}`)
  assert.ok(top <= vh / 3, `参考线也不该深到半屏，实际 ${top}`)

  // 上一章顶部恒为负 ⇒ 永远满足「顶部 ≤ 参考线」⇒ 窄带漏判时当前章会停住不推进
  const narrow = 24
  const wide = top
  const rects = [
    { index: 6, top: -7000 },
    { index: 7, top: 150 },
  ]
  assert.equal(dominantScrollSection(rects, narrow), 6, '窄参考线在下一章露出 150px 时仍留在上一章（= 漏判换章）')
  assert.equal(dominantScrollSection(rects, wide), 7, '宽参考线下同一帧已正确切换为下一章')
  // 条件是单调的：一旦越过参考线，继续滚动只会保持满足
  assert.equal(dominantScrollSection([{ index: 6, top: -7200 }, { index: 7, top: -50 }], wide), 7)
})

test('ReaderScroll - 恢复定位：目标不能提前清空、隔断不能随身份变化', () => {
  const testDir = path.dirname(fileURLToPath(import.meta.url))
  const reader = fs.readFileSync(path.resolve(testDir, '../src/ReaderScreen.tsx'), 'utf-8')

  // ① 恢复目标只能在真正对齐之后清空。
  // 若在 effect 开头就置 null，effect 因 scrollSections 变化重跑时会拿不到目标
  // （相邻章异步挂载后把目标章往下推的那一刻，正是最需要重新对齐的时候）。
  assert.match(reader, /const clearRestoreTarget = \(\) => \{/, '必须把清空恢复目标收敛到一个显式函数')
  assert.doesNotMatch(reader, /\n\s*targetInitialPageRef\.current = null\n\s*initialPagePositionRef\.current = null\n/,
    '恢复目标不能在 effect 开头无条件清空')

  // ② 隔断必须只由章节下标决定：同一章在「当前章 ⇄ 邻居」两种身份下结构要一致。
  // 否则高度会差一个隔断（实测 139px），跨章那一帧视口内容被推走一段。
  assert.match(reader, /const firstMountedIndex = useMemo\(/, '必须有统一的「窗口第一段」判定')
  assert.match(reader, /section\.index > firstMountedIndex &&/, '邻居段的隔断按下标判定')
  assert.match(reader, /chapterIndex > firstMountedIndex &&/, '当前章的隔断同样按下标判定')
  assert.doesNotMatch(reader, /position > 0 && \(\n\s*<div className="reader-chapter-stream-divider"/,
    '隔断不能再依赖「在数组里的位置」')
})

test('ReaderScroll - scroll compensation keeps the anchor visually still', () => {
  // 下移窗口时从顶部丢掉上一章：若锚点区块在平移后上移了 500px，就增加对应的 scrollY 补偿
  assert.equal(scrollCompensation(300, -200), -500, '锚点上移 500 ⇒ 补偿 -500')
  assert.equal(scrollCompensation(-200, 300), 500, '上移窗口插入上一章 ⇒ 反向补偿')
  // 没有位移时不补偿（避免产生无谓的 scrollBy 抖动）
  assert.equal(scrollCompensation(120, 120), 0)
})

test('ReaderScroll - chapter sections keep stable DOM identity and disable native scroll anchoring', () => {
  const testDir = path.dirname(fileURLToPath(import.meta.url))
  const reader = fs.readFileSync(path.resolve(testDir, '../src/ReaderScreen.tsx'), 'utf-8')
  const css = fs.readFileSync(path.resolve(testDir, '../src/styles.css'), 'utf-8')

  // 同一章从「邻居」变成「当前章」时必须保持同一个 React key —— 换 key = 重建 DOM 节点 = 高度归零 = 跳动。
  assert.match(reader, /key=\{scrollSectionKey\(section\.index\)\}/)
  assert.match(reader, /key=\{scrollSectionKey\(chapterIndex\)\}/)
  // 不允许再回到「第 n 章当邻居时用另一个 key 前缀」的写法。
  assert.doesNotMatch(reader, /key=\{`(?:prev|next)-\$\{/)
  assert.equal(scrollSectionKey(7), 'chapter-7')

  // iOS Safari 不得在应用补偿的同时再自作主张校正一次滚动位置。
  assert.match(css, /\.reading-scroll-window\s*\{[^}]*overflow-anchor:\s*none/s)
  assert.match(reader, /if \(scrollShiftAnchorRef\.current\) return/)
  assert.match(reader, /window\.scrollTo\(\{ top: Math\.max\(0, window\.scrollY \+ delta\), behavior: 'auto' \}\)/)
})

test('ReaderScroll - keep 策略：跨章只追加不摘章，回到已读章节不重建 DOM', () => {
  const total = 100
  // 首挂载：当前章 5
  let range = desiredScrollWindow('keep', 5, total, null)
  assert.ok(range.head <= 5 && range.tail >= 5, '窗口必须包含当前章')

  // 一路读到第 6、7、8 章：head 绝不能往后退（往后退 = 从视口上方摘章 = 跳动）
  const heads: number[] = []
  for (const live of [6, 7, 8, 9, 10]) {
    range = desiredScrollWindow('keep', live, total, range)
    heads.push(range.head)
  }
  for (let i = 1; i < heads.length; i++) {
    assert.ok(heads[i] <= heads[i - 1], `第 ${i} 步 head 回退了：${heads[i - 1]} → ${heads[i]}`)
  }
  // 并且当前章始终在窗口内
  assert.ok(range.head <= 10 && range.tail >= 10)

  // 往回读（上滑）时窗口同样不能缩小 —— 已经挂载的章节必须留着。
  const beforeBack = { ...range }
  const afterBack = desiredScrollWindow('keep', 8, total, range)
  assert.equal(afterBack.head, beforeBack.head)
  assert.equal(afterBack.tail, beforeBack.tail)

  // window 策略保持三章口径不变。
  assert.deepEqual(desiredScrollWindow('window', 5, total, null), { head: 4, tail: 6 })
  assert.deepEqual(desiredScrollWindow('window', 0, total, null), { head: 0, tail: 1 })
  assert.deepEqual(desiredScrollWindow('window', total - 1, total, null), { head: total - 2, tail: total - 1 })
})

test('ReaderScroll - keep 策略只回收视口之外且离得够远的章节，补偿只针对视口上方', () => {
  const live = 20
  const vh = 932
  const range = { head: 10, tail: 22 }

  // 每章 8000px 高；索引 < live 的在视口上方，> live 的在视口下方
  const rects = new Map<number, { top: number; height: number }>()
  for (let index = range.head; index <= range.tail; index++) {
    const top = (index - live) * 8000 + 400
    rects.set(index, { top, height: 8000 })
  }

  const plan = scrollWindowTrimPlan(range, live, vh, DEFAULT_KEEP_BOUNDS.before, rects, 1)
  // 头部：10..13 都整段在视口上方一个半屏以外
  assert.deepEqual(plan.remove, [10, 11, 12, 13, 22], '回收 = 视口上方前缀 + 视口下方足够远的尾巴')
  assert.equal(plan.next.head, 14)
  // 尾部：22 整段在视口下方一个半屏以外 → 一并回收（跳章夹层不回收就会永久驻留）；
  // 21 = live+1 落在 keepCeil 保留区里，不回收。
  assert.equal(plan.next.tail, 21, '保留区内的尾部不回收')
  assert.ok(!plan.remove.includes(21) && !plan.remove.includes(20), '当前章与保留区绝不回收')
  assert.ok(plan.next.head <= live && plan.next.tail >= live, '回收后窗口仍必须包含当前章')

  // keepFloor 保护：当前章之前 6 章之内不回收；此时没有任何远方尾部
  const tight = scrollWindowTrimPlan({ head: 15, tail: 21 }, live, vh, DEFAULT_KEEP_BOUNDS.before, rects, 1)
  assert.deepEqual(tight.remove, [], '保留区内一律不回收')

  // keepCeil 保护：当前章之后 keepCeil 章之内不回收（那是「接着往下读」的窗口）
  const ceiling = scrollWindowTrimPlan({ head: 19, tail: 21 }, live, vh, DEFAULT_KEEP_BOUNDS.before, rects, 1)
  assert.deepEqual(ceiling.remove, [], '当前章后 1 章之内不回收')

  // 几何缺失时宁可留着，也不能凭猜回收（那会造成高度突变）
  const noRects = scrollWindowTrimPlan(range, live, vh, DEFAULT_KEEP_BOUNDS.before, new Map(), 1)
  assert.deepEqual(noRects.remove, [])
  assert.deepEqual(noRects.next, range)

  // 近处章节一律不回收（离视口不够远 ⇒ 可能在屏幕里）
  const nearRects = new Map<number, { top: number; height: number }>()
  for (let index = range.head; index <= range.tail; index++) nearRects.set(index, { top: 100, height: 8000 })
  assert.deepEqual(scrollWindowTrimPlan(range, live, vh, 0, nearRects, 0).remove, [], '离视口不够远时一律不回收')

  // 补偿方向：上方高度减少 ⇒ scrollY 减少同样的量（视觉不动），绝不为负补偿
  assert.equal(trimCompensation(8000), -8000)
  assert.equal(trimCompensation(0), 0)
  assert.equal(trimCompensation(-100), 0)
})

test('ReaderScroll - 共享不变量：方向反转 / 超量位移 / 锚点漂移都要被抓出来', () => {
  const vh = 932
  // 干净数据：稳步向下每步 250
  const clean = [
    { y: 1000, anchorAbsTop: 1200, anchorId: 'c3#10' },
    { y: 1250, anchorAbsTop: 950, anchorId: 'c3#10' },
    { y: 1500, anchorAbsTop: 700, anchorId: 'c3#10' },
  ]
  assert.deepEqual(scrollWindowInvariantViolations(clean, 1, vh), [])

  // 方向反转（往下滚却回跳）
  const backward = [clean[0], { y: 700, anchorAbsTop: 1500, anchorId: 'c3#10' }]
  const v1 = scrollWindowInvariantViolations(backward, 1, vh)
  assert.ok(v1.some(v => v.kind === 'backward-shift'), '往下滚时 scrollY 变小必须报 backward-shift')

  // 上滑时反向跳（用户报障的形态）
  const up = [clean[1], { y: 1600, anchorAbsTop: 800, anchorId: 'c3#10' }]
  const v2 = scrollWindowInvariantViolations(up, -1, vh)
  assert.ok(v2.some(v => v.kind === 'backward-shift'), '往上滑时 scrollY 变大必须报 backward-shift')

  // 单步位移超过一个视口
  const overshoot = [clean[0], { y: 1000 + vh + 1, anchorAbsTop: 1200 - vh - 1, anchorId: 'c3#10' }]
  assert.ok(scrollWindowInvariantViolations(overshoot, 1, vh).some(v => v.kind === 'oversized-shift'))

  // 锚点漂移：位移量对了但内容错位
  const drift = [clean[0], { y: 1250, anchorAbsTop: 1200, anchorId: 'c3#10' }]
  assert.ok(scrollWindowInvariantViolations(drift, 1, vh).some(v => v.kind === 'anchor-drift'))

  // 锚点换人时不判漂移（跨元素相减是量纲错误 —— 历史教训）
  const swapped = [clean[0], { y: 1250, anchorAbsTop: 0, anchorId: 'c4#0' }]
  assert.deepEqual(scrollWindowInvariantViolations(swapped, 1, vh), [])
})

test('ReaderScroll - 内核判定：三台真机 UA 必须各归其位（WebKit 才走 keep）', () => {
  // 这三条是用户提供的真实 UA，直接当测试向量：判定错一条，桌面端就会从三章窗口变成别的形态
  const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1'
  const EDGE = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36 Edg/154.0.0.0'
  const ANDROID = 'Mozilla/5.0 (Linux; U; Android 13; zh-cn; PEPM00 Build/TP1A.220905.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/115.0.5790.168 Mobile Safari/537.36 HeyTapBrowser/40.10.23.1'

  assert.equal(classifyScrollEngine(IPHONE), 'webkit', 'iPhone Safari 必须判为 WebKit')
  // ⚠️ Blink 系的 UA 里同样带 AppleWebKit/537.36 —— 只看 AppleWebKit 会把 Edge/安卓误判成 WebKit，
  // 这正是桌面端被误判成 keep（不再是三章窗口）的根因。
  assert.equal(classifyScrollEngine(EDGE), 'blink', 'Edge 的 UA 带 AppleWebKit/537.36，但它是 Blink')
  assert.equal(classifyScrollEngine(ANDROID), 'blink', '安卓 WebView/浏览器同样是 Blink')
  assert.equal(classifyScrollEngine(''), 'unknown')
  assert.equal(classifyScrollEngine(undefined), 'unknown')

  // 策略映射：只有 WebKit 走 keep；Blink 与「认不出」都走 window（保守方向 = 不改变既有桌面行为）
  assert.equal(decideScrollStrategy(null, 'webkit').strategy, 'keep')
  assert.equal(decideScrollStrategy(null, 'webkit').reason, 'webkit')
  assert.equal(decideScrollStrategy(null, 'blink').strategy, 'window')
  assert.equal(decideScrollStrategy(null, 'blink').reason, 'blink')
  assert.equal(decideScrollStrategy(null, 'unknown').strategy, 'window', '认不出时不得改变桌面观感')
  assert.equal(decideScrollStrategy(null, 'unknown').reason, 'unknown-engine')
  // 覆盖优先（探针要在同一内核上把两条策略都跑一遍）
  assert.equal(decideScrollStrategy('keep', 'blink').strategy, 'keep')
  assert.equal(decideScrollStrategy('window', 'webkit').strategy, 'window')
  assert.equal(decideScrollStrategy('window', 'webkit').reason, 'override')
  assert.equal(decideScrollStrategy('window', 'webkit').engine, 'webkit')
  assert.equal(SCROLL_STRATEGY_STORAGE_KEY, 'legado-reader-scroll-strategy')
  assert.equal(decideScrollStrategy('keep', 'webkit').keepBounds, DEFAULT_KEEP_BOUNDS)
})
test('ReaderScroll - expected neighbor section count handles first, last, and single chapters', () => {
  const getExpected = (chapterIndex: number, totalChapters: number) => {
    const hasPrev = chapterIndex > 0
    const hasNext = chapterIndex < totalChapters - 1
    return 1 + (hasPrev ? 1 : 0) + (hasNext ? 1 : 0)
  }

  // 单章书
  assert.equal(getExpected(0, 1), 1)

  // 两章书
  assert.equal(getExpected(0, 2), 2)
  assert.equal(getExpected(1, 2), 2)

  // 多章书（首章、中间章、末章）
  assert.equal(getExpected(0, 100), 2, '首章无上一章，最大段数恒为 2，不能硬编码为 3 导致死循环抢锁')
  assert.equal(getExpected(50, 100), 3, '中间章期望 3 段')
  assert.equal(getExpected(99, 100), 2, '末章无下一章，最大段数恒为 2，不能硬编码为 3 导致死循环抢锁')
})






