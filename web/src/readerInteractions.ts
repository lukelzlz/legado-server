export type ReaderTapZone = 'previous' | 'toggle' | 'next'

export function scrollTapZone(clientY: number, viewportHeight: number): ReaderTapZone {
  const position = clientY / Math.max(1, viewportHeight)
  if (position < 0.3) return 'previous'
  if (position >= 0.7) return 'next'
  return 'toggle'
}

export function paginateTapZone(clientX: number, viewportWidth: number): ReaderTapZone {
  const position = clientX / Math.max(1, viewportWidth)
  if (position < 0.3) return 'previous'
  if (position >= 0.7) return 'next'
  return 'toggle'
}

export function mobileTapZone(clientY: number, viewportHeight: number): ReaderTapZone {
  return scrollTapZone(clientY, viewportHeight)
}

export function isAtTopBoundary(scrollTop: number, threshold = 5): boolean {
  return scrollTop <= threshold
}

export function isAtBottomBoundary(scrollTop: number, scrollHeight: number, clientHeight: number, threshold = 20): boolean {
  return scrollTop + clientHeight >= scrollHeight - threshold
}

export function isTapGesture(startX: number, startY: number, endX: number, endY: number, threshold = 10): boolean {
  return Math.hypot(endX - startX, endY - startY) <= threshold
}

export function swipeDirection(startX: number, startY: number, endX: number, endY: number, minDistance = 40): 'left' | 'right' | null {
  const dx = endX - startX
  const dy = endY - startY
  if (Math.abs(dx) > Math.abs(dy) * 1.5 && Math.abs(dx) >= minDistance) {
    return dx < 0 ? 'left' : 'right'
  }
  return null
}

export function isInteractiveReaderTarget(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest('button, a, input, textarea, select, label, summary, [contenteditable="true"]'))
}

export function isDoubleColumnActive(columnMode: 'auto' | 'single' | 'double', viewportWidth: number): boolean {
  return columnMode === 'double' || (columnMode === 'auto' && viewportWidth >= 800)
}

export type PaginationLayout = {
  isDoubleColumn: boolean
  columnWidth: number
  stride: number
  pageCount: number
}

export function calculatePaginationLayout({
  viewportWidth,
  totalScrollWidth,
  columnGap = 40,
  columnMode = 'auto',
}: {
  viewportWidth: number
  totalScrollWidth: number
  columnGap?: number
  columnMode?: 'auto' | 'single' | 'double'
}): PaginationLayout {
  const isDouble = isDoubleColumnActive(columnMode, viewportWidth)
  if (isDouble) {
    const columnWidth = Math.max(100, Math.floor((viewportWidth - columnGap) / 2))
    const stride = 2 * (columnWidth + columnGap)
    const pageCount = Math.max(1, Math.round((totalScrollWidth + columnGap) / stride))
    return {
      isDoubleColumn: true,
      columnWidth,
      stride,
      pageCount,
    }
  } else {
    const columnWidth = Math.max(100, viewportWidth)
    const stride = columnWidth + columnGap
    const pageCount = Math.max(1, Math.round((totalScrollWidth + columnGap) / (columnWidth + columnGap)))
    return {
      isDoubleColumn: false,
      columnWidth,
      stride,
      pageCount,
    }
  }
}

export type ParagraphRect = {
  top: number
  bottom: number
  left: number
  right: number
}

export type ViewportBounds = {
  top: number
  bottom: number
  left: number
  right: number
}

/**
 * Calculates the first paragraph index that is fully visible within the given viewport bounds.
 * If no paragraph is fully visible, falls back to the best partially visible paragraph, or 0.
 */
export function findFirstFullyVisibleParagraphIndex(
  paragraphs: Array<{ index: number; rect: ParagraphRect }>,
  viewport: ViewportBounds,
  tolerance = 2
): number {
  if (!paragraphs || paragraphs.length === 0) return 0

  // 1. Look for the first paragraph whose rect is completely inside the viewport bounds
  for (const p of paragraphs) {
    const fullyInsideY = p.rect.top >= viewport.top - tolerance && p.rect.bottom <= viewport.bottom + tolerance
    const fullyInsideX = p.rect.left >= viewport.left - tolerance && p.rect.right <= viewport.right + tolerance
    if (fullyInsideY && fullyInsideX) {
      return p.index
    }
  }

  // 2. If none are fully visible (e.g. paragraph is taller than viewport), find the first paragraph that has substantial visibility inside the viewport
  for (const p of paragraphs) {
    const visibleTop = Math.max(p.rect.top, viewport.top)
    const visibleBottom = Math.min(p.rect.bottom, viewport.bottom)
    const visibleHeight = visibleBottom - visibleTop

    const visibleLeft = Math.max(p.rect.left, viewport.left)
    const visibleRight = Math.min(p.rect.right, viewport.right)
    const visibleWidth = visibleRight - visibleLeft

    if (visibleHeight > 0 && visibleWidth > 0) {
      return p.index
    }
  }

  return 0
}

export function parseParagraphsFromContent(rawContent: string): string[] {
  if (!rawContent) return []
  const rawLines = rawContent.split('\n')
  const result: string[] = []
  for (let i = 0; i < rawLines.length; i++) {
    const trimmed = rawLines[i].trim()
    if (trimmed) {
      result.push(trimmed)
    }
  }
  return result
}

export function shouldAppendNextChapter({
  scrollY,
  clientHeight,
  scrollHeight,
  threshold = 900,
}: {
  scrollY: number
  clientHeight: number
  scrollHeight: number
  threshold?: number
}): boolean {
  return scrollY + clientHeight >= scrollHeight - threshold
}

export type ChapterViewportRect = {
  index: number
  top: number
  bottom: number
}

export function findActiveChapterInViewport(chapters: ChapterViewportRect[], viewportHeight: number): number {
  if (chapters.length === 0) return 0
  const midPoint = viewportHeight * 0.4
  for (const ch of chapters) {
    if (ch.top <= midPoint && ch.bottom >= midPoint) {
      return ch.index
    }
  }
  if (chapters[0].top > midPoint) return chapters[0].index
  return chapters[chapters.length - 1].index
}

export function isChapterVisibleInVirtualWindow(
  chapterIndex: number,
  activeChapterIndex: number,
  keepWindow = 2
): boolean {
  return Math.abs(chapterIndex - activeChapterIndex) <= keepWindow
}

export function calculateChapterScrollPosition({
  currentY,
  clientHeight,
  sectionTop,
  sectionHeight,
}: {
  currentY: number
  clientHeight: number
  sectionTop: number
  sectionHeight: number
}): number {
  if (!Number.isFinite(sectionHeight) || sectionHeight <= clientHeight) return 0
  const offsetInChapter = Math.max(0, currentY - sectionTop)
  const maxScrollInChapter = Math.max(1, sectionHeight - clientHeight)
  const ratio = offsetInChapter / maxScrollInChapter
  return Number.isFinite(ratio) ? Math.min(1, Math.max(0, ratio)) : 0
}

export function paragraphIndexToRatio(pIndex: number, totalParagraphs: number): number {
  if (!Number.isFinite(totalParagraphs) || totalParagraphs <= 1 || pIndex <= 0) return 0
  const ratio = pIndex / (totalParagraphs - 1)
  return Number.isFinite(ratio) ? Math.min(1, Math.max(0, ratio)) : 0
}

export function ratioToParagraphIndex(ratio: number, totalParagraphs: number): number {
  if (!Number.isFinite(totalParagraphs) || totalParagraphs <= 1 || !Number.isFinite(ratio) || ratio <= 0) return 0
  return Math.min(totalParagraphs - 1, Math.max(0, Math.round(ratio * (totalParagraphs - 1))))
}



