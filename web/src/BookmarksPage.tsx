import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, BookCoverCandidate, BookmarkBook, BookmarkRecord } from './api'
import { BookCover, coverSources } from './BookCover'
import { Icon } from './icons'
import { toast } from './Toast'
import { ButtonSpinner } from './ButtonSpinner'
import { bookKeyOf, filterBookmarkBooks, filterBookmarkRecords, sortBookmarks } from './bookmarkGroups'

/**
 * 「书签」一级页面：左栏 = 有书签的书（书名 / 作者 / 封面），右栏 = 选中那本书的书签。
 *
 * 数据源是 `bookmark` 表本身，**不要求书在书架上**（用户要求「一股脑全存到表里，不论有没有这本书」）：
 * 不在书架的书没有封面，显示首字占位。
 *
 * 布局与交互刻意**照搬「替换净化规则」页**（同一套 `rules-page-container` / `rules-sidebar` /
 * `rules-list` / `bookmark-item` / `source-batch-bar` 样式）：用户要求「界面仿照规则的页面」。
 *
 * 两侧都能批量管理，且**互斥**（打开一侧会收起另一侧，避免两条悬浮栏同时压在屏幕上）：
 * - 左栏：勾选多本书 → 删掉它们**全部**书签（书签删光后这些书自然从左栏消失）；
 * - 右栏：勾选多条书签 → 删除；另有「删除本书全部书签」。
 *
 * 封面（用户要求：像书架换封面一样，可选书源搜出来的候选，也可上传本地图片）：
 * - 左栏「请求封面」：按书名+作者去书源补封面；连续 5 次抓不到就标记「无封面」并不再自动重试；
 * - 点任意封面 = 打开「设置封面」弹层（书源候选 / 上传本地图片 / 重新获取 / 移除）。
 *
 * ⚠️ 书签删完必须**重新拉左栏**（左栏是服务端按书聚合出来的），否则会出现「书签没了书还在」的假象。
 */

export type BookmarksPageProps = {
  /**
   * 点右栏书签 → 打开阅读器并跳到该条书签的位置。
   *
   * 由 `main.tsx` 接：只有它手里有「书名+作者 → 书架条目 → sourceId/bookUrl」的映射。
   */
  onOpenBookmark?: (target: {
    bookName: string
    bookAuthor?: string | null
    chapterIndex: number
    chapterPos: number
    /** 章名（换源后目录会变，阅读器按「章名优先」重新定位）。 */
    chapterName?: string | null
  }) => void
}

export function BookmarksPage({ onOpenBookmark }: BookmarksPageProps = {}) {
  const { t } = useTranslation()
  const [books, setBooks] = useState<BookmarkBook[]>([])
  const [loadingBooks, setLoadingBooks] = useState(true)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [marks, setMarks] = useState<BookmarkRecord[]>([])
  const [loadingMarks, setLoadingMarks] = useState(false)
  const [bookQuery, setBookQuery] = useState('')
  const [markQuery, setMarkQuery] = useState('')

  const [leftBatch, setLeftBatch] = useState(false)
  const [leftSelected, setLeftSelected] = useState<Set<string>>(new Set())
  const [rightBatch, setRightBatch] = useState(false)
  const [rightSelected, setRightSelected] = useState<Set<number>>(new Set())

  const [editingId, setEditingId] = useState<number | null>(null)
  const [draftText, setDraftText] = useState('')
  const [draftNote, setDraftNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [fetchingCovers, setFetchingCovers] = useState(false)

  /** 封面设置弹层（点封面打开）。 */
  const [coverFor, setCoverFor] = useState<BookmarkBook | null>(null)
  const [candidates, setCandidates] = useState<BookCoverCandidate[]>([])
  const [loadingCandidates, setLoadingCandidates] = useState(false)
  const [coverUploading, setCoverUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const selectedBook = useMemo(
    () => books.find(book => bookKeyOf(book) === selectedKey) ?? null,
    [books, selectedKey],
  )
  const visibleBooks = useMemo(() => filterBookmarkBooks(books, bookQuery), [books, bookQuery])
  const visibleMarks = useMemo(() => filterBookmarkRecords(marks, markQuery), [marks, markQuery])

  /**
   * 重新拉左栏。`preferKey` 用于「删完之后想留在哪本书上」：
   * 该书的书签已删光 ⇒ 服务端不再返回它 ⇒ 自动落到列表第一本（或清空）。
   */
  const loadOverview = useCallback(async (preferKey?: string | null) => {
    try {
      const result = await api.bookmarkOverview()
      const nextBooks = result.items ?? []
      setBooks(nextBooks)
      setSelectedKey(prev => {
        const wanted = preferKey ?? prev
        if (wanted && nextBooks.some(book => bookKeyOf(book) === wanted)) return wanted
        return nextBooks.length > 0 ? bookKeyOf(nextBooks[0]) : null
      })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('bookmarks.loadFailed', '书签加载失败'))
    } finally {
      setLoadingBooks(false)
    }
  }, [t])

  const loadMarks = useCallback(async (book: BookmarkBook | null) => {
    if (!book) {
      setMarks([])
      return
    }
    setLoadingMarks(true)
    try {
      const result = await api.bookmarks(book.bookName, book.bookAuthor)
      setMarks(sortBookmarks(result.items ?? []))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('bookmarks.loadFailed', '书签加载失败'))
    } finally {
      setLoadingMarks(false)
    }
  }, [t])

  useEffect(() => {
    void loadOverview()
  }, [loadOverview])

  // 选中的书一变就拉它的书签；`selectedBook` 在每次重拉左栏后是新对象 ⇒ 顺带刷新右栏
  useEffect(() => {
    setRightSelected(new Set())
    setEditingId(null)
    void loadMarks(selectedBook)
  }, [loadMarks, selectedBook])

  const toggleSet = <T,>(set: Set<T>, value: T): Set<T> => {
    const next = new Set(set)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    return next
  }

  /** 两侧批量模式**互斥**：打开一侧立刻收起另一侧并清空它的选择。 */
  const openLeftBatch = () => {
    setLeftBatch(true)
    setLeftSelected(new Set())
    setRightBatch(false)
    setRightSelected(new Set())
  }
  const openRightBatch = () => {
    setRightBatch(true)
    setRightSelected(new Set())
    setLeftBatch(false)
    setLeftSelected(new Set())
  }
  const exitBatch = () => {
    setLeftBatch(false)
    setLeftSelected(new Set())
    setRightBatch(false)
    setRightSelected(new Set())
  }

  const removeMark = async (item: BookmarkRecord) => {
    if (!window.confirm(t('bookmarks.deleteMarkConfirm', '删除这条书签？\n删除后不可恢复！'))) return
    setBusy(true)
    try {
      await api.deleteBookmark(item.id)
      setMarks(prev => prev.filter(mark => mark.id !== item.id))
      await loadOverview(selectedKey)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  const saveMark = async (item: BookmarkRecord) => {
    setBusy(true)
    try {
      const saved = await api.updateBookmark(item.id, { bookText: draftText, content: draftNote })
      setMarks(prev => prev.map(mark => (mark.id === saved.id ? saved : mark)))
      setEditingId(null)
      toast.success(t('common.saved', '已保存'))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  /** 右栏批量：按 id 删选中的书签。 */
  const deleteSelectedMarks = async () => {
    if (rightSelected.size === 0) return
    const count = rightSelected.size
    if (!window.confirm(t('bookmarks.batchDeleteMarkConfirm', {
      count,
      defaultValue: `删除选中的 ${count} 条书签？\n删除后不可恢复！`,
    }))) return
    setBusy(true)
    try {
      const result = await api.deleteBookmarksBatch({ ids: Array.from(rightSelected) })
      toast.success(t('bookmarks.marksDeleted', { count: result.deleted, defaultValue: `已删除 ${result.deleted} 条书签` }))
      setRightSelected(new Set())
      setRightBatch(false)
      await loadOverview(selectedKey)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  /** 右栏「删本书全部」：按书删（与左栏批量等价，只是作用域限定在当前书）。 */
  const deleteAllMarksOfBook = async () => {
    if (!selectedBook) return
    if (!window.confirm(t('bookmarks.deleteBookConfirm', {
      name: selectedBook.bookName,
      defaultValue: `删除《${selectedBook.bookName}》的全部书签？\n删除后不可恢复！`,
    }))) return
    setBusy(true)
    try {
      const result = await api.deleteBookmarksBatch({
        books: [{ bookName: selectedBook.bookName, bookAuthor: selectedBook.bookAuthor }],
      })
      toast.success(t('bookmarks.marksDeleted', { count: result.deleted, defaultValue: `已删除 ${result.deleted} 条书签` }))
      setRightBatch(false)
      setRightSelected(new Set())
      await loadOverview(null)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  /** 左栏批量：按书删（书签删光后这些书从左栏消失）。 */
  const deleteSelectedBooks = async () => {
    const targets = books.filter(book => leftSelected.has(bookKeyOf(book)))
    if (targets.length === 0) return
    const names = targets.map(book => book.bookName).join(t('common.separator', '、'))
    if (!window.confirm(t('bookmarks.batchDeleteBookConfirm', {
      count: targets.length,
      names,
      defaultValue: `删除选中的 ${targets.length} 本书的全部书签？\n（${names}）\n删除后不可恢复！`,
    }))) return
    setBusy(true)
    try {
      const result = await api.deleteBookmarksBatch({
        books: targets.map(book => ({ bookName: book.bookName, bookAuthor: book.bookAuthor })),
      })
      toast.success(t('bookmarks.marksDeleted', { count: result.deleted, defaultValue: `已删除 ${result.deleted} 条书签` }))
      setLeftSelected(new Set())
      setLeftBatch(false)
      await loadOverview(null)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  /** 「请求封面」：给左栏里还没有封面的书按书名+作者去书源抓一遍（批量）。 */
  const requestCovers = async (force = false) => {
    const targets = books.filter(book => !book.coverKey && (force || !book.coverMissing))
    if (targets.length === 0) {
      toast.info(t('bookmarks.coverNothingToDo', '没有需要补封面的书'))
      return
    }
    setFetchingCovers(true)
    try {
      const result = await api.refreshBookCovers({
        books: targets.map(book => ({ bookName: book.bookName, bookAuthor: book.bookAuthor })),
        force,
      })
      toast.success(t('bookmarks.coverRefreshResult', {
        fetched: result.fetched,
        failed: result.failed,
        skipped: result.skipped,
        marked: result.marked,
        defaultValue: `封面：成功 ${result.fetched} 张，失败 ${result.failed} 本`,
      }))
      await loadOverview(selectedKey)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setFetchingCovers(false)
    }
  }

  /** 弹层里针对当前书再抓一次（`force`：手动触发不受"已标记放弃"限制）。 */
  const refetchCoverFor = async (book: BookmarkBook) => {
    setFetchingCovers(true)
    try {
      const result = await api.refreshBookCovers({
        books: [{ bookName: book.bookName, bookAuthor: book.bookAuthor }],
        force: true,
      })
      if (result.fetched > 0) {
        toast.success(t('bookmarks.coverUpdated', '封面已更新'))
        setCoverFor(null)
        await loadOverview(bookKeyOf(book))
      } else {
        toast.warning(t('bookmarks.coverFetchFailed', '没抓到封面，可手动上传'))
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setFetchingCovers(false)
    }
  }

  /** 打开「设置封面」弹层，并顺手去书源搜候选（与书架换封面同一套体验）。 */
  const openCoverDialog = (book: BookmarkBook) => {
    setCoverFor(book)
    setCandidates([])
    setLoadingCandidates(true)
    void api.bookCoverCandidates(book.bookName, book.bookAuthor)
      .then(result => setCandidates(result.items ?? []))
      .catch(() => setCandidates([]))
      .finally(() => setLoadingCandidates(false))
  }

  const applyCover = async (payload: { coverKey?: string; coverUrl?: string; sourceId?: string; remove?: boolean }) => {
    const target = coverFor
    if (!target) return
    setBusy(true)
    try {
      await api.attachBookCover({ bookName: target.bookName, bookAuthor: target.bookAuthor, ...payload })
      toast.success(t('bookmarks.coverUpdated', '封面已更新'))
      setCoverFor(null)
      await loadOverview(bookKeyOf(target))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(false)
    }
  }

  const uploadCoverFile = async (file: File) => {
    setCoverUploading(true)
    try {
      const uploaded = await api.uploadCover(file)
      await applyCover({ coverKey: uploaded.coverKey })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('bookmarks.coverUploadFailed', '封面上传失败'))
    } finally {
      setCoverUploading(false)
    }
  }

  const jumpToMark = (item: BookmarkRecord) => {
    onOpenBookmark?.({
      bookName: item.bookName,
      bookAuthor: item.bookAuthor,
      chapterIndex: item.chapterIndex,
      chapterPos: item.chapterPos,
      chapterName: item.chapterName,
    })
  }

  /** 封面缩略图（点击 = 设置封面）。 */
  const renderCover = (book: BookmarkBook, large = false) => (
    <button
      type="button"
      className={`bookmark-book-cover ${large ? 'large' : ''}`}
      title={t('bookmarks.coverEditHint', '点击设置封面')}
      aria-label={t('bookmarks.coverEditHint', '点击设置封面')}
      onClick={event => {
        event.stopPropagation()
        openCoverDialog(book)
      }}
    >
      <BookCover
        coverKey={book.coverKey}
        coverUrl={book.coverUrl}
        name={book.bookName}
        className="bookmark-book-cover-img"
        fallbackClassName="bookmark-book-cover-fallback"
      />
    </button>
  )

  return (
    <main className={`rules-page-container bookmarks-page ${leftBatch || rightBatch ? 'batch-mode-active' : ''}`}>
      {/* 左栏：有书签的书 */}
      <aside className="rules-sidebar">
        <div className="source-sidebar-heading">
          <div className="source-sidebar-title-row">
            <span>{t('bookmarks.title', '书签')}</span>
            <small>{books.length}</small>
          </div>
          <div className="source-sidebar-top-actions">
            <button
              type="button"
              className="subtle-button"
              title={t('bookmarks.requestCoversHint', '按书名 + 作者去书源补封面（连续 5 次找不到会标记并停止自动重试）')}
              disabled={fetchingCovers}
              onClick={() => void requestCovers()}
            >
              {fetchingCovers ? <ButtonSpinner size={12} /> : <Icon name="image" />}
              <span>{t('bookmarks.requestCovers', '请求封面')}</span>
            </button>
            <button
              type="button"
              className={`subtle-button batch-mode-btn ${leftBatch ? 'active-batch-btn' : ''}`}
              title={t('bookmarks.batchBooksHint', '批量删除整本书的书签')}
              onClick={() => (leftBatch ? exitBatch() : openLeftBatch())}
            >
              <Icon name="list" />
              <span>{leftBatch ? t('common.ok', '完成') : t('bookmarks.batchManage', '批量管理')}</span>
            </button>
          </div>
        </div>

        <div className="rules-toolbar">
          <div className="rules-search-wrap">
            <Icon name="search" />
            <input
              className="rules-search-input"
              placeholder={t('bookmarks.searchBookPlaceholder', '搜索书名 / 作者')}
              value={bookQuery}
              onChange={event => setBookQuery(event.target.value)}
            />
          </div>
        </div>

        <nav className="rules-list">
          {loadingBooks && (
            <div className="bookmark-page-hint">{t('common.loading', '加载中...')}</div>
          )}
          {!loadingBooks && books.length === 0 && (
            <div className="bookmark-page-hint">{t('bookmarks.emptyBooks', '还没有任何书签')}</div>
          )}
          {!loadingBooks && books.length > 0 && visibleBooks.length === 0 && (
            <div className="bookmark-page-hint">{t('bookmarks.noMatchingBooks', '没有匹配的书')}</div>
          )}
          {visibleBooks.map(book => {
            const key = bookKeyOf(book)
            const isSelected = key === selectedKey
            const isChecked = leftSelected.has(key)
            return (
              <div
                key={key}
                className={`rule-list-card bookmark-book-card ${isSelected ? 'selected' : ''} ${isChecked ? 'checked' : ''}`}
                onClick={() => {
                  if (leftBatch) setLeftSelected(prev => toggleSet(prev, key))
                  else setSelectedKey(key)
                }}
              >
                <div className="bookmark-book-row">
                  {leftBatch && (
                    <label className="batch-checkbox-wrap" onClick={event => event.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => setLeftSelected(prev => toggleSet(prev, key))}
                      />
                    </label>
                  )}
                  {renderCover(book)}
                  <div className="bookmark-book-meta">
                    <span className="rule-name-text" title={book.bookName}>{book.bookName}</span>
                    <small>{book.bookAuthor || t('common.unknownAuthor', '未知作者')}</small>
                  </div>
                  {book.coverMissing && !book.coverKey && (
                    <span
                      className="bookmark-book-cover-missing"
                      title={t('bookmarks.coverMissingHint', '连续 5 次没找到封面，已停止自动补抓；点封面可手动设置')}
                    >
                      {t('bookmarks.coverMissing', '无封面')}
                    </span>
                  )}
                  <span className="bookmark-book-count">
                    {t('bookmarks.countBadge', { count: book.count, defaultValue: `${book.count} 条` })}
                  </span>
                </div>
              </div>
            )
          })}
        </nav>
      </aside>

      {/* 右栏：选中书的书签 */}
      <section className="rules-content-area">
        {!selectedBook && !loadingBooks && (
          <div className="bookmark-empty-state">
            <Icon name="bookmark" />
            <p>{t('bookmarks.selectBookFirst', '先在左侧选择一本有书签的书')}</p>
          </div>
        )}

        {selectedBook && (
          <div className="rules-detail-card">
            <div className="rules-detail-header">
              <div className="rules-detail-title-group">
                <div className="bookmark-detail-head">
                  {renderCover(selectedBook, true)}
                  <div>
                    <h2>{selectedBook.bookName}</h2>
                    <small>
                      {selectedBook.bookAuthor || t('common.unknownAuthor', '未知作者')}
                      {' · '}
                      {t('bookmarks.countBadge', { count: selectedBook.count, defaultValue: `${selectedBook.count} 条` })}
                    </small>
                  </div>
                </div>
              </div>
              <div className="rules-detail-actions">
                <button
                  type="button"
                  className={`subtle-button batch-mode-btn ${rightBatch ? 'active-batch-btn' : ''}`}
                  onClick={() => (rightBatch ? exitBatch() : openRightBatch())}
                >
                  <Icon name="list" />
                  <span>{rightBatch ? t('common.ok', '完成') : t('bookmarks.batchManage', '批量管理')}</span>
                </button>
                <button
                  type="button"
                  className="danger-button"
                  disabled={busy || selectedBook.count === 0}
                  onClick={() => void deleteAllMarksOfBook()}
                >
                  <Icon name="trash" />
                  <span>{t('bookmarks.deleteBookMarks', '删除本书全部书签')}</span>
                </button>
              </div>
            </div>

            <div className="rules-toolbar">
              <div className="rules-search-wrap">
                <Icon name="search" />
                <input
                  className="rules-search-input"
                  placeholder={t('bookmarks.searchMarkPlaceholder', '搜索章节 / 原文 / 备注')}
                  value={markQuery}
                  onChange={event => setMarkQuery(event.target.value)}
                />
              </div>
            </div>

            <div className="bookmark-page-marks">
              {loadingMarks && <div className="bookmark-page-hint">{t('common.loading', '加载中...')}</div>}
              {!loadingMarks && marks.length === 0 && (
                <div className="bookmark-page-hint">{t('reader.noBookmarks', '暂无书签')}</div>
              )}
              {!loadingMarks && marks.length > 0 && visibleMarks.length === 0 && (
                <div className="bookmark-page-hint">{t('bookmarks.noMatchingMarks', '没有匹配的书签')}</div>
              )}
              {visibleMarks.map(mark => {
                const isChecked = rightSelected.has(mark.id)
                const isEditing = editingId === mark.id
                return (
                  <article key={mark.id} className={`bookmark-item ${isEditing ? 'editing' : ''} ${isChecked ? 'checked' : ''}`}>
                    {isEditing ? (
                      <div className="bookmark-edit">
                        <label className="bookmark-field">
                          <span>{t('reader.bookmarkOriginalText', '原文')}</span>
                          <textarea value={draftText} rows={3} onChange={event => setDraftText(event.target.value)} />
                        </label>
                        <label className="bookmark-field">
                          <span>{t('reader.bookmarkNote', '备注')}</span>
                          <textarea value={draftNote} rows={2} onChange={event => setDraftNote(event.target.value)} />
                        </label>
                        <div className="bookmark-edit-actions">
                          <button type="button" className="subtle-button" onClick={() => setEditingId(null)} disabled={busy}>
                            {t('common.cancel', '取消')}
                          </button>
                          <button type="button" className="primary-button" onClick={() => void saveMark(mark)} disabled={busy}>
                            {t('common.save', '保存')}
                          </button>
                        </div>
                      </div>
                    ) : (
                      <>
                        <div className="bookmark-mark-main">
                          {rightBatch && (
                            <label className="batch-checkbox-wrap" onClick={event => event.stopPropagation()}>
                              <input
                                type="checkbox"
                                checked={isChecked}
                                onChange={() => setRightSelected(prev => toggleSet(prev, mark.id))}
                              />
                            </label>
                          )}
                          <button
                            type="button"
                            className="bookmark-jump-static as-button"
                            title={t('bookmarks.openMark', '跳到这条书签的位置')}
                            onClick={() => jumpToMark(mark)}
                          >
                            <strong className="bookmark-chapter">
                              {mark.chapterName || t('reader.chapterIndex', { count: mark.chapterIndex + 1, defaultValue: `第 ${mark.chapterIndex + 1} 章` })}
                            </strong>
                            <span className="bookmark-text">{mark.bookText || t('reader.bookmarkNoText', '（无原文摘要）')}</span>
                            {mark.content ? <em className="bookmark-note">{mark.content}</em> : null}
                            <small className="bookmark-time">{new Date(mark.createdAt).toLocaleDateString()}</small>
                          </button>
                        </div>
                        <div className="bookmark-item-actions">
                          <button
                            type="button"
                            className="subtle-button"
                            title={t('reader.editBookmark', '编辑书签')}
                            aria-label={t('reader.editBookmark', '编辑书签')}
                            disabled={busy}
                            onClick={() => {
                              setEditingId(mark.id)
                              setDraftText(mark.bookText ?? '')
                              setDraftNote(mark.content ?? '')
                            }}
                          >
                            <Icon name="edit" />
                          </button>
                          <button
                            type="button"
                            className="subtle-button shelf-remove"
                            title={t('reader.deleteBookmarkTitle', '删除书签')}
                            aria-label={t('reader.deleteBookmarkTitle', '删除书签')}
                            disabled={busy}
                            onClick={() => void removeMark(mark)}
                          >
                            <Icon name="trash" />
                          </button>
                        </div>
                      </>
                    )}
                  </article>
                )
              })}
            </div>
          </div>
        )}
      </section>

      {/* 左栏批量工具栏 */}
      {leftBatch && (
        <aside className="source-batch-bar" aria-label={t('bookmarks.batchBooksAria', '书签批量操作工具栏（书籍）')}>
          <div className="batch-bar-left">
            <span className="batch-bar-count">
              {t('common.selected', '已选')} <strong>{leftSelected.size}</strong> / {visibleBooks.length}
            </span>
            <div className="batch-select-helpers">
              <button
                type="button"
                className="subtle-button compact"
                onClick={() => setLeftSelected(new Set(visibleBooks.map(book => bookKeyOf(book))))}
              >
                {t('shelf.selectAll', '全选')}
              </button>
              <button type="button" className="subtle-button compact" onClick={() => setLeftSelected(new Set())}>
                {t('source.deselectAll', '全不选')}
              </button>
              <button
                type="button"
                className="subtle-button compact"
                onClick={() => setLeftSelected(new Set(
                  visibleBooks.map(book => bookKeyOf(book)).filter(key => !leftSelected.has(key)),
                ))}
              >
                {t('common.invertSelect', '反选')}
              </button>
            </div>
          </div>
          <div className="batch-bar-right">
            <button
              type="button"
              className="danger-button"
              disabled={leftSelected.size === 0 || busy}
              onClick={() => void deleteSelectedBooks()}
            >
              {t('bookmarks.batchDeleteBooks', '删除所选书籍的书签')}
            </button>
            <button type="button" className="secondary-button" onClick={exitBatch}>
              {t('common.cancel', '取消')}
            </button>
          </div>
        </aside>
      )}

      {/* 右栏批量工具栏 */}
      {rightBatch && (
        <aside className="source-batch-bar" aria-label={t('bookmarks.batchMarksAria', '书签批量操作工具栏（书签）')}>
          <div className="batch-bar-left">
            <span className="batch-bar-count">
              {t('common.selected', '已选')} <strong>{rightSelected.size}</strong> / {visibleMarks.length}
            </span>
            <div className="batch-select-helpers">
              <button
                type="button"
                className="subtle-button compact"
                onClick={() => setRightSelected(new Set(visibleMarks.map(mark => mark.id)))}
              >
                {t('shelf.selectAll', '全选')}
              </button>
              <button type="button" className="subtle-button compact" onClick={() => setRightSelected(new Set())}>
                {t('source.deselectAll', '全不选')}
              </button>
              <button
                type="button"
                className="subtle-button compact"
                onClick={() => setRightSelected(new Set(
                  visibleMarks.map(mark => mark.id).filter(id => !rightSelected.has(id)),
                ))}
              >
                {t('common.invertSelect', '反选')}
              </button>
            </div>
          </div>
          <div className="batch-bar-right">
            <button
              type="button"
              className="danger-button"
              disabled={rightSelected.size === 0 || busy}
              onClick={() => void deleteSelectedMarks()}
            >
              {t('bookmarks.deleteSelected', '删除选中')}
            </button>
            <button type="button" className="secondary-button" onClick={exitBatch}>
              {t('common.cancel', '取消')}
            </button>
          </div>
        </aside>
      )}

      {/* 设置封面弹层：书源候选 / 上传本地图片 / 移除（与书架换封面同一套效果） */}
      {coverFor && (
        <div className="modal-backdrop" onClick={() => setCoverFor(null)}>
          <div
            className="bookmark-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={t('bookmarks.coverTitle', '设置封面')}
            onClick={event => event.stopPropagation()}
          >
            <header className="bookmark-dialog-header">
              <div className="bookmark-dialog-title">
                <div className="bookmark-dialog-kicker">
                  <Icon name="image" />
                  <span>{t('bookmarks.coverTitle', '设置封面')}</span>
                </div>
                <h2 title={coverFor.bookName}>{coverFor.bookName}</h2>
                <small>{coverFor.bookAuthor || t('common.unknownAuthor', '未知作者')}</small>
              </div>
              <button type="button" className="subtle-button close-btn" onClick={() => setCoverFor(null)} aria-label={t('common.close', '关闭')}>
                <Icon name="close" />
              </button>
            </header>

            <div className="bookmark-cover-body">
              <div className="bookmark-cover-current">
                <span className="bookmark-book-cover large">
                  <BookCover
                    coverKey={coverFor.coverKey}
                    coverUrl={coverFor.coverUrl}
                    name={coverFor.bookName}
                    className="bookmark-book-cover-img"
                    fallbackClassName="bookmark-book-cover-fallback"
                  />
                </span>
                <div className="bookmark-cover-actions">
                  <button
                    type="button"
                    className="subtle-button"
                    disabled={coverUploading || busy}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    {coverUploading ? <ButtonSpinner size={12} /> : <Icon name="upload" />}
                    <span>{t('bookmarks.coverUpload', '上传本地图片')}</span>
                  </button>
                  <button
                    type="button"
                    className="subtle-button"
                    disabled={fetchingCovers || busy}
                    onClick={() => void refetchCoverFor(coverFor)}
                  >
                    {fetchingCovers ? <ButtonSpinner size={12} /> : <Icon name="refresh" />}
                    <span>{t('bookmarks.coverRefetch', '按书名重新获取')}</span>
                  </button>
                  <button
                    type="button"
                    className="subtle-button shelf-remove"
                    disabled={busy || (!coverFor.coverKey && !coverFor.coverUrl)}
                    onClick={() => void applyCover({ remove: true })}
                  >
                    <Icon name="trash" />
                    <span>{t('bookmarks.coverRemove', '移除封面')}</span>
                  </button>
                </div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={event => {
                    const file = event.target.files?.[0]
                    event.target.value = ''
                    if (file) void uploadCoverFile(file)
                  }}
                />
              </div>

              <div className="bookmark-cover-candidates">
                <span className="bookmark-field-label">{t('bookmarks.coverFromSources', '书源搜到的封面（点击使用）')}</span>
                {loadingCandidates && <div className="bookmark-page-hint">{t('common.loading', '加载中...')}</div>}
                {!loadingCandidates && candidates.length === 0 && (
                  <div className="bookmark-page-hint">{t('bookmarks.coverNoCandidates', '书源里没有搜到带封面的候选')}</div>
                )}
                <div className="bookmark-cover-candidate-grid">
                  {candidates.map(candidate => {
                    const sources = coverSources({ coverUrl: candidate.coverUrl })
                    return (
                      <button
                        key={`${candidate.sourceId}-${candidate.bookUrl}`}
                        type="button"
                        className="bookmark-cover-candidate"
                        title={`${candidate.name}${candidate.author ? ` / ${candidate.author}` : ''}`}
                        disabled={busy}
                        onClick={() => void applyCover({ coverUrl: candidate.coverUrl ?? undefined, sourceId: candidate.sourceId })}
                      >
                        {sources[0] ? <img src={sources[0]} alt="" referrerPolicy="no-referrer" /> : null}
                        <small>{candidate.name}</small>
                      </button>
                    )
                  })}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </main>
  )
}
