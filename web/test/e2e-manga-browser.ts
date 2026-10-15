/**
 * E2E 回归（方案 E · P3）：漫画图片必须在真实浏览器中**成功解码**，且断网后仍能读已缓存章节。
 *
 * 为什么必须用浏览器（沿用封面那次的教训，SESSION-027）：
 * 「接口 200 + 字节数正常」**证明不了浏览器能显示**（gzip 当 JPEG 存就是这么骗过所有接口测试的）。
 * 唯一可信的判据是 `img.naturalWidth > 0`。
 *
 * 为什么还要测断网：
 * P3 的交付物是"图在 Service Worker 缓存里、清单在本机快照里"两件事**同时**成立。
 * 只测联网渲染的话，少任何一件都发现不了——少缓存 ⇒ 断网全白；少快照 ⇒ 图在缓存里界面也全白。
 *
 * 用法：先启动服务（`./gradlew :server:run` 或 docker），再
 *   npx tsx web/test/e2e-manga-browser.ts
 * 可用 LEGADO_BASE / LEGADO_PASSWORD / CHROME_BIN 覆盖；书架里没有漫画、或找不到浏览器时**跳过**。
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'

const CHROME_CANDIDATES = [
  process.env.CHROME_BIN,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
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

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
})

let failures = 0
const imageResponses: string[] = []

try {
  const page = await browser.newPage()
  page.on('response', (r) => {
    if (!r.url().includes('/api/images/')) return
    imageResponses.push(`${r.status()} ${r.headers()['content-type'] || '?'} ${r.url().slice(-12)}`)
  })

  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1200))
  const pw = await page.$('input[type=password]')
  if (pw) {
    await pw.type(PASSWORD)
    await page.keyboard.press('Enter')
    await new Promise((r) => setTimeout(r, 2500))
  }
  await page.goto(`${BASE}/#shelf`, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 2500))

  // 找一本漫画（书架卡片带漫画徽标）
  const comicIndex = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.shelf-card, .shelf-item, li, article'))
    return cards.findIndex((el) => el.querySelector('.shelf-card-tag-comic') !== null)
  })
  if (comicIndex < 0) {
    console.log('SKIP: 书架里没有漫画书（导入一个漫画源并打开一章后再跑）')
    process.exit(0)
  }
  await page.evaluate((index) => {
    const cards = Array.from(document.querySelectorAll('.shelf-card, .shelf-item, li, article'))
    const target = cards[index] as HTMLElement | undefined
    target?.click()
  }, comicIndex)
  await new Promise((r) => setTimeout(r, 6000))

  const readImages = async () => page.evaluate(() =>
    Array.from(document.querySelectorAll('.manga-scroll img')).map((el) => {
      const i = el as HTMLImageElement
      return { src: i.getAttribute('src') || '', complete: i.complete, w: i.naturalWidth, h: i.naturalHeight }
    }))

  const online = await readImages()
  console.log(`在线：漫画 <img> = ${online.length} 张`)
  if (online.length === 0) {
    console.log('SKIP: 打开的这本书没有渲染出图片（可能不是漫画，或该书源规则没配好）')
    process.exit(0)
  }
  // 铁律：判断"图片可用"只能看 naturalWidth（fetch().size 会骗人）
  for (const i of online.slice(0, 5)) {
    const ok = i.complete && i.w > 0 && i.h > 0
    console.log(`  ${ok ? 'OK    ' : 'BROKEN'} ${i.src.slice(0, 60)} natural=${i.w}x${i.h}`)
    if (!ok) failures++
  }
  if (online.filter(i => i.complete && i.w > 0).length === 0) {
    console.log('FAIL: 在线状态下没有任何一张图成功解码')
    failures++
  }

  // Service Worker 的 legado-manga 缓存必须真的有东西（P3 的 SW 规则是否生效，只能这么验）
  const cached = await page.evaluate(async () => {
    if (typeof caches === 'undefined') return { supported: false, count: 0 }
    const names = await caches.keys()
    if (!names.includes('legado-manga')) return { supported: true, count: 0 }
    const cache = await caches.open('legado-manga')
    return { supported: true, count: (await cache.keys()).length }
  })
  console.log(`SW 缓存 legado-manga：${cached.supported ? `${cached.count} 个条目` : '当前浏览器不支持 caches'}`)
  if (cached.supported && cached.count === 0) {
    console.log('FAIL: 图片已渲染但 SW 缓存为空 —— 整章预热/缓存规则没生效，断网会全白')
    failures++
  }

  // ---- 飞行模式：重开同一本书，应走"本地快照 + SW 缓存" ----
  await page.setOfflineMode(true)
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 1500))
  await page.evaluate((index) => {
    const cards = Array.from(document.querySelectorAll('.shelf-card, .shelf-item, li, article'))
    const target = cards[index] as HTMLElement | undefined
    target?.click()
  }, comicIndex)
  await new Promise((r) => setTimeout(r, 6000))

  const offline = await readImages()
  const banner = await page.evaluate(() => document.querySelectorAll('.manga-offline-banner').length)
  const decodedOffline = offline.filter(i => i.complete && i.w > 0).length
  console.log(`离线：<img> = ${offline.length} 张，成功解码 ${decodedOffline} 张，离线横幅 = ${banner}`)
  if (offline.length === 0) {
    console.log('FAIL: 断网后连清单快照都没渲染出来（本地快照缺失）')
    failures++
  } else if (decodedOffline === 0) {
    console.log('FAIL: 断网后一张图都没解码（SW 缓存没命中）')
    failures++
  }
  await page.setOfflineMode(false)

  if (failures > 0) {
    console.log('\n图片响应记录（诊断用）:')
    for (const line of imageResponses.slice(0, 10)) console.log('  ' + line)
    console.log('\n提示：若状态 200 但 natural=0，多半是落盘字节不是可解码图片（见 SESSION-027）。')
  }
  console.log(failures === 0 ? '\nPASS: 漫画图片在线可解码，且断网后仍可读已缓存章节' : `\nFAIL: ${failures} 项不通过`)
  process.exitCode = failures === 0 ? 0 : 1
} finally {
  await browser.close()
}
