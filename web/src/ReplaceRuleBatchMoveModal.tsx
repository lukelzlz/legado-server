import React, { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ReplaceRuleGroupSummary } from './api'
import { Icon } from './icons'

interface ReplaceRuleBatchMoveModalProps {
  groups: ReplaceRuleGroupSummary[]
  selectedCount: number
  onClose: () => void
  onSelectGroup: (groupName: string | null) => Promise<void>
}

export function ReplaceRuleBatchMoveModal({
  groups,
  selectedCount,
  onClose,
  onSelectGroup,
}: ReplaceRuleBatchMoveModalProps) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)

  const handleSelect = async (groupName: string | null) => {
    setBusy(true)
    try {
      await onSelectGroup(groupName)
      onClose()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div
        className="batch-move-modal rule-batch-move-modal"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={t('rules.batchMoveToGroup', '移动规则至分组')}
      >
        <header className="group-manage-header">
          <div>
            <span className="section-kicker">{t('shelf.batchKicker', '批量操作')}</span>
            <h2>{t('rules.batchMoveToGroup', '移动到分组')}</h2>
            <small>{t('rules.selectedRulesCount', '已选择 {{count}} 条规则', { count: selectedCount })}</small>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label={t('common.close', '关闭')}>
            <Icon name="close" />
          </button>
        </header>

        <div className="batch-move-list">
          <button type="button" className="batch-move-option" disabled={busy} onClick={() => void handleSelect(null)}>
            <div className="option-name">{t('rules.ungrouped', '未分组')}</div>
            <small>{t('shelf.clearGroupBelonging', '清除当前分组归属')}</small>
          </button>
          {groups.map(g => (
            <button key={g.name} type="button" className="batch-move-option" disabled={busy} onClick={() => void handleSelect(g.name)}>
              <div className="option-name">{g.name}</div>
              <small>{t('rules.ruleCountInGroup', '{{count}} 条规则', { count: g.ruleCount })}</small>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
