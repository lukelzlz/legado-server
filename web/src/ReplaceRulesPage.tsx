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
    } catch (e: any) {
      toast.error(e.message || '加载替换规则失败')
      setNotice('无法载入替换规则')
    } finally {
      setLoading(false)
    }
  }, [])

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
        const nameMatch = rule.name.toLowerCase().includes(q)
        const patternMatch = rule.pattern.toLowerCase().includes(q)
        const replacementMatch = rule.replacement.toLowerCase().includes(q)
        const scopeMatch = rule.scope?.toLowerCase().includes(q) || false
        if (!nameMatch && !patternMatch && !replacementMatch && !scopeMatch) return false
      }
      return true
    })
  }, [rules, selectedGroup, query])

  const handleToggle = async (rule: ReplaceRule) => {
    const newStatus = !rule.isEnabled
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

  return (
    <main className="sources-page replace-rules-page">
      {/* 侧边栏 */}
      <aside className="source-sidebar">
        <div className="source-sidebar-heading">
          <span>替换净化规则</span>
          <small>{rules.length}</small>
        </div>

        <div className="source-filter">
          <Icon name="search" />
          <input
            placeholder="搜索规则名 / 正则 / 替换词"
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
        </div>

        <div style={{ display: 'flex', gap: '8px', padding: '0 12px 8px 12px' }}>
          <button
            type="button"
            className="subtle-button"
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => {
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
            }}
          >
            <Icon name="plus" />
            <span>新建规则</span>
          </button>
          <button
            type="button"
            className="subtle-button"
            style={{ flex: 1, justifyContent: 'center' }}
            onClick={() => setIsImportModalOpen(true)}
          >
            <Icon name="upload" />
            <span>导入订阅</span>
          </button>
        </div>

        <div style={{ padding: '0 12px 8px 12px', display: 'flex', gap: '6px' }}>
          <button
            type="button"
            className="ghost-button"
            style={{ flex: 1, fontSize: '11px', padding: '4px 6px' }}
            onClick={() => handleBatchToggle(true)}
          >
            全部启用
          </button>
          <button
            type="button"
            className="ghost-button"
            style={{ flex: 1, fontSize: '11px', padding: '4px 6px' }}
            onClick={() => handleBatchToggle(false)}
          >
            全部禁用
          </button>
          <button
            type="button"
            className="ghost-button"
            style={{ fontSize: '11px', padding: '4px 6px' }}
            onClick={handleExport}
            title="导出全部规则"
          >
            <Icon name="download" />
          </button>
        </div>

        {/* 分组筛选标签 */}
        <div style={{ padding: '4px 12px', display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
          <button
            type="button"
            className={`pill-badge ${selectedGroup === 'all' ? 'active' : ''}`}
            style={{
              cursor: 'pointer',
              background: selectedGroup === 'all' ? 'var(--primary-color, #4f46e5)' : 'rgba(255,255,255,0.06)',
              color: '#fff',
              border: 'none',
              padding: '2px 8px',
              borderRadius: '12px',
              fontSize: '11px',
            }}
            onClick={() => setSelectedGroup('all')}
          >
            全部 ({rules.length})
          </button>
          {groups.map(g => (
            <button
              key={g}
              type="button"
              className={`pill-badge ${selectedGroup === g ? 'active' : ''}`}
              style={{
                cursor: 'pointer',
                background: selectedGroup === g ? 'var(--primary-color, #4f46e5)' : 'rgba(255,255,255,0.06)',
                color: '#fff',
                border: 'none',
                padding: '2px 8px',
                borderRadius: '12px',
                fontSize: '11px',
              }}
              onClick={() => setSelectedGroup(g)}
            >
              {g} ({rules.filter(r => (r.group?.trim() || '未分组') === g).length})
            </button>
          ))}
        </div>

        {notice && <p className="sidebar-notice">{notice}</p>}

        {/* 规则列表 */}
        <nav className="source-list" style={{ marginTop: '8px' }}>
          {loading && <div style={{ padding: '16px', textAlign: 'center', color: '#888' }}>载入中...</div>}
          {!loading && filteredRules.length === 0 && (
            <div style={{ padding: '24px 16px', textAlign: 'center', color: '#888' }}>
              暂无匹配的替换规则
            </div>
          )}
          {filteredRules.map(rule => (
            <button
              key={rule.id}
              type="button"
              className={`source-list-item ${selectedRuleId === rule.id ? 'selected' : ''}`}
              onClick={() => setSelectedRuleId(rule.id)}
            >
              <div className="source-list-item-title">
                <span style={{ opacity: rule.isEnabled ? 1 : 0.45 }}>
                  {rule.name || rule.pattern}
                </span>
                <span
                  className={`pill-badge ${rule.isEnabled ? 'active' : ''}`}
                  style={{
                    fontSize: '10px',
                    padding: '1px 6px',
                    borderRadius: '10px',
                    background: rule.isEnabled ? '#10b981' : '#6b7280',
                    color: '#fff',
                  }}
                  onClick={e => {
                    e.stopPropagation()
                    void handleToggle(rule)
                  }}
                >
                  {rule.isEnabled ? '已启用' : '已停用'}
                </span>
              </div>
              <div style={{ display: 'flex', gap: '6px', fontSize: '11px', color: '#888' }}>
                <small>{rule.group || '未分组'}</small>
                {rule.scope && <small title={`作用域: ${rule.scope}`}>· 🎯 {rule.scope}</small>}
                {rule.replacement.startsWith('@js:') && <small style={{ color: '#ec4899' }}>· JS</small>}
              </div>
            </button>
          ))}
        </nav>
      </aside>

      {/* 主内容区域 */}
      <section className="sources-content">
        <header className="page-title">
          <div>
            <span className="section-kicker">阅读服务器</span>
            <h1>替换净化规则</h1>
            <p>净化网页抓取正文中的反爬文字对调、乱码错别字、引流广告与多余排版。</p>
          </div>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              type="button"
              className="primary-button"
              onClick={() => {
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
              }}
            >
              <Icon name="plus" />
              <span>新建规则</span>
            </button>
          </div>
        </header>

        {/* 详情与沙箱调试卡片 */}
        {selectedRule ? (
          <div className="source-detail-card" style={{ background: 'rgba(255,255,255,0.03)', borderRadius: '12px', padding: '20px', border: '1px solid rgba(255,255,255,0.08)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '16px' }}>
              <div>
                <h2 style={{ margin: '0 0 6px 0', fontSize: '18px' }}>{selectedRule.name || '未命名规则'}</h2>
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                  <span className="pill-badge" style={{ background: 'rgba(255,255,255,0.08)', padding: '2px 8px', borderRadius: '4px', fontSize: '12px' }}>
                    分组: {selectedRule.group || '未分组'}
                  </span>
                  <span className="pill-badge" style={{ background: selectedRule.isEnabled ? 'rgba(16,185,129,0.15)' : 'rgba(255,255,255,0.08)', color: selectedRule.isEnabled ? '#10b981' : '#888', padding: '2px 8px', borderRadius: '4px', fontSize: '12px' }}>
                    {selectedRule.isEnabled ? '✓ 规则已启用' : '⚪ 规则已禁用'}
                  </span>
                  {selectedRule.isRegex && (
                    <span className="pill-badge" style={{ background: 'rgba(99,102,241,0.15)', color: '#818cf8', padding: '2px 8px', borderRadius: '4px', fontSize: '12px' }}>
                      正则表达式
                    </span>
                  )}
                </div>
              </div>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className="ghost-button"
                  onClick={() => handleToggle(selectedRule)}
                >
                  {selectedRule.isEnabled ? '停用' : '启用'}
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

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '16px' }}>
              <div style={{ background: 'rgba(0,0,0,0.2)', padding: '12px', borderRadius: '8px' }}>
                <div style={{ fontSize: '12px', color: '#888', marginBottom: '4px' }}>匹配模式 (Pattern)</div>
                <code style={{ fontSize: '13px', color: '#38bdf8', wordBreak: 'break-all' }}>{selectedRule.pattern}</code>
              </div>
              <div style={{ background: 'rgba(0,0,0,0.2)', padding: '12px', borderRadius: '8px' }}>
                <div style={{ fontSize: '12px', color: '#888', marginBottom: '4px' }}>替换为 (Replacement)</div>
                <code style={{ fontSize: '13px', color: '#4ade80', wordBreak: 'break-all', maxHeight: '80px', overflowY: 'auto', display: 'block' }}>
                  {selectedRule.replacement || '(空 / 剔除)'}
                </code>
              </div>
            </div>

            {selectedRule.scope && (
              <div style={{ fontSize: '12px', color: '#888', marginBottom: '12px' }}>
                🎯 生效范围: <span style={{ color: '#e5e7eb' }}>{selectedRule.scope}</span>
              </div>
            )}
          </div>
        ) : (
          <div style={{ padding: '40px 20px', textAlign: 'center', background: 'rgba(255,255,255,0.02)', borderRadius: '12px', border: '1px dashed rgba(255,255,255,0.08)', marginBottom: '24px' }}>
            <p style={{ color: '#888', margin: 0 }}>👈 在左侧选择一条规则查看详情，或点击「新建规则」配置错字反爬清洗</p>
          </div>
        )}

        {/* 实时沙箱调试区域 */}
        <div style={{ marginTop: '24px', background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: '12px', padding: '20px' }}>
          <h3 style={{ margin: '0 0 12px 0', fontSize: '15px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Icon name="sliders" />
            <span>规则沙箱测试与实时效果预览</span>
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            <div>
              <div style={{ fontSize: '12px', color: '#888', marginBottom: '6px' }}>输入测试原文本（可粘贴小说混淆段落）：</div>
              <textarea
                rows={4}
                style={{ width: '100%', background: 'rgba(0,0,0,0.3)', color: '#fff', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '6px', padding: '8px', fontSize: '13px' }}
                placeholder="例如：大丑阁上，少萝茜心外无点刺痛，魔男们是是会把强大的魔男同族当做仆从军..."
                value={testText}
                onChange={e => setTestText(e.target.value)}
              />
              <button
                type="button"
                className="subtle-button"
                style={{ marginTop: '8px' }}
                disabled={testing}
                onClick={() => handleRunTest(selectedRule || undefined)}
              >
                {testing ? '测试中...' : '运行沙箱测试'}
              </button>
            </div>
            <div>
              <div style={{ fontSize: '12px', color: '#888', marginBottom: '6px' }}>清洗求值结果：</div>
              <div style={{ minHeight: '88px', background: 'rgba(0,0,0,0.4)', borderRadius: '6px', padding: '8px', fontSize: '13px', border: '1px solid rgba(255,255,255,0.1)', color: testResult?.changed ? '#34d399' : '#888' }}>
                {testResult ? (
                  <>
                    <div style={{ wordBreak: 'break-all' }}>{testResult.cleanedText}</div>
                    <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '6px' }}>
                      {testResult.changed ? '✅ 已命中并发生替换' : '⚪ 未产生改动'}
                    </div>
                  </>
                ) : (
                  <span style={{ color: '#555' }}>点击「运行沙箱测试」即可预览清洗效果</span>
                )}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 编辑/新建规则弹窗 */}
      {isEditModalOpen && editingRule && (
        <div className="modal-backdrop" onClick={() => setIsEditModalOpen(false)}>
          <div className="modal-card" style={{ maxWidth: '640px', width: '90%' }} onClick={e => e.stopPropagation()}>
            <header className="modal-header">
              <h2>{editingRule.id ? '编辑替换规则' : '新建替换规则'}</h2>
              <button type="button" className="icon-btn" onClick={() => setIsEditModalOpen(false)}>
                <Icon name="close" />
              </button>
            </header>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '12px', padding: '16px' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '12px' }}>
                <div>
                  <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>规则名称</label>
                  <input
                    style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff' }}
                    placeholder="如：反义词错字清洗"
                    value={editingRule.name || ''}
                    onChange={e => setEditingRule({ ...editingRule, name: e.target.value })}
                  />
                </div>
                <div>
                  <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>分组名称</label>
                  <input
                    style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff' }}
                    placeholder="如：起点反爬"
                    value={editingRule.group || ''}
                    onChange={e => setEditingRule({ ...editingRule, group: e.target.value })}
                  />
                </div>
              </div>

              <div>
                <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>匹配正则 / 关键词 (Pattern)</label>
                <input
                  style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff', fontFamily: 'monospace' }}
                  placeholder="如：(大丑|魔男|少萝茜|阁上)"
                  value={editingRule.pattern || ''}
                  onChange={e => setEditingRule({ ...editingRule, pattern: e.target.value })}
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>
                  替换内容 (可为文本、正则组 $1 或 @js: 字典翻转脚本)
                </label>
                <textarea
                  rows={4}
                  style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff', fontFamily: 'monospace', fontSize: '12px' }}
                  placeholder="@js:const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result] || result;"
                  value={editingRule.replacement || ''}
                  onChange={e => setEditingRule({ ...editingRule, replacement: e.target.value })}
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div>
                  <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>生效书名 / 作用域 (留空则全局生效)</label>
                  <input
                    style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff' }}
                    placeholder="如：宅魔女,诡秘之主 (逗号分隔)"
                    value={editingRule.scope || ''}
                    onChange={e => setEditingRule({ ...editingRule, scope: e.target.value || undefined })}
                  />
                </div>
                <div>
                  <label style={{ fontSize: '12px', color: '#888', display: 'block', marginBottom: '4px' }}>排除范围 (Exclude Scope)</label>
                  <input
                    style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff' }}
                    placeholder="如：凡人修仙传"
                    value={editingRule.excludeScope || ''}
                    onChange={e => setEditingRule({ ...editingRule, excludeScope: e.target.value || undefined })}
                  />
                </div>
              </div>

              <div style={{ display: 'flex', gap: '16px', alignItems: 'center', marginTop: '4px' }}>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={editingRule.isRegex ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isRegex: e.target.checked })}
                  />
                  <span>正则表达式</span>
                </label>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={editingRule.scopeContent ?? true}
                    onChange={e => setEditingRule({ ...editingRule, scopeContent: e.target.checked })}
                  />
                  <span>作用于正文</span>
                </label>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={editingRule.scopeTitle ?? false}
                    onChange={e => setEditingRule({ ...editingRule, scopeTitle: e.target.checked })}
                  />
                  <span>作用于章节标题</span>
                </label>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={editingRule.isEnabled ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isEnabled: e.target.checked })}
                  />
                  <span>立即启用</span>
                </label>
              </div>
            </div>
            <footer className="modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', padding: '16px' }}>
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
          <div className="modal-card" style={{ maxWidth: '540px', width: '90%' }} onClick={e => e.stopPropagation()}>
            <header className="modal-header">
              <h2>导入替换规则</h2>
              <button type="button" className="icon-btn" onClick={() => setIsImportModalOpen(false)}>
                <Icon name="close" />
              </button>
            </header>
            <div className="modal-body" style={{ padding: '16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div style={{ display: 'flex', gap: '12px' }}>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                  <input
                    type="radio"
                    name="importType"
                    checked={importType === 'url'}
                    onChange={() => setImportType('url')}
                  />
                  <span>网络订阅 URL 导入</span>
                </label>
                <label style={{ fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
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
                <input
                  style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff' }}
                  placeholder="https://example.com/replace_rules.json"
                  value={importInput}
                  onChange={e => setImportInput(e.target.value)}
                />
              ) : (
                <textarea
                  rows={6}
                  style={{ width: '100%', padding: '8px', borderRadius: '6px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.15)', color: '#fff', fontFamily: 'monospace', fontSize: '12px' }}
                  placeholder="粘贴 Legado 标准替换规则 JSON 数组..."
                  value={importInput}
                  onChange={e => setImportInput(e.target.value)}
                />
              )}
            </div>
            <footer className="modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', padding: '16px' }}>
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
