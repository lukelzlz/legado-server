import test from 'node:test'
import assert from 'node:assert/strict'
import {
  calculatePaginationLayout,
  isAtBottomBoundary,
  isAtTopBoundary,
  isDoubleColumnActive,
  isTapGesture,
  paginateTapZone,
  scrollTapZone,
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




