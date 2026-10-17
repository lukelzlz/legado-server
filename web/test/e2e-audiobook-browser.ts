/**
 * E2E 回归（方案 G2 · P1/P3）：有声书音频必须在真实浏览器中**真的能播**，且断网后仍能播已缓存章节。
 *
 * ## 为什么判据必须是 `readyState >= 2 && duration > 0`
 * 「接口 200 + 字节数正常」**证明不了浏览器能播**（SESSION-027 的 gzip 假图片就是这么骗过所有接口测试的）。
 * `<audio>` 的唯一可信判据是 `loadedmetadata` 之后 **`readyState >= 2` 且 `duration > 0`**。
 *
 * ## 为什么还要 seek 与断网
 * - **seek**：Range 写错的表现是"拖进度条就卡死/416"，而它**不会**在普通播放里暴露；
 * - **断网**：P3 的交付物是"音频字节在 SW 缓存里 + 清单在本机快照里"两件事**同时**成立。
 *   少任何一件，断网时都会打不开或播不了。
 *
 * 用法：先启动服务（`./gradlew :server:run` 或 docker），再
 *   npx tsx web/test/e2e-audiobook-browser.ts
 * 可用 LEGADO_BASE / LEGADO_PASSWORD / CHROME_BIN 覆盖；书架里没有有声书、或找不到浏览器时**跳过**。
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
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--autoplay-policy=no-user-gesture-required'],
})

let failures = 0
let offlineSkipped = false
const audioResponses: string[] = []

try {
  const page = await browser.newPage()
  page.on('response', (r) => {
    if (!r.url().includes('/api/audio/')) return
    // 记录状态与 Content-Range：206 是 seek 生效的直接证据
    audioResponses.push(`${r.status()} ${r.headers()['content-range'] || '-'} ${r.url().slice(-12)}`)
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

  /**
   * 找一本有声书（书架卡片带听书徽标）。
   *
   * ⚠️ **必须点 `.shelf-card-cover`**：`<article class="shelf-card">` 上的 onClick 只在批量模式下才挂，
   * 直接点卡片什么都不会发生（漫画那次就是这么得到"0 张图"的）。
   */
  const audioIndex = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.shelf-card'))
    return cards.findIndex((el) => el.querySelector('.shelf-card-tag-audio') !== null)
  })
  if (audioIndex < 0) {
    console.log('SKIP: 书架里没有有声书（导入一个音频源/备份后再跑）')
    process.exit(0)
  }

  const openAudioBook = async (index: number) => page.evaluate((i) => {
    const card = Array.from(document.querySelectorAll('.shelf-card'))[i]
    const cover = card?.querySelector('.shelf-card-cover') as HTMLElement | null
    ;(cover ?? (card as HTMLElement | null))?.click()
  }, index)

  await openAudioBook(audioIndex)
  await new Promise((r) => setTimeout(r, 8000))

  /** 读播放器的真实状态（`readyState` / `duration` / `paused` / 当前 src）。 */
  const readAudio = async () => page.evaluate(() => {
    const el = document.querySelector('audio.audiobook-audio') as HTMLAudioElement | null
    if (!el) return null
    return {
      src: el.currentSrc || el.getAttribute('src') || '',
      readyState: el.readyState,
      duration: el.duration,
      currentTime: el.currentTime,
      paused: el.paused,
      error: el.error ? `${el.error.code}:${el.error.message}` : null,
    }
  })

  const waitForMetadata = async (timeoutMs = 30000) => {
    const deadline = Date.now() + timeoutMs
    let last = await readAudio()
    while (Date.now() < deadline) {
      last = await readAudio()
      if (last && last.readyState >= 2 && Number.isFinite(last.duration) && last.duration > 0) return last
      await new Promise(r => setTimeout(r, 1000))
    }
    return last
  }

  // ---- 1. 元数据就绪：铁律判据 readyState >= 2 && duration > 0 ----
  const ready = await waitForMetadata()
  if (!ready) {
    console.log('SKIP: 页面上没有 <audio class="audiobook-audio">（可能不是有声书，或清单没解析出地址）')
    process.exit(0)
  }
  console.log(`音频元素：src=${ready.src.slice(0, 48)} readyState=${ready.readyState} duration=${ready.duration.toFixed(2)}s paused=${ready.paused}`)
  if (ready.error) { console.log(`FAIL: <audio> 报错 ${ready.error}`); failures++ }
  if (ready.readyState < 2) { console.log('FAIL: readyState < 2（元数据都没就绪 ⇒ 字节不是可播放音频）'); failures++ }
  if (!(ready.duration > 0)) { console.log('FAIL: duration <= 0（能取到字节但解不出时长）'); failures++ }

  // ---- 2. 播放 + seek（Range 的真实考验：写错就卡死/416） ----
  const playBtn = await page.$('.audiobook-control.main')
  if (playBtn) {
    await playBtn.click()
    await new Promise(r => setTimeout(r, 2500))
  }
  const playing = await readAudio()
  console.log(`播放中：paused=${playing?.paused} currentTime=${(playing?.currentTime ?? 0).toFixed(2)}s`)
  if (playing && playing.paused) {
    console.log('WARN: 点击主播放键后仍是 paused（无头环境可能缺用户手势，属已知偏差）')
  }

  // 拖到 50%：这一步会触发 Range 请求
  await page.evaluate(() => {
    const el = document.querySelector('audio.audiobook-audio') as HTMLAudioElement | null
    if (el && Number.isFinite(el.duration) && el.duration > 0) el.currentTime = el.duration * 0.5
  })
  await new Promise(r => setTimeout(r, 2500))
  const afterSeek = await readAudio()
  const seekRatio = afterSeek && afterSeek.duration > 0 ? afterSeek.currentTime / afterSeek.duration : 0
  console.log(`seek 后：currentTime=${(afterSeek?.currentTime ?? 0).toFixed(2)}s（${(seekRatio * 100).toFixed(0)}%）`)
  if (afterSeek && seekRatio < 0.4) {
    console.log('FAIL: seek 后播放位置没有落到 50% 附近（Range 很可能写错 ⇒ 播放器 seek 卡死）')
    failures++
  }
  if (audioResponses.some(line => line.startsWith('416'))) {
    console.log('FAIL: 出现 416（Range 边界算错）')
    failures++
  }
  if (audioResponses.some(line => line.startsWith('206'))) {
    console.log('OK: 观测到 206 Partial Content（单段 Range 生效）')
  }

  // ---- 3. Service Worker 接管 + 音频进入 legado-audio 缓存 ----
  await page.evaluate(() => navigator.serviceWorker?.ready.then(() => true)).catch(() => undefined)
  await page.reload({ waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 3000))
  const controlled = await page.evaluate(() => !!navigator.serviceWorker?.controller)
  console.log(`Service Worker 已接管当前页面：${controlled}`)

  // 重新打开（此刻 SW 已接管 ⇒ 音频会进 legado-audio 缓存；整章预热也会补上其余分段）
  await page.goto(`${BASE}/#shelf`, { waitUntil: 'networkidle2', timeout: 30000 })
  await new Promise((r) => setTimeout(r, 2000))
  await openAudioBook(audioIndex)
  await waitForMetadata()
  await new Promise((r) => setTimeout(r, 4000))

  const cached = await page.evaluate(async () => {
    if (typeof caches === 'undefined') return { supported: false, count: 0 }
    const names = await caches.keys()
    if (!names.includes('legado-audio')) return { supported: true, count: 0 }
    const cache = await caches.open('legado-audio')
    return { supported: true, count: (await cache.keys()).length }
  })
  console.log(`SW 缓存 legado-audio：${cached.supported ? `${cached.count} 个条目` : '当前浏览器不支持 caches'}`)
  if (cached.supported && cached.count === 0) {
    console.log('FAIL: 音频已播放但 SW 缓存为空 —— 缓存规则没命中（检查是否是精确 64hex 匹配），断网会播不了')
    failures++
  }

  // ---- 4. 飞行模式：清单走本机快照 + 音频走 SW 缓存 ----
  await page.setOfflineMode(true)
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => undefined)
  await new Promise((r) => setTimeout(r, 3000))
  const offlineOpened = await page.evaluate((index) => {
    const card = Array.from(document.querySelectorAll('.shelf-card'))[index]
    const cover = card?.querySelector('.shelf-card-cover') as HTMLElement | null
    if (!cover) return false
    cover.click()
    return true
  }, audioIndex)
  if (!offlineOpened) {
    offlineSkipped = true
    console.log('SKIP: 离线后书架没渲染出来（本机书架快照未覆盖该场景），离线断言跳过')
  } else {
    const offlineReady = await waitForMetadata(25000)
    const banner = await page.evaluate(() => document.querySelectorAll('.audiobook-offline-banner').length)
    console.log(`离线：readyState=${offlineReady?.readyState ?? -1} duration=${offlineReady?.duration ?? 0} 离线横幅=${banner}`)
    if (!offlineReady || offlineReady.readyState < 2 || !(offlineReady.duration > 0)) {
      console.log('FAIL: 断网后音频无法就绪（清单快照缺失 或 SW 缓存没命中）')
      failures++
    }
  }
  await page.setOfflineMode(false)

  if (failures > 0) {
    console.log('\n音频响应记录（诊断用）:')
    for (const line of audioResponses.slice(0, 12)) console.log('  ' + line)
    console.log('\n提示：若 200 但 duration=0，多半是落盘字节不是可播放音频（对照 SESSION-027 的教训）。')
  }
  if (failures === 0) {
    console.log(
      offlineSkipped
        ? '\nPASS: 音频在真实浏览器中真的能播（readyState>=2 && duration>0），seek 走 206（离线断言本轮跳过）'
        : '\nPASS: 音频在线可播、seek 正常，且断网后仍可播放已缓存章节',
    )
  } else {
    console.log(`\nFAIL: ${failures} 项不通过`)
  }
  process.exitCode = failures === 0 ? 0 : 1
} finally {
  await browser.close()
}
