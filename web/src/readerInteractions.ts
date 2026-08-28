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


