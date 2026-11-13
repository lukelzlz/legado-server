// 与仓库里其它组件一致地引入 React：测试用 tsx 跑的是 classic JSX 变换，
// 少这一行会让 `renderToStaticMarkup` 抛 `ReferenceError: React is not defined`。
import React, { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, BookmarkRecord, Chapter } from './api'
import { Icon } from './icons'
import { matchBestChapter } from './sourceSwitchRemap'
import { toast } from './Toast'
import { ButtonSpinner } from './ButtonSpinner'

/**
 * 书签列表（阅读器顶栏「书签」按钮打开的那个）。
 *
 * 对应开源阅读的「书签页」：列表按章节顺序排，点一条跳过去，右侧可编辑/删除。
 * 位置的**捕获**不在这里——滚动/分页状态在 `ReaderScreen` 手里，
 * 这里只通过 `onAddHere` 请它按当前阅读位置加一条，拿回落库后的记录插进列表。
 *
 * 边界（踩过的坑，别再犯）：
 * 1. **不嵌套第二层弹窗**：编辑/删除确认都做成列表内的行内状态，
 *    嵌套 `.modal-backdrop` 会因为动画/遮罩叠加出现幽灵穿透与层级错乱。
 * 2. **章名优先、下标兜底**：书签存的是加书签那一刻的章名与序号，
 *    换源或目录重排之后序号会漂，展示与跳转都要按章名重新对齐（复用换源的同一套匹配）。
 * 3. 列表的增删改**只改本地列表**、不做整页重拉：一次成功的写操作服务端已返回权威行，
 *    再拉一次会让弹窗在弱网下闪一下空列表。
 */

export type BookmarkListModalProps = {
  bookName: string
  bookAuthor?: string | null
  chapters: Chapter[]
  currentChapterIndex: number
  /** 在**当前阅读位置**加一条书签；返回落库后的记录（失败返回 null）。 */
  onAddHere: () => Promise<BookmarkRecord | null>
  /** 跳转到书签：落点换算（字符偏移 → 滚动比例）由阅读器完成。 */
  onJump: (bookmark: BookmarkRecord) => void
  onClose: () => void
}

/** 章节顺序 + 章内位置的稳定排序（与服务端 `listBookmarks` 的排序口径一致）。 */
function sortBookmarks(items: BookmarkRecord[]): BookmarkRecord[] {
  return [...items].sort((a, b) => (a.chapterIndex - b.chapterIndex) || (a.chapterPos - b.chapterPos))
}

export function BookmarkListModal({
  bookName,
  bookAuthor,
  chapters,
  currentChapterIndex,
  onAddHere,
  onJump,
  onClose,
}: BookmarkListModalProps) {
  const { t } = useTranslation()
  const [items, setItems] = useState<BookmarkRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  /** 正在行内编辑的书签 id（null = 列表态）。 */
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draftText, setDraftText] = useState('')
  const [draftNote, setDraftNote] = useState('')
  /** 正在确认删除的书签 id（null = 没有待确认的删除）。 */
  const [confirmId, setConfirmId] = useState<number | null>(null)
  const [busy, setBusy] = useState<'add' | 'save' | 'delete' | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const result = await api.bookmarks(bookName, bookAuthor)
      setItems(sortBookmarks(result.items ?? []))
    } catch (err) {
      setError(err instanceof Error ? err.message : t('reader.bookmarkLoadFailed', '书签加载失败'))
    } finally {
      setLoading(false)
    }
  }, [bookAuthor, bookName, t])

  useEffect(() => {
    void reload()
  }, [reload])

  /** 章名优先、下标兜底地还原「这条书签在哪一章」。 */
  const chapterTitleOf = (item: BookmarkRecord): string => {
    const matched = matchBestChapter(chapters, item.chapterName ?? undefined, item.chapterIndex)
    if (matched.title) return matched.title
    if (item.chapterName) return item.chapterName
    return t('reader.chapterIndex', { count: item.chapterIndex + 1, defaultValue: `第 ${item.chapterIndex + 1} 章` })
  }

  const addHere = async () => {
    setBusy('add')
    try {
      const created = await onAddHere()
      if (created) {
        setItems(prev => sortBookmarks([...prev.filter(item => item.id !== created.id), created]))
      }
    } finally {
      setBusy(null)
    }
  }

  const beginEdit = (item: BookmarkRecord) => {
    setEditingId(item.id)
    setConfirmId(null)
    setDraftText(item.bookText ?? '')
    setDraftNote(item.content ?? '')
  }

  const saveEdit = async (item: BookmarkRecord) => {
    setBusy('save')
    try {
      const saved = await api.updateBookmark(item.id, { bookText: draftText, content: draftNote })
      setItems(prev => prev.map(row => (row.id === saved.id ? saved : row)))
      setEditingId(null)
      toast.success(t('common.saved', '已保存'))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(null)
    }
  }

  const remove = async (item: BookmarkRecord) => {
    setBusy('delete')
    try {
      await api.deleteBookmark(item.id)
      setItems(prev => prev.filter(row => row.id !== item.id))
      setConfirmId(null)
      if (editingId === item.id) setEditingId(null)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t('common.failed', '操作失败'))
    } finally {
      setBusy(null)
    }
  }

  const jump = (item: BookmarkRecord) => {
    onJump(item)
    onClose()
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="bookmark-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('reader.bookmarks', '书签')}
        onClick={event => event.stopPropagation()}
      >
        <header className="bookmark-dialog-header">
          <div className="bookmark-dialog-title">
            <div className="bookmark-dialog-kicker">
              <Icon name="bookmark" />
              <span>{t('reader.bookmarks', '书签')}</span>
            </div>
            <h2 title={bookName}>{bookName}</h2>
            <small>
              {items.length > 0
                ? t('reader.bookmarkCount', { count: items.length, defaultValue: `共 ${items.length} 条` })
                : t('reader.noBookmarks', '暂无书签')}
            </small>
          </div>
          <div className="bookmark-dialog-actions">
            <button
              type="button"
              className="subtle-button bookmark-add-button"
              onClick={() => void addHere()}
              disabled={busy !== null}
              aria-busy={busy === 'add'}
            >
              {busy === 'add' ? <ButtonSpinner size={12} /> : <Icon name="bookmark" />}
              <span>{t('reader.addBookmark', '添加书签')}</span>
            </button>
            <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
              <Icon name="close" />
            </button>
          </div>
        </header>

        <div className="bookmark-list">
          {loading && <p className="reader-status">{t('common.loading', '加载中...')}</p>}
          {!loading && error && <p className="reader-error">{error}</p>}
          {!loading && !error && items.length === 0 && <p className="reader-status">{t('reader.noBookmarks', '暂无书签')}</p>}
          {!loading && items.map(item => (
            <article
              key={item.id}
              className={`bookmark-item ${item.chapterIndex === currentChapterIndex ? 'current' : ''} ${editingId === item.id ? 'editing' : ''}`}
            >
              {editingId === item.id ? (
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
                    <button type="button" className="subtle-button" onClick={() => setEditingId(null)} disabled={busy !== null}>
                      {t('common.cancel', '取消')}
                    </button>
                    <button type="button" className="primary-button" onClick={() => void saveEdit(item)} disabled={busy !== null} aria-busy={busy === 'save'}>
                      {busy === 'save' && <ButtonSpinner size={12} />}
                      {t('common.save', '保存')}
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  <button type="button" className="bookmark-jump" onClick={() => jump(item)} title={t('reader.jumpToBookmark', '跳转到书签')}>
                    <strong className="bookmark-chapter">{chapterTitleOf(item)}</strong>
                    <span className="bookmark-text">{item.bookText || t('reader.bookmarkNoText', '（无原文摘要）')}</span>
                    {item.content ? <em className="bookmark-note">{item.content}</em> : null}
                    <small className="bookmark-time">{new Date(item.createdAt).toLocaleDateString()}</small>
                  </button>
                  <div className="bookmark-item-actions">
                    <button type="button" className="subtle-button" onClick={() => beginEdit(item)} title={t('reader.editBookmark', '编辑书签')} aria-label={t('reader.editBookmark', '编辑书签')}>
                      <Icon name="edit" />
                    </button>
                    <button type="button" className="subtle-button shelf-remove" onClick={() => setConfirmId(item.id)} title={t('reader.deleteBookmarkTitle', '删除书签')} aria-label={t('reader.deleteBookmarkTitle', '删除书签')}>
                      <Icon name="trash" />
                    </button>
                  </div>
                </>
              )}
              {confirmId === item.id && (
                <div className="bookmark-confirm">
                  <span>{t('reader.deleteBookmarkMessage', '确认删除这条书签？')}</span>
                  <button type="button" className="subtle-button shelf-remove" onClick={() => void remove(item)} disabled={busy !== null} aria-busy={busy === 'delete'}>
                    {busy === 'delete' && <ButtonSpinner size={12} />}
                    {t('common.delete', '删除')}
                  </button>
                  <button type="button" className="subtle-button" onClick={() => setConfirmId(null)} disabled={busy !== null}>
                    {t('common.cancel', '取消')}
                  </button>
                </div>
              )}
            </article>
          ))}
        </div>
      </div>
    </div>
  )
}
