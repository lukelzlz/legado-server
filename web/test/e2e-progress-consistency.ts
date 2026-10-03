/**
 * E2E 回归：阅读进度必须与**阅读器正在显示的那一章**一致，且退出再进入不跳章。
 *
 * 为什么需要真实浏览器（实测 2026-10-03）：
 * `persist()` 只读 `currentRef.current`，而它的两半在不同时机更新 ——
 * `chapter` 只在正文到达后由 `applyContent` 写入，`position` 却由翻页/滚动效果
 * 按**正在显示的章**随时改写；`changeChapter` 又是「先 persist 旧章 → 再切章」。
 * 于是正文还在路上时触发保存（防抖 1.2s / 点返回书架 / 页签隐藏 / 组件卸载），
 * 写进库里的就是「旧章节 + 新位置」的错配 ⇒ 退出再进入往前跳好几章。
 * 实测：滑块跳到第 100 章后退出，服务端记的是第 2 章。
 *
 * 这类缺陷只有「连点翻章 + 立刻退出 + 重新进入」才能触发，接口测试与静态渲染都测不到。
 *
 * 用法：先启动服务，再 `npx tsx web/test/e2e-progress-consistency.ts`
 * 可通过 LEGADO_BASE / LEGADO_PASSWORD 覆盖；找不到浏览器时**跳过**（不误报失败）。
 * 章节目录少于 3 章（书源限流/目录未缓存）时同样跳过 —— 环境不可用不应算失败。
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'

const CHROME_CANDIDATES = [
  process.env.CHROME_BIN,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
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
const TURNS = 4
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

let failures = 0
const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1200, height: 900 })
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await sleep(1500)

  if (await page.$('form.login-panel input[type="password"]')) {
    await page.type('form.login-panel input[type="password"]', PASSWORD)
    await page.click('form.login-panel button.primary-button')
  }
  await sleep(2500)

  type Progress = { chapterUrl: string; chapterIndex: number; scrollPosition: number }
  const api = <T,>(path: string, init?: RequestInit) =>
    page.evaluate(async (p, i) => {
      const r = await fetch(p, i || {})
      let body: unknown = null
      try { body = await r.json() } catch { /* 空响应 */ }
      return { status: r.status, body } as { status: number; body: T }
    }, path, (init || null) as never) as Promise<{ status: number; body: T }>

  const shelf = (await api<Array<{ sourceId: string; bookUrl: string; name: string }>>('/api/bookshelf')).body
  const book = shelf[0]
  if (!book) { console.log('SKIP: 书架为空'); await browser.close(); process.exit(0) }
  const query = `sourceId=${encodeURIComponent(book.sourceId)}&bookUrl=${encodeURIComponent(book.bookUrl)}`
  const original = (await api<Progress>(`/api/reading-progress?${query}`)).body

  /** 阅读器当前状态：以「非邻居段」的标题章号为准（标题形如「第 12 章 xxx」）。 */
  const shown = () =>
    page.evaluate(() => {
      const win = document.querySelector('.reading-scroll-window')
      const sections = win ? [...win.querySelectorAll(':scope > .reading-content')] : []
      const middle = sections.find((s) => !s.classList.contains('is-scroll-neighbor'))
      const title = middle ? (middle.querySelector('h1')?.textContent || '').trim() : null
      const counter = document.querySelector('.reader-chapter-control-bar .chapter-num')?.textContent?.trim() || null
      const total = counter ? Number((counter.split('/')[1] || '0').trim()) : 0
      const m = String(title || '').match(/第\s*(\d+)\s*章/)
      return { title, counter, total, index: m ? Number(m[1]) - 1 : -1 }
    })

  const openFromShelf = async () => {
    await page.evaluate(() => {
      const nav = [...document.querySelectorAll('button, a')].find((e) => (e.textContent || '').trim() === '书架')
      if (nav) (nav as HTMLElement).click()
    })
    await page.waitForSelector('.shelf-card', { timeout: 25000 })
    await page.evaluate((name) => {
      for (const card of [...document.querySelectorAll('.shelf-card')]) {
        if ((card.textContent || '').includes(name.slice(0, 6))) {
          (card.querySelector('.read-btn, .shelf-card-cover, button') as HTMLElement | null)?.click()
          return
        }
      }
    }, book.name)
    await page.waitForSelector('.reader-main', { timeout: 40000 })
    await sleep(7000)
  }

  await openFromShelf()
  const start = await shown()
  if (start.total < TURNS + 2 || start.index < 0) {
    console.log(`SKIP: 该书目录只有 ${start.total} 章或标题不可解析（书源限流/目录未缓存），环境不可用不算失败`)
    await browser.close()
    process.exit(0)
  }
  console.log(`  起点: 「${start.title}」 下标=${start.index}  共 ${start.total} 章`)

  // ⚠️ 必须限速：缓存热时章节正文秒开，「显示章 ≠ 已加载章」的窗口太窄，
  // 这个用例会**在修复前也通过**（实测复现）。加上 1.2s 延迟后，
  // 修复前的构建稳定失败（显示 idx=13 / 保存 idx=6），修复后通过。
  const cdp = await page.target().createCDPSession()
  await cdp.send('Network.enable')
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 1200,
    downloadThroughput: 300 * 1024,
    uploadThroughput: 300 * 1024,
  })

  // 连点「下一章」，**不等正文加载** —— 这正是错配发生的窗口
  for (let i = 0; i < TURNS; i++) {
    await page.evaluate(() => {
      const el = [...document.querySelectorAll('button')].find(
        (b) => (b.getAttribute('aria-label') || '').includes('下一章') || (b.textContent || '').trim() === '下一章',
      )
      if (el) (el as HTMLElement).click()
    })
    await sleep(300)
  }
  await sleep(1000)
  const displayed = await shown()
  console.log(`  连点 ${TURNS} 次后显示: 「${displayed.title}」 下标=${displayed.index}`)

  // 立刻退出（「返回书架」按钮自身会 persist）
  await page.evaluate(() => document.querySelector('[aria-label="返回书架"]')?.dispatchEvent(new MouseEvent('click', { bubbles: true })))
  await sleep(1500)
  await page.waitForSelector('.shelf-card', { timeout: 25000 }).catch(() => {})
  const saved = (await api<Progress>(`/api/reading-progress?${query}`)).body
  console.log(`  退出时服务端保存: chapterIndex=${saved?.chapterIndex}`)

  if (saved?.chapterIndex !== displayed.index) {
    failures++
    console.log(`  ✘ 保存的章节（${saved?.chapterIndex}）与阅读器显示的章节（${displayed.index}）不一致 —— 退出再进入必然跳章`)
  } else {
    console.log('  ✔ 保存的章节 == 阅读器显示的章节')
  }

  // 重新进入，必须精确回到刚才那一章
  await openFromShelf()
  const reentered = await shown()
  console.log(`  重新进入落点: 「${reentered.title}」 下标=${reentered.index}`)
  if (reentered.index !== displayed.index) {
    failures++
    console.log(`  ✘ 重进落点差 ${reentered.index - displayed.index} 章`)
  } else {
    console.log('  ✔ 重进落点与离开时一致')
  }

  // 还原原始进度，避免污染真实数据
  if (original) {
    await page.evaluate(async (q, body) => {
      const login = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: 'x' }),
      }).catch(() => null)
      void login
      await fetch('/api/reading-progress', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
    }, query, { sourceId: book.sourceId, bookUrl: book.bookUrl, chapterUrl: original.chapterUrl, chapterIndex: original.chapterIndex, scrollPosition: original.scrollPosition })
    console.log('  （已还原原始进度）')
  }
} finally {
  await browser.close()
}

if (failures > 0) {
  console.log(`FAILED: ${failures} 项`)
  process.exit(1)
}
console.log('PASS')
