import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { api, SourceLoginUiItem, SourceLoginUiResponse } from './api'
import { SourceWebViewModal } from './SourceWebViewModal'
import { NetworkImportModal } from './NetworkImportModal'
import { isInlineBrowserTarget } from './sourceSettingsResult'

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
  const { t } = useTranslation()
  const [loading, setLoading] = useState(true)
  const [executing, setExecuting] = useState(false)
  const [uiResponse, setUiResponse] = useState<SourceLoginUiResponse | null>(null)
  const [formData, setFormData] = useState<Record<string, string>>({})
  const [menuOpen, setMenuOpen] = useState(false)
  const [headerEditOpen, setHeaderEditOpen] = useState(false)
  const [headerEditText, setHeaderEditText] = useState('')
  const [cookieModalOpen, setCookieModalOpen] = useState(false)
  const [cookieInputText, setCookieInputText] = useState('')
  const [bookmarkletModalOpen, setBookmarkletModalOpen] = useState(false)
  const [showPasswords, setShowPasswords] = useState<Record<string, boolean>>({})
  const [webViewOpen, setWebViewOpen] = useState(false)
  const [webViewStartUrl, setWebViewStartUrl] = useState<string | undefined>(undefined)
  /** 触发内置浏览器的登录动作，登录完成后需要带 Cookie 重跑一次 */
  const pendingBrowserActionRef = useRef<string | null>(null)
  /** 内置浏览器里点了「更新书源」线路后要导入的地址；非 null 即打开网络导入弹窗。 */
  const [networkImportUrl, setNetworkImportUrl] = useState<string | null>(null)

  const loadLoginUi = useCallback(async () => {
    try {
      setLoading(true)
      const res = await api.getSourceLoginUi(sourceId)
      setUiResponse(res)
      setHeaderEditText(res.loginHeader || '')
      const initialData: Record<string, string> = { ...(res.loginInfo || {}) }
      res.loginUi.forEach(item => {
        const key = item.key || item.name
        if (key && initialData[key] === undefined && item.default) {
          initialData[key] = item.default
        }
      })
      setFormData(initialData)
    } catch (err: any) {
      onToast(err.message || t('source.loadLoginUiFailed', '获取登录界面失败'), 'error')
    } finally {
      setLoading(false)
    }
  }, [sourceId, onToast, t])

  useEffect(() => {
    loadLoginUi()
  }, [loadLoginUi])

  /** 用户手动打开内置浏览器（不带触发动作），入口地址交给书源配置决定 */
  const openBuiltInBrowser = useCallback(() => {
    pendingBrowserActionRef.current = null
    setWebViewStartUrl(undefined)
    setWebViewOpen(true)
  }, [])

  const bookmarkletCode = useMemo(() => {
    const serverOrigin = window.location.origin
    const targetSourceId = encodeURIComponent(sourceId)
    const noCookie = JSON.stringify(t('source.bookmarkletNoCookie', '⚠️ 当前页面未检测到任何 Cookie！'))
    const successMsg = JSON.stringify(t('source.bookmarkletSuccess', '✅ 成功将当前站点的 Cookie 同步至阅读服务器！'))
    const failMsg = JSON.stringify(t('source.bookmarkletFailed', '❌ 同步失败: '))
    return `javascript:(function(){var c=document.cookie;var u=location.href;if(!c){alert(${noCookie});return;}fetch('${serverOrigin}/api/sources/${targetSourceId}/login-cookie',{method:'POST',mode:'cors',headers:{'Content-Type':'application/json'},body:JSON.stringify({cookie:c,url:u})}).then(function(r){return r.json()}).then(function(d){alert(${successMsg})}).catch(function(e){alert(${failMsg}+e)});})();`
  }, [sourceId, t])

  const handleInputChange = (key: string, val: string) => {
    setFormData(prev => ({ ...prev, [key]: val }))
  }

  const handleAction = async (actionCode?: string, isLongClick = false, allowBuiltInBrowser = true) => {
    if (!actionCode && actionCode !== '') return
    try {
      setExecuting(true)
      const trimmed = actionCode.trim()
      if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
        window.open(trimmed, '_blank', 'noopener,noreferrer')
        return
      }

      const res = await api.executeSourceLoginAction(sourceId, actionCode, formData, isLongClick)
      if (res.error) {
        // 书源脚本失败时必须显式告知，否则按钮会「看起来成功但毫无反应」
        onToast(t('source.scriptExecFailed', { error: res.error, defaultValue: `书源脚本执行失败：${res.error}` }), 'error')
      } else if (res.toastMessages && res.toastMessages.length > 0) {
        res.toastMessages.forEach(msg => onToast(msg, res.success ? 'success' : 'info'))
      } else if (res.success) {
        onToast(t('source.operationSuccess', '操作执行成功'), 'success')
      }

      if (res.openUrl) {
        // 书源 JS 通过 java.startBrowserAwait(url) 请求打开网页：在本应用内置浏览器中打开，
        // 而不是弹到外部标签页，这样登录产生的 Cookie 能直接落到书源 Credential 里。
        // 书源自生成的内联页（data:text/html;base64,…）只能由代理层在 iframe 里渲染——
        // 直接 window.open 只会开出一个空白新标签，且拿不到任何回传结果。
        if (allowBuiltInBrowser || isInlineBrowserTarget(res.openUrl)) {
          pendingBrowserActionRef.current = actionCode
          setWebViewStartUrl(res.openUrl)
          setWebViewOpen(true)
        } else {
          window.open(res.openUrl, '_blank', 'noopener,noreferrer')
        }
      }
      if (res.copyText) {
        await navigator.clipboard.writeText(res.copyText)
        onToast(t('source.copiedToClipboardWithText', { text: res.copyText, defaultValue: `已复制到剪贴板: ${res.copyText}` }), 'info')
      }
      if (res.updatedLoginInfo) {
        setFormData(prev => ({ ...prev, ...res.updatedLoginInfo }))
      }
      if (res.reRenderUi) {
        await loadLoginUi()
      }
    } catch (err: any) {
      onToast(err.message || t('source.executeActionFailed', '执行操作失败'), 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleSaveAndLogin = async () => {
    try {
      setExecuting(true)
      await api.saveSourceLoginInfo(sourceId, formData)
      const res = await api.executeSourceLoginAction(sourceId, 'login(true)', formData, false)
      if (res.error) {
        onToast(t('source.scriptExecFailed', { error: res.error, defaultValue: `书源脚本执行失败：${res.error}` }), 'error')
      } else if (res.toastMessages && res.toastMessages.length > 0) {
        res.toastMessages.forEach(msg => onToast(msg, res.success ? 'success' : 'info'))
      } else {
        onToast(t('source.loginInfoSavedAndAttempting', '登录信息已保存并尝试登录'), 'success')
      }
      if (res.openUrl) {
        pendingBrowserActionRef.current = 'login(true)'
        setWebViewStartUrl(res.openUrl)
        setWebViewOpen(true)
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
      onToast(err.message || t('source.saveOrLoginFailed', '保存或登录失败'), 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleCopyLoginHeader = async () => {
    const header = uiResponse?.loginHeader
    if (!header) {
      onToast(t('source.noLoginHeader', '当前书源无登录头'), 'info')
      return
    }
    await navigator.clipboard.writeText(header)
    onToast(t('source.loginHeaderCopied', '登录头已复制到剪贴板'), 'success')
    setMenuOpen(false)
  }

  const handleSaveHeader = async () => {
    try {
      setExecuting(true)
      await api.saveSourceLoginHeader(sourceId, headerEditText.trim())
      onToast(t('source.loginHeaderUpdatedSuccess', '登录头已成功更新并保存'), 'success')
      setHeaderEditOpen(false)
      await loadLoginUi()
    } catch (err: any) {
      onToast(err.message || t('source.saveLoginHeaderFailed', '保存登录头失败'), 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleSaveCookie = async () => {
    if (!cookieInputText.trim()) {
      onToast(t('source.enterCookiePrompt', '请输入 Cookie 内容'), 'error')
      return
    }
    try {
      setExecuting(true)
      await api.saveSourceCookie(sourceId, cookieInputText.trim())
      onToast(t('source.cookieSavedSuccess', 'Cookie 已成功保存并关联到书源'), 'success')
      setCookieModalOpen(false)
      setCookieInputText('')
      await loadLoginUi()
    } catch (err: any) {
      onToast(err.message || t('source.saveCookieFailed', '保存 Cookie 失败'), 'error')
    } finally {
      setExecuting(false)
    }
  }

  const handleDeleteLoginHeader = async () => {
    if (!confirm(t('source.confirmDeleteLoginHeader', '确定删除此书源保存的登录头吗？'))) return
    try {
      await api.clearSourceLoginHeader(sourceId)
      onToast(t('source.loginHeaderDeleted', '登录头已删除'), 'success')
      setMenuOpen(false)
      loadLoginUi()
    } catch (err: any) {
      onToast(err.message || t('source.deleteLoginHeaderFailed', '删除登录头失败'), 'error')
    }
  }

  const handleClearLoginInfo = async () => {
    if (!confirm(t('source.confirmClearLoginInfo', '确定清空此书源的所有登录输入信息吗？'))) return
    try {
      await api.clearSourceLoginInfo(sourceId)
      setFormData({})
      onToast(t('source.loginInfoCleared', '登录信息已清空'), 'success')
      setMenuOpen(false)
    } catch (err: any) {
      onToast(err.message || t('source.clearLoginInfoFailed', '清空登录信息失败'), 'error')
    }
  }

  const copyBookmarklet = async () => {
    await navigator.clipboard.writeText(bookmarkletCode)
    onToast(t('source.bookmarkletCopied', '一键抓取书签代码已复制到剪贴板'), 'success')
  }

  const renderControl = (item: SourceLoginUiItem, index: number) => {
    const key = item.key || item.name
    const label = item.viewName || item.name
    const type = (item.type || 'text').toLowerCase()
    const value = formData[key] ?? ''

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
        <div key={`${key}-${index}`} style={flexStyle} className="login-ui-item-btn">
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
            placeholder={item.hint || item.default || t('source.pleaseInput', { label, defaultValue: `请输入${label}` })}
            onChange={e => handleInputChange(key, e.target.value)}
          />
          {isPassword && (
            <button
              type="button"
              className="login-ui-eye-btn"
              onClick={() => setShowPasswords(prev => ({ ...prev, [key]: !prev[key] }))}
              title={isShowing ? t('source.hidePassword', '隐藏密码') : t('source.showPassword', '显示密码')}
            >
              {isShowing ? '🙈' : '👁️'}
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="source-login-dialog" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="source-login-header">
          <div className="source-login-title-group">
            <h3 className="source-login-title">{t('source.loginSourceTitle', { name: sourceName, defaultValue: `登录 ${sourceName}` })}</h3>
            {uiResponse?.sourceVariable && (
              <span className="source-login-subtitle" title={uiResponse.sourceVariable}>
                {t('source.associatedVariable', '已关联变量')}
              </span>
            )}
          </div>
          <div className="source-login-actions">
            <button
              type="button"
              className="source-login-action-btn"
              title={t('source.loginWithBrowserRecommended', '用内置浏览器登录（推荐）')}
              onClick={openBuiltInBrowser}
            >
              🖥
            </button>
            <button
              type="button"
              className="source-login-action-btn primary"
              title={t('source.saveAndLogin', '保存并登录')}
              disabled={executing || loading}
              onClick={handleSaveAndLogin}
            >
              ✓
            </button>
            <div className="source-login-menu-container">
              <button
                type="button"
                className="source-login-action-btn"
                title={t('source.moreOptions', '更多选项')}
                onClick={() => setMenuOpen(prev => !prev)}
              >
                ⋮
              </button>
              {menuOpen && (
                <div className="source-login-dropdown-menu">
                  <button
                    type="button"
                    onClick={() => {
                      setHeaderEditText(uiResponse?.loginHeader || '')
                      setHeaderEditOpen(true)
                      setMenuOpen(false)
                    }}
                  >
                    ✏️ {t('source.editLoginHeader', '填入 / 查看登录头')}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setCookieModalOpen(true)
                      setMenuOpen(false)
                    }}
                  >
                    🍪 {t('source.quickFillCookie', '快捷填入 Cookie')}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setBookmarkletModalOpen(true)
                      setMenuOpen(false)
                    }}
                  >
                    ⚡ {t('source.syncBookmarklet', '一键同步书签 (Bookmarklet)')}
                  </button>
                  {uiResponse?.loginHeader && (
                    <button type="button" onClick={handleCopyLoginHeader}>
                      📋 {t('source.copyLoginHeader', '复制登录头')}
                    </button>
                  )}
                  <button type="button" className="danger-text" onClick={handleDeleteLoginHeader}>
                    🗑️ {t('source.deleteLoginHeader', '删除登录头')}
                  </button>
                  <button type="button" className="danger-text" onClick={handleClearLoginInfo}>
                    🧹 {t('source.clearLoginInfo', '清空登录信息')}
                  </button>
                </div>
              )}
            </div>
            <button
              type="button"
              className="source-login-action-btn"
              title={t('common.close', '关闭')}
              onClick={onClose}
            >
              ✕
            </button>
          </div>
        </div>

        <div className="source-login-body">
          {loading ? (
            <div className="login-ui-loading">{t('source.loadingLoginUi', '正在加载登录界面...')}</div>
          ) : !uiResponse?.loginUi || uiResponse.loginUi.length === 0 ? (
            <div className="login-ui-empty-direct-cookie" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '8px' }}>
                <span style={{ fontSize: '13px', color: 'var(--muted)' }}>
                  {t('source.noVisualLoginFormHint', '此书源未定义可视化登录表单，请直接填入 Cookie / 凭据：')}
                </span>
                {uiResponse?.loginUrl && (
                  <button
                    type="button"
                    className="subtle-button"
                    style={{ fontSize: '12px', padding: '4px 10px' }}
                    onClick={openBuiltInBrowser}
                  >
                    🖥 {t('source.loginWithBrowser', '用内置浏览器登录')}
                  </button>
                )}
              </div>

              <div className="direct-cookie-card" style={{ background: 'var(--card-bg, rgba(255,255,255,0.03))', border: '1px solid var(--line)', borderRadius: '8px', padding: '14px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <label style={{ fontSize: '12px', fontWeight: 600, color: 'var(--foreground)' }}>
                    🍪 {t('source.pasteCookieToken', '粘贴 Cookie / Token (支持 JSON / 键值对)')}
                  </label>
                  <span style={{ fontSize: '11px', color: 'var(--muted)' }}>
                    {t('source.cookieEditorSupport', '支持 Cookie-Editor 导出的 JSON')}
                  </span>
                </div>
                <textarea
                  className="source-login-textarea"
                  rows={5}
                  value={cookieInputText}
                  placeholder={t('source.cookiePlaceholder', '直接粘贴 Cookie 字符串（k1=v1; k2=v2）或 Cookie-Editor 导出的 JSON 数组：\n[{"name":"session_id","value":"..."},{"name":"token","value":"..."}]')}
                  onChange={e => setCookieInputText(e.target.value)}
                />
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '8px' }}>
                  <div style={{ display: 'flex', gap: '6px' }}>
                    <button
                      type="button"
                      className="subtle-button"
                      style={{ fontSize: '12px', padding: '5px 10px' }}
                      onClick={() => setBookmarkletModalOpen(true)}
                    >
                      ⚡ {t('source.quickSyncBookmark', '一键同步书签')}
                    </button>
                    {uiResponse?.loginHeader && (
                      <button
                        type="button"
                        className="subtle-button danger-text"
                        style={{ fontSize: '12px', padding: '5px 10px' }}
                        onClick={handleDeleteLoginHeader}
                      >
                        🗑️ {t('source.clearSavedCredentials', '清除已存凭据')}
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    className="primary-button"
                    disabled={executing || !cookieInputText.trim()}
                    onClick={handleSaveCookie}
                  >
                    {executing ? t('source.saving', '保存中...') : t('source.saveCookie', '保存 Cookie')}
                  </button>
                </div>
              </div>

              {uiResponse?.loginHeader && (
                <div style={{ padding: '10px 14px', background: 'rgba(56, 189, 248, 0.08)', border: '1px solid rgba(56, 189, 248, 0.2)', borderRadius: '6px', fontSize: '12px' }}>
                  <div style={{ fontWeight: 600, color: '#38bdf8', marginBottom: '4px' }}>✅ {t('source.hasPersistedLoginHeader', '当前书源已持久化登录头 (Login Header)')}</div>
                  <div style={{ color: 'var(--muted)', wordBreak: 'break-all', fontFamily: 'monospace', maxHeight: '60px', overflowY: 'auto' }}>
                    {uiResponse.loginHeader}
                  </div>
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
              <div style={{ flex: '1 1 100%', width: '100%' }}>
                <button
                  type="button"
                  className="primary-button"
                  style={{ width: '100%' }}
                  onClick={openBuiltInBrowser}
                >
                  🖥 {t('source.loginWithBrowserRecommended', '用内置浏览器登录（推荐）')}
                </button>
              </div>
              {uiResponse.loginUi.map((item, idx) => renderControl(item, idx))}
            </form>
          )}
        </div>

        {headerEditOpen && (
          <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setHeaderEditOpen(false)}>
            <div className="source-login-dialog" style={{ width: 'min(500px, 94vw)' }} onClick={e => e.stopPropagation()}>
              <div className="source-login-header">
                <h3>{t('source.editLoginHeaderModalTitle', '编辑 / 填入登录头 (Login Header)')}</h3>
                <button type="button" className="source-login-action-btn" onClick={() => setHeaderEditOpen(false)}>✕</button>
              </div>
              <div className="source-login-body" style={{ padding: '16px' }}>
                <p style={{ margin: '0 0 10px', fontSize: '12px', color: 'var(--muted)' }}>
                  {t('source.editLoginHeaderDesc', '支持 JSON 格式（如 {"Authorization": "Bearer ...", "Cookie": "..."}）或直接文本。')}
                </p>
                <textarea
                  className="source-login-textarea"
                  rows={6}
                  value={headerEditText}
                  placeholder='{"Authorization": "Bearer ...", "Cookie": "..."}'
                  onChange={e => setHeaderEditText(e.target.value)}
                />
              </div>
              <div style={{ padding: '12px 18px', display: 'flex', justifyContent: 'flex-end', gap: '8px', borderTop: '1px solid var(--line)' }}>
                <button type="button" className="subtle-button" onClick={() => setHeaderEditOpen(false)}>{t('common.cancel', '取消')}</button>
                <button type="button" className="primary-button" disabled={executing} onClick={handleSaveHeader}>{t('source.saveLoginHeader', '保存登录头')}</button>
              </div>
            </div>
          </div>
        )}

        {cookieModalOpen && (
          <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setCookieModalOpen(false)}>
            <div className="source-login-dialog" style={{ width: 'min(500px, 94vw)' }} onClick={e => e.stopPropagation()}>
              <div className="source-login-header">
                <h3>{t('source.quickFillCookieModalTitle', '快捷填入 Cookie')}</h3>
                <button type="button" className="source-login-action-btn" onClick={() => setCookieModalOpen(false)}>✕</button>
              </div>
              <div className="source-login-body" style={{ padding: '16px' }}>
                <p style={{ margin: '0 0 10px', fontSize: '12px', color: 'var(--muted)' }}>
                  {t('source.quickFillCookieDesc', '将目标网站登录后的 Cookie 字符串粘贴在下方（服务端将自动按域名持久化合并）：')}
                </p>
                <textarea
                  className="source-login-textarea"
                  rows={5}
                  value={cookieInputText}
                  placeholder="token=xxxx; uid=12345; session=yyyy"
                  onChange={e => setCookieInputText(e.target.value)}
                />
              </div>
              <div style={{ padding: '12px 18px', display: 'flex', justifyContent: 'flex-end', gap: '8px', borderTop: '1px solid var(--line)' }}>
                <button type="button" className="subtle-button" onClick={() => setCookieModalOpen(false)}>{t('common.cancel', '取消')}</button>
                <button type="button" className="primary-button" disabled={executing} onClick={handleSaveCookie}>{t('source.saveCookie', '保存 Cookie')}</button>
              </div>
            </div>
          </div>
        )}

        {bookmarkletModalOpen && (
          <div className="modal-backdrop top-layer-modal-backdrop" onClick={() => setBookmarkletModalOpen(false)}>
            <div className="source-login-dialog" style={{ width: 'min(520px, 94vw)' }} onClick={e => e.stopPropagation()}>
              <div className="source-login-header">
                <h3>⚡ {t('source.bookmarkletModalTitle', '一键同步书签 (Bookmarklet)')}</h3>
                <button type="button" className="source-login-action-btn" onClick={() => setBookmarkletModalOpen(false)}>✕</button>
              </div>
              <div className="source-login-body" style={{ padding: '16px', display: 'grid', gap: '12px' }}>
                <p style={{ margin: 0, fontSize: '13px', lineHeight: 1.5, color: 'var(--ink)' }}>
                  {t('source.bookmarkletDesc', '在新标签页打开目标小说网站完成登录后，只需在书签栏点击一下此书签，当前站点的 Cookie / Token 即可秒级自动同步回阅读服务器！')}
                </p>
                <div style={{ background: 'var(--surface-muted)', padding: '10px 12px', borderRadius: '6px', border: '1px solid var(--line)' }}>
                  <span style={{ fontSize: '12px', color: 'var(--muted)', display: 'block', marginBottom: '6px' }}>
                    📖 {t('source.bookmarkletUsageTitle', '使用方法：')}
                  </span>
                  <ol style={{ margin: 0, paddingLeft: '18px', fontSize: '12px', color: 'var(--muted)', display: 'grid', gap: '4px' }}>
                    <li>{t('source.bookmarkletStep1', '点击下方按钮复制书签代码；')}</li>
                    <li>{t('source.bookmarkletStep2', '在浏览器书签栏新建书签，网址处粘贴该代码；')}</li>
                    <li>{t('source.bookmarkletStep3', '在新页面登录网站后，直接点击该书签即可一键回传。')}</li>
                  </ol>
                </div>
                <textarea
                  className="source-login-textarea"
                  rows={4}
                  readOnly
                  value={bookmarkletCode}
                  style={{ fontSize: '11px', fontFamily: 'monospace' }}
                />
              </div>
              <div style={{ padding: '12px 18px', display: 'flex', justifyContent: 'flex-end', gap: '8px', borderTop: '1px solid var(--line)' }}>
                <button type="button" className="subtle-button" onClick={() => setBookmarkletModalOpen(false)}>{t('common.close', '关闭')}</button>
                <button type="button" className="primary-button" onClick={copyBookmarklet}>📋 {t('source.copyBookmarkletCode', '复制书签代码')}</button>
              </div>
            </div>
          </div>
        )}
        {webViewOpen && (
          <SourceWebViewModal
            sourceId={sourceId}
            sourceName={sourceName}
            startUrl={webViewStartUrl}
            onClose={() => setWebViewOpen(false)}
            onToast={onToast}
            onSettingsSaved={() => {
              // 设置已落库：本轮浏览器会话的使命完成，不能再重跑触发动作
              // （否则书源 JS 会再次返回 data: 内联页地址，把设置页又开一遍）
              pendingBrowserActionRef.current = null
              void loadLoginUi()
            }}
            onLoggedIn={async () => {
              await loadLoginUi()
              // 内置浏览器登录成功后，带着已落库的 Cookie 重跑当初触发浏览器的动作，
              // 让书源的 login()/fq_login() 等 JS 能真正完成登录校验。
              const pending = pendingBrowserActionRef.current
              if (pending) {
                pendingBrowserActionRef.current = null
                await handleAction(pending, false, false)
              }
            }}
            onImportOnline={url => setNetworkImportUrl(url)}
          />
        )}
      </div>

      {/*
        网络导入弹窗：必须与内置浏览器弹窗**平级**挂载，严禁嵌在它的 .modal-backdrop 内部
        （仓库既有约定：带 animation/transform 的遮罩会形成层叠上下文，嵌套会导致幽灵穿透）。
      */}
      {networkImportUrl !== null && (
        <NetworkImportModal
          initialUrl={networkImportUrl}
          onClose={() => setNetworkImportUrl(null)}
          onImported={() => { void loadLoginUi() }}
          onToast={onToast}
        />
      )}
    </div>
  )
}
