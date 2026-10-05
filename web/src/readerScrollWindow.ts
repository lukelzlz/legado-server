/**
 * 连续滚动的「章节挂载窗口」引擎（纯函数）。
 *
 * ## 为什么需要它
 *
 * 三章窗口（[上一章, 当前章, 下一章]）在每次跨章时都会**从视口上方摘掉一章**，
 * 同时补上新的下一章。WebKit 实测（`.recon/webkit_boundary_repro.py`）：
 *
 * ```
 * 过界前   y=15678  sh=24597  segs=3  chaps=[2,3,4]
 * 变更     segs=2   chaps=[3,4]  sh → 16191   ← 一帧内文档塌 8400px
 * 补偿     scrollTo(7440.84)  before=15259 after=7440   ← 单次 −8238px 重定位
 * ```
 *
 * `scrollTo(7440)` 在数学上是「视觉不动」，但那一帧「段数 3→2 + 文档高度坍缩」
 * 在真实 iOS 惯性滚动里会让滚动位置被钳制/回弹 ⇒ 用户看到跳动。
 *
 * ## 两条策略
 *
 * - `window`：现状。三章固定窗口，跨章平移，DOM 变更 + 同一帧内补偿。
 * - `keep`：**只追加不摘**。把已读章节留在 DOM 里，跨章不再改动视口上方的结构，
 *   因此**根本不需要补偿**（`compensation = 0`）；`content-visibility: auto` 负责
 *   把离屏章节的渲染成本压掉。只在离视口足够远时才回收。
 *
 * 两条策略共用同一组不变量，见 [scrollWindowInvariantViolations]。
 */

export type ScrollMountStrategy = 'window' | 'keep'

/** 挂载窗口：文档里连续存在的章节区间，`head`/`tail` 都是闭区间端点。 */
export type ScrollWindowRange = {
  /** 最先（最靠上）被挂载的章节下标。 */
  head: number
  /** 最后（最靠下）被挂载的章节下标。 */
  tail: number
}

/** 当前章前后需要常驻的章节数。超出这个范围的尾部会被回收（仅当离视口足够远）。 */
export type KeepBounds = {
  /** 当前章之前保留多少章（决定往回滑多远还能不重建）。 */
  before: number
  /** 当前章之后常驻多少章（决定向前铺多远）。 */
  after: number
}

export const DEFAULT_KEEP_BOUNDS: KeepBounds = { before: 6, after: 2 }

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value))

/**
 * 计算「当前章为 liveIdx」时希望挂载的区间。
 *
 * `keep` 策略的区间是**单调向前扩张**的：已经挂载过的章节不会被主动缩小，
 * 只有离视口足够远时才由 [scrollWindowTrimPlan] 回收 —— 这是「跨章不改动上方 DOM」的保证。
 *
 * @param previous 上一轮已经挂载的区间（首次为 null）。传入它即可得到单调扩张的结果。
 */
export function desiredScrollWindow(
  strategy: ScrollMountStrategy,
  liveIdx: number,
  totalChapters: number,
  previous: ScrollWindowRange | null,
  bounds: KeepBounds = DEFAULT_KEEP_BOUNDS,
): ScrollWindowRange {
  const total = Math.max(0, totalChapters)
  if (total === 0) return { head: 0, tail: -1 }
  const live = clamp(liveIdx, 0, total - 1)

  if (strategy === 'window') {
    // 现状：手头一章 + 上下各一章。
    return { head: Math.max(0, live - 1), tail: Math.min(total - 1, live + 1) }
  }

  if (!previous) {
    return { head: clamp(live - 2, 0, total - 1), tail: clamp(live + 1, 0, total - 1) }
  }

  // keep：向前扩张到「当前章 + after」，但绝不把已挂载的 head/tail 往回缩。
  const wantTail = clamp(live + bounds.after, 0, total - 1)
  const tail = Math.max(previous.tail, wantTail)
  // 区间必须连续且包含 live；previous.head 可能因为回收而大于 live，这里是防御性收敛。
  const head = Math.min(previous.head, live)
  return { head: clamp(head, 0, total - 1), tail: clamp(Math.max(tail, live), 0, total - 1) }
}

export type ScrollTrimPlan = {
  /** 需要从文档里移除的章节下标。一定**整段位于视口之外**（上方或下方）。 */
  remove: number[]
  /** 回收后剩下的窗口（区间仍连续、仍包含当前章）。 */
  next: ScrollWindowRange
}

/** 回收区离视口的最小安全距离（px）：至少半个视口，保证补偿/移除不可能被看见。 */
const MIN_TRIM_MARGIN = 240

/**
 * 计算「可以安全回收哪些已挂载章节」。
 *
 * 两侧都回收，但理由不同：
 *
 * - **头部**（视口上方）：跨章时的常规回收。移除会让 `scrollY` 变小 ⇒ 配套一次
 *   `scrollTo(scrollY - 移除高度)`（[trimCompensation]）就能视觉不动。
 * - **尾部**（视口下方）：处理「大幅跳章」留下的夹层。例如从第 5 章跳到第 300 章，
 *   中间 200 多章既不在保留区、也不在头部回收区；不回收就会永久驻留、DOM 无限增长。
 *   尾部移除**不需要也不应该**补偿滚动位置（它在视口下方，`scrollY` 本来就与它无关）。
 *
 * 安全条件（两侧共用）：整段离视口至少 `max(240, 半个视口)`，
 * 这样即使补偿晚了一帧，也不可能出现在屏幕里。
 *
 * @param rects 各章节相对视口的几何（`top`/`height`，来自 `getBoundingClientRect`）；
 *              缺失的章节视为「不可回收」，宁可多留一帧也不跳。
 * @param keepFloor 当前章之前的保留条数；`index >= liveIdx - keepFloor` 一律不回收。
 * @param keepCeil 当前章之后的保留条数；`index <= liveIdx + keepCeil` 一律不回收。
 */
export function scrollWindowTrimPlan(
  range: ScrollWindowRange,
  liveIdx: number,
  viewportHeight: number,
  keepFloor: number,
  rects: Map<number, { top: number; height: number }>,
  keepCeil: number = 0,
): ScrollTrimPlan {
  const margin = Math.max(MIN_TRIM_MARGIN, viewportHeight * 0.5)
  const remove: number[] = []
  const next: ScrollWindowRange = { head: range.head, tail: range.tail }

  // —— 头部：视口上方 ——
  const floorIndex = liveIdx - Math.max(0, keepFloor)
  for (let index = range.head; index < floorIndex; index++) {
    const rect = rects.get(index)
    if (!rect || rect.height <= 0) break
    // 该章节的底边必须整段位于视口上方（再留一段安全距离）。
    const bottom = rect.top + rect.height
    if (bottom > -margin) break
    remove.push(index)
    next.head = index + 1
  }

  // —— 尾部：视口下方（跳章留下的夹层）——
  // 从窗口最底部往回走，只回收「完全在视口下方」的连续尾巴。
  const ceilIndex = liveIdx + Math.max(0, keepCeil)
  const tailRemoved: number[] = []
  for (let index = range.tail; index > ceilIndex; index--) {
    const rect = rects.get(index)
    if (!rect || rect.height <= 0) break
    // 该章节的顶边必须整段位于视口下方（再留一段安全距离）。
    if (rect.top < viewportHeight + margin) break
    tailRemoved.push(index)
    next.tail = index - 1
  }
  // 从大到小收集的，倒回来保持升序，便于调用方阅读与测试断言
  remove.push(...tailRemoved.reverse())

  if (remove.length === 0) return { remove: [], next: range }
  // 回收后必须仍然包含当前章且区间合法，否则放弃这次回收。
  if (next.head > liveIdx || next.tail < liveIdx || next.head > next.tail) {
    return { remove: [], next: range }
  }
  return { remove, next }
}

/**
 * 回收视口上方章节后需要的滚动补偿量。
 *
 * 上方总高度减少 `removedHeight` ⇒ 文档整体上移 ⇒ `scrollY` 必须减少同样的量，
 * 视觉位置才不动。与 [readerInteractions.scrollCompensation] 同一口径（差值即补偿量），
 * 这里直接用高度和表达，语义更直白。
 */
export function trimCompensation(removedHeight: number): number {
  // 刻意写成 `0 - x` 而不是 `-x`：后者在 x=0 时产出 `-0`，
  // 会让 `assert.equal(trimCompensation(0), 0)` 这类严格比较假失败，也容易让人怀疑补偿方向。
  return 0 - Math.max(0, removedHeight)
}

/**
 * 同一章在不同轮次之间必须保持稳定的 React key，否则 React 会重建 DOM 节点
 * （重建 = 高度短暂归零 = 跳动）。返回 `null` 表示该章节不该出现在窗口里。
 */
export function scrollSectionKey(index: number): string {
  return `chapter-${index}`
}

export type ScrollInvariantSample = {
  index: number
  /** 相对视口的顶部位置（px）。 */
  top: number
  /** 章节高度（px）。 */
  height: number
}

export type ScrollInvariantViolation = {
  step: number
  kind: 'backward-shift' | 'oversized-shift' | 'anchor-drift'
  detail: string
}

/**
 * 连续滚动的共享不变量（A/B 探针与单测共用同一套判据）。
 *
 * 1. **方向一致**：向下滚时 `scrollY` 不得变小、向上滚时不得变大（边界钳制除外）。
 * 2. **位移有界**：单步位移不得超过一个视口高度 —— 跨章不该产生整章级别的重定位。
 * 3. **锚点连续**：同一个锚点元素在一步内的绝对位置位移应≈ −Δscroll。
 *
 * @param direction +1 向下 / -1 向上
 */
export function scrollWindowInvariantViolations(
  samples: Array<{ y: number; anchorAbsTop: number | null; anchorId: string | null }>,
  direction: 1 | -1,
  viewportHeight: number,
): ScrollInvariantViolation[] {
  const violations: ScrollInvariantViolation[] = []
  for (let i = 1; i < samples.length; i++) {
    const a = samples[i - 1]
    const b = samples[i]
    const dy = b.y - a.y
    if (dy !== 0) {
      const reversed = direction > 0 ? dy < 0 : dy > 0
      if (reversed) {
        violations.push({ step: i, kind: 'backward-shift', detail: `scrollY ${a.y}→${b.y}（${dy}）` })
      } else if (Math.abs(dy) > viewportHeight) {
        violations.push({ step: i, kind: 'oversized-shift', detail: `单步位移 ${dy}px 超过一个视口` })
      }
    }
    if (
      a.anchorAbsTop !== null && b.anchorAbsTop !== null &&
      a.anchorId !== null && a.anchorId === b.anchorId
    ) {
      const drift = (b.anchorAbsTop - a.anchorAbsTop) + dy
      if (Math.abs(drift) > 8) {
        violations.push({ step: i, kind: 'anchor-drift', detail: `锚点 ${a.anchorId} 漂移 ${drift}px` })
      }
    }
  }
  return violations
}
