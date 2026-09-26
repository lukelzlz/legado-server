import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'

process.env.NO_PROXY = '127.0.0.1,localhost'
process.env.no_proxy = '127.0.0.1,localhost'

const CHROME_PATH = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8095'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'
const ARTIFACT_DIR = process.env.ARTIFACT_DIR || '/Users/zhangran/.gemini/antigravity/brain/c2ace220-77ec-4e9c-ad15-bbfb0923260b'

async function waitForServer(url: string, timeoutMs = 30000): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`${url}/healthz`)
      if (res.ok) return
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`Server at ${url} failed to respond within ${timeoutMs}ms`)
}

async function run() {
  console.log('🚀 Starting Local Meta Search E2E Browser Verification...')
  await waitForServer(BASE_URL)
  console.log('✅ Backend server is responsive at', BASE_URL)

  await fs.mkdir(ARTIFACT_DIR, { recursive: true })

  // 1. Login via API to prepare test source and import local book
  const loginRes = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: ADMIN_PASSWORD }),
  })
  assert.ok(loginRes.ok, 'Login via API succeeded')
  const cookie = loginRes.headers.get('set-cookie') || ''
  const csrfToken = (await loginRes.json() as { csrfToken: string }).csrfToken

  // 2. Add mock book source
  const mockSource = {
    bookSourceName: '刺猬猫轻小说',
    bookSourceUrl: 'https://ciweimao.mock',
    enabled: true,
    mainJs: `
      function search(k, p) {
        return JSON.stringify([
          {
            name: k,
            author: '魔女恋爱大师',
            bookUrl: 'https://ciweimao.mock/book/1',
            coverUrl: 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=160',
            intro: '一觉醒来竟然变成了高冷魔女？！然而身边的美少女们看我的眼神怎么越来越不对劲了……'
          },
          {
            name: k + '（番外篇）',
            author: '魔女恋爱大师',
            bookUrl: 'https://ciweimao.mock/book/2',
            coverUrl: 'https://images.unsplash.com/photo-1512820790803-83ca734da794?w=160',
            intro: '番外篇特别篇，全新日常剧情展开！'
          }
        ]);
      }
    `
  }

  const sourceRes = await fetch(`${BASE_URL}/api/sources/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Cookie': cookie,
      'X-CSRF-Token': csrfToken,
    },
    body: JSON.stringify({ sources: [JSON.stringify(mockSource)] }),
  })
  assert.ok(sourceRes.ok, `Mock source registered successfully (status: ${sourceRes.status})`)
  console.log('✅ Mock search book source registered')

  // 3. Upload test local book
  const fixturePath = path.resolve('web/test/fixtures/变成魔女，但是她们都想跟我恋爱.txt')
  const fileBytes = await fs.readFile(fixturePath)
  const formData = new FormData()
  formData.append('file', new Blob([fileBytes], { type: 'text/plain' }), '变成魔女，但是她们都想跟我恋爱.txt')

  const importRes = await fetch(`${BASE_URL}/api/bookshelf/import-local`, {
    method: 'POST',
    headers: {
      'Cookie': cookie,
      'X-CSRF-Token': csrfToken,
    },
    body: formData,
  })
  assert.ok(importRes.ok, 'Local book imported successfully')
  const importJson = await importRes.json() as { imported: number }
  assert.equal(importJson.imported, 1)
  console.log('✅ Local book imported into bookshelf')

  // 4. Launch Headless Chrome
  const tempUserDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-meta-test-'))
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    userDataDir: tempUserDataDir,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--window-size=1280,900',
    ],
  })

  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 900 })

    page.on('console', msg => {
      const txt = msg.text()
      if (txt.includes('Error') || txt.includes('error')) console.log('PAGE LOG:', txt)
    })
    page.on('pageerror', err => console.log('PAGE ERROR:', err.message))

    // Step A: Login via Web UI
    console.log('🌐 Opening Web UI at', `${BASE_URL}/#shelf`)
    await page.goto(`${BASE_URL}/#shelf`, { waitUntil: 'networkidle2' })

    const passInput = await page.waitForSelector('form.login-panel input[type="password"]', { timeout: 10000 })
    assert.ok(passInput, 'Password input found')
    await passInput.type(ADMIN_PASSWORD)
    await page.click('form.login-panel button.primary-button')
    try {
      await page.waitForSelector('.app-page-header, .shelf-page, nav[aria-label="主导航"]', { timeout: 10000 })
    } catch (e) {
      await page.screenshot({ path: path.join(ARTIFACT_DIR, 'debug_login_error.png') })
      const body = await page.$eval('body', el => el.innerText)
      console.log('PAGE BODY TEXT ON TIMEOUT:', body)
      throw e
    }
    console.log('✅ Logged in successfully!')

    // Step B: Ensure on Bookshelf
    await page.waitForSelector('.shelf-card', { timeout: 15000 })
    console.log('📚 Bookshelf loaded')

    await page.screenshot({ path: path.join(ARTIFACT_DIR, '01_bookshelf_initial.png') })

    // Step C: Find our imported book and click manage button
    const manageBtn = await page.waitForSelector('button.shelf-btn-manage', { timeout: 8000 })
    assert.ok(manageBtn, 'Manage button found')
    await manageBtn.click()

    // Step D: In Manage modal, click "编辑书籍信息"
    const editActionBtn = await page.waitForSelector('button.manage-action-row', { timeout: 5000 })
    assert.ok(editActionBtn, 'Edit action button found')
    await editActionBtn.click()

    // Step E: Wait for BookInfoEditModal
    await page.waitForSelector('.book-info-edit-modal', { timeout: 5000 })
    console.log('📝 BookInfoEditModal is open')

    // Verify "🔍 联网搜索补全信息" button exists with "推荐" badge
    const searchToggleBtn = await page.waitForSelector('.meta-search-toggle-btn', { timeout: 5000 })
    assert.ok(searchToggleBtn, 'Search toggle button exists in modal')
    const badgeText = await page.$eval('.meta-search-badge', el => el.textContent?.trim()).catch(() => '')
    assert.equal(badgeText, '推荐', 'Recommends search completion for local book')

    await page.screenshot({ path: path.join(ARTIFACT_DIR, '02_edit_modal_with_recommend_button.png') })
    console.log('📸 Captured 02_edit_modal_with_recommend_button.png')

    // Step F: Click search toggle button to expand search panel and start search
    await searchToggleBtn.click()

    // Wait for search results to stream in
    await page.waitForSelector('.meta-search-card', { timeout: 10000 })
    console.log('🔍 Search results rendered in modal')

    await page.screenshot({ path: path.join(ARTIFACT_DIR, '03_search_results_rendered.png') })
    console.log('📸 Captured 03_search_results_rendered.png')

    // Step G: Click "选用" on candidate card
    const applyBtn = await page.waitForSelector('.meta-apply-btn', { timeout: 5000 })
    assert.ok(applyBtn, 'Apply button found on candidate card')
    await applyBtn.click()

    // Verify author and coverUrl are populated
    await new Promise(r => setTimeout(r, 600))
    const authorValue = await page.$eval('input[placeholder="作者（可选）"]', el => (el as HTMLInputElement).value)
    assert.equal(authorValue, '魔女恋爱大师', 'Author field filled with candidate author')

    const appliedText = await page.$eval('.meta-apply-btn.applied', el => el.textContent?.trim()).catch(() => '')
    assert.ok(appliedText.includes('已选用'), 'Apply button displays "已选用"')

    await page.screenshot({ path: path.join(ARTIFACT_DIR, '04_candidate_applied.png') })
    console.log('📸 Captured 04_candidate_applied.png')

    // Step H: Save modifications
    const saveBtn = await page.waitForSelector('button[type="submit"].primary-button', { timeout: 5000 })
    assert.ok(saveBtn, 'Save button found')
    await saveBtn.click()

    // Wait for modal to close
    await page.waitForFunction(() => !document.querySelector('.book-info-edit-modal'), { timeout: 5000 })
    console.log('💾 Modifications saved successfully')

    // Step H2: Close BookManageModal if open
    const closeManageBtn = await page.waitForSelector('.book-manage-sheet button.close-btn', { timeout: 5000 }).catch(() => null)
    if (closeManageBtn) {
      await closeManageBtn.click()
      await page.waitForFunction(() => !document.querySelector('.book-manage-sheet'), { timeout: 5000 })
      console.log('🚪 BookManageModal closed')
    }

    // Step I: Verify on Bookshelf
    await new Promise(r => setTimeout(r, 800))
    await page.screenshot({ path: path.join(ARTIFACT_DIR, '05_bookshelf_updated.png') })
    console.log('📸 Captured 05_bookshelf_updated.png')

    // Verify book card displays author
    const shelfText = await page.$eval('body', el => el.textContent || '')
    assert.ok(shelfText.includes('魔女恋爱大师'), 'Bookshelf card shows updated author name')

    // Step J: Click read button to read local chapter
    const readBtn = await page.waitForSelector('.shelf-btn-read', { timeout: 5000 })
    assert.ok(readBtn, 'Read button found')
    await readBtn.click()

    // Wait for reader to open and display chapter content
    await page.waitForSelector('.reader-workspace, .reader-paginated-wrap, .chapter-text', { timeout: 12000 })
    await new Promise(r => setTimeout(r, 1000))
    const readerText = await page.$eval('body', el => el.textContent || '')
    assert.ok(readerText.includes('第一章 穿越变成魔女'), 'Local chapter content loaded properly')
    console.log('📖 Reader opened local chapter successfully')

    await page.screenshot({ path: path.join(ARTIFACT_DIR, '06_reading_local_book.png') })
    console.log('📸 Captured 06_reading_local_book.png')

    console.log('\n🎉 ALL REAL BROWSER E2E TESTS PASSED PERFECTLY!\n')
  } finally {
    await browser.close()
    await fs.rm(tempUserDataDir, { recursive: true, force: true }).catch(() => {})
  }
}

run().catch(err => {
  console.error('❌ E2E Browser Test Failed:', err)
  process.exit(1)
})
