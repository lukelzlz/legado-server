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

/**
 * 跨章翻页的方向。
 *
 * `next` = 向下一章翻（内容向左走）；`prev` = 向上一章翻（内容向右走）。
 */
export type ChapterTurnDirection = 'next' | 'prev'

/** 跨章过渡的阶段：`pending` = 已决定跨章、等新章内容就位；`in` = 新章沿手势方向滑入。 */
export type ChapterTurnPhase = 'pending' | 'in'

/**
 * 跨章翻页过渡层的类名。
 *
 * ⚠️ 这里是**类名契约的唯一出处**：`styles.css` 里的关键帧选择器必须与它逐字对齐，
 * 否则就是「永不命中的死配置」（仓库既有教训：PWA 缓存规则写了一个根本不存在的路由）。
 * 单测会拿它去比对真实 CSS，防止两边各写一份而漂移。
 */
export function chapterTurnClassName(phase: ChapterTurnPhase | null, direction: ChapterTurnDirection): string {
  const base = 'reader-chapter-turn'
  return phase ? `${base} is-${phase} is-${direction}` : base
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

/**
 * 把章节正文切成段落。
 *
 * 滚动模式的「三章窗口」要对相邻章做与当前章**完全相同**的切分，
 * 因此抽成一个函数，避免两处各写一份而漂移。
 */
export function splitParagraphs(raw: string): string[] {
  if (!raw) return []
  const result: string[] = []
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    if (trimmed) result.push(trimmed)
  }
  return result
}

/** 滚动窗口里一个章节区块在视口中的位置。 */
export type ScrollSectionRect = {
  /** 章节下标（书内全局下标） */
  index: number
  /** 区块顶部相对视口顶部的偏移（即可为负） */
  top: number
}

/**
 * 三章窗口里判定「当前正在阅读的是哪一章」。
 *
 * 规则：取**最后一个顶部不晚于视口中心**的区块。
 *
 * | 场景 | 视口中心落在 | 判定 |
 * | :--- | :--- | :--- |
 * | 正在读中间章 | 中间章顶部之下、下一章顶部之上 | 中间章 |
 * | 向下滚过下一章开头 | 下一章顶部之下 | **下一章** ⇒ 窗口下移 |
 * | 向上滚过当前章开头 | 当前章顶部之上 | **上一章** ⇒ 窗口上移 |
 *
 * 用「视口中心」而不是「视口顶部」是为了**对称**：向下与向上各需滚过约半屏才换章，
 * 不会出现「刚露头就换章」的抖动。
 *
 * @returns 命中的章节下标；区块为空时返回 null（调用方据此跳过本次平移）
 */
export function dominantScrollSection(rects: ScrollSectionRect[], viewportCenter: number): number | null {
  let found: number | null = null
  let foundTop = Number.NEGATIVE_INFINITY
  for (const rect of rects) {
    // Ref Map 的插入顺序不等于 DOM 顺序：React 重绑 callback ref 后，
    // Safari 可能让相邻区块以任意顺序重新登记。必须按视口位置找最靠后的区块。
    if (Number.isFinite(rect.top) && rect.top <= viewportCenter && rect.top > foundTop) {
      found = rect.index
      foundTop = rect.top
    }
  }
  return found
}

export function isScrollSectionTransitionAllowed(currentIndex: number, nextIndex: number, scrollDelta: number): boolean {
  if (nextIndex === currentIndex) return false
  if (scrollDelta > 0) return nextIndex > currentIndex
  if (scrollDelta < 0) return nextIndex < currentIndex
  return false
}

/**
 * 三章窗口平移后，为保持**视觉位置不动**需要补偿的滚动量。
 *
 * 平移会从文档顶部移除/插入区块（例如下移时丢掉「上一章」），
 * 文档内容会整体位移。滚动坐标与内容位移方向相反：锚点向上移动时，
 * 必须增加 `scrollY` 才能把它拉回原来的视口位置。因此补偿量是
 * `anchorTopBefore - anchorTopAfter`，调用方将它加到当前 `scrollY`。
 *
 * 这与区块高度无关，因此无需等新章节加载完也能算准。
 */
export function scrollCompensation(anchorTopBefore: number, anchorTopAfter: number): number {
  return anchorTopBefore - anchorTopAfter
}



