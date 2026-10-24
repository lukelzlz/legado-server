import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { changeAppLanguage } from '../src/i18n'
import type { Chapter } from '../src/api'
import { MangaReaderScreen } from '../src/MangaReaderScreen'
import { AudiobookPlayerScreen } from '../src/AudiobookPlayerScreen'
import { SourceSwitchModal, matchBestChapter, notifySourceSwitchFallback, switchBookSource } from '../src/sourceSwitch'
import { matchBestChapter as matchViaShim } from '../src/SourceSwitchModal'
import { normalizeChapterTitle, remapChapterAfterSourceSwitch } from '../src/sourceSwitchRemap'
import { clearAudioManifests, getAudioManifest, putAudioManifest } from '../src/audioOffline'
import { clearMangaManifests, getMangaManifest, putMangaManifest } from '../src/mangaOffline'
import { toast } from '../src/Toast'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

const ch = (index: number, title: string): Chapter => ({ index, title, url: `https://s.example.com/c/${index}` })

/* ========================================================================== */
/*                 第一部分：重映射纯函数（小说行为零回归的核心）               */
/* ========================================================================== */

test('抽模块 - 小说换源走的仍是同一个函数（纯函数 + 兼容入口同一实现）', () => {
  const chapters = [ch(0, '第一章 穿越异界'), ch(1, '第二章 绝世神功'), ch(2, '第三章 家族大比')]

  // ① 新模块导出的 matchBestChapter 与旧的 SourceSwitchModal 入口必须是**同一个函数引用**
  assert.equal(matchViaShim, matchBestChapter, 'SourceSwitchModal 的导出必须是 sourceSwitch 的同一个函数（不是复制一份）')
  // ② remapChapterAfterSourceSwitch 与 matchBestChapter 结果一致（规则只有一处真相）
  for (const [title, index] of [['第二章 绝世神功', 0], ['第99章 不存在', 2], [undefined, 1]] as const) {
    const viaRemap = remapChapterAfterSourceSwitch(chapters, title as string | undefined, index as number)
    const viaMatch = matchBestChapter(chapters, title as string | undefined, index as number)
    assert.equal(viaRemap.index, viaMatch.index, `落点必须一致（title=${String(title)}）`)
    assert.equal(viaRemap.title, viaMatch.title, `标题必须一致（title=${String(title)}）`)
  }
})

test('重映射 - 章节名完全匹配（原始标题）', () => {
  const chapters = [ch(0, '第一章 穿越异界'), ch(1, '第二章 绝世神功'), ch(2, '第三章 家族大比')]
  const r = remapChapterAfterSourceSwitch(chapters, '第二章 绝世神功', 0)
  assert.equal(r.index, 1)
  assert.equal(r.title, '第二章 绝世神功')
  assert.equal(r.matched, true, '按标题命中 ⇒ matched 必须为真（否则会误报"停到最接近位置"）')
})

test('重映射 - 标题归一化后匹配（同一章、不同书写格式）', () => {
  const chapters = [ch(0, '第1章 穿越异界！'), ch(1, '第2章 绝世神功（修）'), ch(2, '第3章 家族大比')]
  const r = remapChapterAfterSourceSwitch(chapters, '第二章 绝世神功', 0)
  assert.equal(r.index, 1)
  assert.equal(r.title, '第2章 绝世神功（修）')
  assert.equal(r.matched, true)
})

test('重映射 - 仅序号匹配（标题完全匹配不到 ⇒ 停在最接近位置）', () => {
  const chapters = [ch(0, '序章'), ch(1, '开启'), ch(2, '终曲')]
  const r = remapChapterAfterSourceSwitch(chapters, '第九十九回 飞升大结局', 2)
  assert.equal(r.index, 2, '匹配不到就停在原序号（最接近位置）')
  assert.equal(r.title, '终曲')
  assert.equal(r.matched, false, '序号兜底必须如实报告"没匹配上"')

  // 序号为 0 时同理（不能因为"没匹配上"就跳到别的章）
  assert.deepEqual(remapChapterAfterSourceSwitch(chapters, '第九十九回 飞升大结局', 0), { index: 0, title: '序章', matched: false })
})

test('重映射 - 新目录更短 ⇒ 序号被钳到最后一章，不越界', () => {
  const chapters = [ch(0, '单章')]
  assert.deepEqual(remapChapterAfterSourceSwitch(chapters, '第五十章', 50), { index: 0, title: '单章', matched: false })
  // 负数/缺失序号也必须钳到 0（目录加载前 chapterIndex 可能为 0，但防御式断言便宜）
  assert.equal(remapChapterAfterSourceSwitch(chapters, undefined, -3).index, 0)
  assert.equal(remapChapterAfterSourceSwitch(chapters, undefined, undefined).index, 0)

  // 真正"更短"的形态：新目录 3 章、原在 10 章
  const shorter = [ch(0, 'A'), ch(1, 'B'), ch(2, 'C')]
  assert.deepEqual(remapChapterAfterSourceSwitch(shorter, '第十一章 无此题', 10), { index: 2, title: 'C', matched: false })
})

test('重映射 - 空目录：不抛异常，落点为 0/空标题 且如实标记未匹配', () => {
  assert.deepEqual(remapChapterAfterSourceSwitch([], '第一章', 5), { index: 0, title: '', matched: false })
  assert.deepEqual(remapChapterAfterSourceSwitch([], undefined, undefined), { index: 0, title: '', matched: false })
  // 兼容入口同样不许抛
  assert.deepEqual(matchBestChapter([], '第一章', 5), { index: 0, title: '' })
})

test('重映射 - 归一化只吞"第X章"前缀与标点，不吞正文（防止误伤）', () => {
  assert.equal(normalizeChapterTitle('第二章 绝世神功'), normalizeChapterTitle('第2章 绝世神功'))
  assert.equal(normalizeChapterTitle('Chapter 12 - The End'), 'theend')
  assert.equal(normalizeChapterTitle('第十二章'), '')
  // 只差一个字的章节绝不能被归一化成同一章（否则换源会跳到隔壁）
  assert.notEqual(normalizeChapterTitle('绝世神功'), normalizeChapterTitle('绝世神功续'))
  // 单字标题不做模糊匹配：拿"序"去子串匹配会把不相干的章认成同一章
  const chapters = [ch(0, '序曲'), ch(1, '开局'), ch(2, '序')]
  assert.deepEqual(remapChapterAfterSourceSwitch(chapters, '序', 2), { index: 2, title: '序', matched: false })
})

/* ========================================================================== */
/*                          弹窗：候选源来源与排序                             */
/* ========================================================================== */

test('换源弹窗 - 候选源来源与排序（当前源置顶 → 已收录候选 → 章节多者优先）', () => {
  const source = read('../src/sourceSwitch.tsx')

  // 来源：① 当前源（合成条目）② knownAlternateSources ③ 全源流式搜索结果（书名精确相等才收）
  assert.ok(source.includes('isCurrent: true'), '当前源必须作为第一条候选出现')
  assert.ok(source.includes('knownAlternateSources'), '必须吃 knownAlternateSources（已收录候选）')
  assert.ok(source.includes('r.name.trim().toLowerCase() === bookName.trim().toLowerCase()'), '流式结果必须按书名精确相等过滤')
  // 排序：三段优先级
  assert.ok(source.includes('if (a.isCurrent) return -1'), '当前源必须排在第一位')
  assert.ok(source.includes('if (a.isKnownAlternate && !b.isKnownAlternate) return -1'), '已收录候选排第二')
  assert.ok(source.includes('return b.totalChapters - a.totalChapters'), '同档按章节数降序')

  // 小说阅读器确实在用这个共享弹窗（而不是残留一份自己的实现）
  const reader = read('../src/ReaderScreen.tsx')
  assert.ok(reader.includes("import { SourceSwitchModal, switchBookSource } from './sourceSwitch'"), '小说必须从共享模块引入')
  assert.ok(!/^import .*from '\.\/SourceSwitchModal'/m.test(reader), '小说不得再直接从旧 shim 引入（避免两处来源）')
})

test('换源弹窗 - 静态渲染包含搜索横幅、当前源与空态', () => {
  const html = renderToStaticMarkup(
    React.createElement(SourceSwitchModal, {
      bookName: '测试小说',
      author: '某作者',
      currentSourceId: 'https://a.example.com',
      currentBookUrl: 'https://a.example.com/book/1',
      currentChapterTitle: '第一章',
      currentChapterIndex: 0,
      knownAlternateSources: [],
      onSwitch: async () => {},
      onClose: () => {},
    }),
  )
  assert.ok(html.includes('source-switch-dialog'), '必须渲染换源弹窗本体')
  assert.ok(html.includes('source-search-banner'), '必须有全源检索进度横幅')
  assert.ok(html.includes('source-candidate-list'), '必须有候选源列表')
  assert.ok(html.includes('https://a.example.com'), '当前源必须出现在候选里')
})

/* ========================================================================== */
/*                     换源落库编排（三端共用，小说零回归）                     */
/* ========================================================================== */

test('换源编排 - 详情/落库两次请求 + 封面回退顺序 + 书名作者清洗', async () => {
  const calls: { url: string; init: RequestInit }[] = []
  const originalFetch = globalThis.fetch
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push({ url, init: init ?? {} })
    if (url === '/api/books/details') {
      return new Response(JSON.stringify({
        sourceId: 'https://new.example.com',
        // 书名入库前必须清洗（首尾空白去掉；换行**保留**是既有 `cleanTitle` 的既定语义，
        // 由 `sanitizeBookName` 在服务端进一步处理，本 PR 不改这块）
        name: '  《新源书名》  ',
        author: '「某作者」',
        tocUrl: 'https://new.example.com/toc',
        coverUrl: '',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    if (url === '/api/bookshelf/switch-source') {
      return new Response(JSON.stringify({ sourceId: 'https://new.example.com', bookUrl: 'https://new.example.com/b/1' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    throw new Error(`unexpected call: ${url}`)
  }) as typeof fetch

  try {
    const result = await switchBookSource(
      {
        details: {
          sourceId: 'https://old.example.com',
          name: '旧源书名',
          author: '旧作者',
          tocUrl: 'https://old.example.com/toc',
          coverUrl: 'https://old.example.com/cover.jpg',
          alternateSources: [{ sourceId: 'https://alt.example.com', bookUrl: 'https://alt.example.com/b/1', name: '测试小说' }],
        },
        bookUrl: 'https://old.example.com/b/1',
        bookKind: 'comic',
      },
      {
        result: { sourceId: 'https://new.example.com', bookUrl: 'https://new.example.com/b/1', name: '测试小说', bookKind: 'audio' },
        chapters: [ch(0, '第一章'), ch(1, '第二章')],
        targetChapterIndex: 1,
      },
      '未知书名',
    )

    assert.deepEqual(calls.map(c => c.url), ['/api/books/details', '/api/bookshelf/switch-source'], '换源必须只打这两个接口（不新增服务端接口）')
    const write = JSON.parse(String(calls[1].init.body))
    assert.equal(write.oldSourceId, 'https://old.example.com', '必须带上旧源标识（服务端据此迁移书架记录）')
    assert.equal(write.oldBookUrl, 'https://old.example.com/b/1')
    assert.equal(write.book.bookUrl, 'https://new.example.com/b/1')
    assert.equal(write.book.name, '新源书名', '书名必须清洗（剥掉书名号与首尾空白）')
    assert.equal(write.book.author, '某作者', '作者必须清洗（去掉首尾括号/标点噪声）')
    assert.equal(
      write.book.coverUrl,
      'https://old.example.com/cover.jpg',
      '新源没封面 ⇒ 回退顺序：新源 → 候选源 → 旧源 → 已收录候选的封面',
    )
    assert.equal(write.alternateSources?.length, 1, '已收录候选源必须原样保留')

    assert.equal(result.targetChapterIndex, 1)
    assert.equal(result.book.bookKind, 'audio', '详情缺失时必须回退到候选源 bookKind（漫画/有声分派靠它）')
    assert.deepEqual(result.previous, { sourceId: 'https://old.example.com', bookUrl: 'https://old.example.com/b/1' }, '必须回传旧源标识供调用方清缓存')
    assert.equal(result.book.details.alternateSources?.length, 1, '新详情必须继承旧详情的已收录候选')
  } finally {
    globalThis.fetch = originalFetch
  }
})

/* ========================================================================== */
/*                                  漫画接线                                   */
/* ========================================================================== */

test('漫画 - 目录抽屉里有"换源"按钮，位置/class 与小说一致', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, {
      openBook: {
        details: { sourceId: 'https://manga.example.com', name: '测试漫画', tocUrl: 'https://manga.example.com/book/1' },
        bookUrl: 'https://manga.example.com/book/1',
        bookKind: 'comic',
        chapters: [ch(0, '第 1 话'), ch(1, '第 2 话')],
      },
      startIndex: 0,
      onClose: () => {},
      theme: 'dark',
    }),
  )
  assert.ok(html.includes('drawer-footer-actions'), '漫画目录抽屉必须有 drawer-footer-actions（与小说同一层）')
  assert.ok(html.includes('switch-source-btn'), '换源按钮必须复用小说的 switch-source-btn class')
  assert.ok(html.includes('shelf-button'), '换源按钮必须复用 shelf-button 底栏按钮样式')
})

test('漫画 - 换源接线：共用弹窗 + 重新解析 + 重映射 + 只清旧源那一本的离线清单', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // ① 用的是**共用**弹窗与共用编排，而不是自己写一份
  assert.ok(
    source.includes("import { SourceSwitchModal, notifySourceSwitchFallback, remapChapterAfterSourceSwitch, switchBookSource } from './sourceSwitch'"),
    '漫画必须从共享模块引入弹窗与编排',
  )
  assert.ok(source.includes('<SourceSwitchModal'), '漫画必须渲染共用的换源弹窗')
  assert.ok(source.includes('onSwitch={handleSwitchSource}'), '弹窗必须接上漫画自己的落库回调')
  assert.ok(source.includes('const [showSourceSwitch, setShowSourceSwitch] = useState(false)'), '必须有换源弹窗的开关状态')
  assert.ok(source.includes('setActiveDrawer(null); setShowSourceSwitch(true)'), '点击换源必须关抽屉再开弹窗（与小说一致）')

  // ② 换源后重新解析新源目录 + 用纯函数重映射当前章
  assert.ok(source.includes('await switchBookSource(activeBook, chosen,'), '必须走共用编排（内部会重新解析新源详情）')
  assert.ok(source.includes('remapChapterAfterSourceSwitch(chosen.chapters'), '落点必须由纯函数算出，不得各写一套')
  assert.ok(source.includes('setChapterIndex(switched.targetChapterIndex)'), '必须把落点写进章节状态')
  assert.ok(source.includes('setActiveBook(prev => ({ ...prev, ...switched.book }))'), '必须把新目录灌回当前书籍（触发重新拉图片清单）')

  // ③ 旧源离线清单只清这一本
  assert.ok(
    source.includes('clearMangaManifests(sourceId, bookUrl)'),
    '必须清理旧源的离线清单/快照（按 [sourceId, bookUrl]，只清这一本）',
  )
  assert.ok(source.includes('const { sourceId, bookUrl } = switched.previous'), '清理用的必须是**旧源**标识')
  // ④ 旧源的服务端预取任务要停掉（唯一入口是 DELETE /api/books/images/cache，只清这一本）
  assert.ok(source.includes('api.clearImageCache(sourceId, bookUrl)'), '必须停掉旧源的整本离线预取（否则它会拿着旧书地址继续跑）')
})

/* ========================================================================== */
/*                                  有声接线                                   */
/* ========================================================================== */

test('有声 - 目录抽屉里有"换源"按钮，位置/class 与小说一致', () => {
  const html = renderToStaticMarkup(
    React.createElement(AudiobookPlayerScreen, {
      openBook: {
        details: { sourceId: 'https://audio.example.com', name: '测试有声书', tocUrl: 'https://audio.example.com/book/1' },
        bookUrl: 'https://audio.example.com/book/1',
        bookKind: 'audio',
        chapters: [ch(0, '第 1 集'), ch(1, '第 2 集')],
      },
      startIndex: 0,
      onClose: () => {},
      theme: 'dark',
    }),
  )
  assert.ok(html.includes('drawer-footer-actions'), '有声目录抽屉必须有 drawer-footer-actions（与小说同一层）')
  assert.ok(html.includes('switch-source-btn'), '换源按钮必须复用小说的 switch-source-btn class')
  assert.ok(html.includes('shelf-button'), '换源按钮必须复用 shelf-button 底栏按钮样式')
})

test('有声 - 换源接线：共用弹窗 + 重新解析 + 重映射 + 停播 + 取消预取任务', () => {
  const source = read('../src/AudiobookPlayerScreen.tsx')

  assert.ok(
    source.includes("import { SourceSwitchModal, notifySourceSwitchFallback, remapChapterAfterSourceSwitch, switchBookSource } from './sourceSwitch'"),
    '有声必须从共享模块引入弹窗与编排',
  )
  assert.ok(source.includes('<SourceSwitchModal'), '有声必须渲染共用的换源弹窗')
  assert.ok(source.includes('onSwitch={handleSwitchSource}'))
  assert.ok(source.includes('const [showSourceSwitch, setShowSourceSwitch] = useState(false)'))
  assert.ok(source.includes('setActiveDrawer(null); setShowSourceSwitch(true)'))

  assert.ok(source.includes('await switchBookSource(activeBook, chosen,'), '必须走共用编排')
  assert.ok(source.includes('remapChapterAfterSourceSwitch(chosen.chapters'), '落点必须由纯函数算出')
  assert.ok(source.includes('setChapterIndex(switched.targetChapterIndex)'))
  assert.ok(source.includes('setActiveBook(prev => ({ ...prev, ...switched.book }))'), '必须把新目录灌回当前书籍（触发重新拉音频清单）')

  // 旧源音频地址已失效：必须停播（并清掉"播放意图"，否则恢复播放会去打 404）
  assert.ok(source.includes('playingIntentRef.current = false'), '换源必须清掉播放意图')
  assert.ok(source.includes('audioRef.current?.pause()'), '换源必须停掉正在播放的旧源音频')
  // 预取任务取消：唯一入口是 DELETE /api/audio/cache?sourceId&bookUrl（只清这一本）
  assert.ok(source.includes('api.clearAudioCache(sourceId, bookUrl)'), '必须取消旧源的预取任务（既有 API：clearAudioCache 内含 audioPrefetch.cancel）')
  assert.ok(source.includes('clearAudioManifests(sourceId, bookUrl)'), '旧源的本机音频清单快照也要清（只清这一本）')
  assert.ok(source.includes('const { sourceId, bookUrl } = switched.previous'), '清理用的必须是**旧源**标识')
})

test('有声/漫画 - 预取取消走的是真实存在的服务端路由（防死配置）', () => {
  const routes = read('../../server/src/main/kotlin/io/legado/server/Routes.kt')
  const apiSource = read('../src/api.ts')

  // 漫画：DELETE /api/books/images/cache 里先 cancel(prefetch) 再 deleteBook
  assert.ok(routes.includes('mangaPrefetch?.cancel(sourceId, bookUrl)'), '服务端必须在该路由里取消漫画预取')
  assert.ok(apiSource.includes('/api/books/images/cache?sourceId='), '前端 clearImageCache 必须指向该真实路由')
  // 有声：DELETE /api/audio/cache 里先 cancel(prefetch) 再 deleteBook
  assert.ok(routes.includes('audioPrefetch?.cancel(sourceId, bookUrl)'), '服务端必须在该路由里取消音频预取')
  assert.ok(apiSource.includes('`/api/audio/cache?sourceId='), '前端 clearAudioCache 必须指向该真实路由')
})

/* ========================================================================== */
/*              旧源离线清单清理：行为级验证（只清这一本，不误伤）             */
/* ========================================================================== */

/**
 * 极简 IndexedDB 假实现：只支持本测试真正走到的路径
 * （`open` → `transaction('manifests')` → 带 `by_book` 索引的 `openCursor(range)`）。
 *
 * 关键设计：**按数据库名各建一个实例、不依赖调用顺序**。
 * `mangaOffline` / `audioOffline` 各自在模块级缓存了 `dbPromise`，一旦哪个测试先跑、
 * 共享一个 db 实例，就会出现"顺序不同结果不同"的脆弱测试。
 */
class MiniRequest {
  result: unknown = null
  error: unknown = null
  onsuccess: ((e: unknown) => void) | null = null
  onerror: ((e: unknown) => void) | null = null
  onupgradeneeded: (() => void) | null = null
}

class MiniObjectStore {
  data = new Map<string, Record<string, unknown>>()
  name: string
  constructor(name: string) { this.name = name }
  createIndex() { return {} }
  /** 真实 `IDBObjectStore.index(name)` 返回索引对象；本 mock 只有 `by_book`，直接代理回本 store。 */
  index(_name: string) { return this }
  put(record: Record<string, unknown>) { this.data.set(String(record.key), record) }
  /** 真实 `get` 返回的是 **IDBRequest**（结果异步回填），不是一个裸对象。 */
  get(key: string) {
    const req = new MiniRequest()
    // 微任务而不是定时器：源码对 IndexedDB 操作套了 1500ms `withTimeout`，
    // 用 0ms 定时器会和它抢时间（实测偶发超时 ⇒ 返回兜底值 0，表现为"删不掉"）。
    queueMicrotask(() => {
      req.result = this.data.get(key) ?? null
      ;(req.onsuccess as ((e: unknown) => void) | null)?.({ target: req })
    })
    return req
  }
  openCursor(range?: [string, string]) {
    const req = new MiniRequest()
    const rows = [...this.data.values()].filter(r =>
      !range || (r.sourceId === range[0] && r.bookUrl === range[1]))
    let i = 0
    /** 推进一格并把结果异步回给调用方（真实游标语义：调用 `continue()` 才走下一格）。 */
    const step = (): void => {
      if (i < rows.length) {
        const row = rows[i]
        i++
        req.result = { value: row, delete: () => { this.data.delete(String(row.key)) }, continue: step }
      } else {
        req.result = null
      }
      queueMicrotask(() => (req.onsuccess as ((e: unknown) => void) | null)?.({ target: req }))
    }
    step()
    return req
  }
}

class MiniTransaction {
  oncomplete: (() => void) | null = null
  onerror: (() => void) | null = null
  store: MiniObjectStore
  constructor(store: MiniObjectStore) { this.store = store }
  objectStore() { return this.store }
}

class MiniDatabase {
  stores = new Map<string, MiniObjectStore>()
  objectStoreNames = { contains: (name: string) => this.stores.has(name) }
  createObjectStore(name: string) { const s = new MiniObjectStore(name); this.stores.set(name, s); return s }
  transaction(name: string) {
    let store = this.stores.get(name)
    if (!store) { store = new MiniObjectStore(name); this.stores.set(name, store) }
    const tx = new MiniTransaction(store)
    // 事务在"所有游标回调跑完之后"才 commit（真 IDB 就是这个顺序）
    queueMicrotask(() => queueMicrotask(() => queueMicrotask(() => tx.oncomplete?.())))
    return tx
  }
}

/**
 * 每个数据库名对应**唯一** mock 实例：第一次 `open` 时创建。
 *
 * 注意：这里**不假设**自己一定赢。`mangaOffline` / `audioOffline` 各自在模块级缓存了
 * `dbPromise`，而整套测试里先跑的那个 mock 工厂可能已经把它绑定走了
 * （`web/test/offline-pwa.test.ts` 也装了一个 IndexedDB mock）。
 * 因此断言一律走**公开 API**（put/get/clear 前后对比），不依赖"自己这份 mock 的内部表"。
 */
const miniDbs = new Map<string, MiniDatabase>()

function installMiniIndexedDb() {
  ;(globalThis as unknown as { indexedDB: unknown }).indexedDB = {
    open: (name: string) => {
      const req = new MiniRequest()
      let db = miniDbs.get(name)
      if (!db) {
        db = new MiniDatabase()
        miniDbs.set(name, db)
      }
      const db2 = db
      req.result = db2
      queueMicrotask(() => {
        if (!db2.stores.size) req.onupgradeneeded?.()
        req.onsuccess?.({ target: req })
      })
      return req
    },
  }
  ;(globalThis as unknown as { IDBKeyRange: unknown }).IDBKeyRange = {
    only: (value: [string, string]) => value,
  }
}

/**
 * 取一份**全新的**离线模块实例。
 *
 * 为什么不直接用静态导入：`mangaOffline` / `audioOffline` 都在模块级缓存了 `dbPromise`
 * （"打开一次、长期复用"），而整套测试里 `web/test/offline-pwa.test.ts` 也装过一个
 * IndexedDB mock —— 谁先跑，谁就把这份 `dbPromise` 绑到了自己的 mock 上，
 * 于是本文件里的断言会在"单独跑绿、整套跑红"之间摇摆（实测踩过：cursor.delete not a function）。
 *
 * 用查询串拿到一个**独立的模块实例**后，它的 `dbPromise` 一定是本测试自己装的 mock 打开的，
 * 断言才是确定性的（已实测：`import('../src/mangaOffline?x') !== import('../src/mangaOffline')`）。
 */
async function freshOfflineModules() {
  const manga = await import('../src/mangaOffline?test=source-switch-unified')
  const audio = await import('../src/audioOffline?test=source-switch-unified')
  return { manga, audio }
}

test('漫画 - clearMangaManifests 只清旧源的那一本，不动新源与别的书', async () => {
  installMiniIndexedDb()
  const { manga } = await freshOfflineModules()
  const { putMangaManifest, getMangaManifest, clearMangaManifests } = manga

  const mk = (sourceId: string, bookUrl: string, chapterUrl: string) => ({
    sourceId, bookUrl, chapterUrl, chapterIndex: 0,
    images: [{ index: 0, key: 'k'.repeat(8), status: 'ready' as const }],
  })

  await putMangaManifest(mk('old', 'old-book', 'old-book-c1'))
  await putMangaManifest(mk('old', 'old-book', 'old-book-c2'))
  await putMangaManifest(mk('old', 'other-book', 'other-book-c1'))
  await putMangaManifest(mk('new', 'new-book', 'new-book-c1'))

  assert.ok(await getMangaManifest('old', 'old-book-c1'), '前置：旧源那本书的快照必须已写入')
  assert.ok(await getMangaManifest('new', 'new-book-c1'), '前置：新源的快照必须已写入')

  const removed = await clearMangaManifests('old', 'old-book')
  assert.equal(removed, 2, '只应删掉旧源那本书的 2 条快照')

  assert.equal(await getMangaManifest('old', 'old-book-c1'), null, '旧源那本书的快照必须没了')
  assert.equal(await getMangaManifest('old', 'old-book-c2'), null)
  assert.ok(await getMangaManifest('old', 'other-book-c1'), '旧源**别的书**不能被误伤')
  assert.ok(await getMangaManifest('new', 'new-book-c1'), '新源的快照必须完好（否则换源后立刻丢离线能力）')
})

test('有声 - clearAudioManifests 只清旧源的那一本，不动新源与别的书', async () => {
  installMiniIndexedDb()
  const { audio } = await freshOfflineModules()
  const { putAudioManifest, getAudioManifest, clearAudioManifests } = audio

  const mk = (sourceId: string, bookUrl: string, chapterUrl: string) => ({
    sourceId, bookUrl, chapterUrl, chapterIndex: 0,
    tracks: [{ index: 0, segmentIndex: 0, key: 't'.repeat(8), status: 'ready' as const }],
  })

  await putAudioManifest(mk('old', 'old-book', 'old-book-c1'))
  await putAudioManifest(mk('old', 'old-book', 'old-book-c2'))
  await putAudioManifest(mk('old', 'other-book', 'other-book-c1'))
  await putAudioManifest(mk('new', 'new-book', 'new-book-c1'))

  assert.ok(await getAudioManifest('old', 'old-book-c1'), '前置：旧源那本书的快照必须已写入')

  assert.equal(await clearAudioManifests('old', 'old-book'), 2, '只应删掉旧源那本书的 2 条快照')
  assert.equal(await getAudioManifest('old', 'old-book-c1'), null)
  assert.equal(await getAudioManifest('old', 'old-book-c2'), null)
  assert.ok(await getAudioManifest('old', 'other-book-c1'), '旧源别的书不能被误伤')
  assert.ok(await getAudioManifest('new', 'new-book-c1'), '新源的快照必须完好')

  assert.equal(await clearAudioManifests(), 2, '不带参数 = 全清（剩 2 条）')
  assert.equal(await getAudioManifest('new', 'new-book-c1'), null)
})

/* ========================================================================== */
/*                              目录 UI 三端对齐                               */
/* ========================================================================== */

/** 截出某个阅读器的目录抽屉 JSX（从 `reader-drawer-left` 到与之配对的 `</aside>`）。 */
function tocDrawerMarkup(source: string, ariaLabel: string): string {
  const start = source.indexOf(`reader-drawer-left`)
  assert.ok(start > 0, '必须能找到左侧目录抽屉')
  const end = source.indexOf(ariaLabel, start)
  assert.ok(end > start, `必须能找到抽屉的 aria-label（${ariaLabel}）`)
  const close = source.indexOf('</aside>', end)
  assert.ok(close > end, '抽屉必须正确闭合')
  return source.slice(start, close)
}

test('目录 UI - 三端抽屉的骨架 class 完全一致（header → 搜索 → 列表 → footer-actions）', () => {
  const novel = tocDrawerMarkup(read('../src/ReaderScreen.tsx'), 'reader.tocDrawer')
  const manga = tocDrawerMarkup(read('../src/MangaReaderScreen.tsx'), 'reader.tocDrawer')
  const audio = tocDrawerMarkup(read('../src/AudiobookPlayerScreen.tsx'), 'reader.tocDrawer')

  for (const [name, block] of [['小说', novel], ['漫画', manga], ['有声', audio]] as const) {
    assert.ok(block.includes('drawer-header'), `${name}: 必须有 drawer-header`)
    assert.ok(block.includes('drawer-title'), `${name}: 标题必须复用 drawer-title`)
    assert.ok(block.includes('drawer-header-actions'), `${name}: 关闭按钮必须放在 drawer-header-actions`)
    assert.ok(block.includes("t('reader.totalChapters'"), `${name}: 标题必须显示共用章节数文案`)
    assert.ok(block.includes('chapter-search'), `${name}: 必须有章节搜索（小说有 ⇒ 另外两端对齐）`)
    assert.ok(block.includes("t('reader.searchChapter'"), `${name}: 搜索占位文案必须复用同一个 key`)
    assert.ok(block.includes('<VirtualChapterList'), `${name}: 必须复用同一个虚拟章节列表`)
    assert.ok(block.includes('drawer-footer-actions'), `${name}: 必须有 drawer-footer-actions（换源）`)
    assert.ok(block.includes('switch-source-btn'), `${name}: 换源按钮必须复用同一个 class`)
    assert.ok(block.includes("t('reader.switchSource'"), `${name}: 换源文案必须复用既有 key`)
  }

  // 三端换源按钮的**行内属性**一致（同一 class 组合 + 同一"关抽屉再开弹窗"动作）
  const button = (block: string) => {
    const start = block.indexOf('drawer-footer-actions')
    return block.slice(start, block.indexOf('</div>', start)).replace(/\s+/g, ' ')
  }
  for (const block of [novel, manga, audio]) {
    assert.match(
      button(block),
      /className="shelf-button switch-source-btn" onClick=\{\(\) => \{ setActiveDrawer\(null\); setShowSourceSwitch\(true\) \}\}/,
      '三端的换源按钮必须是同一份接线',
    )
  }
})

test('目录 UI - 小说独有的动作没有被"对齐"掉，漫画/有声也在表达自己的特有动作', () => {
  const novel = tocDrawerMarkup(read('../src/ReaderScreen.tsx'), 'reader.tocDrawer')
  const manga = read('../src/MangaReaderScreen.tsx')
  const audio = read('../src/AudiobookPlayerScreen.tsx')

  // 小说独有的两根"针"：固定侧边栏、加书架
  assert.ok(novel.includes('desktop-pin-btn'), '小说的固定目录按钮必须保留')
  assert.ok(novel.includes("t('reader.addToShelf'"), '小说的加书架动作必须保留')

  // 漫画/有声不用"对齐"成小说那样去删自己的功能：目录绿点、离线状态这些都必须还在
  assert.ok(manga.includes('cachedUrlsSet={cachedChapters}'), '漫画的绿点（离线就绪）必须保留')
  assert.ok(audio.includes('cachedUrlsSet={cachedChapters}'), '有声的绿点必须保留')
  assert.ok(manga.includes('manga-offline-banner'), '漫画的离线横幅必须保留')
  assert.ok(audio.includes('audiobook-offline-banner'), '有声的离线横幅必须保留')
  assert.ok(audio.includes('audiobook-feature'), '有声底部功能条（特有的倍速/定时）必须保留')
})

/* ========================================================================== */
/*                                   i18n                                     */
/* ========================================================================== */

test('i18n - 换源相关键四语言齐备且非空（含本 PR 唯一新增键）', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const keys = ['reader.switchSource', 'reader.switchSourceNearestPosition', 'reader.tocDrawer', 'reader.searchChapter', 'reader.closeToc', 'source.switch', 'source.switching']

  for (const locale of locales) {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`))
    for (const key of keys) {
      const value = key.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], data)
      assert.equal(typeof value, 'string', `${locale} 缺少 ${key}`)
      assert.ok((value as string).trim().length > 0, `${locale} 的 ${key} 不能为空`)
    }
  }
})

test('i18n - 匹配不到时的提示只在"真的没匹配上"才弹（不打扰正常换源）', () => {
  const seen: string[] = []
  const listener = (list: { message: string }[]) => { seen.push(...list.map(item => item.message)) }
  const unsubscribe = toast.subscribe(listener)
  void changeAppLanguage('zh-CN')

  const t = (key: string, options?: Record<string, unknown>): string =>
    String((options?.defaultValue as string | undefined) ?? key)

  try {
    notifySourceSwitchFallback({ matched: true }, t as unknown as Parameters<typeof notifySourceSwitchFallback>[1])
    assert.equal(seen.length, 0, '标题匹配成功时绝不能弹提示')

    notifySourceSwitchFallback({ matched: false }, t as unknown as Parameters<typeof notifySourceSwitchFallback>[1])
    assert.equal(seen.length, 1, '按序号兜底时必须弹一次提示（否则用户看不懂为什么跳到这一章）')
  } finally {
    unsubscribe()
  }
})
