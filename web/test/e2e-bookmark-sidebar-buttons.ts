/**
 * 「书签」页左栏顶部两个按钮的布局验证：**均分宽度 + 占满左栏**（用户要求）。
 *
 * 背景：`.source-sidebar-top-actions` 是书源页的通用样式（3 等分栏），书签页只有 2 个按钮，
 * 于是两个按钮各占 1/3、右侧留一条空档。修法是在 `.bookmarks-page` 内改成 2 等分。
 *
 * 断言方式：量真实 DOM 的 `getBoundingClientRect()`——
 * ① 按钮宽度两两相差 < 2px（均分）；② 两个按钮的宽度和 + gap ≈ 容器宽度（占满）。
 *
 * 运行：
 *   $env:CHROME_BIN="...msedge.exe"; $env:TEST_BASE_URL="http://127.0.0.1:8080"; $env:ADMIN_PASSWORD="w71251478"
 *   npx tsx web/test/e2e-bookmark-sidebar-buttons.ts
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
  const login = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PASSWORD }),
  })
  assert.equal(login.status, 200, '登录失败')
  const cookieHeader = (login.headers.getSetCookie?.() ?? [login.headers.get('set-cookie') ?? ''])
    .map(value => (value ?? '').split(';')[0])
    .filter(Boolean)

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-sidebar-buttons-'))
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
    await page.goto(`${BASE_URL}/#bookmarks`, { waitUntil: 'networkidle2' })
    await page.waitForSelector('.bookmarks-page .source-sidebar-top-actions button', { timeout: 20000 })

    const metrics = await page.evaluate(() => {
      const box = document.querySelector('.bookmarks-page .source-sidebar-top-actions') as HTMLElement
      const buttons = Array.from(box.querySelectorAll('button')) as HTMLElement[]
      const rects = buttons.map(button => button.getBoundingClientRect())
      const style = getComputedStyle(box)
      const gap = Number.parseFloat(style.columnGap || style.gap || '0') || 0
      return {
        count: buttons.length,
        labels: buttons.map(button => button.innerText.trim()),
        containerWidth: box.getBoundingClientRect().width,
        widths: rects.map(rect => rect.width),
        gap,
      }
    })
    console.log(`1/3 按钮：${metrics.labels.join(' | ')}`)
    console.log(`2/3 容器宽 ${metrics.containerWidth.toFixed(1)}px，按钮宽 [${metrics.widths.map(w => w.toFixed(1)).join(', ')}]px，gap ${metrics.gap}px`)

    assert.equal(metrics.count, 2, `左栏顶部应只有 2 个按钮，实际 ${metrics.count}`)
    const [first, second] = metrics.widths
    assert.ok(Math.abs(first - second) < 2, `两个按钮必须等宽（均分）：${first} vs ${second}`)
    const used = first + second + metrics.gap
    assert.ok(
      Math.abs(used - metrics.containerWidth) < 2,
      `两个按钮必须占满左栏宽度：按钮+gap=${used.toFixed(1)} vs 容器 ${metrics.containerWidth.toFixed(1)}`,
    )
    console.log('3/3 均分且占满：PASS')

    if (SHOT_DIR) {
      await fs.mkdir(SHOT_DIR, { recursive: true })
      await page.screenshot({ path: path.join(SHOT_DIR, 'bookmarks-sidebar-buttons.png'), clip: { x: 0, y: 0, width: 700, height: 420 } })
      console.log(`     截图：${path.join(SHOT_DIR, 'bookmarks-sidebar-buttons.png')}`)
    }

    console.log('\nSIDEBAR BUTTON LAYOUT CHECKS PASSED')
  } finally {
    await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
  }
}

main()
  .then(() => process.exit(0))
  .catch(error => {
    console.error('sidebar buttons e2e failed:', error)
    process.exit(1)
  })
