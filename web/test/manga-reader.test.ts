import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { MangaReaderScreen } from '../src/MangaReaderScreen'
import { api } from '../src/api'

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

test('MangaReaderScreen - 渲染纵向阅读外壳与章节导航', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )

  // 纵向瀑布容器 + 书架工作面（复用既有 reader 主题/safe-area）
  assert.ok(html.includes('manga-scroll'), '必须渲染纵向滚动容器')
  assert.ok(html.includes('reader-workspace'), '必须复用既有 reader-workspace 外壳')
  // 根节点自带主题 class（与 ReaderScreen 同约定），不靠外层 app-shell 兜底
  assert.ok(html.includes('reader-workspace manga-workspace theme-dark'), '根节点必须自带 theme-* class')
  // 章节导航（上一章 / 下一章 / 滑块）
  assert.ok(html.includes('reader-chapter-control-bar'), '必须渲染章节控制条')
  assert.ok(html.includes('reader-chapter-slider'), '必须渲染章节滑块')
  // 目录抽屉复用虚拟章节列表
  assert.ok(html.includes('chapter-search'), '目录抽屉必须带章节搜索')
})

test('MangaReaderScreen - 三种主题都能落到根节点（随主 UI 主题切换）', () => {
  for (const theme of ['light', 'paper', 'dark'] as const) {
    const html = renderToStaticMarkup(
      React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme }),
    )
    assert.ok(html.includes(`theme-${theme}`), `主题 ${theme} 必须落到漫画阅读器根节点`)
    // 只有一个主题 class，绝不允许把三种一起写上（否则样式优先级会打架）
    for (const other of ['light', 'paper', 'dark'].filter(item => item !== theme)) {
      assert.ok(!html.includes(`theme-${other}`), `渲染 ${theme} 时不得出现 theme-${other}`)
    }
  }
})

test('漫画阅读器 - 严禁 createPortal 逃逸主题容器', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // 本项目主题靠 .app-shell.theme-* 上的 CSS 变量下发；portal 到 body 会脱离该容器，
  // 在夜间模式下表现为「透光背景 + 隐形黑字」（AGENTS.md 记过这条）。
  assert.ok(!source.includes('createPortal'), '漫画阅读器不得用 createPortal 挂到 body')
})

test('MangaReaderScreen - 漫画刻意不提供 TTS 与替换净化', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // 检查真正的「接线」而不是文案：注释里出现 TTS 字样是允许的（那是说明为什么不接）。
  for (const forbidden of ['./ttsEngine', 'TtsPlayerBar', 'toggleTts', 'playTts', 'ttsActive', 'replaceRules', 'paginate']) {
    assert.ok(!source.includes(forbidden), `漫画阅读器不得接线 ${forbidden}`)
  }
})

test('MangaReaderScreen - 单张图片失败必须显式提示且可重试', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(source.includes('onError'), '必须有 onError 兜底')
  assert.ok(source.includes('manga-image-failed'), '失败图必须渲染占位与重试入口')
  assert.ok(source.includes('emptyManifest'), '空清单必须给明确文案，绝不静默留白')
})

test('MangaReaderScreen - 进度语义按图片序号而非像素偏移', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(
    source.includes('(imageIndex + 1) / imageTotal'),
    'scrollPosition 必须是 (图片序号 + 1) / 总张数',
  )
  assert.ok(!/scrollTop\s*\/\s*scrollHeight/.test(source), '严禁用像素偏移充当进度（懒加载会让高度漂移）')
})

test('api client - 暴露 mangaManifest 且书架条目带 bookKind', () => {
  assert.equal(typeof api.mangaManifest, 'function', 'api.mangaManifest 必须存在')

  const apiSource = read('../src/api.ts')
  assert.ok(apiSource.includes("'/api/books/images/manifest'"), '必须指向真实的清单路由')
  assert.ok(/BookshelfItem = \{[^}]*bookKind/.test(apiSource), 'BookshelfItem 必须透出 bookKind')
})

test('P1 契约 - 清单只给服务端 key，图片走 imageUrl，绝不泄露上游地址', () => {
  const apiSource = read('../src/api.ts')
  const screenSource = read('../src/MangaReaderScreen.tsx')

  // 清单项必须是 { index, key, status, width, height } —— 不能再有上游 url
  assert.ok(/MangaImageItem = \{[^}]*key: string/.test(apiSource), 'MangaImageItem 必须带 key')
  assert.ok(/MangaImageItem = \{[^}]*status:/.test(apiSource), 'MangaImageItem 必须带状态')
  assert.ok(!/MangaImageItem = \{[^}]*\burl\b/.test(apiSource), '清单项绝不能带上游 url 字段')

  assert.equal(typeof api.imageUrl, 'function', 'api.imageUrl 必须存在')
  assert.equal(api.imageUrl('a'.repeat(64)), `/api/images/${'a'.repeat(64)}`, 'imageUrl 必须拼出真实直出路由')
  assert.ok(apiSource.includes("'/api/books/images/cache/stats'"), '必须暴露服务端缓存 stats')

  // 阅读器只能用服务端地址，绝不能出现直连上游的写法
  assert.ok(screenSource.includes('api.imageUrl('), '阅读器必须用服务端直出地址')
  assert.ok(!screenSource.includes('item.url'), '阅读器不得再引用清单里的上游 url')
})

test('P1 契约 - 宽高占位与重试 remount（防跳动 / 不破坏 ETag 缓存键）', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(source.includes('aspectRatio'), '必须用 manifest 给的宽高做占位比例，避免首屏跳动')
  assert.ok(source.includes('retryTokens'), '重试必须靠 remount 而不是改 URL')
  assert.ok(!source.includes('_retry='), '严禁用 cache-busting 查询串（会破坏 ETag/immutable 与 SW 缓存键）')
})

test('main.tsx - 按 bookKind 分派到漫画阅读器', () => {
  const source = read('../src/main.tsx')

  assert.ok(source.includes("bookKind === 'comic'"), '必须按 bookKind 分派')
  assert.ok(source.includes('MangaReaderScreen'), '必须挂载漫画阅读器')
  // 书架卡片的类型角标（漫画 / 听书 / 小说）已抽到 src/ShelfKindTag.tsx（便于 renderToStaticMarkup 断言），
  // 故徽标断言落在那里，此处只锁 main.tsx 的接线。
  assert.ok(source.includes('<ShelfKindTag bookKind={item.bookKind} />'), '书架卡片必须挂载类型角标')
  assert.ok(read('../src/ShelfKindTag.tsx').includes('shelf-card-tag-comic'), '书架卡片必须有漫画徽标')
})

test('main.tsx - 漫画分支必须传入主 UI 主题（app-shell + 显式 theme prop）', () => {
  const source = read('../src/main.tsx')
  const start = source.indexOf("if (reader.book.bookKind === 'comic')")
  assert.ok(start >= 0, '必须存在漫画分支')
  // 切到漫画分支之后的第一个 return（即文本阅读器分支）之前，取整段漫画 JSX
  const afterManga = source.indexOf('MangaReaderScreen', start)
  const end = source.indexOf('return (', afterManga)
  const comic = source.slice(start, end === -1 ? source.length : end)

  // ① 漫画分支自己的 app-shell 必须带主题
  assert.ok(comic.includes('app-shell theme-${settings.theme}'), '漫画分支必须渲染在带主题的 app-shell 内')
  // ② 根节点自带 theme prop（第二道保险，不依赖外层继承）
  assert.ok(comic.includes('theme={settings.theme}'), '必须把 settings.theme 传进 MangaReaderScreen')
})

test('styles.css - 漫画样式已声明（含书架漫画徽标）', () => {
  const css = read('../src/styles.css')

  for (const selector of ['.manga-scroll', '.manga-image-frame', '.manga-image-failed', '.shelf-card-tag-comic']) {
    assert.ok(css.includes(selector), `styles.css 必须声明 ${selector}`)
  }
})

test('漫画 UI - 沉浸式交互与图片加载进度（借参考实现的交互，穿本项目的外壳）', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // ① 控制条自动隐藏 + 点画面唤出
  assert.ok(source.includes('autoHideToolbars'), '必须有"自动隐藏工具条"开关')
  assert.ok(source.includes('toggleToolbars'), '点击画面必须能唤出/收起工具条')
  assert.ok(source.includes('toolbars-hidden'), '必须复用本项目的 toolbars-hidden 显隐机制')
  // ② 图片加载进度（参考实现：正在加载图片 n/N）
  assert.ok(source.includes('markLoaded'), '必须累计已解码图片数')
  assert.ok(source.includes('manga-progress-bar'), '必须有图片加载进度条')
  // ③ 图片适配（适宽 / 原始尺寸）
  assert.ok(source.includes("'original'") && source.includes('is-fit-original'), '必须有原始尺寸模式')
  // ④ 复用本项目阅读器外壳与设置排版 —— 不新造一套，才能与文本阅读器观感一致
  assert.ok(source.includes("className={`reader-workspace"), '必须复用 reader-workspace 外壳')
  assert.ok(source.includes('reader-drawer-left'), '目录必须复用左侧抽屉')
  assert.ok(source.includes('reader-drawer-right'), '设置必须复用右侧抽屉')
  assert.ok(source.includes('setting-section'), '设置抽屉必须复用本项目的设置排版')
  assert.ok(source.includes('reader-floating-footer'), '必须复用悬浮底栏')
  assert.ok(source.includes('VirtualChapterList'), '目录必须复用既有虚拟章节列表')
})

test('漫画 UI - 主题兼容硬约束：漫画样式不得写死任何颜色', () => {
  const css = read('../src/styles.css')
  const block = css.slice(css.indexOf('漫画阅读器（方案 E）'))
  assert.ok(block.length > 0, '必须能找到漫画样式块')

  // 写死颜色会在夜间模式下变成"透光背景 + 隐形黑字"（AGENTS.md 记过这条）
  assert.ok(!/#[0-9a-fA-F]{3,8}\b/.test(block), '漫画样式严禁出现十六进制颜色，必须用主题变量')
  assert.ok(!/\brgba?\(/.test(block), '漫画样式严禁出现 rgb()/rgba() 颜色，必须用主题变量')
  assert.ok(block.includes('var(--accent)'), '进度条必须用主题强调色')
  assert.ok(block.includes('var(--surface-muted)') && block.includes('var(--surface)'), '底色必须用主题变量')
})
