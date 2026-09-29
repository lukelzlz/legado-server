// 显式 import React：本仓库的组件在 `web/test` 下用 tsx 静态渲染时走的是 classic JSX 变换
// （`React is not defined` 就是漏了它），与 WebDavSettingsPage / ReplaceRulesPage 保持一致。
import React from 'react'
import { SourceGroupSummary, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'

/**
 * 书库页搜索栏下方的「搜索范围」选项卡。
 *
 * 设计要点（与后端范围语义一一对应）：
 * - **「全部书源」永远是第一项且是默认值**：不选分组就是全量搜索，用户随时能一键回到「搜全部」；
 * - 分组卡片刻意显示 `enabledCount`（**已启用**书源数）而不是组内总数：停用的书源不参与搜索，
 *   显示总数会出现「选了 12 个源、实际只跑了 7 个」的困惑；
 * - 分组与单源**互斥**：这里只负责把用户意图告诉上层，由 store 保证两者不会同时生效；
 * - 单独的组件而不是内联在 `LibraryPage` 里：它是纯展示 + 回调，可以被静态渲染测试覆盖
 *   （书页面的选项卡顺序与「全部书源」默认项是需求明确要求的，值得锁住）。
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
  const allSelected = !selectedGroup && !selectedSourceId

  return (
    <div className="library-scope-bar" role="tablist" aria-label="搜索范围">
      <span className="library-scope-label">搜索范围</span>
      <button
        type="button"
        role="tab"
        aria-selected={allSelected}
        className={`scope-tab ${allSelected ? 'active' : ''}`}
        onClick={onSelectAll}
        title="不限定分组，搜索全部已启用的书源"
      >
        全部书源
      </button>
      {groups.map(group => (
        <button
          key={group.name}
          type="button"
          role="tab"
          aria-selected={selectedGroup === group.name}
          className={`scope-tab ${selectedGroup === group.name ? 'active' : ''}`}
          onClick={() => onSelectGroup(group.name)}
          title={`分组内共 ${group.sourceCount} 个书源，其中 ${group.enabledCount} 个已启用（只有已启用的会参与搜索）`}
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
        title="只搜索还没有归入任何分组的书源"
      >
        未分组
      </button>
      <label className="library-scope-single">
        指定单个书源
        <select
          value={selectedSourceId}
          onChange={event => onSelectSource(event.target.value)}
          aria-label="指定单个书源搜索"
        >
          <option value="">不限</option>
          {sources.map(source => (
            <option key={source.id} value={source.id}>{source.name}</option>
          ))}
        </select>
      </label>
    </div>
  )
}
