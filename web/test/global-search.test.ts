import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 全局搜索（书架 / 目录 / 正文）的前端契约。
 *
 * 写法与 `bookmark-page.test.ts` 一致：对**源码**做断言。
 * 弹窗走 `createPortal(document.body)`，在 node 里没有 DOM 可渲染，
 * 因此这里锁的是「接线是否接上、文案 key 是否存在、顺序是否对」这些真正会回归的点。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
// 统一换行成 LF 再断言：源码里有「按行切片」的断言（如 indexOf('return (\n    <main')），
// Windows 检出（CRLF）会切不出预期片段、CI（LF）会切出空串 —— 同一份测试必须与平台换行无关。
const src = (name: string) =>
  fs.readFileSync(path.resolve(here, '../src', name), 'utf-8').replace(/\r\n/g, '\n')

const modal = src('GlobalSearchModal.tsx')
const mainTsx = src('main.tsx')
const reader = src('ReaderScreen.tsx')
const apiTs = src('api.ts')
const styles = src('styles.css')
const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
const locales = Object.fromEntries(
  LOCALES.map(locale => [locale, JSON.parse(src(`i18n/locales/${locale}.json`)) as Record<string, any>]),
)

// ---------------------------------------------------------------------------
// 1. API 契约：路径不能与「全网搜索」撞车
// ---------------------------------------------------------------------------
test('api.ts - 本地搜索走 /api/search/local，绝不覆盖 /api/search', () => {
  // `/api/search` 已经被多书源全网搜索占用（api.search），两个 handler 挂同一节点会互相打架
  assert.match(apiTs, /request<LocalSearchResponse>\('\/api\/search\/local'/)
  assert.match(apiTs, /searchLocal: \(/)
  // 三模式的类型必须在客户端声明（强类型 DTO，禁止 any）
  assert.match(apiTs, /export type LocalSearchMode = 'shelf' \| 'toc' \| 'content'/)
  assert.match(apiTs, /export type LocalSearchHit = \{/)
  assert.match(apiTs, /export type LocalSearchResponse = \{ items: LocalSearchHit\[\]; total: number; truncated: boolean \}/)
  // 旧的 search 必须仍然指向 /api/search（回归守卫）
  assert.match(apiTs, /request<SearchResult\[\]\>\('\/api\/search', \{ method: 'POST'/)
})

// ---------------------------------------------------------------------------
// 2. 弹窗本体：三 tab、debounce、三种结果的展示、portal + ESC
// ---------------------------------------------------------------------------
test('GlobalSearchModal - 三种模式、portal 到 body 且显式绑主题类', () => {
  assert.match(modal, /modes = \['shelf', 'toc', 'content'\]/)
  assert.match(modal, /mode === value \? 'active' : ''/)
  assert.match(modal, /t\('search\.shelf', '书架'\)/)
  assert.match(modal, /t\('search\.toc', '目录'\)/)
  assert.match(modal, /t\('search\.content', '正文'\)/)
  // 浮层必须 portal 到 body，并显式补 theme-*（否则主题变量全丢）
  assert.match(modal, /createPortal\(/)
  assert.match(modal, /document\.body/)
  assert.match(modal, /modal-backdrop global-search-backdrop theme-\$\{theme\}/)
  // ESC ≡ 关闭
  assert.match(modal, /event\.key === 'Escape'\) onClose\(\)/)
  // 关闭按钮
  assert.match(modal, /className="subtle-button close-btn" onClick=\{onClose\}/)
})

test('GlobalSearchModal - 输入 300ms debounce，且旧请求会被 abort', () => {
  assert.match(modal, /const SEARCH_DEBOUNCE_MS = 300/)
  assert.match(modal, /AbortController\(\)/)
  assert.match(modal, /window\.setTimeout\(\(\) => \{[\s\S]*?SEARCH_DEBOUNCE_MS\)/)
  assert.match(modal, /controller\.abort\(\)/)
  // 空查询不发请求（服务端也会回空，但没必要占一次往返）
  assert.match(modal, /if \(!trimmed\) \{/)
})

test('GlobalSearchModal - 请求带上模式与单本书 scope', () => {
  assert.match(modal, /api\.searchLocal\(/)
  assert.match(modal, /mode,\s*\n\s*query: trimmed/)
  assert.match(modal, /sourceId: sourceId \|\| undefined/)
  assert.match(modal, /bookUrl: bookUrl \|\| undefined/)
})

test('GlobalSearchModal - 三种结果各有对应展示（封面 / 章名 / 高亮 snippet）', () => {
  assert.match(modal, /hit\.kind === 'shelf' \?/)
  assert.match(modal, /<BookCover coverKey=\{hit\.coverKey\}/)
  assert.match(modal, /global-search-chapter/)
  assert.match(modal, /highlightSnippet\(hit\.snippet, query\)/)
  assert.match(modal, /<mark key=\{index\} className="global-search-mark">/)
})

test('GlobalSearchModal - 空态 / 加载态 / 错误态三件套都在', () => {
  assert.match(modal, /t\('search\.loading', '正在搜索…'\)/)
  assert.match(modal, /t\('search\.empty', '没有找到匹配的内容'\)/)
  assert.match(modal, /className="reader-error">\{error\}/)
})

// ---------------------------------------------------------------------------
// 3. 书架页：顶部搜索按钮 → 弹窗 → 开书 / 跳章 / 不在架提示
// ---------------------------------------------------------------------------
test('书架页顶栏有「搜索」按钮，点击打开同一个弹窗', () => {
  assert.match(mainTsx, /className="shelf-search-btn"/)
  assert.match(mainTsx, /onClick=\{\(\) => setShowGlobalSearch\(true\)\}/)
  assert.match(mainTsx, /<GlobalSearchModal\s*\n\s*theme=\{theme\}/)
  // 按钮必须在 `shelf-header-actions` 里（页面顶栏），且在「导入本地」之前
  const headerActions = mainTsx.slice(mainTsx.indexOf('shelf-header-actions'), mainTsx.indexOf('</header>', mainTsx.indexOf('shelf-header-actions')))
  assert.ok(headerActions.includes('shelf-search-btn'), '搜索按钮必须在书架页顶栏')
  assert.ok(headerActions.indexOf('shelf-search-btn') < headerActions.indexOf('shelf-import-btn'), '搜索按钮应排在导入本地之前')
})

test('书架页搜索命中后：书架结果直接开书，目录/正文结果带章定位', () => {
  // 切到 openSearchHit 这个函数体结束（下一个 `  return (` 之前），
  // 不要拿 App 的 `<main` 当终点：那个 return 在函数**之前**，会切出空串。
  const bodyStart = mainTsx.indexOf('const openSearchHit')
  const bodyEnd = mainTsx.indexOf('\n  return (', bodyStart)
  assert.ok(bodyStart >= 0 && bodyEnd > bodyStart, '必须能定位到 openSearchHit 的函数体')
  const body = mainTsx.slice(bodyStart, bodyEnd)
  // 书不在架 ⇒ 明确提示，绝不猜一个 item 去开书
  assert.match(body, /items\.find\(entry => entry\.sourceId === hit\.sourceId && entry\.bookUrl === hit\.bookUrl\)/)
  assert.match(body, /t\('search\.bookNotInShelf', '这本书不在书架里，无法打开'\)/)
  assert.match(body, /if \(hit\.kind === 'shelf'\) \{\s*\n\s*onOpen\(item\)/)
  // 跳章复用 openShelfItem(item, bookmark) 那条通道（内部 matchBestChapter）
  assert.match(body, /chapterName: hit\.chapterTitle \?\? undefined/)
})

test('ShelfPage 的 onOpen 支持章定位参数，并向 App 透传', () => {
  assert.match(mainTsx, /function ShelfPage\(\{ onOpen, theme \}: \{ onOpen: \(item: BookshelfItem, jump\?: BookmarkJumpTarget\) => void; theme: ReaderTheme \}\)/)
  assert.match(mainTsx, /onOpen=\{\(item, jump\) => void openShelfItem\(item, jump\)\}/)
})

// ---------------------------------------------------------------------------
// 4. 阅读器：目录按钮**右边**一颗搜索按钮，且只给正文搜索
// ---------------------------------------------------------------------------
test('阅读器顶栏 - 搜索按钮紧跟在「目录」按钮右边', () => {
  const headerStart = reader.indexOf('className="reader-header"')
  const headerEnd = reader.indexOf('reader-header-actions', headerStart)
  const left = reader.slice(headerStart, headerEnd)
  const catalogAt = left.indexOf("label={t('reader.catalog', '目录')}")
  const searchAt = left.indexOf("label={t('search.content'")
  assert.ok(catalogAt > 0, '顶栏必须还有目录按钮')
  assert.ok(searchAt > catalogAt, '搜索按钮必须在目录按钮右边')
  assert.match(left, /icon="search"/)
  assert.match(left, /onClick=\{\(\) => setShowContentSearch\(true\)\}/)
})

test('阅读器搜索弹窗 - 只给 content 模式，并带上当前书 scope，点结果跳章', () => {
  const block = reader.slice(reader.indexOf('{showContentSearch && ('), reader.indexOf('{showContentSearch && (') + 900)
  assert.match(block, /<GlobalSearchModal/)
  assert.match(block, /modes=\{\['content'\]\}/)
  assert.match(block, /scope=\{\{ sourceId: currentBook\.details\.sourceId, bookUrl: currentBook\.bookUrl \}\}/)
  assert.match(block, /matchBestChapter\(/)
  assert.match(block, /changeChapter\(target, 'first'\)/)
})

// ---------------------------------------------------------------------------
// 5. i18n：四个语言都必须有这批 key（i18n-parity 会再扫一遍 t() 字面量）
// ---------------------------------------------------------------------------
test('i18n - 四个语言的 search.* 新 key 齐备且非空', () => {
  const keys = [
    'title', 'shelf', 'toc', 'content', 'modalPlaceholder', 'empty', 'loading',
    'hintShelf', 'hintToc', 'hintContent', 'bookNotInShelf', 'scopeCurrentBook', 'truncated',
  ]
  for (const locale of LOCALES) {
    for (const key of keys) {
      const value = locales[locale]?.search?.[key]
      assert.equal(typeof value, 'string', `${locale} 缺少 search.${key}`)
      assert.ok(value.trim().length > 0, `${locale} 的 search.${key} 不能为空`)
    }
  }
  // 中文文案要自然：书架/目录/正文三 tab 的名字必须正好是这三个词
  assert.equal(locales['zh-CN'].search.shelf, '书架')
  assert.equal(locales['zh-CN'].search.toc, '目录')
  assert.equal(locales['zh-CN'].search.content, '正文')
  // 原有的「全网搜索」占位符不能被这次改动顶掉（它在书库搜索页仍在用）
  assert.match(locales['zh-CN'].search.placeholder, /全网/)
})

// ---------------------------------------------------------------------------
// 6. 样式：类名统一 global-search- 前缀，且有移动端适配
// ---------------------------------------------------------------------------
test('styles.css - 全局搜索样式齐全（含高亮与移动端）', () => {
  for (const cls of [
    '.global-search-dialog', '.global-search-header', '.global-search-tabs',
    '.global-search-input', '.global-search-hint', '.global-search-results',
    '.global-search-item', '.global-search-cover', '.global-search-snippet',
    '.global-search-mark', '.global-search-clear', '.global-search-truncated',
    '.shelf-search-btn',
  ]) {
    assert.ok(styles.includes(cls), `styles.css 缺少 ${cls}`)
  }
  const media = styles.slice(styles.indexOf('.global-search-dialog {'))
  assert.match(media, /@media \(max-width: 640px\)/)
})
