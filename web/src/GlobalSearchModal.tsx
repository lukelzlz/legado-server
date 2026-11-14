// 与仓库里其它组件一致地引入 React：测试用 tsx 跑的是 classic JSX 变换，
// 少这一行会让 `renderToStaticMarkup` 抛 `ReferenceError: React is not defined`。
import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { api, LocalSearchHit, LocalSearchMode } from './api'
import { BookCover } from './BookCover'
import { ButtonSpinner } from './ButtonSpinner'
import { Icon } from './icons'
import type { ReaderTheme } from './readerSettings'

/**
 * 全局搜索弹窗（书架页顶栏「搜索」按钮 / 阅读器顶栏「正文搜索」按钮共用）。
 *
 * ## 三种模式搜的是**本地库**，不是书源
 * | 模式 | 数据源 | 点结果做什么 |
 * | :-- | :-- | :-- |
 * | `shelf` 书架 | `book_shelf` 的书名 / 作者 | 打开那本书 |
 * | `toc` 目录 | `book_chapter_index`（已抓到的目录摊平） | 打开书并跳到该章 |
 * | `content` 正文 | `book_content_cache`（已缓存的正文） | 打开书并跳到该章 |
 *
 * 「打开书 + 跳章」由调用方决定（`onPick`）：书架页手里有书架条目，阅读器手里有 `changeChapter`，
 * 这里只负责查询与展示 —— 弹窗不该知道怎么开书。
 *
 * ## 边界（踩过的坑）
 * 1. **portal 到 body 且显式绑 `theme-*`**：浮层脱离 `.app-shell` 后 CSS 变量会回退成
 *    透明背景 + 黑字（夜间模式表现为"透光 + 隐形文字"）；
 * 2. **不嵌套第二层 `.modal-backdrop`**：带动画的遮罩里再嵌一层会出现幽灵穿透；
 * 3. **请求必须可取消**：输入框 debounce 300ms，且每次输入都 `abort` 上一次请求，
 *    否则慢查询会让「后发先至」的旧结果覆盖新结果；
 * 4. 所有按钮显式 `type="button"`（表单里的默认 submit 曾导致搜索被重启）。
 */
export type GlobalSearchModalProps = {
  /** 当前主题（portal 脱离 `.app-shell` 后必须显式补上主题类，否则变量全丢）。 */
  theme: ReaderTheme
  /** 允许的模式。书架页三个都给；阅读器只给 `['content']`。 */
  modes?: LocalSearchMode[]
  /** 限定单本书（阅读器内「只搜当前书」）。 */
  scope?: { sourceId: string; bookUrl: string } | null
  /** 点一条结果：由调用方决定"打开这本书 / 跳到这一章"。 */
  onPick: (hit: LocalSearchHit) => void
  onClose: () => void
}

/** 一次请求最多取多少条（服务端还会把 1..100 之外的夹回来）。 */
const SEARCH_LIMIT = 50

/** 输入框 debounce（用户指定 300ms）。 */
const SEARCH_DEBOUNCE_MS = 300

/** 把命中的关键词包成 `<mark>`；没命中就原样返回。 */
function highlightSnippet(text: string, query: string): React.ReactNode {
  const needle = query.trim()
  if (!needle) return text
  const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const parts = text.split(new RegExp(`(${escaped})`, 'gi'))
  return parts.map((part, index) =>
    part.toLowerCase() === needle.toLowerCase()
      ? <mark key={index} className="global-search-mark">{part}</mark>
      : <React.Fragment key={index}>{part}</React.Fragment>,
  )
}

export function GlobalSearchModal({
  theme,
  modes = ['shelf', 'toc', 'content'],
  scope = null,
  onPick,
  onClose,
}: GlobalSearchModalProps) {
  const { t } = useTranslation()
  /** 阅读器里只给一个模式时不做成"只有一个 tab"的怪样子，但仍然保留同一套状态。 */
  const available = useMemo<LocalSearchMode[]>(
    () => (modes.length > 0 ? modes : ['shelf']),
    [modes],
  )
  const [mode, setMode] = useState<LocalSearchMode>(available[0])
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<LocalSearchHit[]>([])
  const [truncated, setTruncated] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  /** 是否已经真正发过一次查询（区分「还没搜」与「搜了没结果」）。 */
  const [searched, setSearched] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  // 模式被外部收窄（理论上只在重新挂载时变）时校正一次，避免停在一个不存在的 tab 上
  useEffect(() => {
    if (!available.includes(mode)) setMode(available[0])
  }, [available, mode])

  // ESC ≡ 关闭（参考现有弹窗写法）
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  // 打开即聚焦输入框（书架页点「搜索」后用户下一步一定是输入）
  useEffect(() => {
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0)
    return () => window.clearTimeout(timer)
  }, [])

  const sourceId = scope?.sourceId ?? ''
  const bookUrl = scope?.bookUrl ?? ''

  useEffect(() => {
    const trimmed = query.trim()
    if (!trimmed) {
      // 空查询不发请求（服务端也会直接回空，但没必要占一次往返）
      setItems([])
      setTruncated(false)
      setSearched(false)
      setError('')
      setLoading(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    setError('')
    const timer = window.setTimeout(() => {
      api.searchLocal(
        {
          mode,
          query: trimmed,
          limit: SEARCH_LIMIT,
          sourceId: sourceId || undefined,
          bookUrl: bookUrl || undefined,
        },
        controller.signal,
      )
        .then(result => {
          if (controller.signal.aborted) return
          setItems(result.items ?? [])
          setTruncated(Boolean(result.truncated))
          setSearched(true)
        })
        .catch(err => {
          if (controller.signal.aborted) return
          setItems([])
          setTruncated(false)
          setSearched(true)
          setError(err instanceof Error ? err.message : t('search.failed', '搜索失败'))
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false)
        })
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
      setLoading(false)
    }
  }, [query, mode, sourceId, bookUrl, t])

  const modeLabel = (value: LocalSearchMode): string => {
    if (value === 'shelf') return t('search.shelf', '书架')
    if (value === 'toc') return t('search.toc', '目录')
    return t('search.content', '正文')
  }

  const hintOf = (value: LocalSearchMode): string => {
    if (value === 'shelf') return t('search.hintShelf', '在书架里按书名或作者查找')
    if (value === 'toc') return t('search.hintToc', '在已获取到目录的书里按章节名查找')
    return t('search.hintContent', '在已缓存的正文里查找（只包含下载/阅读过的章节）')
  }

  const pick = (hit: LocalSearchHit) => {
    onPick(hit)
    onClose()
  }

  const chapterLabel = (hit: LocalSearchHit): string => {
    if (hit.chapterTitle) return hit.chapterTitle
    if (typeof hit.chapterIndex === 'number') {
      return t('reader.chapterIndex', { count: hit.chapterIndex + 1, defaultValue: `第 ${hit.chapterIndex + 1} 章` })
    }
    return ''
  }

  return createPortal(
    <div className={`modal-backdrop global-search-backdrop theme-${theme}`} onClick={onClose}>
      <div
        className="global-search-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('search.title', '搜索')}
        onClick={event => event.stopPropagation()}
      >
        <header className="global-search-header">
          <div className="global-search-title">
            <Icon name="search" />
            <h2>{t('search.title', '搜索')}</h2>
            {scope && <small className="global-search-scope">{t('search.scopeCurrentBook', '仅当前书籍')}</small>}
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        {available.length > 1 && (
          <nav className="global-search-tabs" aria-label={t('search.title', '搜索')}>
            {available.map(value => (
              <button
                key={value}
                type="button"
                className={mode === value ? 'active' : ''}
                onClick={() => setMode(value)}
              >
                {modeLabel(value)}
              </button>
            ))}
          </nav>
        )}

        <div className="global-search-input">
          <Icon name="search" />
          <input
            ref={inputRef}
            value={query}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('search.modalPlaceholder', '输入关键词…')}
            aria-label={hintOf(mode)}
          />
          {loading && <ButtonSpinner size={14} />}
          {!loading && query.trim().length > 0 && (
            <button
              type="button"
              className="subtle-button global-search-clear"
              onClick={() => setQuery('')}
              aria-label={t('common.clear', '清空')}
            >
              <Icon name="close" />
            </button>
          )}
        </div>

        <p className="global-search-hint">{hintOf(mode)}</p>

        <div className="global-search-results">
          {loading && items.length === 0 && <p className="reader-status">{t('search.loading', '正在搜索…')}</p>}
          {!loading && error && <p className="reader-error">{error}</p>}
          {!loading && !error && searched && items.length === 0 && (
            <p className="reader-status">{t('search.empty', '没有找到匹配的内容')}</p>
          )}
          {items.map((hit, index) => (
            <button
              key={`${hit.sourceId}\u0000${hit.bookUrl}\u0000${hit.chapterUrl ?? ''}\u0000${index}`}
              type="button"
              className={`global-search-item global-search-item-${hit.kind}`}
              onClick={() => pick(hit)}
            >
              {hit.kind === 'shelf' ? (
                <>
                  <span className="global-search-cover">
                    <BookCover coverKey={hit.coverKey} name={hit.bookName || ''} className="global-search-cover-img" fallbackClassName="global-search-cover-fallback" />
                  </span>
                  <span className="global-search-body">
                    <strong className="global-search-book-name">{hit.bookName}</strong>
                    <small className="global-search-book-author">{hit.bookAuthor || t('common.unknownAuthor', '佚名')}</small>
                  </span>
                </>
              ) : (
                <span className="global-search-body">
                  <strong className="global-search-book-name">{hit.bookName || t('common.unknownBook', '未知书籍')}</strong>
                  <span className="global-search-chapter">{chapterLabel(hit)}</span>
                  {hit.kind === 'content' && hit.snippet && (
                    <span className="global-search-snippet">{highlightSnippet(hit.snippet, query)}</span>
                  )}
                </span>
              )}
              <Icon name="arrowRight" />
            </button>
          ))}
          {truncated && (
            <p className="global-search-truncated">
              {t('search.truncated', '结果过多，只显示前 {{count}} 条，请细化关键词', { count: SEARCH_LIMIT })}
            </p>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}

