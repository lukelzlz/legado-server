import type { Chapter } from './api'

/**
 * 「换源后停在原章节」的**纯函数**实现（小说 / 漫画 / 有声三端共用）。
 *
 * ## 为什么必须抽成纯函数
 * 换源的**候选源排序**与**弹窗交互**是"看得见"的部分，出了问题用户会立刻发现；
 * 而**重映射**是"看不见"的部分——映射错了只会表现为"换源后跳到莫名其妙的章节"，
 * 既没有报错也不会白屏，极难在人工点测里复现（依赖两端目录的标题形态）。
 * 抽成纯函数后，边界用例（章名完全一致 / 仅序号一致 / 新目录更短 / 完全匹配不到 / 空目录）
 * 都可以在 Node 里逐条锁定。
 *
 * 本文件的匹配规则**必须与抽取前 `SourceSwitchModal.matchBestChapter` 逐字等价**，
 * 否则小说阅读器的用户可见行为就会变（这是本 PR 的硬性红线）。
 */

/**
 * 章节标题归一化：去掉「第X章/回/节/集/卷」前缀、`Chapter N` 前缀、空白与全部标点符号后小写。
 *
 * 目的是让「第二章 绝世神功」「第2章 绝世神功（修）」「2. 绝世神功」归一化后能对上。
 */
export function normalizeChapterTitle(title: string): string {
  return title
    .replace(/^第[0-9一二三四五六七八九十百千万]+[章回节集卷]\s*/gu, '')
    .replace(/^chapter\s*[0-9]+/iu, '')
    .replace(/[\s\p{P}\p{S}]/gu, '')
    .toLowerCase()
}

/** 重映射结果。 */
export type SourceRemapResult = {
  /** 新目录里的落点下标（空目录恒为 0）。 */
  index: number
  /** 落点章节标题（空目录为 `''`）。 */
  title: string
  /**
   * 是否按**标题**匹配上了。
   *
   * `false` 有两种含义，调用方都需要知道：① 标题完全匹配不到、退回按序号定位
   * （"停在最接近的位置"）；② 新目录为空。两者都只提醒、不报错。
   */
  matched: boolean
}

/**
 * 章节名匹配（其余实现共用的唯一真相）。
 *
 * 优先级：原始标题完全一致 → 归一化标题完全一致 → 子串包含；
 * 三者都失败则**退化为章节序号**，并把序号钳到合法区间
 * （新目录更短时停在最后一章，而不是越界或抛错）。
 */
function resolveChapter(
  chapters: Chapter[],
  currentTitle?: string,
  currentIndex?: number,
): SourceRemapResult {
  if (chapters.length === 0) return { index: 0, title: '', matched: false }

  if (currentTitle) {
    const normCurrent = normalizeChapterTitle(currentTitle)
    // 归一化后只剩 1 个字符时**不做**模糊匹配：那是「序」「终」这类通用字，
    // 拿它去子串匹配会把无关章节错认成同一章。
    if (normCurrent.length >= 2) {
      // 1. 原始标题完全一致
      const exact = chapters.find(c => c.title.trim() === currentTitle.trim())
      if (exact) return { index: exact.index, title: exact.title, matched: true }

      // 2. 归一化标题完全一致
      const normExact = chapters.find(c => normalizeChapterTitle(c.title) === normCurrent)
      if (normExact) return { index: normExact.index, title: normExact.title, matched: true }

      // 3. 子串包含（任一方包含另一方）
      const substr = chapters.find(c => {
        const norm = normalizeChapterTitle(c.title)
        return norm.length >= 2 && (norm.includes(normCurrent) || normCurrent.includes(norm))
      })
      if (substr) return { index: substr.index, title: substr.title, matched: true }
    }
  }

  // 兜底：按序号定位，并钳到合法区间（完全匹配不到 ⇒ 停在最接近位置）
  const safeIdx = Math.min(Math.max(0, currentIndex ?? 0), chapters.length - 1)
  return { index: safeIdx, title: chapters[safeIdx]?.title || '', matched: false }
}

/**
 * 章节名匹配 —— **与抽取前签名/语义完全一致的兼容入口**。
 *
 * 既有调用方（含小说阅读器的既有单测）继续用它；新增代码请用
 * {@link remapChapterAfterSourceSwitch} 以同时拿到「是否按标题匹配上」。
 */
export function matchBestChapter(
  chapters: Chapter[],
  currentTitle?: string,
  currentIndex?: number,
): { index: number; title: string } {
  const { index, title } = resolveChapter(chapters, currentTitle, currentIndex)
  return { index, title }
}

/**
 * 换源后的落点：把当前章节映射到新目录。
 *
 * `chapters.length === 0`（新源目录为空）时返回 `{ index: 0, title: '', matched: false }`，
 * **不抛异常**——调用方可以照常把 `index: 0` 写进状态，列表渲染出空态即可。
 */
export function remapChapterAfterSourceSwitch(
  chapters: Chapter[],
  currentTitle?: string,
  currentIndex?: number,
): SourceRemapResult {
  return resolveChapter(chapters, currentTitle, currentIndex)
}
