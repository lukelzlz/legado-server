import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'

const CHROME_PATH = process.env.CHROME_BIN || '/usr/bin/google-chrome' || '/usr/bin/chromium-browser'
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8095'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'
const SCREENSHOT_DIR = '/root/.gemini/antigravity-cli/brain/49a9b075-2710-4ee0-8194-e7f7ac896718'

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

async function runBrowserVerification() {
  console.log(`\n======================================================`)
  console.log(`🌐 Running Real Chrome End-to-End Browser Verification`)
  console.log(`📍 URL: ${BASE_URL}`)
  console.log(`🖥️ Chrome: ${CHROME_PATH}`)
  console.log(`======================================================\n`)

  console.log('⏳ Waiting for test server to be ready...')
  await waitForServer(BASE_URL)
  console.log('✅ Server is ready!')

  const tempUserDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-chrome-test-'))
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

    // Step 1: Login
    console.log('1️⃣ Navigating to login page...')
    await page.goto(BASE_URL, { waitUntil: 'networkidle2' })
    const passInput = await page.waitForSelector('input[type="password"]', { timeout: 10000 })
    assert.ok(passInput, 'Password input found')
    await passInput.type(ADMIN_PASSWORD)
    const loginButton = await page.$('button.primary-button')
    if (loginButton) {
      await loginButton.click()
    } else {
      await page.keyboard.press('Enter')
    }
    await page.waitForSelector('.app-page-header, .shelf-page, nav[aria-label="主导航"]', { timeout: 15000 })
    console.log('✅ Logged in successfully!')

    // Step 2: Navigate to Shelf
    console.log('2️⃣ Navigating to Shelf (#shelf)...')
    await page.goto(`${BASE_URL}/#shelf`, { waitUntil: 'networkidle2' })
    await page.waitForSelector('.shelf-page', { timeout: 10000 })
    console.log('✅ Shelf page rendered!')

    // Step 3: Create and upload sample TXT book
    const txtPath = path.join(os.tmpdir(), '《剑破九天》作者：苍穹剑圣.txt')
    const txtContent = `第一章 仙路初开
大道三千，少年执剑前行。风雪漫天，天地肃穆。

第二章 剑指苍穹
九霄之上，剑光璀璨如烈阳，撕裂混沌。

第三章 终极彼岸
剑道通神，傲立诸天之巅。`
    await fs.writeFile(txtPath, txtContent, 'utf-8')

    console.log('3️⃣ Uploading local TXT book...')
    const fileInput = await page.waitForSelector('input[type="file"]', { timeout: 5000 })
    assert.ok(fileInput, 'File input element found')
    await fileInput.uploadFile(txtPath)

    // Wait for card to appear
    await page.waitForSelector('.shelf-card', { timeout: 15000 })
    const cardTitle = await page.$eval('.shelf-card-name', (el) => el.textContent?.trim())
    const cardAuthor = await page.$eval('.shelf-card-author', (el) => el.textContent?.trim())
    const hasLocalTag = await page.$eval('.shelf-card-tag-local', (el) => el.textContent?.trim())

    console.log(`📚 Book card displayed: Title = "${cardTitle}", Author = "${cardAuthor}", Tag = "${hasLocalTag}"`)
    assert.equal(cardTitle, '剑破九天')
    assert.equal(cardAuthor, '苍穹剑圣')
    assert.equal(hasLocalTag, '本地')
    console.log('✅ Local book uploaded and rendered on shelf with "本地" badge!')

    // Take screenshot of shelf with local book
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'screenshot_shelf_local_book.png'), fullPage: false })
    console.log('📸 Screenshot saved: screenshot_shelf_local_book.png')

    // Step 4: Open Reader
    console.log('4️⃣ Clicking book card to open ReaderScreen...')
    await page.click('.shelf-card-cover')
    await page.waitForSelector('.reading-content, .reader-paginated-viewport, .reader-header', { timeout: 15000 })
    console.log('✅ Reader opened!')

    // Verify reader content
    await page.waitForSelector('.reading-content, .reader-paginated-body', { timeout: 10000 })
    await new Promise((r) => setTimeout(r, 1000))
    const readerText = await page.evaluate(() => document.body.innerText)
    assert.ok(readerText.includes('大道三千，少年执剑前行'), 'Chapter 1 content rendered properly in reader')
    console.log('✅ Reader chapter 1 text rendered properly!')

    // Take screenshot of reader
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'screenshot_reader_local_book.png'), fullPage: false })
    console.log('📸 Screenshot saved: screenshot_reader_local_book.png')

    // Step 5: Return to Shelf & Manage Book
    console.log('5️⃣ Returning to shelf and managing book...')
    await page.goto(`${BASE_URL}/#shelf`, { waitUntil: 'networkidle2' })
    await page.waitForSelector('.shelf-card', { timeout: 10000 })

    // Open management modal
    await page.click('.shelf-btn-manage')
    await page.waitForSelector('.book-manage-sheet', { timeout: 5000 })
    console.log('✅ Book manage sheet opened! Verifying switch source is hidden for local books...')
    const switchSourceExists = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('.manage-action-row strong'))
      return rows.some((r) => r.textContent?.includes('切换书源'))
    })
    assert.equal(switchSourceExists, false, 'Switch source button must be hidden for local book')
    console.log('✅ Confirmed: Switch source option is hidden for local book!')

    // Close modal
    await page.click('.close-btn')
    await new Promise((r) => setTimeout(r, 500))

    // Step 7: Create and upload sample EPUB book
    console.log('7️⃣ Creating and uploading sample EPUB book...')
    const epubPath = path.join(os.tmpdir(), '星海领航者.epub')
    const { execFileSync } = await import('node:child_process')
    const pythonScript = `
import zipfile, io

with zipfile.ZipFile('${epubPath}', 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
    z.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')
    z.writestr('OEBPS/content.opf', '''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>星海领航者</dc:title>
    <dc:creator>银河旅人</dc:creator>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>''')
    z.writestr('OEBPS/toc.ncx', '''<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="p1" playOrder="1"><navLabel><text>第一章 启航深空</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="p2" playOrder="2"><navLabel><text>第二章 折跃奇点</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navMap>
</ncx>''')
    z.writestr('OEBPS/ch1.xhtml', '<html><body><h2>第一章 启航深空</h2><p>群星闪烁，曲率引擎轰鸣启动。</p></body></html>')
    z.writestr('OEBPS/ch2.xhtml', '<html><body><h2>第二章 折跃奇点</h2><p>穿越时空走廊，抵达未知的星系。</p></body></html>')
`
    execFileSync('python3', ['-c', pythonScript])

    const fileInput2 = await page.waitForSelector('input[type="file"]', { timeout: 5000 })
    assert.ok(fileInput2, 'File input found for EPUB')
    await fileInput2.uploadFile(epubPath)

    // Wait for 2nd book card to appear
    await page.waitForFunction(() => document.querySelectorAll('.shelf-card').length >= 2, { timeout: 15000 })
    console.log('✅ EPUB book uploaded successfully and displayed alongside TXT book on shelf!')

    // Capture updated shelf screenshot showing both TXT and EPUB books
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'screenshot_shelf_local_book.png'), fullPage: false })
    console.log('📸 Updated screenshot saved: screenshot_shelf_local_book.png')

    console.log('\n🎉 ALL REAL BROWSER E2E TESTS PASSED PERFECTLY! 🎉\n')
  } finally {
    await browser.close()
    await fs.rm(tempUserDataDir, { recursive: true, force: true }).catch(() => {})
  }
}

runBrowserVerification()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error('❌ Browser verification failed:', err)
    process.exit(1)
  })
