import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api } from './api'
import {
  isBubbleResult,
  mergeBubbleResult,
  readSettingsResultMessage,
  type SettingsPayload,
} from './sourceSettingsResult'

interface SourceWebViewModalProps {
  sourceId: string
  sourceName: string
  /** 书源 JS 通过 java.startBrowserAwait(url) 指定的入口地址，优先于书源配置的 loginUrl */
  startUrl?: string
  onClose: () => void
  onToast: (message: string, type?: 'info' | 'success' | 'error') => void
  onLoggedIn?: () => void
  /** 书源设置结果已落库：调用方据此刷新源变量显示，并取消「重跑触发动作」 */
  onSettingsSaved?: () => void
  /**
   * 被代理页面里点了书源的「更新书源」线路链接
   * （`yuedu://booksource/importonline?src=…`，由代理层在捕获阶段拦下并换算成真实 http(s) 地址）。
   * 调用方据此打开「网络导入」弹窗。见 [ADR-023]。
   */
  onImportOnline?: (url: string) => void
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
  onSettingsSaved,
  onImportOnline,
}) => {
  const { t } = useTranslation()
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
  const iframeRef = useRef<HTMLIFrameElement | null>(null)
  /** 页面回传的最新设置结果 / 气泡结果；两者在提交前合并（与书源 JS 的合并口径一致） */
  const settingsRef = useRef<SettingsPayload | null>(null)
  const bubbleRef = useRef<SettingsPayload | null>(null)
  const resultIdRef = useRef('')
  const savedRef = useRef(false)
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 用 ref 持有回调，避免父组件每次渲染都重建消息监听（与 flushRef 同一手法）。 */
  const onImportOnlineRef = useRef<((url: string) => void) | undefined>(onImportOnline)
  onImportOnlineRef.current = onImportOnline

  /**
   * 把页面回传的设置结果提交给服务端落库。
   *
   * 安卓端由 `startBrowserAwait` 返回关闭时的页面 body 完成这一步；无头端改成
   * 「代理层采集 → postMessage → 这里回传」，因此必须显式 flush（关闭弹窗 / 检测登录前）。
   */
  const flushSettings = useCallback(async () => {
    if (saveTimerRef.current) {
      clearTimeout(saveTimerRef.current)
      saveTimerRef.current = null
    }
    const settings = mergeBubbleResult(settingsRef.current, bubbleRef.current)
    const currentToken = tokenRef.current
    if (!settings || !currentToken) return
    try {
      await api.saveSourceBrowserResult(sourceId, currentToken, settings, resultIdRef.current)
      if (!savedRef.current) {
        savedRef.current = true
        onToast(t('source.settingsSaved', '书源设置已保存'), 'success')
        onSettingsSaved?.()
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('source.settingsSaveFailed', '书源设置保存失败'), 'error')
    }
  }, [sourceId, onToast, onSettingsSaved, t])

  // 会话建立 effect 的依赖必须保持稳定，否则父组件每次渲染都会重建浏览器会话
  const flushRef = useRef(flushSettings)
  flushRef.current = flushSettings

  // 页面每次改动都会重写结果容器，这里做短防抖合并，避免逐次写库
  const scheduleFlush = useCallback(() => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    saveTimerRef.current = setTimeout(() => { void flushRef.current() }, 300)
  }, [])

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
          setAddressDraft(t('source.builtInPageScriptGenerated', '内置页面（由书源脚本生成）'))
          setNav({ list: [t('source.builtInPage', '内置页面')], index: 0 })
        } else {
          setNavTarget(session.startUrl)
          setAddressDraft(session.startUrl)
          setNav({ list: [session.startUrl], index: 0 })
        }
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : t('source.cannotOpenBrowser', '无法打开内置浏览器'))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void open()
    return () => {
      cancelled = true
      // 关闭弹窗前把最后一次设置结果落库，再回收票据
      void flushRef.current().finally(() => {
        if (tokenRef.current) void api.closeSourceBrowserSession(sourceId, tokenRef.current).catch(() => undefined)
      })
    }
  }, [sourceId, startUrlOverride, t])

  // 监听被代理页面回报的地址变化与设置结果
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const data = event.data as { source?: string; type?: string; url?: string } | null
      if (!data || data.source !== 'legado-webview') return
      if (data.type === 'settings-result') {
        // iframe 是不透明源（sandbox 无 allow-same-origin），必须靠 event.source 认定来源
        if (!iframeRef.current || event.source !== iframeRef.current.contentWindow) return
        const parsed = readSettingsResultMessage(data)
        if (!parsed) return
        if (isBubbleResult(parsed.resultId)) bubbleRef.current = parsed.settings
        else settingsRef.current = parsed.settings
        resultIdRef.current = parsed.resultId
        scheduleFlush()
        return
      }
      if (data.type === 'import-online') {
        // 同样是跨源消息，必须靠 event.source 认定来源，不能只信 payload
        if (!iframeRef.current || event.source !== iframeRef.current.contentWindow) return
        const target = typeof data.url === 'string' ? data.url.trim() : ''
        if (!target) return
        onImportOnlineRef.current?.(target)
        return
      }
      if (data.type !== 'navigated') return
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
  }, [scheduleFlush])

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
      // 先落库设置结果，避免「检测登录」触发关闭时把用户在设置页做的改动丢掉
      await flushRef.current()
      const count = await refreshCookies()
      const status = await api.checkSourceLogin(sourceId)
      if (status.loggedIn) {
        onToast(t('source.browserLoginSuccess', { count: count ?? 0, defaultValue: `登录成功，已捕获 ${count ?? 0} 个域名的 Cookie` }), 'success')
        onLoggedIn?.()
        setTimeout(onClose, 600)
      } else {
        onToast(status.message ?? t('source.noValidCredentialsDetected', '尚未检测到有效登录凭据，请完成站点登录后重试'), 'info')
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('source.checkLoginFailed', '检查登录状态失败'), 'error')
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
    // is-blurless：关掉毛玻璃。实测 backdrop-filter + 内部 iframe 持续重绘 = 8 FPS
    // （hover 动画卡成幻灯片），两层都去掉后回到 58 FPS。详见 styles.css 的注释。
    <div className="modal-backdrop is-blurless" onClick={onClose}>
      <div className="source-webview-dialog" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="source-webview-toolbar">
          <div className="source-webview-nav-group">
            <button
              type="button"
              className="source-webview-icon-btn"
              title={t('source.browserBack', '后退')}
              disabled={!canGoBack}
              onClick={() => canGoBack && navigate(nav.list[nav.index - 1])}
            >
              ‹
            </button>
            <button
              type="button"
              className="source-webview-icon-btn"
              title={t('source.browserForward', '前进')}
              disabled={!canGoForward}
              onClick={() => canGoForward && navigate(nav.list[nav.index + 1])}
            >
              ›
            </button>
            <button
              type="button"
              className="source-webview-icon-btn"
              title={t('source.browserRefresh', '刷新')}
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
              title={t('source.browserHome', '回到登录页')}
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
              placeholder={t('source.browserUrlPlaceholder', '输入该书源站点内的网址')}
            />
          </form>

          <div className="source-webview-actions">
            {cookieCount !== null && (
              <span className="source-webview-cookie-badge" title={t('source.capturedCookieCount', '已捕获的 Cookie 域名数')}>
                🍪 {cookieCount}
              </span>
            )}
            <button
              type="button"
              className="source-webview-icon-btn"
              title={t('source.openInNewTab', '在新标签页打开')}
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
              {busy ? t('source.loginChecking', '检测中...') : `✓ ${t('source.loginCompleted', '登录完成')}`}
            </button>
            <button type="button" className="source-webview-icon-btn" title={t('common.close', '关闭')} onClick={onClose}>
              ✕
            </button>
          </div>
        </div>

        <div className="source-webview-body">
          {error ? (
            <div className="source-webview-placeholder error">
              <div className="source-webview-placeholder-title">{t('source.cannotOpenBrowser', '无法打开内置浏览器')}</div>
              <div className="source-webview-placeholder-text">{error}</div>
              <div className="source-webview-placeholder-text">
                {t('source.cannotOpenBrowserHint', '可在书源中补全 loginUrl 后重试，或改用「快捷填入 Cookie / 一键同步书签」方式。')}
              </div>
            </div>
          ) : (
            <>
              {loading && <div className="source-webview-loading">{t('source.openingSourceSite', { name: sourceName, defaultValue: `正在打开 ${sourceName} ...` })}</div>}
              {pageSrc && (
                <iframe
                  key={navSeq}
                  ref={iframeRef}
                  className="source-webview-frame"
                  title={t('source.sourceSiteLoginTitle', { name: sourceName, defaultValue: `${sourceName} 登录` })}
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
          {t('source.browserFootnote', '页面由服务端代理渲染（站点 JS 在隔离沙箱中运行，无法访问本应用数据）。登录成功后 Cookie 会自动保存到该书源，无需手动复制。')}
        </div>
      </div>
    </div>
  )
}
