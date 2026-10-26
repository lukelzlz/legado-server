import React from 'react'
import { useTranslation } from 'react-i18next'
import { SourceGroupSummary, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'

/**
 * 书库页搜索栏下方的搜索范围入口。
 *
 * 书源分组按钮保留为快速入口，但不再与单个书源互斥；完整的多选范围通过
 * `onOpenPicker` 打开的选择器维护。旧版单值 props 仍可用于静态渲染与兼容调用方。
 */
export interface SearchScopeBarProps {
  groups: SourceGroupSummary[]
  /** 旧版单源下拉所需；新选择器模式不传。 */
  sources?: SourceSummary[]
  selectedGroups?: string[]
  selectedSourceIds?: string[]
  /** 旧版单值范围 props。 */
  selectedGroup?: string
  selectedSourceId?: string
  onSelectAll: () => void
  onToggleGroup?: (group: string) => void
  onOpenPicker?: () => void
  /** 旧版单值回调。 */
  onSelectGroup?: (group: string) => void
  onSelectSource?: (sourceId: string) => void
}

export function SearchScopeBar({
  groups,
  sources,
  selectedGroups,
  selectedSourceIds,
  selectedGroup = '',
  selectedSourceId = '',
  onSelectAll,
  onToggleGroup,
  onOpenPicker,
  onSelectGroup,
  onSelectSource,
}: SearchScopeBarProps) {
  const { t } = useTranslation()
  const effectiveGroups = selectedGroups ?? (selectedGroup ? [selectedGroup] : [])
  const effectiveSourceIds = selectedSourceIds ?? (selectedSourceId ? [selectedSourceId] : [])
  const groupSet = new Set(effectiveGroups)
  const allSelected = effectiveGroups.length === 0 && effectiveSourceIds.length === 0
  const hasCustomScope = effectiveGroups.length > 0 || effectiveSourceIds.length > 0
  const pickerLabel = effectiveGroups.length > 0 && effectiveSourceIds.length > 0
    ? t('search.scopeMixed', '{{groups}} 个分组 + {{sources}} 个书源', { groups: effectiveGroups.length, sources: effectiveSourceIds.length })
    : effectiveGroups.length > 0
      ? t('search.scopeSelectedGroups', '已选 {{count}} 个分组', { count: effectiveGroups.length })
      : effectiveSourceIds.length > 0
        ? t('search.scopeSelectedSources', '已选 {{count}} 个书源', { count: effectiveSourceIds.length })
        : t('search.scopePickSources', '选择书源')

  const toggleGroup = onToggleGroup ?? onSelectGroup ?? (() => undefined)

  return (
    <div className="library-scope-bar" role="tablist" aria-label={t('search.scopeLabel')}>
      <span className="library-scope-label">{t('search.scopeLabel')}</span>
      <button
        type="button"
        role="tab"
        aria-selected={allSelected}
        className={`scope-tab ${allSelected ? 'active' : ''}`}
        onClick={onSelectAll}
        title={t('search.scopeAllTitle')}
      >
        {t('search.scopeAll')}
      </button>
      {groups.map(group => (
        <button
          key={group.name}
          type="button"
          role="tab"
          aria-selected={groupSet.has(group.name)}
          className={`scope-tab ${groupSet.has(group.name) ? 'active' : ''}`}
          onClick={() => toggleGroup(group.name)}
          title={t('search.scopeGroupTitle', { total: group.sourceCount, enabled: group.enabledCount, defaultValue: `分组内共 ${group.sourceCount} 个书源，其中 ${group.enabledCount} 个已启用（只有已启用的会参与搜索）` })}
        >
          {group.name}
          <small>{group.enabledCount}</small>
        </button>
      ))}
      <button
        type="button"
        role="tab"
        aria-selected={groupSet.has(UNGROUPED_SOURCE_GROUP)}
        className={`scope-tab ${groupSet.has(UNGROUPED_SOURCE_GROUP) ? 'active' : ''}`}
        onClick={() => toggleGroup(UNGROUPED_SOURCE_GROUP)}
        title={t('search.scopeUngroupedTitle')}
      >
        {t('search.scopeUngrouped')}
      </button>
      {onOpenPicker ? (
        <button
          type="button"
          className={`scope-picker-trigger ${hasCustomScope ? 'active' : ''}`}
          onClick={onOpenPicker}
          aria-label={t('search.scopePickerAria', '打开搜索范围选择器')}
          title={t('search.scopePickerTitle', '选择多个分组或书源')}
        >
          {pickerLabel}
        </button>
      ) : sources && onSelectSource ? (
        <label className="library-scope-single">
          {t('search.scopeSingle')}
          <select
            value={selectedSourceId}
            onChange={event => onSelectSource(event.target.value)}
            aria-label={t('search.singleSourceAria')}
          >
            <option value="">{t('search.unlimited')}</option>
            {sources.map(source => (
              <option key={source.id} value={source.id}>{source.name}</option>
            ))}
          </select>
        </label>
      ) : null}
    </div>
  )
}
