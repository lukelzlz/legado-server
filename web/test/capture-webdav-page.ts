import puppeteer, { type Page } from 'puppeteer-core'
import fs from 'node:fs/promises'

const CHROME = process.env.CHROME_BIN ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = process.env.LEGADO_BASE ?? 'http://127.0.0.1:8080'
const PASSWORD = process.env.LEGADO_PASSWORD ?? '<你的登录密码>'
const OUT = process.env.SHOT_DIR ?? 'C:\\Users\\w1593\\AppData\\Local\\Temp\\legado-webdav-shots'

async function ensureLoggedIn(page: Page) {
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' })
  const hasPasswordField = await page.$('input[type="password"]')
  if (hasPasswordField) {
    await page.type('input[type="password"]', PASSWORD)
    await page.click('.login-panel button.primary-button')
  }
  await page.waitForSelector('.app-page-header', { timeout: 25000 })
}

async function inspectPage(page: Page, label: string) {
  const texts = (selector: string) =>
    page.$$eval(selector, elements => elements.map(element => (element as HTMLElement).innerText.trim()))
  const box = (selector: string) =>
    page.$eval(selector, element => {
      const rect = (element as HTMLElement).getBoundingClientRect()
      return { width: Math.round(rect.width), height: Math.round(rect.height) }
    }).catch(() => null)
  const viewport = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  return {
    label,
    navActive: await texts('.app-page-header nav button.active'),
    navLabels: await texts('.app-page-header nav button'),
    statusValues: await texts('.webdav-card-value'),
    guideTitles: await texts('.webdav-guide-head strong'),
    guideCommands: await texts('.webdav-guide-command'),
    fileRows: await texts('.webdav-file-row .webdav-file-name'),
    breadcrumbs: await texts('.webdav-crumb'),
    notice: await texts('.webdav-notice'),
    layout: {
      viewport: viewport.innerWidth,
      documentScrollWidth: viewport.scrollWidth,
      hasHorizontalOverflow: viewport.scrollWidth > viewport.innerWidth + 1,
      page: await box('.webdav-page'),
      statusGrid: await box('.webdav-status-grid'),
      guideGrid: await box('.webdav-guide-grid'),
      fileList: await box('.webdav-file-list'),
      firstRow: await box('.webdav-file-row'),
    },
  }
}

async function main() {
  await fs.mkdir(OUT, { recursive: true })
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  const errors: string[] = []
  try {
    const desktop = await browser.newPage()
    desktop.on('pageerror', error => errors.push(`desktop pageerror: ${String(error)}`))
    desktop.on('console', message => { if (message.type() === 'error') errors.push(`desktop console: ${message.text()}`) })
    await desktop.setViewport({ width: 1440, height: 960 })
    await ensureLoggedIn(desktop)
    await desktop.goto(`${BASE}/#webdav`, { waitUntil: 'networkidle2' })
    await desktop.waitForSelector('.webdav-file-row', { timeout: 20000 })
    await new Promise(resolve => setTimeout(resolve, 800))
    await desktop.screenshot({ path: `${OUT}/webdav-desktop.png`, fullPage: true })
    const desktopReport = await inspectPage(desktop, 'desktop-1440')

    await desktop.click('.webdav-file-name.is-link')
    await new Promise(resolve => setTimeout(resolve, 1000))
    await desktop.screenshot({ path: `${OUT}/webdav-subdir.png`, fullPage: true })
    const subdirReport = await inspectPage(desktop, 'desktop-subdir')

    // 回到根目录，验证页面内上传（会话 + CSRF）与删除
    await desktop.click('.webdav-crumb')
    await new Promise(resolve => setTimeout(resolve, 800))
    const sampleFile = `${OUT}/uploaded-from-page.txt`
    await fs.writeFile(sampleFile, 'uploaded from the WebDAV settings page\n', 'utf8')
    const fileInput = await desktop.$('.webdav-files-actions input[type="file"]')
    await fileInput!.uploadFile(sampleFile)
    await desktop.waitForFunction(
      name => Array.from(document.querySelectorAll('.webdav-file-row .webdav-file-name')).some(element => element.textContent === name),
      { timeout: 20000 },
      'uploaded-from-page.txt',
    )
    const afterUpload = await desktop.$$eval('.webdav-file-row .webdav-file-name', elements => elements.map(element => (element as HTMLElement).innerText))

    desktop.on('dialog', dialog => void dialog.accept())
    const rowIndex = afterUpload.indexOf('uploaded-from-page.txt')
    await desktop.click(`.webdav-file-row:nth-child(${rowIndex + 1}) .danger-btn`)
    await desktop.waitForFunction(
      expected => document.querySelectorAll('.webdav-file-row').length === expected
        && !Array.from(document.querySelectorAll('.webdav-file-row .webdav-file-name')).some(element => element.textContent === 'uploaded-from-page.txt'),
      { timeout: 20000 },
      afterUpload.length - 1,
    )
    const afterDelete = await desktop.$$eval('.webdav-file-row .webdav-file-name', elements => elements.map(element => (element as HTMLElement).innerText))
    const uploadFlow = { afterUpload, afterDelete }

    const mobile = await browser.newPage()
    mobile.on('pageerror', error => errors.push(`mobile pageerror: ${String(error)}`))
    mobile.on('console', message => { if (message.type() === 'error') errors.push(`mobile console: ${message.text()}`) })
    await mobile.setViewport({ width: 390, height: 844, isMobile: true, deviceScaleFactor: 2 })
    await ensureLoggedIn(mobile)
    await mobile.goto(`${BASE}/#webdav`, { waitUntil: 'networkidle2' })
    await mobile.waitForSelector('.webdav-file-row', { timeout: 20000 })
    await new Promise(resolve => setTimeout(resolve, 800))
    await mobile.screenshot({ path: `${OUT}/webdav-mobile.png`, fullPage: true })
    const mobileReport = await inspectPage(mobile, 'mobile-390')

    console.log(JSON.stringify({ out: OUT, desktopReport, subdirReport, uploadFlow, mobileReport, errors }, null, 2))
  } finally {
    await browser.close()
  }
}

void main()
