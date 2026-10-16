import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import i18n, { changeAppLanguage, resources } from '../src/i18n'

/**
 * 「书籍管理」弹窗里的「清除缓存」行（独立 PR：`feat/shelf-clear-cache`）。
 *
 * 为什么这些断言是**源码级**而不是 `renderToStaticMarkup`：
 * `BookManageModal` 与 `ShelfPage` 都定义在 `web/src/main.tsx` 里，而该模块
 * ① `import './styles.css'`（Node 无法加载 `.css`，实测 `Unknown file extension ".css"`）、
 * ② 模块顶层直接 `createRoot(document.getElementById('root')!).render(...)`。
 * 因此 Node 侧**不可能** import 它，静态渲染也无从谈起（想把弹窗拆成独立模块属于
 * 无关重构，本 PR 刻意不做）。
 *
 * 这里采取的折中：按**真实 JSX 源码**解析出弹窗里的 `manage-action-row` 兄弟节点序列——
 * 兄弟节点在源码里的先后**就是**渲染后的 DOM 顺序，`className` / `Icon name` / 内联默认文案
 * 也都是逐字取自真实模板。与之配套的浏览器端渲染验证见
 * `web/test/e2e-browser-reclean-a11y.ts`（真实点击 `.book-manage-sheet` 里的按钮）。
 *
 * 解析手法与 `button-styles-audit.test.ts` 同源（引号 / 花括号感知的 `<button ...>` 扫描）。
 */

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

/** 统一成 LF：本仓库在 Windows 下 CRLF 检出，索引/行尾断言必须与行尾风格无关。 */
const readSource = (name: string) => fs.readFileSync(path.resolve(__dirname, `../src/${name}`), 'utf8').replace(/\r\n/g, '\n')

/** 新增的 8 个 i18n key（四语都必须有、都必须非空）。 */
const CLEAR_CACHE_KEYS = [
  'clearCache',
  'clearCacheDesc',
  'clearCacheConfirm',
  'clearCacheSuccess',
  'clearCachePartial',
  'clearCachePartImages',
  'clearCachePartAudio',
  'clearCachePartContent',
  'clearCachePartLocal',
] as const

/** 四语各 9 项，缺一项都不行（本机离线正文算第四项）。 */
const CLEAR_CACHE_PART_KEYS = [
  'clearCachePartImages',
  'clearCachePartAudio',
  'clearCachePartContent',
  'clearCachePartLocal',
] as const

const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const

function translationOf(locale: (typeof LOCALES)[number]): Record<string, any> {
  return (resources[locale].translation as Record<string, any>)
}

/** 切出 `BookManageModal` 的函数体（到下一个顶层 `function ` 为止）。 */
function bookManageModalSource(): string {
  const source = readSource('main.tsx')
  const start = source.indexOf('function BookManageModal(')
  assert.ok(start > -1, 'main.tsx 必须存在 BookManageModal')
  const end = source.indexOf('\nfunction GroupManageModal(', start)
  assert.ok(end > start, '没法定位 BookManageModal 的结尾')
  return source.slice(start, end)
}

/**
 * 按源码顺序抽出弹窗里所有 `<button …>…</button>` 元素（**含子节点**——`<strong>` 文案在开标签之后，
 * 只扫开标签是拿不到标题的，这一点踩过坑）。
 * 返回顺序 = JSX 兄弟节点顺序 = 渲染后的 DOM 顺序。
 */
function extractButtonElements(body: string): string[] {
  const close = '</button>'
  return body
    .split('<button')
    .slice(1)
    .map(chunk => {
      const end = chunk.indexOf(close)
      // 源码里所有 button 都有闭合标签；万一没有（解析异常）就取到结尾，让断言如实失败
      return '<button' + (end === -1 ? chunk : chunk.slice(0, end + close.length))
    })
}

type ActionRow = { element: string; classes: string[]; strong: string }

/** 弹窗里所有「操作行」按钮（`manage-action-row`），保持源码顺序。 */
function actionRows(): ActionRow[] {
  return extractButtonElements(bookManageModalSource())
    .filter(element => /className="[^"]*manage-action-row/.test(element))
    .map(element => {
      const classMatch = /className="([^"]*)"/.exec(element)
      const strongMatch = /<strong>([\s\S]*?)<\/strong>/.exec(element)
      return {
        element,
        classes: (classMatch?.[1] ?? '').split(/\s+/).filter(Boolean),
        strong: (strongMatch?.[1] ?? '').replace(/\s+/g, ' ').trim(),
      }
    })
}

const findRow = (rows: ActionRow[], key: string) => rows.find(r => r.strong.includes(`shelf.${key}`))

/** 取函数/处理器的源码片段（从锚点起 `length` 个字符内）。 */
function windowFrom(source: string, anchor: string, length = 400): string {
  const at = source.indexOf(anchor)
  assert.ok(at > -1, `源码里找不到锚点：${anchor}`)
  return source.slice(at, at + length)
}

/** `ShelfPage` 里那个「清除缓存」处理函数的源码片段。 */
function clearCacheHandler(): string {
  const source = readSource('main.tsx')
  const start = source.indexOf('const clearCache = async (item: BookshelfItem) => {')
  assert.ok(start > -1, 'ShelfPage 必须有 clearCache 处理函数')
  const end = source.indexOf('\n  }\n', start)
  assert.ok(end > start, '没法定位 clearCache 处理函数的结尾')
  return source.slice(start, end)
}

// ---------------------------------------------------------------------------
// 1 & 2. 弹窗里的「清除缓存」行：danger 配色 + 排在「移出书架」之前
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 弹窗渲染出「清除缓存」行，且与「移出书架」同为 manage-action-row danger', () => {
  const rows = actionRows()
  const clearRow = findRow(rows, 'clearCache')
  const removeRow = findRow(rows, 'removeFromShelf')

  assert.ok(clearRow, '书籍管理弹窗里必须有一行渲染 shelf.clearCache')
  assert.ok(removeRow, '书籍管理弹窗里必须仍有一行渲染 shelf.removeFromShelf')

  // 用户原话：「在移出书架上面添加一个新按钮清除缓存，和移出书架一样用红色配色」
  assert.ok(clearRow.classes.includes('manage-action-row'), `清除缓存行的 class 必须含 manage-action-row，实际：${clearRow.classes.join(' ')}`)
  assert.ok(clearRow.classes.includes('danger'), `清除缓存行的 class 必须含 danger（红色），实际：${clearRow.classes.join(' ')}`)
  assert.deepEqual(
    [...clearRow.classes].sort(),
    [...removeRow.classes].sort(),
    '清除缓存行与移出书架行的 class 必须完全一致（红色配色同源）',
  )

  // 复用 Icon 组件里已存在的图标，不新造图标
  assert.ok(/<Icon name="trash"\s*\/>/.test(clearRow.element), '清除缓存行必须使用既有的 trash 图标')
  assert.ok(!/<Icon name="(broom|refresh)\s*"/.test(clearRow.element), '不得使用不存在的图标名')

  // 主副标题都在，且确实来自 i18n key
  assert.ok(clearRow.strong.includes("t('shelf.clearCache'"), '主标题必须走 shelf.clearCache')
  assert.ok(clearRow.element.includes("t('shelf.clearCacheDesc'"), '副标题必须走 shelf.clearCacheDesc')

  // 内联默认值必须与 zh-CN 词条逐字一致（否则中文会闪回英文/出现两套文案）
  const zhCN = translationOf('zh-CN')
  assert.ok(
    clearRow.element.includes(`t('shelf.clearCache', '${zhCN.shelf.clearCache}')`),
    `inline 默认值必须与 zh-CN 一致：${zhCN.shelf.clearCache}`,
  )
  assert.ok(
    clearRow.element.includes(`t('shelf.clearCacheDesc', '${zhCN.shelf.clearCacheDesc}')`),
    `inline 默认值必须与 zh-CN 一致：${zhCN.shelf.clearCacheDesc}`,
  )
})

test('shelf-clear-cache - 顺序断言：「清除缓存」紧挨在「移出书架」上面（DOM 兄弟顺序）', () => {
  const rows = actionRows()
  const clearIndex = rows.findIndex(r => r.strong.includes('shelf.clearCache'))
  const removeIndex = rows.findIndex(r => r.strong.includes('shelf.removeFromShelf'))

  assert.ok(clearIndex > -1 && removeIndex > -1, '两行都必须存在')
  assert.ok(
    clearIndex < removeIndex,
    `「清除缓存」必须渲染在「移出书架」之前（实际索引 ${clearIndex} vs ${removeIndex}）`,
  )
  assert.equal(removeIndex, clearIndex + 1, '「清除缓存」必须紧邻「移出书架」的正上方')
  assert.equal(removeIndex, rows.length - 1, '「移出书架」必须仍是弹窗里的最后一行（不改变既有收尾）')
})

test('shelf-clear-cache - 点击后先 onClose() 再动作，且沿用 onClearCache props 链路（不就地直调 api）', () => {
  const rows = actionRows()
  const clearRow = findRow(rows, 'clearCache')!
  assert.ok(clearRow, '清除缓存行必须存在')

  const onClick = /onClick=\{([\s\S]*?)\}>/.exec(clearRow.element)
  assert.ok(onClick, '清除缓存行必须有 onClick')
  assert.ok(/onClose\(\)\s*;\s*onClearCache\(\)/.test(onClick![1]), `onClick 必须「先关弹窗再动作」，实际：${onClick![1]}`)

  const source = readSource('main.tsx')
  // 弹窗 props 链路：声明 → 书架页传入
  assert.ok(/onClearCache:\s*\(\)\s*=>\s*void/.test(bookManageModalSource()), 'BookManageModal 必须声明 onClearCache prop')
  assert.ok(
    /onClearCache=\{\(\)\s*=>\s*clearCache\(managingItem\)\}/.test(source),
    '书架页必须按既有 props 链路把 onClearCache 传给 BookManageModal',
  )
  assert.equal((source.match(/<BookManageModal\b/g) ?? []).length, 1, 'BookManageModal 只应有一处使用点')

  // 弹窗内部不得绕过 props 直接调 api（野路子守卫）
  assert.ok(
    !/api\.(clearImageCache|clearAudioCache|clearBookCacheData)/.test(bookManageModalSource()),
    'BookManageModal 内部不得直接调用清理接口，必须走 onClearCache',
  )
})

// ---------------------------------------------------------------------------
// 3. 四语言词条齐全且非空（复用既有 parity 测试的 leaf-key 做法）
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 四语言 key 全部存在且非空，且 en/ja 未被中文污染', () => {
  const cjk = /[\u4e00-\u9fff]/
  const expectedClearCache: Record<(typeof LOCALES)[number], string> = {
    'zh-CN': '清除缓存',
    'zh-TW': '清除快取',
    'en-US': 'Clear Cache',
    'ja-JP': 'キャッシュ削除',
  }

  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, unknown>
    for (const key of CLEAR_CACHE_KEYS) {
      const value = shelf[key]
      assert.ok(typeof value === 'string', `${locale}.shelf.${key} 必须存在且为字符串`)
      assert.ok((value as string).trim().length > 0, `${locale}.shelf.${key} 不得为空`)
    }
    assert.equal(shelf.clearCache, expectedClearCache[locale], `${locale}.shelf.clearCache 文案不符`)

    // 四语必须互相独立：en/ja 不得直接复制中文
    if (locale === 'en-US') {
      assert.ok(!cjk.test(shelf.clearCache as string), 'en-US 不得含中文')
      assert.ok(!cjk.test(shelf.clearCacheDesc as string), 'en-US 不得含中文')
      assert.ok(!cjk.test(shelf.clearCacheConfirm as string), 'en-US confirm 不得含中文')
      assert.ok(!cjk.test(shelf.clearCacheSuccess as string), 'en-US 成功 toast 不得含中文')
      assert.ok(!cjk.test(shelf.clearCachePartial as string), 'en-US 部分失败 toast 不得含中文')
      assert.ok(!cjk.test(shelf.clearCachePartLocal as string), 'en-US 本机离线正文文案不得含中文')
    }
    if (locale === 'ja-JP') {
      assert.notEqual(shelf.clearCacheDesc, translationOf('zh-CN').shelf.clearCacheDesc, 'ja-JP 不得直接复制中文')
      assert.notEqual(shelf.clearCacheSuccess, translationOf('zh-CN').shelf.clearCacheSuccess, 'ja-JP 不得直接复制中文')
      assert.notEqual(shelf.clearCachePartial, translationOf('zh-CN').shelf.clearCachePartial, 'ja-JP 不得直接复制中文')
      assert.notEqual(shelf.clearCachePartLocal, translationOf('zh-CN').shelf.clearCachePartLocal, 'ja-JP 不得直接复制中文')
    }
    if (locale === 'zh-TW') {
      assert.notEqual(shelf.clearCacheDesc, translationOf('zh-CN').shelf.clearCacheDesc, 'zh-TW 必须是繁体习惯用词')
      assert.notEqual(shelf.clearCachePartLocal, translationOf('zh-CN').shelf.clearCachePartLocal, 'zh-TW 必须是繁体习惯用词')
    }
  }
})

test('shelf-clear-cache - 四个失败项文案（图片/音频/正文/本机离线正文）四语齐全、非空且互不相同', () => {
  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    const labels = CLEAR_CACHE_PART_KEYS.map(key => shelf[key])
    assert.equal(labels.length, 4, `${locale} 必须有四个失败项文案`)
    for (const [i, label] of labels.entries()) {
      assert.ok(typeof label === 'string' && label.trim().length > 0, `${locale}.shelf.${CLEAR_CACHE_PART_KEYS[i]} 不得为空`)
    }
    assert.equal(new Set(labels).size, 4, `${locale} 四个失败项文案必须互不相同（否则 toast 分不清哪一项没清掉）`)
    // 本机离线那一项必须能一眼看出是"本机/离线"（不能和服务端正文缓存撞名）
    assert.ok(
      /本机|本機|local|ローカル/i.test(shelf.clearCachePartLocal),
      `${locale} clearCachePartLocal 必须点明「本机/离线」：${shelf.clearCachePartLocal}`,
    )
  }
})

test('shelf-clear-cache - confirm 文案明确列出「会清什么」与「不会动什么」，且插值可用', async () => {
  const mustNotAffect: Record<(typeof LOCALES)[number], string> = {
    'zh-CN': '阅读进度',
    'zh-TW': '閱讀進度',
    'en-US': 'reading progress',
    'ja-JP': '読書の進捗',
  }
  const cleared: Record<(typeof LOCALES)[number], string[]> = {
    'zh-CN': ['图片', '音频', '正文', '本机离线'],
    'zh-TW': ['圖片', '音訊', '正文', '本機離線'],
    'en-US': ['image', 'audio', 'text', 'local offline'],
    'ja-JP': ['画像', '音声', '本文', 'ローカルオフライン'],
  }

  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    const confirmText = shelf.clearCacheConfirm
    // 书名必须插值进来，否则用户看不出清的是哪本书
    assert.ok(confirmText.includes('{{name}}'), `${locale} confirm 必须带 {{name}}`)
    // 明确列出会清掉的部分（图片 / 音频 / 正文 / 本机离线）
    for (const part of cleared[locale]) {
      assert.ok(confirmText.includes(part), `${locale} confirm 必须点名会清掉「${part}」`)
    }
    // 明确声明不动阅读进度（这是「清除缓存」与「移出书架」的关键区别）
    assert.ok(confirmText.includes(mustNotAffect[locale]), `${locale} confirm 必须声明不动「${mustNotAffect[locale]}」`)

    // 运行时插值：不留占位符、带上书名
    await changeAppLanguage(locale)
    const rendered = i18n.t('shelf.clearCacheConfirm', { name: 'TEST_BOOK' })
    assert.ok(rendered.includes('TEST_BOOK'), `${locale} confirm 插值后必须含书名`)
    assert.ok(!rendered.includes('{{'), `${locale} confirm 插值后不得残留占位符`)

    const success = i18n.t('shelf.clearCacheSuccess', { name: 'TEST_BOOK' })
    assert.ok(success.includes('TEST_BOOK'), `${locale} 成功 toast 必须带书名`)

    const partial = i18n.t('shelf.clearCachePartial', { name: 'TEST_BOOK', parts: shelf.clearCachePartImages })
    assert.ok(partial.includes('TEST_BOOK'), `${locale} 部分失败 toast 必须带书名`)
    assert.ok(partial.includes(shelf.clearCachePartImages), `${locale} 部分失败 toast 必须点名没清掉的部分`)
    assert.ok(!partial.includes('{{'), `${locale} 部分失败 toast 不得残留占位符`)
  }
  await changeAppLanguage('zh-CN')
})

// ---------------------------------------------------------------------------
// 4. 源码级接线：三个端点都必须真的被调用到（防「按钮是死的」）
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 三个既有 api 封装复用了正确的三个端点', () => {
  const apiSrc = readSource('api.ts')

  const imageWindow = windowFrom(apiSrc, 'clearImageCache:', 400)
  assert.ok(imageWindow.includes('/api/books/images/cache?sourceId='), 'clearImageCache 必须打 /api/books/images/cache')
  assert.ok(imageWindow.includes("method: 'DELETE'"), 'clearImageCache 必须是 DELETE')

  const audioWindow = windowFrom(apiSrc, 'clearAudioCache:', 400)
  assert.ok(audioWindow.includes('/api/audio/cache?sourceId='), 'clearAudioCache 必须打 /api/audio/cache')
  assert.ok(audioWindow.includes("method: 'DELETE'"), 'clearAudioCache 必须是 DELETE')

  const contentWindow = windowFrom(apiSrc, 'clearBookCacheData:', 400)
  assert.ok(
    contentWindow.includes('/api/bookshelf/cache?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}&clearData=true'),
    'clearBookCacheData 必须打 /api/bookshelf/cache?...&clearData=true',
  )
  assert.ok(contentWindow.includes("method: 'DELETE'"), 'clearBookCacheData 必须是 DELETE')
})

test('shelf-clear-cache - 处理函数串行调用「三端点 + 本机按书删除」，且逐个记账（不静默吞失败）', () => {
  const source = readSource('main.tsx')
  const start = source.indexOf('const clearCache = async (item: BookshelfItem) => {')
  const handler = clearCacheHandler()

  // 必须挂在 ShelfPage 上（而不是脱离页面就地直调）
  const shelfPageAt = source.indexOf('function ShelfPage(')
  assert.ok(shelfPageAt > -1 && shelfPageAt < start, 'clearCache 必须定义在 ShelfPage 内部')

  // confirm() 必须在任何删除动作之前
  const confirmAt = handler.indexOf('confirm(')
  assert.ok(confirmAt > -1, '必须先 confirm() 再动手')

  const steps = ['api.clearImageCache', 'api.clearAudioCache', 'api.clearBookCacheData', 'clearOfflineBook(']
  const order = steps.map(name => {
    const at = handler.indexOf(name)
    assert.ok(at > -1, `clearCache 必须调用 ${name}`)
    assert.ok(at > confirmAt, `confirm() 必须早于 ${name}`)
    return at
  })
  assert.deepEqual(
    [...order].sort((a, b) => a - b),
    order,
    '四步必须按「图片 → 音频 → 正文（服务端）→ 本机离线正文」串行调用',
  )

  // 四段调用必须各自 try/catch 记账：某一段失败不能静默吞掉，也不能连累后面三段
  const tryStarts = order.map((callAt, i) => {
    const tryAt = handler.lastIndexOf('try {', callAt)
    assert.ok(tryAt > -1 && tryAt < callAt, `第 ${i + 1} 段调用必须包在 try 里`)
    return tryAt
  })
  for (let i = 1; i < tryStarts.length; i++) {
    assert.ok(tryStarts[i - 1] < tryStarts[i], '四个 try 块必须串行排列（不是并发、也不是嵌套）')
  }
  for (let i = 0; i < tryStarts.length; i++) {
    const segmentEnd = i === tryStarts.length - 1 ? handler.length : tryStarts[i + 1]
    const segment = handler.slice(tryStarts[i], segmentEnd)
    assert.ok(/catch\s*\{/.test(segment), `第 ${i + 1} 段调用必须自带 catch`)
  }
  assert.equal((handler.match(/failedParts\.push\(/g) ?? []).length, 4, '四段失败必须各自记账（failedParts 四次 push）')

  // 全成功 → success；部分失败 → warning 并点名
  const successAt = handler.indexOf("toast.success(")
  const warnAt = handler.indexOf('toast.warning(')
  assert.ok(successAt > -1, '全成功必须 toast.success')
  assert.ok(warnAt > -1, '部分失败必须 toast.warning（不得静默）')
  assert.ok(handler.includes("t('shelf.clearCacheSuccess'"), '成功 toast 必须走 shelf.clearCacheSuccess')
  assert.ok(handler.includes("t('shelf.clearCachePartial'"), '部分失败 toast 必须走 shelf.clearCachePartial')
  assert.ok(handler.includes('name: item.name'), 'toast 必须带上书名')
  for (const partKey of CLEAR_CACHE_PART_KEYS) {
    assert.ok(
      handler.includes(`t('shelf.${partKey}'`),
      `必须分别标注「${partKey}」哪一部分没清掉（含本机离线正文）`,
    )
  }

  // 清完要回书架拉一次最新状态（离线章数已归零）
  assert.ok(/\bload\(\)/.test(handler), '清完缓存必须刷新书架状态')
})

test('shelf-clear-cache - 作用域严格按书：四步都只带 item.sourceId + item.bookUrl，绝不全清、绝不带上别的书', () => {
  const handler = clearCacheHandler()

  // 本机那一份：只允许一次调用，参数必须逐字是当前这本书
  const localCalls = handler.match(/clearOfflineBook\([^)]*\)/g) ?? []
  assert.deepEqual(
    localCalls,
    ['clearOfflineBook(item.sourceId, item.bookUrl)'],
    `本机离线删除必须且只能按当前这本书删除，实际：${JSON.stringify(localCalls)}`,
  )
  // 全清 / LRU 驱逐 / 遍历全部书：都会波及其它书，一律禁止
  assert.ok(!/clearAllOffline/.test(handler), '严禁在单本清理里调用全清 clearAllOffline')
  assert.ok(!/evictLruOfflineBooks/.test(handler), '严禁顺手触发 LRU 驱逐（会删掉别的书）')
  assert.ok(!/getOfflineStorageStats/.test(handler), '严禁按「全部书」遍历后批量删')

  // 服务端三步同样只带这本书
  for (const name of ['clearImageCache', 'clearAudioCache', 'clearBookCacheData']) {
    const calls = handler.match(new RegExp(`api\\.${name}\\([^)]*\\)`, 'g')) ?? []
    assert.deepEqual(
      calls,
      [`api.${name}(item.sourceId, item.bookUrl)`],
      `${name} 必须且只能按当前这本书调用，实际：${JSON.stringify(calls)}`,
    )
  }
})

test('shelf-clear-cache - 本机按书删除复用了既有 API，且 key 由 sourceId+bookUrl 构成（不按书名）', () => {
  const src = readSource('offlineStorage.ts')

  // 已有按书删除 API ⇒ 直接复用，不新增「全清」或平行实现
  assert.ok(
    /export async function clearOfflineBook\(sourceId: string, bookUrl: string\)/.test(src),
    '必须复用既有的 clearOfflineBook(sourceId, bookUrl)',
  )
  // by_book 索引落在 [sourceId, bookUrl] 上：这是「按书、不按书名」的硬证据
  assert.ok(
    /createIndex\('by_book', \['sourceId', 'bookUrl'\]/.test(src),
    'by_book 索引必须由 [sourceId, bookUrl] 构成',
  )
  assert.ok(/IDBKeyRange\.only\(\[sourceId, bookUrl\]\)/.test(src), '删除范围必须精确到这一本书')
  // 章节主键 = sourceId::bookUrl::chapterUrl
  assert.ok(
    /`\$\{sourceId\}::\$\{bookUrl\}::\$\{chapterUrl\}`/.test(src),
    '章节主键必须由 sourceId + bookUrl + chapterUrl 构成',
  )

  // clearOfflineBook 的函数体里不得出现任何「按书名」的匹配
  const bodyStart = src.indexOf('export async function clearOfflineBook')
  const bodyEnd = src.indexOf('export async function clearAllOffline')
  assert.ok(bodyStart > -1 && bodyEnd > bodyStart, '没法定位 clearOfflineBook 函数体')
  const body = src.slice(bodyStart, bodyEnd)
  assert.ok(!/\bname\b/.test(body), 'clearOfflineBook 不得按书名匹配（书名列根本不存在）')
})
