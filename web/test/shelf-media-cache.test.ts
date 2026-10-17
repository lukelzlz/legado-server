import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resources } from '../src/i18n'
import { SHELF_MODAL_SHELF, shelfModalReducer, type ShelfModalState } from '../src/shelfModalStack'
import type { BookshelfItem } from '../src/api'

/**
 * issue #75：有声书/漫画在书架里点「离线缓存全本」时，**弹窗文案与状态复位**。
 *
 * 拆成两层断言，理由与本仓库既有做法一致：
 *
 * 1. **状态转移**（真跑）：`BookManageModal` 的"正在缓存 / 已缓存 / 重试"三态完全由
 *    `item.cacheState` 决定，而 `item` 是 `shelfModalStack` 这个纯 reducer 里的快照。
 *    所以"任务结束后弹窗会不会复位"是可以在 Node 里**真实执行**的，
 *    不需要浏览器（而且 `main.tsx` ① `import './styles.css'` ② 顶层 `createRoot`
 *    ⇒ Node 侧根本 import 不进来）。
 * 2. **文案接线**（读源码）：`BookManageModal` 住 `main.tsx`，只能按真实 JSX 兄弟节点
 *    逐字断言：有声书必须走**音频口径**（章 + 分段），漫画走**图片口径**，
 *    小说才说"正文"。改前这里只有一套"正在离线缓存全本 … 章 / 预先下载全书正文"，
 *    有声书看了全是错话。
 */

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

/** 统一成 LF：本仓库在 Windows 上检出是 CRLF，断言不能依赖行尾。 */
const readSource = (name: string) =>
  fs.readFileSync(path.resolve(__dirname, `../src/${name}`), 'utf8').replace(/\r\n/g, '\n')

const mainTsx = readSource('main.tsx')

/** 摘出 `BookManageModal` 的函数体（到下一个顶层 `function` 之前），断言只在这一段里成立。 */
function bookManageModalSource(): string {
  const start = mainTsx.indexOf('function BookManageModal(')
  assert.ok(start >= 0, 'main.tsx 里必须还有 BookManageModal（找不到说明被重命名了，测试需要更新）')
  const rest = mainTsx.slice(start + 1)
  const next = rest.search(/\n(?:function |const \w+ = \(|export )/)
  return next < 0 ? rest : rest.slice(0, next)
}

const modalSource = bookManageModalSource()

const audioItem = (over: Partial<BookshelfItem> = {}): BookshelfItem => ({
  sourceId: 'https://audio.example.com',
  bookUrl: 'https://audio.example.com/book/1',
  name: '听书样本',
  tocUrl: 'https://audio.example.com/book/1/toc',
  lastReadAt: 0,
  cachedChapters: 0,
  totalChapters: 0,
  cacheState: 'caching',
  completed: false,
  bookKind: 'audio',
  ...over,
})

const openCaching = (item: BookshelfItem): ShelfModalState =>
  shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openManage', item })

// ---------------------------------------------------------------------------
// 1. 文案接线：audio / comic 必须是媒体口径，只有小说才说"正文"
// ---------------------------------------------------------------------------

test('issue #75 - BookManageModal 按书型选文案：有声书走音频口径（章 + 分段）', () => {
  // 书型判定
  assert.match(modalSource, /const bookKind = item\.bookKind \|\| 'novel'/, '必须先从 item 取书型')
  assert.match(modalSource, /const isAudio = bookKind === 'audio'/, '必须识别有声书')
  assert.match(modalSource, /const isComic = bookKind === 'comic'/, '必须识别漫画')

  // 三态标题（缓存中 / 可缓存 / 已缓存）都要有音频版本
  for (const key of ['shelf.cachingAllAudio', 'shelf.cacheAllTitleAudio', 'shelf.recacheOrVerifyAudio']) {
    assert.ok(modalSource.includes(`t('${key}'`), `有声书标题必须用 ${key}`)
  }
  // 说明文案：空闲 / 已缓存
  assert.ok(modalSource.includes("t('shelf.cacheAllDescAudioIdle'"), '有声书空闲说明不能用"正文"口径')
  assert.ok(
    modalSource.includes("t('shelf.cacheAllDescAudioReady'"),
    '有声书完成说明必须报「章 + 段音频」',
  )

  // 渲染时用的是这些变量，不是写死的"正在离线缓存全本"
  assert.ok(modalSource.includes('<strong>{cachingTitle}</strong>'), '缓存中标题必须来自 cachingTitle')
  assert.ok(modalSource.includes('isReady ? recacheTitle'), '已缓存标题必须来自 recacheTitle')
  assert.ok(modalSource.includes('isReady ? readyDesc :'), '已缓存说明必须来自 readyDesc')
  assert.ok(modalSource.includes(': idleDesc}'), '空闲说明必须来自 idleDesc')

  // 进度行：有声书多一档分段口径，且用的是既有的 `reader.cacheProgressTracks`（{{done}}/{{planned}}）
  assert.ok(
    modalSource.includes("isAudio ? t('reader.cacheProgressTracks', '分段 {{done}}/{{planned}}', { done: item.cachedTracks ?? 0, planned: item.totalTracks || '?' }) : null"),
    '有声书进度必须额外显示"分段 done/planned"',
  )
})

test('issue #75 - BookManageModal 按书型选文案：漫画走图片口径', () => {
  for (const key of ['shelf.cachingAllComic', 'shelf.cacheAllTitleComic', 'shelf.recacheOrVerifyComic']) {
    assert.ok(modalSource.includes(`t('${key}'`), `漫画标题必须用 ${key}`)
  }
  assert.ok(modalSource.includes("t('shelf.cacheAllDescComicIdle'"), '漫画空闲说明必须提**图片**而不是正文')
  assert.ok(modalSource.includes("t('shelf.cacheAllDescComicReady'"), '漫画完成说明要有独立文案')

  // 小说保留原文案（不能被顺手改错）
  assert.ok(modalSource.includes("t('shelf.cacheAllDescIdle', '预先下载全书正文以供离线阅读')"), '小说的"正文"文案必须保留')
})

test('issue #75 - 目录未知时进度不能算成 100%（totalChapters=0 时 percent 必须是 0）', () => {
  assert.match(
    modalSource,
    /const percent = item\.totalChapters > 0\s*\n?\s*\? Math\.min\(100, Math\.round\(\(item\.cachedChapters \/ item\.totalChapters\) \* 100\)\)\s*\n?\s*: 0/,
    'percent 必须先判 totalChapters > 0，否则 0/1 或 1/1 都会显示成 100%',
  )
  assert.ok(
    !modalSource.includes('Math.max(1, item.totalChapters || 1)'),
    '不能再用 "把 0 当成 1" 的老写法（那正是「100% (1/1) 章」的来源）',
  )
  // 总章数未知时显示 `?`
  assert.ok(modalSource.includes("{item.cachedChapters} / {item.totalChapters || '?'}"), '总章数未知必须显示 ?')
})

// ---------------------------------------------------------------------------
// 2. 状态复位：任务结束（完成 / 失败）后弹窗必须离开"正在缓存 + 取消缓存"
// ---------------------------------------------------------------------------

test('issue #75 - 有声书缓存结束后弹窗复位成「已离线缓存 X 章 / N 段」', () => {
  let state = openCaching(audioItem({ cacheState: 'caching', cachedChapters: 0, totalChapters: 0 }))
  assert.equal(state.layer, 'manage')
  assert.equal((state as Extract<ShelfModalState, { layer: 'manage' }>).item.cacheState, 'caching', '刚点缓存时是 caching')

  // 服务端任务跑完了：书架列表下一拍回来的是 ready + 真实章数/段数
  state = shelfModalReducer(state, {
    type: 'syncSnapshot',
    items: [audioItem({ cacheState: 'ready', cachedChapters: 12, totalChapters: 42, cachedTracks: 30, totalTracks: 90 })],
  })
  const item = (state as Extract<ShelfModalState, { layer: 'manage' }>).item
  assert.equal(item.cacheState, 'ready', '完成后必须离开 caching（否则弹窗常驻"正在缓存 + 取消缓存"）')
  assert.equal(item.cachedChapters, 12)
  assert.equal(item.totalChapters, 42, '总章数必须来自服务端（目录），不能停在占位的 1')
  assert.equal(item.cachedTracks, 30, '分段数也要回灌，否则"章 + 分段"里的分段永远不动')
  assert.equal(item.totalTracks, 90)
})

test('issue #75 - 有声书缓存失败后弹窗显示「失败 + 重试」而不是继续转圈', () => {
  let state = openCaching(audioItem({ cacheState: 'caching' }))
  state = shelfModalReducer(state, {
    type: 'syncSnapshot',
    items: [audioItem({ cacheState: 'failed', cachedChapters: 0, totalChapters: 42, cacheError: '有 9 段音频下载失败，点击重试' })],
  })
  const item = (state as Extract<ShelfModalState, { layer: 'manage' }>).item
  assert.equal(item.cacheState, 'failed')
  assert.equal(item.cacheError, '有 9 段音频下载失败，点击重试', '失败原因必须透传给弹窗（详情行）')

  // 弹窗渲染时：failed ⇒ 「重试离线缓存」+ cacheError
  assert.ok(modalSource.includes("isFailed ? t('shelf.retryCache', '重试离线缓存')"), '失败必须给重试入口')
  assert.ok(modalSource.includes('item.cacheError ||'), '失败原因必须显示（服务端给的那句）')
})

test('issue #75 - 只有分段进度变化也要回灌弹窗（不能因为章数没变就跳过）', () => {
  let state = openCaching(audioItem({ cacheState: 'caching', cachedChapters: 3, totalChapters: 42, cachedTracks: 8, totalTracks: 90 }))
  state = shelfModalReducer(state, {
    type: 'syncSnapshot',
    items: [audioItem({ cacheState: 'caching', cachedChapters: 3, totalChapters: 42, cachedTracks: 11, totalTracks: 90 })],
  })
  const item = (state as Extract<ShelfModalState, { layer: 'manage' }>).item
  assert.equal(item.cachedTracks, 11)
})

// ---------------------------------------------------------------------------
// 3. 四语文案齐全且占位符正确（漏一个占位符 = 界面上出现字面量 {{tracks}}）
// ---------------------------------------------------------------------------

const MEDIA_SHELF_KEYS = [
  'cachingAllAudio',
  'cachingAllComic',
  'cacheAllTitleAudio',
  'cacheAllTitleComic',
  'recacheOrVerifyAudio',
  'recacheOrVerifyComic',
  'cacheAllDescAudioIdle',
  'cacheAllDescComicIdle',
  'cacheAllDescAudioReady',
  'cacheAllDescComicReady',
] as const

test('issue #75 - 新增的媒体缓存文案在四语里都存在且占位符正确', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  for (const locale of locales) {
    const translation = (resources[locale] as { translation: { shelf: Record<string, string>; reader: Record<string, string> } }).translation
    const shelf = translation.shelf
    for (const key of MEDIA_SHELF_KEYS) {
      const value = shelf[key]
      assert.ok(typeof value === 'string' && value.length > 0, `${locale} 缺少 shelf.${key}`)
      if (key === 'cacheAllDescAudioReady') {
        assert.ok(value.includes('{{count}}'), `${locale} shelf.${key} 必须带 {{count}}`)
        assert.ok(value.includes('{{tracks}}'), `${locale} shelf.${key} 必须带 {{tracks}}`)
      }
      // 文案里不能残留"正文"字样：媒体书不是正文
      if (key === 'cacheAllDescAudioIdle' || key === 'cacheAllDescComicIdle') {
        assert.ok(!value.includes('正文'), `${locale} shelf.${key} 不能写"正文"（那是小说的口径）`)
      }
    }
    // 分段进度复用既有的 reader.* key，四语必须在（`{{done}}/{{planned}}`）
    const trackKey = translation.reader.cacheProgressTracks
    assert.ok(trackKey.includes('{{done}}'), `${locale} reader.cacheProgressTracks 丢了 {{done}}`)
    assert.ok(trackKey.includes('{{planned}}'), `${locale} reader.cacheProgressTracks 丢了 {{planned}}`)
  }
})
