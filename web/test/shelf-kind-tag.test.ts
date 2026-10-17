import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { changeAppLanguage, resources, SUPPORTED_LOCALES } from '../src/i18n'
import { ShelfKindTag } from '../src/ShelfKindTag'
import { isLocalBook } from '../src/localBook'
import { SHELF_MODAL_SHELF, shelfModalReducer } from '../src/shelfModalStack'
import type { BookshelfItem } from '../src/api'

// 书架卡片右上角的书籍类型角标：**本地 / 漫画 / 听书 / 小说**。
// 用户需求（issue #66）：「本地书导入时右上角标签添加"本地"，数据库里也要有，漫画有声小说同理」
// ⇒ 四类**互斥**（四选一，不是"本地 + 小说"叠两个）；本地书优先按**身份**判
// （`loc_book` / `local://`，见 `src/localBook.ts`），其次按服务端下发的 `book_kind`。
//
// 组件渲染期**不读 `location`**：这里是 `renderToStaticMarkup` 的无头渲染。

const TAG_CLASSES = ['shelf-card-tag-local', 'shelf-card-tag-comic', 'shelf-card-tag-audio', 'shelf-card-tag-novel'] as const

type TagProps = { bookKind?: string; local?: boolean }

/** 渲染类型角标，并返回每个角标 class 出现的次数。 */
function renderTagCounts(props: TagProps): Record<string, number> {
  const html = renderToStaticMarkup(React.createElement(ShelfKindTag, props))
  return Object.fromEntries(TAG_CLASSES.map(cls => [cls, html.split(cls).length - 1]))
}

const totalTags = (props: TagProps) => {
  const counts = renderTagCounts(props)
  return TAG_CLASSES.reduce((sum, cls) => sum + counts[cls], 0)
}

test('书架角标 - 小说（bookKind=novel 或缺省）渲染 shelf-card-tag-novel 且文案为「小说」', async () => {
  try {
    await changeAppLanguage('zh-CN')

    for (const bookKind of ['novel', undefined] as const) {
      const html = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind }))
      const label = bookKind === undefined ? '不带 bookKind' : `bookKind=${bookKind}`

      assert.ok(html.includes('shelf-card-tag-novel'), `${label} 的书必须渲染 shelf-card-tag-novel`)
      assert.ok(html.includes('小说'), `${label} 的书角标文案必须是「小说」`)
      assert.ok(!html.includes('shelf-card-tag-comic'), `${label} 的书不得出现漫画角标`)
      assert.ok(!html.includes('shelf-card-tag-audio'), `${label} 的书不得出现听书角标`)
      assert.ok(!html.includes('shelf-card-tag-local'), `${label} 的书不得出现本地角标`)
    }
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - 本地书渲染 shelf-card-tag-local 且文案为「本地」，绝不退化成小说/漫画/听书', async () => {
  try {
    await changeAppLanguage('zh-CN')

    // 两条来源：① 服务端 book_kind='local'；② 前端按身份判出来的 local
    for (const props of [{ bookKind: 'local' }, { bookKind: 'novel', local: true }, { local: true }] as TagProps[]) {
      const html = renderToStaticMarkup(React.createElement(ShelfKindTag, props))
      const label = JSON.stringify(props)

      assert.ok(html.includes('shelf-card-tag-local'), `${label} 必须渲染 shelf-card-tag-local（本地不是小说的特例）`)
      assert.ok(html.includes('本地'), `${label} 的角标文案必须是「本地」`)
      assert.ok(!html.includes('shelf-card-tag-novel'), `${label} 不得再渲染小说角标（右上角只能有一个标签）`)
      assert.ok(!html.includes('shelf-card-tag-comic'), `${label} 不得出现漫画角标`)
      assert.ok(!html.includes('shelf-card-tag-audio'), `${label} 不得出现听书角标`)
    }
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - 漫画 / 听书绝不渲染小说或本地角标（右上角不会出现两个标签）', async () => {
  try {
    await changeAppLanguage('zh-CN')

    const comic = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind: 'comic' }))
    assert.ok(comic.includes('shelf-card-tag-comic'), '漫画书必须渲染漫画角标')
    assert.ok(!comic.includes('shelf-card-tag-novel'), '漫画书不得再渲染小说角标（否则右上角叠两个标签）')
    assert.ok(!comic.includes('shelf-card-tag-local'), '漫画书不得渲染本地角标')

    const audio = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind: 'audio' }))
    assert.ok(audio.includes('shelf-card-tag-audio'), '听书必须渲染听书角标')
    assert.ok(!audio.includes('shelf-card-tag-novel'), '听书不得再渲染小说角标（否则右上角叠两个标签）')
    assert.ok(!audio.includes('shelf-card-tag-local'), '听书不得渲染本地角标')
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - 任一种输入都恰好只有一个类型角标（四类互斥）', () => {
  const inputs: TagProps[] = [
    { bookKind: 'novel' },
    { bookKind: 'comic' },
    { bookKind: 'audio' },
    { bookKind: 'local' },
    { bookKind: undefined },
    { bookKind: 'video' },
    { local: true },
    { bookKind: 'comic', local: true },
  ]
  for (const props of inputs) {
    assert.equal(totalTags(props), 1, `${JSON.stringify(props)} 应恰好渲染 1 个类型角标，实际 ${JSON.stringify(renderTagCounts(props))}`)
  }
})

test('书架角标 - 四种语言的本地 / 漫画 / 听书 / 小说徽标文案都正确', async () => {
  const expected: Array<[Parameters<typeof changeAppLanguage>[0], { local: string; comic: string; audio: string; novel: string }]> = [
    ['zh-CN', { local: '本地', comic: '漫画', audio: '听书', novel: '小说' }],
    ['zh-TW', { local: '本機', comic: '漫畫', audio: '聽書', novel: '小說' }],
    ['en-US', { local: 'Local', comic: 'Comic', audio: 'Audio', novel: 'Novel' }],
    ['ja-JP', { local: 'ローカル', comic: '漫画', audio: '朗読', novel: '小説' }],
  ]

  try {
    for (const [locale, texts] of expected) {
      await changeAppLanguage(locale)
      for (const [bookKind, text] of [['local', texts.local], ['comic', texts.comic], ['audio', texts.audio], ['novel', texts.novel]] as const) {
        const html = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind }))
        assert.ok(html.includes(`>${text}<`), `${locale} 的 ${bookKind} 角标文案应为「${text}」`)
      }
    }
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - localTag / comicTag / audioTag / novelTag 四语言都存在且非空（不许再靠中文默认值兜底）', () => {
  const keys = ['shelf.localTag', 'shelf.comicTag', 'shelf.audioTag', 'shelf.novelTag'] as const

  for (const locale of SUPPORTED_LOCALES) {
    for (const key of keys) {
      const value = key.split('.').reduce<unknown>(
        (node, part) => (node as Record<string, unknown> | undefined)?.[part],
        resources[locale.code].translation as unknown,
      )

      assert.equal(typeof value, 'string', `${locale.code} 缺少 ${key}：会静默退化成 t() 里的中文默认值`)
      assert.ok((value as string).trim().length > 0, `${locale.code} 的 ${key} 不得为空`)
    }
  }
})

test('书架角标 - localTag 的 zh-TW 必须是「本機」、en 不得含中文（四语不是复制粘贴）', () => {
  const shelfOf = (code: string) => (resources[code].translation as Record<string, any>).shelf as Record<string, string>
  assert.equal(shelfOf('zh-CN').localTag, '本地')
  assert.equal(shelfOf('zh-TW').localTag, '本機')
  assert.equal(shelfOf('en-US').localTag, 'Local')
  assert.equal(shelfOf('ja-JP').localTag, 'ローカル')
  assert.ok(!/[\u4e00-\u9fff]/.test(shelfOf('en-US').localTag), 'en-US.localTag 不得含中文')
})

test('书架角标 - .shelf-card-tag-local 几何与漫画 / 听书 / 小说完全一致，仅背景色为中性灰', () => {
  const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

  const ruleBody = (cls: string): string => {
    const matched = css.match(new RegExp(`\\.${cls}\\s*\\{([^}]*)\\}`))
    assert.ok(matched, `styles.css 缺少 .${cls}`)
    return matched![1]
  }

  // 去掉背景色后应逐字相同：位置/内边距/圆角/字号/字重/字距/毛玻璃/阴影不许各写各的。
  const withoutBackground = (body: string) => body.replace(/background:[^;]*;?/, '').replace(/\s+/g, ' ').trim()
  const local = ruleBody('shelf-card-tag-local')

  assert.equal(withoutBackground(local), withoutBackground(ruleBody('shelf-card-tag-comic')), '本地角标几何必须与漫画角标一致')
  assert.equal(withoutBackground(local), withoutBackground(ruleBody('shelf-card-tag-audio')), '本地角标几何必须与听书角标一致')
  assert.equal(withoutBackground(local), withoutBackground(ruleBody('shelf-card-tag-novel')), '本地角标几何必须与小说角标一致')
  // 与小说一样是**右上角**（不能还留在左上角与「批量选择」复选框/单选角重叠）
  assert.match(local, /right:\s*8px/, '本地角标必须与其他三类一样在右上角（right: 8px）')
  assert.ok(!/left:\s*8px/.test(local), '本地角标不得仍在左上角')

  const background = local.match(/background:([^;]*)/)?.[1] ?? ''
  // 中性灰：三原色接近相等，且不与漫画橙 / 听书蓝 / 小说绿重复
  const rgb = background.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/)
  assert.ok(rgb, `本地角标背景必须是 rgba 色值：${background}`)
  const [r, g, b] = [Number(rgb![1]), Number(rgb![2]), Number(rgb![3])]
  assert.ok(Math.max(r, g, b) - Math.min(r, g, b) <= 12, `本地角标必须用中性色（三通道接近），实际 rgb(${r}, ${g}, ${b})`)
  for (const other of ['rgba(21, 128, 61, 0.92)', 'rgba(180, 83, 9, 0.92)', 'rgba(29, 78, 216, 0.92)']) {
    assert.ok(!background.includes(other), `本地角标背景色不得与既有角标重复：${other}`)
  }
})

test('书架角标 - ShelfPage 接线：四类角标由 ShelfKindTag 唯一产出，本地书按身份判 local', () => {
  // main.tsx 末尾有 createRoot(document.getElementById('root')!)，无法在 Node 里 import，
  // 因此这里只做接线断言：保证抽出的组件没有变成没人用的死代码。
  const source = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')

  assert.ok(
    source.includes('<ShelfKindTag bookKind={item.bookKind} local={isLocalBook(item)} />'),
    '书架卡片必须挂载 <ShelfKindTag bookKind={item.bookKind} local={isLocalBook(item)} />（本地身份优先于服务端 book_kind）',
  )
  assert.ok(
    /import \{ isLocalBook \} from '\.\/localBook'/.test(source),
    'main.tsx 必须复用 localBook 模块的判据（不得再内联一份）',
  )
  // 修复前：本地书是**左上角**另起一个 span（与右上「小说」同显）。四类互斥后必须只剩一处。
  assert.ok(
    !source.includes('{isLocal && <span className="shelf-card-tag-local">'),
    '本地角标不得再由 main.tsx 内联渲染在左上角（必须走 ShelfKindTag，四类互斥）',
  )
  assert.equal((source.match(/shelf-card-tag-local/g) ?? []).length, 0, 'main.tsx 里不得再出现 shelf-card-tag-local（唯一产出点是 ShelfKindTag）')
})

test('本地书判定 - isLocalBook 按 loc_book 书源 id / local:// 路径（与服务端 BookKind.isLocalRef 同源）', () => {
  assert.equal(isLocalBook({ sourceId: 'loc_book', bookUrl: 'local://abc' }), true)
  assert.equal(isLocalBook({ sourceId: 'weird-id', bookUrl: 'local://abc/chapter_1' }), true)
  assert.equal(isLocalBook({ sourceId: 'loc_book', bookUrl: 'https://example.com/book/1' }), true)
  assert.equal(isLocalBook({ sourceId: 'https://example.com', bookUrl: 'https://example.com/book/1' }), false)
  // `loc_book` 只是**书源 id**，不是 book_kind 取值；两者别混（这里锁死判据来源）
  assert.equal(isLocalBook({ sourceId: 'https://example.com', bookUrl: 'https://example.com/local/1' }), false)
})

// ---------------------------------------------------------------------------
// 书籍管理弹窗 / 清缓存弹窗：本地书**不渲染**缓存与清除缓存入口（issue #66 用户原话）：
// 「本地书根本不要有清除缓存的按钮」「缓存按钮也不该有」
// ---------------------------------------------------------------------------

function shelfBook(overrides: Partial<BookshelfItem> = {}): BookshelfItem {
  return {
    sourceId: 'loc_book',
    bookUrl: 'local://local-book-1',
    name: '本地测试书',
    tocUrl: 'local://local-book-1/toc',
    lastReadAt: 0,
    cachedChapters: 2,
    totalChapters: 2,
    cacheState: 'ready',
    completed: false,
    ...overrides,
  }
}

/** 按名字切出 `main.tsx` 里的顶层函数（到下一个顶层 `function ` 为止）。 */
function topLevelFunctionSource(source: string, name: string): string {
  const start = source.indexOf(`function ${name}(`)
  assert.ok(start > -1, `main.tsx 必须存在 ${name}`)
  const next = source.slice(start + 1).search(/\nfunction /)
  assert.ok(next > -1, `没法定位 ${name} 的结尾`)
  return source.slice(start, start + 1 + next)
}

test('书籍管理弹窗 - 本地书的「离线缓存」与「清除缓存」两行**整段条件渲染**（不是禁用）', () => {
  const mainTsx = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  const modal = topLevelFunctionSource(mainTsx, 'BookManageModal')

  // ① 「离线缓存全本 / 重新缓存 / 重试离线缓存」：onCache 那行必须被 !isLocalBook(item) 罩住
  const cacheRowAt = modal.indexOf('onClick={() => onCache()}')
  assert.ok(cacheRowAt > -1, '书籍管理弹窗必须仍有「离线缓存全本」行（网络书要用）')
  const cacheGuardAt = modal.lastIndexOf('{!isLocalBook(item) && (', cacheRowAt)
  assert.ok(cacheGuardAt > -1, '「离线缓存」行必须由 {!isLocalBook(item) && ( ... )} 条件渲染')
  // 守卫与按钮之间只能是 isCaching 三元，不能有别的闭合（证明按钮真在守卫内部）
  const between = modal.slice(cacheGuardAt, cacheRowAt)
  assert.ok(between.includes('isCaching ? ('), '「离线缓存」守卫内必须先是 isCaching 分支（缓存中/按钮二选一）')
  assert.ok(!/\)\}\s*$/.test(between.trimEnd()), '「离线缓存」按钮不得落在守卫之外')

  // ② 「清除缓存」：onClearCache 那行必须被 !isLocalBook(item) 罩住
  const clearRowAt = modal.indexOf('onClick={() => onClearCache()}')
  assert.ok(clearRowAt > -1, '书籍管理弹窗必须仍有「清除缓存」行（网络书要用）')
  const clearGuardAt = modal.lastIndexOf('{!isLocalBook(item) && (', clearRowAt)
  assert.ok(clearGuardAt > -1, '「清除缓存」行必须由 {!isLocalBook(item) && ( ... )} 条件渲染')
  // 守卫必须真的**闭合在「移出书架」之前**（否则等于把两行都罩住 / 或压根没罩）
  const clearEnd = modal.indexOf('</button>', clearRowAt)
  assert.ok(clearEnd > clearRowAt, '没法定位「清除缓存」按钮的结尾')
  const removeStart = modal.indexOf('<button className="manage-action-row danger"', clearEnd + '</button>'.length)
  assert.ok(removeStart > clearEnd, '没法定位「移出书架」按钮')
  assert.equal(
    modal.slice(clearEnd + '</button>'.length, removeStart).trim(),
    ')}',
    '「清除缓存」的守卫必须闭合在它自己这一行之后、「移出书架」之前',
  )

  // ③ 禁用是**错误**的收口方式：用户要的是"不要有"，不是"点了没反应"
  const cacheRowStart = modal.lastIndexOf('<button', cacheRowAt)
  const clearRowStart = modal.lastIndexOf('<button', clearRowAt)
  assert.ok(!/disabled=/.test(modal.slice(cacheRowStart, cacheRowAt)), '「离线缓存」行不得用 disabled 冒充隐藏')
  assert.ok(!/disabled=/.test(modal.slice(clearRowStart, clearRowAt)), '「清除缓存」行不得用 disabled 冒充隐藏')

  // ④ 本地书仍保留「重新解析本地文件」（那是本地书**唯一**有效的"重建正文"入口）
  assert.ok(/\{isLocalBook\(item\) && \(/.test(modal), '本地书必须保留「重新解析本地文件」入口')
})

test('清缓存范围弹窗 - 本地书永远进不了 clearCacheScope 层（reducer 拦截，防 UI 绕过）', () => {
  const local = shelfBook()
  const online = shelfBook({ sourceId: 'https://example.com', bookUrl: 'https://example.com/book/1', tocUrl: 'https://example.com/toc' })

  const manageLocal = shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openManage', item: local })
  assert.equal(manageLocal.layer, 'manage')
  const stillManage = shelfModalReducer(manageLocal, { type: 'openClearCacheScope' })
  assert.equal(stillManage.layer, 'manage', '本地书点「清除缓存」绝不能切到范围弹窗（状态必须原地不动）')
  assert.equal('item' in stillManage ? stillManage.item.bookUrl : '', local.bookUrl, '原地不动时必须还是同一本书')

  // 网络书照旧可叠加二级（回归保护：拦截只针对本地书）
  const manageOnline = shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openManage', item: online })
  assert.equal(shelfModalReducer(manageOnline, { type: 'openClearCacheScope' }).layer, 'clearCacheScope')

  // `local://` 路径但 sourceId 不是 loc_book 也算本地书（与服务端 BookKind.isLocalRef 同源）
  const weirdLocal = shelfBook({ sourceId: 'legacy-local', bookUrl: 'local://x/chapter_1' })
  const manageWeird = shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openManage', item: weirdLocal })
  assert.equal(shelfModalReducer(manageWeird, { type: 'openClearCacheScope' }).layer, 'manage')
})

test('阅读器目录抽屉 - 本地书整段不渲染缓存区（4 个 cache-range-btn + 自定义范围 + 下载到本设备）', () => {
  const reader = readFileSync(new URL('../src/ReaderScreen.tsx', import.meta.url), 'utf8').replace(/\r\n/g, '\n')

  // 整个 `drawer-cache-section` 必须被 `{!localBook && (` 罩住（Guard 与元素之间只允许注释）
  assert.ok(
    /\{!localBook && \(\s*(?:\/\*[\s\S]*?\*\/\s*)?<div className="drawer-cache-section">/.test(reader),
    '阅读器目录抽屉的整个缓存区必须由 {!localBook && ( ... )} 条件渲染',
  )
  // 四类缓存按钮 / 自定义范围 / 下载到本设备都必须仍在**同一个**缓存区里（不能有第二个未加守卫的副本）
  assert.equal((reader.match(/className="cache-range-btn"/g) ?? []).length, 4, '四个范围按钮必须都还在缓存区里')
  assert.equal((reader.match(/className="cache-local-sync-row"/g) ?? []).length, 1, '「下载到本设备」必须只有一处')
  assert.equal((reader.match(/className="drawer-cache-section"/g) ?? []).length, 1, '缓存区只能有一处（否则守卫会漏掉一处）')
  // 本地书判据必须复用共享模块（与服务端同源）
  assert.ok(/import \{ isLocalBook \} from '\.\/localBook'/.test(reader), 'ReaderScreen 必须复用 localBook 模块的判据')
  assert.ok(
    /const localBook = isLocalBook\(\{ sourceId: currentBook\.details\.sourceId, bookUrl: currentBook\.bookUrl \}\)/.test(reader),
    '阅读器必须按当前书身份算出 localBook',
  )
  // 缓存区之外的动作（换源 / 加书架）不得被顺手隐藏
  const footerActions = reader.slice(reader.indexOf('<div className="drawer-footer-actions">'))
  assert.ok(footerActions.includes('reader.switchSource') && footerActions.includes('reader.addToShelf'), '换源 / 加书架必须保持原样')
})
