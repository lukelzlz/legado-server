import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  bookmarkOffsetFromPosition,
  bookmarkPositionFraction,
  bookmarkSnippet,
} from '../src/bookmarkPos'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = (name: string) => fs.readFileSync(path.resolve(here, '../src', name), 'utf-8')

const readerScreen = src('ReaderScreen.tsx')
const bookmarkModal = src('BookmarkListModal.tsx')
const apiTs = src('api.ts')
const stylesCss = src('styles.css')
const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const

// ---------------------------------------------------------------------------
// 1. 位置换算：书签存字符偏移（对齐备份 bookmark.json 的 chapterPos），
//    跳转时换回滚动比例，两条公式必须互逆。
// ---------------------------------------------------------------------------
test('bookmarkPos - offset and fraction round-trip on the same chapter text', () => {
  const textLength = 5000
  for (const position of [0, 0.013, 0.25, 0.5, 0.987, 1]) {
    const offset = bookmarkOffsetFromPosition(position, textLength)
    const back = bookmarkPositionFraction(offset, textLength)
    // 取整误差最多一个字符 ⇒ 比例误差上限 1/正文字数
    assert.ok(Math.abs(back - position) <= 1 / textLength + 1e-9, `round-trip drift at ${position}: ${back}`)
  }
})

test('bookmarkPos - clamps out-of-range input instead of producing NaN or负值', () => {
  assert.equal(bookmarkOffsetFromPosition(-3, 100), 0)
  assert.equal(bookmarkOffsetFromPosition(9, 100), 100)
  assert.equal(bookmarkOffsetFromPosition(Number.NaN, 100), 0)
  assert.equal(bookmarkOffsetFromPosition(0.5, 0), 0, '空正文只能落在 0')
  assert.equal(bookmarkPositionFraction(-20, 100), 0)
  assert.equal(bookmarkPositionFraction(520, 100), 1)
  assert.equal(bookmarkPositionFraction(Number.NaN, 100), 0)
  assert.equal(bookmarkPositionFraction(50, 0), 0)
})

test('bookmarkPos - snippet starts at the anchor and folds whitespace', () => {
  const text = '　　第一段内容。\n\n　　第二段内容，这里开始是书签所在的段落。'
  const offset = text.indexOf('第二段')
  const snippet = bookmarkSnippet(text, offset, 12)
  assert.equal(snippet.includes('\n'), false, '摘要不能带换行（列表里会被行高撑开）')
  assert.equal(snippet.startsWith('第二段'), true, `摘要应从书签处开始：${snippet}`)
  assert.ok(snippet.length <= 12)
})

test('bookmarkPos - snippet falls back to the previous window at chapter end', () => {
  const text = '正文正文正文正文'
  const snippet = bookmarkSnippet(text, text.length, 5)
  assert.equal(snippet, '文正文正文', '末尾锚点必须向前补，不能给空摘要')
  assert.equal(bookmarkSnippet('', 10).length, 0)
})

// ---------------------------------------------------------------------------
// 2. 阅读器接线：顶栏按钮在「设置」前面、底部悬浮栏有「添加书签」、
//    跳转走 pending + 对齐 effect（不另写一套定位逻辑）。
// ---------------------------------------------------------------------------
test('ReaderScreen - 顶栏在设置按钮前插入书签按钮', () => {
  const header = readerScreen.slice(
    readerScreen.indexOf('<div className="reader-header-actions">'),
    readerScreen.indexOf('</header>'),
  )
  assert.ok(header.length > 0, '必须能切出顶栏操作区')
  const bookmarkIndex = header.indexOf(`t('reader.bookmarks'`)
  const settingsIndex = header.indexOf(`t('reader.settings'`)
  assert.ok(bookmarkIndex >= 0, '顶栏必须有书签按钮')
  assert.ok(settingsIndex >= 0, '顶栏必须仍有设置按钮')
  assert.ok(bookmarkIndex < settingsIndex, '书签按钮必须在设置按钮之前')
  assert.match(header, /<IconButton label=\{t\('reader\.bookmarks'[^}]*\} icon="bookmark" onClick=\{\(\) => setShowBookmarks\(true\)\} \/>/)
})

test('ReaderScreen - 底部悬浮栏新增「添加书签」，并且是 5 格均分', () => {
  const navStart = readerScreen.indexOf('<nav className="mobile-reader-nav">')
  assert.ok(navStart > 0, '必须有底部导航')
  const nav = readerScreen.slice(navStart, readerScreen.indexOf('</nav>', navStart))
  assert.ok(nav.includes(`t('reader.addBookmark', '添加书签')`), '底部悬浮栏必须有添加书签')
  assert.ok(nav.includes('addBookmarkHere()'), '按钮必须真的去加书签')
  const buttonCount = (nav.match(/mobile-nav-btn/g) ?? []).length
  assert.equal(buttonCount, 5, `底部悬浮栏应为 5 个按钮，实际 ${buttonCount}`)
  assert.match(stylesCss, /\.mobile-reader-nav\s*\{[^}]*grid-template-columns:\s*repeat\(5,\s*1fr\)/)
})

test('ReaderScreen - 跳转书签：章名优先匹配 + 偏移在正文到手时换算成比例', () => {
  assert.match(readerScreen, /const jumpToBookmark = useCallback\(/)
  assert.match(
    readerScreen,
    /matchBestChapter\(currentBook\.chapters, bookmark\.chapterName \?\? undefined, bookmark\.chapterIndex\)/,
    '书签的章必须按「章名优先、下标兜底」重新对齐（换源/目录重排后序号会漂）',
  )
  // 跨章跳转：偏移挂到 pending，等这一章正文到手才算得出版本比例
  assert.match(readerScreen, /pendingBookmarkJumpRef\.current = \{ chapterIndex: target, chapterPos: bookmark\.chapterPos \}/)
  assert.match(
    readerScreen,
    /position = bookmarkPositionFraction\(pendingJump\.chapterPos, nextContent\.length\)/,
  )
  // 同一章跳转：内容没变，靠 tick 让对齐 effect 重跑
  assert.match(readerScreen, /initialPagePositionRef\.current = fraction\s*\n\s*setBookmarkJumpTick\(tick => tick \+ 1\)/)
  assert.match(readerScreen, /\[bookmarkJumpTick, chapterIndex, content, loading, scrollStream, settings\.pageMode\]/)
})

test('ReaderScreen - 添加书签用当前比例 × 正文长度换算成章内偏移并带原文摘要', () => {
  assert.match(readerScreen, /const addBookmarkHere = useCallback\(/)
  assert.match(readerScreen, /const offset = bookmarkOffsetFromPosition\(position, content\.length\)/)
  assert.match(readerScreen, /chapterPos: offset/)
  assert.match(readerScreen, /bookText: bookmarkSnippet\(content, offset\)/)
  assert.match(readerScreen, /toast\.success\(t\('reader\.bookmarkAdded', '已添加书签'\)\)/)
  // 正文还没到手时不能写出一条位置错乱的书签
  assert.match(readerScreen, /if \(!chapter \|\| loading \|\| !content\)/)
})

test('ReaderScreen - 书签弹窗挂载在阅读器内，跳转后按 Close 收起', () => {
  assert.match(readerScreen, /import \{ BookmarkListModal \} from '\.\/BookmarkListModal'/)
  assert.match(readerScreen, /\{showBookmarks && \(\s*<BookmarkListModal/)
  assert.match(readerScreen, /onJump=\{jumpToBookmark\}/)
  assert.match(readerScreen, /onAddHere=\{addBookmarkHere\}/)
})

// ---------------------------------------------------------------------------
// 3. 列表弹窗：不嵌套第二层遮罩，增删改用服务端返回的权威行。
// ---------------------------------------------------------------------------
test('BookmarkListModal - 列表/编辑/删除都在同一层弹窗内（不嵌套 modal-backdrop）', () => {
  const backdrops = (bookmarkModal.match(/className="modal-backdrop"/g) ?? []).length
  assert.equal(backdrops, 1, '嵌套 modal-backdrop 会出现幽灵遮罩与层级错乱')
  assert.match(bookmarkModal, /const \[editingId, setEditingId\] = useState<number \| null>\(null\)/)
  assert.match(bookmarkModal, /const \[confirmId, setConfirmId\] = useState<number \| null>\(null\)/)
})

test('BookmarkListModal - 四个动作都接到真实接口', () => {
  assert.match(bookmarkModal, /await api\.bookmarks\(bookName, bookAuthor\)/)
  assert.match(bookmarkModal, /await onAddHere\(\)/)
  assert.match(bookmarkModal, /await api\.updateBookmark\(item\.id, \{ bookText: draftText, content: draftNote \}\)/)
  assert.match(bookmarkModal, /await api\.deleteBookmark\(item\.id\)/)
  assert.match(bookmarkModal, /onJump\(item\)/, '点击一条必须能跳转')
})

test('api.ts - 书签四接口的方法与路径', () => {
  assert.match(apiTs, /bookmarks: \(bookName: string, bookAuthor\?: string \| null\) =>/)
  assert.match(apiTs, /`\/api\/bookmarks\?bookName=\$\{encodeURIComponent\(bookName\)\}/)
  assert.match(apiTs, /request<BookmarkRecord>\('\/api\/bookmarks', \{ method: 'POST'/)
  assert.match(apiTs, /`\/api\/bookmarks\/\$\{id\}`, \{ method: 'PUT'/)
  assert.match(apiTs, /deleteBookmark: \(id: number\) => request<void>\(`\/api\/bookmarks\/\$\{id\}`, \{ method: 'DELETE' \}\)/)
})

// ---------------------------------------------------------------------------
// 4. 样式与文案
// ---------------------------------------------------------------------------
test('styles.css - 书签弹窗与列表的关键样式存在', () => {
  assert.match(stylesCss, /\.bookmark-dialog\s*\{[^}]*max-height:\s*min\(82vh, 700px\)/)
  assert.match(stylesCss, /\.bookmark-item\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) auto;/)
  assert.match(stylesCss, /\.bookmark-item-actions \.shelf-remove\s*\{[^}]*color:\s*var\(--danger\)/)
  assert.match(stylesCss, /\.bookmark-text\s*\{[^}]*-webkit-line-clamp:\s*2/)
})

test('styles.css - 手机端顶栏容纳第三颗按钮（操作区自适应）', () => {
  assert.match(stylesCss, /@media \(max-width: 720px\) \{\s*\.reader-header \{ grid-template-columns: 80px minmax\(0, 1fr\) auto; \}/)
  assert.match(stylesCss, /@media \(max-width: 420px\) \{\s*\.reader-header \{ grid-template-columns: 40px minmax\(0, 1fr\) auto; \}/)
})

test('i18n - 书签相关 key 在四个语言里都齐全且非空', () => {
  const keys = [
    'bookmarks',
    'addBookmark',
    'noBookmarks',
    'bookmarkAdded',
    'bookmarkCount',
    'bookmarkNote',
    'bookmarkOriginalText',
    'bookmarkNoText',
    'bookmarkLoadFailed',
    'bookmarkNeedsContent',
    'jumpToBookmark',
    'editBookmark',
    'deleteBookmarkTitle',
    'deleteBookmarkMessage',
  ]
  for (const locale of LOCALES) {
    const data = JSON.parse(fs.readFileSync(path.resolve(here, `../src/i18n/locales/${locale}.json`), 'utf-8')) as
      { reader: Record<string, string> }
    for (const key of keys) {
      const value = data.reader[key]
      assert.equal(typeof value, 'string', `${locale} 缺少 reader.${key}`)
      assert.ok((value ?? '').trim().length > 0, `${locale} 的 reader.${key} 不能为空`)
    }
  }
})
