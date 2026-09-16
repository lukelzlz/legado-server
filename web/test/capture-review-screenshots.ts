import puppeteer, { Browser, Page } from 'puppeteer-core'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'

const CHROME_PATH = process.env.CHROME_BIN || '/usr/bin/google-chrome' || '/usr/bin/chromium-browser'
const BASE_URL = 'http://127.0.0.1:5173'
const ARTIFACT_DIR = '/root/.gemini/antigravity-cli/brain/b99e0969-7e8f-4524-99df-e8fd3e8bd17d'
const MAX_CONCURRENT_CHROME = 2 // Strict <= 3 limit

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
  fn: (browser: Browser) => Promise<T>
): Promise<T> {
  await pool.acquire()
  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), `legado-shot-${Date.now()}-`))
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
      ],
    })
    return await fn(browser)
  } finally {
    if (browser) await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => {})
    pool.release()
  }
}

const mockRules = [
  {
    id: 'rule-1',
    name: '起点反爬错字对调清洗',
    group: '反爬清洗',
    pattern: '(大丑|魔男|少萝茜|阁上)',
    replacement: "@js:const map={'大丑':'小丑','魔男':'魔女','少萝茜':'多萝茜','阁上':'阁下'}; return map[result]||result;",
    isRegex: true,
    scope: '诡秘之主,宿命之环',
    scopeTitle: false,
    scopeContent: true,
    isEnabled: true,
    order: 1,
  },
  {
    id: 'rule-2',
    name: '全网引流广告与加群信息',
    group: '广告剔除',
    pattern: '(请记住本书首发域名|关注微信公众号|官方读者群).*',
    replacement: '',
    isRegex: true,
    scope: undefined,
    scopeTitle: true,
    scopeContent: true,
    isEnabled: true,
    order: 2,
  },
  {
    id: 'rule-3',
    name: '错别字纠正（他/她/它）',
    group: '错字纠正',
    pattern: '她男人们',
    replacement: '他们',
    isRegex: false,
    scope: undefined,
    scopeTitle: false,
    scopeContent: true,
    isEnabled: false,
    order: 3,
  },
]

const sampleBookContent = `听到了“愚者”的回答，“倒吊人”阿尔杰悄然松了口气，低下脑袋，谦卑说道：

“请允许我提前赞美您的垂听。”

因为我也很好奇……很好奇一位序列6的“风眷者”相信得到它就可以拥有序列4实力的神奇物品究竟是什么……很好奇一位海盗将军想在贝克兰德做什么……克莱恩微微一笑，保持着高深默然的姿态。

反正我又没有承诺倾听了就要给予帮助！他在心里强调了一句。

不过，比起之前，如今的他有了更多的底气，因为他现实里的盟友，神秘的阿兹克先生正在贝克兰德。

如果确实有必要，克莱恩愿意使用铜哨，请求阿兹克帮忙，当然，他肯定不会提“塔罗会”相关的事情。

阿尔杰停顿了几秒，重新组织好语言，低沉开口道：

“那件神奇物品是‘齐林格斯’从一座古代遗迹里得到的，他并不知道具体的作用，只知道它能帮助自己偷取别人的非凡能力……”`

async function setupPageMocks(page: Page) {
  page.on('console', msg => {
    if (msg.type() === 'error') console.log('PAGE LOG ERROR:', msg.text())
  })
  page.on('pageerror', err => {
    console.log('PAGE ERROR:', err.message)
  })

  await page.setRequestInterception(true)
  page.on('request', req => {
    const url = req.url()
    if (url.includes('/api/auth/session') || url.includes('/api/auth/status')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ authenticated: true, csrfToken: 'mock-csrf' }),
      })
    }
    if (url.includes('/api/bookshelf')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            sourceId: 'source-1',
            bookUrl: 'https://example.com/book/1',
            name: '诡秘之主',
            author: '爱潜水的乌贼',
            tocUrl: 'https://example.com/book/1/toc',
            coverKey: 'gui-mi',
            chapterIndex: 0,
            scrollPosition: 0,
            lastReadAt: Date.now(),
            cachedChapters: 2,
            totalChapters: 1432,
            cacheState: 'ready',
            completed: false,
          },
        ]),
      })
    }
    if (url.includes('/api/replace-rules')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockRules),
      })
    }
    if (url.includes('/api/sources')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'source-1', name: '优书网精选源', url: 'https://example.com', enabled: true, isJsSource: false, hasLogin: false, updatedAt: Date.now(), version: 1 },
        ]),
      })
    }
    if (url.includes('/api/reading-progress')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sourceId: 'source-1',
          bookUrl: 'https://example.com/book/1',
          chapterUrl: 'https://example.com/chapter/179',
          chapterIndex: 0,
          scrollPosition: 0,
          updatedAt: Date.now(),
        }),
      })
    }
    if (url.includes('/api/books/details')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          sourceId: 'source-1',
          name: '诡秘之主',
          author: '爱潜水的乌贼',
          coverUrl: '',
          intro: '蒸汽与机械的浪潮中，谁能触及非凡？历史和黑暗的迷雾里，又是谁在耳语？',
          tocUrl: 'https://example.com/book/1/toc',
        }),
      })
    }
    if (url.includes('/api/books/chapters')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { index: 0, title: '第一百七十九章 赞美“愚者”先生', url: 'https://example.com/chapter/179' },
          { index: 1, title: '第一百八十章 密修会的线索', url: 'https://example.com/chapter/180' },
        ]),
      })
    }
    if (url.includes('/api/books/content')) {
      return req.respond({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          title: '第一百七十九章 赞美“愚者”先生',
          content: sampleBookContent,
        }),
      })
    }
    req.continue()
  })
}

async function injectSafeArea(page: Page, top = 47, bottom = 34) {
  await page.addStyleTag({
    content: `
      :root {
        --safe-top: ${top}px !important;
        --safe-bottom: ${bottom}px !important;
      }
      /* Realistic iPhone status bar overlay */
      body::before {
        content: '<   7:07              5G 60%';
        color: rgba(120, 120, 120, 0.9);
        font-size: 12px;
        font-weight: 600;
        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
        position: fixed;
        top: 6px;
        left: 18px;
        right: 18px;
        height: 20px;
        display: flex;
        justify-content: space-between;
        align-items: center;
        z-index: 99999;
        pointer-events: none;
      }
      body::after {
        content: '';
        position: fixed;
        bottom: 8px;
        left: 50%;
        transform: translateX(-50%);
        width: 134px;
        height: 5px;
        border-radius: 100px;
        background: rgba(120, 120, 120, 0.5);
        z-index: 99999;
        pointer-events: none;
      }
    `,
  })
}

async function runReviewCaptures() {
  console.log(`📸 Starting review screenshot captures...`)
  console.log(`📁 Artifact Target: ${ARTIFACT_DIR}`)

  // 1. Mobile Reader Screen (Light, Paper, Dark with Safe-Area insets)
  await withChrome('Mobile Reader Screen - Light & Paper & Dark Themes', async (browser) => {
    const page = await browser.newPage()
    await page.setViewport({ width: 393, height: 852, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
    await setupPageMocks(page)

    await page.goto(`${BASE_URL}/#shelf`, { waitUntil: 'networkidle0' })
    await page.waitForSelector('.shelf-btn-read', { timeout: 8000 })
    await page.click('.shelf-btn-read')

    await page.waitForSelector('.reading-content', { timeout: 8000 })
    await injectSafeArea(page, 47, 34)

    // Capture Light Theme
    await page.evaluate(() => {
      const root = document.querySelector('.reader-workspace')
      if (root) {
        root.className = root.className.replace(/theme-\w+/, 'theme-light')
      }
    })
    await new Promise(r => setTimeout(r, 200))
    await page.screenshot({ path: path.join(ARTIFACT_DIR, 'review_reader_mobile_light.png'), fullPage: false })
    console.log(`✅ Captured: review_reader_mobile_light.png`)

    // Capture Paper Theme
    await page.evaluate(() => {
      const root = document.querySelector('.reader-workspace')
      if (root) {
        root.className = root.className.replace(/theme-\w+/, 'theme-paper')
      }
    })
    await new Promise(r => setTimeout(r, 200))
    await page.screenshot({ path: path.join(ARTIFACT_DIR, 'review_reader_mobile_paper.png'), fullPage: false })
    console.log(`✅ Captured: review_reader_mobile_paper.png`)

    // Capture Dark Theme
    await page.evaluate(() => {
      const root = document.querySelector('.reader-workspace')
      if (root) {
        root.className = root.className.replace(/theme-\w+/, 'theme-dark')
      }
    })
    await new Promise(r => setTimeout(r, 200))
    await page.screenshot({ path: path.join(ARTIFACT_DIR, 'review_reader_mobile_dark.png'), fullPage: false })
    console.log(`✅ Captured: review_reader_mobile_dark.png`)
  })

  // 2. Replace Rules Page - Desktop & Mobile
  await withChrome('Replace Rules Page - Desktop & Mobile Views', async (browser) => {
    // Desktop View
    const desktopPage = await browser.newPage()
    await desktopPage.setViewport({ width: 1280, height: 800, deviceScaleFactor: 2 })
    await setupPageMocks(desktopPage)

    await desktopPage.goto(`${BASE_URL}/#rules`, { waitUntil: 'networkidle0' })
    await desktopPage.waitForSelector('.rules-page-container', { timeout: 8000 })

    // Desktop Light
    await desktopPage.evaluate(() => {
      document.body.className = 'theme-light'
    })
    await new Promise(r => setTimeout(r, 200))
    await desktopPage.screenshot({ path: path.join(ARTIFACT_DIR, 'review_rules_desktop_light.png') })
    console.log(`✅ Captured: review_rules_desktop_light.png`)

    // Desktop Paper
    await desktopPage.evaluate(() => {
      document.body.className = 'theme-paper'
    })
    await new Promise(r => setTimeout(r, 200))
    await desktopPage.screenshot({ path: path.join(ARTIFACT_DIR, 'review_rules_desktop_paper.png') })
    console.log(`✅ Captured: review_rules_desktop_paper.png`)

    // Desktop Dark
    await desktopPage.evaluate(() => {
      document.body.className = 'theme-dark'
    })
    await new Promise(r => setTimeout(r, 200))
    await desktopPage.screenshot({ path: path.join(ARTIFACT_DIR, 'review_rules_desktop_dark.png') })
    console.log(`✅ Captured: review_rules_desktop_dark.png`)

    // Mobile View
    const mobilePage = await browser.newPage()
    await mobilePage.setViewport({ width: 393, height: 852, deviceScaleFactor: 2, isMobile: true, hasTouch: true })
    await setupPageMocks(mobilePage)

    await mobilePage.goto(`${BASE_URL}/#rules`, { waitUntil: 'networkidle0' })
    await mobilePage.waitForSelector('.rules-page-container', { timeout: 8000 })
    await injectSafeArea(mobilePage, 47, 34)

    // Mobile Light
    await mobilePage.evaluate(() => {
      document.body.className = 'theme-light'
    })
    await new Promise(r => setTimeout(r, 200))
    await mobilePage.screenshot({ path: path.join(ARTIFACT_DIR, 'review_rules_mobile_light.png') })
    console.log(`✅ Captured: review_rules_mobile_light.png`)

    // Mobile Dark with Accordion Card Clicked
    await mobilePage.evaluate(() => {
      document.body.className = 'theme-dark'
      const firstCard = document.querySelector('.rule-list-card') as HTMLElement
      if (firstCard) firstCard.click()
    })
    await new Promise(r => setTimeout(r, 200))
    await mobilePage.screenshot({ path: path.join(ARTIFACT_DIR, 'review_rules_mobile_dark.png') })
    console.log(`✅ Captured: review_rules_mobile_dark.png`)
  })

  console.log(`🎉 All review screenshots captured successfully!`)
}

runReviewCaptures().catch(err => {
  console.error('Screenshot capture failed:', err)
  process.exit(1)
})
