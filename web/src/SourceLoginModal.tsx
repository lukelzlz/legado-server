import React, { useState, useEffect, useCallback } from 'react'
import { api, SourceLoginUiItem, SourceLoginUiResponse } from './api'

interface SourceLoginModalProps {
  sourceId: string
  sourceName: string
  onClose: () => void
  onToast: (message: string, type?: 'info' | 'success' | 'error') => void
}

export const SourceLoginModal: React.FC<SourceLoginModalProps> = ({
  sourceId,
  sourceName,
  onClose,
  onToast,
}) => {
  const [loading, setLoading] = useState(true)
  const [executing, setExecuting] = useState(false)
  const [uiResponse, setUiResponse] = useState<SourceLoginUiResponse | null>(null)
  const [formData, setFormData] = useState<Record<string, string>>({})
  const [menuOpen, setMenuOpen] = useState(false)
  const [headerViewOpen, setHeaderViewOpen] = useState(false)
  const [showPasswords, setShowPasswords] = useState<Record<string, boolean>>({})

  const loadLoginUi = useCallback(async () => {
    try {
      setLoading(true)
      const res = await api.getSourceLoginUi(sourceId)
      setUiResponse(res)
      const initialData: Record<string, string> = { ...(res.loginInfo || {}) }
      res.loginUi.forEach(item => {
        const key = item.key || item.name
        if (key && initialData[key] === undefined && item.default) {
          initialData[key] = item.default
        }
      })
      setFormData(initialData)
    } catch (err: any) {
      onToast(err.message || '获取登录界面失败', 'error')
    } finally {
      setLoading(false)
    }
  }, [sourceId, onToast])

  useEffect(() => {
    loadLoginUi()
  }, [loadLoginUi])

  const handleInputChange = (key: string, val: string) => {
    setFormData(prev => ({ ...prev, [key]: val }))
  }

  const handleAction = async (actionCode?: string, isLongClick = false) => {
    if (!actionCode && actionCode !== '') return
    try {
      setExecuting(true)
      const trimmed = actionCode.trim()
      if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
        window.open(trimmed, '_blank', 'noopener,noreferrer')
        return
      }

      const res = await api.executeSourceLoginAction(sourceId, actionCode, formData, isLongClick)
      if (res.toastMessages && res.toastMessages.length > 0) {
        res.toastMessages.forEach(msg => onToast(msg, res.success ? 'success' : 'info'))
      } else if (res.success) {
        onToast('操作执行成功', 'success')
      }

      if (res.openUrl) {
        window.open(res.openUrl, '_blank', 'noopener,noreferrer')
      }
      if (res.copyText) {
        await navigator.clipboard.writeText(res.copyText)
        onToast(`已复制到剪贴板: ${res.copyText}`, 'info')
      }
      if (res.updatedLoginInfo) {
        setFormData(prev => ({ ...prev, ...res.updatedLoginInfo }))
      }
      if (res.reRenderUi) {
        await loadLoginUi()
      }
    } catch (err: any) {
      onToast(err.message || '执行操作失败', 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleSaveAndLogin = async () => {
    try {
      setExecuting(true)
      await api.saveSourceLoginInfo(sourceId, formData)
      const res = await api.executeSourceLoginAction(sourceId, 'login(true)', formData, false)
      if (res.toastMessages && res.toastMessages.length > 0) {
        res.toastMessages.forEach(msg => onToast(msg, res.success ? 'success' : 'info'))
      } else {
        onToast('登录信息已保存并尝试登录', 'success')
      }
      if (res.openUrl) {
        window.open(res.openUrl, '_blank', 'noopener,noreferrer')
      }
      if (res.copyText) {
        await navigator.clipboard.writeText(res.copyText)
      }
      if (res.updatedLoginInfo) {
        setFormData(prev => ({ ...prev, ...res.updatedLoginInfo }))
      }
      if (res.reRenderUi) {
        await loadLoginUi()
      }
    } catch (err: any) {
      onToast(err.message || '保存或登录失败', 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleCopyLoginHeader = async () => {
    const header = uiResponse?.loginHeader
    if (!header) {
      onToast('当前书源无登录头', 'info')
      return
    }
    await navigator.clipboard.writeText(header)
    onToast('登录头已复制到剪贴板', 'success')
    setMenuOpen(false)
  }

  const handleDeleteLoginHeader = async () => {
    if (!confirm('确定删除此书源保存的登录头吗？')) return
    try {
      await api.clearSourceLoginHeader(sourceId)
      onToast('登录头已删除', 'success')
      setMenuOpen(false)
      loadLoginUi()
    } catch (err: any) {
      onToast(err.message || '删除登录头失败', 'error')
    }
  }

  const handleClearLoginInfo = async () => {
    if (!confirm('确定清空此书源的所有登录输入信息吗？')) return
    try {
      await api.clearSourceLoginInfo(sourceId)
      setFormData({})
      onToast('登录信息已清空', 'success')
      setMenuOpen(false)
    } catch (err: any) {
      onToast(err.message || '清空登录信息失败', 'error')
    }
  }

  const renderControl = (item: SourceLoginUiItem, index: number) => {
    const key = item.key || item.name
    const label = item.viewName || item.name
    const type = (item.type || 'text').toLowerCase()
    const value = formData[key] ?? ''

    // Flex style computation
    const basisPercent = item.style?.layout_flexBasisPercent ?? -1
    let flexStyle: React.CSSProperties = {}
    if (basisPercent > 0) {
      if (basisPercent >= 0.9) {
        flexStyle = { flex: '1 1 100%', width: '100%' }
      } else if (basisPercent >= 0.4 && basisPercent <= 0.6) {
        flexStyle = { flex: '1 1 calc(50% - 8px)', minWidth: '180px' }
      } else {
        flexStyle = { flex: `1 1 ${Math.round(basisPercent * 100)}%` }
      }
    } else {
      flexStyle = { flex: item.style?.layout_flexGrow ? `${item.style.layout_flexGrow} 1 auto` : '1 1 100%', width: '100%' }
    }

    if (item.style?.layout_wrapBefore) {
      flexStyle.breakBefore = 'always'
    }

    if (type === 'button') {
      return (
        <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-btn-container">
          <button
            type="button"
            className="source-login-btn primary-subtle"
            disabled={executing}
            onClick={() => handleAction(item.action, false)}
            onContextMenu={e => {
              e.preventDefault()
              handleAction(item.action, true)
            }}
          >
            {label}
          </button>
        </div>
      )
    }

    if (type === 'toggle' || type === 'switch' || type === 'checkbox') {
      const isChecked = value === 'true' || value === '1'
      return (
        <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-toggle-container">
          <label className="login-ui-toggle-label">
            <span>{label}</span>
            <input
              type="checkbox"
              checked={isChecked}
              onChange={e => handleInputChange(key, e.target.checked ? 'true' : 'false')}
            />
          </label>
        </div>
      )
    }

    if (type === 'select' || type === 'dropdown') {
      const options = item.options || (item.chars ? item.chars.filter((c): c is string => c !== null) : [])
      return (
        <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-field">
          <label className="login-ui-field-label">{label}</label>
          <select
            className="login-ui-select"
            value={value}
            onChange={e => handleInputChange(key, e.target.value)}
          >
            {options.map((opt, i) => (
              <option key={`${opt}-${i}`} value={opt}>{opt}</option>
            ))}
          </select>
        </div>
      )
    }

    if (type === 'label' || type === 'textlabel') {
      return (
        <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-info">
          <div className="login-ui-info-text">{item.hint || item.default || label}</div>
        </div>
      )
    }

    // Default: text / password
    const isPassword = type === 'password'
    const isShowing = showPasswords[key]
    return (
      <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-field">
        <label className="login-ui-field-label">{label}</label>
        <div className="login-ui-input-wrapper">
          <input
            type={isPassword && !isShowing ? 'password' : 'text'}
            className="login-ui-input"
            value={value}
            placeholder={item.hint || item.default || `请输入${label}`}
            onChange={e => handleInputChange(key, e.target.value)}
          />
          {isPassword && (
            <button
              type="button"
              className="login-ui-eye-btn"
              onClick={() => setShowPasswords(prev => ({ ...prev, [key]: !prev[key] }))}
              title={isShowing ? '隐藏密码' : '显示密码'}
            >
              {isShowing ? '🙈' : '👁️'}
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content source-login-modal" onClick={e => e.stopPropagation()}>
        {/* Modal Header */}
        <div className="modal-header source-login-header">
          <div className="source-login-title-group">
            <h3 className="source-login-title">登录 {sourceName}</h3>
            {uiResponse?.sourceVariable && (
              <span className="source-login-subtitle" title={uiResponse.sourceVariable}>
                已关联变量
              </span>
            )}
          </div>
          <div className="source-login-actions">
            {/* Save & Run Login Checkmark */}
            <button
              type="button"
              className="source-login-action-btn primary"
              title="保存并登录"
              disabled={executing || loading}
              onClick={handleSaveAndLogin}
            >
              ✓
            </button>

            {/* Three Dots More Menu */}
            <div className="source-login-menu-container">
              <button
                type="button"
                className="source-login-action-btn"
                title="更多选项"
                onClick={() => setMenuOpen(prev => !prev)}
              >
                ⋮
              </button>
              {menuOpen && (
                <div className="source-login-dropdown-menu">
                  <button
                    type="button"
                    onClick={() => {
                      setHeaderViewOpen(true)
                      setMenuOpen(false)
                    }}
                  >
                    查看登录头
                  </button>
                  <button type="button" onClick={handleCopyLoginHeader}>
                    复制登录头
                  </button>
                  <button type="button" className="danger-text" onClick={handleDeleteLoginHeader}>
                    删除登录头
                  </button>
                  <button type="button" className="danger-text" onClick={handleClearLoginInfo}>
                    清空登录信息
                  </button>
                </div>
              )}
            </div>

            {/* Close Modal */}
            <button
              type="button"
              className="source-login-action-btn"
              title="关闭"
              onClick={onClose}
            >
              ✕
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div className="modal-body source-login-body">
          {loading ? (
            <div className="login-ui-loading">正在加载登录界面...</div>
          ) : !uiResponse?.loginUi || uiResponse.loginUi.length === 0 ? (
            <div className="login-ui-empty">
              <p>此书源未定义可视化的登录表单。</p>
              {uiResponse?.loginUrl && (
                <div style={{ marginTop: '16px' }}>
                  <button
                    type="button"
                    className="source-login-btn primary"
                    onClick={() => window.open(uiResponse.loginUrl, '_blank', 'noopener,noreferrer')}
                  >
                    在网页中打开登录页
                  </button>
                </div>
              )}
            </div>
          ) : (
            <form
              className="source-login-flex-form"
              onSubmit={e => {
                e.preventDefault()
                handleSaveAndLogin()
              }}
            >
              {uiResponse.loginUi.map((item, idx) => renderControl(item, idx))}
            </form>
          )}
        </div>

        {/* View Login Header Subdialog */}
        {headerViewOpen && (
          <div className="modal-overlay" style={{ zIndex: 1100 }} onClick={() => setHeaderViewOpen(false)}>
            <div className="modal-content header-view-dialog" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h3>已保存的登录头</h3>
                <button type="button" className="source-login-action-btn" onClick={() => setHeaderViewOpen(false)}>✕</button>
              </div>
              <div className="modal-body">
                <pre className="header-view-code">
                  {uiResponse?.loginHeader ? uiResponse.loginHeader : '(暂无登录头数据)'}
                </pre>
              </div>
              <div className="modal-footer">
                <button type="button" className="btn-secondary" onClick={() => setHeaderViewOpen(false)}>关闭</button>
                {uiResponse?.loginHeader && (
                  <button type="button" className="btn-primary" onClick={handleCopyLoginHeader}>复制登录头</button>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
