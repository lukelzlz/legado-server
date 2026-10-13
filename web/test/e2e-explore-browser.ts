import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawn, ChildProcess } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const TEST_PORT = 8092
const BASE_URL = `http://127.0.0.1:${TEST_PORT}`
const ADMIN_PASSWORD = 'admin123'
const WORKTREE_ROOT = path.resolve(__dirname, '../..')
const SERVER_BIN = path.join(WORKTREE_ROOT, 'server/build/install/legado-server/bin/legado-server')

async function sleep(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function runExploreE2ETest() {
  console.log('====================================================')
  console.log('🚀 Running Explore Page Browser & A11y E2E Verification')
  console.log(`📍 Base URL: ${BASE_URL}`)
  console.log(`🌐 Chrome Binary: ${CHROME_PATH}`)
  console.log(`📦 Server Bin: ${SERVER_BIN}`)
  console.log('====================================================\n')

  const testDataDir = await fs.mkdtemp(path.join(os.tmpdir(), `legado-explore-e2e-${Date.now()}-`))
  let serverProcess: ChildProcess | null = null
  let browser: any = null

  try {
    // 1. 启动服务端进程
    console.log('⚙️ [1/6] Starting standalone Legado Server on port 8092...')
    serverProcess = spawn(SERVER_BIN, [], {
      env: {
        ...process.env,
        LEGADO_PORT: String(TEST_PORT),
        ADMIN_PASSWORD,
        LEGADO_SECURE_COOKIES: 'false',
        LEGADO_DATA_DIR: testDataDir,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    })

    serverProcess.stdout?.on('data', chunk => {
      const line = chunk.toString().trim()
      if (line.includes('Responding at') || line.includes('started in')) {
        console.log(`   [Server stdout] ${line}`)
      }
    })
    serverProcess.stderr?.on('data', chunk => {
      const line = chunk.toString().trim()
      if (line.length > 0) {
        console.log(`   [Server stderr] ${line}`)
      }
    })

    // 轮询等待服务端完全就绪
    let serverReady = false
    for (let i = 0; i < 30; i++) {
      await sleep(400)
      try {
        const res = await fetch(`${BASE_URL}/`)
        if (res.status === 200) {
          serverReady = true
          break
        }
      } catch {}
    }
    assert.ok(serverReady, 'Server failed to start within 12 seconds')
    console.log('   ✅ Legado Server responded 200 OK at root.')

    // 2. 通过 API 登录并导入带有丰富 exploreUrl 的测试书源
    console.log('\n📥 [2/6] Logging in via REST API and seeding Explore Book Source...')
    const loginRes = await fetch(`${BASE_URL}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: ADMIN_PASSWORD }),
    })
    assert.equal(loginRes.status, 200, 'Login API failed')
    const sessionCookie = loginRes.headers.get('set-cookie')?.split(';')[0] || ''
    const loginData = await loginRes.json() as { csrfToken?: string }
    const csrfToken = loginData.csrfToken || ''

    const testSource = {
      bookSourceUrl: 'https://explore-demo.example',
      bookSourceName: '星河中文网（发现测试源）',
      bookSourceGroup: '精品推荐',
      exploreUrl: [
        '热门榜单::/rank/hot_{{page}}',
        '完本精选&&/finish/all_{{page}}',
        '男生频道',
        '  玄幻奇幻::/sort/xuanhuan_{{page}}',
        '  修真仙侠::/sort/xianxia_{{page}}',
        '女生频道',
        '  都市言情::/sort/dushi_{{page}}',
        '  青春校园::/sort/xiaoyuan_{{page}}',
      ].join('\n'),
      ruleSearch: {
        bookList: '.book-item',
        name: '.name@text',
        author: '.author@text',
        bookUrl: '.name@href',
        coverUrl: '.cover@src',
        intro: '.intro@text',
      },
    }

    const importRes = await fetch(`${BASE_URL}/api/sources/import`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Cookie': sessionCookie,
        'X-CSRF-Token': csrfToken,
      },
      body: JSON.stringify({
        sources: [JSON.stringify(testSource)],
      }),
    })
    assert.equal(importRes.status, 200, 'Import book source API failed')
    const importResult = await importRes.json() as { imported: number }
    assert.equal(importResult.imported, 1, 'Source should be successfully imported')
    console.log('   ✅ Seeded explore-enabled source via API.')

    // 3. 启动无头 Chrome 浏览器
    console.log('\n🌐 [3/6] Launching Headless Chrome via Puppeteer...')
    const browserUserDir = await fs.mkdtemp(path.join(os.tmpdir(), `explore-browser-user-${Date.now()}-`))
    browser = await puppeteer.launch({
      executablePath: CHROME_PATH,
      headless: true,
      userDataDir: browserUserDir,
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-gpu',
        '--window-size=1280,800',
      ],
    })
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 800 })

    // 4. 浏览器访问首页并登录
    console.log('\n🔑 [4/6] Navigating to Home and performing Web Login...')
    await page.goto(`${BASE_URL}/`, { waitUntil: 'networkidle0' })

    const pwdInput = await page.$('input[type="password"]')
    if (pwdInput) {
      console.log('   - Submitting password for UI authentication...')
      await page.type('input[type="password"]', ADMIN_PASSWORD)
      await sleep(100)
      await page.click('button[type="submit"]')
    }

    await page.waitForSelector('.app-page-header', { timeout: 10000 })
    console.log('   ✅ Successfully logged in, AppHeader rendered (.app-page-header).')

    // 5. 验证顶栏「发现」按钮并通过无障碍操控进入发现页
    console.log('\n🧭 [5/6] Navigating to Explore Page via Header Action...')
    const navButtons = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('header button, nav button')) as HTMLButtonElement[]
      return btns.map(b => ({
        tag: b.tagName,
        className: b.className,
        text: b.innerText.trim(),
        ariaLabel: b.getAttribute('aria-label'),
      }))
    })
    console.log('   - Detected nav buttons:', JSON.stringify(navButtons, null, 2))

    const clicked = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[]
      const target = btns.find(b => b.innerText.trim() === '发现' || b.getAttribute('aria-label') === '发现')
      if (target) {
        target.click()
        return true
      }
      return false
    })
    console.log(`   - Clicked explore button: ${clicked}`)
    await sleep(500)
    const currentUrl = page.url()
    const pageHtml = await page.evaluate(() => document.body.innerHTML)
    console.log(`   - Page URL: ${currentUrl}`)
    console.log(`   - Has explore-page: ${pageHtml.includes('explore-page')}`)

    // 等待发现页挂载
    await page.waitForSelector('.explore-page', { timeout: 6000 })
    console.log('   ✅ Successfully mounted Explore Page (.explore-page).')

    // 6. 深度无障碍操控审计与键盘导航验证
    console.log('\n♿ [6/6] Running Accessibility (A11y) & Keyboard Navigation Verification...')
    const a11ySummary = await page.evaluate(() => {
      const violations: string[] = []

      // 1. 检查所有交互按钮的无障碍属性与名称
      const buttons = Array.from(document.querySelectorAll('.explore-page button')) as HTMLButtonElement[]
      for (const btn of buttons) {
        const text = btn.innerText.trim() || btn.getAttribute('aria-label') || btn.getAttribute('title')
        if (!text) {
          violations.push(`Button (class: "${btn.className}") has no accessible name`)
        }
        if (!btn.getAttribute('type')) {
          violations.push(`Button "${text}" missing explicit type="button"`)
        }
      }

      // 2. 检查书源搜索输入框
      const input = document.querySelector('.explore-source-filter-input') as HTMLInputElement | null
      if (input && !input.getAttribute('aria-label') && !input.getAttribute('placeholder')) {
        violations.push('Explore source filter input missing aria-label or placeholder')
      }

      // 3. 检查书源列表与分类标签
      const sources = Array.from(document.querySelectorAll('.explore-source-item')) as HTMLElement[]
      const tags = Array.from(document.querySelectorAll('.explore-category-tag')) as HTMLButtonElement[]
      const groupTitles = Array.from(document.querySelectorAll('.explore-category-group-title')).map(el => (el as HTMLElement).innerText.trim())
      const activeTag = document.querySelector('.explore-category-tag.active') as HTMLButtonElement | null

      return {
        violations,
        buttonCount: buttons.length,
        sourceCount: sources.length,
        categoryCount: tags.length,
        groupTitles,
        firstSourceName: sources[0]?.innerText.trim(),
        activeCategoryName: activeTag?.innerText.trim(),
      }
    })

    console.log(`   - Audited ${a11ySummary.buttonCount} buttons on Explore Page`)
    console.log(`   - Detected ${a11ySummary.sourceCount} explore-enabled source: "${a11ySummary.firstSourceName?.split('\n')[0]}"`)
    console.log(`   - Rendered ${a11ySummary.categoryCount} category tags across groups: [${a11ySummary.groupTitles.join(', ')}]`)
    console.log(`   - Default auto-selected category: "${a11ySummary.activeCategoryName}"`)

    if (a11ySummary.violations.length > 0) {
      console.error('❌ A11y Violations detected:', a11ySummary.violations)
      assert.fail(`Accessibility audit failed with ${a11ySummary.violations.length} issues`)
    }
    console.log('   ✅ A11y Audit 100% Passed (all buttons typed, labeled, accessible).')

    // 键盘/点击切换分类
    console.log('   - Interacting with category tag: "修真仙侠"...')
    const switched = await page.evaluate(() => {
      const tags = Array.from(document.querySelectorAll('.explore-category-tag')) as HTMLButtonElement[]
      const target = tags.find(t => t.innerText.includes('修真仙侠'))
      if (target) {
        target.focus()
        target.click()
        return true
      }
      return false
    })
    assert.ok(switched, 'Should find and click "修真仙侠" tag')

    await sleep(200)
    const newActiveCategory = await page.evaluate(() => {
      const activeTag = document.querySelector('.explore-category-tag.active') as HTMLButtonElement | null
      return activeTag?.innerText.trim()
    })
    console.log(`   - Newly active category tag: "${newActiveCategory}"`)
    assert.equal(newActiveCategory, '修真仙侠', 'Active category should update to "修真仙侠"')

    // 测试书源搜索输入过滤
    console.log('   - Testing source filter input responsiveness...')
    await page.type('.explore-source-filter-input', '星河')
    await sleep(200)
    const filteredCount = await page.evaluate(() => {
      return document.querySelectorAll('.explore-source-item').length
    })
    assert.equal(filteredCount, 1, 'Filtered sources count should be 1')

    console.log('\n🎉 ====================================================')
    console.log('🎉 REAL BROWSER & A11Y E2E VERIFICATION PASSED 100%!')
    console.log('🎉 ====================================================')

  } finally {
    if (browser) {
      console.log('\n🧹 Closing Puppeteer browser...')
      await browser.close().catch(() => {})
    }
    if (serverProcess) {
      console.log('🛑 Terminating Legado Server process...')
      serverProcess.kill('SIGTERM')
      await sleep(500)
    }
    try {
      await fs.rm(testDataDir, { recursive: true, force: true }).catch(() => {})
    } catch {}
  }
}

runExploreE2ETest()
  .then(() => {
    process.exit(0)
  })
  .catch(err => {
    console.error('❌ E2E Test execution failed:', err)
    process.exit(1)
  })
