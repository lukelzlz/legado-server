import puppeteer, { Browser, Page } from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawn, ChildProcess } from 'node:child_process'

const CHROME_PATH = process.env.CHROME_BIN || '/usr/bin/google-chrome' || '/usr/bin/chromium-browser'
const PORT = 5173
const BASE_URL = `http://127.0.0.1:${PORT}`
const MAX_CONCURRENT_CHROME = 2 // Strict limit <= 2 instances

console.log(`\n======================================================`)
console.log(`🚀 Starting Real Browser (Chrome) E2E Test Suite`)
console.log(`🌐 Chrome Binary: ${CHROME_PATH}`)
console.log(`🔒 Max Concurrent Chrome Instances: ${MAX_CONCURRENT_CHROME} (Strictly <= 2)`)
console.log(`======================================================\n`)

class ChromePool {
  private activeCount = 0
  private maxConcurrent: number
  private queue: Array<() => void> = []

  constructor(maxConcurrent: number) {
    this.maxConcurrent = maxConcurrent
  }

  async acquire(): Promise<void> {
    if (this.activeCount < this.maxConcurrent) {
      this.activeCount++
      return
    }
    await new Promise<void>((resolve) => {
      this.queue.push(resolve)
    })
    this.activeCount++
  }

  release(): void {
    this.activeCount--
    if (this.queue.length > 0) {
      const next = this.queue.shift()
      next?.()
    }
  }

  getActiveCount(): number {
    return this.activeCount
  }
}

const pool = new ChromePool(MAX_CONCURRENT_CHROME)

async function withChrome<T>(
  name: string,
  fn: (browser: Browser, page: Page) => Promise<T>
): Promise<T> {
  await pool.acquire()
  assert.ok(
    pool.getActiveCount() <= MAX_CONCURRENT_CHROME,
    `Active Chrome instances (${pool.getActiveCount()}) exceeded max limit (${MAX_CONCURRENT_CHROME})`
  )

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), `legado-chrome-test-${Date.now()}-`))
  let browser: Browser | null = null

  try {
    browser = await puppeteer.launch({
      executablePath: CHROME_PATH,
      headless: true,
      userDataDir,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-gpu',
        '--disable-web-security',
        '--window-size=1280,800',
      ],
    })

    const page = await browser.newPage()
    const start = Date.now()
    console.log(`[Instance Start] 🌐 Running: "${name}" (Active Instances: ${pool.getActiveCount()})`)
    const result = await fn(browser, page)
    const durationMs = Date.now() - start
    console.log(`[Instance Pass]  ✔ Completed: "${name}" in ${durationMs}ms`)
    return result
  } catch (err: any) {
    console.error(`[Instance Fail]  ✖ Failed: "${name}" - ${err.message}`)
    throw err
  } finally {
    if (browser) await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => {})
    pool.release()
  }
}

// Start local Vite dev server
async function startViteServer(): Promise<ChildProcess> {
  const child = spawn('npx', ['vite', '--port', String(PORT), '--host', '127.0.0.1'], {
    cwd: path.resolve(process.cwd(), 'web'),
    stdio: 'pipe',
    shell: true,
  })

  let ready = false
  child.stdout?.on('data', (d) => {
    const text = d.toString()
    if (text.includes('Local:') || text.includes('ready in') || text.includes('http://127.0.0.1')) {
      ready = true
    }
  })

  for (let i = 0; i < 40; i++) {
    if (ready) break
    try {
      const resp = await fetch(BASE_URL)
      if (resp.ok) {
        ready = true
        break
      }
    } catch {}
    await new Promise((r) => setTimeout(r, 250))
  }

  if (!ready) {
    child.kill()
    throw new Error('Failed to start Vite dev server on port ' + PORT)
  }
  console.log(`⚡ Vite Dev Server is ready at ${BASE_URL}`)
  return child
}

let mockSources = [
  {
    id: 'https://source1.com',
    name: '笔趣阁测试源1',
    url: 'https://source1.com',
    group: '优质推荐',
    enabled: true,
    isJsSource: false,
    hasLogin: true,
    updatedAt: Date.now() - 10000,
    version: 1,
  },
  {
    id: 'https://source2.com',
    name: '快读网测试源2',
    url: 'https://source2.com',
    group: '优质推荐',
    enabled: true,
    isJsSource: false,
    hasLogin: false,
    updatedAt: Date.now() - 20000,
    version: 1,
  },
  {
    id: 'https://source3.com',
    name: '失效小说站3',
    url: 'https://source3.com',
    group: '备用源',
    enabled: true,
    isJsSource: false,
    hasLogin: false,
    updatedAt: Date.now() - 30000,
    version: 1,
  },
  {
    id: 'https://source4.com',
    name: '未分组JS源4',
    url: 'https://source4.com',
    group: null,
    enabled: false,
    isJsSource: true,
    hasLogin: false,
    updatedAt: Date.now() - 40000,
    version: 1,
  },
]

async function setupMockRoutes(page: Page) {
  await page.setRequestInterception(true)
  page.on('request', async (req) => {
    const url = req.url()
    const method = req.method()

    if (url.includes('/api/auth/session')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, csrfToken: 'test-csrf-token' }),
      })
    }

    if (url.includes('/api/sources/batch') && method === 'POST') {
      const payload = JSON.parse(req.postData() || '{}')
      const { action, ids, group } = payload
      if (action === 'enable') {
        mockSources = mockSources.map((s) => (ids.includes(s.id) ? { ...s, enabled: true } : s))
      } else if (action === 'disable') {
        mockSources = mockSources.map((s) => (ids.includes(s.id) ? { ...s, enabled: false } : s))
      } else if (action === 'delete') {
        mockSources = mockSources.filter((s) => !ids.includes(s.id))
      } else if (action === 'set_group') {
        mockSources = mockSources.map((s) => (ids.includes(s.id) ? { ...s, group: group || null } : s))
      }
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ok: true, affected: ids.length, action, message: `已成功处理 ${ids.length} 个书源` }),
      })
    }

    if (url.includes('/api/sources/health-check') && method === 'POST') {
      const payload = JSON.parse(req.postData() || '{}')
      const ids = payload.ids || mockSources.map((s) => s.id)
      const results = ids.map((id: string) => {
        const source = mockSources.find((s) => s.id === id) || { name: id }
        if (id.includes('source3')) {
          return {
            id,
            name: source.name,
            ok: false,
            latencyMs: 5002,
            statusCode: 0,
            statusCategory: 'failed',
            error: 'Connect timed out (5000ms)',
          }
        }
        if (id.includes('source2')) {
          return {
            id,
            name: source.name,
            ok: true,
            latencyMs: 2600,
            statusCode: 200,
            statusCategory: 'slow',
          }
        }
        return {
          id,
          name: source.name,
          ok: true,
          latencyMs: 95,
          statusCode: 200,
          statusCategory: 'valid',
        }
      })
      const successCount = results.filter((r: any) => r.statusCategory === 'valid').length
      const slowCount = results.filter((r: any) => r.statusCategory === 'slow').length
      const failedCount = results.filter((r: any) => r.statusCategory === 'failed').length
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          total: results.length,
          successCount,
          slowCount,
          failedCount,
          durationMs: 480,
          results,
        }),
      })
    }

    if (url.includes('/api/sources/export')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockSources.map((s) => JSON.stringify({ bookSourceUrl: s.url, bookSourceName: s.name }))),
      })
    }

    if (url.includes('/api/sources') && method === 'GET') {
      const parsedUrl = new URL(url)
      const q = parsedUrl.searchParams.get('q')?.toLowerCase() || ''
      const filtered = mockSources.filter(
        (s) => !q || s.name.toLowerCase().includes(q) || s.url.toLowerCase().includes(q) || (s.group ?? '').toLowerCase().includes(q)
      )
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(filtered),
      })
    }

    if (url.includes('/api/bookshelf')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    }

    if (url.includes('/api/replace-rules')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    }

    if (url.includes('/api/subscriptions')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    }

    req.continue()
  })
}

async function runE2E() {
  const viteProcess = await startViteServer()

  try {
    // Instance 1: Desktop Viewport E2E Batch & Health Check Flow
    await withChrome('Instance 1: Desktop Batch Operations & Health Check Real Flow', async (browser, page) => {
      await page.setViewport({ width: 1280, height: 800 })
      await setupMockRoutes(page)

      console.log('  1. Navigating to #sources...')
      await page.goto(`${BASE_URL}#sources`, { waitUntil: 'networkidle0' })

      // Verify sources loaded
      await page.waitForSelector('.source-list')
      const initialItems = await page.$$('.source-list button')
      assert.strictEqual(initialItems.length, 4, 'Should render 4 sources initially')

      // Click "批量管理"
      console.log('  2. Entering Batch Mode...')
      const batchBtn = await page.waitForSelector('.source-sidebar-top-actions button:last-child')
      assert.ok(batchBtn)
      await batchBtn.click()

      // Verify floating batch bar appears
      await page.waitForSelector('.source-batch-bar')
      const batchBarCount = await page.$eval('.batch-bar-count', (el) => el.textContent)
      assert.ok(batchBarCount?.includes('0 / 4'), `Expected count '0 / 4', got '${batchBarCount}'`)

      // Test "全选"
      console.log('  3. Testing "全选" (Select All)...')
      const selectAllBtn = await page.waitForSelector('.batch-select-helpers button:first-child')
      await selectAllBtn?.click()
      let countText = await page.$eval('.batch-bar-count', (el) => el.textContent)
      assert.ok(countText?.includes('4 / 4'), `Expected count '4 / 4', got '${countText}'`)

      // Test "反选"
      console.log('  4. Testing "反选" (Invert Select)...')
      const invertBtn = await page.waitForSelector('.batch-select-helpers button:nth-child(3)')
      await invertBtn?.click()
      countText = await page.$eval('.batch-bar-count', (el) => el.textContent)
      assert.ok(countText?.includes('0 / 4'), `Expected count '0 / 4', got '${countText}'`)

      // Click first 2 checkboxes manually
      const checkboxes = await page.$$('.batch-checkbox-wrap input[type="checkbox"]')
      await checkboxes[0].click()
      await checkboxes[1].click()
      countText = await page.$eval('.batch-bar-count', (el) => el.textContent)
      assert.ok(countText?.includes('2 / 4'), `Expected count '2 / 4', got '${countText}'`)

      // Test "批量停用"
      console.log('  5. Testing "批量停用" (Batch Disable)...')
      const disableBtn = await page.waitForSelector('.batch-bar-right button:nth-child(2)')
      await disableBtn?.click()
      await new Promise((r) => setTimeout(r, 300))
      const disabledBadges = await page.$$('.source-disabled-badge')
      assert.ok(disabledBadges.length >= 2, 'Should display at least 2 disabled badges')

      // Test "修改分组" Modal
      console.log('  6. Testing "修改分组" Modal...')
      const changeGroupBtn = await page.waitForSelector('.batch-bar-right button:nth-child(3)')
      await changeGroupBtn?.click()
      await page.waitForSelector('.source-group-modal')

      // Enter new group name
      const groupInput = await page.waitForSelector('#groupNameInput')
      await groupInput?.type('极速VIP源')
      const confirmGroupBtn = await page.waitForSelector('.source-group-modal .primary-button')
      await confirmGroupBtn?.click()
      await new Promise((r) => setTimeout(r, 400))

      // Verify group dropdown in sidebar has new group
      await page.waitForSelector('.source-group-select')
      const groupOptions = await page.$$eval('.source-group-select option', (opts) => opts.map((o) => o.textContent))
      assert.ok(
        groupOptions.some((txt) => txt?.includes('极速VIP源')),
        `Expected group '极速VIP源' in dropdown options: ${JSON.stringify(groupOptions)}`
      )

      // Test "体检" Modal
      console.log('  7. Testing "体检" (Health Check Probe) Modal...')
      const healthBtn = await page.waitForSelector('.health-probe-btn')
      await healthBtn?.click()
      await page.waitForSelector('.source-health-modal')

      // Wait for health results to load
      await page.waitForSelector('.health-item-card')
      const healthCards = await page.$$('.health-item-card')
      assert.strictEqual(healthCards.length, 4, 'Should show 4 checked sources')

      // Check stats tabs (🟢 正常, 🟡 迟缓, 🔴 失效)
      const tabFailed = await page.waitForSelector('.health-tab-btn.tab-failed')
      await tabFailed?.click()
      await new Promise((r) => setTimeout(r, 200))
      const failedCards = await page.$$('.health-item-card')
      assert.strictEqual(failedCards.length, 1, 'Should filter to 1 failed source')

      // Test "一键停用失效源"
      const disableFailedBtn = await page.waitForSelector('.health-actions-right .secondary-button')
      await disableFailedBtn?.click()
      await new Promise((r) => setTimeout(r, 300))

      // Close health modal
      const closeHealthBtn = await page.waitForSelector('.source-health-modal .close-button')
      await closeHealthBtn?.click()

      console.log('  ✔ Instance 1 (Desktop) all real interaction checks passed!')
    })

    // Instance 2: Mobile Viewport (iPhone 14 Pro 393x852) Safe-Area & Touch Flow
    await withChrome('Instance 2: Mobile Viewport & Safe-Area Touch Interactions', async (browser, page) => {
      await page.setViewport({ width: 393, height: 852, isMobile: true, hasTouch: true })
      await setupMockRoutes(page)

      console.log('  1. Navigating to mobile #sources view...')
      await page.goto(`${BASE_URL}#sources`, { waitUntil: 'networkidle0' })

      // Enter Batch Mode
      console.log('  2. Mobile Touch: Entering Batch Mode...')
      const batchBtn = await page.waitForSelector('.source-sidebar-top-actions button:last-child')
      await batchBtn?.click()

      // Verify floating bar responsive styling on mobile
      await page.waitForSelector('.source-batch-bar')
      const barBox = await page.$eval('.source-batch-bar', (el) => {
        const rect = el.getBoundingClientRect()
        return { bottom: window.innerHeight - rect.bottom, width: rect.width }
      })
      assert.ok(barBox.bottom >= 0, 'Floating bar should sit at or above screen bottom')

      // Tap on a batch item
      const batchItems = await page.$$('.source-list-item.batch-item')
      await batchItems[0].click()
      const isChecked = await page.$eval('.batch-checkbox-wrap input', (el: any) => el.checked)
      assert.strictEqual(isChecked, true, 'Tapping item row should toggle checkbox')

      // Exit Batch Mode
      const exitBtn = await page.waitForSelector('.source-batch-bar .secondary-button')
      await exitBtn?.click()
      await new Promise((r) => setTimeout(r, 200))

      const floatingBarExists = await page.$('.source-batch-bar')
      assert.strictEqual(floatingBarExists, null, 'Floating bar should dismiss on exit')

      console.log('  ✔ Instance 2 (Mobile Safe-Area) all touch & responsiveness checks passed!')
    })

    console.log(`\n🎉 Real Browser (Chrome) E2E Tests Finished Successfully! (All instances <= ${MAX_CONCURRENT_CHROME})\n`)
  } finally {
    viteProcess.kill('SIGTERM')
  }
}

runE2E().catch((err) => {
  console.error('Fatal E2E Error:', err)
  process.exit(1)
})
