import React, { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type MangaCacheBookStat, type MangaCacheStats } from './api'
import { Icon } from './icons'
import {
  clearAllOffline,
  clearOfflineBook,
  getOfflineStorageStats,
  isLruEvictEnabled,
  OfflineBookStat,
  OfflineStorageStats,
  setLruEvictEnabled,
} from './offlineStorage'
import { toast } from './Toast'

interface OfflineCacheModalProps {
  onClose: () => void
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 KB'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

export function OfflineCacheModal({ onClose }: OfflineCacheModalProps) {
  const { t } = useTranslation()
  const [stats, setStats] = useState<OfflineStorageStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [lruEnabled, setLruEnabled] = useState(isLruEvictEnabled)
  const [clearing, setClearing] = useState(false)
  /** 服务端图片缓存（方案 E）：与上面的"本机 IndexedDB"**分开统计、分开显示** —— 否则用户无法判断空间被谁吃了。 */
  const [serverStats, setServerStats] = useState<MangaCacheStats | null>(null)
  const [serverBusy, setServerBusy] = useState(false)

  const loadStats = async () => {
    try {
      const data = await getOfflineStorageStats()
      setStats(data)
    } catch {
      toast.error(t('source.loadStorageStatsFailed', '读取离线存储状态失败'))
    } finally {
      setLoading(false)
    }
  }

  const loadServerStats = async () => {
    try {
      setServerStats(await api.imageCacheStats())
    } catch {
      // 服务端缓存统计读不到（未登录/离线）不该让整个面板失败：本机部分仍然可用
      setServerStats(null)
    }
  }

  useEffect(() => {
    void loadStats()
    void loadServerStats()
  }, [])

  const handlePinBook = async (book: MangaCacheBookStat) => {
    try {
      await api.pinMangaBook(book.sourceId, book.bookUrl, !book.pinned)
      toast.success(
        book.pinned
          ? t('source.mangaUnpinned', '已取消「整本保留」，可被自动清理')
          : t('source.mangaPinned', '已设为「整本保留」，不会被自动清理'),
      )
      await loadServerStats()
    } catch {
      toast.error(t('source.mangaPinFailed', '设置失败'))
    }
  }

  const handleClearServerBook = async (book: MangaCacheBookStat) => {
    const name = book.name || book.bookUrl
    if (!window.confirm(t('source.confirmClearServerBook', { name, defaultValue: `确定清理《${name}》的服务端图片缓存吗？清理后需重新联网抓取。` }))) return
    try {
      await api.clearImageCache(book.sourceId, book.bookUrl)
      toast.success(t('source.clearedServerBook', '已清理该书的服务端图片缓存'))
      await loadServerStats()
    } catch {
      toast.error(t('source.clearFailed', '清理失败'))
    }
  }

  const handleClearAllServer = async () => {
    if (!window.confirm(t('source.confirmClearAllServer', '确定清空服务端全部图片缓存吗？所有漫画都需要重新联网抓取。'))) return
    setServerBusy(true)
    try {
      await api.clearImageCache()
      toast.success(t('source.clearedAllServer', '已清空服务端图片缓存'))
      await loadServerStats()
    } catch {
      toast.error(t('source.clearAllFailed', '清空失败'))
    } finally {
      setServerBusy(false)
    }
  }

  const handleToggleLru = (e: React.ChangeEvent<HTMLInputElement>) => {
    const checked = e.target.checked
    setLruEnabled(checked)
    setLruEvictEnabled(checked)
    toast.info(checked ? t('source.autoLruEnabledToast', '已开启存储不足时自动清理旧书') : t('source.autoLruDisabledToast', '已关闭自动清理'))
  }

  const handleClearBook = async (book: OfflineBookStat) => {
    if (!window.confirm(t('source.confirmClearBookCache', { name: book.name || book.bookUrl, defaultValue: `确定要清理《${book.name || book.bookUrl}》的本地离线缓存吗？` }))) return
    try {
      const count = await clearOfflineBook(book.sourceId, book.bookUrl)
      toast.success(t('source.clearedChaptersSuccess', { count, defaultValue: `已清理 ${count} 个本地离线章节` }))
      await loadStats()
    } catch {
      toast.error(t('source.clearFailed', '清理失败'))
    }
  }

  const handleClearAll = async () => {
    if (!window.confirm(t('source.confirmClearAllOffline', '确定要清空本设备上的所有书籍离线缓存吗？清空后断网将无法脱机阅读。'))) return
    setClearing(true)
    try {
      await clearAllOffline()
      toast.success(t('source.clearedAllOfflineSuccess', '已清空全部本地离线缓存'))
      await loadStats()
    } catch {
      toast.error(t('source.clearAllFailed', '清空失败'))
    } finally {
      setClearing(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="source-login-modal offline-cache-modal"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('source.offlineCacheModalAria', '离线缓存与存储管理')}
      >
        <header className="source-login-header">
          <div className="source-login-title">
            <span className="source-login-icon"><Icon name="book" /></span>
            <div className="source-login-heading">
              <h2>{t('source.offlineCacheTitle', '本地离线缓存管理')}</h2>
              <small>{t('source.offlineCacheSubtitle', 'PWA 客户端本地存储 (IndexedDB)')}</small>
            </div>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        <div className="source-login-body">
          {loading ? (
            <div className="source-login-loading">
              <span className="source-login-spinner" />
              <span>{t('source.offlineCalculating', '正在统计设备存储占用...')}</span>
            </div>
          ) : (
            <div className="offline-cache-content">
              <div className="cache-overview-card">
                <div className="overview-item">
                  <span className="overview-label">{t('source.offlineBooks', '已离线书籍')}</span>
                  <strong className="overview-value">{stats?.books.length || 0} {t('library.unitsBook', '本')}</strong>
                </div>
                <div className="overview-divider" />
                <div className="overview-item">
                  <span className="overview-label">{t('source.offlineChapters', '总离线章节')}</span>
                  <strong className="overview-value">{stats?.totalChapters || 0} {t('reader.cacheCustomUnit', '章')}</strong>
                </div>
                <div className="overview-divider" />
                <div className="overview-item">
                  <span className="overview-label">{t('source.offlineSpace', '本地空间占用')}</span>
                  <strong className="overview-value text-teal">{formatBytes(stats?.totalBytes || 0)}</strong>
                </div>
              </div>

              <div className="offline-cache-setting-row">
                <label className="lru-toggle-label">
                  <input
                    type="checkbox"
                    checked={lruEnabled}
                    onChange={handleToggleLru}
                  />
                  <div>
                    <strong>{t('source.autoLruClean', '空间不足时自动 LRU 清理')}</strong>
                    <small>{t('source.autoLruCleanDesc', '当手机存储配额耗尽时，自动淘汰最早已读完书籍的本地缓存以腾出空间')}</small>
                  </div>
                </label>
              </div>

              <div className="offline-books-section">
                <div className="offline-books-header">
                  <span>{t('source.offlineListTitle', '已离线书籍列表')}</span>
                  <small>{t('source.offlineListHint', '断网时可脱机阅读')}</small>
                </div>
                {(!stats?.books || stats.books.length === 0) ? (
                  <p className="empty-notice">{t('source.offlineEmpty', '当前设备暂无本地离线书籍。在阅读器设置中选择“缓存并下载到本设备”即可脱机阅读。')}</p>
                ) : (
                  <div className="offline-books-list">
                    {stats.books.map(book => (
                      <div key={`${book.sourceId}::${book.bookUrl}`} className="offline-book-row">
                        <div className="offline-book-meta">
                          <strong>{book.name || book.bookUrl}</strong>
                          <small>{book.chapterCount} {t('reader.cacheCustomUnit', '章')} · {formatBytes(book.totalBytes)} · {book.sourceId}</small>
                        </div>
                        <button
                          type="button"
                          className="subtle-button danger-btn"
                          onClick={() => void handleClearBook(book)}
                        >
                          {t('common.delete', '清除')}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/*
                服务端图片缓存（方案 E · P1/P2）。
                与上面的本机 IndexedDB 缓存**必须分开显示**：两者都可能吃掉空间，
                混成一个数字用户根本判断不出该清哪一个。
              */}
              <div className="offline-books-section server-cache-section">
                <div className="offline-books-header">
                  <span>{t('source.serverImageCacheTitle', '服务端图片缓存（漫画）')}</span>
                  <small>{t('source.serverImageCacheHint', '与本机缓存分开统计')}</small>
                </div>
                {!serverStats ? (
                  <p className="empty-notice">{t('source.serverImageCacheUnavailable', '暂无法读取服务端缓存状态（未登录或服务不可达）。')}</p>
                ) : (
                  <>
                    <div className="cache-overview-card">
                      <div className="overview-item">
                        <span className="overview-label">{t('source.serverImageBytes', '服务端占用')}</span>
                        <strong className="overview-value text-teal">
                          {formatBytes(serverStats.diskBytes)}
                          <small className="overview-quota"> / {formatBytes(serverStats.quotaBytes)}</small>
                        </strong>
                      </div>
                      <div className="overview-divider" />
                      <div className="overview-item">
                        <span className="overview-label">{t('source.serverImageCount', '图片张数')}</span>
                        <strong className="overview-value">{serverStats.imageCount}</strong>
                      </div>
                      <div className="overview-divider" />
                      <div className="overview-item">
                        <span className="overview-label">{t('source.serverImageBooks', '涉及书籍')}</span>
                        <strong className="overview-value">{serverStats.books.length} {t('library.unitsBook', '本')}</strong>
                      </div>
                    </div>
                    {serverStats.diskBytes > serverStats.totalBytes * 1.05 && (
                      <p className="cache-drift-warning">
                        {t('source.serverImageDrift', '磁盘占用与索引不一致（可能存在尚未回收的残片），执行一次清理即可。')}
                      </p>
                    )}
                    {serverStats.books.length === 0 ? (
                      <p className="empty-notice">{t('source.serverImageEmpty', '服务端暂无漫画图片缓存。在漫画阅读器里打开章节会自动缓存。')}</p>
                    ) : (
                      <div className="offline-books-list">
                        {serverStats.books.map(book => (
                          <div key={`${book.sourceId}::${book.bookUrl}`} className="offline-book-row">
                            <div className="offline-book-meta">
                              <strong>
                                {book.name || book.bookUrl}
                                {book.pinned && <span className="server-cache-pin-badge">{t('source.serverImagePinned', '整本保留')}</span>}
                              </strong>
                              <small>
                                {book.imageCount} {t('source.serverImageUnit', '张')} · {formatBytes(book.totalBytes)}
                                {' · '}{t('source.serverImageReadyChapters', { count: book.readyChapterCount, defaultValue: `已可离线 ${book.readyChapterCount} 章` })}
                              </small>
                            </div>
                            <div className="offline-book-actions">
                              <button type="button" className="subtle-button" onClick={() => void handlePinBook(book)}>
                                {book.pinned ? t('source.serverImageUnpin', '取消保留') : t('source.serverImagePin', '整本保留')}
                              </button>
                              <button type="button" className="subtle-button danger-btn" onClick={() => void handleClearServerBook(book)}>
                                {t('common.delete', '清除')}
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                    <div className="server-cache-footer">
                      <button
                        type="button"
                        className="danger-button"
                        disabled={serverBusy || serverStats.books.length === 0}
                        onClick={() => void handleClearAllServer()}
                      >
                        {serverBusy ? t('source.clearing', '清空中...') : t('source.clearAllServerImageCache', '清空服务端图片缓存')}
                      </button>
                    </div>
                  </>
                )}
              </div>
            </div>
          )}
        </div>

        <footer className="source-login-footer">
          <button
            type="button"
            className="danger-button"
            disabled={clearing || !stats?.books.length}
            onClick={() => void handleClearAll()}
          >
            {clearing ? t('source.clearing', '清空中...') : t('source.clearAllOffline', '清空全部本地缓存')}
          </button>
          <button type="button" className="primary-button" onClick={onClose}>
            {t('common.done', '完成')}
          </button>
        </footer>
      </div>
    </div>
  )
}
