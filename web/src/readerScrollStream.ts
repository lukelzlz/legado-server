/**
 * 连续滚动的引擎（纯函数）—— 整个项目只有这一套滚动模型。
 *
 * ## 模型
 *
 * 一整条连续文档，**坐标以已经渲染出来的内容为准**：
 *
 * - 已读章节的正文就在文档里，向上滚动永远不需要重建 ⇒ 不会出现「跳回上一章开头」。
 * - 向下读到接近底部时按批追加后面的章节（只追加，不改动上方结构）。
 * - 视口上方**最多保留 [SCROLL_KEEP_ABOVE] 章正文**，出现第 4 章时把最上面那一章
 *   收成**等高占位**（高度取收起前实测值）⇒ 文档高度不变、**不需要任何滚动补偿**。
 * - 向上滚回已读章节时，占位块再**换回正文**（这就是「重新装载」）。
 *
 * 这正是当前实现与其他方案反复出问题的地方：凡是**移除视口上方的章节**，文档高度就会变小，
 * 浏览器随即钳制滚动位置，于是出现 `segs 3→2`、`scrollHeight` 一帧坍缩几千像素、
 * 以及一次大位移的补偿。等高占位把这些都不存在化：结构变化只发生在视口之外，且高度是精确值。
 */

export type ScrollStreamSlot = {
  index: number
  /** `text` = 已渲染正文；`placeholder` = 等高占位（收起了正文）。 */
  mode: 'text' | 'placeholder'
  /** 收起正文时的实测高度（px）；`text` 模式下无意义。 */
  height: number
}

export type ScrollVirtualizePlan = {
  /** 需要收成占位的章节下标（都在视口上方足够远处）。 */
  toPlaceholder: number[]
  /** 需要把占位换回正文的章节下标（向上滚回来时命中，即「重新装载」）。 */
  toText: number[]
}

/**
 * 视口上方「必须始终保持渲染」的像素距离。
 *
 * 取「两屏」而不是「一屏」：WebKit 的惯性滑动一帧可以位移几百像素，
 * 用一屏的话可能刚好在手指还在滑的时候跨过边界，于是视口里出现占位。
 */
export function keepRenderedDistance(viewportHeight: number): number {
  return Math.max(1200, viewportHeight * 2)
}

/**
 * 计算「视口上方第一个必须保持渲染的章节下标」。
 *
 * 这是收占位的**硬下界**：所有 `index >= 该值` 的章节都不许收起。
 * 向上滚动时这个值单调不增，因此已读区间不会被重新收掉。
 */
export function computeFirstRenderedIndex(
  slots: Array<{ index: number; top: number; height: number }>,
  keepDistance: number,
): number {
  let first = Number.POSITIVE_INFINITY
  for (const slot of slots) {
    if (!Number.isFinite(slot.top) || !Number.isFinite(slot.height)) continue
    // slot.top 是相对视口的坐标：`top + height` 是这一段的底边
    const bottom = slot.top + slot.height
    if (bottom >= -keepDistance) first = Math.min(first, slot.index)
  }
  return Number.isFinite(first) ? first : Number.NEGATIVE_INFINITY
}

/**
 * 当前章之上最多保留多少章**渲染着的正文**（用户定调：三章；出现第 4 章就卸掉最上面那章）。
 */
export const SCROLL_KEEP_ABOVE = 3

/**
 * 未测章节的高度估算：用**最近的已测邻居**的高度，而不是「一屏高」。
 *
 * ⚠️ 这条是本项目最贵的一个坑（用户报障：「向上划看上面的章节时总会跳动，本该在上一章末尾，
 * 直接跳到上一章开头」）。原先未测章节在 DOM 里只撑「100vh」，而真实章节约 8500px：
 * 滚动过程中它们被量到真实高度后，文档一次性变长数千像素/章 ⇒ 视口内容整体位移，
 * 观感就是「跳回上一章开头」。
 * 同一本书的章节长度通常接近，用邻居实测值估算可以把误差压到很小，
 * 再配合「先估算、量到真实值后**同帧**补差」的收尾，位移就看不出来了。
 */
export function estimateChapterHeight(
  index: number,
  measured: ReadonlyMap<number, number>,
): number | null {
  for (let offset = 1; offset <= 6; offset++) {
    const before = measured.get(index - offset)
    if (before !== undefined && before > 0) return before
    const after = measured.get(index + offset)
    if (after !== undefined && after > 0) return after
  }
  return null
}

/**
 * 计算本帧要收起 / 换回正文的章节。
 *
 * 规则（用户定调）：**当前章之上最多保留 [SCROLL_KEEP_ABOVE] 章正文**；
 * 再往上就把最上面那一章收成**精确等高占位**；向上滚回来时占位再换回正文。
 *
 * 为什么收起也不会跳：占位块的高度就是收起前实测的像素高度 ⇒ 文档高度**一点没变**、
 * 被收章节的空间仍然被占住 ⇒ 不需要任何滚动补偿。
 *
 * `measured` 是**已经量到实测高度**的章节集合。
 * 量到高度的章节按**真实高度**收起（精确）；量不到的（例如向上补齐、从未渲染过的那几章）
 * 用 [estimateChapterHeight] 估算，避免它们以「一屏高」的骨架形态撑在文档里 ——
 * 那会让滚动几何随着测量推进而持续变化（就是「向上划时跳动」的根因）。
 */
export function planPlaceholders(
  slots: ScrollStreamSlot[],
  firstRenderedIndex: number,
  measured: ReadonlySet<number>,
  keepAbove: number = SCROLL_KEEP_ABOVE,
): ScrollVirtualizePlan {
  const toPlaceholder: number[] = []
  const toText: number[] = []
  // 按章节顺序走一遍：视口上方**只放行最近 keepAbove 章**，更上面的全部收成占位。
  const above = slots
    .filter(slot => Number.isFinite(slot.index) && slot.index < firstRenderedIndex)
    .sort((a, b) => a.index - b.index)
  // ⚠️ 不能写成 `above.slice(-keepAbove)`：keepAbove=0 时 `slice(-0)` 等价于 `slice(0)` = **保留全部**，
  // 语义正好反过来（实测：上限 0 时一个都没收起）。
  const allowed = new Set(
    keepAbove <= 0 ? [] : above.slice(Math.max(0, above.length - keepAbove)).map(slot => slot.index),
  )
  for (const slot of slots) {
    if (!Number.isFinite(slot.index)) continue
    if (slot.index >= firstRenderedIndex) {
      // 进入保留区 ⇒ 占位换回正文（重新装载）
      if (slot.mode === 'placeholder') toText.push(slot.index)
      continue
    }
    if (allowed.has(slot.index)) {
      if (slot.mode === 'placeholder' && measured.has(slot.index)) toText.push(slot.index)
      continue
    }
    // 超出保留上限 ⇒ 收成等高占位（量到过高度用真实值；没有真实值也必须收，
    // 否则它以一屏高的骨架留在文档里、后续测量会持续改变滚动几何）
    if (slot.mode === 'text') toPlaceholder.push(slot.index)
  }
  return { toPlaceholder, toText }
}

/** 向下追加一批章节的下标区间（闭区间）。 */
export const SCROLL_APPEND_BATCH = 12

/**
 * 当前章前后各预载多少章（用户定调：**上下各 2 章**）。
 *
 * 目的：正文章节**跨越章界之前就已经在手里**，而不是「读到章界那一刻才开始加载」——
 * 后者的表现就是跨章时出现空白/骨架帧，紧接着内容到位、文档位移，观感即是「跳」。
 */
export const SCROLL_NEIGHBOUR_BUFFER = 2

/**
 * 缓冲区间：以 `live` 为中心，上下各 [SCROLL_NEIGHBOUR_BUFFER] 章（越界自动钳制）。
 */
export function bufferLoadRange(live: number, totalChapters: number, buffer = SCROLL_NEIGHBOUR_BUFFER): { from: number; to: number } | null {
  if (totalChapters <= 0) return null
  const center = Math.max(0, Math.min(totalChapters - 1, live))
  return {
    from: Math.max(0, center - buffer),
    to: Math.min(totalChapters - 1, center + buffer),
  }
}

export function nextAppendRange(loadedTail: number, totalChapters: number, batch = SCROLL_APPEND_BATCH): { from: number; to: number } | null {
  if (totalChapters <= 0) return null
  const last = Math.min(totalChapters - 1, loadedTail)
  if (last >= totalChapters - 1) return null
  const from = last + 1
  const to = Math.min(totalChapters - 1, from + batch - 1)
  return { from, to }
}

/**
 * 按批追加时**多缓冲这么多章**（用户定调「上下各 2 章」）。
 *
 * 实际的「读到哪里才开始加载下一批」由背压信号决定（还有两屏 + 预看距离），
 * 这里只是保证每次批量加载都往前多带几章 ⇒ 跨章界时正文已经躺在手里。
 */
export const SCROLL_BATCH_LOOKAHEAD = SCROLL_NEIGHBOUR_BUFFER

/**
 * 向前（上方）补齐一批章节：当视口顶部离「已渲染流的最顶部」只剩不到这么多像素时触发。
 *
 * 向上滚动的语义是「回到已经读过的地方」，那些章节此前已渲染过、只是被收成了占位；
 * 把它们换回正文由 [planPlaceholders] 负责。这里只负责**更上方、从未渲染过**的章节
 * （例如从目录直接跳到第 300 章，上方 0~299 章从未加载）。
 */
export function prependDistanceTrigger(viewportHeight: number): number {
  return Math.max(600, viewportHeight)
}

export function previousPrependRange(loadedHead: number, batch = SCROLL_APPEND_BATCH): { from: number; to: number } | null {
  if (loadedHead <= 0) return null
  const to = loadedHead - 1
  const from = Math.max(0, to - batch + 1)
  return { from, to }
}

/**
 * 前插章节后的滚动修正量。
 *
 * 在视口**上方**插入 `insertedHeight` 像素的内容后，文档整体下移，
 * 当前视口指向的内容会往上跑 ⇒ 必须把 `scrollY` 加上同样的量，视觉位置才不动。
 *
 * 上插不会让文档变短，因此**永远不会触发浏览器的滚动位置钳制**，
 * 一次同帧 `scrollTo` 就足够，不需要任何重试或残差补偿。
 */
export function prependScrollFix(insertedHeight: number): number {
  return Math.max(0, insertedHeight)
}

/** 视口顶部落在哪一章上（判定「当前章」）。 */
export function chapterIndexAtViewportTop(
  slots: Array<{ index: number; top: number }>,
  viewportTopThreshold = 0,
): number | null {
  let found: number | null = null
  let foundTop = Number.NEGATIVE_INFINITY
  for (const slot of slots) {
    if (!Number.isFinite(slot.top)) continue
    if (slot.top <= viewportTopThreshold && slot.top > foundTop) {
      foundTop = slot.top
      found = slot.index
    }
  }
  return found
}

/**
 * 章节区块的 React key。
 *
 * ⚠️ 必须是**只由章节下标决定**的稳定值：同一章在「正文态 ⇄ 等高占位态」之间切换时
 * 若 key 变了，React 会重建 DOM 节点 ⇒ 高度归零 ⇒ 滚动位置跳动。
 */
export function scrollSectionKey(index: number): string {
  return `chapter-${index}`
}
