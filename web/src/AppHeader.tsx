import React, { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { Icon } from './icons'
import { Logo } from './Logo'
import { checkForAppUpdate, promptPwaInstall, subscribePwaInstall } from './PwaManager'
import { toast } from './Toast'
import type { ReaderSettings } from './readerSettings'
import { SUPPORTED_LOCALES, type SupportedLocale, changeAppLanguage, getCurrentLocale } from './i18n'
import { api } from './api'

export type AppPage = 'sources' | 'subscriptions' | 'rss' | 'library' | 'shelf' | 'reader' | 'rules' | 'webdav' | 'explore'

export interface AppHeaderProps {
  page: AppPage
  settings: ReaderSettings
  searching?: boolean
  onSettingsChange: (next: ReaderSettings) => void
  onNavigate: (page: AppPage) => void
  onOpenReplaceRules?: () => void
  onOpenOfflineCache?: () => void
  onLogout: () => void
}

export const APP_THEMES: ReadonlyArray<{ id: ReaderSettings['theme']; name: string; key: string }> = [
  { id: 'light', name: '晓白', key: 'header.themeLight' },
  { id: 'paper', name: '护眼', key: 'header.themePaper' },
  { id: 'dark', name: '夜读', key: 'header.themeDark' },
]

export function AppHeader({
  page,
  settings,
  searching,
  onSettingsChange,
  onNavigate,
  onOpenReplaceRules,
  onOpenOfflineCache,
  onLogout,
}: AppHeaderProps) {
  const { t, i18n } = useTranslation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [canInstall, setCanInstall] = useState(false)
  const [checkingUpdate, setCheckingUpdate] = useState(false)

  useEffect(() => {
    return subscribePwaInstall(setCanInstall)
  }, [])

  const handlePwaInstall = async () => {
    setMenuOpen(false)
    await promptPwaInstall()
  }

  const handleCheckUpdate = async () => {
    setCheckingUpdate(true)
    try {
      const res = await checkForAppUpdate()
      if (res.hasUpdate) {
        toast.success(res.message)
      } else {
        toast.info(res.message)
      }
    } catch {
      toast.error(t('toast.checkUpdateFailed', '检查更新失败'))
    } finally {
      setCheckingUpdate(false)
    }
  }

  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const menuContainerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (
        menuContainerRef.current &&
        !menuContainerRef.current.contains(target) &&
        !menuButtonRef.current?.contains(target)
      ) {
        setMenuOpen(false)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('pointerdown', handlePointerDown)

    return () => {
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [menuOpen])

  const handleLogout = () => {
    if (window.confirm(t('header.logoutConfirm'))) {
      setMenuOpen(false)
      onLogout()
    }
  }

  const handleThemeChange = (theme: ReaderSettings['theme']) => {
    onSettingsChange({ ...settings, theme })
  }

  const handleLanguageChange = async (locale: SupportedLocale) => {
    await changeAppLanguage(locale)
    try {
      await api.setLocale(locale)
    } catch {
      // Ignore
    }
    const loc = SUPPORTED_LOCALES.find(l => l.code === locale)
    toast.success(i18n.t('toast.localeSwitched', { lng: locale, lang: loc?.nativeName || locale }))
  }

  const canUseDOM = typeof document !== 'undefined'

  const menuDropdownContent = menuOpen ? (
    <div className={`header-menu-portal-wrapper theme-${settings.theme}`}>
      <div
        className="header-menu-backdrop"
        onClick={() => setMenuOpen(false)}
        aria-hidden="true"
      />
      <div className={`header-menu-dropdown theme-${settings.theme}`} ref={menuContainerRef} role="menu" aria-label={t('header.functionMenu')}>
        <div className="menu-header">
          <div className="menu-status-badge">
            <span className="menu-status-dot" />
            <span className="menu-header-title">{t('header.serviceLoggedIn')}</span>
          </div>
        </div>

        <div className="menu-section">
          <div className="menu-section-label">{t('header.themeSection')}</div>
          <div className="menu-theme-grid" role="radiogroup" aria-label={t('header.themeSection')}>
            {APP_THEMES.map(themeItem => {
              const isSelected = settings.theme === themeItem.id
              return (
                <button
                  key={themeItem.id}
                  type="button"
                  className={`menu-theme-btn theme-option-${themeItem.id} ${isSelected ? 'selected' : ''}`}
                  role="radio"
                  aria-checked={isSelected}
                  onClick={() => handleThemeChange(themeItem.id)}
                >
                  <span className={`theme-swatch theme-swatch-${themeItem.id}`} />
                  <span className="theme-name">{t(themeItem.key, themeItem.name)}</span>
                  {isSelected && <Icon name="check" className="theme-check-icon" />}
                </button>
              )
            })}
          </div>
        </div>

        <div className="menu-divider" />

        <div className="menu-section">
          <div className="menu-section-label">{t('header.language')}</div>
          <div className="menu-lang-grid" role="radiogroup" aria-label={t('header.language')}>
            {SUPPORTED_LOCALES.map(loc => {
              const isSelected = (i18n.language || getCurrentLocale()) === loc.code
              return (
                <button
                  key={loc.code}
                  type="button"
                  className={`menu-lang-btn ${isSelected ? 'selected' : ''}`}
                  role="radio"
                  aria-checked={isSelected}
                  onClick={() => void handleLanguageChange(loc.code)}
                >
                  <span>{loc.nativeName}</span>
                  {isSelected && <Icon name="check" className="theme-check-icon" />}
                </button>
              )
            })}
          </div>
        </div>

        <div className="menu-divider" />

        <div className="menu-section">
          {canInstall && (
            <button
              type="button"
              className="menu-item-btn accent-text"
              role="menuitem"
              onClick={() => void handlePwaInstall()}
            >
              <Icon name="download" />
              <span>{t('header.installPwa')}</span>
            </button>
          )}
          <button
            type="button"
            className="menu-item-btn"
            role="menuitem"
            disabled={checkingUpdate}
            onClick={() => void handleCheckUpdate()}
          >
            <Icon name="refresh" />
            <span>{checkingUpdate ? t('header.checkingUpdate') : t('header.checkUpdate')}</span>
          </button>
          {onOpenOfflineCache && (
            <button
              type="button"
              className="menu-item-btn"
              role="menuitem"
              onClick={() => {
                setMenuOpen(false)
                onOpenOfflineCache()
              }}
            >
              <Icon name="book" />
              <span>{t('header.offlineCache')}</span>
            </button>
          )}
          <a
            href="/simple/"
            className="menu-link-btn"
            role="menuitem"
            title={t('header.kindleTitle')}
          >
            <Icon name="book" />
            <span>{t('header.kindle')}</span>
          </a>
        </div>

        <div className="menu-divider" />

        <div className="menu-section">
          <button
            type="button"
            className="menu-logout-btn"
            role="menuitem"
            onClick={handleLogout}
          >
            <Icon name="logOut" />
            <span>{t('header.logout')}</span>
          </button>
        </div>
      </div>
    </div>
  ) : null

  return (
    <header className="app-page-header">
      <button
        type="button"
        className="app-brand"
        onClick={() => onNavigate('library')}
        aria-label={t('login.brand')}
      >
        <Logo size={22} />
        <strong>{t('login.brand')}</strong>
      </button>

      <nav aria-label={t('header.mainNav')}>
        <button
          type="button"
          className={page === 'library' ? 'active' : ''}
          onClick={() => onNavigate('library')}
        >
          {t('header.library')}
          {searching && (
            <span
              className="nav-search-indicator"
              title={t('header.searchingInBackground')}
              aria-label={t('header.searchingInBackground')}
            />
          )}
        </button>
        <button
          type="button"
          className={page === 'explore' ? 'active' : ''}
          onClick={() => onNavigate('explore')}
        >
          {t('header.explore', '发现')}
        </button>
        <button
          type="button"
          className={page === 'shelf' ? 'active' : ''}
          onClick={() => onNavigate('shelf')}
        >
          {t('header.shelf')}
        </button>
        <button
          type="button"
          className={page === 'sources' ? 'active' : ''}
          onClick={() => onNavigate('sources')}
        >
          {t('header.sources')}
        </button>
        <button
          type="button"
          className={page === 'subscriptions' ? 'active' : ''}
          onClick={() => onNavigate('subscriptions')}
        >
          {t('header.subscriptions')}
        </button>
        <button
          type="button"
          className={page === 'rss' ? 'active' : ''}
          onClick={() => onNavigate('rss')}
        >
          {t('header.rss', '订阅源')}
        </button>
        <button
          type="button"
          className={page === 'rules' ? 'active' : ''}
          onClick={() => onNavigate('rules')}
        >
          {t('header.rules')}
        </button>
        <button
          type="button"
          className={page === 'webdav' ? 'active' : ''}
          title={t('header.webdavTitle')}
          onClick={() => onNavigate('webdav')}
        >
          {t('header.webdav')}
        </button>
      </nav>

      <div className="header-actions">
        <button
          type="button"
          ref={menuButtonRef}
          className={`header-menu-btn ${menuOpen ? 'active' : ''}`}
          aria-label={menuOpen ? t('header.closeMenu') : t('header.openMenu')}
          aria-expanded={menuOpen}
          aria-haspopup="true"
          onClick={() => setMenuOpen(prev => !prev)}
        >
          <Icon name="menu" />
        </button>

        {menuDropdownContent && (canUseDOM ? createPortal(menuDropdownContent, document.body) : menuDropdownContent)}
      </div>
    </header>
  )
}
