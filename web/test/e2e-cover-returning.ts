/**
 * Reproduce what a RETURNING browser sees: a browser that already has the
 * Service Worker + legado-covers cache populated from BEFORE the fix.
 *
 * Strategy: reuse a persistent Chrome profile so the SW and CacheStorage survive
 * between runs. Run twice — the second run is a "returning visitor".
 */
import puppeteer from 'puppeteer-core'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const CHROME = [
  process.env.CHROME_BIN,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
].filter(Boolean).find((p) => { try { return fs.existsSync(p) } catch { return false } })

if (!CHROME) { console.log('SKIP: no chrome'); process.exit(0) }

const BASE = process.env.LEGADO_BASE || 'http://127.0.0.1:8080'
const PASSWORD = process.env.LEGADO_PASSWORD || 'w71251478'
const PROFILE = path.join(os.tmpdir(), 'legado-returning-profile')

console.log('profile:', PROFILE)
console.log('exists :', fs.existsSync(PROFILE))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  userDataDir: PROFILE,          // <-- persistent: SW + CacheStorage survive
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
})

try {
  const page = await browser.newPage()

  const coverResponses = []
  page.on('response', async (r) => {
    if (!r.url().includes('/api/covers/')) return
    let len = -1
    try { len = (await r.buffer()).length } catch {}
    coverResponses.push(`${r.status()} ${r.headers()['content-type'] || '?'} bytes=${len} fromSW=${r.fromServiceWorker()}`)
  })

  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 2000))

  const pw = await page.$('input[type=password]')
  if (pw) {
    await pw.type(PASSWORD)
    await page.keyboard.press('Enter')
    await new Promise((r) => setTimeout(r, 2500))
  }

  await page.goto(`${BASE}/#shelf`, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 4000))

  // what SW / caches exist?
  const swState = await page.evaluate(async () => {
    const regs = await navigator.serviceWorker.getRegistrations()
    const names = await caches.keys()
    const out = { regs: regs.length, controller: !!navigator.serviceWorker.controller, caches: names, coverCacheCount: -1 }
    const coverCacheName = names.find((n) => n.includes('cover'))
    if (coverCacheName) {
      const c = await caches.open(coverCacheName)
      out.coverCacheCount = (await c.keys()).length
    }
    return out
  })
  console.log('\n=== service worker / cache state ===')
  console.log(JSON.stringify(swState, null, 2))

  const imgs = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.shelf-card-cover img')).map((el) => {
      const i = el as HTMLImageElement
      return { src: i.getAttribute('src') || '', w: i.naturalWidth, h: i.naturalHeight, complete: i.complete }
    }))
  const cards = await page.evaluate(() => document.querySelectorAll('.shelf-card-cover').length)
  const fallbacks = await page.evaluate(() => document.querySelectorAll('.shelf-card-cover .cover-fallback').length)

  console.log('\n=== rendered covers ===')
  console.log(`cards=${cards} imgs=${imgs.length} fallbacks=${fallbacks}`)
  let broken = 0
  for (const i of imgs) {
    const ok = i.complete && i.w > 0 && i.h > 0
    if (!ok) broken++
    console.log(`  ${ok ? 'OK    ' : 'BROKEN'} ${String(i.src).slice(0, 80)} natural=${i.w}x${i.h}`)
  }

  console.log('\n=== cover responses (fromSW=true means Service Worker served it) ===')
  for (const r of coverResponses.slice(0, 12)) console.log('  ' + r)

  // deliberately inspect the Cache Storage content: is it gzip?
  const cacheBytes = await page.evaluate(async () => {
    const names = await caches.keys()
    const n = names.find((x) => x.includes('cover'))
    if (!n) return { note: 'no cover cache' }
    const c = await caches.open(n)
    const keys = await c.keys()
    const out = []
    for (const k of keys.slice(0, 5)) {
      const res = await c.match(k)
      if (!res) continue
      const buf = new Uint8Array(await res.arrayBuffer())
      out.push({ url: k.url.slice(-20), bytes: buf.length, magic: Array.from(buf.slice(0, 4)).map((b) => b.toString(16).padStart(2, '0')).join(' ') })
    }
    return { cacheName: n, entries: out }
  })
  console.log('\n=== Cache Storage contents (magic ff d8 ff e0 = real JPEG, 1f 8b = gzip) ===')
  console.log(JSON.stringify(cacheBytes, null, 2))

  console.log(broken === 0 ? '\nPASS (returning browser)' : `\nFAIL (returning browser): ${broken} broken`)
} finally {
  await browser.close()
}
