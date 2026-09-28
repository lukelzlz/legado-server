/**
 * E2E 回归：书架封面必须在真实浏览器中**成功解码**。
 *
 * 为什么需要这个用例（SESSION-027）：
 * 封面曾经"HTTP 200、字节数正常、接口测试全绿"，但浏览器里是**空白/裂图**——
 * 因为磁盘上存的是 gzip 字节而 Content-Type 谎报 image/jpeg
 * （`HttpResponse.BodyHandlers.ofByteArray()` 不解压）。
 * 这类缺陷**只有真实浏览器能发现**：任何"断言 200 + 长度"的接口测试都会放过它。
 *
 * 因此本用例的判定标准是 `img.naturalWidth > 0`（真正解码出像素），
 * 而不是响应状态码。
 *
 * 用法：先启动服务，再 `npx tsx web/test/e2e-cover-browser.ts`
 * 可通过 LEGADO_BASE / LEGADO_PASSWORD 覆盖；找不到浏览器时**跳过**（不误报失败）。
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
try {
  const page = await browser.newPage()

  // 记录封面请求的响应，便于失败时给出可操作的信息
  const responses: string[] = []
  page.on('response', async (r) => {
    if (!r.url().includes('/api/covers/')) return
    let len = -1
    try { len = (await r.buffer()).length } catch {}
    responses.push(`${r.status()} ${r.headers()['content-type'] || '?'} bytes=${len}`)
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
  await new Promise((r) => setTimeout(r, 3500))

  const cards = await page.evaluate(() => document.querySelectorAll('.shelf-card-cover').length)
  const fallbacks = await page.evaluate(() => document.querySelectorAll('.shelf-card-cover .cover-fallback').length)
  const imgs = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.shelf-card-cover img')).map((el) => {
      const i = el as HTMLImageElement
      return { src: i.getAttribute('src') || '', complete: i.complete, w: i.naturalWidth, h: i.naturalHeight }
    }))

  console.log(`shelf cards=${cards}  cover <img>=${imgs.length}  letter-fallbacks=${fallbacks}`)

  if (cards === 0) {
    console.log('SKIP: 书架没有书籍，无法验证封面')
    process.exit(0)
  }

  // 每张封面都必须真正解码出像素
  for (const i of imgs) {
    const ok = i.complete && i.w > 0 && i.h > 0
    console.log(`  ${ok ? 'OK    ' : 'BROKEN'} ${String(i.src).slice(0, 90)} natural=${i.w}x${i.h}`)
    if (!ok) failures++
  }

  // 有卡片却没有 <img> 说明全部退化成文字占位符（也是封面不可用）
  if (imgs.length < cards) {
    console.log(`  BROKEN 有 ${cards} 张卡片但只有 ${imgs.length} 个 <img>（其余退化成了文字占位符）`)
    failures++
  }

  if (failures > 0) {
    console.log('\n封面响应记录（诊断用）:')
    for (const r of responses.slice(0, 10)) console.log('  ' + r)
    console.log('\n提示：若 bytes>0 但 natural=0，多半是落盘字节不是可解码图片')
    console.log('（例如上游 gzip 未解压就存，见 SESSION-027）。')
  }

  console.log(failures === 0 ? '\nPASS: 全部封面在浏览器中成功解码' : `\nFAIL: ${failures} 个封面无法解码`)
  process.exitCode = failures === 0 ? 0 : 1
} finally {
  await browser.close()
}
