import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { AudiobookPlayerScreen, audiobookCacheProgressMarkup } from '../src/AudiobookPlayerScreen'
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

  for (const label of ['本章', '后 50 章', '后 100 章', '全本缓存', '自定义...']) {
    assert.ok(body.includes(label), `缓存底栏必须有「${label}」按钮`)
  }
  // 本仓库铁律：辅助按钮必须显式 type="button"（否则表单内会触发 submit）
  const rangeButtons = body.match(/<button[^>]*cache-range-btn[^>]*>/g) ?? []
  assert.equal(rangeButtons.length, 5, `缓存底栏必须正好 5 个缓存范围按钮（本章/后 50/后 100/全本/自定义，实测 ${rangeButtons.length}）`)
  for (const button of rangeButtons) {
    assert.ok(button.includes('type="button"'), `缓存按钮必须显式 type="button"：${button}`)
  }
  // 「下载到本设备」与「管理本设备离线缓存」：复用小说那套 class，各自显式 type="button"
  for (const cls of ['cache-sync-btn', 'cache-manage-btn']) {
    const button = body.match(new RegExp(`<button[^>]*${cls}[^>]*>`))?.[0]
    assert.ok(button, `必须复用 .${cls}`)
    assert.ok(button!.includes('type="button"'), `.${cls} 必须显式 type="button"`)
  }

  // 五个动作必须分别接到 requestCache(0) / (50) / (100) / 全本（整本 ⇒ 服务端 pinned 免死金牌）
  const source = read('../src/AudiobookPlayerScreen.tsx')
  for (const call of ['requestCache(0)', 'requestCache(50)', 'requestCache(100)', 'requestCache(chapters.length)']) {
    assert.ok(source.includes(call), `目录底栏必须保留缓存动作 ${call}`)
  }
  assert.ok(!source.includes("t('audiobook.cache'"), '底部功能条的缓存按钮必须已删除（requestCache 本身保留）')

  // 动作区（`</footer>` 之后）：加书架/已在书架复用 .shelf-button
  assert.equal((html.match(/drawer-footer-actions/g) ?? []).length, 1, '目录抽屉里必须恰好一个 drawer-footer-actions（两个 div 会变成两行、各占半格）')
  const actions = html.slice(html.indexOf('drawer-footer-actions'))
  const shelfButton = actions.match(/<button[^>]*shelf-button[^>]*>/)?.[0] ?? ''
  assert.ok(shelfButton.includes('type="button"'), '「加书架」必须显式 type="button"')
  assert.ok(shelfButton.includes('shelf-button'), '「加书架」必须复用 .shelf-button')
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

/**
 * 用户反馈「有声书和漫画目录下面的按钮还是不全」⇒ 目录底栏补齐到与小说一致：
 * 自定义范围 / 进度条 + 取消 / 下载到本设备 / 管理本设备离线缓存 / 加书架。
 */
test('audiobook - 目录底栏的五个缓存按钮逐一接线，自定义范围按范围发起缓存', () => {
  const source = read('../src/AudiobookPlayerScreen.tsx')
  const start = source.indexOf('<footer className="drawer-footer">')
  assert.ok(start >= 0, '目录抽屉必须有 drawer-footer')
  const footer = source.slice(start, source.indexOf('</footer>', start))

  // 顺序与漫画/小说那套一致：本章 → 后 50 章 → 后 100 章 → 全本 → 自定义
  const wiring = [
    { label: "t('audiobook.cacheCurrent'", title: "t('audiobook.cacheCurrentTitle'", call: 'requestCache(0)' },
    { label: "t('reader.cacheNext50'", title: "t('reader.cacheNext50Title'", call: 'requestCache(50)' },
    { label: "t('reader.cacheNext100'", title: "t('reader.cacheNext100Title'", call: 'requestCache(100)' },
    { label: "t('reader.cacheAll'", title: "t('audiobook.cacheAllTitle'", call: 'requestCache(chapters.length)' },
    { label: "t('reader.cacheCustom'", title: "t('reader.cacheCustomTitle'", call: 'setCustomRangeOpen' },
  ]
  const buttons = footer.match(/<button[\s\S]*?<\/button>/g) ?? []
  wiring.forEach((item, index) => {
    const button = buttons[index]
    assert.ok(button, `第 ${index + 1} 个缓存按钮必须存在`)
    assert.ok(button.includes(item.call), `第 ${index + 1} 个按钮必须调用 ${item.call}`)
    assert.ok(button.includes(item.label), `第 ${index + 1} 个按钮的文案必须用 ${item.label}`)
    assert.ok(button.includes(item.title), `第 ${index + 1} 个按钮的 title 必须用 ${item.title}`)
    assert.ok(button.includes('type="button"'), `第 ${index + 1} 个按钮必须显式 type="button"`)
  })

  // 自定义范围：复用小说的 class 与文案键，确认范围后按「第 X 至 Y 章」发起既有缓存调用
  assert.ok(footer.includes('cache-custom-range-box'), '自定义范围必须复用 .cache-custom-range-box')
  assert.ok(footer.includes('range-inputs'), '自定义范围必须复用 .range-inputs')
  assert.ok(footer.includes('primary-button range-start-btn'), '「开始缓存」必须复用 .primary-button.range-start-btn')
  for (const key of ['cacheCustomFrom', 'cacheCustomTo', 'cacheCustomUnit', 'startCache']) {
    assert.ok(footer.includes(`t('reader.${key}'`), `自定义范围必须复用 reader.${key}`)
  }
  assert.ok(
    footer.includes('requestCacheRange(customRangeStart, customRangeEnd)'),
    '「开始缓存」必须把「第 X 至 Y 章」交给 requestCacheRange（→ 既有 api.audioPrefetch）',
  )
  assert.ok(
    /const requestCacheRange[\s\S]*?requestCache\(to - from, from - 1\)/.test(source),
    '自定义范围必须落回既有的 requestCache（不新造请求路径）',
  )

  // 进行中反馈：沿用既有的 `caching`，不新造状态机；禁用而不是把抽屉关掉
  assert.equal(
    footer.split('disabled={caching}').length - 1,
    5,
    '缓存进行中必须让五个缓存按钮都置灰（沿用 caching 这一个既有状态）',
  )
  assert.ok(!footer.includes('setActiveDrawer'), '底栏按钮不得关闭抽屉（要保持目录可见，让绿点当场亮）')
})

test('audiobook - 进行中渲染出进度条与「取消缓存」，且取消只停任务不删缓存', () => {
  // ① 真的渲染"进行中"那一态：`renderToStaticMarkup` 不跑 effect ⇒ 组件初始态永远是空闲的，
  //    所以喂一个 running 状态给这个纯渲染函数（组件本身用的就是它）。
  const html = renderToStaticMarkup(
    audiobookCacheProgressMarkup({
      percent: 42,
      done: 21,
      planned: 50,
      serverCachingLabel: '正在服务端缓存',
      unitLabel: '章',
      cancelLabel: '取消缓存',
      onCancel: () => {},
    }),
  )
  assert.ok(html.includes('drawer-cache-progress'), '进行中必须复用 .drawer-cache-progress')
  assert.ok(html.includes('cache-progress-text'), '进行中必须有进度文案（.cache-progress-text）')
  assert.ok(html.includes('cache-progress-bar-track'), '进行中必须有进度条轨道（与小说同款 class）')
  assert.ok(html.includes('cache-progress-bar-fill'), '进行中必须有进度条填充（与小说同款 class）')
  assert.ok(html.includes('width:42%'), '进度条宽度必须来自 done/planned（实测缺少 42% 宽度）')
  assert.ok(html.includes('42% (21/50章)'), '进度文案必须是「x% (done/planned 章)」')
  const cancel = html.match(/<button[^>]*cache-cancel-btn[^>]*>/)?.[0] ?? ''
  assert.ok(cancel.includes('type="button"'), '「取消缓存」必须显式 type="button"')
  assert.ok(html.includes('取消缓存'), '「取消缓存」必须复用 reader.cancelCache 的文案')

  // ② 进度数据的来源：既有状态接口，且**复用既有轮询**（不新加定时器）
  const source = read('../src/AudiobookPlayerScreen.tsx')
  assert.ok(source.includes('api.audioPrefetchStatus()'), '进度必须来自既有状态接口 api.audioPrefetchStatus()')
  assert.equal(
    source.split('window.setInterval').length - 1,
    2,
    '不得新增轮询循环：状态刷新必须复用既有那两个定时器（缓存轮询 + 睡眠定时）',
  )

  // ③ 取消必须走**只取消不删缓存**的新端点，绝不能是会删缓存的 clearAudioCache（DELETE /audio/cache）
  assert.ok(source.includes('api.cancelAudioPrefetch('), '取消缓存必须调用新增的 cancelAudioPrefetch')
  assert.ok(!source.includes('api.clearAudioCache('), '取消缓存绝不能调用 clearAudioCache（那条 DELETE 会把已下好的音频一起删掉）')

  const apiSource = read('../src/api.ts')
  assert.ok(apiSource.includes('cancelAudioPrefetch: (sourceId: string, bookUrl: string)'), 'api.ts 必须新增 cancelAudioPrefetch 封装')
  assert.ok(apiSource.includes("'/api/books/audio/prefetch/cancel'"), 'cancelAudioPrefetch 必须打新增的 /books/audio/prefetch/cancel')
  assert.ok(
    /cancelAudioPrefetch[\s\S]{0,200}method: 'POST'/.test(apiSource),
    'cancelAudioPrefetch 必须是 POST（与既有预取路由同款写接口）',
  )

  // ④ 服务端：新路由必须存在、必须鉴权，且**不得**碰 audioCache（删缓存的语义）
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')
  const routeStart = routes.indexOf('post("/books/audio/prefetch/cancel")')
  assert.ok(routeStart >= 0, '服务端必须新增 POST /books/audio/prefetch/cancel')
  // 只取这一条路由的块（到下一个路由注册为止）——后面 `DELETE /audio/cache` 本来就该删缓存
  const nextRoute = routes.indexOf('get("/books/audio/cached-chapters")', routeStart)
  const block = routes.slice(routeStart, nextRoute > routeStart ? nextRoute : routeStart + 2000)
  assert.ok(block.includes('auth.requireSession(call, true)'), '取消路由必须与既有预取路由同款鉴权（写接口）')
  assert.ok(block.includes('audioPrefetch?.cancel(request.sourceId, bookUrl)'), '取消路由必须只调预取服务的 cancel')
  // 断言前先剥掉注释：这条路由的说明注释里**正写着**「绝不调用 audioCache.deleteBook」，
  // 带着注释断言会自伤（漫画那边实测踩过一次）。
  const blockCode = block.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(!blockCode.includes('audioCache.deleteBook'), '取消路由绝不能删音频缓存（那是 DELETE /audio/cache 的语义）')
  assert.ok(!blockCode.includes('audioCache.deleteKey'), '取消路由绝不能删音频分段')
})

test('audiobook - 下载到本设备 / 管理本设备离线缓存 / 加书架 三处都接上了既有能力', () => {
  const source = read('../src/AudiobookPlayerScreen.tsx')
  const html = renderToStaticMarkup(
    React.createElement(AudiobookPlayerScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )
  const drawerStart = html.indexOf('reader-drawer-left')
  const drawer = html.slice(drawerStart, html.indexOf('</aside>', drawerStart))
  const footerStart = source.indexOf('<footer className="drawer-footer">')
  const footer = source.slice(footerStart, source.indexOf('</footer>', footerStart))
  const drawerSource = source.slice(source.indexOf('reader-drawer-left'), source.indexOf('</aside>', source.indexOf('reader-drawer-left')))

  // ① 「下载到本设备」：复用 reader.downloadToDevice(+Title) 与 .cache-sync-btn
  assert.ok(footer.includes('cache-sync-btn'), '必须复用 .cache-sync-btn')
  assert.ok(footer.includes("t('reader.downloadToDevice'"), '必须复用 reader.downloadToDevice')
  assert.ok(footer.includes("t('reader.downloadToDeviceTitle'"), '必须复用 reader.downloadToDeviceTitle')
  assert.ok(footer.includes('onClick={downloadToDevice}'), '「下载到本设备」必须接线到 downloadToDevice')
  // 语义如实落地：音频的字节落在服务端缓存里 ⇒ 触发的是本书的既有缓存动作（覆盖到书末）
  assert.ok(
    /const downloadToDevice[\s\S]{0,900}requestCache\(chapters\.length, 0\)/.test(source),
    '「下载到本设备」必须落到既有的 requestCache（本仓库没有"只预热本机"的既有路径）',
  )

  // ② 「管理本设备离线缓存」：接同一个 OfflineCacheModal（小说用 setOfflineStatsModal(true) 打开）
  assert.ok(footer.includes('cache-manage-btn'), '必须复用 .cache-manage-btn')
  assert.ok(footer.includes("t('reader.manageOfflineCache'"), '必须复用 reader.manageOfflineCache')
  assert.ok(footer.includes('setOfflineStatsModal(true)'), '「管理」按钮必须打开离线缓存弹窗')
  assert.ok(source.includes("import { OfflineCacheModal } from './OfflineCacheModal'"), '必须复用小说同一个 OfflineCacheModal')
  assert.ok(source.includes('<OfflineCacheModal'), '必须真的挂载 OfflineCacheModal')
  assert.ok(/offlineStatsModal[\s\S]{0,600}<OfflineCacheModal/.test(source), 'OfflineCacheModal 必须由 offlineStatsModal 控制显隐')

  // ③ 「加书架 / 已在书架」：与小说**同一套** API（bookshelf 查在架 + add/remove 改）
  //   ⚠️ 动作区在 `</footer>` 之后，所以这里必须从整个目录抽屉里取按钮
  const shelfButton = drawerSource.match(/<button[^>]*shelf-button[\s\S]*?<\/button>/)?.[0] ?? ''
  assert.ok(shelfButton.includes('shelf-button'), '必须复用 .shelf-button')
  assert.ok(shelfButton.includes('type="button"'), '「加书架」必须显式 type="button"')
  assert.ok(drawerSource.includes("t('reader.inShelf'"), '在架文案必须复用 reader.inShelf')
  assert.ok(drawerSource.includes("t('reader.addToShelf'"), '加书架文案必须复用 reader.addToShelf')
  assert.ok(source.includes('api.addToBookshelf('), '加书架必须复用 api.addToBookshelf')
  assert.ok(source.includes('api.removeFromBookshelf('), '已在书架时再点必须复用 api.removeFromBookshelf')
  assert.ok(source.includes('api.bookshelf()'), '在架状态必须用 api.bookshelf() 查询（与小说同一套封装）')
  assert.ok(source.includes("t('shelf.removeFromShelfConfirm'"), '移出书架必须复用小说的确认文案')

  // 与小说阅读器用的是同一套 API（避免两边漂移）
  const novel = read('../src/ReaderScreen.tsx')
  for (const call of ['api.addToBookshelf(', 'api.removeFromBookshelf(', 'api.bookshelf()']) {
    assert.ok(novel.includes(call), `小说阅读器也必须用 ${call}（两端必须同源）`)
  }

  // ④ 换源存在时（集成分支）：换源与加书架必须在**同一个** drawer-footer-actions，顺序照小说（换源在前）
  assert.ok(drawer.includes('drawer-footer-actions'), '目录抽屉必须有 drawer-footer-actions')
  if (source.includes("t('reader.switchSource'")) {
    assert.equal(
      source.split('<div className="drawer-footer-actions">').length - 1,
      1,
      '换源与「加书架」必须合并进同一个 drawer-footer-actions（两个 div 会渲染成两行、各占左半格）',
    )
    const actions = source.slice(source.indexOf('<div className="drawer-footer-actions">'))
    assert.ok(
      actions.indexOf("t('reader.switchSource'") < actions.indexOf("t('reader.addToShelf'"),
      '顺序必须照小说：换源在前、加书架在后',
    )
  }
})

test('audiobook - 目录底栏新增按钮复用的文案键在四种语言里齐全、非空', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const required = {
    reader: [
      'cacheNext50', 'cacheNext50Title', 'cacheNext100', 'cacheNext100Title', 'cacheAll',
      // 本 PR 新增入口用到的键（**全部复用既有键，一个都没新造**）
      'cacheCustom', 'cacheCustomTitle', 'cacheCustomFrom', 'cacheCustomTo', 'cacheCustomUnit',
      'startCache', 'cancelCache', 'serverCaching',
      'downloadToDevice', 'downloadToDeviceTitle', 'manageOfflineCache',
      'inShelf', 'addToShelf', 'removedFromShelfToast', 'addedToShelfToast',
    ],
    audiobook: ['cacheCurrent', 'cacheCurrentTitle', 'cacheAllTitle'],
    shelf: ['removeFromShelfConfirm', 'toggleShelfFailed'],
  }

  for (const locale of locales) {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`)) as Record<string, Record<string, string>>
    for (const [namespace, keys] of Object.entries(required)) {
      assert.ok(data[namespace], `${locale} 必须包含 ${namespace} 命名空间`)
      for (const key of keys) {
        const value = data[namespace][key]
        assert.ok(
          typeof value === 'string' && value.trim().length > 0,
          `${locale}.${namespace}.${key} 必须存在且非空`,
        )
      }
    }
  }
})
