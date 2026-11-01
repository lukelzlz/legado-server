/**
 * 书签功能的**真浏览器**端到端验证（需要一个已启动的服务端）。
 *
 * 为什么要有这个脚本：书签的三处交互（顶栏开列表、列表内加/改/删、底部悬浮栏加书签）
 * 都是纯前端接线，单测只能校验源码结构与纯函数，**接线断了照样全绿**。
 * 这里用真实的 Edge/Chrome 点一遍，并把截图落盘作为验收证据。
 *
 * 运行（Windows，需先起服务）：
 *   $env:CHROME_BIN="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
 *   $env:TEST_BASE_URL="http://127.0.0.1:8080"
 *   $env:ADMIN_PASSWORD="<管理员密码>"
 *   $env:SHOT_DIR="E:\Desktop\legado\_artifacts"
 *   npx tsx web/test/e2e-bookmark-browser.ts
 *
 * 用**本地 TXT 书**当宿主：正文来自服务端本地库（`loc_book`），不依赖任何网络书源，
 * 因此这条用例在任何环境都能稳定跑。
 */
import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'

const CHROME_PATH = process.env.CHROME_BIN
  || (process.platform === 'win32'
    ? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
    : '/usr/bin/google-chrome')
const BASE_URL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8080'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'
const SHOT_DIR = process.env.SHOT_DIR || os.tmpdir()
const BOOK_TITLE = '书签联调书'
const BOOK_AUTHOR = '联调'

async function waitForServer(url: string, timeoutMs = 30000): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`${url}/healthz`)
      if (res.ok) return
    } catch {
      /* 服务还没起来，继续等 */
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`服务端 ${url} 在 ${timeoutMs}ms 内没有响应`)
}

/** 收尾清理：删掉本次上传的本地书与它名下的书签，避免在用户书架上留垃圾。
 *  书签按「书名+作者」关联、删书不会级联删书签（与开源阅读一致），所以必须单独清。 */
async function sessionCookie(): Promise<string> {
  const login = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: ADMIN_PASSWORD }),
  })
  return (login.headers.get('set-cookie') || '').split(';')[0]
}

async function cleanupBook(): Promise<void> {
  const login = await fetch(`${BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: ADMIN_PASSWORD }),
  })
  if (!login.ok) return
  const cookie = (login.headers.get('set-cookie') || '').split(';')[0]
  const { csrfToken } = await login.json() as { csrfToken: string }
  const headers = { Cookie: cookie, 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }

  // 本地 TXT 书没有作者 ⇒ 书签落库时 bookAuthor 为空；查询/清理都**只按书名**（带了作者会匹配不到）
  const query = `bookName=${encodeURIComponent(BOOK_TITLE)}`
  const marks = await (await fetch(`${BASE_URL}/api/bookmarks?${query}`, { headers: { Cookie: cookie } })).json() as {
    items: Array<{ id: number }>
  }
  for (const mark of marks.items ?? []) {
    await fetch(`${BASE_URL}/api/bookmarks/${mark.id}`, { method: 'DELETE', headers })
  }

  const shelf = await (await fetch(`${BASE_URL}/api/bookshelf`, { headers: { Cookie: cookie } })).json() as Array<{
    sourceId: string
    bookUrl: string
    name: string
  }>
  for (const book of shelf.filter(item => item.name === BOOK_TITLE)) {
    await fetch(`${BASE_URL}/api/bookshelf`, {
      method: 'DELETE',
      headers,
      body: JSON.stringify({ sourceId: book.sourceId, bookUrl: book.bookUrl }),
    })
  }
}

/** 阅读器的顶栏/底栏默认是隐藏的（`toolbars-hidden` 时 `pointer-events: none`），
 *  点正文**中间**才唤出（上下三分之一分别是上一页/下一页的翻滚区，点错会翻章）。
 *  正文还在 loading 时点击会被忽略，所以这里重试几次。 */
async function showReaderToolbars(page: import('puppeteer-core').Page): Promise<void> {
  for (let attempt = 0; attempt < 6; attempt++) {
    if (await page.$('.reader-workspace.toolbars-open')) return
    const point = await page.evaluate(() => ({ x: window.innerWidth / 2, y: window.innerHeight / 2 }))
    await page.mouse.click(point.x, point.y)
    await new Promise(resolve => setTimeout(resolve, 600))
  }
  const state = await page.$eval('.reader-workspace', el => el.className)
  const diagnostic = await page.evaluate(() => {
    const articles = Array.from(document.querySelectorAll('.reading-content') as unknown as HTMLElement[])
    const cx = window.innerWidth / 2
    const cy = window.innerHeight / 2
    const hit = document.elementFromPoint(cx, cy) as HTMLElement | null
    return {
      class: (document.querySelector('.reader-workspace') as HTMLElement | null)?.className,
      scrollY: window.scrollY,
      articles: articles.map(el => ({
        idx: el.dataset.chapterIndex,
        current: el.className.includes('is-scroll-neighbor') ? false : true,
        top: Math.round(el.getBoundingClientRect().top),
        height: Math.round(el.getBoundingClientRect().height),
      })),
      centerHit: hit ? `${hit.tagName}.${hit.className}` : 'none',
      selection: window.getSelection()?.toString().length ?? 0,
    }
  })
  await page.screenshot({ path: path.join(SHOT_DIR, 'debug-toolbars.png') })
  throw new Error(`点正文没能唤出阅读器工具条，当前 class=${state}，诊断=${JSON.stringify(diagnostic)}`)
}

/** 用**真实滚轮事件**滚动正文。
 *  不能用 `window.scrollTo`：阅读器在恢复/对齐期间会用 rAF 反复纠偏（约 2 秒），
 *  只有真实的 wheel 事件会取消那轮纠偏（`cancelRetry`），这也正是真人滚动的行为。 */
async function scrollReader(page: import('puppeteer-core').Page, deltaY: number): Promise<void> {
  await page.mouse.move(10, Math.round((await page.evaluate(() => window.innerHeight)) / 2))
  await page.mouse.wheel({ deltaY })
  await new Promise(resolve => setTimeout(resolve, 900))
}

/**
 * 点顶栏按钮。
 *
 * 顶栏带 `backdrop-filter`（毛玻璃），Puppeteer 的坐标点击偶尔会落到被模糊的正文上
 * （实测：真实鼠标点得到、CDP 注入的坐标点击点不到），所以这里直接在元素上派发 click。
 * 断言的仍然是**真实的 React 处理器**与真实渲染结果。
 */
async function clickHeaderButton(page: import('puppeteer-core').Page, labelPart: string): Promise<void> {
  const clicked = await page.evaluate((part) => {
    const buttons = Array.from(document.querySelectorAll('.reader-header-actions .reader-icon-button')) as HTMLElement[]
    const target = buttons.find(button => (button.getAttribute('aria-label') || '').includes(part))
    if (!target) return false
    target.click()
    return true
  }, labelPart)
  assert.ok(clicked, `顶栏没有 aria-label 含「${labelPart}」的按钮`)
}

async function main(): Promise<void> {
  await fs.mkdir(SHOT_DIR, { recursive: true })
  await waitForServer(BASE_URL)
  // 先清一遍：上一次跑挂了可能留下同名书与书签，会让「空列表」与计数断言失去确定性
  await cleanupBook().catch(() => undefined)

  const userDataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'legado-bookmark-e2e-'))
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    userDataDir,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--window-size=1280,900'],
  })

  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 900 })
    page.on('console', message => {
      if (message.type() === 'error') console.log(`   [browser console] ${message.text()}`)
    })
    page.on('pageerror', error => console.log(`   [browser pageerror] ${error.message}`))

    // 1) 登录
    await page.goto(BASE_URL, { waitUntil: 'networkidle2' })
    const passwordInput = await page.waitForSelector('input[type="password"]', { timeout: 15000 })
    await passwordInput?.type(ADMIN_PASSWORD)
    await page.keyboard.press('Enter')
    await page.waitForSelector('.shelf-page, .app-page-header, nav[aria-label="主导航"]', { timeout: 20000 })
    console.log('1/9 login ok')

    // 2) 上传一本本地 TXT 书当书签宿主
    await page.goto(`${BASE_URL}/#shelf`, { waitUntil: 'networkidle2' })
    await page.waitForSelector('.shelf-page', { timeout: 15000 })
    const txtPath = path.join(os.tmpdir(), `${BOOK_TITLE}.txt`)
    // 每章够长（几屏），才能在同一章内滚到中间加书签、再跳回来验证落点
    const chapterBody = '　　这是书签联调用的正文段落。'.repeat(200)
    await fs.writeFile(txtPath, `第一章 起\n${chapterBody}\n\n第二章 承\n${chapterBody}\n\n第三章 转\n${chapterBody}\n`, 'utf-8')
    const fileInput = await page.waitForSelector('input[type="file"]', { timeout: 10000 })
    await fileInput?.uploadFile(txtPath)
    await page.waitForFunction(
      (title) => Array.from(document.querySelectorAll('.shelf-card-name')).some(el => el.textContent?.trim() === title),
      { timeout: 30000 },
      BOOK_TITLE,
    )
    console.log('2/9 local book uploaded')

    // 3) 打开阅读器
    await page.evaluate((title) => {
      const cards = Array.from(document.querySelectorAll('.shelf-card')) as HTMLElement[]
      const card = cards.find(item => item.querySelector('.shelf-card-name')?.textContent?.trim() === title)
      ;(card?.querySelector('.shelf-card-cover') as HTMLElement | null)?.click()
    }, BOOK_TITLE)
    await page.waitForSelector('.reader-workspace', { timeout: 20000 })
    await page.waitForSelector('.reading-content, .reader-paginated-viewport', { timeout: 20000 })
    // 正文（段落）真的渲染出来再动手：loading 期间点击会被阅读器忽略
    await page.waitForFunction(() => document.querySelectorAll('.reading-content .reader-paragraph').length > 0, { timeout: 20000 })
    await new Promise(resolve => setTimeout(resolve, 800))
    await showReaderToolbars(page)
    console.log('3/9 reader opened')

    // 4) 顶栏「书签」按钮必须存在且在「设置」前面
    const headerButtons = await page.$$eval('.reader-header-actions .reader-icon-button', nodes => nodes.map(node => node.getAttribute('aria-label') || ''))
    const bookmarkButtonIndex = headerButtons.findIndex(label => label.includes('书签'))
    const settingsButtonIndex = headerButtons.findIndex(label => label.includes('设置'))
    assert.ok(bookmarkButtonIndex >= 0, `顶栏没有书签按钮：${headerButtons.join(' | ')}`)
    assert.ok(settingsButtonIndex >= 0, `顶栏没有设置按钮：${headerButtons.join(' | ')}`)
    assert.ok(bookmarkButtonIndex < settingsButtonIndex, `书签按钮必须在设置前面：${headerButtons.join(' | ')}`)
    console.log(`4/9 header buttons = [${headerButtons.join(', ')}]`)

    // 5) 打开书签列表 → 空态 → 加一条 → 列表出现一条
    await clickHeaderButton(page, '书签')
    try {
      await page.waitForSelector('.bookmark-dialog', { timeout: 10000 })
    } catch (error) {
      await page.screenshot({ path: path.join(SHOT_DIR, 'debug-bookmark-dialog.png') })
      const html = await page.evaluate(() => document.body.innerHTML.length)
      const hasDialog = await page.evaluate(() => Boolean(document.querySelector('.bookmark-dialog')))
      throw new Error(`书签弹窗没有出现（body length=${html}, hasDialog=${hasDialog}）`, { cause: error })
    }
    const emptyText = await page.$eval('.bookmark-dialog', el => el.textContent || '')
    assert.ok(emptyText.includes('暂无书签'), `新书应当是空书签列表：${emptyText.slice(0, 120)}`)
    await (await page.$('.bookmark-add-button'))?.click()
    await page.waitForSelector('.bookmark-item', { timeout: 10000 })
    const firstItem = await page.$eval('.bookmark-item', el => el.textContent || '')
    assert.ok(firstItem.length > 0, '书签条目必须有内容')
    await page.screenshot({ path: path.join(SHOT_DIR, 'bookmark-list.png') })
    console.log(`5/9 bookmark added from list: ${firstItem.replace(/\s+/g, ' ').slice(0, 60)}`)

    // 6) 编辑备注
    await (await page.$$('.bookmark-item-actions button'))[0]?.click()
    await page.waitForSelector('.bookmark-edit', { timeout: 5000 })
    const textareas = await page.$$('.bookmark-edit textarea')
    await textareas[1]?.type('联调备注：这里有个伏笔')
    await (await page.$('.bookmark-edit-actions .primary-button'))?.click()
    await page.waitForFunction(
      () => (document.querySelector('.bookmark-note')?.textContent || '').includes('联调备注'),
      { timeout: 10000 },
    )
    console.log('6/9 note saved and rendered')

    // 7) 跳转真的把视口带回书签位置：先滚到下方，再点第 1 条（落在章首）→ 应回到顶部附近
    await scrollReader(page, 900)
    const scrolledY = await page.evaluate(() => Math.round(window.scrollY))
    assert.ok(scrolledY > 100, `滚轮没有滚动正文（scrollY=${scrolledY}）`)
    await showReaderToolbars(page)
    await clickHeaderButton(page, '书签')
    await page.waitForSelector('.bookmark-item', { timeout: 10000 })
    await (await page.$('.bookmark-jump'))?.click()
    await page.waitForFunction(() => !document.querySelector('.bookmark-dialog'), { timeout: 10000 })
    await page.waitForFunction(() => window.scrollY < 200, { timeout: 15000 })
    console.log(`7/9 scrolled to ${scrolledY}, jump back to chapter start: scrollY=${await page.evaluate(() => Math.round(window.scrollY))}`)

    // 8) 反方向：在中间位置加一条书签 → 滚回顶部 → 跳过去应落在中间
    await scrollReader(page, 700)
    await showReaderToolbars(page)
    await clickHeaderButton(page, '书签')
    await page.waitForSelector('.bookmark-item', { timeout: 10000 })
    const beforeAdd = await page.$$eval('.bookmark-item', nodes => nodes.length)
    // 正文可能还在换章加载中（此时点「添加书签」只会提示稍后再加）⇒ 重试几次点击
    for (let attempt = 0; attempt < 6; attempt++) {
      await (await page.$('.bookmark-add-button'))?.click()
      await new Promise(resolve => setTimeout(resolve, 800))
      if ((await page.$$eval('.bookmark-item', nodes => nodes.length)) > beforeAdd) break
    }
    const afterAdd = await page.$$eval('.bookmark-item', nodes => nodes.length)
    assert.ok(afterAdd > beforeAdd, `中间位置的书签没加上（${beforeAdd} -> ${afterAdd}）`)
    await (await page.$('.bookmark-dialog .close-btn'))?.click()
    await scrollReader(page, -5000)
    await showReaderToolbars(page)
    await clickHeaderButton(page, '书签')
    await page.waitForSelector('.bookmark-item', { timeout: 10000 })
    const jumps = await page.$$('.bookmark-jump')
    await jumps[jumps.length - 1]?.click()
    await page.waitForFunction(() => window.scrollY > 200, { timeout: 15000 })
    console.log(`8/9 jump back to mid-chapter bookmark: scrollY=${await page.evaluate(() => Math.round(window.scrollY))}`)

    // 9) 底部悬浮栏（手机视口）：5 格均分，第 4 格是「添加书签」
    await page.setViewport({ width: 390, height: 844 })
    await new Promise(resolve => setTimeout(resolve, 600))
    await showReaderToolbars(page)
    const navCount = await page.$$eval('.mobile-reader-nav .mobile-nav-btn', nodes => nodes.length)
    assert.equal(navCount, 5, `底部悬浮栏应为 5 格，实际 ${navCount}`)
    const navLabels = await page.$$eval('.mobile-reader-nav .mobile-nav-btn', nodes => nodes.map(node => (node.textContent || '').trim()))
    // 先挪一点再点：同一位置重复加会按唯一键合并（那才是对的），这里要验证的是"新增一条"
    await scrollReader(page, 500)
    await showReaderToolbars(page)
    const countBeforeNav = await (await fetch(`${BASE_URL}/api/bookmarks?bookName=${encodeURIComponent(BOOK_TITLE)}`, {
      headers: { Cookie: await sessionCookie() },
    }).then(res => res.json()) as { items: unknown[] }).items.length
    await (await page.$$('.mobile-reader-nav .mobile-nav-btn'))[3]?.click()
    await page.waitForFunction(() => document.body.innerText.includes('已添加书签'), { timeout: 10000 })
    await page.screenshot({ path: path.join(SHOT_DIR, 'bookmark-mobile-nav.png') })
    const countAfterNav = await (await fetch(`${BASE_URL}/api/bookmarks?bookName=${encodeURIComponent(BOOK_TITLE)}`, {
      headers: { Cookie: await sessionCookie() },
    }).then(res => res.json()) as { items: unknown[] }).items.length
    assert.equal(countAfterNav, countBeforeNav + 1, `底部悬浮栏加书签应新增一条（${countBeforeNav} -> ${countAfterNav}）`)
    await showReaderToolbars(page)
    await clickHeaderButton(page, '书签')
    await page.waitForSelector('.bookmark-item', { timeout: 10000 })
    const itemCount = await page.$$eval('.bookmark-item', nodes => nodes.length)
    assert.equal(itemCount, countAfterNav, `列表条数应与库里一致（${itemCount} vs ${countAfterNav}）`)
    console.log(`9/9 mobile nav = [${navLabels.join(', ')}], bookmarks = ${itemCount}`)

    // 删除一条：行内确认
    await (await page.$$('.bookmark-item-actions .shelf-remove'))[0]?.click()
    await page.waitForSelector('.bookmark-confirm', { timeout: 5000 })
    await (await page.$('.bookmark-confirm .shelf-remove'))?.click()
    await page.waitForFunction(
      (count) => document.querySelectorAll('.bookmark-item').length < count,
      { timeout: 10000 },
      itemCount,
    )
    const afterDelete = await page.$$eval('.bookmark-item', nodes => nodes.length)
    console.log(`   delete ok: ${itemCount} -> ${afterDelete}`)
    await page.screenshot({ path: path.join(SHOT_DIR, 'bookmark-after-delete.png') })

    console.log('\nALL BOOKMARK E2E CHECKS PASSED')
  } finally {
    await browser.close()
    await fs.rm(userDataDir, { recursive: true, force: true }).catch(() => undefined)
    await cleanupBook().catch(() => undefined)
  }
}

main()
  .then(() => process.exit(0))
  .catch(error => {
    console.error('bookmark e2e failed:', error)
    process.exit(1)
  })
