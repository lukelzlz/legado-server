import React, { useState, useEffect, useMemo } from 'react'
import { api, ReplaceRule, ReplaceRulePreviewResponse } from './api'
import { toast } from './Toast'

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
      loadRules()
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
        const nameMatch = rule.name.toLowerCase().includes(q)
        const patternMatch = rule.pattern.toLowerCase().includes(q)
        const replacementMatch = rule.replacement.toLowerCase().includes(q)
        const scopeMatch = rule.scope?.toLowerCase().includes(q) || false
        if (!nameMatch && !patternMatch && !replacementMatch && !scopeMatch) return false
      }
      return true
    })
  }, [rules, selectedGroup, scopeFilter, searchQuery, currentBookName, currentSourceUrl])

  const handleToggle = async (rule: ReplaceRule) => {
    const newStatus = !rule.isEnabled
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
      toast.warning('匹配模式不能为空')
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
      toast.warning('请输入导入内容或链接')
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
      toast.warning('请输入待测试的文本')
      return
    }
    setTesting(true)
    try {
      const res = await api.previewReplaceRule({
        text: testText,
        rule: editingRule || undefined,
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 overflow-y-auto">
      <div className="bg-[#171a21] border border-[#262b36] rounded-xl w-full max-w-4xl max-h-[90vh] flex flex-col shadow-2xl text-[#e6e8eb]">
        {/* Header */}
        <div className="p-4 border-b border-[#262b36] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold text-white flex items-center gap-2">
              <span>🧹 替换净化规则管理</span>
              <span className="text-xs px-2 py-0.5 rounded-full bg-[#262b36] text-gray-400 font-normal">
                共 {rules.length} 条
              </span>
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => openAddModal(false)}
              className="px-3 py-1.5 text-xs bg-blue-600 hover:bg-blue-500 text-white rounded-lg transition-colors flex items-center gap-1 font-medium"
            >
              <span>+ 新建规则</span>
            </button>
            {currentBookName && (
              <button
                type="button"
                onClick={() => openAddModal(true)}
                className="px-3 py-1.5 text-xs bg-purple-600 hover:bg-purple-500 text-white rounded-lg transition-colors flex items-center gap-1 font-medium"
              >
                <span>+ 当前书专用</span>
              </button>
            )}
            <button
              type="button"
              onClick={() => setIsImportModalOpen(true)}
              className="px-3 py-1.5 text-xs bg-[#262b36] hover:bg-[#323947] text-gray-300 rounded-lg transition-colors font-medium"
            >
              导入订阅
            </button>
            <button
              type="button"
              onClick={handleExport}
              className="px-3 py-1.5 text-xs bg-[#262b36] hover:bg-[#323947] text-gray-300 rounded-lg transition-colors font-medium"
            >
              导出
            </button>
            <button
              type="button"
              onClick={onClose}
              className="p-1.5 text-gray-400 hover:text-white rounded-lg hover:bg-[#262b36] transition-colors"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Toolbar & Filters */}
        <div className="p-4 border-b border-[#262b36] flex flex-wrap gap-3 items-center justify-between bg-[#12141a]">
          <div className="flex items-center gap-2 flex-1 min-w-[200px]">
            <input
              type="text"
              placeholder="搜索规则名、匹配正则、替换内容、作用域..."
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-1.5 text-xs text-white placeholder-gray-500 focus:outline-none focus:border-blue-500"
            />
          </div>

          <div className="flex items-center gap-2">
            {currentBookName && (
              <div className="flex rounded-lg bg-[#1c2029] p-0.5 border border-[#2e3442]">
                <button
                  type="button"
                  onClick={() => setScopeFilter('all')}
                  className={`px-2.5 py-1 text-xs rounded-md transition-colors ${
                    scopeFilter === 'all' ? 'bg-blue-600 text-white font-medium' : 'text-gray-400 hover:text-white'
                  }`}
                >
                  全部范围
                </button>
                <button
                  type="button"
                  onClick={() => setScopeFilter('current')}
                  className={`px-2.5 py-1 text-xs rounded-md transition-colors ${
                    scopeFilter === 'current' ? 'bg-blue-600 text-white font-medium' : 'text-gray-400 hover:text-white'
                  }`}
                >
                  当前书生效 ({currentBookName})
                </button>
              </div>
            )}

            {groups.length > 0 && (
              <select
                value={selectedGroup}
                onChange={e => setSelectedGroup(e.target.value)}
                className="bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-1.5 text-xs text-gray-300 focus:outline-none focus:border-blue-500"
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
        <div className="flex-1 overflow-y-auto p-4 space-y-2 min-h-[300px]">
          {loading ? (
            <div className="text-center py-12 text-gray-500 text-sm">正在加载替换规则...</div>
          ) : filteredRules.length === 0 ? (
            <div className="text-center py-12 text-gray-500 text-sm flex flex-col items-center gap-2">
              <span>暂无匹配的替换规则</span>
              <span className="text-xs text-gray-600">
                点击右上角「+ 新建规则」或「导入订阅」导入社区规则库
              </span>
            </div>
          ) : (
            filteredRules.map(rule => (
              <div
                key={rule.id}
                className={`p-3 rounded-lg border transition-all ${
                  rule.isEnabled
                    ? 'bg-[#1c2029] border-[#2e3442] hover:border-[#3b4354]'
                    : 'bg-[#14161d] border-[#222733] opacity-60'
                } flex items-center justify-between gap-4`}
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="font-semibold text-sm text-white truncate">
                      {rule.name || rule.pattern}
                    </span>
                    {rule.group && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-950 text-blue-300 border border-blue-800">
                        {rule.group}
                      </span>
                    )}
                    {rule.isRegex && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-purple-950 text-purple-300 border border-purple-800">
                        正则
                      </span>
                    )}
                    {rule.scope && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-950 text-amber-300 border border-amber-800">
                        范围: {rule.scope}
                      </span>
                    )}
                    {rule.excludeScope && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded bg-red-950 text-red-300 border border-red-800">
                        排除: {rule.excludeScope}
                      </span>
                    )}
                  </div>
                  <div className="text-xs text-gray-400 font-mono flex items-center gap-2 flex-wrap">
                    <span className="text-red-400 bg-red-950/30 px-1 rounded truncate max-w-xs">
                      {rule.pattern}
                    </span>
                    <span>➔</span>
                    <span className="text-green-400 bg-green-950/30 px-1 rounded truncate max-w-xs">
                      {rule.replacement || '(清空)'}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2 flex-shrink-0">
                  <label className="relative inline-flex items-center cursor-pointer">
                    <input
                      type="checkbox"
                      checked={rule.isEnabled}
                      onChange={() => handleToggle(rule)}
                      className="sr-only peer"
                    />
                    <div className="w-9 h-5 bg-gray-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-blue-600"></div>
                  </label>

                  <button
                    type="button"
                    onClick={() => {
                      setEditingRule(rule)
                      setTestText('')
                      setTestResult(null)
                      setIsEditModalOpen(true)
                    }}
                    className="p-1.5 text-gray-400 hover:text-blue-400 rounded hover:bg-[#262b36] transition-colors"
                    title="编辑"
                  >
                    ✏️
                  </button>

                  <button
                    type="button"
                    onClick={() => handleDelete(rule)}
                    className="p-1.5 text-gray-400 hover:text-red-400 rounded hover:bg-[#262b36] transition-colors"
                    title="删除"
                  >
                    🗑️
                  </button>
                </div>
              </div>
            ))
          )}
        </div>

        {/* Footer info */}
        <div className="p-3 border-t border-[#262b36] text-xs text-gray-500 bg-[#12141a] flex justify-between items-center">
          <span>
            💡 规则将按优先级顺序在服务端抓取、缓存与 TTS 朗读时自动生效。
          </span>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-1.5 bg-[#262b36] hover:bg-[#323947] text-white rounded-lg transition-colors text-xs font-medium"
          >
            完成
          </button>
        </div>
      </div>

      {/* Edit / Create Rule Dialog */}
      {isEditModalOpen && editingRule && (
        <div className="fixed inset-0 z-60 flex items-center justify-center bg-black/70 backdrop-blur-md p-4">
          <div className="bg-[#171a21] border border-[#2e3442] rounded-xl w-full max-w-2xl flex flex-col shadow-2xl text-[#e6e8eb] max-h-[90vh] overflow-y-auto">
            <div className="p-4 border-b border-[#262b36] flex items-center justify-between">
              <h3 className="text-base font-semibold text-white">
                {editingRule.id ? '编辑替换净化规则' : '新建替换净化规则'}
              </h3>
              <button
                type="button"
                onClick={() => setIsEditModalOpen(false)}
                className="text-gray-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleSaveRule} className="p-4 space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    规则名称 <span className="text-red-400">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="如：反爬混淆修复 / 去广告"
                    value={editingRule.name || ''}
                    onChange={e => setEditingRule({ ...editingRule, name: e.target.value })}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    分组名称
                  </label>
                  <input
                    type="text"
                    placeholder="如：网络反爬 / 错字纠正"
                    value={editingRule.group || ''}
                    onChange={e => setEditingRule({ ...editingRule, group: e.target.value })}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-300 mb-1">
                  匹配模式 (Pattern) <span className="text-red-400">*</span>
                </label>
                <textarea
                  rows={2}
                  required
                  placeholder="要替换的文本或正则表达式，如：(大丑|魔男|少萝茜|阁上)"
                  value={editingRule.pattern || ''}
                  onChange={e => setEditingRule({ ...editingRule, pattern: e.target.value })}
                  className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg p-2.5 text-xs text-white font-mono focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-300 mb-1">
                  替换为 (Replacement)
                </label>
                <textarea
                  rows={3}
                  placeholder="替换内容，支持 $1 捕获组或 @js: 脚本。如：@js: const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result]||result;"
                  value={editingRule.replacement || ''}
                  onChange={e => setEditingRule({ ...editingRule, replacement: e.target.value })}
                  className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg p-2.5 text-xs text-white font-mono focus:outline-none focus:border-blue-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    作用范围 (Scope，空则全局生效)
                  </label>
                  <input
                    type="text"
                    placeholder="指定书名、书源URL，以逗号分隔"
                    value={editingRule.scope || ''}
                    onChange={e => setEditingRule({ ...editingRule, scope: e.target.value })}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    排除范围 (ExcludeScope)
                  </label>
                  <input
                    type="text"
                    placeholder="排除的书名或书源"
                    value={editingRule.excludeScope || ''}
                    onChange={e => setEditingRule({ ...editingRule, excludeScope: e.target.value })}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
              </div>

              {/* Switches */}
              <div className="flex flex-wrap gap-4 pt-1">
                <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editingRule.isRegex ?? true}
                    onChange={e => setEditingRule({ ...editingRule, isRegex: e.target.checked })}
                    className="rounded bg-[#1c2029] border-[#2e3442] text-blue-600 focus:ring-0"
                  />
                  <span>支持正则表达式</span>
                </label>
                <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeContent ?? true}
                    onChange={e => setEditingRule({ ...editingRule, scopeContent: e.target.checked })}
                    className="rounded bg-[#1c2029] border-[#2e3442] text-blue-600 focus:ring-0"
                  />
                  <span>作用于正文</span>
                </label>
                <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={editingRule.scopeTitle ?? false}
                    onChange={e => setEditingRule({ ...editingRule, scopeTitle: e.target.checked })}
                    className="rounded bg-[#1c2029] border-[#2e3442] text-blue-600 focus:ring-0"
                  />
                  <span>作用于章节标题</span>
                </label>
              </div>

              {/* Test Sandbox */}
              <div className="p-3 rounded-lg bg-[#12141a] border border-[#262b36] space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-gray-300">🧪 实时效果调试</span>
                  <button
                    type="button"
                    onClick={handleRunTest}
                    disabled={testing}
                    className="px-2.5 py-1 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded font-medium transition-colors"
                  >
                    {testing ? '测试中...' : '运行测试'}
                  </button>
                </div>
                <textarea
                  rows={2}
                  placeholder="在此输入待调试的句子，如：大丑阁上，少萝茜今天当定了魔男！"
                  value={testText}
                  onChange={e => setTestText(e.target.value)}
                  className="w-full bg-[#1c2029] border border-[#2e3442] rounded p-2 text-xs text-white font-mono focus:outline-none"
                />
                {testResult && (
                  <div className="text-xs space-y-1 pt-1 border-t border-[#262b36]">
                    <div className="text-gray-400">
                      清洗后结果：
                      <span className="text-green-400 font-mono ml-1">
                        {testResult.cleanedText}
                      </span>
                    </div>
                    <div className="text-gray-500 text-[11px]">
                      状态：{testResult.changed ? '✅ 已命中并发生替换' : '⚪ 未产生改动'}
                    </div>
                  </div>
                )}
              </div>

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setIsEditModalOpen(false)}
                  className="px-4 py-2 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded-lg transition-colors font-medium"
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 text-xs bg-blue-600 hover:bg-blue-500 text-white rounded-lg transition-colors font-medium"
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
        <div className="fixed inset-0 z-60 flex items-center justify-center bg-black/70 backdrop-blur-md p-4">
          <div className="bg-[#171a21] border border-[#2e3442] rounded-xl w-full max-w-lg flex flex-col shadow-2xl text-[#e6e8eb]">
            <div className="p-4 border-b border-[#262b36] flex items-center justify-between">
              <h3 className="text-base font-semibold text-white">导入替换净化规则</h3>
              <button
                type="button"
                onClick={() => setIsImportModalOpen(false)}
                className="text-gray-400 hover:text-white"
              >
                ✕
              </button>
            </div>

            <div className="p-4 space-y-3">
              <div className="flex rounded-lg bg-[#1c2029] p-0.5 border border-[#2e3442]">
                <button
                  type="button"
                  onClick={() => setImportType('url')}
                  className={`flex-1 py-1.5 text-xs rounded-md transition-colors ${
                    importType === 'url' ? 'bg-blue-600 text-white font-medium' : 'text-gray-400 hover:text-white'
                  }`}
                >
                  网络订阅 URL 导入
                </button>
                <button
                  type="button"
                  onClick={() => setImportType('text')}
                  className={`flex-1 py-1.5 text-xs rounded-md transition-colors ${
                    importType === 'text' ? 'bg-blue-600 text-white font-medium' : 'text-gray-400 hover:text-white'
                  }`}
                >
                  直接粘贴 JSON 文本
                </button>
              </div>

              {importType === 'url' ? (
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    规则订阅地址 (HTTP/HTTPS)
                  </label>
                  <input
                    type="url"
                    placeholder="https://.../replaceRule.json"
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                  <div className="mt-2 text-[11px] text-gray-500">
                    支持夜雨聆风、破冰、肥猫等 Legado 兼容格式的替换规则 JSON 链接。
                  </div>
                </div>
              ) : (
                <div>
                  <label className="block text-xs font-medium text-gray-300 mb-1">
                    规则 JSON 内容
                  </label>
                  <textarea
                    rows={6}
                    placeholder="粘贴 [...] 或 { data: [...] } 格式的 JSON"
                    value={importInput}
                    onChange={e => setImportInput(e.target.value)}
                    className="w-full bg-[#1c2029] border border-[#2e3442] rounded-lg p-2.5 text-xs text-white font-mono focus:outline-none focus:border-blue-500"
                  />
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setIsImportModalOpen(false)}
                  className="px-4 py-2 text-xs bg-gray-700 hover:bg-gray-600 text-white rounded-lg transition-colors font-medium"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={handleImport}
                  disabled={importing}
                  className="px-4 py-2 text-xs bg-blue-600 hover:bg-blue-500 text-white rounded-lg transition-colors font-medium"
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
