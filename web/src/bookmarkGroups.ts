import type { BookmarkBook, BookmarkRecord } from './api'

/**
 * 「书签」页（左栏书列表 / 右栏书签）用到的纯函数。
 *
 * 单独成文件是为了能在 Node 里直接单测：排序与筛选口径一旦写错，
 * 表现是"删完了书还在列表中"或"搜索搜不到"，都属于静默错误。
 */

/** 书的身份键：与服务端一致——作者为 null 与空串视为同一本书。 */
export function bookKeyOf(book: { bookName: string; bookAuthor?: string | null }): string {
  return `${book.bookName}\u0000${book.bookAuthor ?? ''}`
}

/** 章节顺序 + 章内位置的稳定排序（与服务端 `listBookmarks` 口径一致）。 */
export function sortBookmarks(items: BookmarkRecord[]): BookmarkRecord[] {
  return [...items].sort((a, b) => (a.chapterIndex - b.chapterIndex) || (a.chapterPos - b.chapterPos))
}

/** 左栏搜索：书名或作者包含关键字（不区分大小写）。 */
export function filterBookmarkBooks(books: BookmarkBook[], query: string): BookmarkBook[] {
  const keyword = query.trim().toLowerCase()
  if (!keyword) return books
  return books.filter(book =>
    book.bookName.toLowerCase().includes(keyword)
    || (book.bookAuthor ?? '').toLowerCase().includes(keyword))
}

/** 右栏搜索：章名 / 原文摘要 / 备注 任一命中即可。 */
export function filterBookmarkRecords(items: BookmarkRecord[], query: string): BookmarkRecord[] {
  const keyword = query.trim().toLowerCase()
  if (!keyword) return items
  return items.filter(item =>
    (item.chapterName ?? '').toLowerCase().includes(keyword)
    || (item.bookText ?? '').toLowerCase().includes(keyword)
    || (item.content ?? '').toLowerCase().includes(keyword))
}
