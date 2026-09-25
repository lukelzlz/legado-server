import React, { useState, useEffect, useMemo } from 'react'
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
      toast.error(e.message || '加载替换规则失败')
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
      if (selectedGroup !== 'all' && (rule.group?.trim() || '未分组') !== selectedGroup) {
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
  }, [rules, selectedGroup, scopeFilter, searchQuery, currentBookName, currentSourceUrl])

  const handleToggle = async (rule: ReplaceRule) => {
    const newStatus = !(rule.isEnabled ?? true)
    try {
      await api.toggleReplaceRules([rule.id], newStatus)
      setRules(prev => prev.map(r => (r.id === rule.id ? { ...r, isEnabled: newStatus } : r)))
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || '切换状态失败')
    }
  }

  const handleDelete = async (rule: ReplaceRule) => {
    if (!confirm(`确定删除规则「${rule.name || rule.pattern}」吗？`)) return
    try {
      await api.deleteReplaceRule(rule.id)
      setRules(prev => prev.filter(r => r.id !== rule.id))
      toast.success('删除成功')
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || '删除失败')
    }
  }

  const handleSaveRule = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!editingRule || !editingRule.pattern?.trim()) {
      toast.error('匹配模式不能为空')
      return
    }
    try {
      if (editingRule.id) {
        const updated = await api.updateReplaceRule(editingRule.id, editingRule)
        setRules(prev => prev.map(r => (r.id === updated.id ? updated : r)))
        toast.success('规则已更新')
      } else {
        const created = await api.createReplaceRule(editingRule)
        setRules(prev => [created, ...prev])
        toast.success('规则创建成功')
      }
      setIsEditModalOpen(false)
      setEditingRule(null)
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || '保存规则失败')
    }
  }

  const handleImport = async () => {
    if (!importInput.trim()) {
      toast.error('请输入导入内容或链接')
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
      toast.success(`导入完成：新增 ${res.imported} 条，更新 ${res.updated} 条，跳过 ${res.skipped} 条`)
      setIsImportModalOpen(false)
      setImportInput('')
      await loadRules()
      onRulesChanged?.()
    } catch (e: any) {
      toast.error(e.message || '导入失败')
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
    toast.success('已导出替换规则文件')
  }

  const handleRunTest = async () => {
    if (!testText.trim()) {
      toast.error('请输入待测试的文本')
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
      toast.error(e.message || '测试失败')
    } finally {
      setTesting(false)
    }
  }

  const openAddModal = (forCurrentBook = false) => {
    setEditingRule({
      name: forCurrentBook && currentBookName ? `${currentBookName}-净化规则` : '',
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
            <span>替换净化规则管理</span>
            <span className="rules-count-badge">共 {rules.length} 条</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              onClick={() => openAddModal(false)}
              className="primary-button"
              style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
            >
              <Icon name="plus" />
              <span>新建规则</span>
            </button>
            {currentBookName && (
              <button
                type="button"
                onClick={() => openAddModal(true)}
                className="subtle-button"
                style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
              >
                <span>当前书专用</span>
              </button>
            )}
            <button
              type="button"
              onClick={() => setIsImportModalOpen(true)}
              className="subtle-button"
              style={{ height: '30px', padding: '0 10px', fontSize: '12px' }}
            >
              <Icon name="upload" />
              <span>导入</span>
            </button>
            <button
              type="button"
              onClick={handleExport}
              className="ghost-button"
              style={{ height: '30px', padding: '0 8px', fontSize: '12px' }}
              title="导出全部"
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
              placeholder="搜索规则名、正则、替换词..."
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
                  全部
                </button>
                <button
                  type="button"
                  onClick={() => setScopeFilter('current')}
                  className={`rules-group-pill ${scopeFilter === 'current' ? 'active' : ''}`}
                >
                  本书专用 ({currentBookName})
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
                <option value="all">全部分组 ({rules.length})</option>
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
            <div style={{ textAlign: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>正在加载替换规则...</div>
          ) : filteredRules.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '36px', color: 'var(--muted)', fontSize: '13px' }}>
              <div>暂无匹配的替换规则</div>
              <small style={{ marginTop: '4px', display: 'block' }}>点击右上角「+ 新建规则」或「导入」导入规则库</small>
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
                      {rule.name || rule.pattern || '未命名规则'}
                    </span>
                    <div className="rule-meta-tags">
                      {rule.group && <span className="rule-tag">{rule.group}</span>}
                      {(rule.isRegex ?? true) && <span className="rule-tag tag-regex">正则</span>}
                      {(rule.replacement ?? '').startsWith('@js:') && <span className="rule-tag tag-js">JS</span>}
                      {rule.scope && <span className="rule-tag tag-scope" title={`范围: ${rule.scope}`}>🎯 {rule.scope}</span>}
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                    <span
                      className={`rule-status-badge ${(rule.isEnabled ?? true) ? 'enabled' : 'disabled'}`}
                      onClick={() => handleToggle(rule)}
                    >
                      {(rule.isEnabled ?? true) ? '已启用' : '已停用'}
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
                      title="编辑"
                    >
                      <Icon name="edit" />
                    </button>
                    <button
                      type="button"
                      onClick={() => handleDelete(rule)}
                      className="danger-button"
                      style={{ padding: '3px 6px', height: '26px' }}
                      title="删除"
                    >
                      <Icon name="close" />
                    </button>
                  </div>
                </div>

                <div className="rules-code-grid" style={{ gridTemplateColumns: '1fr 1fr', marginTop: '4px' }}>
                  <div className="rules-code-box" style={{ padding: '6px 8px' }}>
                    <span className="rules-code-label">匹配:</span>
                    <span className="rules-code-val pattern-val" style={{ fontSize: '11px', maxHeight: '40px' }}>{rule.pattern}</span>
                  </div>
                  <div className="rules-code-box" style={{ padding: '6px 8px' }}>
                    <span className="rules-code-label">替换:</span>
                    <span className="rules-code-val replacement-val" style={{ fontSize: '11px', maxHeight: '40px' }}>{rule.replacement || '(清空)'}</span>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>

        {/* Footer */}
        <footer style={{ padding: '10px 16px max(10px, var(--safe-bottom))', borderTop: '1px solid var(--line)', background: 'var(--surface-muted)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <small style={{ color: 'var(--muted)', fontSize: '11px' }}>
            💡 规则在抓取、离线下载与 TTS 朗读时自动生效
          </small>
          <button
            type="button"
            onClick={onClose}
            className="primary-button"
            style={{ height: '32px', padding: '0 16px', fontSize: '12px' }}
          >
            完成
          </button>
        </footer>
      </div>

      {/* Edit / Create Rule Dialog */}
      {isEditModalOpen && editingRule && (
        <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setIsEditModalOpen(false)}>
          <div className="rule-modal-card" onClick={e => e.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">
                {editingRule.id ? '编辑替换净化规则' : '新建替换净化规则'}
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
                  <label>规则名称 *</label>
                  <input
                    type="text"
                    required
                    placeholder="如：反爬混淆修复 / 去广告"
                    value={editingRule.name || ''}
                    onChange={e => setEditingRule({ ...editingRule, name: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
                <div className="rule-form-group">
                  <label>分组名称</label>
                  <input
                    type="text"
                    placeholder="如：网络反爬 / 错字纠正"
                    value={editingRule.group || ''}
                    onChange={e => setEditingRule({ ...editingRule, group: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
              </div>

              <div className="rule-form-group">
                <label>匹配模式 (Pattern) *</label>
                <textarea
                  rows={2}
                  required
                  placeholder="要替换的文本或正则表达式，如：(大丑|魔男|少萝茜|阁上)"
                  value={editingRule.pattern || ''}
                  onChange={e => setEditingRule({ ...editingRule, pattern: e.target.value })}
                  className="rule-form-textarea code-font"
                />
              </div>

              <div className="rule-form-group">
                <label>替换为 (Replacement)</label>
                <textarea
                  rows={3}
                  placeholder="替换内容，支持 $1 捕获组或 @js: 脚本。如：@js: const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result]||result;"
                  value={editingRule.replacement || ''}
                  onChange={e => setEditingRule({ ...editingRule, replacement: e.target.value })}
                  className="rule-form-textarea code-font"
                />
              </div>

              <div className="rule-form-grid-2">
                <div className="rule-form-group">
                  <label>作用范围 (Scope，空则全局生效)</label>
                  <input
                    type="text"
                    placeholder="指定书名、书源URL，以逗号分隔"
                    value={editingRule.scope || ''}
                    onChange={e => setEditingRule({ ...editingRule, scope: e.target.value })}
                    className="rule-form-input"
                  />
                </div>
                <div className="rule-form-group">
                  <label>排除范围 (ExcludeScope)</label>
                  <input
                    type="text"
                    placeholder="排除的书名或书源"
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
                  <span>支持正则表达式</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeContent ?? true}
                    onChange={e => setEditingRule({ ...editingRule, scopeContent: e.target.checked })}
                  />
                  <span>作用于正文</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeTitle ?? false}
                    onChange={e => setEditingRule({ ...editingRule, scopeTitle: e.target.checked })}
                  />
                  <span>作用于章节标题</span>
                </label>
              </div>

              {/* Test Sandbox */}
              <div className="rules-code-box" style={{ marginTop: '4px' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '4px' }}>
                  <span className="rules-code-label">🧪 实时效果调试</span>
                  <button
                    type="button"
                    onClick={handleRunTest}
                    disabled={testing}
                    className="subtle-button"
                    style={{ height: '24px', padding: '0 8px', fontSize: '11px' }}
                  >
                    {testing ? '测试中...' : '运行测试'}
                  </button>
                </div>
                <textarea
                  rows={2}
                  placeholder="在此输入待调试的句子..."
                  value={testText}
                  onChange={e => setTestText(e.target.value)}
                  className="rule-form-textarea code-font"
                  style={{ minHeight: '44px' }}
                />
                {testResult && (
                  <div style={{ marginTop: '6px', fontSize: '12px' }}>
                    <div style={{ color: testResult.changed ? '#16a34a' : 'var(--muted)' }}>
                      清洗结果：{testResult.cleanedText}
                    </div>
                    <small style={{ color: 'var(--muted)' }}>
                      {testResult.changed ? '✅ 已命中并发生替换' : '⚪ 未产生改动'}
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
                  取消
                </button>
                <button
                  type="submit"
                  className="primary-button"
                >
                  保存规则
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
              <h3 className="source-login-title">导入替换净化规则</h3>
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
                  <span>网络订阅 URL 导入</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="radio"
                    name="modalImportType"
                    checked={importType === 'text'}
                    onChange={() => setImportType('text')}
                  />
                  <span>直接粘贴 JSON 文本</span>
                </label>
              </div>

              {importType === 'url' ? (
                <div className="rule-form-group">
                  <label>规则订阅地址</label>
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
                  <label>规则 JSON 内容</label>
                  <textarea
                    rows={6}
                    placeholder="粘贴 [...] 或 { data: [...] } 格式的 JSON"
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
                  取消
                </button>
                <button
                  type="button"
                  onClick={handleImport}
                  disabled={importing}
                  className="primary-button"
                >
                  {importing ? '导入中...' : '开始导入'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
