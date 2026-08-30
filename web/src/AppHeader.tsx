import React, { useEffect, useRef, useState } from 'react'
import { Icon } from './icons'
import { Logo } from './Logo'
import type { ReaderSettings } from './readerSettings'

export type AppPage = 'sources' | 'subscriptions' | 'library' | 'shelf' | 'reader'

export interface AppHeaderProps {
  page: AppPage
  settings: ReaderSettings
  searching?: boolean
  onSettingsChange: (next: ReaderSettings) => void
  onNavigate: (page: AppPage) => void
  onLogout: () => void
}

export const APP_THEMES: ReadonlyArray<{ id: ReaderSettings['theme']; name: string }> = [
  { id: 'light', name: '晓白' },
  { id: 'paper', name: '护眼' },
  { id: 'dark', name: '夜读' },
]

export function AppHeader({
  page,
  settings,
  searching,
  onSettingsChange,
  onNavigate,
  onLogout,
}: AppHeaderProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuContainerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }

    const handlePointerDown = (event: PointerEvent) => {
      if (menuContainerRef.current && !menuContainerRef.current.contains(event.target as Node)) {
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
    if (window.confirm('确定要退出登录吗？')) {
      setMenuOpen(false)
      onLogout()
    }
  }

  const handleThemeChange = (theme: ReaderSettings['theme']) => {
    onSettingsChange({ ...settings, theme })
  }

  return (
    <header className="app-page-header">
      <button
        type="button"
        className="app-brand"
        onClick={() => onNavigate('library')}
        aria-label="阅读服务器"
      >
        <Logo size={22} />
        <strong>阅读服务器</strong>
      </button>

      <nav aria-label="主导航">
        <button
          type="button"
          className={page === 'library' ? 'active' : ''}
          onClick={() => onNavigate('library')}
        >
          书库
          {searching && (
            <span
              className="nav-search-indicator"
              title="后台正在搜索..."
              aria-label="后台正在搜索"
            />
          )}
        </button>
        <button
          type="button"
          className={page === 'shelf' ? 'active' : ''}
          onClick={() => onNavigate('shelf')}
        >
          书架
        </button>
        <button
          type="button"
          className={page === 'sources' ? 'active' : ''}
          onClick={() => onNavigate('sources')}
        >
          书源
        </button>
        <button
          type="button"
          className={page === 'subscriptions' ? 'active' : ''}
          onClick={() => onNavigate('subscriptions')}
        >
          订阅
        </button>
      </nav>

      <div className="header-actions" ref={menuContainerRef}>
        <button
          type="button"
          className={`header-menu-btn ${menuOpen ? 'active' : ''}`}
          aria-label={menuOpen ? '关闭功能菜单' : '打开功能菜单'}
          aria-expanded={menuOpen}
          aria-haspopup="true"
          onClick={() => setMenuOpen(prev => !prev)}
        >
          <Icon name="menu" />
        </button>

        {menuOpen && (
          <>
            <div
              className="header-menu-backdrop"
              onClick={() => setMenuOpen(false)}
              aria-hidden="true"
            />
            <div className="header-menu-dropdown" role="menu" aria-label="功能菜单">
              <div className="menu-header">
                <div className="menu-status-badge">
                  <span className="menu-status-dot" />
                  <span className="menu-header-title">已登录服务</span>
                </div>
              </div>

              <div className="menu-section">
                <div className="menu-section-label">全站主题</div>
                <div className="menu-theme-grid" role="radiogroup" aria-label="全站主题选择">
                  {APP_THEMES.map(t => {
                    const isSelected = settings.theme === t.id
                    return (
                      <button
                        key={t.id}
                        type="button"
                        className={`menu-theme-btn theme-option-${t.id} ${isSelected ? 'selected' : ''}`}
                        role="radio"
                        aria-checked={isSelected}
                        onClick={() => handleThemeChange(t.id)}
                      >
                        <span className={`theme-swatch theme-swatch-${t.id}`} />
                        <span className="theme-name">{t.name}</span>
                        {isSelected && <Icon name="check" className="theme-check-icon" />}
                      </button>
                    )
                  })}
                </div>
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
                  <span>退出登录</span>
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </header>
  )
}
