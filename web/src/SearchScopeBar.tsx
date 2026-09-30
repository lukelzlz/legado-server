import React from 'react'
import { useTranslation } from 'react-i18next'
import { SourceGroupSummary, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'

/**
 * 书库页搜索栏下方的「搜索范围」选项卡。
 */
export interface SearchScopeBarProps {
  /** 服务端聚合出的书源分组（不含「未分组」）。 */
  groups: SourceGroupSummary[]
  /** 全部书源（用于「指定单个书源」下拉）。 */
  sources: SourceSummary[]
  /** 当前选中的分组件名；空串 = 未按分组过滤。 */
  selectedGroup: string
  /** 当前选中的单个书源 id；空串 = 未指定单源。 */
  selectedSourceId: string
  /** 选择「全部书源」（清空分组与单源）。 */
  onSelectAll: () => void
  /** 选择一个书源分组（含 [UNGROUPED_SOURCE_GROUP]）。 */
  onSelectGroup: (group: string) => void
  /** 选择单个书源。 */
  onSelectSource: (sourceId: string) => void
}

export function SearchScopeBar({
  groups,
  sources,
  selectedGroup,
  selectedSourceId,
  onSelectAll,
  onSelectGroup,
  onSelectSource,
}: SearchScopeBarProps) {
  const { t } = useTranslation()
  const allSelected = !selectedGroup && !selectedSourceId

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
          aria-selected={selectedGroup === group.name}
          className={`scope-tab ${selectedGroup === group.name ? 'active' : ''}`}
          onClick={() => onSelectGroup(group.name)}
          title={t('search.scopeGroupTitle', { total: group.sourceCount, enabled: group.enabledCount, defaultValue: `分组内共 ${group.sourceCount} 个书源，其中 ${group.enabledCount} 个已启用（只有已启用的会参与搜索）` })}
        >
          {group.name}
          <small>{group.enabledCount}</small>
        </button>
      ))}
      <button
        type="button"
        role="tab"
        aria-selected={selectedGroup === UNGROUPED_SOURCE_GROUP}
        className={`scope-tab ${selectedGroup === UNGROUPED_SOURCE_GROUP ? 'active' : ''}`}
        onClick={() => onSelectGroup(UNGROUPED_SOURCE_GROUP)}
        title={t('search.scopeUngroupedTitle')}
      >
        {t('search.scopeUngrouped')}
      </button>
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
    </div>
  )
}
