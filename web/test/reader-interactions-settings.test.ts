import test from 'node:test'
import assert from 'node:assert/strict'
import {
  scrollTapZone,
  paginateTapZone,
  mobileTapZone,
  isAtTopBoundary,
  isAtBottomBoundary,
  isTapGesture,
  swipeDirection,
  isInteractiveReaderTarget,
  calculatePaginationLayout,
  isDoubleColumnActive,
} from '../src/readerInteractions.ts'
import {
  clampScrollPosition,
  scrollPosition,
  parseReaderFont,
  parsePageMode,
  parseColumnMode,
  parseMaxWidth,
  loadReaderSettings,
  saveReaderSettings,
  getReaderFontFamily,
  defaultReaderSettings,
} from '../src/readerSettings.ts'

test('reader interactions - tap zones and gestures', () => {
  // Scroll mode tap zones
  assert.equal(scrollTapZone(100, 1000), 'previous') // 10%
  assert.equal(scrollTapZone(500, 1000), 'toggle')   // 50%
  assert.equal(scrollTapZone(900, 1000), 'next')     // 90%
  assert.equal(mobileTapZone(100, 1000), 'previous')

  // Paginated mode tap zones
  assert.equal(paginateTapZone(100, 1000), 'previous') // 10%
  assert.equal(paginateTapZone(500, 1000), 'toggle')   // 50%
  assert.equal(paginateTapZone(900, 1000), 'next')     // 90%

  // Boundaries
  assert.equal(isAtTopBoundary(0), true)
  assert.equal(isAtTopBoundary(5), true)
  assert.equal(isAtTopBoundary(10), false)
  assert.equal(isAtBottomBoundary(980, 2000, 1000, 20), true) // 980 + 1000 = 1980 >= 2000 - 20
  assert.equal(isAtBottomBoundary(500, 2000, 1000, 20), false)

  // Gestures
  assert.equal(isTapGesture(100, 100, 105, 105), true)
  assert.equal(isTapGesture(100, 100, 150, 100), false)

  assert.equal(swipeDirection(200, 100, 100, 100), 'left')
  assert.equal(swipeDirection(100, 100, 200, 100), 'right')
  assert.equal(swipeDirection(100, 100, 110, 100), null) // too short
  assert.equal(swipeDirection(100, 100, 150, 200), null) // vertical diagonal
})

test('reader settings - parsing, math clamping and localStorage persistence', () => {
  assert.equal(clampScrollPosition(0.5), 0.5)
  assert.equal(clampScrollPosition(-1), 0)
  assert.equal(clampScrollPosition(2), 1)
  assert.equal(clampScrollPosition(NaN), 0)

  assert.equal(scrollPosition(500, 1500, 500), 0.5) // 500 / 1000 = 0.5

  assert.equal(parseReaderFont('kai'), 'kai')
  assert.equal(parseReaderFont('invalid-font'), 'song')

  assert.equal(parsePageMode('paginate'), 'paginate')
  assert.equal(parsePageMode('other'), 'scroll')

  assert.equal(parseColumnMode('double'), 'double')
  assert.equal(parseColumnMode('invalid'), 'auto')

  assert.equal(parseMaxWidth(1000), 1000)
  assert.equal(parseMaxWidth(200), 560)
  assert.equal(parseMaxWidth(3000), 1400)
  assert.equal(parseMaxWidth('invalid'), defaultReaderSettings.maxWidth)

  // Fonts
  assert.ok(getReaderFontFamily('kai').includes('Kaiti'))
  assert.ok(getReaderFontFamily('system').includes('system-ui') || getReaderFontFamily('system').includes('-apple-system'))

  // LocalStorage
  const mockStorage: Record<string, string> = {}
  const originalWindow = globalThis.window
  ;(globalThis as any).window = {
    localStorage: {
      getItem: (key: string) => mockStorage[key] || null,
      setItem: (key: string, val: string) => { mockStorage[key] = val },
    },
  }

  try {
    const loadedDefault = loadReaderSettings()
    assert.equal(loadedDefault.theme, 'light')

    saveReaderSettings({ ...defaultReaderSettings, theme: 'dark', fontSize: 24 })
    const loadedSaved = loadReaderSettings()
    assert.equal(loadedSaved.theme, 'dark')
    assert.equal(loadedSaved.fontSize, 24)
  } finally {
    globalThis.window = originalWindow
  }
})
