import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { AudiobookPlayerScreen } from '../src/AudiobookPlayerScreen'
import { api } from '../src/api'
import { audioManifestKey, warmChapterAudio } from '../src/audioOffline'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

const openBook = {
  details: {
    sourceId: 'https://audio.example.com',
    name: '测试有声书',
    author: '测试作者',
    tocUrl: 'https://audio.example.com/book/1',
    coverUrl: 'https://audio.example.com/cover.jpg',
  },
  bookUrl: 'https://audio.example.com/book/1',
  bookKind: 'audio',
  chapters: [
    { index: 0, title: '第 1 集', url: 'https://audio.example.com/c/1' },
    { index: 1, title: '第 2 集', url: 'https://audio.example.com/c/2' },
  ],
}

/**
 * 方案 G2 · 有声书播放器（P0–P3）。
 *
 * 这里断言的都是"**写错也不报错**"的那几类：
 * 1. SW 音频缓存规则必须与真实注册路由逐字对齐（封面与漫画各栽过一次死配置）；
 * 2. 播放器**绝不下发上游 URL**，只拼服务端直出地址（否则绕过缓存 ⇒ 防盗链/离线全失效）；
 * 3. 主题必须完全跟随主 UI（三套主题都要落到根节点，且只落一个）；
 * 4. 毫秒进度：音频走 `position_ms`，`scrollPosition` 固定 0（绝不混语义）；
 * 5. `audiobook.*` 的 locale 键在 4 种语言里必须齐全且一致（i18n 全量对齐）；
 * 6. 整章预热：并发受限、失败静默、可中断。
 */

test('audiobook - 渲染播放外壳（封面 / 进度 / 主播放键 / 底部功能条）', () => {
  const html = renderToStaticMarkup(
    React.createElement(AudiobookPlayerScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )

  assert.ok(html.includes('audiobook-workspace'), '必须渲染有声书工作面根节点')
  assert.ok(html.includes('audiobook-workspace theme-dark'), '根节点必须自带 theme-* class')
  assert.ok(html.includes('audiobook-cover-wrapper'), '封面是视觉主体（大圆角方形封面）')
  assert.ok(html.includes('audiobook-progress'), '必须有播放进度条')
  assert.ok(html.includes('audiobook-control main'), '必须有中心主播放键')
  assert.ok(html.includes('audiobook-bottom-controls'), '必须有底部功能条（倍速/定时/目录/设置）')
  // 缓存按钮已搬进目录抽屉 ⇒ 底部功能条只剩 4 个按钮，且不再出现缓存文案
  const bottom = html.match(/<footer class="audiobook-bottom-controls">([\s\S]*?)<\/footer>/)
  assert.ok(bottom, '底部功能条必须是可截取的 <footer class="audiobook-bottom-controls">')
  assert.equal((bottom![1].match(/<button/g) ?? []).length, 4, '底部功能条必须只剩 4 个按钮（倍速/定时/目录/设置）')
  assert.ok(!bottom![1].includes('缓存'), '底部功能条不得再出现缓存按钮（缓存入口已收敛进目录抽屉）')
  for (const label of ['倍速', '定时', '目录', '设置']) {
    assert.ok(bottom![1].includes(label), `底部功能条必须保留「${label}」按钮`)
  }
  // 参考稿的版式要点：信息区（章节标题 + 书名）
  assert.ok(html.includes('audiobook-episode-title'), '必须有章节标题')
  assert.ok(html.includes('audiobook-book-title'), '必须有书名/作者')
  // 目录抽屉复用虚拟章节列表与搜索（与文本/漫画阅读器同构）
  assert.ok(html.includes('chapter-search'), '目录抽屉必须带章节搜索')
  assert.ok(html.includes('reader-drawer'), '必须复用既有抽屉外壳')
})

test('audiobook - 目录抽屉底部必须带缓存动作底栏（与小说阅读器同构）', () => {
  const html = renderToStaticMarkup(
    React.createElement(AudiobookPlayerScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )

  assert.equal((html.match(/<footer class="drawer-footer">/g) ?? []).length, 1, '只应有目录抽屉一个 drawer-footer（不得在播放设置抽屉重复出现）')
  const footer = html.match(/<footer class="drawer-footer">([\s\S]*?)<\/footer>/)
  assert.ok(footer, '目录抽屉必须有 <footer class="drawer-footer"> 底栏（小说阅读器同款结构）')
  const body = footer![1]

  // 底栏必须挂在目录抽屉（reader-drawer-left）内部、且在章节列表之后
  const asideIndex = html.indexOf('reader-drawer-left')
  assert.ok(asideIndex >= 0 && asideIndex < html.indexOf('<footer class="drawer-footer">'), '画缓存底栏必须位于目录抽屉内部')
  assert.ok(html.indexOf('reader-chapters') < html.indexOf('<footer class="drawer-footer">'), '缓存底栏必须在章节列表之后（不遮挡目录滚动区）')

  for (const label of ['本章', '后 50 章', '后 100 章', '全本缓存']) {
    assert.ok(body.includes(label), `缓存底栏必须有「${label}」按钮`)
  }
  // 本仓库铁律：辅助按钮必须显式 type="button"（否则表单内会触发 submit）
  assert.equal((body.match(/<button/g) ?? []).length, 4, '缓存底栏必须正好 4 个按钮（本章/后 50 章/后 100 章/全本缓存）')
  assert.equal((body.match(/type="button"/g) ?? []).length, 4, '缓存底栏的每个按钮都必须显式声明 type="button"')

  // 四个动作必须分别接到 requestCache(0) / (50) / (100) / 全本（整本 ⇒ 服务端 pinned 免死金牌）
  const source = read('../src/AudiobookPlayerScreen.tsx')
  for (const call of ['requestCache(0)', 'requestCache(50)', 'requestCache(100)', 'requestCache(chapters.length)']) {
    assert.ok(source.includes(call), `目录底栏必须保留缓存动作 ${call}`)
  }
  assert.ok(!source.includes("t('audiobook.cache'"), '底部功能条的缓存按钮必须已删除（requestCache 本身保留）')
})

test('audiobook - 三套主题都能落到根节点（随主 UI 主题切换）', () => {
  for (const theme of ['light', 'paper', 'dark'] as const) {
    const html = renderToStaticMarkup(
      React.createElement(AudiobookPlayerScreen, { openBook, startIndex: 0, onClose: () => {}, theme }),
    )
    assert.ok(html.includes(`theme-${theme}`), `主题 ${theme} 必须落到播放器根节点`)
    for (const other of ['light', 'paper', 'dark'].filter(item => item !== theme)) {
      assert.ok(!html.includes(`theme-${other}`), `渲染 ${theme} 时不得出现 theme-${other}`)
    }
  }
})

test('audiobook - 严禁全局 Mini 播放条，也严禁在渲染期读 location', () => {
  const source = read('../src/AudiobookPlayerScreen.tsx')
  // Non-Goal：全局常驻 Mini 播放条会污染阅读/漫画用户
  assert.ok(!source.includes('MiniPlayer'), '严禁引入全局 Mini 播放条（Non-Goal）')
  assert.ok(!source.includes('createPortal'), '严禁用 createPortal 逃逸主题容器（会丢 CSS 变量）')
  // 静态渲染（Node 无 location）必须不炸
  assert.ok(!/^\s*(const|let)\s+\w+\s*=\s*location\./m.test(source), '渲染期不得直接读 location')
  // 播放元素只能指向服务端直出，绝不出现上游域名拼接
  assert.ok(source.includes('api.audioUrl('), 'src 必须走服务端直出 api.audioUrl(key)')
  assert.ok(!source.includes('upstreamUrl'), '前端不得接触上游 URL（服务端绝不下发）')
})

test('audiobook - SW 音频缓存规则与真实路由逐字对齐（防死配置）', () => {
  const vite = read('../vite.config.ts')
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')

  // 服务端真实注册：route("/api") + get("/audio/{key}") ⇒ /api/audio/<sha256>
  assert.ok(routes.includes('get("/audio/{key}")'), '服务端直出路由必须是 /audio/{key}（挂在 /api 下）')
  assert.ok(vite.includes('legado-audio'), '必须声明音频缓存名')
  // ⚠️ Workbox 的 RegExp 拿**完整 URL** 匹配，带 ^/ 锚点永不命中 ⇒ 必须用函数匹配 pathname
  assert.ok(vite.includes('url.pathname.startsWith('), 'SW 规则必须用函数匹配 url.pathname')
  assert.ok(!vite.includes('urlPattern: /^\\/api\\/'), '严禁用锚定路径的 RegExp 写 urlPattern（死配置）')

  const pattern = /^\/api\/audio\/[0-9a-f]{64}$/
  assert.ok(pattern.test(`/api/audio/${'a'.repeat(64)}`), '必须命中真实音频地址')
  for (const wrong of ['/api/books/audio', '/api/books/audio/cache', '/api/audio/cache/stats', `/api/audio/${'a'.repeat(63)}`]) {
    assert.ok(!pattern.test(wrong), `${wrong} 不是音频字节本身，绝不能被音频缓存规则吃掉`)
  }
  // ⚠️ 前缀式匹配会把 `/api/audio/cache/stats` 也吞进 CacheFirst ⇒ 缓存水位永远停在第一次的结果
  assert.ok(
    !vite.includes("url.pathname.startsWith('/api/audio/')"),
    '音频 SW 规则必须用精确的 64 位 hex 正则，不能用前缀匹配（会连统计接口一起缓存）',
  )
  // 方案原稿里的 /api/books/audio/... 是**清单**接口（POST）——照抄就是永不命中的死配置
  assert.ok(!vite.includes("urlPattern: /^\\/api\\/books\\/audio\\//"), '严禁把清单接口当成音频直出规则')
})

test('audiobook - 毫秒进度：audioUrl 契约 + position_ms 语义不混用', () => {
  assert.equal(api.audioUrl('a'.repeat(64)), `/api/audio/${'a'.repeat(64)}`)

  const apiSource = read('../src/api.ts')
  assert.ok(apiSource.includes("'/api/books/audio'"), '清单接口路径必须是 /api/books/audio')
  assert.ok(apiSource.includes('positionMs'), '进度提交必须带毫秒位置')

  const player = read('../src/AudiobookPlayerScreen.tsx')
  // `scrollPosition` 对音频固定 0：毫秒是唯一权威（服务端 0..1 校验不动，毫秒走 position_ms）
  assert.ok(/target\.index,\s*0,\s*target\.title,\s*Math\.max\(0, Math\.round\(positionMs\)\)/.test(player), '音频的 scrollPosition 必须固定 0，毫秒走 positionMs')

  const models = read('../../server/src/main/kotlin/io/legado/server/Models.kt')
  assert.ok(models.includes('val positionMs: Long? = null'), '服务端 ReadingProgress 必须有可空 position_ms 字段')
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')
  assert.ok(routes.includes('progress.positionMs != null && progress.positionMs < 0'), '服务端必须校验毫秒非负（且不动 0..1 校验）')
  assert.ok(routes.includes('positionMs = saved.positionMs'), '毫秒进度必须镜像到手机端进度文件（durChapterPos）')
})

test('audiobook - 4 种语言的 audiobook 键必须齐全且完全一致', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const keySets = locales.map(locale => {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`)) as { audiobook?: Record<string, string> }
    assert.ok(data.audiobook, `${locale} 必须包含 audiobook 命名空间`)
    const entries = Object.entries(data.audiobook!)
    assert.ok(entries.length >= 40, `${locale} 的 audiobook 键数量异常（${entries.length}）`)
    for (const [key, value] of entries) {
      assert.ok(typeof value === 'string' && value.trim().length > 0, `${locale}.audiobook.${key} 不能为空`)
    }
    return Object.keys(data.audiobook!).sort().join(',')
  })
  for (const set of keySets.slice(1)) {
    assert.equal(set, keySets[0], '4 种语言的 audiobook 键必须完全一致（i18n 全量对齐测试会强制）')
  }
})

test('audiobook - 离线清单快照键是单射的（长度前缀）', () => {
  // `${a}\u0000${b}` 不是单射：(a\0b, c) 与 (a, b\0c) 会撞成同一个 key
  assert.notEqual(audioManifestKey('a\u0000b', 'c'), audioManifestKey('a', 'b\u0000c'))
  assert.equal(audioManifestKey('src', 'chap'), '3:srcchap')
})

test('audiobook - 整章预热：并发受限（≤2）、失败静默、可中断', async () => {
  let inFlight = 0
  let maxInFlight = 0
  const okFetch: typeof fetch = async () => {
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    await new Promise(resolve => setTimeout(resolve, 5))
    inFlight--
    return new Response('x', { status: 200 })
  }
  const ok = await warmChapterAudio(['a', 'b', 'c', 'd', 'e'], { fetchImpl: okFetch })
  assert.deepEqual(ok, { warmed: 5, total: 5 })
  assert.ok(maxInFlight <= 2, `音频预热并发必须 ≤2（实测 ${maxInFlight}）—— 音频是大字节，并发过高会把前台播放拖死`)

  // 失败一律吞掉（上游 403/404、离线都是常态）
  const failing: typeof fetch = async () => { throw new Error('offline') }
  assert.deepEqual(await warmChapterAudio(['a', 'b'], { fetchImpl: failing }), { warmed: 0, total: 2 })

  // 非 200（例如防盗链 403）不算预热成功
  const forbidden: typeof fetch = async () => new Response('nope', { status: 403 })
  assert.deepEqual(await warmChapterAudio(['a'], { fetchImpl: forbidden }), { warmed: 0, total: 1 })

  // 中断后立刻收手
  const controller = new AbortController()
  controller.abort()
  assert.deepEqual(await warmChapterAudio(['a', 'b', 'c'], { fetchImpl: okFetch, signal: controller.signal }), { warmed: 0, total: 3 })

  // 没有 key 时不做任何网络请求
  assert.deepEqual(await warmChapterAudio([], { fetchImpl: okFetch }), { warmed: 0, total: 0 })
})
