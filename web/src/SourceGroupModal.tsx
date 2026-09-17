import { useState } from 'react'
import { Icon } from './icons'

interface SourceGroupModalProps {
  selectedCount: number
  existingGroups: string[]
  onConfirm: (group: string | null) => Promise<void>
  onClose: () => void
}

export function SourceGroupModal({
  selectedCount,
  existingGroups,
  onConfirm,
  onClose,
}: SourceGroupModalProps) {
  const [groupName, setGroupName] = useState('')
  const [isClear, setIsClear] = useState(false)
  const [busy, setBusy] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      if (isClear) {
        await onConfirm(null)
      } else {
        await onConfirm(groupName.trim() || null)
      }
      onClose()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card source-group-modal" onClick={e => e.stopPropagation()}>
        <header className="modal-header">
          <div>
            <h2>批量设置分组</h2>
            <small>已选中 {selectedCount} 个书源</small>
          </div>
          <button type="button" className="close-button" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>

        <form onSubmit={handleSubmit} className="source-group-form">
          <div className="group-mode-toggle">
            <label className="group-radio-label">
              <input
                type="radio"
                name="group_action"
                checked={!isClear}
                onChange={() => setIsClear(false)}
              />
              <span>移入指定分组</span>
            </label>
            <label className="group-radio-label">
              <input
                type="radio"
                name="group_action"
                checked={isClear}
                onChange={() => setIsClear(true)}
              />
              <span>清空分组（移出所有分组）</span>
            </label>
          </div>

          {!isClear && (
            <>
              <div className="form-group">
                <label htmlFor="groupNameInput">分组名称</label>
                <input
                  id="groupNameInput"
                  type="text"
                  placeholder="输入新分组名称或点选下方已有标签..."
                  value={groupName}
                  onChange={e => setGroupName(e.target.value)}
                  autoFocus
                />
              </div>

              {existingGroups.length > 0 && (
                <div className="existing-groups-section">
                  <span className="existing-groups-label">现有分组标签（点击快速选用）：</span>
                  <div className="existing-groups-chips">
                    {existingGroups.map(g => (
                      <button
                        key={g}
                        type="button"
                        className={`group-chip ${groupName === g ? 'active' : ''}`}
                        onClick={() => setGroupName(g)}
                      >
                        {g}
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}

          <div className="modal-actions">
            <button type="button" className="subtle-button" onClick={onClose} disabled={busy}>
              取消
            </button>
            <button type="submit" className="primary-button" disabled={busy || (!isClear && !groupName.trim())}>
              {busy ? '正在保存...' : '确认修改'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
