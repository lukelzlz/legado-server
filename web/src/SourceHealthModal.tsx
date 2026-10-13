import { useState, useEffect, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { api, SourceHealthCheckItem, SourceHealthCheckResponse, SourceSummary } from './api'
import { Icon } from './icons'

interface SourceHealthModalProps {
  sources: SourceSummary[]
  onClose: () => void
  onSourcesChange: () => void
  onToast: (message: string, type?: 'info' | 'success' | 'error') => void
}

type TabFilter = 'all' | 'valid' | 'slow' | 'failed'

export function SourceHealthModal({
  sources,
  onClose,
  onSourcesChange,
  onToast,
}: SourceHealthModalProps) {
  const { t } = useTranslation()
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState<{ total: number; checked: number }>({ total: sources.length, checked: 0 })
  const [results, setResults] = useState<SourceHealthCheckItem[]>([])
  const [activeTab, setActiveTab] = useState<TabFilter>('all')
  const [filterQuery, setFilterQuery] = useState('')
  const [durationMs, setDurationMs] = useState(0)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const timerRef = useRef<number | null>(null)
  const startTimeRef = useRef<number>(0)

  const stats = useMemo(() => {
    let valid = 0
    let slow = 0
    let failed = 0
    for (const r of results) {
      if (r.statusCategory === 'valid') valid++
      else if (r.statusCategory === 'slow') slow++
      else failed++
    }
    return {
      total: results.length,
      valid,
      slow,
      failed,
    }
  }, [results])

  const filteredResults = useMemo(() => {
    return results.filter(item => {
      if (activeTab === 'valid' && item.statusCategory !== 'valid') return false
      if (activeTab === 'slow' && item.statusCategory !== 'slow') return false
      if (activeTab === 'failed' && item.statusCategory !== 'failed' && item.statusCategory !== 'blocked') return false
      if (filterQuery) {
        const q = filterQuery.toLowerCase()
        return item.name.toLowerCase().includes(q) || item.id.toLowerCase().includes(q)
      }
      return true
    })
  }, [results, activeTab, filterQuery])

  const startCheck = async (targetIds?: string[]) => {
    const idsToCheck = targetIds ?? sources.map(s => s.id)
    if (idsToCheck.length === 0) {
      onToast(t('source.noSourcesToCheck', '没有可检测的书源'), 'info')
      return
    }
    setRunning(true)
    setProgress({ total: idsToCheck.length, checked: 0 })
    startTimeRef.current = Date.now()
    if (timerRef.current) clearInterval(timerRef.current)
    timerRef.current = window.setInterval(() => {
      setDurationMs(Date.now() - startTimeRef.current)
    }, 100)

    try {
      const resp = await api.healthCheckSources(idsToCheck, 5000)
      if (targetIds && targetIds.length < sources.length) {
        // Merge partial results
        setResults(prev => {
          const map = new Map(prev.map(item => [item.id, item]))
          for (const item of resp.results) {
            map.set(item.id, item)
          }
          return Array.from(map.values())
        })
      } else {
        setResults(resp.results)
      }
      setDurationMs(resp.durationMs)
      setProgress({ total: idsToCheck.length, checked: idsToCheck.length })
      onToast(
        t('source.healthCheckComplete', {
          valid: resp.successCount,
          slow: resp.slowCount,
          failed: resp.failedCount,
          defaultValue: `体检完成：${resp.successCount} 个正常，${resp.slowCount} 个迟缓，${resp.failedCount} 个失效`,
        }),
        resp.failedCount > 0 ? 'info' : 'success'
      )
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('source.healthCheckFailed', '体检请求失败'), 'error')
    } finally {
      setRunning(false)
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }

  useEffect(() => {
    // Auto-start on modal mount
    void startCheck()
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [])

  const handleBatchDisableFailed = async () => {
    const failedItems = results.filter(r => r.statusCategory === 'failed' || r.statusCategory === 'blocked')
    if (failedItems.length === 0) {
      onToast(t('source.noFailedSourcesToDisable', '当前无失效书源需要停用'), 'info')
      return
    }
    setBusyAction('disable_failed')
    try {
      const resp = await api.batchSources('disable', failedItems.map(i => i.id))
      onToast(t('source.disabledFailedSuccess', { count: resp.affected, defaultValue: `已停用 ${resp.affected} 个失效书源` }), 'success')
      onSourcesChange()
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('source.disableSourcesFailed', '停用失败'), 'error')
    } finally {
      setBusyAction(null)
    }
  }

  const handleBatchDeleteFailed = async () => {
    const failedItems = results.filter(r => r.statusCategory === 'failed' || r.statusCategory === 'blocked')
    if (failedItems.length === 0) {
      onToast(t('source.noFailedSourcesToDelete', '当前无失效书源需要删除'), 'info')
      return
    }
    const separator = t('common.separator', '、')
    const names = failedItems.slice(0, 3).map(i => i.name).join(separator) + (failedItems.length > 3 ? t('source.etcCount', ' 等共 {{count}} 个', { count: failedItems.length }) : '')
    if (!confirm(t('source.deleteFailedConfirm', {
      count: failedItems.length,
      names,
      defaultValue: `确定要彻底删除以下 ${failedItems.length} 个失效书源吗？\n（${names}）\n删除后不可恢复！`,
    }))) {
      return
    }
    setBusyAction('delete_failed')
    try {
      const resp = await api.batchSources('delete', failedItems.map(i => i.id))
      onToast(t('source.deletedFailedSuccess', { count: resp.affected, defaultValue: `已删除 ${resp.affected} 个失效书源` }), 'success')
      setResults(prev => prev.filter(r => r.statusCategory !== 'failed' && r.statusCategory !== 'blocked'))
      onSourcesChange()
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('source.deleteSourcesFailed', '删除失败'), 'error')
    } finally {
      setBusyAction(null)
    }
  }

  const handleRetryFailed = () => {
    const failedIds = results
      .filter(r => r.statusCategory === 'failed' || r.statusCategory === 'blocked')
      .map(r => r.id)
    if (failedIds.length === 0) {
      onToast(t('source.noFailedSourcesToRetry', '没有失效书源需要重试'), 'info')
      return
    }
    void startCheck(failedIds)
  }

  const handleRetrySingle = (id: string) => {
    void startCheck([id])
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card source-health-modal" onClick={e => e.stopPropagation()}>
        <header className="modal-header">
          <div className="health-modal-title">
            <Icon name="activity" />
            <div>
              <h2>{t('source.healthCheckTitle', '书源连通性与健康体检')}</h2>
              <small>
                {running
                  ? t('source.probingHostConnectivity', { time: (durationMs / 1000).toFixed(1), defaultValue: `正在并发探测书源 Host 与入口连通性 (${(durationMs / 1000).toFixed(1)}s)...` })
                  : t('source.healthSummaryDone', { total: stats.total, time: (durationMs / 1000).toFixed(1), defaultValue: `共检测 ${stats.total} 个书源 · 用时 ${(durationMs / 1000).toFixed(1)}s` })}
              </small>
            </div>
          </div>
          <button type="button" className="close-button" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        {/* Progress bar */}
        <div className="health-progress-wrap">
          <div
            className={`health-progress-bar ${running ? 'running' : ''}`}
            style={{ width: running ? '100%' : '100%' }}
          />
        </div>

        {/* Stats and Filter Tabs */}
        <div className="health-stats-tabs">
          <div className="health-tabs">
            <button
              type="button"
              className={`health-tab-btn ${activeTab === 'all' ? 'active' : ''}`}
              onClick={() => setActiveTab('all')}
            >
              {t('source.allCount', { count: stats.total, defaultValue: `全部 (${stats.total})` })}
            </button>
            <button
              type="button"
              className={`health-tab-btn tab-valid ${activeTab === 'valid' ? 'active' : ''}`}
              onClick={() => setActiveTab('valid')}
            >
              {t('source.statusValid', { count: stats.valid, defaultValue: `🟢 正常 (${stats.valid})` })}
            </button>
            <button
              type="button"
              className={`health-tab-btn tab-slow ${activeTab === 'slow' ? 'active' : ''}`}
              onClick={() => setActiveTab('slow')}
            >
              {t('source.statusSlow', { count: stats.slow, defaultValue: `🟡 迟缓 (${stats.slow})` })}
            </button>
            <button
              type="button"
              className={`health-tab-btn tab-failed ${activeTab === 'failed' ? 'active' : ''}`}
              onClick={() => setActiveTab('failed')}
            >
              {t('source.statusFailed', { count: stats.failed, defaultValue: `🔴 失效 (${stats.failed})` })}
            </button>
          </div>
          <div className="health-search-input">
            <Icon name="search" />
            <input
              type="text"
              placeholder={t('source.searchHealthResults', '搜索检测结果...')}
              value={filterQuery}
              onChange={e => setFilterQuery(e.target.value)}
            />
          </div>
        </div>

        {/* Action Quick Bar */}
        <div className="health-actions-bar">
          <div className="health-actions-left">
            <button
              type="button"
              className="subtle-button"
              onClick={() => void startCheck()}
              disabled={running || busyAction !== null}
            >
              <Icon name="refresh" className={running ? 'spin' : ''} />
              <span>{running ? t('source.checking', '体检中...') : t('source.recheckAll', '重新全量体检')}</span>
            </button>
            {stats.failed > 0 && !running && (
              <button
                type="button"
                className="subtle-button"
                onClick={handleRetryFailed}
                disabled={busyAction !== null}
              >
                <span>{t('source.retryFailed', { count: stats.failed, defaultValue: `重试失效项 (${stats.failed})` })}</span>
              </button>
            )}
          </div>
          {stats.failed > 0 && (
            <div className="health-actions-right">
              <button
                type="button"
                className="secondary-button"
                onClick={() => void handleBatchDisableFailed()}
                disabled={running || busyAction !== null}
              >
                <span>{t('source.disableFailed', { count: stats.failed, defaultValue: `一键停用失效源 (${stats.failed})` })}</span>
              </button>
              <button
                type="button"
                className="danger-button"
                onClick={() => void handleBatchDeleteFailed()}
                disabled={running || busyAction !== null}
              >
                <span>{t('source.deleteFailed', { count: stats.failed, defaultValue: `一键删除失效源 (${stats.failed})` })}</span>
              </button>
            </div>
          )}
        </div>

        {/* Results List */}
        <div className="health-results-list">
          {filteredResults.length === 0 ? (
            <div className="health-empty-state">
              <p>{running ? t('source.probingInProgress', '正在努力探测中...') : t('source.noMatchingHealthResults', '无符合筛选条件的结果')}</p>
            </div>
          ) : (
            filteredResults.map(item => {
              const isOk = item.ok
              const isSlow = item.statusCategory === 'slow'
              const isBlocked = item.statusCategory === 'blocked'
              const isFailed = item.statusCategory === 'failed'

              let badgeClass = 'badge-valid'
              let badgeText = t('source.badgeValid', '连通正常')
              if (isSlow) {
                badgeClass = 'badge-slow'
                badgeText = t('source.badgeSlow', '响应较慢')
              } else if (isBlocked) {
                badgeClass = 'badge-blocked'
                badgeText = t('source.badgeRestricted', '访问受限')
              } else if (isFailed) {
                badgeClass = 'badge-failed'
                badgeText = t('source.badgeFailed', '连接失效')
              }

              return (
                <article key={item.id} className={`health-item-card ${item.statusCategory}`}>
                  <div className="health-item-main">
                    <div className="health-item-top">
                      <strong className="health-item-name">{item.name}</strong>
                      <span className={`health-badge ${badgeClass}`}>{badgeText}</span>
                      {item.latencyMs > 0 && (
                        <span className="health-latency">{item.latencyMs}ms</span>
                      )}
                      {item.statusCode > 0 && (
                        <span className="health-code">HTTP {item.statusCode}</span>
                      )}
                    </div>
                    <div className="health-item-url" title={item.id}>
                      {item.id}
                    </div>
                    {item.error && <p className="health-item-error">{item.error}</p>}
                  </div>
                  <div className="health-item-actions">
                    <button
                      type="button"
                      className="subtle-button icon-only"
                      title={t('source.singleRetest', '单独重测')}
                      onClick={() => handleRetrySingle(item.id)}
                      disabled={running}
                    >
                      <Icon name="refresh" />
                    </button>
                  </div>
                </article>
              )
            })
          )}
        </div>
      </div>
    </div>
  )
}
