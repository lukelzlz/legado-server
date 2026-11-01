/**
 * 墨水屏版（`/simple/`、`/kindle`）入口的**真浏览器**验证。
 *
 * 背景（2026-11-01 用户报障）：`http://host/simple/#shelf` 在**用过桌面版**的浏览器里打开的是
 * **电脑版界面**。根因是桌面版的 Workbox SW 在 `scope:'/'` 下用 `navigateFallback: '/index.html'`
 * 兜底了所有导航，而 denylist 只排除了 `/api/`，于是 `/simple/` 被回退成缓存里的桌面 index.html。
 *
 * 这条用例必须**先访问桌面版并让 SW 生效**，再去打开 `/simple/`，否则测不到这个缺陷（干净上下文永远是好的）。
 *
 * 运行：
 *   $env:CHROME_BIN="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
 *   $env:TEST_BASE_URL="http://127.0.0.1:8080"
 *   npx tsx web/test/e2e-simple-eink-browser.ts
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

type Snapshot = {
  path: string
  desktopShell: boolean
  simpleShell: boolean
  simpleTitle: string | null
  controlled: boolean
}

async function waitForServer(url: string, timeoutMs = 30000): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      if ((await fetch(`${url}/healthz`)).ok) return
    } catch {
      /* 还没起来 */
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`服务端 ${url} 在 ${timeoutMs}ms 内没有响应`)
}

async function main(): Promise<void> {
  await waitForServer(BASE_URL)

  // 0) 服务端层面：`/simple/` 必须直接返回墨水屏页面（不是桌面 SPA 的 index.html）
  const simpleHtml = await (await fetch(`${BASE_URL}/simple/`)).text()
  assert.ok(simpleHtml.includes('id="pageBox"'), '服务端应直出墨水屏页面（#pageBox）')
  assert.equal(simpleHtml.includes('app-shell'), false, '服务端不能把桌面版 index.html 当成 /simple/ 返回')

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-simple-e2e-'))
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    userDataDir,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--window-size=1280,900'],
  })

  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 900 })
    page.on('pageerror', error => console.log(`   [browser pageerror] ${error.message}`))

    const snapshot = (): Promise<Snapshot> => page.evaluate(() => ({
      path: location.pathname + location.hash,
      desktopShell: Boolean(document.querySelector('.app-shell')),
      simpleShell: Boolean(document.querySelector('#pageBox')),
      simpleTitle: document.querySelector('.shelf-title')?.textContent?.trim() ?? null,
      controlled: Boolean(navigator.serviceWorker?.controller),
    })) as Promise<Snapshot>

    // 1) 干净上下文：`/simple/` 本来就该是墨水屏版
    await page.goto(`${BASE_URL}/simple/`, { waitUntil: 'networkidle2' })
    const fresh = await snapshot()
    assert.equal(fresh.simpleShell, true, `干净上下文 /simple/ 应是墨水屏版：${JSON.stringify(fresh)}`)
    assert.equal(fresh.desktopShell, false)
    console.log(`1/4 fresh /simple/ -> simple=${fresh.simpleShell} title=${fresh.simpleTitle}`)

    // 2) 访问桌面版，让 Service Worker 装上并接管页面（这一步是复现缺陷的前提）
    await page.goto(`${BASE_URL}/`, { waitUntil: 'networkidle2' })
    await new Promise(resolve => setTimeout(resolve, 2500))
    await page.reload({ waitUntil: 'networkidle2' })
    await new Promise(resolve => setTimeout(resolve, 2500))
    let controlled = false
    for (let attempt = 0; attempt < 10 && !controlled; attempt++) {
      controlled = (await snapshot()).controlled
      if (!controlled) await new Promise(resolve => setTimeout(resolve, 500))
    }
    assert.equal(controlled, true, '桌面版应当被 Service Worker 控制（否则这条用例测不到缺陷）')
    console.log('2/4 desktop page is now controlled by the service worker')

    // 3) 关键断言：SW 已控制的情况下打开 `/simple/#shelf` 仍然必须是墨水屏版
    await page.goto(`${BASE_URL}/simple/#shelf`, { waitUntil: 'networkidle2' })
    await new Promise(resolve => setTimeout(resolve, 1500))
    const hijackCheck = await snapshot()
    assert.equal(
      hijackCheck.desktopShell,
      false,
      `❌ /simple/#shelf 被 SW 回退成了桌面版（${
        JSON.stringify(hijackCheck)
      }）—— 检查 vite.config.ts 的 navigateFallbackDenylist 是否排除了 /simple/ 与 /kindle`,
    )
    assert.equal(hijackCheck.simpleShell, true, `❌ /simple/#shelf 没有渲染墨水屏页面：${JSON.stringify(hijackCheck)}`)
    console.log(`3/4 /simple/#shelf under SW control -> simple=${hijackCheck.simpleShell} title=${hijackCheck.simpleTitle}`)

    // 4) `/kindle` 走重定向后也必须是墨水屏版
    await page.goto(`${BASE_URL}/kindle/`, { waitUntil: 'networkidle2' })
    await new Promise(resolve => setTimeout(resolve, 1200))
    const kindle = await snapshot()
    assert.equal(kindle.simpleShell, true, `/kindle/ 应是墨水屏版：${JSON.stringify(kindle)}`)
    assert.equal(kindle.desktopShell, false, `/kindle/ 不能是桌面版：${JSON.stringify(kindle)}`)
    console.log(`4/4 /kindle/ -> ${kindle.path} simple=${kindle.simpleShell}`)

    console.log('\nALL SIMPLE-EINK ENTRY CHECKS PASSED')
  } finally {
    await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
  }
}

main()
  .then(() => process.exit(0))
  .catch(error => {
    console.error('simple eink e2e failed:', error)
    process.exit(1)
  })
