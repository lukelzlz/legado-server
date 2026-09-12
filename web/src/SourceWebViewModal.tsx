import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'

interface SourceWebViewModalProps {
  sourceId: string
  sourceName: string
  /** 书源 JS 通过 java.startBrowserAwait(url) 指定的入口地址，优先于书源配置的 loginUrl */
  startUrl?: string
  onClose: () => void
  onToast: (message: string, type?: 'info' | 'success' | 'error') => void
  onLoggedIn?: () => void
}

type NavState = { list: string[]; index: number }

/**
 * 内置登录浏览器（网页版 WebView）。
 *
 * 目标站点由服务端整站反向代理到本服务路径下，因此可以安全地放进 iframe：
 * - iframe 故意**不开启 allow-same-origin**，被代理页面的 JS 无法触碰本应用的 DOM；
 * - 代理端点用一次性令牌鉴权，不依赖管理会话 Cookie；
 * - 目标站点的 Set-Cookie 会被服务端吸收进书源 Cookie jar，登录成功即刻生效。
 */
export const SourceWebViewModal: React.FC<SourceWebViewModalProps> = ({
  sourceId,
  sourceName,
  startUrl: startUrlOverride,
  onClose,
  onToast,
  onLoggedIn,
}) => {
  const [token, setToken] = useState('')
  const [startUrl, setStartUrl] = useState('')
  const [navTarget, setNavTarget] = useState('')
  const [inlineKey, setInlineKey] = useState('')
  const [navSeq, setNavSeq] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [nav, setNav] = useState<NavState>({ list: [], index: -1 })
  const [cookieCount, setCookieCount] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [addressDraft, setAddressDraft] = useState('')
  const tokenRef = useRef('')

  const currentUrl = nav.list[nav.index] ?? ''

  // 建立代理会话
  useEffect(() => {
    let cancelled = false
    const open = async () => {
      try {
        const session = await api.createSourceBrowserSession(sourceId, startUrlOverride)
        if (cancelled) return
        tokenRef.current = session.token
        setToken(session.token)
        setStartUrl(session.startUrl)
        if (session.inlineOnly) {
          // 书源脚本生成的自包含页面（教程 / 更新 / 设置中心）：由服务端解码托管，
          // 直接把上萬字符的数据地址塞进 iframe 会触发请求行 8192 上限。
          const { key } = await api.registerSourceBrowserInline(sourceId, session.token, (startUrlOverride ?? '').trim())
          if (cancelled) return
          setInlineKey(key)
          setAddressDraft('内置页面（由书源脚本生成）')
          setNav({ list: ['内置页面'], index: 0 })
        } else {
          setNavTarget(session.startUrl)
          setAddressDraft(session.startUrl)
          setNav({ list: [session.startUrl], index: 0 })
        }
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : '无法打开内置浏览器')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void open()
    return () => {
      cancelled = true
      if (tokenRef.current) void api.closeSourceBrowserSession(sourceId, tokenRef.current).catch(() => undefined)
    }
  }, [sourceId, startUrlOverride])

  // 监听被代理页面回报的地址变化，用于地址栏与前进/后退
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const data = event.data as { source?: string; type?: string; url?: string } | null
      if (!data || data.source !== 'legado-webview' || data.type !== 'navigated') return
      const url = typeof data.url === 'string' ? data.url : ''
      if (!url) return
      setNav(prev => {
        if (prev.list[prev.index] === url) return prev
        const list = prev.list.slice(0, prev.index + 1)
        list.push(url)
        return { list, index: list.length - 1 }
      })
      setAddressDraft(url)
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [])

  const refreshCookies = useCallback(async () => {
    try {
      const result = await api.sourceBrowserCookies(sourceId)
      setCookieCount(result.count)
      return result.count
    } catch {
      return null
    }
  }, [sourceId])

  useEffect(() => {
    if (!token) return
    void refreshCookies()
  }, [token, nav.index, refreshCookies])

  const pageSrc = useMemo(() => {
    if (!token) return ''
    if (inlineKey) return api.sourceBrowserInlineUrl(sourceId, token, inlineKey)
    return navTarget ? api.sourceBrowserPageUrl(sourceId, token, navTarget) : ''
  }, [sourceId, token, navTarget, inlineKey])

  const navigate = useCallback(
    (url: string) => {
      if (!url) return
      setLoading(true)
      setInlineKey('')
      setNavTarget(url)
      setAddressDraft(url)
      setNavSeq(seq => seq + 1)
    },
    [],
  )

  const canGoBack = nav.index > 0
  const canGoForward = nav.index >= 0 && nav.index < nav.list.length - 1

  const handleCheckLogin = async () => {
    setBusy(true)
    try {
      const count = await refreshCookies()
      const status = await api.checkSourceLogin(sourceId)
      if (status.loggedIn) {
        onToast(`登录成功，已捕获 ${count ?? 0} 个域名的 Cookie`, 'success')
        onLoggedIn?.()
        setTimeout(onClose, 600)
      } else {
        onToast(status.message ?? '尚未检测到有效登录凭据，请完成站点登录后重试', 'info')
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '检查登录状态失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const submitAddress = (event: React.FormEvent) => {
    event.preventDefault()
    const value = addressDraft.trim()
    if (!value) return
    navigate(/^https?:\/\//i.test(value) ? value : `https://${value}`)
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="source-webview-dialog" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="source-webview-toolbar">
          <div className="source-webview-nav-group">
            <button
              type="button"
              className="source-webview-icon-btn"
              title="后退"
              disabled={!canGoBack}
              onClick={() => canGoBack && navigate(nav.list[nav.index - 1])}
            >
              ‹
            </button>
            <button
              type="button"
              className="source-webview-icon-btn"
              title="前进"
              disabled={!canGoForward}
              onClick={() => canGoForward && navigate(nav.list[nav.index + 1])}
            >
              ›
            </button>
            <button
              type="button"
              className="source-webview-icon-btn"
              title="刷新"
              disabled={!token}
              onClick={() => {
                setLoading(true)
                setNavSeq(seq => seq + 1)
              }}
            >
              ⟳
            </button>
            <button
              type="button"
              className="source-webview-icon-btn"
              title="回到登录页"
              disabled={!startUrl}
              onClick={() => navigate(startUrl)}
            >
              ⌂
            </button>
          </div>

          <form className="source-webview-address-form" onSubmit={submitAddress}>
            <input
              className="source-webview-address"
              value={addressDraft}
              spellCheck={false}
              onChange={e => setAddressDraft(e.target.value)}
              placeholder="输入该书源站点内的网址"
            />
          </form>

          <div className="source-webview-actions">
            {cookieCount !== null && (
              <span className="source-webview-cookie-badge" title="已捕获的 Cookie 域名数">
                🍪 {cookieCount}
              </span>
            )}
            <button
              type="button"
              className="source-webview-icon-btn"
              title="在新标签页打开"
              disabled={!pageSrc}
              onClick={() => pageSrc && window.open(pageSrc, '_blank', 'noopener,noreferrer')}
            >
              ⧉
            </button>
            <button
              type="button"
              className="primary-button source-webview-login-btn"
              disabled={busy || !token}
              onClick={handleCheckLogin}
            >
              {busy ? '检测中...' : '✓ 登录完成'}
            </button>
            <button type="button" className="source-webview-icon-btn" title="关闭" onClick={onClose}>
              ✕
            </button>
          </div>
        </div>

        <div className="source-webview-body">
          {error ? (
            <div className="source-webview-placeholder error">
              <div className="source-webview-placeholder-title">无法打开内置浏览器</div>
              <div className="source-webview-placeholder-text">{error}</div>
              <div className="source-webview-placeholder-text">
                可在书源中补全 <code>loginUrl</code> 后重试，或改用「快捷填入 Cookie / 一键同步书签」方式。
              </div>
            </div>
          ) : (
            <>
              {loading && <div className="source-webview-loading">正在打开 {sourceName} ...</div>}
              {pageSrc && (
                <iframe
                  key={navSeq}
                  className="source-webview-frame"
                  title={`${sourceName} 登录`}
                  src={pageSrc}
                  sandbox="allow-forms allow-scripts allow-popups allow-modals allow-downloads"
                  referrerPolicy="no-referrer"
                  onLoad={() => setLoading(false)}
                />
              )}
            </>
          )}
        </div>

        <div className="source-webview-footnote">
          页面由服务端代理渲染（站点 JS 在隔离沙箱中运行，无法访问本应用数据）。登录成功后 Cookie 会自动保存到该书源，无需手动复制。
        </div>
      </div>
    </div>
  )
}
