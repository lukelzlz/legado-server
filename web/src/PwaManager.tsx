import React, { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from './i18n'
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

let activeRegistration: ServiceWorkerRegistration | null = null
let triggerNeedRefresh: ((val: boolean) => void) | null = null

export async function checkForAppUpdate(): Promise<{ hasUpdate: boolean; message: string }> {
  const t = i18n.t.bind(i18n)
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return { hasUpdate: false, message: t('pwa.swNotEnabled') }
  }
  try {
    const reg = activeRegistration || await navigator.serviceWorker.getRegistration()
    if (!reg) {
      return { hasUpdate: false, message: t('pwa.appUpToDate') }
    }
    if (reg.waiting) {
      if (triggerNeedRefresh) triggerNeedRefresh(true)
      return { hasUpdate: true, message: t('pwa.newVersionReady') }
    }
    await reg.update()
    if (reg.waiting || reg.installing) {
      if (triggerNeedRefresh) triggerNeedRefresh(true)
      return { hasUpdate: true, message: t('pwa.newVersionPreparing') }
    }
    return { hasUpdate: false, message: t('pwa.appUpToDate') }
  } catch {
    return { hasUpdate: false, message: t('pwa.updateCheckDone') }
  }
}

export function PwaManager() {
  const { t } = useTranslation()
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
  const registrationRef = useRef<ServiceWorkerRegistration | null>(null)

  useEffect(() => {
    triggerNeedRefresh = setNeedRefresh

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
    let checkForUpdate: (() => void) | null = null
    let updateTimer = 0
    const isTestEnv = typeof (globalThis as { process?: { env?: { NODE_ENV?: string } } }).process?.env?.NODE_ENV !== 'undefined' &&
      (globalThis as { process?: { env?: { NODE_ENV?: string } } }).process?.env?.NODE_ENV === 'test'
    if ('serviceWorker' in navigator && !isTestEnv) {
      navigator.serviceWorker.register('/sw.js', { scope: '/' }).then(reg => {
        registrationRef.current = reg
        activeRegistration = reg
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

        // 旧版 Service Worker 已装好并处于等待态时（例如上次错过了更新提示），立即给出提示
        if (reg.waiting && navigator.serviceWorker.controller) setNeedRefresh(true)

        // 补一次立即检查
        void reg.update().catch(() => undefined)
      }).catch(() => {
        // SW register failed or disabled
      })

      // 页面长期打开 / 从后台切回 (pageshow/visibilitychange/online) 时主动检查更新
      checkForUpdate = () => {
        const r = registrationRef.current || activeRegistration
        void r?.update().catch(() => undefined)
      }
      window.addEventListener('focus', checkForUpdate)
      window.addEventListener('pageshow', checkForUpdate)
      window.addEventListener('online', checkForUpdate)
      document.addEventListener('visibilitychange', checkForUpdate)
      updateTimer = window.setInterval(checkForUpdate, 5 * 60 * 1000)

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
      if (checkForUpdate) {
        window.removeEventListener('focus', checkForUpdate)
        window.removeEventListener('pageshow', checkForUpdate)
        window.removeEventListener('online', checkForUpdate)
        document.removeEventListener('visibilitychange', checkForUpdate)
      }
      if (updateTimer) window.clearInterval(updateTimer)
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
              <strong>{t('pwa.updateAvailable')}</strong>
              <small>{t('pwa.updateDesc')}</small>
            </div>
          </div>
          <div className="pwa-update-actions">
            <button type="button" className="primary-button pwa-update-btn" onClick={handleUpdate}>
              {t('pwa.updateNow')}
            </button>
            <button type="button" className="subtle-button pwa-close-btn" onClick={() => setNeedRefresh(false)}>
              <Icon name="close" />
            </button>
          </div>
        </div>
      )}

      {/* PWA Install Bottom Banner */}
      {showBanner && (
        <div className="pwa-install-banner" role="region" aria-label={t('pwa.installBannerAria')}>
          <div className="pwa-banner-left">
            <div className="pwa-banner-logo">
              <Logo size={28} />
            </div>
            <div className="pwa-banner-info">
              <strong>{t('pwa.installTitle')}</strong>
              <p>{t('pwa.installDesc')}</p>
            </div>
          </div>
          <div className="pwa-banner-actions">
            <button type="button" className="primary-button pwa-install-btn" onClick={() => void handleInstallClick()}>
              <Icon name="download" />
              <span>{t('pwa.installNow')}</span>
            </button>
            <button type="button" className="subtle-button pwa-dismiss-btn" onClick={handleDismissBanner} aria-label={t('pwa.remindLater')}>
              <Icon name="close" />
            </button>
          </div>
        </div>
      )}
    </>
  )
}
