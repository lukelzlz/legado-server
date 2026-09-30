/**
 * 端到端真实浏览器多语言自动化测试：
 * 验证语言即时切换、各语种文案、页面刷新/服务端漫游持久化及 API 协同。
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const CHROME_PATH = process.env.CHROME_BIN ||
  process.env.PUPPETEER_EXECUTABLE_PATH ||
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE_URL = process.env.LEGADO_BASE_URL || 'http://127.0.0.1:18080'
const ADMIN_PASSWORD = process.env.LEGADO_PASSWORD || 'admin123'
const SHOT_DIR = process.env.LEGADO_SHOT_DIR || process.cwd()

const results: { label: string; ok: boolean; extra?: string }[] = []
const check = (label: string, ok: boolean, extra = '') => {
  results.push({ label, ok, extra })
  console.log(`${ok ? '✔' : '✖'} ${label}${extra ? ` — ${extra}` : ''}`)
  if (!ok) {
    throw new Error(`Assertion failed: ${label} (${extra})`)
  }
}

const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-e2e-i18n-'))
const browser = await puppeteer.launch({
  executablePath: CHROME_PATH,
  headless: true,
  userDataDir,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,960'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 960 })

  console.log(`\nNavigating to ${BASE_URL}...`)
  await page.goto(BASE_URL, { waitUntil: 'networkidle2' })

  // 1. 登录处理（如果处于未登录态）
  const pwdInput = await page.$('input[type="password"]')
  if (pwdInput) {
    console.log('Logging in...')
    await pwdInput.type(ADMIN_PASSWORD)
    await page.keyboard.press('Enter')
    await page.waitForSelector('.app-page-header nav, .header-menu-btn', { timeout: 15_000 })
    await new Promise(r => setTimeout(r, 1000))
  } else {
    await page.waitForSelector('.app-page-header nav, .header-menu-btn', { timeout: 15_000 })
  }

  // 辅助函数：读取当前顶栏导航文字
  const getNavTabs = async () => {
    return await page.$$eval('.app-page-header nav button', btns =>
      btns.map(b => (b.textContent || '').trim().replace(/\s+/g, ' '))
    )
  }

  // 辅助函数：打开功能菜单
  const openMenu = async () => {
    const isMenuOpen = await page.evaluate(() => document.querySelector('.header-menu-dropdown') !== null)
    if (!isMenuOpen) {
      await page.click('button.header-menu-btn')
      await page.waitForSelector('.header-menu-dropdown .menu-lang-grid', { timeout: 5000 })
      await new Promise(r => setTimeout(r, 300))
    }
  }

  // 辅助函数：点击语言按钮
  const clickLanguage = async (name: string) => {
    await openMenu()
    const clicked = await page.evaluate((langName) => {
      const btns = Array.from(document.querySelectorAll('.menu-lang-btn'))
      for (const btn of btns) {
        if (btn.textContent?.includes(langName)) {
          (btn as HTMLButtonElement).click()
          return true
        }
      }
      return false
    }, name)
    check(`点击语言切换按钮 [${name}]`, clicked)
    await new Promise(r => setTimeout(r, 500))
  }

  // ---- 初始状态检查 (默认应为 zh-CN 或已有设置) ----
  console.log('\n--- 步骤 1: 验证简体中文 (zh-CN) ---')
  await clickLanguage('简体中文')
  let navTabs = await getNavTabs()
  check('简体中文导航包含书库与书架', navTabs.includes('书库') && navTabs.includes('书架'), navTabs.join(' | '))
  check('简体中文导航包含书源与规则', navTabs.includes('书源') && navTabs.includes('规则'), navTabs.join(' | '))
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-i18n-zh-CN.png') })

  // ---- 步骤 2: 切换到英语 (en-US) ----
  console.log('\n--- 步骤 2: 切换到 English (en-US) ---')
  await clickLanguage('English')
  navTabs = await getNavTabs()
  check('英文导航包含 Library 与 Bookshelf', navTabs.includes('Library') && navTabs.includes('Bookshelf'), navTabs.join(' | '))
  check('英文导航包含 Sources 与 Rules', navTabs.includes('Sources') && navTabs.includes('Rules'), navTabs.join(' | '))
  check('英文导航包含 Subscriptions 与 Files', navTabs.includes('Subscriptions') && navTabs.includes('Files'), navTabs.join(' | '))
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-i18n-en-US.png') })

  // ---- 步骤 3: 切换到繁體中文 (zh-TW) ----
  console.log('\n--- 步骤 3: 切换到 繁體中文 (zh-TW) ---')
  await clickLanguage('繁體中文')
  navTabs = await getNavTabs()
  check('繁体中文导航包含書庫與書架', navTabs.includes('書庫') && navTabs.includes('書架'), navTabs.join(' | '))
  check('繁体中文导航包含訂閱與檔案', navTabs.includes('訂閱') && navTabs.includes('檔案'), navTabs.join(' | '))
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-i18n-zh-TW.png') })

  // ---- 步骤 4: 切换到 日本語 (ja-JP) ----
  console.log('\n--- 步骤 4: 切换到 日本語 (ja-JP) ---')
  await clickLanguage('日本語')
  navTabs = await getNavTabs()
  check('日语导航包含ライブラリ与本棚', navTabs.includes('ライブラリ') && navTabs.includes('本棚'), navTabs.join(' | '))
  check('日语导航包含ブックソース与置換ルール', navTabs.includes('ブックソース') && navTabs.includes('置換ルール'), navTabs.join(' | '))
  check('日语导航包含購読与ファイル', navTabs.includes('購読') && navTabs.includes('ファイル'), navTabs.join(' | '))
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-i18n-ja-JP.png') })

  // ---- 步骤 5: 验证持久化与服务端漫游 ----
  console.log('\n--- 步骤 5: 验证页面刷新与服务端设置同步 ---')
  // 检查 localStorage
  const localStoredLocale = await page.evaluate(() => localStorage.getItem('legado-locale-v1'))
  check('localStorage 持久化为 ja-JP', localStoredLocale === 'ja-JP', `actual: ${localStoredLocale}`)

  // 检查服务端 app_setting
  const serverLocale = await page.evaluate(async () => {
    const res = await fetch('/api/settings/locale')
    if (!res.ok) return null
    const json = await res.json()
    return json.locale
  })
  check('服务端接口 GET /api/settings/locale 返回 ja-JP', serverLocale === 'ja-JP', `actual: ${serverLocale}`)

  // 刷新页面，验证无感保持
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForSelector('.app-page-header nav', { timeout: 10_000 })
  navTabs = await getNavTabs()
  check('页面刷新后保持日语本棚与ライブラリ', navTabs.includes('本棚') && navTabs.includes('ライブラリ'), navTabs.join(' | '))

  // ---- 步骤 6: 恢复为简体中文 ----
  console.log('\n--- 步骤 6: 恢复为简体中文 (zh-CN) ---')
  await clickLanguage('简体中文')
  navTabs = await getNavTabs()
  check('恢复为简体中文导航', navTabs.includes('书库') && navTabs.includes('书架'), navTabs.join(' | '))
  const restoredServerLocale = await page.evaluate(async () => {
    const res = await fetch('/api/settings/locale')
    return (await res.json()).locale
  })
  check('服务端最终恢复为 zh-CN', restoredServerLocale === 'zh-CN', `actual: ${restoredServerLocale}`)
  await page.screenshot({ path: path.join(SHOT_DIR, 'e2e-i18n-restored-zh-CN.png') })

  console.log('\n=============================================')
  console.log(`全部 ${results.length} 项浏览器端端到端断言 100% 通过！`)
  console.log('=============================================\n')
} finally {
  await browser.close()
  await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => {})
}
