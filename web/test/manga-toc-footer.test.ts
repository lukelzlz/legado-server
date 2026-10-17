import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { MangaReaderScreen, mangaCacheProgressMarkup } from '../src/MangaReaderScreen'

/**
 * 漫画「目录缓存栏」（用户原话：「在漫画的目录界面添上缓存栏，缓存到浏览器，还有换源什么的，
 * 像小说的那个一样」；后续反馈：「有声书和漫画目录下面的按钮还是不全」）。
 *
 * 本文件锁住「目录底栏与小说对齐」这件事：
 * ① 底栏在**目录抽屉**里、章节列表**之后**，五个缓存按钮全部显式 type="button"；
 * ② 五个按钮与「本章 / 后 50 章 / 后 100 章 / 全本 / 自定义…」逐一接线（源码级断言，
 *    因为 renderToStaticMarkup 拿不到 onClick）；
 * ③ **进行中**那一态真的能渲染出进度条与「取消缓存」，且取消走的是**只取消不删缓存**的新端点
 *    （`/api/books/images/prefetch/cancel`），**绝不能**是 `clearImageCache`（那条会删缓存）；
 * ④ 「下载到本设备」「管理本设备离线缓存」「加书架」三格都在，并复用小说的 class 与文案键；
 * ⑤ 设置抽屉里那一节**搬走了**（不是复制）；绿点图例仍在（"哪些章可离线读"的唯一解释）；
 * ⑥ 用到的文案键在四种语言里都存在且非空。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

const openBook = {
  details: {
    sourceId: 'https://manga.example.com',
    name: '测试漫画',
    tocUrl: 'https://manga.example.com/book/1',
  },
  bookUrl: 'https://manga.example.com/book/1',
  bookKind: 'comic',
  chapters: [
    { index: 0, title: '第 1 话', url: 'https://manga.example.com/c/1' },
    { index: 1, title: '第 2 话', url: 'https://manga.example.com/c/2' },
  ],
}

const render = () => renderToStaticMarkup(
  React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
)

/** 从 `marker` 起截到其后第一个 `</aside>`：漫画的抽屉内部不会再嵌套 aside。 */
function asideSegment(text: string, marker: string): string {
  const start = text.indexOf(marker)
  assert.ok(start >= 0, `必须存在 ${marker}`)
  const end = text.indexOf('</aside>', start)
  assert.ok(end > start, `${marker} 之后必须有闭合的 </aside>`)
  return text.slice(start, end)
}

/** 底栏片段（`<footer class="drawer-footer">` … `</footer>`）。 */
function footerSegment(text: string): string {
  const start = text.indexOf('<footer className="drawer-footer">') >= 0
    ? text.indexOf('<footer className="drawer-footer">')
    : text.indexOf('<footer class="drawer-footer">')
  assert.ok(start >= 0, '必须存在 <footer class="drawer-footer">')
  const end = text.indexOf('</footer>', start)
  assert.ok(end > start, '底栏必须有闭合的 </footer>')
  return text.slice(start, end + '</footer>'.length)
}

const FOOTER_CLASS = 'class="drawer-footer"'
const ACTIONS_OPEN = '<div className="drawer-footer-actions">'
const SOURCE_SWITCH_KEY = "t('reader.switchSource'"
const ADD_TO_SHELF_KEY = "t('reader.addToShelf'"

test('漫画目录底栏 - 恰好一个 drawer-footer，位于章节列表之后，五个缓存按钮 + 两个本机动作', () => {
  const html = render()
  const leftDrawer = asideSegment(html, 'reader-drawer-left')

  // ① 恰好一个（不是"至少一个"）：多了就是复制了一份底栏
  assert.equal(leftDrawer.split(FOOTER_CLASS).length - 1, 1, '目录抽屉里必须恰好有一个 .drawer-footer')
  // ② 位于章节列表之后（列表在上、动作在下，才是"底栏"）
  const listAt = leftDrawer.indexOf('reader-chapters')
  const footerAt = leftDrawer.indexOf(FOOTER_CLASS)
  assert.ok(listAt >= 0, '目录抽屉必须复用 VirtualChapterList（reader-chapters）')
  assert.ok(listAt < footerAt, '底栏必须在章节列表**之后**')
  // 底栏应"钉"在抽屉底部：其后最多只允许 footer-actions（换源 + 加书架）与收尾标签。
  const afterFooter = leftDrawer.slice(leftDrawer.indexOf('</footer>', footerAt) + '</footer>'.length)
  assert.ok(!afterFooter.includes('reader-chapters'), '底栏之后不能再出现章节列表（否则就不是固定在底部的那一栏）')
  assert.ok(
    afterFooter.trim().length === 0 || afterFooter.includes('drawer-footer-actions'),
    '底栏之后只允许跟 drawer-footer-actions（换源 + 加书架）再收尾',
  )
  assert.ok(!/<button[^>]*cache-range-btn/.test(afterFooter), '底栏之后不能还有缓存按钮（缓存按钮必须都在底栏里）')

  // ③ 五个缓存按钮，全部显式 type="button"（仓库铁律：不写会触发 form 默认 submit）
  const footer = footerSegment(html)
  const rangeButtons = footer.match(/<button[^>]*cache-range-btn[^>]*>/g) ?? []
  assert.equal(rangeButtons.length, 5, `底栏必须有五个缓存按钮（本章/后50/后100/全本/自定义，实测 ${rangeButtons.length}）`)
  for (const button of rangeButtons) {
    assert.ok(button.includes('type="button"'), `缓存按钮必须显式 type="button"：${button}`)
    assert.ok(button.includes('cache-range-btn'), `缓存按钮必须复用 .cache-range-btn：${button}`)
  }

  // ④ 「下载到本设备」与「管理本设备离线缓存」：复用小说那套 class（各自显式 type="button"）
  const syncButton = footer.match(/<button[^>]*cache-sync-btn[^>]*>/)?.[0] ?? ''
  assert.ok(syncButton.includes('type="button"'), '「下载到本设备」必须显式 type="button"')
  const manageButton = footer.match(/<button[^>]*cache-manage-btn[^>]*>/)?.[0] ?? ''
  assert.ok(manageButton.includes('type="button"'), '「管理本设备离线缓存」必须显式 type="button"')

  // ⑤ 全页只有这五个 .cache-range-btn：设置抽屉里那一份是**搬走**而不是复制
  const pageWide = html.match(/<button[^>]*cache-range-btn[^>]*>/g) ?? []
  assert.equal(pageWide.length, 5, `全页只允许这五个缓存按钮（实测 ${pageWide.length}）`)

  // ⑥ 底栏动作区：加书架/已在书架复用 .shelf-button（换源由另一个 PR 加在同一锚点）
  assert.equal(leftDrawer.split('drawer-footer-actions').length - 1, 1, '目录抽屉里必须恰好一个 drawer-footer-actions（两个 div 会变成两行、各占半格）')
  const actions = leftDrawer.slice(leftDrawer.indexOf('drawer-footer-actions'))
  const shelfButton = actions.match(/<button[^>]*shelf-button[^>]*>/)?.[0] ?? ''
  assert.ok(shelfButton.includes('type="button"'), '「加书架」必须显式 type="button"')
  assert.ok(shelfButton.includes('shelf-button'), '「加书架」必须复用 .shelf-button')
})

test('漫画目录底栏 - 五个按钮逐一接线，自定义范围按范围发起缓存', () => {
  const source = read('../src/MangaReaderScreen.tsx')
  const footer = footerSegment(asideSegment(source, 'reader-drawer-left'))

  const buttons = footer.match(/<button[\s\S]*?<\/button>/g) ?? []
  // 顺序与有声书/小说那套一致：本章 → 后 50 章 → 后 100 章 → 全本 → 自定义
  const wiring = [
    { label: "t('manga.offlineCurrent'", title: "t('manga.offlineCurrentTitle'", call: 'requestOffline(0)' },
    { label: "t('reader.cacheNext50'", title: "t('reader.cacheNext50Title'", call: 'requestOffline(50)' },
    { label: "t('reader.cacheNext100'", title: "t('reader.cacheNext100Title'", call: 'requestOffline(100)' },
    { label: "t('reader.cacheAll'", title: "t('manga.offlineAllTitle'", call: 'requestOffline(chapters.length)' },
    { label: "t('reader.cacheCustom'", title: "t('reader.cacheCustomTitle'", call: 'setCustomRangeOpen' },
  ]
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
    footer.includes('requestOfflineRange(customRangeStart, customRangeEnd)'),
    '「开始缓存」必须把「第 X 至 Y 章」交给 requestOfflineRange（→ 既有 api.mangaPrefetch）',
  )
  assert.ok(
    /const requestOfflineRange[\s\S]*?requestOffline\(to - from, from - 1\)/.test(read('../src/MangaReaderScreen.tsx')),
    '自定义范围必须落回既有的 requestOffline（不新造请求路径）',
  )

  // 「整本」必须是 chapters.length（= 覆盖到书末 ⇒ requestOffline 里判定为 whole ⇒ pin 免死金牌）
  assert.ok(footer.includes('requestOffline(chapters.length)'), '全本缓存必须传 chapters.length')

  // 进行中反馈：沿用既有的 offlineRunning，不新造状态机；禁用而不是把抽屉关掉
  assert.equal(
    footer.split('disabled={offlineRunning}').length - 1,
    5,
    '缓存进行中必须让五个缓存按钮都置灰（沿用 offlineRunning 这一个既有状态）',
  )
  // 点击**不关**抽屉：否则绿点当场看不见，等于把缓存做成了盲盒
  assert.ok(!footer.includes('setActiveDrawer'), '底栏按钮不得关闭抽屉（要保持目录可见，让绿点当场亮）')
})

test('漫画目录底栏 - 进行中渲染出进度条与「取消缓存」，且取消只停任务不删缓存', () => {
  // ① 真的渲染"进行中"那一态：`renderToStaticMarkup` 不跑 effect ⇒ 组件初始态永远是空闲的，
  //    所以喂一个 running 状态给这个纯渲染函数（组件本身用的就是它）。
  let cancelled = 0
  const html = renderToStaticMarkup(
    mangaCacheProgressMarkup({
      percent: 42,
      done: 21,
      planned: 50,
      serverCachingLabel: '正在服务端缓存',
      unitLabel: '章',
      cancelLabel: '取消缓存',
      onCancel: () => { cancelled += 1 },
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
  const source = read('../src/MangaReaderScreen.tsx')
  assert.ok(source.includes('api.mangaPrefetchStatus()'), '进度必须来自既有状态接口 api.mangaPrefetchStatus()')
  assert.equal(
    source.split('window.setInterval').length - 1,
    1,
    '不得新增轮询循环：状态刷新必须复用既有的那一个定时器',
  )
  assert.ok(!/setPrefetchProgress[\s\S]{0,80}setInterval/.test(source), '进度回填不得自带轮询')

  // ③ 取消必须走**只取消不删缓存**的新端点，绝不能是会删缓存的 clearImageCache（DELETE /books/images/cache）
  assert.ok(source.includes('api.cancelMangaPrefetch('), '取消缓存必须调用新增的 cancelMangaPrefetch')
  // clearImageCache（DELETE /books/images/cache）会连已下好的图一起删，只允许出现在"换源清理旧源"那段；
  // 取消缓存必须走只取消不删缓存的新端点 —— 所以断言要收敛到 cancelOffline 内部，而不是全文件扫。
  const cancelStart = source.indexOf('const cancelOffline = useCallback(')
  assert.ok(cancelStart >= 0, '必须存在 cancelOffline 处理器')
  const cancelHandler = source.slice(cancelStart, source.indexOf('}, [', cancelStart) + 4)
  assert.ok(cancelHandler.includes('api.cancelMangaPrefetch('), 'cancelOffline 必须调用 cancelMangaPrefetch')
  assert.ok(!cancelHandler.includes('clearImageCache'), 'cancelOffline 内绝不能出现 clearImageCache（会删掉已下好的图）')
  for (const hit of source.matchAll(/api\.clearImageCache\(/g)) {
    const around = source.slice(Math.max(0, (hit.index ?? 0) - 500), hit.index ?? 0)
    assert.ok(/switchBookSource|clearMangaManifests|换源/.test(around), 'clearImageCache 只允许用于换源时清理旧源')
  }

  const apiSource = read('../src/api.ts')
  assert.ok(apiSource.includes("cancelMangaPrefetch: (sourceId: string, bookUrl: string)"), 'api.ts 必须新增 cancelMangaPrefetch 封装')
  assert.ok(apiSource.includes("'/api/books/images/prefetch/cancel'"), 'cancelMangaPrefetch 必须打新增的 /books/images/prefetch/cancel')
  assert.ok(
    /cancelMangaPrefetch[\s\S]{0,200}method: 'POST'/.test(apiSource),
    'cancelMangaPrefetch 必须是 POST（与既有预取路由同款写接口）',
  )

  // ④ 服务端：新路由必须存在、必须鉴权，且**不得**碰 imageCache（删缓存的语义）
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')
  const start = routes.indexOf('post("/books/images/prefetch/cancel")')
  assert.ok(start >= 0, '服务端必须新增 POST /books/images/prefetch/cancel')
  // 只取这一条路由的块（到下一个路由注册为止）——后面还有 `delete("/books/images/cache")`，
  // 它**本来就该**删缓存，不能把它的语句算进本条路由
  const nextRoute = routes.indexOf('put("/books/images/cache/pin")', start)
  const block = routes.slice(start, nextRoute > start ? nextRoute : start + 2000)
  assert.ok(block.includes('auth.requireSession(call, true)'), '取消路由必须与既有预取路由同款鉴权（写接口）')
  assert.ok(block.includes('mangaPrefetch?.cancel(request.sourceId, bookUrl)'), '取消路由必须只调预取服务的 cancel')
  // 断言前先剥掉注释：这条路由的说明注释里**正写着**「绝不调用 imageCache.deleteBook」（形如
  // `// ⚠️ 绝不调用 imageCache.deleteBook …`），带着注释断言会自伤（实测踩过一次）。
  const blockCode = block.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(!blockCode.includes('imageCache.deleteBook'), '取消路由绝不能删图片缓存（那是 DELETE /books/images/cache 的语义）')
  assert.ok(!blockCode.includes('deleteMangaChapters'), '取消路由绝不能删清单（否则下次打开是空清单）')
})

test('漫画目录底栏 - 下载到本设备 / 管理本设备离线缓存 / 加书架 三处都接上了既有能力', () => {
  const source = read('../src/MangaReaderScreen.tsx')
  const html = render()
  const leftDrawerSource = asideSegment(source, 'reader-drawer-left')

  // ① 「下载到本设备」：复用 reader.downloadToDevice(+Title) 与 .cache-sync-btn
  const footer = footerSegment(leftDrawerSource)
  assert.ok(footer.includes('cache-sync-btn'), '必须复用 .cache-sync-btn')
  assert.ok(footer.includes("t('reader.downloadToDevice'"), '必须复用 reader.downloadToDevice')
  assert.ok(footer.includes("t('reader.downloadToDeviceTitle'"), '必须复用 reader.downloadToDeviceTitle')
  assert.ok(footer.includes('onClick={downloadToDevice}'), '「下载到本设备」必须接线到 downloadToDevice')
  // 语义如实落地：漫画的字节落在服务端缓存里 ⇒ 触发的是本书的既有缓存动作（覆盖到书末）
  assert.ok(
    /const downloadToDevice[\s\S]{0,700}requestOffline\(chapters\.length, 0\)/.test(source),
    '「下载到本设备」必须落到既有的 requestOffline（漫画没有"只预热本机"的既有路径）',
  )

  // ② 「管理本设备离线缓存」：接同一个 OfflineCacheModal（小说用 setOfflineStatsModal(true) 打开）
  assert.ok(footer.includes('cache-manage-btn'), '必须复用 .cache-manage-btn')
  assert.ok(footer.includes("t('reader.manageOfflineCache'"), '必须复用 reader.manageOfflineCache')
  assert.ok(footer.includes('setOfflineStatsModal(true)'), '「管理」按钮必须打开离线缓存弹窗')
  assert.ok(source.includes("import { OfflineCacheModal } from './OfflineCacheModal'"), '必须复用小说同一个 OfflineCacheModal')
  assert.ok(source.includes('<OfflineCacheModal'), '必须真的挂载 OfflineCacheModal')
  assert.ok(/offlineStatsModal[\s\S]{0,400}<OfflineCacheModal/.test(source), 'OfflineCacheModal 必须由 offlineStatsModal 控制显隐')

  // ③ 「加书架 / 已在书架」：与小说**同一套** API（bookshelf 查在架 + add/remove 改）
  //   ⚠️ 动作区在 `</footer>` **之后**，所以这里必须从整个目录抽屉里取按钮，不能只取底栏片段
  const shelfButton = leftDrawerSource.match(/<button[^>]*shelf-button[\s\S]*?<\/button>/)?.[0] ?? ''
  assert.ok(shelfButton.includes('shelf-button'), '必须复用 .shelf-button')
  assert.ok(shelfButton.includes('type="button"'), '「加书架」必须显式 type="button"')
  assert.ok(leftDrawerSource.includes("t('reader.inShelf'"), '在架文案必须复用 reader.inShelf')
  assert.ok(leftDrawerSource.includes("t('reader.addToShelf'"), '加书架文案必须复用 reader.addToShelf')
  assert.ok(source.includes('api.addToBookshelf('), '加书架必须复用 api.addToBookshelf')
  assert.ok(source.includes('api.removeFromBookshelf('), '已在书架时再点必须复用 api.removeFromBookshelf')
  assert.ok(source.includes('api.bookshelf()'), '在架状态必须用 api.bookshelf() 查询（与小说同一套封装）')
  assert.ok(source.includes("t('shelf.removeFromShelfConfirm'"), '移出书架必须复用小说的确认文案')

  // 与小说阅读器用的是同一套 API / 同一批文案键（避免两边漂移）
  const novel = read('../src/ReaderScreen.tsx')
  for (const call of ['api.addToBookshelf(', 'api.removeFromBookshelf(', 'api.bookshelf()']) {
    assert.ok(novel.includes(call), `小说阅读器也必须用 ${call}（两端必须同源）`)
  }

  // ④ 换源存在时（集成分支）：换源与加书架必须在**同一个** drawer-footer-actions，顺序照小说（换源在前）
  const actionsStart = html.indexOf('drawer-footer-actions')
  assert.ok(actionsStart >= 0, '目录抽屉必须有 drawer-footer-actions')
  if (source.includes(SOURCE_SWITCH_KEY)) {
    const actions = source.slice(source.indexOf(ACTIONS_OPEN))
    assert.equal(
      source.split(ACTIONS_OPEN).length - 1,
      1,
      '换源与「加书架」必须合并进同一个 drawer-footer-actions（两个 div 会渲染成两行、各占左半格）',
    )
    assert.ok(actions.includes(SOURCE_SWITCH_KEY), '换源必须与加书架同处一个 drawer-footer-actions')
    assert.ok(actions.includes(ADD_TO_SHELF_KEY), '加书架必须与换源同处一个 drawer-footer-actions')
    assert.ok(
      actions.indexOf(SOURCE_SWITCH_KEY) < actions.indexOf(ADD_TO_SHELF_KEY),
      '顺序必须照小说：换源在前、加书架在后',
    )
  }
})

test('漫画目录底栏 - 离线缓存那一节已从设置抽屉**搬走**（不是复制）', () => {
  const html = render()
  const settingsDrawer = asideSegment(html, 'reader-drawer-right')

  for (const gone of ['cache-range-btn', 'manga.offlineCurrent', 'offlineCache', '绿点']) {
    assert.ok(!settingsDrawer.includes(gone), `设置抽屉里不该再有离线缓存那一节（残留 "${gone}"）`)
  }
  assert.ok(!settingsDrawer.includes(FOOTER_CLASS), '设置抽屉里不该有底栏')

  // 源码级同样断言：避免只是 defaultProps 之类把文案藏起来
  const source = asideSegment(read('../src/MangaReaderScreen.tsx'), 'reader-drawer-right')
  for (const gone of ['cache-range-btn', 'requestOffline', 'manga.offlineCache', 'manga.offlineHint']) {
    assert.ok(!source.includes(gone), `设置抽屉源码里不该再有 "${gone}"`)
  }
})

test('漫画目录底栏 - 绿点图例（已离线 N 章 · 绿点）仍在页面上渲染得到', () => {
  const html = render()
  const leftDrawer = asideSegment(html, 'reader-drawer-left')
  const footer = leftDrawer.slice(leftDrawer.indexOf(FOOTER_CLASS))

  // 绿点图例是"整章可离线阅读"的唯一解释：补齐按钮的过程中绝不能弄丢
  assert.ok(footer.includes('绿点'), '底栏必须保留绿点图例（已离线 N 章 · 绿点表示整章可离线阅读）')
  assert.ok(footer.includes('已离线 0 章'), '空闲态必须显示已离线章数（初始 0 章）')

  // 运行态与空闲态两种文案都还在（沿用既有键，不新造）
  const source = footerSegment(asideSegment(read('../src/MangaReaderScreen.tsx'), 'reader-drawer-left'))
  assert.ok(source.includes("t('manga.offlineHint'"), '必须保留 offlineHint（空闲态图例）')
  assert.ok(source.includes("t('manga.offlineRunning'"), '必须保留 offlineRunning（运行态提示）')
})

test('漫画目录底栏 - 新增按钮复用的文案键在四种语言里齐全、非空', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const required = {
    manga: ['offlineCurrent', 'offlineCurrentTitle', 'offlineAllTitle', 'offlineHint', 'offlineRunning'],
    reader: [
      'cacheNext50', 'cacheNext50Title', 'cacheNext100', 'cacheNext100Title', 'cacheAll',
      // 本 PR 新增入口用到的键（**全部复用既有键，一个都没新造**）
      'cacheCustom', 'cacheCustomTitle', 'cacheCustomFrom', 'cacheCustomTo', 'cacheCustomUnit',
      'startCache', 'cancelCache', 'serverCaching',
      'downloadToDevice', 'downloadToDeviceTitle', 'manageOfflineCache',
      'inShelf', 'addToShelf', 'removedFromShelfToast', 'addedToShelfToast',
    ],
    shelf: ['removeFromShelfConfirm', 'toggleShelfFailed'],
  }

  const shapes = locales.map(locale => {
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
    // 底栏文案键在四语言里的集合必须一致（i18n 全量对齐测试会强制，这里提前锁住）
    return Object.keys(data.manga).sort().join(',')
  })
  for (const shape of shapes.slice(1)) {
    assert.equal(shape, shapes[0], '四种语言的 manga 键集合必须完全一致')
  }

  // 插值占位符必须留着，否则"已离线 N 章"会退化成没有数字的一句话
  for (const locale of locales) {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`)) as Record<string, Record<string, string>>
    assert.ok(data.manga.offlineHint.includes('{{count}}'), `${locale}.manga.offlineHint 必须保留 {{count}} 插值`)
  }
})
