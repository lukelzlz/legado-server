/**
 * 书签位置的换算（纯函数，可单测）。
 *
 * 阅读器内部的进度是**比例**（`scrollPosition`，0..1，相对本章正文区块的滚动范围），
 * 而备份包 `bookmark.json` 里的 `chapterPos` 是**章内字符偏移**（开源阅读的语义：
 * `Bookmark.chapterPos` 就是正文里的字符下标，不是比例）。书签既要能被手机端的备份认，
 * 也要能被别的端读回来，所以**入库一律存字符偏移**，落点再换算回比例。
 *
 * 两条公式互逆（`bookmarkPositionFraction(bookmarkOffsetFromPosition(p, n), n) === p`），
 * 因此自己加的书签跳回去就是原来那一屏。
 *
 * 为什么可以按字符数线性映射：中文正文的行高基本只由字数决定，标题与段间距在各章之间
 * 是同一套排版 ⇒ 两者近似成正比，误差落在一个段落以内。跨端导入的书签（例如手机端
 * 按它自己那份正文算出来的偏移）同样按这条公式落点，误差量级一致。
 */

/** 比例 → 章内字符偏移（越界钳到正文范围内；空正文恒为 0）。 */
export function bookmarkOffsetFromPosition(position: number, textLength: number): number {
  const length = Math.max(0, Math.floor(textLength))
  if (length === 0) return 0
  const ratio = Number.isFinite(position) ? Math.min(1, Math.max(0, position)) : 0
  return Math.min(length, Math.max(0, Math.round(ratio * length)))
}

/** 章内字符偏移 → 比例（越界钳到 0..1；空正文恒为 0）。 */
export function bookmarkPositionFraction(offset: number, textLength: number): number {
  const length = Math.max(0, Math.floor(textLength))
  if (length === 0) return 0
  const safeOffset = Number.isFinite(offset) ? Math.min(length, Math.max(0, offset)) : 0
  return Math.min(1, Math.max(0, safeOffset / length))
}

/**
 * 书签的「原文摘要」：从书签位置起截一段，折掉换行与多余空白。
 *
 * 与 `bookmark.json` 里的 `bookText` 同用途——列表里给用户认位置用，不需要整页原文。
 * 位置贴在章尾时向前补一段，避免摘要为空。
 */
export function bookmarkSnippet(text: string, offset: number, maxLength = 100): string {
  const source = text ?? ''
  const limit = Math.max(1, Math.floor(maxLength))
  const length = source.length
  if (length === 0) return ''
  const start = Math.min(length, Math.max(0, Number.isFinite(offset) ? Math.floor(offset) : 0))
  const tail = source.slice(start, start + limit)
  const picked = tail.trim().length > 0 ? tail : source.slice(Math.max(0, start - limit), start)
  return picked.replace(/\s+/g, ' ').trim()
}
