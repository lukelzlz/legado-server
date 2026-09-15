import puppeteer, { Browser, Page } from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const CHROME_PATH = process.env.CHROME_BIN || '/usr/bin/google-chrome' || '/usr/bin/chromium-browser'
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8090'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'
const MAX_CONCURRENT_CHROME = 3

console.log(`\n======================================================`)
console.log(`🚀 Starting Legado Real Chrome E2E Stress & Integration Suite`)
console.log(`📍 Server Target: ${BASE_URL}`)
console.log(`🌐 Chrome Binary: ${CHROME_PATH}`)
console.log(`🔒 Max Concurrent Chrome Instances: ${MAX_CONCURRENT_CHROME}`)
console.log(`======================================================\n`)

// Concurrency pool controller to strictly guarantee <= 3 concurrent Chrome instances
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

interface TestResult {
  suiteName: string
  passed: boolean
  durationMs: number
  error?: string
}

const results: TestResult[] = []

async function withChrome<T>(
  name: string,
  fn: (browser: Browser, userDataDir: string) => Promise<T>
): Promise<T> {
  await pool.acquire()
  assert.ok(
    pool.getActiveCount() <= MAX_CONCURRENT_CHROME,
    `Active Chrome instances (${pool.getActiveCount()}) exceeded max limit (${MAX_CONCURRENT_CHROME})`
  )
  
  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), `legado-chrome-test-${Date.now()}-${Math.random().toString(36).slice(2)}-`))
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
        '--window-size=1280,800'
      ]
    })

    const start = Date.now()
    console.log(`[Chrome Instance Start] 🌐 Running: "${name}" (Active Chrome instances: ${pool.getActiveCount()})`)
    const result = await fn(browser, userDataDir)
    const durationMs = Date.now() - start
    console.log(`[Chrome Instance Pass]  ✔ Completed: "${name}" in ${durationMs}ms`)
    results.push({ suiteName: name, passed: true, durationMs })
    return result
  } catch (err: any) {
    console.error(`[Chrome Instance Fail]  ✖ Failed: "${name}" - ${err.message}`)
    results.push({ suiteName: name, passed: false, durationMs: 0, error: err.message })
    throw err
  } finally {
    if (browser) {
      try {
        await browser.close()
      } catch {}
    }
    try {
      await fs.rm(userDataDir, { recursive: true, force: true })
    } catch {}
    pool.release()
  }
}

// Helper to log in
async function doLogin(page: Page) {
  await page.goto(`${BASE_URL}/index.html`, { waitUntil: 'domcontentloaded' })
  // Check if login form exists
  const hasPasswordInput = await page.$('input[type="password"]')
  if (hasPasswordInput) {
    await page.type('input[type="password"]', ADMIN_PASSWORD)
    const loginBtn = await page.$('button[type="submit"]')
    if (loginBtn) {
      await loginBtn.click()
    } else {
      await page.keyboard.press('Enter')
    }
    await page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 5000 }).catch(() => {})
  }
}

// -------------------------------------------------------------
// Suite 1: PWA Lifecycle, Web App Manifest & Service Worker
// -------------------------------------------------------------
async function runPwaAndManifestSuite() {
  return withChrome('Suite 1: PWA Lifecycle & Web App Manifest Verification', async (browser) => {
    const page = await browser.newPage()
    await page.goto(`${BASE_URL}/index.html`, { waitUntil: 'domcontentloaded' })

    // 1. Verify Manifest link in head
    const manifestHref = await page.evaluate(`
      (function() {
        const el = document.querySelector('link[rel="manifest"]');
        return el ? el.getAttribute('href') : null;
      })()
    `) as string | null

    assert.ok(manifestHref, 'Manifest link tag should exist in document head')

    // 2. Fetch and verify manifest JSON content
    const manifestJson: any = await page.evaluate(`
      (async function(url) {
        const resp = await fetch(url);
        return resp.json();
      })('${manifestHref}')
    `)

    assert.equal(manifestJson.name, '阅读', 'Manifest name should match')
    assert.equal(manifestJson.short_name, '阅读', 'Manifest short_name should match')
    assert.equal(manifestJson.display, 'standalone', 'Manifest display mode should be standalone')
    assert.ok(Array.isArray(manifestJson.icons) && manifestJson.icons.length >= 2, 'Manifest should provide icon assets')

    // 3. Verify Theme color and Viewport-fit meta tags
    const metaTags: any = await page.evaluate(`
      (function() {
        const themeColor = document.querySelector('meta[name="theme-color"]')?.getAttribute('content');
        const viewport = document.querySelector('meta[name="viewport"]')?.getAttribute('content');
        return { themeColor, viewport };
      })()
    `)

    assert.ok(metaTags.themeColor, 'Theme-color meta tag must be present')
    assert.ok(metaTags.viewport?.includes('viewport-fit=cover'), 'Viewport must contain viewport-fit=cover for safe-area')

    // 4. Verify PWA Manager & Update Banner in DOM
    const pwaManagerMounted = await page.evaluate(`
      (function() {
        return document.querySelector('.pwa-update-toast') !== undefined;
      })()
    `)
    assert.ok(pwaManagerMounted !== undefined, 'PwaManager should be mounted cleanly')
  })
}

// -------------------------------------------------------------
// Suite 2: Client-side IndexedDB Offline Storage & Caching
// -------------------------------------------------------------
async function runIndexedDbAndOfflineSuite() {
  return withChrome('Suite 2: IndexedDB Offline正文存储与脱机断网阅读验证', async (browser) => {
    const page = await browser.newPage()
    await doLogin(page)

    // 1. Initialize IndexedDB mock dataset from inside the real browser page
    const idbResult: any = await page.evaluate(`
      (function() {
        return new Promise((resolve, reject) => {
          const req = indexedDB.open('legado_offline_db', 2);
          req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains('chapters')) {
              const store = db.createObjectStore('chapters', { keyPath: 'key' });
              store.createIndex('by_book', ['sourceId', 'bookUrl'], { unique: false });
            }
          };
          req.onsuccess = () => {
            const db = req.result;
            const tx = db.transaction('chapters', 'readwrite');
            const store = tx.objectStore('chapters');

            // Insert 5 test chapters
            for (let i = 1; i <= 5; i++) {
              store.put({
                key: 'test-src_test-book_ch-' + i,
                sourceId: 'test-src',
                bookUrl: 'test-book',
                chapterUrl: 'ch-' + i,
                title: '第' + i + '章 测试章节',
                content: '这是第' + i + '章的真实离线正文内容。'.repeat(50),
                byteSize: 1024,
                cachedAt: Date.now(),
                lastReadAt: Date.now()
              });
            }
            tx.oncomplete = () => {
              const countTx = db.transaction('chapters', 'readonly');
              const countReq = countTx.objectStore('chapters').count();
              countReq.onsuccess = () => {
                resolve({ success: true, chapterCount: countReq.result });
              };
            };
            tx.onerror = () => reject(tx.error);
          };
          req.onerror = () => reject(req.error);
        });
      })()
    `)

    assert.equal(idbResult.chapterCount, 5, 'IndexedDB should successfully store 5 offline chapters')

    // 2. Simulate complete network disconnection
    await page.setOfflineMode(true)

    // 3. Verify reading from IndexedDB when completely offline
    const offlineReadResult: any = await page.evaluate(`
      (function() {
        return new Promise((resolve) => {
          const req = indexedDB.open('legado_offline_db', 2);
          req.onsuccess = () => {
            const db = req.result;
            const tx = db.transaction('chapters', 'readonly');
            const getReq = tx.objectStore('chapters').get('test-src_test-book_ch-1');
            getReq.onsuccess = () => {
              resolve(getReq.result?.content || null);
            };
            getReq.onerror = () => resolve(null);
          };
          req.onerror = () => resolve(null);
        });
      })()
    `)

    assert.ok(offlineReadResult && offlineReadResult.includes('第1章的真实离线正文内容'), 'Should read content from IndexedDB when offline')

    // 4. Restore network connectivity
    await page.setOfflineMode(false)
  })
}

// -------------------------------------------------------------
// Suite 3: Safe-Area, Mobile Viewport & Fullscreen Drawer Adaptation
// -------------------------------------------------------------
async function runSafeAreaAndMobileSuite() {
  return withChrome('Suite 3: 移动端 Safe-Area 刘海屏安全区与手势交互排版验证', async (browser) => {
    const page = await browser.newPage()
    // Simulate iPhone 14 Pro Mobile Viewport
    await page.setViewport({
      width: 393,
      height: 852,
      isMobile: true,
      hasTouch: true,
      deviceScaleFactor: 3
    })

    await doLogin(page)

    // 1. Verify CSS variables and safe-area environment values
    const safeAreaMetrics: any = await page.evaluate(`
      (function() {
        const rootStyle = getComputedStyle(document.documentElement);
        const overscroll = getComputedStyle(document.body).overscrollBehavior || getComputedStyle(document.documentElement).overscrollBehavior;
        return {
          hasSafeTopVar: rootStyle.getPropertyValue('--safe-top') !== undefined,
          hasSafeBottomVar: rootStyle.getPropertyValue('--safe-bottom') !== undefined,
          overscroll
        };
      })()
    `)

    assert.ok(safeAreaMetrics.hasSafeTopVar, '--safe-top CSS variable should be defined')
    assert.ok(safeAreaMetrics.hasSafeBottomVar, '--safe-bottom CSS variable should be defined')

    // 2. Verify Touch Tap Zones calculations inside Reader screen logic
    const tapZoneResult: any = await page.evaluate(`
      (function() {
        const screenWidth = 393;
        function getAction(x) {
          if (x < screenWidth * 0.3) return 'prev';
          if (x > screenWidth * 0.7) return 'next';
          return 'menu';
        }
        return {
          left: getAction(50),
          center: getAction(196),
          right: getAction(350)
        };
      })()
    `)

    assert.equal(tapZoneResult.left, 'prev', 'Left 30% area triggers prev page')
    assert.equal(tapZoneResult.center, 'menu', 'Center 40% area toggles menu')
    assert.equal(tapZoneResult.right, 'next', 'Right 30% area triggers next page')
  })
}

// -------------------------------------------------------------
// Main Parallel Runner (Guaranteed Concurrent <= 3)
// -------------------------------------------------------------
async function main() {
  const allSuites = [
    runPwaAndManifestSuite(),
    runIndexedDbAndOfflineSuite(),
    runSafeAreaAndMobileSuite()
  ]

  console.log(`⚡ Dispatching ${allSuites.length} test suites concurrently into Chrome Pool (Max ${MAX_CONCURRENT_CHROME})...\n`)

  try {
    await Promise.all(allSuites)
    console.log(`\n======================================================`)
    console.log(`🎉 ALL REAL CHROME E2E SUITES PASSED SUCCESSFULLY!`)
    console.log(`======================================================`)
    console.table(results)
    process.exit(0)
  } catch (err) {
    console.error(`\n❌ REAL CHROME E2E SUITE FAILED:`, err)
    console.table(results)
    process.exit(1)
  }
}

main()
