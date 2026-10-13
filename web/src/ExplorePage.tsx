import React, { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type ExploreCategory, type ExploreSourceItem, type SearchResult, type BookshelfItem } from './api'
import { Icon } from './icons'
import { toast } from './Toast'
import { loadSourceBook } from './searchStore'
import type { OpenBook } from './ReaderScreen'

interface ExplorePageProps {
  onOpen: (book: OpenBook, index: number) => void
}

export function ExplorePage({ onOpen }: ExplorePageProps) {
  const { t } = useTranslation()

  // 书源与分类状态
  const [sources, setSources] = useState<ExploreSourceItem[]>([])
  const [selectedSourceId, setSelectedSourceId] = useState<string>('')
  const [sourceQuery, setSourceQuery] = useState('')
  const [categories, setCategories] = useState<ExploreCategory[]>([])
  const [selectedCategory, setSelectedCategory] = useState<ExploreCategory | null>(null)

  // 书籍列表与分页状态
  const [books, setBooks] = useState<SearchResult[]>([])
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)

  // 加载状态
  const [loadingSources, setLoadingSources] = useState(false)
  const [loadingCategories, setLoadingCategories] = useState(false)
  const [loadingBooks, setLoadingBooks] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [openingBookUrl, setOpeningBookUrl] = useState<string | null>(null)

  // 书架比对
  const [shelfBooks, setShelfBooks] = useState<BookshelfItem[]>([])
  const [addingShelfMap, setAddingShelfMap] = useState<Record<string, boolean>>({})

  // 初始化加载书源与书架列表
  useEffect(() => {
    let active = true
    setLoadingSources(true)
    Promise.all([
      api.exploreSources(),
      api.bookshelf().catch(() => [] as BookshelfItem[]),
    ])
      .then(([sourceList, shelfList]) => {
        if (!active) return
        setSources(sourceList)
        setShelfBooks(shelfList)
        if (sourceList.length > 0) {
          const firstEnabled = sourceList.find(s => s.enabled) || sourceList[0]
          setSelectedSourceId(firstEnabled.id)
        }
      })
      .catch(err => {
        if (!active) return
        toast.error(err.message || t('explore.noExploreSources', '暂无支持发现页的书源'))
      })
      .finally(() => {
        if (active) setLoadingSources(false)
      })
    return () => {
      active = false
    }
  }, [t])

  // 当前选中的书源对象
  const currentSource = useMemo(() => {
    return sources.find(s => s.id === selectedSourceId) || null
  }, [sources, selectedSourceId])

  // 过滤后的书源列表
  const filteredSources = useMemo(() => {
    const q = sourceQuery.trim().toLowerCase()
    if (!q) return sources
    return sources.filter(s => s.name.toLowerCase().includes(q) || (s.group && s.group.toLowerCase().includes(q)))
  }, [sources, sourceQuery])

  // 当选中的书源变化时，获取其分类
  useEffect(() => {
    if (!selectedSourceId) {
      setCategories([])
      setSelectedCategory(null)
      setBooks([])
      return
    }

    let active = true
    setLoadingCategories(true)
    setCategories([])
    setSelectedCategory(null)
    setBooks([])
    setHasMore(true)

    api.exploreCategories(selectedSourceId)
      .then(catList => {
        if (!active) return
        setCategories(catList)
        // 自动选择第一个可用的分类（有有效 url）
        const findFirstUsable = (list: ExploreCategory[]): ExploreCategory | null => {
          for (const item of list) {
            if (item.url && item.url.trim().length > 0) return item
            if (item.subCategories && item.subCategories.length > 0) {
              const sub = findFirstUsable(item.subCategories)
              if (sub) return sub
            }
          }
          return null
        }
        const first = findFirstUsable(catList)
        if (first) {
          setSelectedCategory(first)
        }
      })
      .catch(err => {
        if (!active) return
        toast.error(err.message || t('explore.noCategories', '该书源未配置有效分类'))
      })
      .finally(() => {
        if (active) setLoadingCategories(false)
      })

    return () => {
      active = false
    }
  }, [selectedSourceId, t])

  // 当选中的分类变化时，重置并加载书籍列表
  useEffect(() => {
    if (!selectedSourceId || !selectedCategory?.url) {
      setBooks([])
      return
    }

    let active = true
    setLoadingBooks(true)
    setBooks([])
    setPage(1)
    setHasMore(true)

    api.exploreBooks(selectedSourceId, selectedCategory.url, 1)
      .then(resultList => {
        if (!active) return
        setBooks(resultList)
        if (resultList.length === 0) {
          setHasMore(false)
        }
      })
      .catch(err => {
        if (!active) return
        toast.error(err.message || t('explore.loadingBooks', '正在加载书籍...'))
      })
      .finally(() => {
        if (active) setLoadingBooks(false)
      })

    return () => {
      active = false
    }
  }, [selectedSourceId, selectedCategory, t])

  // 加载更多
  const handleLoadMore = async () => {
    if (!selectedSourceId || !selectedCategory?.url || loadingMore || !hasMore) return
    const nextPage = page + 1
    setLoadingMore(true)
    try {
      const more = await api.exploreBooks(selectedSourceId, selectedCategory.url, nextPage)
      if (more.length === 0) {
        setHasMore(false)
        toast.info(t('explore.noMoreBooks', '没有更多书籍了'))
      } else {
        setBooks(prev => [...prev, ...more])
        setPage(nextPage)
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      toast.error(msg)
    } finally {
      setLoadingMore(false)
    }
  }

  // 判断是否已在书架
  const isBookInShelf = (book: SearchResult): boolean => {
    return shelfBooks.some(s => s.name === book.name && (!book.author || s.author === book.author))
  }

  // 一键加入书架
  const handleAddToShelf = async (book: SearchResult) => {
    const key = `${book.sourceId}::${book.bookUrl}`
    if (addingShelfMap[key] || isBookInShelf(book)) return

    setAddingShelfMap(prev => ({ ...prev, [key]: true }))
    try {
      // 获取书籍详情以获取权威 tocUrl
      const details = await api.details(book.sourceId, book.bookUrl)
      const saved = await api.addToBookshelf({
        sourceId: book.sourceId,
        bookUrl: book.bookUrl,
        name: details.name || book.name,
        author: details.author || book.author,
        tocUrl: details.tocUrl,
        coverUrl: details.coverUrl || book.coverUrl,
      })
      setShelfBooks(prev => [...prev, saved])
      toast.success(t('shelf.addedToShelfToast', { name: book.name, defaultValue: `《${book.name}》已加入书架` }))
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      toast.error(msg)
    } finally {
      setAddingShelfMap(prev => ({ ...prev, [key]: false }))
    }
  }

  // 立即开读
  const handleOpenReader = async (book: SearchResult) => {
    setOpeningBookUrl(book.bookUrl)
    try {
      const openBook = await loadSourceBook(book)
      const resumeIndex = openBook.progress
        ? Math.min(Math.max(openBook.progress.chapterIndex, 0), Math.max(0, openBook.chapters.length - 1))
        : 0
      onOpen(openBook, resumeIndex)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      toast.error(msg)
    } finally {
      setOpeningBookUrl(null)
    }
  }

  return (
    <main className="explore-page">
      {/* 左侧/顶部：书源切换与发现分类 */}
      <aside className="explore-sidebar">
        <div className="explore-sidebar-section">
          <div className="explore-source-select-header">
            <h3>{t('explore.selectSource', '选择书源')}</h3>
            <span className="explore-source-count-badge">
              {filteredSources.length}
            </span>
          </div>

          <div className="explore-source-filter">
            <input
              type="text"
              value={sourceQuery}
              onChange={e => setSourceQuery(e.target.value)}
              placeholder={t('explore.searchSourcePlaceholder', '筛选书源...')}
              className="explore-source-filter-input"
            />
          </div>

          <div className="explore-source-list" role="list">
            {loadingSources ? (
              <div className="explore-loading-hint">{t('explore.loadingCategories', '正在加载分类...')}</div>
            ) : filteredSources.length === 0 ? (
              <div className="explore-empty-hint">{t('explore.noExploreSources', '暂无支持发现页的书源')}</div>
            ) : (
              filteredSources.map(s => (
                <button
                  type="button"
                  key={s.id}
                  className={`explore-source-item ${s.id === selectedSourceId ? 'active' : ''}`}
                  onClick={() => setSelectedSourceId(s.id)}
                >
                  <div className="explore-source-item-title">{s.name}</div>
                  {s.group && <span className="explore-source-group-tag">{s.group}</span>}
                </button>
              ))
            )}
          </div>
        </div>

        {/* 分类栏目 */}
        <div className="explore-sidebar-section explore-categories-section">
          <div className="explore-category-header">
            <h3>{currentSource?.name ? `${currentSource.name}` : t('explore.title', '发现')}</h3>
          </div>

          {loadingCategories ? (
            <div className="explore-loading-hint">{t('explore.loadingCategories', '正在加载分类...')}</div>
          ) : categories.length === 0 ? (
            <div className="explore-empty-hint">{t('explore.noCategories', '该书源未配置有效分类')}</div>
          ) : (
            <div className="explore-category-tree">
              {categories.map((cat, idx) => (
                <div key={`${cat.title}-${idx}`} className="explore-category-group">
                  {cat.subCategories && cat.subCategories.length > 0 ? (
                    <>
                      <div className="explore-category-group-title">{cat.title}</div>
                      <div className="explore-category-tags">
                        {cat.subCategories.map((sub, subIdx) => (
                          <button
                            type="button"
                            key={`${sub.title}-${subIdx}`}
                            className={`explore-category-tag ${selectedCategory === sub ? 'active' : ''}`}
                            onClick={() => setSelectedCategory(sub)}
                          >
                            {sub.title}
                          </button>
                        ))}
                      </div>
                    </>
                  ) : (
                    <button
                      type="button"
                      className={`explore-category-tag standalone ${selectedCategory === cat ? 'active' : ''}`}
                      onClick={() => setSelectedCategory(cat)}
                    >
                      {cat.title}
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </aside>

      {/* 主视口：书籍卡片网格与分页 */}
      <section className="explore-content">
        <header className="explore-content-header">
          <div className="explore-content-title-area">
            <h2>{selectedCategory?.title || t('explore.title', '发现')}</h2>
            {currentSource && (
              <span className="explore-content-source-name">
                {currentSource.name}
              </span>
            )}
          </div>
          {books.length > 0 && (
            <span className="explore-content-count">
              {t('library.statResultCount', { count: books.length, defaultValue: `结果 ${books.length}` })}
            </span>
          )}
        </header>

        {loadingBooks ? (
          <div className="explore-loading-books">
            <div className="explore-spinner" />
            <p>{t('explore.loadingBooks', '正在加载书籍...')}</p>
          </div>
        ) : books.length === 0 ? (
          <div className="explore-empty-books">
            <Icon name="search" width={48} height={48} className="explore-empty-icon" />
            <p>{t('explore.noBooks', '该分类下暂无书籍')}</p>
          </div>
        ) : (
          <>
            <div className="explore-book-grid">
              {books.map((book, idx) => {
                const inShelf = isBookInShelf(book)
                const key = `${book.sourceId}::${book.bookUrl}`
                const adding = Boolean(addingShelfMap[key])
                const opening = openingBookUrl === book.bookUrl

                return (
                  <article key={`${book.bookUrl}-${idx}`} className="explore-book-card">
                    <div className="explore-book-cover-wrapper">
                      {book.coverUrl ? (
                        <img
                          src={book.coverUrl}
                          alt={book.name}
                          className="explore-book-cover"
                          loading="lazy"
                          onError={e => {
                            // 裂图回退为占位卡片
                            (e.currentTarget as HTMLElement).style.display = 'none'
                          }}
                        />
                      ) : null}
                      <div className="explore-book-cover-placeholder">
                        <span className="explore-cover-text">{book.name.slice(0, 4)}</span>
                      </div>
                    </div>

                    <div className="explore-book-info">
                      <h4 className="explore-book-name" title={book.name}>{book.name}</h4>
                      {book.author && (
                        <div className="explore-book-author">
                          <span>{book.author}</span>
                        </div>
                      )}
                      {book.intro && (
                        <p className="explore-book-intro" title={book.intro}>
                          {book.intro}
                        </p>
                      )}

                      <div className="explore-book-actions">
                        <button
                          type="button"
                          className={`explore-action-btn ${inShelf ? 'in-shelf' : 'add-shelf'}`}
                          onClick={() => handleAddToShelf(book)}
                          disabled={inShelf || adding}
                          title={inShelf ? t('explore.inShelf', '已在书架') : t('explore.addToShelf', '加入书架')}
                        >
                          <Icon name={inShelf ? 'check' : 'plus'} width={14} height={14} />
                          <span>{adding ? t('explore.addingToShelf', '加入中...') : inShelf ? t('explore.inShelf', '已在书架') : t('explore.addToShelf', '加入书架')}</span>
                        </button>

                        <button
                          type="button"
                          className="explore-action-btn primary"
                          onClick={() => handleOpenReader(book)}
                          disabled={opening}
                          title={t('explore.readNow', '立即阅读')}
                        >
                          <Icon name="book" width={14} height={14} />
                          <span>{opening ? t('library.reading', '正在打开...') : t('explore.readNow', '立即阅读')}</span>
                        </button>
                      </div>
                    </div>
                  </article>
                )
              })}
            </div>

            {hasMore ? (
              <div className="explore-load-more-wrap">
                <button
                  type="button"
                  className="explore-load-more-btn"
                  onClick={handleLoadMore}
                  disabled={loadingMore}
                >
                  {loadingMore ? t('explore.loadingMore', '加载中...') : t('explore.loadMore', '加载更多')}
                </button>
              </div>
            ) : (
              <div className="explore-no-more">{t('explore.noMoreBooks', '没有更多书籍了')}</div>
            )}
          </>
        )}
      </section>
    </main>
  )
}
