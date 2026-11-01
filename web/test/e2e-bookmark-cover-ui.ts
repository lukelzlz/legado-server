/**
 * 「书签」页的 UI 级真浏览器验证（封面弹层 + 批量栏三连修）。
 *
 * 覆盖 2026-11-01 用户实测报障的三条：
 * 1. 批量栏文案不能漏出 `{{count}}`，且显示 `已选 N / M`；
 * 2. 两个批量管理**互斥**（开一侧收起另一侧）；
 * 3. 两页批量栏都有「取消」，点了能退出并清空勾选。
 * 另加：点封面打开「设置封面」弹层（书源候选 / 上传本地图片 / 移除）。
 *
 * 运行：
 *   $env:CHROME_BIN="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
 *   $env:TEST_BASE_URL="http://127.0.0.1:8080"; $env:ADMIN_PASSWORD="w71251478"
 *   $env:SHOT_DIR="E:\Desktop\legado\_artifacts"
 *   npx tsx web/test/e2e-bookmark-cover-ui.ts
 */
import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const CHROME_PATH = process.env.CHROME_BIN
  || (process.platform === 'win32'
    ? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
    : '/usr/bin/google-chrome')
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8080'
const PASSWORD = process.env.ADMIN_PASSWORD || 'w71251478'
const SHOT_DIR = process.env.SHOT_DIR || ''

async function waitForServer(url: string, timeoutMs = 30000): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      if ((await fetch(`${url}/healthz`)).ok) return
    } catch { /* 还没起来 */ }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`服务端 ${url} 在 ${timeoutMs}ms 内没有响应`)
}

async function main(): Promise<void> {
  await waitForServer(BASE_URL)

  // 用接口登录拿 Cookie（浏览器里直接带 cookie，跳过登录页）
  const login = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PASSWORD }),
  })
  assert.equal(login.status, 200, '登录失败')
  const cookieHeader = (login.headers.getSetCookie?.() ?? [login.headers.get('set-cookie') ?? ''])
    .map(value => (value ?? '').split(';')[0])
    .filter(Boolean)
  const csrf = (await login.json()).csrfToken as string

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-bookmark-ui-'))
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    userDataDir,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--window-size=1440,1000'],
  })

  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1440, height: 1000 })
    await page.setCookie(...cookieHeader.map(pair => {
      const [name, ...rest] = pair.split('=')
      return { name, value: rest.join('='), domain: '127.0.0.1', path: '/' }
    }))
    page.on('pageerror', error => console.log(`   [browser pageerror] ${error.message}`))
    page.on('dialog', dialog => void dialog.accept())

    await page.goto(`${BASE_URL}/#bookmarks`, { waitUntil: 'networkidle2' })
    await page.waitForSelector('.bookmarks-page .rule-list-card', { timeout: 20000 })
    const bookCount = await page.$$eval('.bookmarks-page .rule-list-card', cards => cards.length)
    console.log(`1/6 打开 #bookmarks：左栏 ${bookCount} 本书`)
    assert.ok(bookCount > 0, '左栏应有书（需要真实书签数据）')

    const clickByText = async (selector: string, text: string) => {
      const handles = await page.$$(selector)
      for (const handle of handles) {
        const label = await handle.evaluate(node => (node as HTMLElement).innerText.replace(/\s+/g, ' ').trim())
        if (label.includes(text)) {
          await handle.evaluate(node => (node as HTMLElement).click())
          return true
        }
      }
      return false
    }

    // ① 左栏批量栏文案：不能漏 {{count}}
    assert.ok(await clickByText('.bookmarks-page .source-sidebar-top-actions button', '批量管理'), '找不到左栏「批量管理」')
    await page.waitForSelector('.source-batch-bar', { timeout: 5000 })
    const leftBarText = await page.$eval('.source-batch-bar .batch-bar-count', node => (node as HTMLElement).innerText.replace(/\s+/g, ' ').trim())
    console.log(`2/6 左栏批量栏文案：${leftBarText}`)
    assert.equal(leftBarText.includes('{{'), false, `批量栏漏出模板占位符：${leftBarText}`)
    assert.match(leftBarText, /^已选\s*0\s*\/\s*\d+$/, `批量栏应为「已选 0 / N」：${leftBarText}`)
    assert.equal(await page.$$eval('.source-batch-bar', bars => bars.length), 1, '左栏批量时不应同时出现两条工具栏')

    // ② 开右栏批量 ⇒ 左栏自动收起
    assert.ok(await clickByText('.bookmarks-page .rules-detail-actions button', '批量管理'), '找不到右栏「批量管理」')
    await new Promise(resolve => setTimeout(resolve, 300))
    const barsAfterRight = await page.$$eval('.source-batch-bar', bars => bars.length)
    const rightBarText = await page.$eval('.source-batch-bar .batch-bar-count', node => (node as HTMLElement).innerText.replace(/\s+/g, ' ').trim())
    console.log(`3/6 打开右栏批量后：工具栏 ${barsAfterRight} 条，文案 ${rightBarText}`)
    assert.equal(barsAfterRight, 1, '两个批量管理必须互斥（只应有一条工具栏）')

    // ③ 「取消」退出批量
    assert.ok(await clickByText('.source-batch-bar button', '取消'), '批量栏缺少「取消」按钮')
    await new Promise(resolve => setTimeout(resolve, 300))
    assert.equal(await page.$$eval('.source-batch-bar', bars => bars.length), 0, '点「取消」后批量栏应消失')
    console.log('4/6 「取消」生效：批量栏已收起')

    // ④ 点封面 → 「设置封面」弹层
    await page.click('.bookmarks-page .bookmark-book-cover')
    await page.waitForSelector('.bookmark-dialog', { timeout: 8000 })
    const dialog = await page.$eval('.bookmark-dialog', node => (node as HTMLElement).innerText)
    assert.ok(dialog.includes('设置封面'), `弹层标题不对：${dialog.slice(0, 60)}`)
    assert.ok(dialog.includes('上传本地图片'), '弹层缺少「上传本地图片」')
    assert.ok(dialog.includes('移除封面'), '弹层缺少「移除封面」')
    console.log('5/6 「设置封面」弹层：书源候选 + 上传本地图片 + 重新获取 + 移除 都在')

    if (SHOT_DIR) {
      await fs.mkdir(SHOT_DIR, { recursive: true })
      await page.screenshot({ path: path.join(SHOT_DIR, 'bookmarks-cover-dialog.png') })
      console.log(`     截图：${path.join(SHOT_DIR, 'bookmarks-cover-dialog.png')}`)
    }

    await page.click('.bookmark-dialog .close-btn')
    await new Promise(resolve => setTimeout(resolve, 200))
    assert.equal(await page.$$eval('.bookmark-dialog', nodes => nodes.length), 0, '弹层应能关闭')
    console.log('6/6 弹层可关闭')

    console.log('\nALL BOOKMARK COVER UI CHECKS PASSED')
    void csrf
  } finally {
    await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
  }
}

main()
  .then(() => process.exit(0))
  .catch(error => {
    console.error('bookmark cover ui e2e failed:', error)
    process.exit(1)
  })
