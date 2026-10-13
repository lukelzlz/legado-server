// 显式 import React：本仓库的组件在 `web/test` 下用 tsx 静态渲染时走的是 classic JSX 变换。
import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from './i18n'
import { api, SourceGroupSummary, SourceSummary, UNGROUPED_SOURCE_GROUP } from './api'
import { Icon } from './icons'
import { toast } from './Toast'

/**
 * 目标分组的取值：`__new__` 表示「新建分组」，其余为已有分组名。
 *
 * 单独抽成常量 + [resolveTargetGroup] 是因为「选了新建但没填名字」这类空值必须在这里判定，
 * 不能靠 UI 的 disabled 状态兜底（按钮状态与提交路径是两回事）。
 */
export const NEW_GROUP_OPTION = '__new__'

export function resolveTargetGroup(target: string, newGroupName: string): string | null {
  const name = (target === NEW_GROUP_OPTION ? newGroupName : target).trim()
  if (!name || name.toLowerCase() === UNGROUPED_SOURCE_GROUP.toLowerCase()) return null
  return name
}

/**
 * 删除分组的确认文案。
 *
 * 抽成纯函数是为了让「**只删分组，不删书源**」这句话可以被测试锁住 ——
 * 它是用户最容易被吓到的一步，文案写错（或漏掉）比功能出错更糟。
 */
export function groupDeleteConfirmMessage(
  group: SourceGroupSummary,
  t: (key: string, options?: any) => string = (k, opt) => String(i18n.t(k, opt as any))
): string {
  return t('source.deleteGroupConfirm', {
    name: group.name,
    count: group.sourceCount,
    defaultValue: `删除分组「${group.name}」？\n组内 ${group.sourceCount} 个书源不会被删除，只会变成「未分组」。`,
  })
}

/**
 * 分组卡片左侧的首字标记。
 *
 * 空名兜底成空串；使用 Array.from 安全提取首个 Unicode 代码点，防止 Emoji 代理对截断导致乱码。
 */
export function groupInitial(name: string): string {
  const trimmed = name.trim()
  return trimmed ? (Array.from(trimmed)[0]?.toUpperCase() ?? '') : ''
}

interface SourceGroupManagerModalProps {
  /** 当前分组列表（弹窗自己也会刷新一份）。 */
  groups: SourceGroupSummary[]
  /** 任何改动后通知外层刷新书源列表与分组列表。 */
  onChanged: () => void | Promise<void>
  onClose: () => void
}

/**
 * 「分组管理」：书源分组的**唯一**管理入口（从批量管理里独立出来）。
 *
 * 三件事：看分组 → 改分组（重命名 / 删除）→ 批量勾选书源加入分组。
 *
 * ## 视觉与交互刻意对齐「书架分组管理」（`GroupManageModal`）
 * 复用同一套 `group-manage-*` 骨架类：同样的弹窗外壳、`section-kicker` 标题、行内改名
 * （`group-item-editing` + ✓/✕ 图标按钮）、行右侧图标操作区、底部 `group-manage-footer`。
 * 反馈一律走 `toast`（与书架分组删除/改名一致），不再另画一条提示条。
 *
 * ## 行为上的三点硬约束
 * - **不自动分组**：勾选书源后必须显式选目标分组（或填新组名）才会写入；
 * - 书源列表在弹窗内单独拉**全量**（带自己的筛选框）：外层 `sources` 可能正被书源页的筛选框过滤；
 * - 分组是书源上的字符串（见 ADR-021），所以没有「先建空分组」这一步：源全部移走后分组自然消失。
 */
export function SourceGroupManagerModal({ groups, onChanged, onClose }: SourceGroupManagerModalProps) {
  const { t } = useTranslation()
  const [localGroups, setLocalGroups] = useState<SourceGroupSummary[]>(groups)
  const [sources, setSources] = useState<SourceSummary[]>([])
  const [query, setQuery] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [target, setTarget] = useState('')
  const [newGroupName, setNewGroupName] = useState('')
  const [editingGroup, setEditingGroup] = useState<string | null>(null)
  const [editingName, setEditingName] = useState('')
  const [busy, setBusy] = useState(false)

  const reload = useCallback(async () => {
    const [nextGroups, nextSources] = await Promise.all([api.sourceGroups(), api.sources()])
    setLocalGroups(nextGroups)
    setSources(nextSources)
  }, [])

  useEffect(() => {
    void reload().catch(error => toast.error(error instanceof Error ? error.message : t('source.loadGroupsFailed', '无法载入分组信息')))
  }, [reload, t])

  const visibleSources = useMemo(() => {
    const q = query.trim().toLowerCase()
    const matched = q
      ? sources.filter(source =>
        source.name.toLowerCase().includes(q)
        || source.url.toLowerCase().includes(q)
        || (source.group ?? '').toLowerCase().includes(q))
      : sources
    // 未分组的排在前面：分组管理里最常干的事就是把没归类的源归进去
    return [...matched].sort((a, b) => {
      const aGrouped = a.group ? 1 : 0
      const bGrouped = b.group ? 1 : 0
      if (aGrouped !== bGrouped) return aGrouped - bGrouped
      return a.name.localeCompare(b.name, 'zh-Hans-CN')
    })
  }, [sources, query])

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const selectAllVisible = () => setSelectedIds(new Set(visibleSources.map(source => source.id)))
  const clearSelection = () => setSelectedIds(new Set())
  const invertSelection = () => setSelectedIds(prev => new Set(visibleSources.filter(s => !prev.has(s.id)).map(s => s.id)))

  const applyGroup = async (group: string | null) => {
    if (selectedIds.size === 0) {
      toast.info(t('source.selectSourcesFirst', '请先勾选要归类的书源'))
      return
    }
    setBusy(true)
    try {
      const resp = await api.batchSources('set_group', Array.from(selectedIds), group ?? undefined)
      toast.success(t('source.updatedGroupsSuccess', { count: resp.affected, defaultValue: `已更新 ${resp.affected} 个书源的分组` }))
      setSelectedIds(new Set())
      if (target === NEW_GROUP_OPTION && group) setTarget(group)
      setNewGroupName('')
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('source.modifyGroupFailed', '修改分组失败'))
    } finally {
      setBusy(false)
    }
  }

  const handleAddToGroup = () => {
    const rawName = (target === NEW_GROUP_OPTION ? newGroupName : target).trim()
    if (rawName.toLowerCase() === UNGROUPED_SOURCE_GROUP.toLowerCase()) {
      toast.error(t('source.reservedGroupName', { name: UNGROUPED_SOURCE_GROUP, defaultValue: `不能使用系统保留字「${UNGROUPED_SOURCE_GROUP}」作为分组名称` }))
      return
    }
    const group = resolveTargetGroup(target, newGroupName)
    if (!group) {
      toast.info(t('source.chooseTargetGroupPrompt', '请选择目标分组，或填写新分组名称'))
      return
    }
    void applyGroup(group)
  }

  const handleRename = async (group: SourceGroupSummary) => {
    const next = editingName.trim()
    if (!next || next === group.name) {
      setEditingGroup(null)
      return
    }
    if (next.toLowerCase() === UNGROUPED_SOURCE_GROUP.toLowerCase()) {
      toast.error(t('source.reservedGroupName', { name: UNGROUPED_SOURCE_GROUP, defaultValue: `不能使用系统保留字「${UNGROUPED_SOURCE_GROUP}」作为分组名称` }))
      return
    }
    setBusy(true)
    try {
      const resp = await api.renameSourceGroup(group.name, next)
      toast.success(resp.message)
      if (target === group.name) setTarget(next)
      setEditingGroup(null)
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('source.renameGroupFailed', '重命名分组失败'))
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (group: SourceGroupSummary) => {
    if (!window.confirm(groupDeleteConfirmMessage(group, t))) return
    setBusy(true)
    try {
      const resp = await api.clearSourceGroup(group.name)
      toast.success(resp.message)
      if (target === group.name) setTarget('')
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('source.deleteGroupFailed', '删除分组失败'))
    } finally {
      setBusy(false)
    }
  }

  const ungroupedCount = sources.filter(source => !source.group).length

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div
        className="group-manage-modal source-group-manager"
        onClick={event => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('source.groupManage', '分组管理')}
      >
        <header className="group-manage-header">
          <div className="sgm-head-main">
            <span className="section-kicker">{t('source.sectionKicker', '书源分类')}</span>
            <h2>{t('source.groupManage', '分组管理')}</h2>
            <small>{t('source.groupManageDesc', '按分组收纳书源，搜书时即可按分组收窄范围')}</small>
          </div>
          <div className="sgm-head-side">
            <div className="sgm-stats" role="list" aria-label={t('source.groupOverview', '书源分组概览')}>
              <span className="sgm-stat" role="listitem">
                <strong>{localGroups.length}</strong>
                <em>{t('source.statGroups', '分组')}</em>
              </span>
              <span className="sgm-stat" role="listitem">
                <strong>{sources.length}</strong>
                <em>{t('source.statSources', '书源')}</em>
              </span>
              <span className={`sgm-stat ${ungroupedCount > 0 ? 'is-attention' : ''}`} role="listitem">
                <strong>{ungroupedCount}</strong>
                <em>{t('source.statUngrouped', '未分组')}</em>
              </span>
            </div>
            <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
              <Icon name="close" />
            </button>
          </div>
        </header>

        <div className="group-manage-body">
          <section className="sgm-section">
            <div className="sgm-section-head">
              <h3><Icon name="folder" />{t('source.existingGroups', '已有分组')}</h3>
              <span className="sgm-section-hint">{t('source.existingGroupsHint', '重命名或删除分组 —— 组内书源不会被删除')}</span>
            </div>
            <div className="group-list">
              {localGroups.length === 0 ? (
                <div className="group-list-empty sgm-empty">
                  <Icon name="folder" />
                  <p>{t('source.noGroupsYet', '还没有分组')}</p>
                  <small>{t('source.noGroupsHint', '在下方勾选书源并填写新分组名即可创建')}</small>
                </div>
              ) : (
                localGroups.map(group => (
                  <div key={group.name} className="group-item-row">
                    {editingGroup === group.name ? (
                      <div className="group-item-editing">
                        <span className="sgm-group-avatar" aria-hidden="true">{groupInitial(group.name)}</span>
                        <input
                          type="text"
                          value={editingName}
                          onChange={event => setEditingName(event.target.value)}
                          autoFocus
                          maxLength={20}
                          onKeyDown={event => {
                            if (event.key === 'Enter') void handleRename(group)
                            if (event.key === 'Escape') setEditingGroup(null)
                          }}
                        />
                        <button type="button" className="subtle-button confirm-btn" onClick={() => void handleRename(group)} disabled={busy} title={t('common.save', '保存')} aria-label={t('common.save', '保存')}>
                          <Icon name="check" />
                        </button>
                        <button type="button" className="subtle-button" onClick={() => setEditingGroup(null)} title={t('common.cancel', '取消')} aria-label={t('common.cancel', '取消')}>
                          <Icon name="close" />
                        </button>
                      </div>
                    ) : (
                      <>
                        <div className="group-item-info">
                          <span className="sgm-group-avatar" aria-hidden="true">{groupInitial(group.name)}</span>
                          <span className="group-item-name">{group.name}</span>
                          <span className="group-item-count">{t('source.sourcesCount', { count: group.sourceCount, defaultValue: `${group.sourceCount} 个` })}</span>
                          <span className="group-item-count">{t('source.enabledCountBadge', { count: group.enabledCount, defaultValue: `启用 ${group.enabledCount}` })}</span>
                        </div>
                        <div className="group-item-actions">
                          <button
                            type="button"
                            className="subtle-button"
                            disabled={busy}
                            onClick={() => {
                              setEditingGroup(group.name)
                              setEditingName(group.name)
                            }}
                            title={t('source.rename', '重命名')}
                            aria-label={t('source.rename', '重命名')}
                          >
                            <Icon name="edit" />
                          </button>
                          <button
                            type="button"
                            className="subtle-button danger-icon-btn"
                            disabled={busy}
                            onClick={() => void handleDelete(group)}
                            title={t('source.deleteGroupTitle', '删除分组（书源不会被删除）')}
                            aria-label={t('common.delete', '删除')}
                          >
                            <Icon name="trash" />
                          </button>
                        </div>
                      </>
                    )}
                  </div>
                ))
              )}
            </div>
          </section>

          <section className="sgm-section group-picker">
            <div className="sgm-section-head">
              <h3><Icon name="list" />{t('source.addSourcesToGroup', '把书源加入分组')}</h3>
              <span className="sgm-section-hint">{t('source.addSourcesToGroupHint', '先勾选书源，再选目标分组')}</span>
            </div>

            <div className="group-picker-head">
              <label className="group-picker-field">
                <span>{t('source.targetGroup', '目标分组')}</span>
                <select value={target} onChange={event => setTarget(event.target.value)} aria-label={t('source.targetGroup', '目标分组')}>
                  <option value="">{t('source.selectPlaceholder', '请选择…')}</option>
                  {localGroups.map(group => (
                    <option key={group.name} value={group.name}>{group.name}</option>
                  ))}
                  <option value={NEW_GROUP_OPTION}>{t('source.newGroupOption', '+ 新建分组…')}</option>
                </select>
              </label>
              {target === NEW_GROUP_OPTION && (
                <input
                  type="text"
                  className="group-picker-new"
                  value={newGroupName}
                  placeholder={t('source.newGroupPlaceholder', '新分组名称')}
                  maxLength={20}
                  onChange={event => setNewGroupName(event.target.value)}
                />
              )}
              <div className="group-picker-buttons">
                <button type="button" className="primary-button group-picker-submit" disabled={busy} onClick={handleAddToGroup}>
                  {t('source.addToGroup', '加入分组')}
                </button>
                <button type="button" className="subtle-button group-picker-remove" disabled={busy} onClick={() => void applyGroup(null)}>
                  {t('source.removeFromGroup', '移出分组')}
                </button>
              </div>
            </div>

            <div className="group-picker-toolbar">
              <span className="sgm-search">
                <Icon name="search" />
                <input
                  type="search"
                  className="group-picker-search"
                  placeholder={t('source.filterSourcesPlaceholder', '筛选书源（名称 / 地址 / 分组）')}
                  value={query}
                  onChange={event => setQuery(event.target.value)}
                />
              </span>
              <span className="group-picker-count">
                {t('source.selectedCount', '已选 {{count}} / {{total}}', { count: selectedIds.size, total: visibleSources.length })}
              </span>
              <span className="group-picker-actions">
                <button type="button" className="subtle-button compact" onClick={selectAllVisible}>{t('source.selectAll', '全选')}</button>
                <button type="button" className="subtle-button compact" onClick={clearSelection}>{t('source.deselectAll', '全不选')}</button>
                <button type="button" className="subtle-button compact" onClick={invertSelection}>{t('source.invertSelection', '反选')}</button>
              </span>
            </div>

            <div className="group-list group-picker-list" aria-label={t('source.sourceList', '书源列表')}>
              {visibleSources.map(source => (
                <label key={source.id} className={`group-item-row pick-item ${selectedIds.has(source.id) ? 'checked' : ''}`}>
                  <input
                    type="checkbox"
                    checked={selectedIds.has(source.id)}
                    onChange={() => toggleSelect(source.id)}
                  />
                  <span className="group-item-info">
                    <span className="group-item-name">{source.name}</span>
                    {!source.enabled && <span className="source-disabled-badge">{t('source.disabledBadge', '已停用')}</span>}
                  </span>
                  <span className={`group-item-count ${source.group ? '' : 'ungrouped'}`}>
                    {source.group || t('source.statUngrouped', '未分组')}
                  </span>
                </label>
              ))}
              {visibleSources.length === 0 && <p className="group-list-empty">{t('source.noMatchingSources', '没有匹配的书源')}</p>}
            </div>
          </section>
        </div>

        <footer className="group-manage-footer">
          <span className="sgm-footer-hint">
            {selectedIds.size > 0 ? t('source.selectedSourcesCount', { count: selectedIds.size, defaultValue: `已选中 ${selectedIds.size} 个书源` }) : t('source.selectSourcesPrompt', '勾选书源后可批量归类')}
          </span>
          <button type="button" className="primary-button" onClick={onClose}>{t('common.done', '完成')}</button>
        </footer>
      </div>
    </div>
  )
}
