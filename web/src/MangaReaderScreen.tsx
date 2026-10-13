import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type MangaImageItem } from './api'
import { Icon } from './icons'
import { OpenBook, VirtualChapterList } from './ReaderScreen'

/**
 * 漫画阅读器（方案 E · P0「解析打通」）。
 *
 * P0 定位：**先能看**。图片直连上游地址，服务端只负责解析出有序清单。
 * 已知局限（P1 会解决，见 `方案E-详细设计-全链路与流程图.md`）：
 * - 图片直连上游 ⇒ 防盗链/需要 Referer 或 Cookie 的站点会裂图（这正是 P0 要记录的信息）；
 * - 没有服务端字节缓存 ⇒ 每次打开都回源，无法离线；
 * - 没有宽高占位 ⇒ 首屏会有轻微跳动。
 *
 * 刻意**不做**的事：TTS 朗读（漫画没有可朗读文本）、替换净化（对图片无意义）、
 * 分页/双页/右到左模式（Non-Goals，P3 之后另立项）。
 */
type MangaReaderScreenProps = {
  openBook: OpenBook
  startIndex: number
  onClose: () => void
}

function IconButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return (
    <button type="button" className="reader-icon-button" aria-label={label} title={label} onClick={onClick}>
      <Icon name={icon} />
    </button>
  )
}

export function MangaReaderScreen({ openBook, startIndex, onClose }: MangaReaderScreenProps) {
  const { t } = useTranslation()
  const chapters = openBook.chapters
  const [chapterIndex, setChapterIndex] = useState(() => Math.max(0, Math.min(startIndex, Math.max(0, chapters.length - 1))))
  const [images, setImages] = useState<MangaImageItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [failed, setFailed] = useState<Set<number>>(() => new Set())
  const [activeImage, setActiveImage] = useState(0)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [chapterQuery, setChapterQuery] = useState('')

  const scrollRef = useRef<HTMLElement | null>(null)
  const imageRefs = useRef<Array<HTMLDivElement | null>>([])
  const restoredRef = useRef(false)
  const saveTimerRef = useRef<number | null>(null)

  const chapter = chapters[chapterIndex]
  const total = images.length

  const filteredChapters = useMemo(() => {
    const query = chapterQuery.trim()
    if (!query) return chapters
    return chapters.filter(item => item.title.includes(query))
  }, [chapters, chapterQuery])

  // ---- 进度保存：scrollPosition 语义 = (当前图片序号 + 1) / 总张数 ----
  // 刻意不用像素偏移：图片懒加载会让容器高度持续变化，像素偏移必然漂移。
  const persist = useCallback((index: number, imageIndex: number, imageTotal: number) => {
    const target = chapters[index]
    if (!target) return
    const position = imageTotal > 0 ? Math.min(1, Math.max(0, (imageIndex + 1) / imageTotal)) : 0
    void api.saveProgress(
      openBook.details.sourceId,
      openBook.bookUrl,
      target.url,
      target.index,
      position,
      target.title,
    ).catch(() => undefined)
  }, [chapters, openBook.bookUrl, openBook.details.sourceId])

  // ---- 载入章节清单 ----
  useEffect(() => {
    const target = chapters[chapterIndex]
    if (!target) {
      setImages([])
      setError(t('manga.noChapters', '本书没有可阅读的章节'))
      setLoading(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setFailed(new Set())
    setActiveImage(0)
    restoredRef.current = false
    void api.mangaManifest(openBook.details.sourceId, target.url, openBook.bookUrl, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return
        const list = result?.images ?? []
        setImages(list)
        if (list.length === 0) {
          // 明确报错，绝不静默留白（AGENTS.md：「静默丢数据」比抛错危险）
          setError(t('manga.emptyManifest', '图片规则未提取到任何图片，请检查该书源的正文规则'))
        } else {
          persist(chapterIndex, 0, list.length)
        }
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        setImages([])
        setError(err instanceof Error ? err.message : t('manga.loadFailed', '图片清单加载失败'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [chapterIndex, chapters, openBook.details.sourceId, openBook.bookUrl, persist, t])

  // ---- 恢复进度：定位到上次看到的那一张（仅首次进入本章时） ----
  useEffect(() => {
    if (restoredRef.current || loading || total === 0) return
    const progress = openBook.progress
    restoredRef.current = true
    if (!progress || progress.chapterUrl !== chapter?.url || !progress.scrollPosition) return
    const targetIndex = Math.min(total - 1, Math.max(0, Math.round(progress.scrollPosition * total) - 1))
    if (targetIndex <= 0) return
    const node = imageRefs.current[targetIndex]
    if (node?.scrollIntoView) node.scrollIntoView({ block: 'start' })
  }, [loading, total, openBook.progress, chapter?.url])

  // ---- 滚动时更新「当前第几张」并防抖保存进度 ----
  useEffect(() => {
    const container = scrollRef.current
    if (!container || total === 0) return
    let frame = 0
    const onScroll = () => {
      if (frame) return
      frame = window.requestAnimationFrame(() => {
        frame = 0
        const anchor = container.scrollTop + container.clientHeight * 0.4
        let current = 0
        for (let i = 0; i < imageRefs.current.length; i++) {
          const node = imageRefs.current[i]
          if (!node) continue
          if (node.offsetTop <= anchor) current = i
          else break
        }
        setActiveImage(prev => (prev === current ? prev : current))
        if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current)
        saveTimerRef.current = window.setTimeout(() => persist(chapterIndex, current, total), 800)
      })
    }
    container.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      container.removeEventListener('scroll', onScroll)
      if (frame) window.cancelAnimationFrame(frame)
      if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current)
    }
  }, [chapterIndex, total, persist])

  const changeChapter = useCallback((next: number) => {
    if (next < 0 || next >= chapters.length || next === chapterIndex) return
    // 切章前先落一次进度，避免快速切章丢掉当前位置
    persist(chapterIndex, activeImage, Math.max(1, total))
    setChapterIndex(next)
    scrollRef.current?.scrollTo({ top: 0 })
  }, [chapters.length, chapterIndex, persist, activeImage, total])

  const retryImage = useCallback((index: number) => {
    setFailed(prev => {
      const next = new Set(prev)
      next.delete(index)
      return next
    })
    // 换一个查询串强制浏览器重新请求（服务端 P1 起换成定位地址后不再需要）
    setImages(prev => prev.map(item => (item.index === index ? { ...item, url: `${item.url}${item.url.includes('?') ? '&' : '?'}_retry=${Date.now()}` } : item)))
  }, [])

  return (
    <main className="reader-workspace toolbars-open manga-workspace">
      <header className="reader-header">
        <div className="reader-header-left">
          <IconButton label={t('manga.back', '返回')} icon="arrowLeft" onClick={() => { persist(chapterIndex, activeImage, Math.max(1, total)); onClose() }} />
          <IconButton label={t('reader.catalog', '目录')} icon="list" onClick={() => setDrawerOpen(open => !open)} />
        </div>
        <strong className="reader-header-title" title={openBook.details.name}>
          {openBook.details.name}
          {chapter && <small className="manga-header-chapter">{chapter.title}</small>}
        </strong>
        <div className="reader-header-actions">
          {total > 0 && <span className="manga-counter">{activeImage + 1} / {total}</span>}
        </div>
      </header>

      <aside className={`reader-drawer reader-drawer-left ${drawerOpen ? 'open' : ''}`} aria-label={t('reader.tocDrawer', '目录抽屉')}>
        <header className="drawer-header">
          <div className="drawer-title">
            <Icon name="list" />
            <strong>{t('reader.toc', '目录')}</strong>
            <small>{t('reader.totalChapters', { count: chapters.length, defaultValue: `共 ${chapters.length} 章` })}</small>
          </div>
          <div className="drawer-header-actions">
            <IconButton label={t('reader.closeToc', '关闭目录')} icon="close" onClick={() => setDrawerOpen(false)} />
          </div>
        </header>
        <div className="chapter-search">
          <Icon name="search" />
          <input value={chapterQuery} onChange={event => setChapterQuery(event.target.value)} placeholder={t('reader.searchChapter', '搜索章节')} />
        </div>
        <VirtualChapterList
          chapters={filteredChapters}
          activeChapterIndex={chapterIndex}
          onSelect={index => { changeChapter(index); setDrawerOpen(false) }}
          itemHeight={40}
          overscan={8}
          autoScrollKey={drawerOpen ? 1 : 0}
        />
      </aside>

      {drawerOpen && <div className="reader-drawer-backdrop" onClick={() => setDrawerOpen(false)} aria-hidden="true" />}

      <section className="manga-scroll" ref={scrollRef}>
        {loading && (
          <div className="reader-loading-container" aria-label={t('manga.loading', '正在加载图片清单')}>
            <div className="reader-loading-badge"><span className="reader-loading-spinner-ring" />{t('manga.loading', '正在加载图片清单')}</div>
          </div>
        )}
        {!loading && error && (
          <div className="manga-error-box">
            <p className="reader-error">{error}</p>
            <div className="manga-error-actions">
              <button type="button" className="subtle-button" onClick={() => setChapterIndex(current => current)}>{t('manga.retry', '重试')}</button>
              <button type="button" className="subtle-button" onClick={() => changeChapter(chapterIndex + 1)} disabled={chapterIndex >= chapters.length - 1}>{t('manga.nextChapter', '下一章')}</button>
            </div>
          </div>
        )}
        {!loading && !error && images.map(item => (
          <div
            key={`${item.index}-${item.url}`}
            className="manga-image-frame"
            ref={node => { imageRefs.current[item.index] = node }}
          >
            {failed.has(item.index) ? (
              <button type="button" className="manga-image-failed" onClick={() => retryImage(item.index)}>
                <Icon name="image" />
                <span>{t('manga.imageFailed', { index: item.index + 1, defaultValue: `第 ${item.index + 1} 张加载失败，点击重试` })}</span>
                <small>{t('manga.imageFailedHint', '若反复失败，该图床很可能启用了防盗链（需要 Referer / Cookie），P1 服务端缓存会解决')}</small>
              </button>
            ) : (
              <img
                src={item.url}
                alt={t('manga.imageAlt', { index: item.index + 1, defaultValue: `第 ${item.index + 1} 张` })}
                loading={item.index < 3 ? 'eager' : 'lazy'}
                decoding="async"
                referrerPolicy="no-referrer"
                onError={() => setFailed(prev => new Set(prev).add(item.index))}
              />
            )}
          </div>
        ))}
        {!loading && !error && total > 0 && (
          <div className="manga-end-of-chapter">
            <button type="button" className="subtle-button" onClick={() => changeChapter(chapterIndex + 1)} disabled={chapterIndex >= chapters.length - 1}>
              {t('manga.nextChapter', '下一章')}
            </button>
          </div>
        )}
      </section>

      <footer className="reader-floating-footer">
        <div className="reader-chapter-control-bar">
          <button type="button" className="reader-chapter-btn prev" disabled={chapterIndex <= 0} onClick={() => changeChapter(chapterIndex - 1)} aria-label={t('manga.prevChapter', '上一章')}>
            <Icon name="arrowLeft" />
          </button>
          <div className="reader-chapter-slider-wrap">
            <div className="reader-chapter-info">
              <span className="reader-chapter-title">{chapter?.title ?? ''}</span>
              <small>{chapterIndex + 1} / {chapters.length}</small>
            </div>
            <input
              type="range"
              className="reader-chapter-slider"
              min={0}
              max={Math.max(0, chapters.length - 1)}
              value={chapterIndex}
              onChange={event => changeChapter(Number(event.target.value))}
              aria-label={t('manga.chapterSlider', '章节进度')}
            />
          </div>
          <button type="button" className="reader-chapter-btn next" disabled={chapterIndex >= chapters.length - 1} onClick={() => changeChapter(chapterIndex + 1)} aria-label={t('manga.nextChapter', '下一章')}>
            <Icon name="arrowRight" />
          </button>
        </div>
      </footer>
    </main>
  )
}
