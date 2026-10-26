import React, { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, SourceGroupSummary, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'

export interface SearchScopePickerModalProps {
  groups: SourceGroupSummary[]
  selectedGroups: string[]
  selectedSourceIds: string[]
  onApply: (groups: string[], sourceIds: string[]) => void
  onClose: () => void
}

/**
 * 搜索范围多选弹窗。
 *
 * 分组与单个书源是两个独立的集合：书源候选只按输入关键词查询，不会被已选分组
 * 限制，这样用户才能选择“分组 + 分组外的额外书源”。服务端不截断匹配结果。
 */
export function SearchScopePickerModal({
  groups,
  selectedGroups,
  selectedSourceIds,
  onApply,
  onClose,
}: SearchScopePickerModalProps) {
  const { t } = useTranslation()
  const [draftGroups, setDraftGroups] = useState<string[]>(selectedGroups)
  const [draftSourceIds, setDraftSourceIds] = useState<string[]>(selectedSourceIds)
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<SourceSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    const trimmed = query.trim()
    if (!trimmed) {
      setItems([])
      setLoading(false)
      setError('')
      return
    }

    const controller = new AbortController()
    setLoading(true)
    setError('')
    void api.sourcePicker(trimmed, [], controller.signal)
      .then(result => setItems(result.items))
      .catch(err => {
        if (controller.signal.aborted) return
        setItems([])
        setError(err instanceof Error ? err.message : t('search.scopePickerLoadFailed', '书源查询失败'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [query, retry, t])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  const selectedSet = useMemo(() => new Set(draftSourceIds), [draftSourceIds])
  const groupOptions = useMemo(() => [
    ...groups,
    { name: UNGROUPED_SOURCE_GROUP, sourceCount: 0, enabledCount: 0 },
  ], [groups])

  const toggleGroup = (name: string) => {
    setDraftGroups(previous => previous.includes(name)
      ? previous.filter(item => item !== name)
      : [...previous, name])
  }

  const toggleSource = (id: string) => {
    setDraftSourceIds(previous => previous.includes(id)
      ? previous.filter(item => item !== id)
      : [...previous, id])
  }

  const clear = () => {
    setDraftGroups([])
    setDraftSourceIds([])
  }

  return (
    <div className="modal-backdrop search-scope-picker-backdrop" onClick={onClose}>
      <div
        className="search-scope-picker"
        role="dialog"
        aria-modal="true"
        aria-label={t('search.scopePickerTitle', '选择搜索范围')}
        onClick={event => event.stopPropagation()}
      >
        <header className="search-scope-picker-header">
          <div>
            <span className="section-kicker">{t('search.scopeLabel', '搜索范围')}</span>
            <h2>{t('search.scopePickerTitle', '选择搜索范围')}</h2>
            <p>{t('search.scopePickerDesc', '可同时选择多个分组和单个书源，搜索范围按并集计算')}</p>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>×</button>
        </header>

        <div className="search-scope-picker-body">
          <section className="search-scope-picker-section">
            <div className="search-scope-picker-section-title">
              <strong>{t('search.scopePickerGroups', '选择分组')}</strong>
              <small>{t('search.scopePickerGroupsHint', '分组内的已启用书源都会参与搜索')}</small>
            </div>
            <div className="search-scope-picker-groups">
              {groupOptions.map(group => {
                const selected = draftGroups.includes(group.name)
                const isUngrouped = group.name === UNGROUPED_SOURCE_GROUP
                return (
                  <label key={group.name} className={`search-scope-picker-group ${selected ? 'checked' : ''}`}>
                    <input type="checkbox" checked={selected} onChange={() => toggleGroup(group.name)} />
                    <span>{isUngrouped ? t('search.scopeUngrouped', '未分组') : group.name}</span>
                    {!isUngrouped && <small>{group.enabledCount}</small>}
                  </label>
                )
              })}
            </div>
          </section>

          <section className="search-scope-picker-section search-scope-picker-sources">
            <div className="search-scope-picker-section-title">
              <strong>{t('search.scopePickerSources', '选择单个书源')}</strong>
              <small>{t('search.scopePickerSourcesHint', '可与分组混合选择')}</small>
            </div>
            <label className="search-scope-picker-search">
              <span aria-hidden="true">⌕</span>
              <input
                type="search"
                autoFocus
                value={query}
                onChange={event => setQuery(event.target.value)}
                placeholder={t('search.scopePickerSearchPlaceholder', '输入书源名称、地址或分组名称')}
                aria-label={t('search.scopePickerSearchPlaceholder', '输入书源名称、地址或分组名称')}
              />
            </label>
            <div className="search-scope-picker-source-list" aria-live="polite">
              {loading && <p className="search-scope-picker-empty">{t('common.loading', '加载中...')}</p>}
              {!loading && error && (
                <div className="search-scope-picker-error">
                  <span>{error}</span>
                  <button type="button" className="subtle-button compact" onClick={() => setRetry(value => value + 1)}>{t('common.retry', '重试')}</button>
                </div>
              )}
              {!loading && !error && !query.trim() && (
                <p className="search-scope-picker-empty">{t('search.scopePickerSearchHint', '输入关键词后显示匹配的全部书源')}</p>
              )}
              {!loading && !error && query.trim() && items.length === 0 && (
                <p className="search-scope-picker-empty">{t('search.scopePickerNoSources', '没有匹配的书源')}</p>
              )}
              {!loading && !error && items.map(item => (
                <label key={item.id} className={`search-scope-picker-source ${selectedSet.has(item.id) ? 'checked' : ''}`}>
                  <input type="checkbox" checked={selectedSet.has(item.id)} onChange={() => toggleSource(item.id)} />
                  <span className="search-scope-picker-source-info">
                    <strong>{item.name}</strong>
                    <small>{item.group || t('search.scopeUngrouped', '未分组')} · {item.url}</small>
                  </span>
                </label>
              ))}
            </div>
          </section>
        </div>

        <footer className="search-scope-picker-footer">
          <span>
            {t('search.scopePickerSelected', '已选 {{groups}} 个分组 + {{sources}} 个书源', { groups: draftGroups.length, sources: draftSourceIds.length })}
          </span>
          <div>
            <button type="button" className="subtle-button" onClick={clear}>{t('common.clear', '清空')}</button>
            <button type="button" className="subtle-button" onClick={onClose}>{t('common.cancel', '取消')}</button>
            <button type="button" className="primary-button" onClick={() => { onApply(draftGroups, draftSourceIds); onClose() }}>{t('common.apply', '应用范围')}</button>
          </div>
        </footer>
      </div>
    </div>
  )
}
