import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { getMangaManifest, mangaManifestKey, putMangaManifest, warmChapterImages } from '../src/mangaOffline'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

/**
 * 方案 E · P3：PWA 整章离线。
 *
 * P3 的三件事全是"**配置写错也不报错**"的类型，所以这里都做成可执行的断言：
 * 1. SW 规则必须与真实注册路由**逐字对齐**（封面就栽过一次死配置：`/api/book/cover` 根本不存在）；
 * 2. 断网时清单接口必然失败 ⇒ 必须有本地快照兜底，否则"图在缓存里、界面却全白"；
 * 3. 整章预热是逐张 fetch，失败静默、并发受限（打满上游会把前台阅读也拖死）。
 */
test('P3 - SW 漫画缓存规则与真实路由逐字对齐（防死配置）', () => {
  const vite = read('../vite.config.ts')
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')

  // 服务端真实注册的是 route("/api") + get("/images/{key}") ⇒ 前端必须按 /api/images/ 匹配
  assert.ok(routes.includes('get("/images/{key}")'), '服务端直出路由是 /images/{key}（挂在 /api 下）')
  assert.ok(routes.includes('route("/api")'), '确认它挂在 /api 之下')

  assert.ok(vite.includes('legado-manga'), 'must 声明漫画缓存名')
  assert.ok(vite.includes("handler: 'CacheFirst'"), '图片按内容寻址 ⇒ CacheFirst 是安全的')

  // 真的用同一套正则验证"匹配什么、不匹配什么"（而不是只做字符串包含）
  const pattern = /^\/api\/images\//
  assert.ok(pattern.test(`/api/images/${'a'.repeat(64)}`), '必须命中真实图片地址')
  for (const wrong of ['/api/books/images/manifest', '/api/books/images/prefetch', '/api/books/images/cache/stats']) {
    assert.ok(!pattern.test(wrong), `${wrong} 是接口（含 POST），绝不能被图片缓存规则吃掉`)
  }
  // 方案 E 原稿写的是 /api/books/images/...（"定位 URL"形态）——照抄就是永不命中的死配置
  assert.ok(
    !vite.includes("urlPattern: /^\\/api\\/books\\/images\\//"),
    '严禁把过时的 /api/books/images/ 当成图片规则',
  )
})

test('P3 - 编译产物 sw.js 里确实存在漫画缓存规则', () => {
  // AGENTS.md 铁律：改 SW 规则必须读**编译后**的 dist/sw.js 复核，否则"改了但没生效"看不出来
  const sw = read('../dist/sw.js')

  assert.ok(sw.includes('legado-manga'), 'dist/sw.js 必须包含漫画缓存名')
  assert.ok(sw.includes('/^\\/api\\/images\\//'), 'dist/sw.js 必须包含 /api/images/ 规则')
  assert.ok(sw.includes('CacheFirst'), 'dist/sw.js 必须是 CacheFirst')
  assert.ok(sw.includes('"GET"'), '必须显式限定 GET（清单/预取都是 POST，不能被缓存）')
})

test('P3 - 整章预热：并发受限、失败静默、可中断', async () => {
  // ① 全部成功 ⇒ 全部计入
  let inFlight = 0
  let maxInFlight = 0
  const okFetch: typeof fetch = async () => {
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise(resolve => setTimeout(resolve, 5))
    inFlight--
    return new Response('x', { status: 200 })
  }
  const ok = await warmChapterImages(['a', 'b', 'c', 'd', 'e', 'f'], { fetchImpl: okFetch, imageUrl: key => `/api/images/${key}` })
  assert.deepEqual(ok, { warmed: 6, total: 6 })
  assert.ok(maxInFlight <= 4, `并发必须受限（实测峰值 ${maxInFlight}，上限 4）`)

  // ② 上游 404/403/断网 ⇒ 静默，绝不抛
  const failing: typeof fetch = async () => new Response('nope', { status: 403 })
  assert.deepEqual(await warmChapterImages(['a', 'b'], { fetchImpl: failing }), { warmed: 0, total: 2 })
  const throwing: typeof fetch = async () => { throw new Error('offline') }
  assert.deepEqual(await warmChapterImages(['a', 'b'], { fetchImpl: throwing }), { warmed: 0, total: 2 })

  // ③ 中断后不再继续（用户切章时不该还在后台猛拉旧章）
  const controller = new AbortController()
  let calls = 0
  const aborting: typeof fetch = async () => {
    calls++
    if (calls >= 2) controller.abort()
    await new Promise(resolve => setTimeout(resolve, 5))
    return new Response('x', { status: 200 })
  }
  const aborted = await warmChapterImages(Array.from({ length: 20 }, (_, i) => `k${i}`), {
    fetchImpl: aborting,
    signal: controller.signal,
  })
  assert.ok(aborted.warmed < aborted.total, '中断后必须提前收手')

  // ④ 空清单（0 张图）不是错误
  assert.deepEqual(await warmChapterImages([], { fetchImpl: okFetch }), { warmed: 0, total: 0 })
})

test('P3 - 离线快照：读不到就返回 null，读写都不抛', async () => {
  // 离线快照属于增强项：任何环境问题（无 IndexedDB / 配额满 / 隐私模式）都必须静默降级
  assert.equal(await getMangaManifest('src', 'ch-never-saved'), null, '没存过快照必须回 null')
  await putMangaManifest({
    sourceId: 'src', bookUrl: 'book', chapterUrl: 'ch-1', chapterIndex: 0,
    images: [{ index: 0, key: 'a'.repeat(64), status: 'ready' }],
  })
})

test('P3 - 快照主键必须单射（长度前缀，普通分隔符会撞）', () => {
  // 书源 ID 允许任意非空字符串 ⇒ `"${a}\u0000${b}"` 这种拼法会撞 key
  assert.notEqual(mangaManifestKey('a\u0000b', 'c'), mangaManifestKey('a', 'b\u0000c'))
  assert.notEqual(mangaManifestKey('src', 'ch-1'), mangaManifestKey('src', 'ch-2'))
  assert.notEqual(mangaManifestKey('src-a', 'ch'), mangaManifestKey('src', 'ach'))
  assert.equal(mangaManifestKey('src', 'ch-1'), mangaManifestKey('src', 'ch-1'))
})

test('P3 - 阅读器：清单失败时回退本地快照 + 明确横幅与缺图文案', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // ① 成功路径：存快照 + 整章预热（顺序必须在"有图"之后，空清单不该预热）
  const savedAfterLengthCheck = source.indexOf('putMangaManifest(') > source.indexOf('list.length === 0')
  assert.ok(savedAfterLengthCheck, '快照与预热必须在确认有图之后再做')
  assert.ok(source.includes('warmChapterImages(list.map(item => item.key)'), '必须整章预热（按清单 key 逐张）')

  // ② 失败路径：拿本地快照继续渲染
  assert.ok(
    /catch\(async \(err: unknown\)[\s\S]{0,400}?getMangaManifest\(/.test(source),
    '清单请求失败必须尝试本地快照（否则图在缓存里、界面却全白）',
  )
  assert.ok(source.includes('setOfflineSnapshot(true)'), '必须进入离线快照状态')

  // ③ 必须显式告知：横幅 + 缺图文案区分离线/上游
  assert.ok(source.includes('manga-offline-banner'), '必须有离线横幅')
  assert.ok(source.includes("t('manga.imageMissingOffline'"), '离线缺图必须给不同的文案')
  assert.ok(source.includes("t('manga.imageFailedHint'"), '上游失败文案仍要保留')
})

test('P3 - 进度：漫画复用同一套离线进度队列（不另起一条路）', () => {
  const screen = read('../src/MangaReaderScreen.tsx')
  const api = read('../src/api.ts')

  assert.ok(screen.includes('api.saveProgress('), '漫画进度必须走既有的 api.saveProgress')
  // 既有实现已经把进度写进本地队列，并在成功时 flush ⇒ 漫画天然获得"离线写队列、联网补传"
  assert.ok(api.includes('enqueueOfflineProgress(progressItem)'), 'api.saveProgress 必须写本地队列')
  assert.ok(api.includes('flushOfflineProgress('), '联网后必须补传队列')
  // 语义仍是"按图片序号"，不是像素偏移
  assert.ok(screen.includes('(imageIndex + 1) / imageTotal'), '进度语义必须是图片序号')
})

test('P3 - 本机"清空离线缓存"必须连带清掉漫画清单快照', () => {
  const offline = read('../src/offlineStorage.ts')

  assert.ok(offline.includes("from './mangaOffline'"), '本机离线模块必须接管漫画快照的清理')
  assert.ok(offline.includes('clearMangaManifests()'), '全部清空要带走漫画快照')
  assert.ok(offline.includes('clearMangaManifests(sourceId, bookUrl)'), '单书清理要带走该书的漫画快照')
})

test('P3 - 离线横幅样式同样只用主题变量', () => {
  const css = read('../src/styles.css')
  const start = css.indexOf('.manga-offline-banner')
  assert.ok(start > 0, '必须能找到离线横幅样式')
  const block = css.slice(start, start + 500)

  assert.ok(!/#[0-9a-fA-F]{3,8}\b/.test(block), '严禁十六进制颜色')
  assert.ok(!/\brgba?\(/.test(block), '严禁 rgb()/rgba() 颜色')
  assert.ok(block.includes('var(--surface-muted)') && block.includes('var(--line)'), '必须用主题变量')
})
