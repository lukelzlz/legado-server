import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  bookKeyOf,
  filterBookmarkBooks,
  filterBookmarkRecords,
  sortBookmarks,
} from '../src/bookmarkGroups'
import type { BookmarkBook, BookmarkRecord } from '../src/api'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = (name: string) => fs.readFileSync(path.resolve(here, '../src', name), 'utf-8')

const page = src('BookmarksPage.tsx')
const bookmarksPage = page
const rulesPage = src('ReplaceRulesPage.tsx')
const readerScreen = src('ReaderScreen.tsx')
const header = src('AppHeader.tsx')
const mainTsx = src('main.tsx')
const apiTs = src('api.ts')
const stylesCss = src('styles.css')
const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const

const mark = (patch: Partial<BookmarkRecord>): BookmarkRecord => ({
  id: 1,
  bookName: '书',
  bookAuthor: '作者',
  chapterIndex: 0,
  chapterName: '第一章',
  chapterPos: 0,
  bookText: '正文',
  content: '',
  createdAt: 0,
  ...patch,
})

const book = (patch: Partial<BookmarkBook>): BookmarkBook => ({
  bookName: '书',
  bookAuthor: '作者',
  count: 1,
  lastCreatedAt: 0,
  ...patch,
})

// ---------------------------------------------------------------------------
// 1. 纯函数：书的身份键必须与服务端一致（NULL 与空串是同一本）
// ---------------------------------------------------------------------------
test('bookKeyOf - 作者 null 与空串视为同一本书（与服务端 coalesce 口径一致）', () => {
  assert.equal(bookKeyOf({ bookName: '书', bookAuthor: null }), bookKeyOf({ bookName: '书', bookAuthor: '' }))
  assert.equal(bookKeyOf({ bookName: '书' }), bookKeyOf({ bookName: '书', bookAuthor: undefined }))
  assert.notEqual(bookKeyOf({ bookName: '书', bookAuthor: 'A' }), bookKeyOf({ bookName: '书', bookAuthor: 'B' }))
})

test('sortBookmarks - 按章节与章内位置稳定排序，且不改原数组', () => {
  const input = [
    mark({ id: 3, chapterIndex: 2, chapterPos: 0 }),
    mark({ id: 1, chapterIndex: 0, chapterPos: 500 }),
    mark({ id: 2, chapterIndex: 0, chapterPos: 10 }),
  ]
  const sorted = sortBookmarks(input)
  assert.deepEqual(sorted.map(item => item.id), [2, 1, 3])
  assert.deepEqual(input.map(item => item.id), [3, 1, 2], '不能就地改动调用方的数组')
})

test('filterBookmarkBooks - 书名 / 作者命中，空白关键字不过滤', () => {
  const books = [
    book({ bookName: '十日终焉', bookAuthor: '杀虫队队员' }),
    book({ bookName: '被废修为后', bookAuthor: null }),
  ]
  assert.equal(filterBookmarkBooks(books, '').length, 2)
  assert.equal(filterBookmarkBooks(books, '   ').length, 2, '纯空白等于不过滤')
  assert.deepEqual(filterBookmarkBooks(books, '终焉').map(item => item.bookName), ['十日终焉'])
  assert.deepEqual(filterBookmarkBooks(books, '杀虫').map(item => item.bookName), ['十日终焉'])
  assert.equal(filterBookmarkBooks(books, '不存在的书').length, 0)
})

test('filterBookmarkRecords - 章名 / 原文 / 备注任一命中', () => {
  const marks = [
    mark({ id: 1, chapterName: '第一百章 决战', bookText: '他抬起头', content: '' }),
    mark({ id: 2, chapterName: '第一章', bookText: '风雪很大', content: '这里有伏笔' }),
  ]
  assert.deepEqual(filterBookmarkRecords(marks, '决战').map(item => item.id), [1])
  assert.deepEqual(filterBookmarkRecords(marks, '风雪').map(item => item.id), [2])
  assert.deepEqual(filterBookmarkRecords(marks, '伏笔').map(item => item.id), [2])
  assert.equal(filterBookmarkRecords(marks, '').length, 2)
})

// ---------------------------------------------------------------------------
// 2. 页面契约：左书右签、删完重拉左栏（书才会消失）、两侧都能批量
// ---------------------------------------------------------------------------
test('BookmarksPage - 复用替换规则页的布局类，并带 bookmarks-page 标记', () => {
  assert.match(page, /className=\{`rules-page-container bookmarks-page/)
  assert.match(page, /<aside className="rules-sidebar">/)
  assert.match(page, /<section className="rules-content-area">/)
  assert.match(page, /className="rules-list"/)
  // 左栏顺序：标题 → 搜索 → 列表；右栏：选中书的书签
  assert.ok(page.indexOf('rules-sidebar') < page.indexOf('rules-content-area'))
})

test('BookmarksPage - 左栏显示书名 / 作者 / 封面 / 条数', () => {
  assert.match(page, /<BookCover\b/)
  assert.match(page, /coverKey=\{book\.coverKey\}/)
  assert.match(page, /coverUrl=\{book\.coverUrl\}/)
  assert.match(page, /\{book\.bookAuthor \|\| t\('common\.unknownAuthor'/)
  assert.match(page, /bookmarks\.countBadge/)
})

test('BookmarksPage - 删书签后必须重拉左栏（书签删光则该书从左栏消失）', () => {
  // 单条删除、右栏批量、删本书全部：三条路径都要 `loadOverview`
  const removeMarkBody = page.slice(page.indexOf('const removeMark'), page.indexOf('const saveMark'))
  assert.match(removeMarkBody, /await loadOverview\(selectedKey\)/)
  const batchMarkBody = page.slice(page.indexOf('const deleteSelectedMarks'), page.indexOf('const deleteAllMarksOfBook'))
  assert.match(batchMarkBody, /await loadOverview\(selectedKey\)/)
  const allBody = page.slice(page.indexOf('const deleteAllMarksOfBook'), page.indexOf('const deleteSelectedBooks'))
  assert.match(allBody, /await loadOverview\(null\)/)
})

test('BookmarksPage - 两侧都能批量管理（左按书、右按 id）', () => {
  assert.match(page, /api\.deleteBookmarksBatch\(\{ ids: Array\.from\(rightSelected\) \}\)/)
  assert.match(page, /books: targets\.map\(book => \(\{ bookName: book\.bookName, bookAuthor: book\.bookAuthor \}\)\)/)
  assert.match(page, /bookmarks\.batchDeleteBooks/)
  assert.match(page, /bookmarks\.deleteSelected/)
  // 两侧各自的批量工具栏
  assert.equal((page.match(/className="source-batch-bar"/g) ?? []).length, 2)
})

test('api.ts - 概览与批量删除接口的方法与路径', () => {
  assert.match(apiTs, /bookmarkOverview: \(\) => request<\{ items: BookmarkBook\[\] \}>\('\/api\/bookmarks\/overview'\)/)
  assert.match(apiTs, /deleteBookmarksBatch: \(payload: \{ ids\?: number\[\]; books\?: Array<\{ bookName: string; bookAuthor\?: string \| null \}> \}\)/)
  assert.match(apiTs, /request<\{ deleted: number \}>\('\/api\/bookmarks\/delete-batch', \{ method: 'POST'/)
})

test('导航 - 顶栏新增「书签」一级入口，且页面可路由', () => {
  assert.match(header, /\| 'bookmarks'/)
  assert.match(header, /page === 'bookmarks' \? 'active' : ''/)
  assert.match(header, /onNavigate\('bookmarks'\)/)
  assert.match(mainTsx, /location\.hash === '#bookmarks' \? 'bookmarks'/)
  assert.match(mainTsx, /page === 'bookmarks' \? \(\s*<BookmarksPage onOpenBookmark=\{/)
  assert.ok(
    header.indexOf("onNavigate('shelf')") < header.indexOf("onNavigate('bookmarks')"),
    '书签入口应排在书架之后',
  )
})

// ---------------------------------------------------------------------------
// 3. 窄屏：右栏不能被规则页的 `display:none` 吃掉（否则手机上删不了单条书签）
// ---------------------------------------------------------------------------
test('styles.css - 窄屏下书签页的右栏必须仍然可见', () => {
  assert.match(stylesCss, /@media \(max-width: 768px\) \{\s*\.rules-page-container\.bookmarks-page \{ grid-template-columns: 1fr; \}/)
  assert.match(stylesCss, /\.rules-page-container\.bookmarks-page \.rules-content-area \{ display: block; \}/)
  // 规则页自身的隐藏规则仍在（只对规则页生效）
  assert.match(stylesCss, /\.rules-content-area \{\s*display: none;/)
})

test('styles.css - 书签页特有样式存在，且不含写死颜色（主题切换零适配）', () => {
  const start = stylesCss.indexOf('「书签」一级页面')
  const end = stylesCss.indexOf('漫画阅读器（方案 E）')
  assert.ok(start > 0 && end > start, '必须能找到书签页样式块')
  const block = stylesCss.slice(start, end)
  assert.match(block, /\.bookmark-book-cover\s*\{/)
  assert.match(block, /\.bookmark-book-count\s*\{/)
  assert.equal(/#[0-9a-fA-F]{3,8}\b/.test(block), false, '书签页样式严禁写死十六进制颜色')
  assert.equal(/\brgba?\(/.test(block), false, '书签页样式严禁写死 rgb()/rgba() 颜色')
})

// ---------------------------------------------------------------------------
// 4. 四语言齐全
// ---------------------------------------------------------------------------
test('i18n - 书签页的 key 在四个语言里都齐全且非空', () => {
  const keys = [
    'title',
    'loadFailed',
    'searchBookPlaceholder',
    'searchMarkPlaceholder',
    'emptyBooks',
    'noMatchingBooks',
    'noMatchingMarks',
    'countBadge',
    'selectBookFirst',
    'batchManage',
    'batchBooksHint',
    'batchBooksAria',
    'batchMarksAria',
    'deleteBookMarks',
    'deleteSelected',
    'batchDeleteBooks',
    'deleteMarkConfirm',
    'batchDeleteMarkConfirm',
    'deleteBookConfirm',
    'batchDeleteBookConfirm',
    'marksDeleted',
    // 封面链路（请求封面 / 手动设置 / 无封面标记）与「点书签跳转」
    'requestCovers',
    'requestCoversHint',
    'coverTitle',
    'coverUpload',
    'coverRefetch',
    'coverFromSources',
    'coverRemove',
    'coverMissing',
    'coverUpdated',
    'openMark',
    'notInShelf',
  ]
  for (const locale of LOCALES) {
    const data = JSON.parse(fs.readFileSync(path.resolve(here, `../src/i18n/locales/${locale}.json`), 'utf-8')) as
      { bookmarks: Record<string, string>; header: Record<string, string> }
    for (const key of keys) {
      const value = data.bookmarks[key]
      assert.equal(typeof value, 'string', `${locale} 缺少 bookmarks.${key}`)
      assert.ok((value ?? '').trim().length > 0, `${locale} 的 bookmarks.${key} 不能为空`)
    }
    assert.ok((data.header.bookmarks ?? '').trim().length > 0, `${locale} 缺少 header.bookmarks`)
  }
})

// ---------------------------------------------------------------------------
// 5. 批量栏三个真实缺陷（用户实测报障）
// ---------------------------------------------------------------------------

/**
 * 「已选 {{count}} 项 0 / 24」：`common.selected` 里带 `{{count}}` 但调用处只把它当**标签**用
 * （后面紧跟 `<strong>{已选数量}</strong> / 总数`），于是模板占位符原样漏到界面上。
 * 该 key 被规则页 / 书签页 / 书源页三处共用 ⇒ 值必须是不带占位符的纯标签。
 */
test('批量栏 - common.selected 不得含 {{count}} 占位符（否则界面漏出模板原文）', () => {
  for (const locale of LOCALES) {
    const data = JSON.parse(fs.readFileSync(path.resolve(here, `../src/i18n/locales/${locale}.json`), 'utf-8')) as
      { common: Record<string, string> }
    const value = data.common.selected
    assert.equal(typeof value, 'string', `${locale} 缺少 common.selected`)
    assert.equal(/\{\{/.test(value ?? ''), false, `${locale} 的 common.selected 不能含占位符：${value}`)
  }
  // 三处调用后面都紧跟真实计数，不再传 count
  for (const source of [rulesPage, bookmarksPage, mainTsx]) {
    assert.match(source, /t\('common\.selected', '已选'\)\} <strong>\{/)
  }
})

/**
 * 书签页有**两个**批量管理（左=书，右=书签）。同时打开会出现两条悬浮栏压屏 ⇒ 必须互斥：
 * 打开任一侧都要把另一侧收起并清空其选择。
 */
test('书签批量 - 两个批量管理互斥：开一侧立刻收起另一侧', () => {
  const left = bookmarksPage.slice(bookmarksPage.indexOf('const openLeftBatch'), bookmarksPage.indexOf('const openRightBatch'))
  assert.match(left, /setLeftBatch\(true\)/)
  assert.match(left, /setRightBatch\(false\)/, '打开左栏批量时必须收起右栏批量')
  assert.match(left, /setRightSelected\(new Set\(\)\)/, '打开左栏批量时必须清空右栏选择')

  const right = bookmarksPage.slice(bookmarksPage.indexOf('const openRightBatch'), bookmarksPage.indexOf('const exitBatch'))
  assert.match(right, /setRightBatch\(true\)/)
  assert.match(right, /setLeftBatch\(false\)/, '打开右栏批量时必须收起左栏批量')
  assert.match(right, /setLeftSelected\(new Set\(\)\)/, '打开右栏批量时必须清空左栏选择')
})

/** 批量栏必须有「取消」按钮（用户要求：书签与规则两处都要）。 */
test('批量栏 - 书签页与规则页都提供「取消」按钮', () => {
  for (const [name, source] of [['书签页', bookmarksPage], ['规则页', rulesPage]] as const) {
    assert.match(source, /\{t\('common\.cancel', '取消'\)\}/, `${name}的批量栏缺少取消按钮`)
  }
})

/** 点书签 → 打开阅读器并跳到那条书签的章节/位置。 */
test('书签跳转 - 点书签回传 chapterIndex/chapterPos，阅读器开屏即定位', () => {
  assert.match(bookmarksPage, /onOpenBookmark\?\.\(\{/)
  assert.match(bookmarksPage, /chapterIndex:\s*item\.chapterIndex/)
  assert.match(bookmarksPage, /chapterPos:\s*item\.chapterPos/)
  assert.match(mainTsx, /const openBookmarkTarget = async/)
  assert.match(mainTsx, /startBookmark=\{reader\.bookmark\}/)
  assert.match(readerScreen, /startBookmark\?: \{ chapterIndex: number; chapterPos: number \}/)
  assert.match(
    readerScreen,
    /pendingBookmarkJumpRef = useRef<\{ chapterIndex: number; chapterPos: number \} \| null>\(startBookmark \?\? null\)/,
    '开屏必须把书签落点交给既有的「待落点」通道',
  )
})

// ---------------------------------------------------------------------------
// 6. 封面链路（请求封面 / 手动设置 / 5 次标记）
// ---------------------------------------------------------------------------
test('封面 - 书签页可请求封面、点封面手动设置（书源候选或上传本地图片）', () => {
  assert.match(bookmarksPage, /api\.refreshBookCovers\(/)
  assert.match(bookmarksPage, /api\.bookCoverCandidates\(/)
  assert.match(bookmarksPage, /api\.attachBookCover\(/)
  assert.match(bookmarksPage, /api\.uploadCover\(/)
  // 手动设置弹层：书源候选 + 上传本地图片 + 移除
  assert.match(bookmarksPage, /coverFromSources/)
  assert.match(bookmarksPage, /type="file"/)
  assert.match(bookmarksPage, /remove: true/)
  // 「无封面·已停止自动补抓」要有可见标记
  // 按作者名搜索：按钮 + 带 byAuthor 的请求 + 作者为空时禁用
  assert.match(bookmarksPage, /coverSearchByAuthor/)
  assert.match(bookmarksPage, /loadCandidates\(coverFor, !authorOnly\)/)
  // 按钮要和「上传本地图片 / 按书名重新获取 / 移除封面」放在同一列（bookmark-cover-actions）里
  assert.match(bookmarksPage, /bookmark-cover-actions[\s\S]{0,3000}coverSearchByAuthor/)
  assert.doesNotMatch(bookmarksPage, /bookmark-cover-candidates-head/)
  assert.match(bookmarksPage, /authorOnly/)
  assert.match(bookmarksPage, /bookmarks\.coverNoAuthor/)
  assert.match(bookmarksPage, /book\.coverMissing && !book\.coverKey/)
})

test('封面 - 接口契约：批量补抓 / 候选列表 / 挂载封面', () => {
  assert.match(apiTs, /refreshBookCovers: \(payload: \{[\s\S]{0,200}?\) =>\s*request<BookCoverRefreshResult>\('\/api\/covers\/refresh'/)
  assert.match(apiTs, /attachBookCover: \(payload: \{[\s\S]{0,400}?'\/api\/covers\/book'/)
  assert.match(apiTs, /bookCoverCandidates: \(bookName: string, bookAuthor\?: string \| null, byAuthor = false\) =>[\s\S]{0,320}?\/api\/covers\/candidates\?bookName=/)
  // 按作者名搜索要真的把 byAuthor=true 带给服务端
  assert.match(apiTs, /byAuthor \? '&byAuthor=true' : ''/)
})

