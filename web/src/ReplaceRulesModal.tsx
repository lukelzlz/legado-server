import React, { useState, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ReplaceRule, ReplaceRulePreviewResponse } from './api'
import { toast } from './Toast'
import { Icon } from './icons'

interface Props {
  isOpen: boolean
  onClose: () => void
  currentBookName?: string
  currentSourceUrl?: string
  onRulesChanged?: () => void
}

export const ReplaceRulesModal: React.FC<Props> = ({
  isOpen,
  onClose,
  currentBookName,
  currentSourceUrl,
  onRulesChanged,
}) => {
  const { t } = useTranslation()
  const [rules, setRules] = useState<ReplaceRule[]>([])
  const [loading, setLoading] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedGroup, setSelectedGroup] = useState<string>('all')
  const [scopeFilter, setScopeFilter] = useState<'all' | 'current'>('all')

  // 编辑模态框状态
  const [editingRule, setEditingRule] = useState<Partial<ReplaceRule> | null>(null)
  const [isEditModalOpen, setIsEditModalOpen] = useState(false)

  // 导入模态框状态
  const [isImportModalOpen, setIsImportModalOpen] = useState(false)
  const [importType, setImportType] = useState<'url' | 'text'>('url')
  const [importInput, setImportInput] = useState('')
  const [importing, setImporting] = useState(false)

  // 规则测试预览状态
  const [testText, setTestText] = useState('')
  const [testResult, setTestResult] = useState<ReplaceRulePreviewResponse | null>(null)
  const [testing, setTesting] = useState(false)

  const loadRules = async () => {
    setLoading(true)
    try {
      const data = await api.getReplaceRules()
      setRules(data)
    } catch (e: any) {
      toast.error(e.message || t('rules.loadRulesFailed', '加载替换规则失败'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (isOpen) {
      void loadRules()
    }
  }, [isOpen])

  const groups = useMemo(() => {
    const set = new Set<string>()
    rules.forEach(r => {
      if (r.group?.trim()) set.add(r.group.trim())
    })
    return Array.from(set)
  }, [rules])

  const filteredRules = useMemo(() => {
    return rules.filter(rule => {
      if (selectedGroup !== 'all' && (rule.group?.trim() || t('rules.ungrouped', '未分组')) !== selectedGroup) {
        return false
      }
      if (scopeFilter === 'current') {
        const matchesCurrent =
          !rule.scope?.trim() ||
          (currentBookName && rule.scope.includes(currentBookName)) ||
          (currentSourceUrl && rule.scope.includes(currentSourceUrl))
        if (!matchesCurrent) return false
      }
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase()
        const nameMatch = (rule.name || '').toLowerCase().includes(q)
        const patternMatch = (rule.pattern || '').toLowerCase().includes(q)
        const replacementMatch = (rule.replacement || '').toLowerCase().includes(q)
        const scopeMatch = rule.scope?.toLowerCase().includes(q) || false
        if (!nameMatch && !patternMatch && !replacementMatch && !scopeMatch) return false
      }
      return true
    })
  }, [rules, selectedGroup, scopeFilter, searchQuery, currentBookName, currentSourceUrl, t])

  const handleToggle = async (rule: ReplaceRule) => {
    const newStatus = !(rule.isEnabled ?? true)
    try {
      await api.toggleReplaceRules([rule.id], newStatus)
      setRules(prev => prev.map(r => (r.id === rule.id ? { ...r, isEnabled: newStatus } : r)))
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || t('rules.toggleStatusFailed', '切换状态失败'))
    }
  }

  const handleDelete = async (rule: ReplaceRule) => {
    if (!confirm(t('rules.deleteRuleConfirm', '确定删除规则「{{name}}」吗？', { name: rule.name || rule.pattern }))) return
    try {
      await api.deleteReplaceRule(rule.id)
      setRules(prev => prev.filter(r => r.id !== rule.id))
      toast.success(t('rules.deleteSuccess', '删除成功'))
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || t('rules.deleteFailed', '删除失败'))
    }
  }

  const handleSaveRule = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editingRule || !editingRule.pattern?.trim()) {
      toast.error(t('rules.patternRequired', '匹配模式不能为空'))
      return
    }
    try {
      if (editingRule.id) {
        const updated = await api.updateReplaceRule(editingRule.id, editingRule)
        setRules(prev => prev.map(r => (r.id === updated.id ? updated : r)))
        toast.success(t('rules.ruleUpdated', '规则已更新'))
      } else {
        const created = await api.createReplaceRule(editingRule)
        setRules(prev => [created, ...prev])
        toast.success(t('rules.ruleCreated', '规则创建成功'))
      }
      setIsEditModalOpen(false)
      setEditingRule(null)
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || t('rules.saveRuleFailed', '保存规则失败'))
    }
  }

  const handleImport = async () => {
    if (!importInput.trim()) {
      toast.error(t('rules.inputImportContent', '请输入导入内容或链接'))
      return
    }
    setImporting(true)
    try {
      let res
      if (importType === 'url') {
        res = await api.importReplaceRulesUrl(importInput.trim())
      } else {
        res = await api.importReplaceRulesText(importInput.trim())
      }
      toast.success(t('rules.importCompleteSummary', '导入完成：新增 {{imported}} 条，更新 {{updated}} 条，跳过 {{skipped}} 条', {
        imported: res.imported,
        updated: res.updated,
        skipped: res.skipped,
      }))
      setIsImportModalOpen(false)
      setImportInput('')
      await loadRules()
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || t('rules.importFailed', '导入失败，请检查格式或网络连接'))
    } finally {
      setImporting(false)
    }
  }

  const handleExport = () => {
    const jsonStr = JSON.stringify(rules, null, 2)
    const blob = new Blob([jsonStr], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `legado-replace-rules-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
    toast.success(t('rules.exportSuccess', '已导出替换规则文件'))
  }

  const handleRunTest = async () => {
    if (!testText.trim()) {
      toast.error(t('rules.inputTestText', '请输入待测试的文本'))
      return
    }
    setTesting(true)
    try {
      const res = await api.previewReplaceRule({
        text: testText,
        rule: (editingRule as ReplaceRule) || undefined,
        bookName: currentBookName,
        sourceUrl: currentSourceUrl,
      })
      setTestResult(res)
    } catch (e: any) {
      toast.error(e.message || t('rules.testFailed', '测试失败'))
    } finally {
      setTesting(false)
    }
  }

  const openAddModal = (forCurrentBook = false) => {
    setEditingRule({
      name: forCurrentBook && currentBookName ? t('rules.currentBookPurifyRule', '{{name}}-净化规则', { name: currentBookName }) : '',
      group: forCurrentBook && currentBookName ? currentBookName : '',
      pattern: '',
      replacement: '',
      isRegex: true,
      scope: forCurrentBook && currentBookName ? currentBookName : '',
      excludeScope: '',
      scopeTitle: false,
      scopeContent: true,
      isEnabled: true,
      order: 0,
      timeoutMillisecond: 3000,
    })
    setTestText('')
    setTestResult(null)
    setIsEditModalOpen(true)
  }

  if (!isOpen) return null

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="rule-modal-card"
        style={{ width: 'min(820px, 94vw)', maxHeight: '90vh' }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <header className="source-login-header">
          <div className="rules-sidebar-title">
            <Icon name="sliders" />
            <span>{t('rules.mainTitle', { defaultValue: '替换净化规则管理' })}</span>
            <span className="rules-count-badge">{t('common.total', { count: rules.length, defaultValue: `共 ${rules.length} 条` })}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              onClick={() => openAddModal(false)}
              className="primary-button"
              style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
            >
              <Icon name="plus" />
              <span>{t('rules.newRule', { defaultValue: '新建规则' })}</span>
            </button>
            {currentBookName && (
              <button
                type="button"
                onClick={() => openAddModal(true)}
                className="subtle-button"
                style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
              >
                <span>{t('rules.ruleForCurrentBook', { defaultValue: '当前书专用' })}</span>
              </button>
            )}
            <button
              type="button"
              onClick={() => setIsImportModalOpen(true)}
              className="subtle-button"
              style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
            >
              <Icon name="upload" />
              <span>{t('common.import', { defaultValue: '导入' })}</span>
            </button>
            <button
              type="button"
              onClick={handleExport}
              className="ghost-button"
              style={{ height: '30px', padding: '0 8px', fontSize: '12px' }}
              title={t('rules.exportAll', { defaultValue: '导出全部' })}
            >
              <Icon name="download" />
            </button>
            <button
              type="button"
              onClick={onClose}
              className="icon-btn"
            >
              <Icon name="close" />
            </button>
          </div>
        </header>

        {/* Toolbar & Filters */}
        <div style={{ padding: '10px 16px', borderBottom: '1px solid var(--line)', background: 'var(--surface-muted)', display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'center', justifyContent: 'space-between' }}>
          <div className="rules-search-wrap" style={{ flex: '1 1 200px' }}>
            <Icon name="search" />
            <input
              type="text"
              placeholder={t('rules.searchPlaceholder', { defaultValue: '搜索规则名、正则、替换词...' })}
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              className="rules-search-input"
            />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {currentBookName && (
              <div style={{ display: 'flex', gap: '4px' }}>
                <button
                  type="button"
                  onClick={() => setScopeFilter('all')}
                  className={`rules-group-pill ${scopeFilter === 'all' ? 'active' : ''}`}
                >
                  {t('common.all', { defaultValue: '全部' })}
                </button>
                <button
                  type="button"
                  onClick={() => setScopeFilter('current')}
                  className={`rules-group-pill ${scopeFilter === 'current' ? 'active' : ''}`}
                >
                  {t('rules.forCurrentBookName', { name: currentBookName, defaultValue: `本书专用 (${currentBookName})` })}
                </button>
              </div>
            )}

            {groups.length > 0 && (
              <select
                value={selectedGroup}
                onChange={e => setSelectedGroup(e.target.value)}
                className="rule-form-input"
                style={{ width: 'auto', height: '34px', padding: '0 8px', fontSize: '12px' }}
              >
                <option value="all">{t('rules.allGroups', { count: rules.length, defaultValue: `全部分组 (${rules.length})` })}</option>
                {groups.map(g => (
                  <option key={g} value={g}>
                    {g} ({rules.filter(r => r.group?.trim() === g).length})
                  </option>
                ))}
              </select>
            )}
          </div>
        </div>

        {/* Rule List */}
        <div style={{ flex: '1 1 auto', overflowY: 'auto', padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: '8px', minHeight: '260px' }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>{t('rules.loadingRules', { defaultValue: '正在加载替换规则...' })}</div>
          ) : filteredRules.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>
              <div>{t('rules.emptyRules', { defaultValue: '暂无匹配的替换规则' })}</div>
              <small style={{ marginTop: '4px', display: 'block' }}>{t('rules.emptyRulesHint', { defaultValue: '点击右上角「+ 新建规则」或「导入」导入规则库' })}</small>
            </div>
          ) : (
            filteredRules.map(rule => (
              <div
                key={rule.id}
                className={`rule-list-card ${(rule.isEnabled ?? true) ? '' : 'disabled'}`}
                style={{ opacity: (rule.isEnabled ?? true) ? 1 : 0.6 }}
              >
                <div className="rule-card-header">
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: 1, minWidth: 0 }}>
                    <span className="rule-name-text">
                      {rule.name || rule.pattern || t('rules.unnamedRule', { defaultValue: '未命名规则' })}
                    </span>
                    <div className="rule-meta-tags">
                      {rule.group && <span className="rule-tag">{rule.group}</span>}
                      {(rule.isRegex ?? true) && <span className="rule-tag tag-regex">Regex</span>}
                      {(rule.replacement ?? '').startsWith('@js:') && <span className="rule-tag tag-js">JS</span>}
                      {rule.scope && <span className="rule-tag tag-scope" title={t('rules.scopePrefix', '范围: {{scope}}', { scope: rule.scope })}>🎯 {rule.scope}</span>}
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                    <span
                      className={`rule-status-badge ${(rule.isEnabled ?? true) ? 'enabled' : 'disabled'}`}
                      onClick={() => handleToggle(rule)}
                    >
                      {(rule.isEnabled ?? true) ? t('rules.ruleEnabled', { defaultValue: '已启用' }) : t('rules.ruleDisabled', { defaultValue: '已停用' })}
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        setEditingRule(rule)
                        setTestText('')
                        setTestResult(null)
                        setIsEditModalOpen(true)
                      }}
                      className="subtle-button"
                      style={{ padding: '3px 6px', height: '26px' }}
                      title={t('common.edit', { defaultValue: '编辑' })}
                    >
                      <Icon name="edit" />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDelete(rule)}
                      className="danger-button"
                      style={{ padding: '3px 6px', height: '26px' }}
                      title={t('common.delete', { defaultValue: '删除' })}
                    >
                      <Icon name="close" />
                    </button>
                  </div>
                </div>

                <div className="rules-code-grid" style={{ gridTemplateColumns: '1fr 1fr', marginTop: '4px' }}>
                  <div className="rules-code-box" style={{ padding: '6px 8px' }}>
                    <span className="rules-code-label">{t('rules.patternLabel', { defaultValue: '匹配:' })}</span>
                    <span className="rules-code-val pattern-val" style={{ fontSize: '11px', maxHeight: '40px' }}>{rule.pattern}</span>
                  </div>
                  <div className="rules-code-box" style={{ padding: '6px 8px' }}>
                    <span className="rules-code-label">{t('rules.replacementLabel', { defaultValue: '替换:' })}</span>
                    <span className="rules-code-val replacement-val" style={{ fontSize: '11px', maxHeight: '40px' }}>{rule.replacement || t('rules.replacementEmpty', { defaultValue: '(清空)' })}</span>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>

        {/* Footer */}
        <footer style={{ padding: '10px 16px max(10px, var(--safe-bottom))', borderTop: '1px solid var(--line)', background: 'var(--surface-muted)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <small style={{ color: 'var(--muted)', fontSize: '11px' }}>
            {t('rules.ruleNoticeHint', { defaultValue: '💡 规则在抓取、离线下载与 TTS 朗读时自动生效' })}
          </small>
          <button
            type="button"
            onClick={onClose}
            className="primary-button"
            style={{ height: '32px', padding: '0 16px', fontSize: '12px' }}
          >
            {t('common.ok', { defaultValue: '完成' })}
          </button>
        </footer>
      </div>

      {/* Edit / Create Rule Dialog */}
      {isEditModalOpen && editingRule && (
        <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setIsEditModalOpen(false)}>
          <div className="rule-modal-card" onClick={e => e.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">
                {editingRule.id ? t('rules.editRule', { defaultValue: '编辑替换净化规则' }) : t('rules.newRuleModalTitle', { defaultValue: '新建替换净化规则' })}
              </h3>
              <button
                type="button"
                onClick={() => setIsEditModalOpen(false)}
                className="icon-btn"
              >
                <Icon name="close" />
              </button>
            </header>

            <form onSubmit={handleSaveRule} style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div className="rule-form-grid-2">
                <div className="rule-form-group">
                  <label>{t('rules.ruleName', { defaultValue: '规则名称' })} *</label>
                  <input
                    type="text"
                    required
                    placeholder={t('rules.ruleName', { defaultValue: '如：反爬混淆修复 / 去广告' })}
                    value={editingRule.name || ''}
                    onChange={e => setEditingRule({ ...editingRule, name: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
                <div className="rule-form-group">
                  <label>{t('rules.ruleGroup', { defaultValue: '分组名称' })}</label>
                  <input
                    type="text"
                    placeholder={t('rules.ruleGroup', { defaultValue: '如：网络反爬 / 错字纠正' })}
                    value={editingRule.group || ''}
                    onChange={e => setEditingRule({ ...editingRule, group: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
              </div>

              <div className="rule-form-group">
                <label>{t('rules.patternLabel', { defaultValue: '匹配模式 (Pattern)' })} *</label>
                <textarea
                  rows={2}
                  required
                  placeholder={t('rules.placeholderPattern', '(大丑|魔男|少萝茜|阁上)')}
                  value={editingRule.pattern || ''}
                  onChange={e => setEditingRule({ ...editingRule, pattern: e.target.value })}
                  className="rule-form-textarea code-font"
                />
              </div>

              <div className="rule-form-group">
                <label>{t('rules.replacementLabel', { defaultValue: '替换为 (Replacement)' })}</label>
                <textarea
                  rows={3}
                  placeholder={t('rules.placeholderReplacement', "@js: const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result]||result;")}
                  value={editingRule.replacement || ''}
                  onChange={e => setEditingRule({ ...editingRule, replacement: e.target.value })}
                  className="rule-form-textarea code-font"
                />
              </div>

              <div className="rule-form-grid-2">
                <div className="rule-form-group">
                  <label>{t('rules.scopeHint', { defaultValue: '作用范围 (Scope，空则全局生效)' })}</label>
                  <input
                    type="text"
                    placeholder={t('rules.placeholderScope', '指定书名、书源URL，以逗号分隔')}
                    value={editingRule.scope || ''}
                    onChange={e => setEditingRule({ ...editingRule, scope: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
                <div className="rule-form-group">
                  <label>{t('rules.excludeScopeHint', { defaultValue: '排除范围 (ExcludeScope)' })}</label>
                  <input
                    type="text"
                    placeholder={t('rules.placeholderExclude', '排除的书名或书源')}
                    value={editingRule.excludeScope || ''}
                    onChange={e => setEditingRule({ ...editingRule, excludeScope: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
              </div>

              {/* Checkboxes */}
              <div className="rule-form-checkboxes">
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.isRegex ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isRegex: e.target.checked })}
                  />
                  <span>{t('rules.regex', { defaultValue: '支持正则表达式' })}</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeContent ?? true}
                    onChange={e => setEditingRule({ ...editingRule, scopeContent: e.target.checked })}
                  />
                  <span>{t('rules.applyToContent', { defaultValue: '作用于正文' })}</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeTitle ?? false}
                    onChange={e => setEditingRule({ ...editingRule, scopeTitle: e.target.checked })}
                  />
                  <span>{t('rules.applyToTitle', { defaultValue: '作用于章节标题' })}</span>
                </label>
              </div>

              {/* Test Sandbox */}
              <div className="rules-code-box" style={{ marginTop: '4px' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '4px' }}>
                  <span className="rules-code-label">🧪 {t('rules.sandboxTitle', { defaultValue: '实时效果调试' })}</span>
                  <button
                    type="button"
                    onClick={handleRunTest}
                    disabled={testing}
                    className="subtle-button"
                    style={{ height: '24px', padding: '0 8px', fontSize: '11px' }}
                  >
                    {testing ? t('rules.sandboxRunning', { defaultValue: '测试中...' }) : t('rules.runSandboxTest', { defaultValue: '运行测试' })}
                  </button>
                </div>
                <textarea
                  rows={2}
                  placeholder={t('rules.sandboxInputPlaceholder', { defaultValue: '在此输入待调试的句子...' })}
                  value={testText}
                  onChange={e => setTestText(e.target.value)}
                  className="rule-form-textarea code-font"
                  style={{ minHeight: '44px' }}
                />
                {testResult && (
                  <div style={{ marginTop: '6px', fontSize: '12px' }}>
                    <div style={{ color: testResult.changed ? '#16a34a' : 'var(--muted)' }}>
                      {t('rules.sandboxResultLabel', { defaultValue: '清洗结果：' })}{testResult.cleanedText}
                    </div>
                    <small style={{ color: 'var(--muted)' }}>
                      {testResult.changed ? t('rules.sandboxHit', { defaultValue: '✅ 已命中并发生替换' }) : t('rules.sandboxNoChange', { defaultValue: '⚪ 未产生改动' })}
                    </small>
                  </div>
                )}
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', paddingTop: '8px', borderTop: '1px solid var(--line)' }}>
                <button
                  type="button"
                  onClick={() => setIsEditModalOpen(false)}
                  className="ghost-button"
                >
                  {t('common.cancel', { defaultValue: '取消' })}
                </button>
                <button
                  type="submit"
                  className="primary-button"
                >
                  {t('common.save', { defaultValue: '保存规则' })}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Import Dialog */}
      {isImportModalOpen && (
        <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setIsImportModalOpen(false)}>
          <div className="rule-modal-card" style={{ maxWidth: '520px' }} onClick={e => e.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">{t('rules.importRules', { defaultValue: '导入替换净化规则' })}</h3>
              <button
                type="button"
                onClick={() => setIsImportModalOpen(false)}
                className="icon-btn"
              >
                <Icon name="close" />
              </button>
            </header>

            <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ display: 'flex', gap: '16px' }}>
                <label className="rule-checkbox-label">
                  <input
                    type="radio"
                    name="modalImportType"
                    checked={importType === 'url'}
                    onChange={() => setImportType('url')}
                  />
                  <span>{t('rules.importUrlTab', { defaultValue: '网络订阅 URL 导入' })}</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="radio"
                    name="modalImportType"
                    checked={importType === 'text'}
                    onChange={() => setImportType('text')}
                  />
                  <span>{t('rules.importJsonTab', { defaultValue: '直接粘贴 JSON 文本' })}</span>
                </label>
              </div>

              {importType === 'url' ? (
                <div className="rule-form-group">
                  <input
                    type="url"
                    placeholder="https://.../replaceRule.json"
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                    className="rule-form-input"
                  />
                </div>
              ) : (
                <div className="rule-form-group">
                  <textarea
                    rows={6}
                    placeholder="[ ... ]"
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                    className="rule-form-textarea code-font"
                  />
                </div>
              )}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', paddingTop: '8px', borderTop: '1px solid var(--line)' }}>
                <button
                  type="button"
                  onClick={() => setIsImportModalOpen(false)}
                  className="ghost-button"
                >
                  {t('common.cancel', { defaultValue: '取消' })}
                </button>
                <button
                  type="button"
                  onClick={handleImport}
                  disabled={importing}
                  className="primary-button"
                >
                  {importing ? t('rules.importing', { defaultValue: '导入中...' }) : t('rules.startImport', { defaultValue: '开始导入' })}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
