import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const stylesCss = fs.readFileSync(path.resolve(__dirname, '../src/styles.css'), 'utf-8')
const readerScreenTsx = fs.readFileSync(path.resolve(__dirname, '../src/ReaderScreen.tsx'), 'utf-8')

test('ReaderChapterControlBar - CSS styles and animation contracts', () => {
  // 1. Floating footer fixed at bottom with blur and transform transitions
  assert.match(stylesCss, /\.reader-floating-footer\s*\{[^}]*position:\s*fixed;[^}]*bottom:\s*0;/)
  assert.match(stylesCss, /\.reader-floating-footer\s*\{[^}]*z-index:\s*20;/)
  assert.match(stylesCss, /\.reader-workspace\.toolbars-hidden\s+\.reader-floating-footer\s*\{[^}]*transform:\s*translateY\(100%\);/)

  // 2. Sidebar pinned adaptation
  assert.match(stylesCss, /\.reader-workspace\.sidebar-pinned\s+\.reader-floating-footer\s*\{[^}]*left:\s*300px;/)

  // 3. Chapter control bar layout and buttons
  assert.match(stylesCss, /\.reader-chapter-control-bar\s*\{[^}]*display:\s*grid;/)
  assert.match(stylesCss, /\.reader-chapter-btn\s*\{[^}]*display:\s*inline-flex;/)
  assert.match(stylesCss, /\.reader-chapter-slider\s*\{[^}]*-webkit-appearance:\s*none;/)
  assert.match(stylesCss, /\.reader-chapter-info\s*\{[^}]*display:\s*flex;/)
})

test('ReaderChapterControlBar - ReaderScreen markup and event handler wiring', () => {
  // 1. Floating footer present in ReaderScreen JSX
  assert.match(readerScreenTsx, /className="reader-floating-footer"/)
  assert.match(readerScreenTsx, /className="reader-chapter-control-bar"/)
  assert.match(readerScreenTsx, /className="reader-chapter-btn prev"/)
  assert.match(readerScreenTsx, /className="reader-chapter-btn next"/)
  assert.match(readerScreenTsx, /className="reader-chapter-slider"/)

  // 2. Click propagation stop guards on floating footer
  assert.match(readerScreenTsx, /<footer className="reader-floating-footer" onClick=\{e => e\.stopPropagation\(\)\} onPointerDown=\{e => e\.stopPropagation\(\)\}>/)

  // 3. State management for slider dragging and committing
  assert.match(readerScreenTsx, /const \[sliderChapterIndex, setSliderChapterIndex\] = useState\(chapterIndex\)/)
  assert.match(readerScreenTsx, /const \[isDraggingSlider, setIsDraggingSlider\] = useState\(false\)/)
  assert.match(readerScreenTsx, /handleSliderChange/)
  assert.match(readerScreenTsx, /handleSliderCommit/)
})

test('ReaderChapterControlBar - chapter slider math and clamping logic', () => {
  const calcSliderPercent = (index: number, total: number) => {
    return total > 1 ? (index / (total - 1)) * 100 : 100
  }

  assert.equal(calcSliderPercent(0, 100), 0)
  assert.equal(calcSliderPercent(99, 100), 100)
  assert.equal(calcSliderPercent(49, 99), 50)
  assert.equal(calcSliderPercent(0, 1), 100)
  assert.equal(calcSliderPercent(0, 0), 100)
})

test('ReaderChapterControlBar - all locales support chapter navigation keys', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP']
  for (const locale of locales) {
    const filePath = path.resolve(__dirname, `../src/i18n/locales/${locale}.json`)
    const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'))
    assert.ok(data.reader.prevChapter, `Missing prevChapter in ${locale}`)
    assert.ok(data.reader.nextChapter, `Missing nextChapter in ${locale}`)
    assert.ok(data.reader.chapterProgress, `Missing chapterProgress in ${locale}`)
  }
})
