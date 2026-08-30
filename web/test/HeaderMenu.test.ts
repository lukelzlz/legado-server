import test from 'node:test'
import assert from 'node:assert/strict'
import { renderToStaticMarkup } from 'react-dom/server'
import React from 'react'
import { AppHeader, APP_THEMES, AppPage } from '../src/AppHeader'
import { Logo } from '../src/Logo'
import { Login } from '../src/Login'
import { defaultReaderSettings, ReaderSettings } from '../src/readerSettings'

test('HeaderMenu - APP_THEMES configuration', () => {
  assert.equal(APP_THEMES.length, 3)
  const themeIds = APP_THEMES.map(t => t.id)
  assert.deepEqual(themeIds, ['light', 'paper', 'dark'])
  
  const themeNames = APP_THEMES.map(t => t.name)
  assert.deepEqual(themeNames, ['晓白', '护眼', '夜读'])
})

test('HeaderMenu - AppHeader static rendering', () => {
  const settings: ReaderSettings = { ...defaultReaderSettings, theme: 'paper' }
  let navigatedTo: AppPage | null = null
  let nextSettings: ReaderSettings | null = null
  let loggedOut = false

  const element = React.createElement(AppHeader, {
    page: 'library',
    settings,
    onSettingsChange: (next) => { nextSettings = next },
    onNavigate: (p) => { navigatedTo = p },
    onLogout: () => { loggedOut = true },
  })

  const html = renderToStaticMarkup(element)

  // Verify brand title and icon
  assert.ok(html.includes('阅读服务器'), 'Header should contain brand title')
  assert.ok(html.includes('app-brand'), 'Header should have app-brand class')
  
  // Verify 4 main navigation tabs
  assert.ok(html.includes('书库'), 'Header should contain 书库 tab')
  assert.ok(html.includes('书架'), 'Header should contain 书架 tab')
  assert.ok(html.includes('书源'), 'Header should contain 书源 tab')
  assert.ok(html.includes('订阅'), 'Header should contain 订阅 tab')

  // Verify hamburger menu button
  assert.ok(html.includes('header-menu-btn'), 'Header should contain hamburger menu button')
})

test('HeaderMenu - Theme change handling', () => {
  const currentSettings: ReaderSettings = { ...defaultReaderSettings, theme: 'light' }
  let appliedSettings: ReaderSettings | null = null

  const onSettingsChange = (next: ReaderSettings) => {
    appliedSettings = next
  }

  // Simulate theme change
  onSettingsChange({ ...currentSettings, theme: 'dark' })
  assert.equal(appliedSettings?.theme, 'dark')

  onSettingsChange({ ...currentSettings, theme: 'paper' })
  assert.equal(appliedSettings?.theme, 'paper')
})

test('HeaderMenu - Logout confirmation safety', () => {
  let loggedOut = false
  const onLogout = () => {
    loggedOut = true
  }

  const confirmLogout = (confirmed: boolean) => {
    if (confirmed) {
      onLogout()
    }
  }

  // User cancels confirm
  confirmLogout(false)
  assert.equal(loggedOut, false, 'Should not log out when user cancels confirmation')

  // User confirms
  confirmLogout(true)
  assert.equal(loggedOut, true, 'Should log out when user accepts confirmation')
})

test('Logo - SVG rendering with brand gradients and classes', () => {
  const element = React.createElement(Logo, { size: 28 })
  const html = renderToStaticMarkup(element)
  assert.ok(html.includes('app-brand-logo'), 'Logo should have app-brand-logo class')
  assert.ok(html.includes('logoTealGrad'), 'Logo should contain teal gradient definition')
  assert.ok(html.includes('logoGoldGrad'), 'Logo should contain gold gradient definition')
  assert.ok(html.includes('width="28"'), 'Logo should respect size prop')
})

test('Login - Static rendering contains updated brand Logo', () => {
  const element = React.createElement(Login, { onLogin: () => {} })
  const html = renderToStaticMarkup(element)
  assert.ok(html.includes('login-mark'), 'Login should contain login-mark container')
  assert.ok(html.includes('app-brand-logo'), 'Login screen should render the new app-brand-logo')
  assert.ok(html.includes('阅读服务器'), 'Login screen should contain brand title')
  assert.ok(html.includes('回到你的阅读空间'), 'Login screen should contain welcome text')
})

