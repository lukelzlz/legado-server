import React, { useEffect, useState } from 'react'
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
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

export function OfflineCacheModal({ onClose }: OfflineCacheModalProps) {
  const [stats, setStats] = useState<OfflineStorageStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [lruEnabled, setLruEnabled] = useState(isLruEvictEnabled)
  const [clearing, setClearing] = useState(false)

  const loadStats = async () => {
    try {
      const data = await getOfflineStorageStats()
      setStats(data)
    } catch {
      toast.error('读取离线存储状态失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadStats()
  }, [])

  const handleToggleLru = (e: React.ChangeEvent<HTMLInputElement>) => {
    const checked = e.target.checked
    setLruEnabled(checked)
    setLruEvictEnabled(checked)
    toast.info(checked ? '已开启存储不足时自动清理旧书' : '已关闭自动清理')
  }

  const handleClearBook = async (book: OfflineBookStat) => {
    if (!window.confirm(`确定要清理《${book.name || book.bookUrl}》的本地离线缓存吗？`)) return
    try {
      const count = await clearOfflineBook(book.sourceId, book.bookUrl)
      toast.success(`已清理 ${count} 个本地离线章节`)
      await loadStats()
    } catch {
      toast.error('清理失败')
    }
  }

  const handleClearAll = async () => {
    if (!window.confirm('确定要清空本设备上的所有书籍离线缓存吗？清空后断网将无法脱机阅读。')) return
    setClearing(true)
    try {
      await clearAllOffline()
      toast.success('已清空全部本地离线缓存')
      await loadStats()
    } catch {
      toast.error('清空失败')
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
        aria-label="离线缓存与存储管理"
      >
        <header className="source-login-header">
          <div className="source-login-title">
            <span className="source-login-icon"><Icon name="book" /></span>
            <div className="source-login-heading">
              <h2>本地离线缓存管理</h2>
              <small>PWA 客户端本地存储 (IndexedDB)</small>
            </div>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>

        <div className="source-login-body">
          {loading ? (
            <div className="source-login-loading">
              <span className="source-login-spinner" />
              <span>正在统计设备存储占用...</span>
            </div>
          ) : (
            <div className="offline-cache-content">
              <div className="cache-overview-card">
                <div className="overview-item">
                  <span className="overview-label">已离线书籍</span>
                  <strong className="overview-value">{stats?.books.length || 0} 本</strong>
                </div>
                <div className="overview-divider" />
                <div className="overview-item">
                  <span className="overview-label">总离线章节</span>
                  <strong className="overview-value">{stats?.totalChapters || 0} 章</strong>
                </div>
                <div className="overview-divider" />
                <div className="overview-item">
                  <span className="overview-label">本地空间占用</span>
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
                    <strong>空间不足时自动 LRU 清理</strong>
                    <small>当手机存储配额耗尽时，自动淘汰最早已读完书籍的本地缓存以腾出空间</small>
                  </div>
                </label>
              </div>

              <div className="offline-books-section">
                <div className="offline-books-header">
                  <span>已离线书籍列表</span>
                  <small>断网时可脱机阅读</small>
                </div>
                {(!stats?.books || stats.books.length === 0) ? (
                  <p className="empty-notice">当前设备暂无本地离线书籍。在阅读器设置中选择“缓存并下载到本设备”即可脱机阅读。</p>
                ) : (
                  <div className="offline-books-list">
                    {stats.books.map(book => (
                      <div key={`${book.sourceId}::${book.bookUrl}`} className="offline-book-row">
                        <div className="offline-book-meta">
                          <strong>{book.name || book.bookUrl}</strong>
                          <small>{book.chapterCount} 章 · {formatBytes(book.totalBytes)} · {book.sourceId}</small>
                        </div>
                        <button
                          type="button"
                          className="subtle-button danger-btn"
                          onClick={() => void handleClearBook(book)}
                        >
                          清除
                        </button>
                      </div>
                    ))}
                  </div>
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
            {clearing ? '清空中...' : '清空全部本地缓存'}
          </button>
          <button type="button" className="primary-button" onClick={onClose}>
            完成
          </button>
        </footer>
      </div>
    </div>
  )
}
