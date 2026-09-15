import React, { useEffect, useState } from 'react'
import { Icon } from './icons'
import { Logo } from './Logo'

interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[]
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>
  prompt(): Promise<void>
}

declare global {
  interface WindowEventMap {
    beforeinstallprompt: BeforeInstallPromptEvent
    appinstalled: Event
  }
}

let deferredInstallPrompt: BeforeInstallPromptEvent | null = null
const installPromptListeners = new Set<(canInstall: boolean) => void>()

export function isPwaStandalone(): boolean {
  if (typeof window === 'undefined') return false
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    (window.navigator as any).standalone === true ||
    document.referrer.includes('android-app://')
  )
}

export function subscribePwaInstall(callback: (canInstall: boolean) => void): () => void {
  installPromptListeners.add(callback)
  callback(deferredInstallPrompt !== null && !isPwaStandalone())
  return () => installPromptListeners.delete(callback)
}

export async function promptPwaInstall(): Promise<boolean> {
  if (!deferredInstallPrompt) return false
  try {
    await deferredInstallPrompt.prompt()
    const choice = await deferredInstallPrompt.userChoice
    deferredInstallPrompt = null
    installPromptListeners.forEach(cb => cb(false))
    return choice.outcome === 'accepted'
  } catch {
    return false
  }
}

export function PwaManager() {
  const [canInstall, setCanInstall] = useState(false)
  const [dismissedBanner, setDismissedBanner] = useState(() => {
    try {
      return localStorage.getItem('legado_pwa_banner_dismissed') === 'true'
    } catch {
      return false
    }
  })
  const [needRefresh, setNeedRefresh] = useState(false)
  const [registration, setRegistration] = useState<ServiceWorkerRegistration | null>(null)

  useEffect(() => {
    // 1. Listen for install prompt
    const handleBeforeInstallPrompt = (e: BeforeInstallPromptEvent) => {
      e.preventDefault()
      deferredInstallPrompt = e
      setCanInstall(true)
      installPromptListeners.forEach(cb => cb(true))
    }

    const handleAppInstalled = () => {
      deferredInstallPrompt = null
      setCanInstall(false)
      installPromptListeners.forEach(cb => cb(false))
    }

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
    window.addEventListener('appinstalled', handleAppInstalled)

    // 2. Service Worker registration and update detection
    if ('serviceWorker' in navigator && process.env.NODE_ENV !== 'test') {
      navigator.serviceWorker.register('/sw.js', { scope: '/' }).then(reg => {
        setRegistration(reg)

        reg.addEventListener('updatefound', () => {
          const newWorker = reg.installing
          if (newWorker) {
            newWorker.addEventListener('statechange', () => {
              if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                setNeedRefresh(true)
              }
            })
          }
        })
      }).catch(() => {
        // SW register failed or disabled
      })

      let refreshing = false
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        if (!refreshing) {
          refreshing = true
          window.location.reload()
        }
      })
    }

    return () => {
      window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt)
      window.removeEventListener('appinstalled', handleAppInstalled)
    }
  }, [])

  const handleInstallClick = async () => {
    const accepted = await promptPwaInstall()
    if (accepted) {
      setCanInstall(false)
    }
  }

  const handleDismissBanner = () => {
    setDismissedBanner(true)
    try {
      localStorage.setItem('legado_pwa_banner_dismissed', 'true')
    } catch {
      // ignore
    }
  }

  const handleUpdate = () => {
    if (registration && registration.waiting) {
      registration.waiting.postMessage({ type: 'SKIP_WAITING' })
    } else {
      window.location.reload()
    }
  }

  const showBanner = canInstall && !dismissedBanner && !isPwaStandalone()

  return (
    <>
      {/* PWA New Version Update Toast */}
      {needRefresh && (
        <div className="pwa-update-toast" role="alert">
          <div className="pwa-update-content">
            <span className="pwa-update-dot" />
            <div className="pwa-update-text">
              <strong>发现新版本</strong>
              <small>服务端功能已更新，点击即刻生效</small>
            </div>
          </div>
          <div className="pwa-update-actions">
            <button type="button" className="primary-button pwa-update-btn" onClick={handleUpdate}>
              立即更新
            </button>
            <button type="button" className="subtle-button pwa-close-btn" onClick={() => setNeedRefresh(false)}>
              <Icon name="close" />
            </button>
          </div>
        </div>
      )}

      {/* PWA Install Bottom Banner */}
      {showBanner && (
        <div className="pwa-install-banner" role="region" aria-label="安装应用提示">
          <div className="pwa-banner-left">
            <div className="pwa-banner-logo">
              <Logo size={28} />
            </div>
            <div className="pwa-banner-info">
              <strong>安装「阅读」到桌面 / 主屏幕</strong>
              <p>享受无浏览器工具栏的沉浸式全屏与离线阅读体验</p>
            </div>
          </div>
          <div className="pwa-banner-actions">
            <button type="button" className="primary-button pwa-install-btn" onClick={() => void handleInstallClick()}>
              <Icon name="download" />
              <span>立即安装</span>
            </button>
            <button type="button" className="subtle-button pwa-dismiss-btn" onClick={handleDismissBanner} aria-label="稍后提醒">
              <Icon name="close" />
            </button>
          </div>
        </div>
      )}
    </>
  )
}
