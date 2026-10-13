// 显式 import React：本仓库的组件在 `web/test` 下用 tsx 静态渲染时走的是 classic JSX 变换。
import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from './i18n'
import { api, ReplaceRule, ReplaceRuleGroupSummary, UNGROUPED_SOURCE_GROUP } from './api'
import { Icon } from './icons'
import { toast } from './Toast'

export const NEW_RULE_GROUP_OPTION = '__new__'

export function resolveRuleTargetGroup(target: string, newGroupName: string): string | null {
  const name = (target === NEW_RULE_GROUP_OPTION ? newGroupName : target).trim()
  if (!name || name.toLowerCase() === UNGROUPED_SOURCE_GROUP.toLowerCase()) return null
  return name
}

export function ruleGroupDeleteConfirmMessage(
  group: ReplaceRuleGroupSummary,
  t: (key: string, options?: any) => string = (k, opt) => String(i18n.t(k, opt as any))
): string {
  return t('rules.deleteGroupConfirm', {
    name: group.name,
    count: group.ruleCount,
    defaultValue: `删除分组「${group.name}」？\n组内 ${group.ruleCount} 条规则不会被删除，只会变成「未分组」。`,
  })
}

export function ruleGroupInitial(name: string): string {
  const trimmed = name.trim()
  return trimmed ? (Array.from(trimmed)[0]?.toUpperCase() ?? '') : ''
}

interface ReplaceRuleGroupManagerModalProps {
  groups: ReplaceRuleGroupSummary[]
  onChanged: () => void | Promise<void>
  onClose: () => void
}

export function ReplaceRuleGroupManagerModal({ groups, onChanged, onClose }: ReplaceRuleGroupManagerModalProps) {
  const { t } = useTranslation()
  const [localGroups, setLocalGroups] = useState<ReplaceRuleGroupSummary[]>(groups)
  const [rules, setRules] = useState<ReplaceRule[]>([])
  const [query, setQuery] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [target, setTarget] = useState('')
  const [newGroupName, setNewGroupName] = useState('')
  const [editingGroup, setEditingGroup] = useState<string | null>(null)
  const [editingName, setEditingName] = useState('')
  const [busy, setBusy] = useState(false)

  const reload = useCallback(async () => {
    const [nextGroups, nextRules] = await Promise.all([api.replaceRuleGroups(), api.getReplaceRules()])
    setLocalGroups(nextGroups)
    setRules(nextRules)
  }, [])

  useEffect(() => {
    void reload().catch(error => toast.error(error instanceof Error ? error.message : t('rules.loadGroupsFailed', '无法载入分组信息')))
  }, [reload, t])

  const visibleRules = useMemo(() => {
    const q = query.trim().toLowerCase()
    const matched = q
      ? rules.filter(r =>
        (r.name || '').toLowerCase().includes(q)
        || (r.pattern || '').toLowerCase().includes(q)
        || (r.replacement || '').toLowerCase().includes(q)
        || (r.group || '').toLowerCase().includes(q)
      )
      : rules
    return [...matched].sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
  }, [rules, query])

  const ungroupedCount = useMemo(
    () => rules.filter(r => !r.group || !r.group.trim()).length,
    [rules]
  )

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleSelectAll = () => {
    setSelectedIds(new Set(visibleRules.map(r => r.id)))
  }

  const handleClearAll = () => {
    setSelectedIds(new Set())
  }

  const handleInvertSelection = () => {
    const next = new Set<string>()
    for (const r of visibleRules) {
      if (!selectedIds.has(r.id)) next.add(r.id)
    }
    setSelectedIds(next)
  }

  const handleStartRename = (group: ReplaceRuleGroupSummary) => {
    setEditingGroup(group.name)
    setEditingName(group.name)
  }

  const handleCancelRename = () => {
    setEditingGroup(null)
    setEditingName('')
  }

  const handleSaveRename = async (from: string) => {
    const to = editingName.trim()
    if (!to) {
      toast.error(t('rules.groupNameEmpty', '分组名称不能为空'))
      return
    }
    if (to.toLowerCase() === UNGROUPED_SOURCE_GROUP.toLowerCase()) {
      toast.error(t('rules.groupNameReserved', '不能使用系统保留字「{{name}}」作为分组名称', { name: to }))
      return
    }
    if (from.toLowerCase() === to.toLowerCase()) {
      handleCancelRename()
      return
    }
    setBusy(true)
    try {
      const resp = await api.renameReplaceRuleGroup(from, to)
      toast.success(resp.message)
      handleCancelRename()
      if (target === from) setTarget(to)
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('rules.renameGroupFailed', '重命名分组失败'))
    } finally {
      setBusy(false)
    }
  }

  const handleDeleteGroup = async (group: ReplaceRuleGroupSummary) => {
    if (!window.confirm(ruleGroupDeleteConfirmMessage(group, t))) return
    setBusy(true)
    try {
      const resp = await api.clearReplaceRuleGroup(group.name)
      toast.success(resp.message)
      if (target === group.name) setTarget('')
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('rules.deleteGroupFailed', '删除分组失败'))
    } finally {
      setBusy(false)
    }
  }

  const handleApplyGroup = async () => {
    if (selectedIds.size === 0) {
      toast.error(t('rules.selectRulesFirst', '请先勾选需要归类的替换规则'))
      return
    }
    const resolved = resolveRuleTargetGroup(target, newGroupName)
    if (target === NEW_RULE_GROUP_OPTION && !resolved) {
      toast.error(t('rules.newGroupNamePrompt', '请输入新分组名称'))
      return
    }
    setBusy(true)
    try {
      const resp = await api.batchReplaceRules({
        action: 'set_group',
        ids: Array.from(selectedIds),
        group: resolved,
      })
      toast.success(
        resolved
          ? t('rules.rulesMovedToGroup', '已将 {{count}} 条规则加入分组「{{group}}」', { count: resp.affected, group: resolved })
          : t('rules.rulesMovedToUngrouped', '已将 {{count}} 条规则移出分组', { count: resp.affected })
      )
      setSelectedIds(new Set())
      if (target === NEW_RULE_GROUP_OPTION) {
        setTarget(resolved ?? '')
        setNewGroupName('')
      }
      await reload()
      await onChanged()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('rules.setGroupFailed', '批量设置分组失败'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="group-manage-modal source-group-manager"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('rules.ruleGroupManagerTitle', '替换规则分组管理')}
      >
        <header className="group-manage-header">
          <div>
            <span className="section-kicker">{t('rules.mainTitle', '替换净化规则')}</span>
            <h2>{t('rules.groupManager', '分组管理')}</h2>
          </div>
          <button type="button" className="close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        <div className="sgm-overview">
          <div className="sgm-stat-pill">
            <span className="sgm-stat-label">{t('rules.groupsCountLabel', '已有分组')}</span>
            <span className="sgm-stat-val">{localGroups.length}</span>
          </div>
          <div className="sgm-stat-pill">
            <span className="sgm-stat-label">{t('rules.totalRulesLabel', '规则总数')}</span>
            <span className="sgm-stat-val">{rules.length}</span>
          </div>
          <div className={`sgm-stat-pill ${ungroupedCount > 0 ? 'is-warning' : ''}`}>
            <span className="sgm-stat-label">{t('rules.ungroupedLabel', '未分组规则')}</span>
            <span className="sgm-stat-val">{ungroupedCount}</span>
          </div>
        </div>

        <div className="group-manage-body">
          <section className="sgm-section">
            <div className="sgm-section-head">
              <h3>{t('rules.existingGroupsTitle', '已有分组')}</h3>
              <span className="sgm-section-hint">{t('rules.existingGroupsDesc', '重命名或解绑已有分组')}</span>
            </div>

            {localGroups.length === 0 ? (
              <div className="group-empty-state">
                <span className="group-empty-icon" aria-hidden="true">🏷️</span>
                <p className="group-empty-title">{t('rules.noGroupsYet', '暂无自定义分组')}</p>
                <p className="group-empty-desc">{t('rules.noGroupsHint', '可以在下方将规则归入新分组，分组创建后即可在此集中重命名或解绑。')}</p>
              </div>
            ) : (
              <ul className="group-list">
                {localGroups.map(group => {
                  const isEditing = editingGroup === group.name
                  return (
                    <li key={group.name} className={`group-item-row ${target === group.name ? 'is-active-target' : ''}`}>
                      <span className="group-avatar" aria-hidden="true">{ruleGroupInitial(group.name)}</span>
                      {isEditing ? (
                        <div className="group-item-editing">
                          <input
                            type="text"
                            value={editingName}
                            onChange={e => setEditingName(e.target.value)}
                            onKeyDown={e => {
                              if (e.key === 'Enter') void handleSaveRename(group.name)
                              else if (e.key === 'Escape') handleCancelRename()
                            }}
                            autoFocus
                            disabled={busy}
                            placeholder={t('rules.groupNamePlaceholder', '分组名称')}
                          />
                          <button
                            type="button"
                            className="subtle-button confirm-btn"
                            disabled={busy}
                            onClick={() => void handleSaveRename(group.name)}
                            title={t('common.save', '保存')}
                          >
                            <Icon name="check" />
                          </button>
                          <button
                            type="button"
                            className="subtle-button cancel-btn"
                            disabled={busy}
                            onClick={handleCancelRename}
                            title={t('common.cancel', '取消')}
                          >
                            <Icon name="close" />
                          </button>
                        </div>
                      ) : (
                        <>
                          <div className="group-item-info">
                            <span className="group-item-name" title={group.name}>{group.name}</span>
                            <span className="group-item-count">
                              {t('rules.ruleGroupStats', '{{count}} 条（{{enabled}} 启用）', { count: group.ruleCount, enabled: group.enabledCount })}
                            </span>
                          </div>
                          <div className="group-item-actions">
                            <button
                              type="button"
                              className="subtle-button edit-btn"
                              disabled={busy}
                              onClick={() => handleStartRename(group)}
                              title={t('rules.renameGroup', '重命名')}
                            >
                              <Icon name="edit" />
                            </button>
                            <button
                              type="button"
                              className="danger-button delete-btn"
                              disabled={busy}
                              onClick={() => void handleDeleteGroup(group)}
                              title={t('rules.deleteGroup', '删除分组（仅解绑）')}
                            >
                              <Icon name="trash" />
                            </button>
                          </div>
                        </>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
          </section>

          <section className="sgm-section sgm-workbench-card">
            <div className="sgm-section-head">
              <h3>{t('rules.groupWorkbenchTitle', '规则归类工作台')}</h3>
              <span className="sgm-section-hint">{t('rules.groupWorkbenchDesc', '批量勾选规则并设置归属分组')}</span>
            </div>

            <div className="group-picker-row">
              <label htmlFor="rule-target-group-select" className="group-picker-label">
                {t('rules.targetGroupLabel', '目标分组')}
              </label>
              <select
                id="rule-target-group-select"
                className="group-picker-select"
                value={target}
                onChange={e => setTarget(e.target.value)}
                disabled={busy}
              >
                <option value="">{t('rules.targetGroupChoosePrompt', '— 请选择目标分组 —')}</option>
                {localGroups.map(g => (
                  <option key={g.name} value={g.name}>
                    {g.name} ({g.ruleCount})
                  </option>
                ))}
                <option value={NEW_RULE_GROUP_OPTION}>{t('rules.targetGroupNewOption', '＋ 新建分组…')}</option>
              </select>
            </div>

            {target === NEW_RULE_GROUP_OPTION && (
              <div className="group-picker-new-row">
                <input
                  type="text"
                  className="group-picker-input"
                  value={newGroupName}
                  onChange={e => setNewGroupName(e.target.value)}
                  placeholder={t('rules.newGroupNamePrompt', '输入新分组名称')}
                  disabled={busy}
                  autoFocus
                />
              </div>
            )}

            <div className="group-picker-toolbar">
              <div className="group-picker-search">
                <Icon name="search" />
                <input
                  type="text"
                  placeholder={t('rules.filterRulesToGroupPlaceholder', '在全量规则中筛选…')}
                  value={query}
                  onChange={e => setQuery(e.target.value)}
                  disabled={busy}
                />
              </div>
              <div className="sgm-segmented-helpers" role="group" aria-label={t('common.select', '选择模式')}>
                <button type="button" className="subtle-button compact" onClick={handleSelectAll} disabled={busy || visibleRules.length === 0}>
                  {t('shelf.selectAll', '全选')}
                </button>
                <button type="button" className="subtle-button compact" onClick={handleClearAll} disabled={busy || selectedIds.size === 0}>
                  {t('source.deselectAll', '全不选')}
                </button>
                <button type="button" className="subtle-button compact" onClick={handleInvertSelection} disabled={busy || visibleRules.length === 0}>
                  {t('common.invertSelect', '反选')}
                </button>
              </div>
            </div>

            <div className="group-picker-list" role="list" aria-label={t('rules.allRulesAria', '规则列表')}>
              {visibleRules.length === 0 ? (
                <p className="group-picker-empty">{t('rules.emptyRules', '暂无匹配的替换规则')}</p>
              ) : (
                visibleRules.map(r => {
                  const isChecked = selectedIds.has(r.id)
                  return (
                    <label key={r.id} className={`pick-item ${isChecked ? 'is-checked' : ''}`}>
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => toggleSelect(r.id)}
                        disabled={busy}
                      />
                      <div className="pick-item-info">
                        <span className="pick-item-name">{r.name || r.pattern || t('rules.unnamedRule', '未命名规则')}</span>
                        <span className="pick-item-group-tag">
                          {r.group?.trim() ? r.group.trim() : t('rules.ungrouped', '未分组')}
                        </span>
                      </div>
                    </label>
                  )
                })
              )}
            </div>

            <div className="sgm-selected-summary">
              <span>{t('rules.selectedRulesSummary', '已勾选 {{count}} / {{total}} 条规则', { count: selectedIds.size, total: visibleRules.length })}</span>
            </div>
          </section>
        </div>

        <footer className="group-manage-footer">
          <button type="button" className="subtle-button" onClick={onClose} disabled={busy}>
            {t('common.close', '关闭')}
          </button>
          <button
            type="button"
            className="primary-button"
            disabled={busy || selectedIds.size === 0 || (!target || (target === NEW_RULE_GROUP_OPTION && !newGroupName.trim()))}
            onClick={() => void handleApplyGroup()}
          >
            {busy ? t('common.loading', '正在处理…') : t('rules.applyGroupButton', '将所选规则加入该分组')}
          </button>
        </footer>
      </div>
    </div>
  )
}
