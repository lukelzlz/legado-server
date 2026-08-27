import test from 'node:test'
import assert from 'node:assert/strict'
import { isAtBottomBoundary, isAtTopBoundary, isTapGesture, paginateTapZone, scrollTapZone, swipeDirection } from '../src/readerInteractions'
import { defaultReaderSettings, getReaderFontFamily, parsePageMode, parseReaderFont } from '../src/readerSettings'

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


