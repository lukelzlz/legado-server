import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseParagraphsFromContent,
  shouldAppendNextChapter,
  findActiveChapterInViewport,
  isChapterVisibleInVirtualWindow,
  calculateChapterScrollPosition,
  paragraphIndexToRatio,
  ratioToParagraphIndex,
  ChapterViewportRect,
} from '../src/readerInteractions'

test('ReaderInfiniteStream - parseParagraphsFromContent clean and splits properly', () => {
  const sample = `
    第一段开头有空格。
    
    第二段中间有空行。   
    
    第三段结尾。
  `
  const result = parseParagraphsFromContent(sample)
  assert.equal(result.length, 3)
  assert.equal(result[0], '第一段开头有空格。')
  assert.equal(result[1], '第二段中间有空行。')
  assert.equal(result[2], '第三段结尾。')

  assert.deepEqual(parseParagraphsFromContent(''), [])
})

test('ReaderInfiniteStream - shouldAppendNextChapter detects proximity to bottom', () => {
  const clientHeight = 800
  const scrollHeight = 4000
  const threshold = 900

  // 刚滚到 1500，还剩 2500，不触发
  assert.equal(shouldAppendNextChapter({ scrollY: 1500, clientHeight, scrollHeight, threshold }), false)

  // 滚到 2400 (2400 + 800 = 3200 >= 4000 - 900 = 3100)，触发预载追加
  assert.equal(shouldAppendNextChapter({ scrollY: 2400, clientHeight, scrollHeight, threshold }), true)
})

test('ReaderInfiniteStream - findActiveChapterInViewport finds chapter crossing midpoint', () => {
  const vh = 1000
  // 第 1 章从 0 到 1200，第 2 章从 1200 到 2500
  const chapters: ChapterViewportRect[] = [
    { index: 0, top: -400, bottom: 800 },
    { index: 1, top: 800, bottom: 2000 },
  ]
  // midPoint = 400, 落在第 0 章 (-400 <= 400 <= 800)
  assert.equal(findActiveChapterInViewport(chapters, vh), 0)

  // 用户继续往下滚，第 0 章滚上去了 (-1000 到 -200)，第 1 章在 (-200 到 1500)
  const chaptersScrolled: ChapterViewportRect[] = [
    { index: 0, top: -1000, bottom: -200 },
    { index: 1, top: -200, bottom: 1500 },
  ]
  // midPoint = 400, 落在第 1 章 (-200 <= 400 <= 1500)
  assert.equal(findActiveChapterInViewport(chaptersScrolled, vh), 1)
})

test('ReaderInfiniteStream - virtual window retains nearby chapters and unmounts distant ones', () => {
  const activeIdx = 5
  // 保持前后 2 章（3, 4, 5, 6, 7）渲染
  assert.equal(isChapterVisibleInVirtualWindow(5, activeIdx, 2), true)
  assert.equal(isChapterVisibleInVirtualWindow(4, activeIdx, 2), true)
  assert.equal(isChapterVisibleInVirtualWindow(7, activeIdx, 2), true)
  assert.equal(isChapterVisibleInVirtualWindow(2, activeIdx, 2), false) // 过于遥远，卸载
  assert.equal(isChapterVisibleInVirtualWindow(8, activeIdx, 2), false) // 过于遥远，卸载
})

test('ReaderInfiniteStream - calculateChapterScrollPosition calculates exact relative ratio inside section', () => {
  const clientHeight = 800
  // 第二章：offsetTop = 3000, offsetHeight = 3000
  // 可滚动行程 = 3000 - 800 = 2200

  // 1. 刚进入第二章开头 (currentY = 3000)
  const posAtStart = calculateChapterScrollPosition({
    currentY: 3000,
    clientHeight,
    sectionTop: 3000,
    sectionHeight: 3000,
  })
  assert.equal(posAtStart, 0.0)

  // 2. 第二章正中间 (currentY = 4100, offsetInChapter = 1100)
  const posAtMid = calculateChapterScrollPosition({
    currentY: 4100,
    clientHeight,
    sectionTop: 3000,
    sectionHeight: 3000,
  })
  assert.equal(posAtMid, 0.5)

  // 3. 第二章末尾 (currentY = 5200, offsetInChapter = 2200)
  const posAtEnd = calculateChapterScrollPosition({
    currentY: 5200,
    clientHeight,
    sectionTop: 3000,
    sectionHeight: 3000,
  })
  assert.equal(posAtEnd, 1.0)

  // 4. 超短章节防除以零或溢出防御
  const shortPos = calculateChapterScrollPosition({
    currentY: 3000,
    clientHeight: 800,
    sectionTop: 3000,
    sectionHeight: 600,
  })
  assert.equal(shortPos, 0.0)
})

test('ReaderInfiniteStream - paragraphIndexToRatio and ratioToParagraphIndex roundtrip precision', () => {
  const total = 45
  for (let pIdx = 0; pIdx < total; pIdx++) {
    const ratio = paragraphIndexToRatio(pIdx, total)
    assert.ok(ratio >= 0.0 && ratio <= 1.0, `Ratio must be in [0, 1] for pIdx=${pIdx}`)
    const restoredPIdx = ratioToParagraphIndex(ratio, total)
    assert.equal(restoredPIdx, pIdx, `Roundtrip must restore exact pIdx=${pIdx}, got=${restoredPIdx}`)
  }

  // 边界保护
  assert.equal(paragraphIndexToRatio(0, 0), 0)
  assert.equal(paragraphIndexToRatio(-5, 20), 0)
  assert.equal(ratioToParagraphIndex(-0.5, 20), 0)
  assert.equal(ratioToParagraphIndex(1.5, 20), 19)
})
