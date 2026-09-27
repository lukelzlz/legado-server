/**
 * E2E 回归：书源界面的**列表独立滚动条**。
 *
 * ## 为什么要用真实浏览器断言
 *
 * 这是**纯布局**行为，单元测试（`renderToStaticMarkup`）只能验证 DOM 结构，
 * **测不出** `scrollHeight` / `clientHeight` 的关系。
 * 「列表内部可滚、页面本身不滚」这件事只有真实浏览器能判定 ——
 * 静态渲染里 `.source-list` 永远存在，看起来"没问题"。
 *
 * ## 断言的是几何量
 *
 * - 列表 `scrollHeight > clientHeight`（内容确实超出、有独立滚动条）
 * - 侧栏高度被约束在视口内（否则列表会被撑高、滚动条落到页面上）
 * - **页面本身不滚动**（`documentElement.scrollHeight <= innerHeight + 1`）——
 *   这正是「独立滚动条」与「整页滚动」的分界
 *
 * 用法：先启动服务，再 `npx tsx web/test/e2e-layout-shelf-sources.ts`
 * 可通过 LEGADO_BASE / LEGADO_PASSWORD 覆盖；找不到浏览器时**跳过**（不误报失败）。
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'

const CHROME_CANDIDATES = [
  process.env.CHROME_BIN,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter(Boolean) as string[]

const chrome = CHROME_CANDIDATES.find((p) => { try { return fs.existsSync(p) } catch { return false } })
if (!chrome) {
  console.log('SKIP: 未找到可用浏览器（设置 CHROME_BIN 可指定）')
  process.exit(0)
}

const BASE = process.env.LEGADO_BASE || 'http://127.0.0.1:8080'
const PASSWORD = process.env.LEGADO_PASSWORD || 'w71251478'

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
})

let failures = 0
const check = (ok: boolean, label: string, detail = '') => {
  console.log(`${ok ? '  OK  ' : ' FAIL '} ${label}${detail ? ' — ' + detail : ''}`)
  if (!ok) failures++
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })

  await page.goto(`${BASE}/`, { waitUntil: 'networkidle2', timeout: 30000 })
  const needsLogin = await page.$('input[type="password"]')
  if (needsLogin) {
    await page.type('input[type="password"]', PASSWORD)
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }).catch(() => null),
      page.keyboard.press('Enter'),
    ])
    await new Promise(r => setTimeout(r, 1200))
  }

  console.log('\n[书源界面] 列表独立滚动条')

  const toSources = await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button, a'))
    const target = btns.find(b => {
      const t = (b.textContent || '').trim()
      return t === '书源' || t.includes('书源管理')
    })
    if (target) { (target as HTMLElement).click(); return true }
    return false
  })
  if (!toSources) {
    console.log('SKIP: 未找到「书源」入口')
  } else {
    await page.waitForSelector('.source-list', { timeout: 8000 }).catch(() => null)
    await new Promise(r => setTimeout(r, 900))

    const metrics = await page.evaluate(() => {
      const list = document.querySelector('.source-list') as HTMLElement | null
      const sidebar = document.querySelector('.source-sidebar') as HTMLElement | null
      if (!list || !sidebar) return null
      return {
        listScrollHeight: list.scrollHeight,
        listClientHeight: list.clientHeight,
        listOverflowY: getComputedStyle(list).overflowY,
        sidebarClientHeight: sidebar.clientHeight,
        docScrollHeight: document.documentElement.scrollHeight,
        innerHeight: window.innerHeight,
        itemCount: document.querySelectorAll('.source-list button').length,
      }
    })

    if (!metrics) {
      check(false, '找到书源列表', '未找到 .source-list')
    } else {
      console.log(`      （列表 ${metrics.itemCount} 项，scrollHeight=${metrics.listScrollHeight} clientHeight=${metrics.listClientHeight}）`)

      check(
        metrics.listOverflowY === 'auto' || metrics.listOverflowY === 'scroll',
        '列表自身可滚动（overflow-y: auto）',
        `实际 ${metrics.listOverflowY}`,
      )
      check(
        metrics.listScrollHeight > metrics.listClientHeight,
        '列表内容超出其可视高度（存在独立滚动条）',
        `${metrics.listScrollHeight} > ${metrics.listClientHeight}`,
      )
      check(
        metrics.sidebarClientHeight > 0 && metrics.sidebarClientHeight <= metrics.innerHeight + 1,
        '侧栏高度被约束在视口内',
        `sidebar=${metrics.sidebarClientHeight} 视口=${metrics.innerHeight}`,
      )
      // 关键：页面本身**不该**出现滚动条（滚动被收敛进列表）
      check(
        metrics.docScrollHeight <= metrics.innerHeight + 1,
        '页面本身不滚动（滚动收敛进列表）',
        `doc=${metrics.docScrollHeight} 视口=${metrics.innerHeight}`,
      )
    }
  }
} finally {
  await browser.close()
}

console.log(`\n${failures === 0 ? '✅ 全部通过' : `❌ ${failures} 项失败`}`)
process.exit(failures === 0 ? 0 : 1)
