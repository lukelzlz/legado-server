import React, { useState, useEffect, useMemo, useCallback } from 'react'
import { api, ReplaceRule, ReplaceRulePreviewResponse } from './api'
import { toast } from './Toast'
import { Icon } from './icons'

export function ReplaceRulesPage() {
  const [rules, setRules] = useState<ReplaceRule[]>([])
  const [loading, setLoading] = useState(false)
  const [query, setQuery] = useState('')
  const [selectedGroup, setSelectedGroup] = useState<string>('all')
  const [selectedRuleId, setSelectedRuleId] = useState<string | null>(null)
  const [notice, setNotice] = useState('')

  // 移动端折叠面板展开状态跟踪
  const [expandedMobileRuleId, setExpandedMobileRuleId] = useState<string | null>(null)

  // 编辑模态框
  const [editingRule, setEditingRule] = useState<Partial<ReplaceRule> | null>(null)
  const [isEditModalOpen, setIsEditModalOpen] = useState(false)

  // 导入模态框
  const [isImportModalOpen, setIsImportModalOpen] = useState(false)
  const [importType, setImportType] = useState<'url' | 'text'>('url')
  const [importInput, setImportInput] = useState('')
  const [importing, setImporting] = useState(false)

  // 实时沙箱测试
  const [testText, setTestText] = useState('')
  const [testResult, setTestResult] = useState<ReplaceRulePreviewResponse | null>(null)
  const [testing, setTesting] = useState(false)

  const loadRules = useCallback(async () => {
    setLoading(true)
    try {
      const data = await api.getReplaceRules()
      setRules(data)
      if (data.length > 0 && !selectedRuleId) {
        setSelectedRuleId(data[0].id)
      }
    } catch (e: any) {
      toast.error(e.message || '加载替换规则失败')
      setNotice('无法载入替换规则')
    } finally {
      setLoading(false)
    }
  }, [selectedRuleId])

  useEffect(() => {
    void loadRules()
  }, [loadRules])

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
      if (query.trim()) {
        const q = query.toLowerCase()
        const nameMatch = (rule.name || '').toLowerCase().includes(q)
        const patternMatch = (rule.pattern || '').toLowerCase().includes(q)
        const replacementMatch = (rule.replacement || '').toLowerCase().includes(q)
        const scopeMatch = rule.scope?.toLowerCase().includes(q) || false
        if (!nameMatch && !patternMatch && !replacementMatch && !scopeMatch) return false
      }
      return true
    })
  }, [rules, selectedGroup, query])

  const handleToggle = async (rule: ReplaceRule) => {
    const newStatus = !(rule.isEnabled ?? true)
    try {
      await api.toggleReplaceRules([rule.id], newStatus)
      setRules(prev => prev.map(r => (r.id === rule.id ? { ...r, isEnabled: newStatus } : r)))
    } catch (e: any) {
      toast.error(e.message || '切换状态失败')
    }
  }

  const handleDelete = async (id: string, name: string) => {
    if (!window.confirm(`确定要删除规则「${name}」吗？`)) return
    try {
      await api.deleteReplaceRule(id)
      toast.success('规则已删除')
      if (selectedRuleId === id) setSelectedRuleId(null)
      if (expandedMobileRuleId === id) setExpandedMobileRuleId(null)
      await loadRules()
    } catch (e: any) {
      toast.error(e.message || '删除规则失败')
    }
  }

  const handleBatchToggle = async (enable: boolean) => {
    const ids = filteredRules.map(r => r.id)
    if (ids.length === 0) return
    try {
      await api.toggleReplaceRules(ids, enable)
      toast.success(`已${enable ? '启用' : '禁用'}当前筛选出的 ${ids.length} 条规则`)
      await loadRules()
    } catch (e: any) {
      toast.error(e.message || '批量操作失败')
    }
  }

  const handleExport = async () => {
    try {
      const jsonStr = JSON.stringify(rules, null, 2)
      const blob = new Blob([jsonStr], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `replace_rules_${new Date().toISOString().slice(0, 10)}.json`
      a.click()
      URL.revokeObjectURL(url)
      toast.success('已导出全部替换规则')
    } catch (e: any) {
      toast.error(e.message || '导出规则失败')
    }
  }

  const handleSaveRule = async (ruleData: Partial<ReplaceRule>) => {
    if (!ruleData.pattern?.trim()) {
      toast.error('匹配模式不能为空')
      return
    }
    try {
      if (ruleData.id) {
        await api.updateReplaceRule(ruleData.id, ruleData)
        toast.success('规则已更新')
      } else {
        await api.createReplaceRule(ruleData)
        toast.success('规则已创建')
      }
      setIsEditModalOpen(false)
      setEditingRule(null)
      await loadRules()
    } catch (e: any) {
      toast.error(e.message || '保存规则失败')
    }
  }

  const handleImport = async () => {
    if (!importInput.trim()) {
      toast.error('请输入订阅地址或规则内容')
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
      toast.success(`导入成功：新增 ${res.imported} 条，更新 ${res.updated} 条，跳过 ${res.skipped} 条`)
      setIsImportModalOpen(false)
      setImportInput('')
      await loadRules()
    } catch (e: any) {
      toast.error(e.message || '导入失败，请检查格式或网络连接')
    } finally {
      setImporting(false)
    }
  }

  const handleRunTest = async (ruleOverride?: Partial<ReplaceRule>) => {
    if (!testText.trim()) {
      toast.error('请输入要测试的文本')
      return
    }
    setTesting(true)
    try {
      const res = await api.previewReplaceRule({
        text: testText,
        rule: ruleOverride as ReplaceRule | undefined,
      })
      setTestResult(res)
    } catch (e: any) {
      toast.error(e.message || '测试失败')
    } finally {
      setTesting(false)
    }
  }

  const selectedRule = useMemo(() => {
    return rules.find(r => r.id === selectedRuleId) || null
  }, [rules, selectedRuleId])

  const openCreateModal = () => {
    setEditingRule({
      name: '',
      group: selectedGroup !== 'all' ? selectedGroup : '',
      pattern: '',
      replacement: '',
      isRegex: true,
      scope: undefined,
      excludeScope: undefined,
      scopeTitle: false,
      scopeContent: true,
      isEnabled: true,
      order: 0,
    })
    setIsEditModalOpen(true)
  }

  return (
    <main className="rules-page-container">
      {/* 侧边栏（桌面端列表 / 移动端折叠卡片列表） */}
      <aside className="rules-sidebar">
        <header className="rules-sidebar-header">
          <div className="rules-sidebar-title">
            <Icon name="sliders" />
            <span>替换净化规则</span>
            <span className="rules-count-badge">{rules.length}</span>
          </div>
          <button
            type="button"
            className="primary-button"
            style={{ padding: '4px 10px', height: '28px', fontSize: '12px' }}
            onClick={openCreateModal}
          >
            <Icon name="plus" />
            <span>新建规则</span>
          </button>
        </header>

        {/* 工具条：搜索与快捷按钮 */}
        <div className="rules-toolbar">
          <div className="rules-search-wrap">
            <Icon name="search" />
            <input
              className="rules-search-input"
              placeholder="搜索规则名 / 正则 / 替换词"
              value={query}
              onChange={e => setQuery(e.target.value)}
            />
          </div>

          <div className="rules-action-row">
            <button
              type="button"
              className="subtle-button"
              onClick={() => setIsImportModalOpen(true)}
            >
              <Icon name="upload" />
              <span>导入订阅</span>
            </button>
            <button
              type="button"
              className="ghost-button"
              onClick={() => handleBatchToggle(true)}
            >
              全部启用
            </button>
            <button
              type="button"
              className="ghost-button"
              onClick={() => handleBatchToggle(false)}
            >
              全部禁用
            </button>
            <button
              type="button"
              className="ghost-button"
              onClick={handleExport}
              title="导出全部"
            >
              <Icon name="download" />
            </button>
          </div>
        </div>

        {/* 分组筛选胶囊 */}
        <div className="rules-group-bar">
          <button
            type="button"
            className={`rules-group-pill ${selectedGroup === 'all' ? 'active' : ''}`}
            onClick={() => setSelectedGroup('all')}
          >
            全部 ({rules.length})
          </button>
          {groups.map(g => (
            <button
              key={g}
              type="button"
              className={`rules-group-pill ${selectedGroup === g ? 'active' : ''}`}
              onClick={() => setSelectedGroup(g)}
            >
              {g} ({rules.filter(r => (r.group?.trim() || '未分组') === g).length})
            </button>
          ))}
        </div>

        {notice && <p className="sidebar-notice" style={{ margin: '8px 12px 0' }}>{notice}</p>}

        {/* 规则列表 */}
        <nav className="rules-list">
          {loading && <div style={{ padding: '24px', textAlign: 'center', color: 'var(--muted)', fontSize: '13px' }}>载入中...</div>}
          {!loading && filteredRules.length === 0 && (
            <div style={{ padding: '32px 16px', textAlign: 'center', color: 'var(--muted)', fontSize: '13px' }}>
              暂无匹配的替换规则
            </div>
          )}

          {filteredRules.map(rule => {
            const isSelected = selectedRuleId === rule.id
            const isMobileOpen = expandedMobileRuleId === rule.id

            return (
              <React.Fragment key={rule.id}>
                {/* 桌面端卡片项 */}
                <div
                  className={`rule-list-card ${isSelected ? 'selected' : ''}`}
                  onClick={() => {
                    setSelectedRuleId(rule.id)
                    setExpandedMobileRuleId(isMobileOpen ? null : rule.id)
                  }}
                >
                  <div className="rule-card-header">
                    <span className="rule-name-text" style={{ opacity: (rule.isEnabled ?? true) ? 1 : 0.5 }}>
                      {rule.name || rule.pattern || '未命名规则'}
                    </span>
                    <span
                      className={`rule-status-badge ${(rule.isEnabled ?? true) ? 'enabled' : 'disabled'}`}
                      onClick={e => {
                        e.stopPropagation()
                        void handleToggle(rule)
                      }}
                      title="点击切换启用状态"
                    >
                      {(rule.isEnabled ?? true) ? '已启用' : '已停用'}
                    </span>
                  </div>
                  <div className="rule-meta-tags">
                    <span className="rule-tag">{rule.group || '未分组'}</span>
                    {(rule.isRegex ?? true) && <span className="rule-tag tag-regex">Regex</span>}
                    {(rule.replacement ?? '').startsWith('@js:') && <span className="rule-tag tag-js">JS</span>}
                    {rule.scope && (
                      <span className="rule-tag tag-scope" title={`生效范围: ${rule.scope}`}>
                        🎯 {rule.scope}
                      </span>
                    )}
                  </div>
                </div>

                {/* 移动端专属展开折叠面板 */}
                {isMobileOpen && (
                  <div className="rule-accordion-item open" style={{ display: 'none' /* 由媒体查询或类控制 */ }}>
                    <div className="rule-accordion-content">
                      <div className="rules-code-grid" style={{ gridTemplateColumns: '1fr' }}>
                        <div className="rules-code-box">
                          <span className="rules-code-label">匹配模式 (Pattern)</span>
                          <span className="rules-code-val pattern-val">{rule.pattern}</span>
                        </div>
                        <div className="rules-code-box">
                          <span className="rules-code-label">替换内容 (Replacement)</span>
                          <span className="rules-code-val replacement-val">
                            {rule.replacement || '(空 / 剔除)'}
                          </span>
                        </div>
                      </div>

                      {rule.scope && (
                        <div className="rules-scope-info">
                          <span>🎯 生效范围:</span>
                          <span className="rules-scope-val">{rule.scope}</span>
                        </div>
                      )}

                      <div className="rule-accordion-actions">
                        <button
                          type="button"
                          className="ghost-button"
                          onClick={() => handleToggle(rule)}
                        >
                          {(rule.isEnabled ?? true) ? '停用' : '启用'}
                        </button>
                        <button
                          type="button"
                          className="subtle-button"
                          onClick={() => {
                            setEditingRule(rule)
                            setIsEditModalOpen(true)
                          }}
                        >
                          <Icon name="edit" />
                          <span>编辑</span>
                        </button>
                        <button
                          type="button"
                          className="danger-button"
                          onClick={() => handleDelete(rule.id, rule.name)}
                        >
                          <Icon name="close" />
                          <span>删除</span>
                        </button>
                      </div>
                    </div>
                  </div>
                )}
              </React.Fragment>
            )
          })}
        </nav>

        {/* 移动端底部沙箱区域 */}
        <div className="mobile-sandbox-section" style={{ display: 'none' /* 窄屏媒体查询中展示 */ }}>
          <div className="rules-sandbox-header">
            <Icon name="sliders" />
            <span>沙箱清洗测试</span>
          </div>
          <div style={{ marginTop: '10px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <textarea
              className="rules-sandbox-textarea"
              rows={3}
              placeholder="输入测试小说混淆文本..."
              value={testText}
              onChange={e => setTestText(e.target.value)}
            />
            <button
              type="button"
              className="primary-button"
              disabled={testing}
              onClick={() => handleRunTest(selectedRule || undefined)}
            >
              {testing ? '测试中...' : '运行沙箱测试'}
            </button>
            {testResult && (
              <div className={`rules-sandbox-result ${testResult.changed ? 'changed' : 'unchanged'}`}>
                <div>{testResult.cleanedText}</div>
                <div className="rules-result-status">
                  {testResult.changed ? '✅ 规则已命中替换' : '⚪ 未产生改动'}
                </div>
              </div>
            )}
          </div>
        </div>
      </aside>

      {/* 桌面端主展示与沙箱区域 */}
      <section className="rules-content-area">
        <header className="page-title">
          <div>
            <span className="section-kicker">阅读服务器</span>
            <h1>替换净化规则</h1>
            <p>净化网页抓取正文中的反爬文字对调、乱码错别字、引流广告与多余排版。</p>
          </div>
          <div className="page-title-actions">
            <button
              type="button"
              className="primary-button"
              onClick={openCreateModal}
            >
              <Icon name="plus" />
              <span>新建规则</span>
            </button>
          </div>
        </header>

        {/* 选中规则详情卡片 */}
        {selectedRule ? (
          <div className="rules-detail-card">
            <div className="rules-detail-header">
              <div className="rules-detail-title-group">
                <h2>{selectedRule.name || selectedRule.pattern || '未命名规则'}</h2>
                <div className="rule-meta-tags">
                  <span className="rule-tag">分组: {selectedRule.group || '未分组'}</span>
                  <span className={`rule-status-badge ${(selectedRule.isEnabled ?? true) ? 'enabled' : 'disabled'}`}>
                    {(selectedRule.isEnabled ?? true) ? '✓ 规则已启用' : '⚪ 规则已禁用'}
                  </span>
                  {(selectedRule.isRegex ?? true) && <span className="rule-tag tag-regex">正则表达式</span>}
                  {(selectedRule.replacement ?? '').startsWith('@js:') && <span className="rule-tag tag-js">JS 沙箱</span>}
                </div>
              </div>
              <div className="rules-detail-actions">
                <button
                  type="button"
                  className="ghost-button"
                  onClick={() => handleToggle(selectedRule)}
                >
                  {(selectedRule.isEnabled ?? true) ? '停用' : '启用'}
                </button>
                <button
                  type="button"
                  className="subtle-button"
                  onClick={() => {
                    setEditingRule(selectedRule)
                    setIsEditModalOpen(true)
                  }}
                >
                  <Icon name="edit" />
                  <span>编辑</span>
                </button>
                <button
                  type="button"
                  className="danger-button"
                  onClick={() => handleDelete(selectedRule.id, selectedRule.name)}
                >
                  <Icon name="close" />
                  <span>删除</span>
                </button>
              </div>
            </div>

            <div className="rules-code-grid">
              <div className="rules-code-box">
                <span className="rules-code-label">匹配模式 (Pattern)</span>
                <span className="rules-code-val pattern-val">{selectedRule.pattern}</span>
              </div>
              <div className="rules-code-box">
                <span className="rules-code-label">替换内容 (Replacement)</span>
                <span className="rules-code-val replacement-val">
                  {selectedRule.replacement || '(空 / 剔除)'}
                </span>
              </div>
            </div>

            {selectedRule.scope && (
              <div className="rules-scope-info">
                <span>🎯 生效范围:</span>
                <span className="rules-scope-val">{selectedRule.scope}</span>
              </div>
            )}
          </div>
        ) : (
          <div className="source-detail-card" style={{ textAlign: 'center', padding: '48px 20px', color: 'var(--muted)' }}>
            👈 在左侧选择一条规则查看详情，或点击「新建规则」配置错字反爬清洗
          </div>
        )}

        {/* 实时沙箱调试区域 */}
        <div className="rules-sandbox-card">
          <div className="rules-sandbox-header">
            <Icon name="sliders" />
            <span>规则沙箱测试与实时效果预览</span>
          </div>

          <div className="rules-sandbox-grid">
            <div className="rules-sandbox-col">
              <span className="rules-sandbox-label">输入测试原文本（可粘贴小说混淆段落）：</span>
              <textarea
                className="rules-sandbox-textarea"
                rows={4}
                placeholder="例如：大丑阁上，少萝茜心外无点刺痛，魔男们是是会把强大的魔男同族当做仆从军..."
                value={testText}
                onChange={e => setTestText(e.target.value)}
              />
              <div style={{ display: 'flex', justifyContent: 'flex-start', marginTop: '6px' }}>
                <button
                  type="button"
                  className="subtle-button"
                  disabled={testing}
                  onClick={() => handleRunTest(selectedRule || undefined)}
                >
                  {testing ? '测试中...' : '运行沙箱测试'}
                </button>
              </div>
            </div>

            <div className="rules-sandbox-col">
              <span className="rules-sandbox-label">清洗求值结果：</span>
              <div className={`rules-sandbox-result ${testResult?.changed ? 'changed' : 'unchanged'}`}>
                {testResult ? (
                  <>
                    <div style={{ wordBreak: 'break-all' }}>{testResult.cleanedText}</div>
                    <div className="rules-result-status">
                      {testResult.changed ? '✅ 已命中并发生替换' : '⚪ 未产生改动'}
                    </div>
                  </>
                ) : (
                  <span style={{ color: 'var(--muted)', alignSelf: 'center', margin: 'auto' }}>
                    点击「运行沙箱测试」即可预览清洗效果
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 编辑/新建规则弹窗 */}
      {isEditModalOpen && editingRule && (
        <div className="modal-backdrop" onClick={() => setIsEditModalOpen(false)}>
          <div className="rule-modal-card" onClick={e => e.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">{editingRule.id ? '编辑替换规则' : '新建替换规则'}</h3>
              <button type="button" className="icon-btn" onClick={() => setIsEditModalOpen(false)}>
                <Icon name="close" />
              </button>
            </header>

            <div className="source-login-body" style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div className="rule-form-grid-2">
                <div className="rule-form-group">
                  <label>规则名称</label>
                  <input
                    className="rule-form-input"
                    placeholder="如：反义词错字清洗"
                    value={editingRule.name || ''}
                    onChange={e => setEditingRule({ ...editingRule, name: e.target.value })}
                  />
                </div>
                <div className="rule-form-group">
                  <label>分组名称</label>
                  <input
                    className="rule-form-input"
                    placeholder="如：起点反爬"
                    value={editingRule.group || ''}
                    onChange={e => setEditingRule({ ...editingRule, group: e.target.value })}
                  />
                </div>
              </div>

              <div className="rule-form-group">
                <label>匹配正则 / 关键词 (Pattern)</label>
                <input
                  className="rule-form-input code-font"
                  placeholder="如：(大丑|魔男|少萝茜|阁上)"
                  value={editingRule.pattern || ''}
                  onChange={e => setEditingRule({ ...editingRule, pattern: e.target.value })}
                />
              </div>

              <div className="rule-form-group">
                <label>替换内容 (可为文本、正则组 $1 或 @js: 字典翻转脚本)</label>
                <textarea
                  className="rule-form-textarea code-font"
                  rows={4}
                  placeholder="@js:const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result] || result;"
                  value={editingRule.replacement || ''}
                  onChange={e => setEditingRule({ ...editingRule, replacement: e.target.value })}
                />
              </div>

              <div className="rule-form-grid-2">
                <div className="rule-form-group">
                  <label>生效书名 / 作用域 (留空则全局生效)</label>
                  <input
                    className="rule-form-input"
                    placeholder="如：宅魔女,诡秘之主 (逗号分隔)"
                    value={editingRule.scope || ''}
                    onChange={e => setEditingRule({ ...editingRule, scope: e.target.value || undefined })}
                  />
                </div>
                <div className="rule-form-group">
                  <label>排除范围 (Exclude Scope)</label>
                  <input
                    className="rule-form-input"
                    placeholder="如：凡人修仙传"
                    value={editingRule.excludeScope || ''}
                    onChange={e => setEditingRule({ ...editingRule, excludeScope: e.target.value || undefined })}
                  />
                </div>
              </div>

              <div className="rule-form-checkboxes">
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.isRegex ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isRegex: e.target.checked })}
                  />
                  <span>正则表达式</span>
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
                <label className="rule-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editingRule.isEnabled ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isEnabled: e.target.checked })}
                  />
                  <span>立即启用</span>
                </label>
              </div>
            </div>

            <footer style={{ padding: '12px 20px max(12px, var(--safe-bottom))', display: 'flex', justifyContent: 'flex-end', gap: '8px', borderTop: '1px solid var(--line)' }}>
              <button type="button" className="ghost-button" onClick={() => setIsEditModalOpen(false)}>
                取消
              </button>
              <button type="button" className="primary-button" onClick={() => handleSaveRule(editingRule)}>
                保存规则
              </button>
            </footer>
          </div>
        </div>
      )}

      {/* 导入订阅弹窗 */}
      {isImportModalOpen && (
        <div className="modal-backdrop" onClick={() => setIsImportModalOpen(false)}>
          <div className="rule-modal-card" style={{ maxWidth: '520px' }} onClick={e => e.stopPropagation()}>
            <header className="source-login-header">
              <h3 className="source-login-title">导入替换规则</h3>
              <button type="button" className="icon-btn" onClick={() => setIsImportModalOpen(false)}>
                <Icon name="close" />
              </button>
            </header>

            <div className="source-login-body" style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ display: 'flex', gap: '16px' }}>
                <label className="rule-checkbox-label">
                  <input
                    type="radio"
                    name="importType"
                    checked={importType === 'url'}
                    onChange={() => setImportType('url')}
                  />
                  <span>网络订阅 URL 导入</span>
                </label>
                <label className="rule-checkbox-label">
                  <input
                    type="radio"
                    name="importType"
                    checked={importType === 'text'}
                    onChange={() => setImportType('text')}
                  />
                  <span>直接粘贴 JSON 文本</span>
                </label>
              </div>

              {importType === 'url' ? (
                <div className="rule-form-group">
                  <input
                    className="rule-form-input"
                    placeholder="https://example.com/replace_rules.json"
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                  />
                </div>
              ) : (
                <div className="rule-form-group">
                  <textarea
                    className="rule-form-textarea code-font"
                    rows={6}
                    placeholder="粘贴 Legado 标准替换规则 JSON 数组..."
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                  />
                </div>
              )}
            </div>

            <footer style={{ padding: '12px 20px max(12px, var(--safe-bottom))', display: 'flex', justifyContent: 'flex-end', gap: '8px', borderTop: '1px solid var(--line)' }}>
              <button type="button" className="ghost-button" onClick={() => setIsImportModalOpen(false)}>
                取消
              </button>
              <button type="button" className="primary-button" disabled={importing} onClick={handleImport}>
                {importing ? '导入中...' : '开始导入'}
              </button>
            </footer>
          </div>
        </div>
      )}
    </main>
  )
}
