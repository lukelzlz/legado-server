import React, { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'

export interface SearchScopePickerModalProps {
  selectedGroups: string[]
  selectedSourceIds: string[]
  onApply: (groups: string[], sourceIds: string[]) => void
  onClose: () => void
}

export function selectedGroupForSource(item: Pick<SourceSummary, 'group'>, selectedGroups: string[]) {
  const group = item.group?.trim() || UNGROUPED_SOURCE_GROUP
  return selectedGroups.find(selected => selected.toLowerCase() === group.toLowerCase())
}

export function expandGroupWithoutSource(
  selectedGroups: string[],
  selectedSourceIds: string[],
  group: string,
  excludedSourceId: string,
  groupMemberIds: string[],
) {
  return {
    groups: selectedGroups.filter(item => item !== group),
    sourceIds: [...new Set([
      ...selectedSourceIds.filter(id => id !== excludedSourceId),
      ...groupMemberIds.filter(id => id !== excludedSourceId),
    ])],
  }
}

/**
 * 搜索范围的书源多选弹窗。
 *
 * 分组由弹窗外的快捷选项维护；这里只选择额外书源。空关键词也查询并展示全部
 * 候选，输入关键词后按名称、地址或分组过滤，服务端不截断匹配结果。
 */
export function SearchScopePickerModal({
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
  const [pendingSourceId, setPendingSourceId] = useState<string | null>(null)

  useEffect(() => {
    const trimmed = query.trim()
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

  const toggleSource = async (item: SourceSummary) => {
    const selectedGroup = selectedGroupForSource(item, draftGroups)
    if (!selectedGroup) {
      setDraftSourceIds(previous => previous.includes(item.id)
        ? previous.filter(id => id !== item.id)
        : [...previous, item.id])
      return
    }

    // 排除组内单源时，将整组展开成其余成员的显式选择，避免误伤同组其他书源。
    setPendingSourceId(item.id)
    setError('')
    try {
      const result = await api.sourcePicker('', [selectedGroup])
      const next = expandGroupWithoutSource(
        draftGroups,
        draftSourceIds,
        selectedGroup,
        item.id,
        result.items.map(source => source.id),
      )
      setDraftGroups(next.groups)
      setDraftSourceIds(next.sourceIds)
    } catch (err) {
      setError(err instanceof Error ? err.message : t('search.scopePickerLoadFailed', '书源查询失败'))
    } finally {
      setPendingSourceId(null)
    }
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
            <p>{t('search.scopePickerDesc', '可选择一个或多个书源，并与外部分组组合搜索')}</p>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>×</button>
        </header>

        <div className="search-scope-picker-body">
          <section className="search-scope-picker-section search-scope-picker-sources">
            <div className="search-scope-picker-section-title">
              <strong>{t('search.scopePickerSources', '选择单个书源')}</strong>
              <small>{t('search.scopePickerSourcesHint', '不输入关键词时显示全部书源')}</small>
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
              {!loading && !error && items.length === 0 && (
                <p className="search-scope-picker-empty">{t('search.scopePickerNoSources', '没有匹配的书源')}</p>
              )}
              {!loading && !error && items.map(item => {
                const checked = selectedSet.has(item.id) || selectedGroupForSource(item, draftGroups) !== undefined
                return (
                  <label key={item.id} className={`search-scope-picker-source ${checked ? 'checked' : ''}`} aria-busy={pendingSourceId === item.id}>
                    <input type="checkbox" checked={checked} disabled={pendingSourceId !== null} onChange={() => void toggleSource(item)} />
                    <span className="search-scope-picker-source-info">
                      <strong>{item.name}</strong>
                      <small><span className="search-scope-picker-source-group">{item.group || t('search.scopeUngrouped', '未分组')}</span><span className="search-scope-picker-source-url">{item.url}</span></small>
                    </span>
                  </label>
                )
              })}
            </div>
          </section>
        </div>

        <footer className="search-scope-picker-footer">
          <span>
            {t('search.scopePickerSelected', '已选 {{groups}} 个分组 + {{sources}} 个书源', { groups: draftGroups.length, sources: draftSourceIds.length })}
          </span>
          <div>
            <button type="button" className="subtle-button" disabled={pendingSourceId !== null} onClick={clear}>{t('common.clear', '清空')}</button>
            <button type="button" className="subtle-button" onClick={onClose}>{t('common.cancel', '取消')}</button>
            <button type="button" className="primary-button" disabled={pendingSourceId !== null} onClick={() => { onApply(draftGroups, draftSourceIds); onClose() }}>{t('common.apply', '应用范围')}</button>
          </div>
        </footer>
      </div>
    </div>
  )
}
