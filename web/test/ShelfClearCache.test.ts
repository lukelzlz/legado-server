import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import i18n, { changeAppLanguage, resources } from '../src/i18n'
import {
  BROWSER_CLEAR_STEPS,
  SCOPE_STEPS,
  SERVER_CLEAR_STEPS,
  isServerStep,
  stepsForScope,
} from '../src/shelfClearCache'
import { SHELF_MODAL_SHELF, shelfModalReducer, type ShelfModalState } from '../src/shelfModalStack'
import type { BookshelfItem } from '../src/api'

/**
 * 「清除缓存」功能（独立 PR：`feat/shelf-clear-cache`）。
 *
 * 需求形态：书籍管理弹窗里有一行红色的「清除缓存」，点它**弹出范围选择弹窗**
 * （清除服务器缓存 / 清除浏览器缓存 / 清理服务器和浏览器缓存，三个都红色 + 中性取消），
 * 选定后按范围删掉这一本书的缓存。
 *
 * 为什么断言分两层：
 * - **范围正确性**（选服务器就绝不动本机、选浏览器就绝不碰服务端）在
 *   `src/shelfClearCache.ts` 这张纯表上做**真实运行时**断言（import 进来跑）；
 * - **UI / 接线**（弹窗结构、三个选项各传哪个 scope、取消零动作、per-book 参数）只能读源码断言：
 *   `BookManageModal` / `ClearCacheScopeModal` / `ShelfPage` 都住在 `web/src/main.tsx`，
 *   而该模块 ① `import './styles.css'`（Node 实测 `Unknown file extension ".css"`）、
 *   ② 模块顶层直接 `createRoot(document.getElementById('root')!)` ⇒ **Node 侧无法 import**，
 *   `renderToStaticMarkup` 无从谈起（把弹窗拆成独立模块属于另一个 refactor，本 PR 不做）。
 *   源码断言按**真实 JSX 兄弟节点顺序**（= 渲染后的 DOM 顺序）与逐字 class/文案取值，
 *   配合浏览器端 e2e（`web/test/e2e-browser-reclean-a11y.ts` 真实点击 `.book-manage-sheet`）。
 *
 * - **两级层级（issue #66）** 在 `src/shelfModalStack.ts` 这个纯 reducer 上做**真实运行时**断言：
 *   「取消 / ESC / 点遮罩 / 清理执行完 ⇒ 只退一级、仍停留在书籍管理」不再能只靠 grep 源码
 *   （grep 抓不到"状态被清掉"），必须把转移序列真跑一遍。
 */

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

/** 统一成 LF：本仓库在 Windows 下 CRLF 检出，索引/行尾断言必须与行尾风格无关。 */
const readSource = (name: string) => fs.readFileSync(path.resolve(__dirname, `../src/${name}`), 'utf8').replace(/\r\n/g, '\n')

/** 本 PR 涉及的全部 i18n key（四语都必须有、都必须非空）。 */
const CLEAR_CACHE_KEYS = [
  // 书籍管理弹窗那一行
  'clearCache',
  'clearCacheDesc',
  // 范围选择弹窗：标题 / 副说明 / 三个选项及其副说明 / 取消及其副说明
  'clearCacheTitle',
  'clearCacheHint',
  'clearCacheServer',
  'clearCacheServerDesc',
  // 本地导入书的「服务器缓存」副说明（本地书正文不可重建，不能说"下次重新下载"）
  'clearCacheServerLocalDesc',
  'clearCacheBrowser',
  'clearCacheBrowserDesc',
  'clearCacheBoth',
  'clearCacheBothDesc',
  'clearCacheCancel',
  'clearCacheCancelDesc',
  // 失败点名与结果 toast
  'clearCacheSuccess',
  'clearCachePartial',
  'clearCachePartImages',
  'clearCachePartAudio',
  'clearCachePartContent',
  'clearCachePartLocal',
] as const

/** 四个清理动作各自的「失败点名」文案（本机离线正文算第四项）。 */
const CLEAR_CACHE_PART_KEYS = [
  'clearCachePartImages',
  'clearCachePartAudio',
  'clearCachePartContent',
  'clearCachePartLocal',
] as const

/** 范围弹窗里必须引用的 key（含内联默认值要与 zh-CN 逐字一致的那些）。 */
const MODAL_KEYS = [
  'clearCacheTitle',
  'clearCacheHint',
  'clearCacheServer',
  'clearCacheServerDesc',
  'clearCacheServerLocalDesc',
  'clearCacheBrowser',
  'clearCacheBrowserDesc',
  'clearCacheBoth',
  'clearCacheBothDesc',
  'clearCacheCancel',
  'clearCacheCancelDesc',
] as const

const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const

function translationOf(locale: (typeof LOCALES)[number]): Record<string, any> {
  return (resources[locale].translation as Record<string, any>)
}

/** 按名字切出 `main.tsx` 里的顶层函数（到下一个顶层 `function ` 为止）。 */
function topLevelFunctionSource(source: string, name: string): string {
  const start = source.indexOf(`function ${name}(`)
  assert.ok(start > -1, `main.tsx 必须存在 ${name}`)
  const next = source.slice(start + 1).search(/\nfunction /)
  assert.ok(next > -1, `没法定位 ${name} 的结尾`)
  return source.slice(start, start + 1 + next)
}

const bookManageModalSource = () => topLevelFunctionSource(readSource('main.tsx'), 'BookManageModal')
const clearCacheModalSource = () => topLevelFunctionSource(readSource('main.tsx'), 'ClearCacheScopeModal')

/**
 * 按源码顺序抽出一段 JSX 里所有 `<button …>…</button>` 元素（**含子节点**——`<strong>` 文案在开标签之后，
 * 只扫开标签是拿不到标题的，这一点踩过坑）。顺序 = JSX 兄弟顺序 = 渲染后的 DOM 顺序。
 */
function extractButtonElements(body: string): string[] {
  const close = '</button>'
  return body
    .split('<button')
    .slice(1)
    .map(chunk => {
      const end = chunk.indexOf(close)
      return '<button' + (end === -1 ? chunk : chunk.slice(0, end + close.length))
    })
}

const classesOf = (element: string) => (/className="([^"]*)"/.exec(element)?.[1] ?? '').split(/\s+/).filter(Boolean)
const strongOf = (element: string) => (/<strong>([\s\S]*?)<\/strong>/.exec(element)?.[1] ?? '').replace(/\s+/g, ' ').trim()
/** 取该按钮的 onClick 内容（`onClick={...}>` 的惰性匹配）。 */
const onClickOf = (element: string) => /onClick=\{([\s\S]*?)\}>/.exec(element)?.[1] ?? ''

type ActionRow = { element: string; classes: string[]; strong: string }

/** 书籍管理弹窗里所有「操作行」按钮（`manage-action-row`），保持源码顺序。 */
function manageActionRows(): ActionRow[] {
  return extractButtonElements(bookManageModalSource())
    .filter(element => /className="[^"]*manage-action-row/.test(element))
    .map(element => ({ element, classes: classesOf(element), strong: strongOf(element) }))
}

/** 范围选择弹窗里所有按钮（含右上角关闭），保持源码顺序。 */
const modalButtons = () => extractButtonElements(clearCacheModalSource())

/** `ShelfPage.clearCache` 处理函数的源码片段。 */
function clearCacheHandler(): string {
  const source = readSource('main.tsx')
  const start = source.indexOf('const clearCache = async (item: BookshelfItem, scope: ClearCacheScope) => {')
  assert.ok(start > -1, 'ShelfPage 必须有按 scope 的 clearCache 处理函数')
  const end = source.indexOf('\n  }\n', start)
  assert.ok(end > start, '没法定位 clearCache 处理函数的结尾')
  return source.slice(start, end)
}

/** 取函数/处理器源码片段（从锚点起 `length` 个字符内）。 */
function windowFrom(source: string, anchor: string, length = 400): string {
  const at = source.indexOf(anchor)
  assert.ok(at > -1, `源码里找不到锚点：${anchor}`)
  return source.slice(at, at + length)
}

// ---------------------------------------------------------------------------
// 1 & 2. 书籍管理弹窗里的「清除缓存」行：danger 配色 + 排在「移出书架」之前
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 弹窗渲染出「清除缓存」行，且与「移出书架」同为 manage-action-row danger', () => {
  const rows = manageActionRows()
  const clearRow = rows.find(r => r.strong.includes('shelf.clearCache'))
  const removeRow = rows.find(r => r.strong.includes('shelf.removeFromShelf'))

  assert.ok(clearRow, '书籍管理弹窗里必须有一行渲染 shelf.clearCache')
  assert.ok(removeRow, '书籍管理弹窗里必须仍有一行渲染 shelf.removeFromShelf')

  // 用户原话：「和移出书架一样用红色配色」
  assert.ok(clearRow!.classes.includes('manage-action-row'), `实际：${clearRow!.classes.join(' ')}`)
  assert.ok(clearRow!.classes.includes('danger'), `清除缓存行的 class 必须含 danger（红色），实际：${clearRow!.classes.join(' ')}`)
  assert.deepEqual([...clearRow!.classes].sort(), [...removeRow!.classes].sort(), '清除缓存行与移出书架行的 class 必须完全一致（红色配色同源）')

  assert.ok(/<Icon name="trash"\s*\/>/.test(clearRow!.element), '清除缓存行必须使用既有的 trash 图标')
  assert.ok(clearRow!.strong.includes("t('shelf.clearCache'"), '主标题必须走 shelf.clearCache')
  assert.ok(clearRow!.element.includes("t('shelf.clearCacheDesc'"), '副标题必须走 shelf.clearCacheDesc')

  // 内联默认值必须与 zh-CN 词条逐字一致
  const zhCN = translationOf('zh-CN').shelf
  assert.ok(clearRow!.element.includes(`t('shelf.clearCache', '${zhCN.clearCache}')`), `inline 默认值必须与 zh-CN 一致：${zhCN.clearCache}`)
  assert.ok(clearRow!.element.includes(`t('shelf.clearCacheDesc', '${zhCN.clearCacheDesc}')`), `inline 默认值必须与 zh-CN 一致：${zhCN.clearCacheDesc}`)
})

test('shelf-clear-cache - 顺序断言：「清除缓存」紧挨在「移出书架」上面（DOM 兄弟顺序）', () => {
  const rows = manageActionRows()
  const clearIndex = rows.findIndex(r => r.strong.includes('shelf.clearCache'))
  const removeIndex = rows.findIndex(r => r.strong.includes('shelf.removeFromShelf'))

  assert.ok(clearIndex > -1 && removeIndex > -1, '两行都必须存在')
  assert.ok(clearIndex < removeIndex, `「清除缓存」必须渲染在「移出书架」之前（实际 ${clearIndex} vs ${removeIndex}）`)
  assert.equal(removeIndex, clearIndex + 1, '「清除缓存」必须紧邻「移出书架」的正上方')
  assert.equal(removeIndex, rows.length - 1, '「移出书架」必须仍是弹窗里的最后一行')
})

test('shelf-clear-cache - 点这一行不再 confirm，而是打 onClearCache 打开范围弹窗（**不先关父级**，issue #66）', () => {
  const clearRow = manageActionRows().find(r => r.strong.includes('shelf.clearCache'))
  assert.ok(clearRow, '清除缓存行必须存在')

  // issue #66 的根因就在这一行：修复前是 `onClose(); onClearCache()` ——
  // 先关掉书籍管理弹窗，范围弹窗取消后就无父级可回，于是两级一起消失回到书架。
  assert.ok(
    !/onClose/.test(onClickOf(clearRow!.element)),
    `onClick 不得先关闭书籍管理弹窗（根因），实际：${onClickOf(clearRow!.element)}`,
  )
  assert.ok(
    /onClearCache\(\)/.test(onClickOf(clearRow!.element)),
    `onClick 必须只打 onClearCache 打开范围弹窗，实际：${onClickOf(clearRow!.element)}`,
  )

  const source = readSource('main.tsx')
  // props 链路：声明 → 书架页传入 → 弹窗内部不绕过 props 直调
  assert.ok(/onClearCache:\s*\(\)\s*=>\s*void/.test(bookManageModalSource()), 'BookManageModal 必须声明 onClearCache prop')
  assert.ok(
    /onClearCache=\{\(\)\s*=>\s*dispatchShelfModal\(\{\s*type:\s*'openClearCacheScope'\s*\}\)\}/.test(source),
    '书架页必须把 onClearCache 接到「只叠加二级」的层级转移（不得顺手把一级置空）',
  )
  assert.ok(
    !/setManagingItem|setClearingCacheItem/.test(source),
    '两级弹窗不得再用两个彼此独立的可空状态（那正是 #66 的成因），必须走 shelfModalStack 状态机',
  )
  assert.ok(
    !/api\.(clearImageCache|clearAudioCache|clearBookCacheData)|clearOfflineBook\(/.test(bookManageModalSource()),
    'BookManageModal 内部不得直接调用清理接口，必须走 onClearCache',
  )
  assert.ok(
    !/api\.(clearImageCache|clearAudioCache|clearBookCacheData)|clearOfflineBook\(/.test(clearCacheModalSource()),
    '范围弹窗内部同样不得直接调用清理接口，必须走 onPick',
  )
  assert.equal((source.match(/<BookManageModal\b/g) ?? []).length, 1, 'BookManageModal 只应有一处使用点')
})

// ---------------------------------------------------------------------------
// 3. 范围选择弹窗的结构铁律：portal + theme + 三个红色选项 + 中性取消
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 范围弹窗：createPortal 到 document.body + 显式 theme，且只挂一层 backdrop', () => {
  const modal = clearCacheModalSource()

  // 铁律①：portal 逃出 backdrop-filter 包含块，并显式绑定主题（否则夜间模式透光+隐形黑字）
  assert.ok(/createPortal\(/.test(modal), '范围弹窗必须用 createPortal 挂到 body')
  assert.ok(/document\.body,\s*\)/.test(modal), 'createPortal 的容器必须是 document.body')
  assert.ok(/className=\{`modal-backdrop top-layer-modal-backdrop theme-\$\{theme\}`\}/.test(modal), '浮层容器必须是 modal-backdrop top-layer-modal-backdrop（与书架其它弹窗同层级）并显式绑定 theme-${theme}')
  assert.ok(/theme:\s*ReaderTheme/.test(modal), 'theme 必须是显式 prop（不能读全局/localStorage 兜底）')

  // 铁律②：只有一层 backdrop（严禁嵌套；注意 `top-layer-modal-backdrop` 也含该子串，按 className 起始处计数）
  assert.equal((modal.match(/className=\{`modal-backdrop/g) ?? []).length, 1, '弹窗内只能有一层 modal-backdrop')
  // 铁律③：所有按钮显式 type="button"
  const buttons = modalButtons()
  assert.equal(buttons.length, 5, `范围弹窗应有 5 个按钮（关闭 + 三选项 + 取消），实际 ${buttons.length}`)
  for (const button of buttons) {
    assert.ok(/<button type="button"/.test(button), `每个按钮都必须显式 type="button"：${button.slice(0, 60)}`)
  }

  // 无障碍：dialog + aria-modal + aria-label
  assert.ok(/role="dialog"/.test(modal) && /aria-modal="true"/.test(modal), '必须有 role="dialog" 与 aria-modal="true"')
  assert.ok(/aria-label=\{t\('shelf\.clearCacheTitle'/.test(modal), 'aria-label 必须走 shelf.clearCacheTitle')
})

test('shelf-clear-cache - 范围弹窗：三个选项都是 manage-action-row danger（红），取消是同布局但不带 danger', () => {
  const rows = extractButtonElements(clearCacheModalSource()).filter(e => /className="[^"]*manage-action-row/.test(e))
  const options = rows.filter(e => classesOf(e).includes('danger'))
  const cancels = rows.filter(e => !classesOf(e).includes('danger'))

  assert.equal(options.length, 3, `必须正好三个红色选项，实际 ${options.length}`)
  assert.equal(cancels.length, 1, `必须正好一个中性取消，实际 ${cancels.length}`)
  for (const option of options) {
    assert.ok(classesOf(option).includes('manage-action-row'), '选项必须用 manage-action-row（与移出书架同布局）')
    assert.ok(classesOf(option).includes('danger'), '选项必须是 danger（红色，与移出书架同源 --danger）')
  }
  assert.deepEqual(classesOf(cancels[0]), ['manage-action-row'], '取消必须只有 manage-action-row（中性、不红）')

  // 顺序：三个选项在取消之前
  const titles = rows.map(e => strongOf(e))
  assert.ok(titles[3].includes('shelf.clearCacheCancel'), '取消必须是最后一行')
})

test('shelf-clear-cache - 三个选项各传哪个 scope 精确断言；取消零动作', () => {
  const rows = extractButtonElements(clearCacheModalSource()).filter(e => /className="[^"]*manage-action-row/.test(e))
  const options = rows.filter(e => classesOf(e).includes('danger'))
  const cancel = rows.filter(e => !classesOf(e).includes('danger'))[0]

  const scopes = options.map(e => {
    const onClick = onClickOf(e)
    const picked = /onPick\('([a-z]+)'\)/.exec(onClick)
    assert.ok(picked, `选项必须显式传 scope：${onClick}`)
    return picked![1]
  })
  assert.deepEqual(scopes, ['server', 'browser', 'both'], '三选项必须依次是 服务器 / 浏览器 / 两者（互斥三选一）')
  assert.equal(new Set(scopes).size, 3, '三个选项的 scope 不得重复')

  // 每个选项的标题 key 也要对得上（防止"文案与行为错位"）
  const titleKeys = options.map(e => (/t\('(shelf\.clearCache[A-Za-z]+)'/.exec(strongOf(e))?.[1] ?? ''))
  assert.deepEqual(titleKeys, ['shelf.clearCacheServer', 'shelf.clearCacheBrowser', 'shelf.clearCacheBoth'])

  // 取消：只关弹窗，绝不触发任何清理
  const cancelOnClick = onClickOf(cancel)
  assert.ok(!/onPick/.test(cancelOnClick), `取消不得触发任何 scope，实际：${cancelOnClick}`)
  assert.ok(/onClose/.test(cancelOnClick), '取消只应关闭弹窗')
  assert.ok(strongOf(cancel).includes("t('shelf.clearCacheCancel'"), '取消文案必须走 shelf.clearCacheCancel')
})

// ---------------------------------------------------------------------------
// 4. 范围模型（纯模块，真实运行时断言）：选服务器就绝不动本机，反之亦然
// ---------------------------------------------------------------------------

test('shelf-clear-cache - stepsForScope 精确：服务器=3 步且零本机；浏览器=恰好本机 1 步且零服务端；两者=4 步有序', () => {
  // 服务器：恰好三个服务端端点，**一个本机步骤都不能有**
  assert.deepEqual([...stepsForScope('server')], ['images', 'audio', 'content'])
  assert.equal(stepsForScope('server').length, 3)
  assert.ok(!stepsForScope('server').includes('local'), '选「清除服务器缓存」绝不能动本机缓存')
  assert.ok(stepsForScope('server').every(isServerStep), '服务器范围里必须全是服务端步骤')

  // 浏览器：恰好一步，**一个服务端端点都不能有**
  assert.deepEqual([...stepsForScope('browser')], ['local'])
  assert.equal(stepsForScope('browser').length, 1, '选「清除浏览器缓存」绝不能碰服务端')
  assert.ok(!stepsForScope('browser').some(isServerStep), '浏览器范围里必须没有服务端步骤')

  // 两者：四步齐全且顺序固定（图片 → 音频 → 正文 → 本机）
  assert.deepEqual([...stepsForScope('both')], ['images', 'audio', 'content', 'local'])
  assert.deepEqual([...stepsForScope('both')], [...SERVER_CLEAR_STEPS, ...BROWSER_CLEAR_STEPS], 'both 必须恰好是两者相加')

  // 三个范围互斥且两两不同
  assert.equal(new Set(['server', 'browser', 'both'].map(s => stepsForScope(s as any).join(','))).size, 3)
  const serverSet = new Set(stepsForScope('server'))
  assert.ok(!BROWSER_CLEAR_STEPS.some(step => serverSet.has(step)), '服务端集合与本机集合必须不相交')
  assert.deepEqual(Object.keys(SCOPE_STEPS).sort(), ['both', 'browser', 'server'], '范围只有这三种')

  // 未知范围 ⇒ 空动作（宁可不删，也绝不越界删）
  assert.deepEqual([...stepsForScope('nonsense' as any)], [], '脏 scope 必须退化为「什么都不做」')
  assert.equal(isServerStep('local'), false)
})

// ---------------------------------------------------------------------------
// 5. 接线：处理函数只按 stepsForScope(scope) 派发，且只带这一本书
// ---------------------------------------------------------------------------

test('shelf-clear-cache - clearCache 唯一按 stepsForScope(scope) 循环派发：四个清理调用各一处且全在循环内', () => {
  const source = readSource('main.tsx')
  const handler = clearCacheHandler()

  const loopAt = handler.indexOf('for (const step of stepsForScope(scope))')
  assert.ok(loopAt > -1, 'clearCache 必须按 stepsForScope(scope) 循环派发（范围只在一处定义）')

  // 四个清理动作在整个 main.tsx 里各只出现一次，且都在循环体内 ⇒ 实际执行集合 === stepsForScope(scope)
  const calls: Array<[string, string]> = [
    ['api.clearImageCache', 'api.clearImageCache(item.sourceId, item.bookUrl)'],
    ['api.clearAudioCache', 'api.clearAudioCache(item.sourceId, item.bookUrl)'],
    ['api.clearBookCacheData', 'api.clearBookCacheData(item.sourceId, item.bookUrl)'],
    ['clearOfflineBook', 'clearOfflineBook(item.sourceId, item.bookUrl)'],
  ]
  for (const [needle, exact] of calls) {
    const pattern = new RegExp(`${needle.replace('.', '\\.')}\\([^)]*\\)`, 'g')
    assert.equal((source.match(pattern) ?? []).length, 1, `${needle} 在 main.tsx 里只应有一处调用点`)
    assert.deepEqual(handler.match(pattern) ?? [], [exact], `${needle} 必须且只能按当前这本书调用`)
    assert.ok(handler.indexOf(needle) > loopAt, `${needle} 必须在循环体内（否则会绕过范围表）`)
  }

  // 每个 step 都有分派分支；local 是 else（兜底）分支。
  // 注意 `step === 'x'` 会出现两次：① if/else 分派 ② catch 里的失败点名三元表达式。
  for (const needle of ["if (step === 'images') {", "else if (step === 'audio') {", "else if (step === 'content') {"]) {
    assert.equal(handler.split(needle).length - 1, 1, `必须恰好一个分派分支：${needle}`)
  }
  for (const step of ['images', 'audio', 'content']) {
    assert.equal((handler.match(new RegExp(`step === '${step}' \\?`, 'g')) ?? []).length, 1, `失败点名必须恰好覆盖一次 ${step}`)
  }
  assert.equal((handler.match(/step === 'local'/g) ?? []).length, 0, 'local 必须走 else 兜底，不得多写一个分支')
  assert.ok(/else \{[\s\S]{0,200}?await clearOfflineBook/.test(handler), 'local 必须作为 else 兜底分支')

  // 禁全清 / 禁波及其它书
  assert.ok(!/clearAllOffline/.test(handler), '严禁调用全清 clearAllOffline')
  assert.ok(!/evictLruOfflineBooks/.test(handler), '严禁顺手触发 LRU 驱逐（会删别的书）')
  assert.ok(!/getOfflineStorageStats/.test(handler), '严禁遍历全部书后批量删')
})

test('shelf-clear-cache - 每步独立 try/catch 单点记账（只点名实际执行过的段）+ 完成 load() + 忙碌态收尾', () => {
  const handler = clearCacheHandler()

  // 单点记账：全部标签都从循环内**唯一**那个 catch 里 push ⇒ 没执行过的段不可能被点名
  assert.equal((handler.match(/failedParts\.push\(/g) ?? []).length, 1, '必须只有一处 push（循环内的 catch）')
  const pushAt = handler.indexOf('failedParts.push(')
  const catchAt = handler.lastIndexOf('catch {', pushAt)
  const loopAt = handler.indexOf('for (const step of stepsForScope(scope))')
  assert.ok(loopAt > -1 && loopAt < catchAt && catchAt < pushAt, 'push 必须在「循环体内的 catch」中')
  for (const label of CLEAR_CACHE_PART_KEYS) {
    assert.ok(handler.includes(`t('shelf.${label}'`), `失败点名必须覆盖 shelf.${label}`)
  }

  // 全成功 success / 部分失败 warning（带书名），并刷新书架
  assert.ok(handler.includes('toast.success(') && handler.includes("t('shelf.clearCacheSuccess'"), '全成功必须 toast.success 且走 clearCacheSuccess')
  assert.ok(handler.includes('toast.warning(') && handler.includes("t('shelf.clearCachePartial'"), '部分失败必须 toast.warning 且走 clearCachePartial')
  assert.ok(handler.includes('name: item.name'), 'toast 必须带上书名')
  assert.ok(/\bload\(\)/.test(handler), '清完必须刷新书架状态')

  // 忙碌态：进入即置位、finally 里复位并关弹窗（整段循环被一个 try/finally 罩住，
  // 因此即使中途抛出意外异常，也不会把弹窗永久卡在"清理中"）
  assert.ok(/setClearingCache\(true\)/.test(handler), '开始清理必须置忙碌态')
  const finallyAt = handler.indexOf('finally {')
  assert.ok(finallyAt > -1 && finallyAt > loopAt, '必须有 finally 兜底，且它罩住整个循环')
  const finallyBlock = handler.slice(finallyAt, handler.indexOf('\n    }', finallyAt))
  assert.ok(/setClearingCache\(false\)/.test(finallyBlock), 'finally 必须复位忙碌态')
  // issue #66：执行完的落点 = 与「取消」同一个转移（只退一级回到书籍管理），不是回书架
  assert.ok(
    /dispatchShelfModal\(\{\s*type:\s*'closeClearCacheScope'\s*\}\)/.test(finallyBlock),
    'finally 必须退回一级「书籍管理」（closeClearCacheScope），不得直接清空弹窗',
  )
  assert.ok(!/setClearingCacheItem|setManagingItem/.test(finallyBlock), 'finally 不得再用「置空」方式关弹窗')

  // confirm() 已彻底移除（弹窗本身就是确认）
  assert.ok(!/\bconfirm\(/.test(handler), 'clearCache 不得再用 confirm()')
  assert.ok(!/\bconfirm\(/.test(clearCacheModalSource()), '范围弹窗不得用 confirm()')
})

test('shelf-clear-cache - 范围弹窗在书架页以平级条件分支挂载（层级互斥），并把 theme 一路传下去', () => {
  const source = readSource('main.tsx')

  assert.ok(/\{clearingCacheItem && \(\s*\n\s*<ClearCacheScopeModal/.test(source), '范围弹窗必须由 {clearingCacheItem && ...} 平级挂载（不嵌在书籍管理弹窗里）')
  assert.ok(/theme=\{theme\}/.test(source), '必须把 theme 传进范围弹窗（portal 后靠它绑主题变量）')
  assert.ok(/busy=\{clearingCache\}/.test(source), '必须把忙碌态传进去（清理中禁止再点）')
  assert.ok(/onPick=\{scope => void clearCache\(clearingCacheItem, scope\)\}/.test(source), 'onPick 必须把用户选定的 scope 交给 clearCache')
  assert.ok(/function ShelfPage\(\{ onOpen, theme \}/.test(source), 'ShelfPage 必须显式接收 theme')
  assert.ok(/<ShelfPage theme=\{settings\.theme\}/.test(source), 'App 必须把 settings.theme 传给 ShelfPage')

  // 平级挂载的证据：范围弹窗与书籍管理弹窗是两个互斥的顶层条件分支
  const manageAt = source.indexOf('{managingItem && (')
  const clearAt = source.indexOf('{clearingCacheItem && (')
  assert.ok(manageAt > -1 && clearAt > manageAt, '两个弹窗必须是相邻的平级分支')

  // issue #66：两个分支的"可见性"必须由**同一个**层级状态派生 ⇒ 结构上不可能同时渲染
  // （同一时刻最多一个非 null）⇒ 不可能出现两层遮罩叠在一起或 backdrop 嵌套。
  assert.ok(
    /const managingItem = shelfModal\.layer === 'manage' \? shelfModal\.item : null/.test(source),
    '一级弹窗的 item 必须由层级状态派生（manage 层才可见）',
  )
  assert.ok(
    /const clearingCacheItem = shelfModal\.layer === 'clearCacheScope' \? shelfModal\.item : null/.test(source),
    '二级弹窗的 item 必须由层级状态派生（clearCacheScope 层才可见）',
  )
})

// ---------------------------------------------------------------------------
// 8. issue #66：两级弹窗的层级状态机（纯模块，真实运行时断言）
// ---------------------------------------------------------------------------

/** 测试用的书架条目（只填必需字段，其余按需覆盖）。 */
function shelfBook(overrides: Partial<BookshelfItem> = {}): BookshelfItem {
  return {
    sourceId: 'src-1',
    bookUrl: 'https://example.com/book/1',
    name: '测试书',
    tocUrl: 'https://example.com/toc',
    lastReadAt: 0,
    cachedChapters: 3,
    totalChapters: 10,
    cacheState: 'ready',
    completed: false,
    ...overrides,
  }
}

/** 状态机里的 item（联合类型窄化辅助，测试里只读）。 */
const itemOf = (state: ShelfModalState): BookshelfItem | undefined => 'item' in state ? state.item : undefined

test('shelf-clear-cache - issue #66：打开范围弹窗后「取消」⇒ 仍停留在「书籍管理」层级（真实状态转移）', () => {
  const book = shelfBook()

  // ① 书架点「管理」⇒ 一级
  let state = shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openManage', item: book })
  assert.equal(state.layer, 'manage')

  // ② 一级里点「清除缓存」⇒ 叠加二级，**一级的书必须原样保留**（否则取消无处可回）
  state = shelfModalReducer(state, { type: 'openClearCacheScope' })
  assert.equal(state.layer, 'clearCacheScope', '点「清除缓存」应叠加上范围选择弹窗')
  assert.equal(itemOf(state), book, '二级打开时必须保留一级那本书（issue #66 的根因就是这里把它丢了）')

  // ③ 取消（以及 ESC / 点遮罩 / 清理执行完 —— 组件侧都是同一个转移）⇒ 只退一级
  state = shelfModalReducer(state, { type: 'closeClearCacheScope' })
  assert.equal(state.layer, 'manage', '取消后必须回到「书籍管理」层级，而不是直接回书架')
  assert.equal(itemOf(state), book, '回到的一级必须还是同一本书')

  // ④ 这时再关一级才回书架
  state = shelfModalReducer(state, { type: 'closeManage' })
  assert.equal(state.layer, 'shelf')
})

test('shelf-clear-cache - issue #66：ESC / 点遮罩 / 执行完在组件侧都走同一个「只退一级」转移', () => {
  const modal = clearCacheModalSource()

  // 点遮罩：busy 时禁用，否则 onClose
  assert.ok(/onClick=\{busy \? undefined : onClose\}/.test(modal), '点遮罩必须等价于取消（onClose），清理中禁用')
  // ESC：与取消等价（清理中忽略）
  assert.ok(/event\.key === 'Escape'/.test(modal), '范围弹窗必须支持 ESC 关闭')
  assert.ok(/Escape' && !busy\) onClose\(\)/.test(modal), 'ESC 必须与取消等价：只调 onClose，且清理中忽略')
  // 取消行：只调 onClose，不触发任何清理
  const rows = extractButtonElements(modal).filter(e => /className="[^"]*manage-action-row/.test(e))
  const cancel = rows.filter(e => !classesOf(e).includes('danger'))[0]
  assert.ok(/onClose/.test(onClickOf(cancel)), '取消只应关闭弹窗（由父级解释为"退回一级"）')
  assert.ok(!/onPick/.test(onClickOf(cancel)), '取消不得触发任何 scope')

  // 而 onClose 在书架页被解释成 closeClearCacheScope（只退一级，不关一级）
  const source = readSource('main.tsx')
  assert.ok(
    /onClose=\{\(\) => dispatchShelfModal\(\{\s*type: 'closeClearCacheScope'\s*\}\)\}/.test(source),
    '范围弹窗的 onClose 必须只退一级（closeClearCacheScope）',
  )
  // 一级自己也支持 ESC（回书架）；因为两级互斥挂载，不会一次连退两级
  assert.ok(/event\.key === 'Escape'/.test(bookManageModalSource()), '书籍管理弹窗也必须支持 ESC 关闭')
})

test('shelf-clear-cache - issue #66：层级是单一状态 ⇒ 二级不可能脱离一级单独出现（也不会两层同显）', () => {
  const book = shelfBook()

  // 书架层直接开二级 ⇒ 无效（保持书架层）：二级永远有父级
  assert.equal(shelfModalReducer(SHELF_MODAL_SHELF, { type: 'openClearCacheScope' }).layer, 'shelf')

  const manage: ShelfModalState = { layer: 'manage', item: book }
  // 一级层收到「关二级」⇒ 无效（绝不能被顺手降级成书架）
  assert.deepEqual(shelfModalReducer(manage, { type: 'closeClearCacheScope' }), manage)

  // 二级层的 updateItem / syncSnapshot 仍保持二级（层级不会被回灌改动）
  const scope: ShelfModalState = { layer: 'clearCacheScope', item: book }
  const updated = shelfBook({ name: '改过的书名' })
  const afterUpdate = shelfModalReducer(scope, { type: 'updateItem', item: updated })
  assert.equal(afterUpdate.layer, 'clearCacheScope', 'updateItem 不得改层级')
  assert.equal(itemOf(afterUpdate)?.name, '改过的书名')

  // 书架层收到 updateItem / syncSnapshot ⇒ 保持书架（不会凭空冒出弹窗）
  assert.equal(shelfModalReducer(SHELF_MODAL_SHELF, { type: 'updateItem', item: book }).layer, 'shelf')
  assert.equal(shelfModalReducer(SHELF_MODAL_SHELF, { type: 'syncSnapshot', items: [book] }).layer, 'shelf')
})

test('shelf-clear-cache - issue #66：列表轮询只回灌进度字段（不覆盖编辑结果、不改变层级）', () => {
  const edited = shelfBook({ name: '我改的书名', author: '我改的作者' })
  const state: ShelfModalState = { layer: 'clearCacheScope', item: edited }

  const fresh = shelfBook({
    name: '服务端原名',
    author: '服务端原作者',
    cachedChapters: 9,
    totalChapters: 20,
    cacheState: 'caching',
    cacheError: undefined,
  })
  const next = shelfModalReducer(state, { type: 'syncSnapshot', items: [fresh] })
  assert.equal(next.layer, 'clearCacheScope', '回灌不得改变层级')
  const item = itemOf(next)!
  assert.equal(item.cachedChapters, 9, '进度字段必须跟着列表刷新')
  assert.equal(item.totalChapters, 20)
  assert.equal(item.cacheState, 'caching')
  assert.equal(item.name, '我改的书名', '回灌不得覆盖用户编辑过的书名')
  assert.equal(item.author, '我改的作者')

  // 无变化的快照必须返回**同一个 state**（避免多渲染一拍）
  assert.equal(shelfModalReducer(next, { type: 'syncSnapshot', items: [fresh] }), next)

  // 列表里找不到这本书 ⇒ 保持原状（不闪没、不改层级）
  assert.equal(shelfModalReducer(next, { type: 'syncSnapshot', items: [] }), next)
})

// ---------------------------------------------------------------------------
// 9. #64 交过来的条件文案：本地书（loc_book / local://）不能说"正文下次重新下载"
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 本地书的「服务器缓存」副说明走条件文案，网络书原值不变', () => {
  const modal = clearCacheModalSource()
  const zhCN = translationOf('zh-CN').shelf

  assert.ok(
    /isLocalBook\(item\) \? t\('shelf\.clearCacheServerLocalDesc'/.test(modal),
    '本地书（loc_book / local://）必须换成"不清本地书正文"的副说明',
  )
  assert.ok(
    modal.includes(`t('shelf.clearCacheServerDesc', '${zhCN.clearCacheServerDesc}')`),
    '网络书那句原值必须逐字保留（只加分支，不改原值）',
  )
  assert.ok(
    modal.includes(`t('shelf.clearCacheServerLocalDesc', '${zhCN.clearCacheServerLocalDesc}')`),
    '新 key 的内联默认值必须与 zh-CN 逐字一致',
  )

  // 判定纯函数按仓库既有写法：loc_book 虚拟书源 / local:// 路径。
  // issue #66 第二片：判据抽到 `src/localBook.ts`（书架角标 / 书籍管理弹窗 / 阅读器目录抽屉三处共用）。
  const helper = windowFrom(readSource('localBook.ts'), 'export function isLocalBook(', 260)
  assert.ok(helper.includes("item.sourceId === 'loc_book'"), 'isLocalBook 必须判 loc_book')
  assert.ok(helper.includes("item.bookUrl.startsWith('local://')"), 'isLocalBook 必须判 local:// 路径')
  // main.tsx 必须复用同一份判据，不得再各自内联一份（否则三个入口迟早分叉）
  assert.ok(
    /import \{ isLocalBook \} from '\.\/localBook'/.test(readSource('main.tsx')),
    'main.tsx 必须从 localBook 模块复用 isLocalBook',
  )

  // 四语齐全、非空、互不雷同，且都必须点明"正文不会被删"
  const mustSayKept: Record<(typeof LOCALES)[number], RegExp> = {
    'zh-CN': /不删除|不会删除/,
    'zh-TW': /不刪除|不會刪除/,
    'en-US': /is kept|not removed|kept/i,
    'ja-JP': /失われません|削除しません|残り/,
  }
  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    const value = shelf.clearCacheServerLocalDesc
    assert.equal(typeof value, 'string', `${locale}.shelf.clearCacheServerLocalDesc 必须存在`)
    assert.ok(value.trim().length > 0, `${locale}.shelf.clearCacheServerLocalDesc 不得为空`)
    assert.ok(mustSayKept[locale].test(value), `${locale} 本地书副说明必须点明正文不受影响：${value}`)
    if (locale !== 'zh-CN') {
      assert.notEqual(value, zhCN.clearCacheServerLocalDesc, `${locale} 不得直接复制简体中文`)
    }
    if (locale === 'en-US') assert.ok(!/[\u4e00-\u9fff]/.test(value), 'en-US 不得含中文')
  }
  // 「取消」的承诺（不做任何清理、返回书籍管理）与这条无关，仍是四语保留的既有文案
  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    assert.ok(typeof shelf.clearCacheCancelDesc === 'string' && shelf.clearCacheCancelDesc.length > 0, `${locale} 取消副说明必须保留`)
  }
  assert.equal(zhCN.clearCacheCancelDesc, '不做任何清理，返回书籍管理')
})

// ---------------------------------------------------------------------------
// 6. 三个既有 api 封装复用了正确的三个端点
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

// ---------------------------------------------------------------------------
// 7. i18n 四语
// ---------------------------------------------------------------------------

test('shelf-clear-cache - 四语言 key 全部存在且非空，且 en/ja/zh-TW 未被中文污染', () => {
  const cjk = /[\u4e00-\u9fff]/
  const expectedClearCache: Record<(typeof LOCALES)[number], string> = {
    'zh-CN': '清除缓存',
    'zh-TW': '清除快取',
    'en-US': 'Clear Cache',
    'ja-JP': 'キャッシュ削除',
  }
  /** 这几个 key 在 zh-TW 必须真的写成繁体习惯词（其余可能与简体重合，如「取消」） */
  const mustBeTraditional = [
    'clearCacheTitle',
    'clearCacheDesc',
    'clearCacheHint',
    'clearCachePartImages',
    'clearCachePartAudio',
    'clearCachePartContent',
    'clearCachePartLocal',
    'clearCacheServer',
    'clearCacheServerDesc',
    'clearCacheBrowser',
    'clearCacheBrowserDesc',
    'clearCacheBoth',
    'clearCacheBothDesc',
  ] as const

  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, unknown>
    for (const key of CLEAR_CACHE_KEYS) {
      const value = shelf[key]
      assert.ok(typeof value === 'string', `${locale}.shelf.${key} 必须存在且为字符串`)
      assert.ok((value as string).trim().length > 0, `${locale}.shelf.${key} 不得为空`)
    }
    assert.equal(shelf.clearCache, expectedClearCache[locale], `${locale}.shelf.clearCache 文案不符`)

    if (locale === 'en-US') {
      // en 一条中文都不许有
      for (const key of CLEAR_CACHE_KEYS) {
        assert.ok(!cjk.test(shelf[key] as string), `en-US.shelf.${key} 不得含中文`)
      }
    }
    if (locale === 'ja-JP') {
      for (const key of CLEAR_CACHE_KEYS) {
        assert.notEqual(shelf[key], translationOf('zh-CN').shelf[key], `ja-JP.shelf.${key} 不得直接复制中文`)
      }
    }
    if (locale === 'zh-TW') {
      for (const key of mustBeTraditional) {
        assert.notEqual(shelf[key], translationOf('zh-CN').shelf[key], `zh-TW.shelf.${key} 必须是繁体习惯用词`)
      }
    }
  }
})

test('shelf-clear-cache - 四个失败项文案四语齐全、非空、互不相同，且本机那项点明「本机/离线」', () => {
  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    const labels = CLEAR_CACHE_PART_KEYS.map(key => shelf[key])
    assert.equal(labels.length, 4, `${locale} 必须有四个失败项文案`)
    for (const [i, label] of labels.entries()) {
      assert.ok(typeof label === 'string' && label.trim().length > 0, `${locale}.shelf.${CLEAR_CACHE_PART_KEYS[i]} 不得为空`)
    }
    assert.equal(new Set(labels).size, 4, `${locale} 四个失败项文案必须互不相同`)
    assert.ok(/本机|本機|local|ローカル/i.test(shelf.clearCachePartLocal), `${locale} clearCachePartLocal 必须点明「本机/离线」：${shelf.clearCachePartLocal}`)
  }
})

test('shelf-clear-cache - 范围弹窗引用的每个 key 四语都有，且内联默认值与 zh-CN 逐字一致', () => {
  const modal = clearCacheModalSource()
  const zhCN = translationOf('zh-CN').shelf

  for (const key of MODAL_KEYS) {
    assert.ok(modal.includes(`t('shelf.${key}'`), `范围弹窗必须引用 shelf.${key}（否则是死代码/悬空文案）`)
    assert.ok(modal.includes(`'${zhCN[key]}'`), `shelf.${key} 的内联默认值必须与 zh-CN 逐字一致`)
    for (const locale of LOCALES) {
      const value = translationOf(locale).shelf[key]
      assert.ok(typeof value === 'string' && value.length > 0, `${locale}.shelf.${key} 必须非空`)
    }
  }

  // 副说明必须插值书名、且声明"不影响阅读进度/书架/封面"
  assert.ok(/t\('shelf\.clearCacheHint',[\s\S]{0,220}?\{ name: item\.name \}/.test(modal), '副说明必须插值书名')
  const mustNotAffect: Record<(typeof LOCALES)[number], string> = {
    'zh-CN': '阅读进度',
    'zh-TW': '閱讀進度',
    'en-US': 'reading progress',
    'ja-JP': '読書の進捗',
  }
  for (const locale of LOCALES) {
    const hint = translationOf(locale).shelf.clearCacheHint as string
    assert.ok(hint.includes('{{name}}'), `${locale} 副说明必须带 {{name}}`)
    // 大小写不敏感：en 的 "Reading progress" 出现在句首
    assert.ok(
      hint.toLowerCase().includes(mustNotAffect[locale].toLowerCase()),
      `${locale} 副说明必须声明不动「${mustNotAffect[locale]}」：${hint}`,
    )
  }

  // 三个选项的副说明各自要能看出后果差异
  const clues: Record<(typeof LOCALES)[number], { server: string; browser: string; both: string }> = {
    'zh-CN': { server: '服务端', browser: '本机', both: '一起' },
    'zh-TW': { server: '伺服器', browser: '本機', both: '一起' },
    'en-US': { server: 'server', browser: 'device', both: 'both' },
    'ja-JP': { server: 'サーバー', browser: '端末', both: '両方' },
  }
  for (const locale of LOCALES) {
    const shelf = translationOf(locale).shelf as Record<string, string>
    assert.ok(shelf.clearCacheServerDesc.includes(clues[locale].server), `${locale} 服务器副说明必须提到「${clues[locale].server}」`)
    assert.ok(shelf.clearCacheBrowserDesc.includes(clues[locale].browser), `${locale} 浏览器副说明必须提到「${clues[locale].browser}」`)
    assert.ok(shelf.clearCacheBothDesc.includes(clues[locale].both), `${locale} 两者副说明必须提到「${clues[locale].both}」`)
  }
})

test('shelf-clear-cache - confirm() 的旧文案已改造为弹窗副说明：clearCacheConfirm 四语都不存在、源码无悬空引用', () => {
  for (const locale of LOCALES) {
    assert.ok(!('clearCacheConfirm' in translationOf(locale).shelf), `${locale} 不得残留 clearCacheConfirm`)
    assert.ok('clearCacheHint' in translationOf(locale).shelf, `${locale} 必须有 clearCacheHint（副说明）`)
  }
  const main = readSource('main.tsx')
  assert.ok(!main.includes('clearCacheConfirm'), 'main.tsx 里不得残留 clearCacheConfirm 引用')
  assert.ok(main.includes("t('shelf.clearCacheHint'"), 'clearCacheHint 必须真的被弹窗引用（不是悬空 key）')
})

test('shelf-clear-cache - 副说明与成功/部分失败 toast 的运行时插值可用', async () => {
  for (const locale of LOCALES) {
    await changeAppLanguage(locale)
    const shelf = translationOf(locale).shelf as Record<string, string>

    const hint = i18n.t('shelf.clearCacheHint', { name: 'TEST_BOOK' })
    assert.ok(hint.includes('TEST_BOOK') && !hint.includes('{{'), `${locale} 副说明插值后必须含书名且无占位符`)

    const success = i18n.t('shelf.clearCacheSuccess', { name: 'TEST_BOOK' })
    assert.ok(success.includes('TEST_BOOK'), `${locale} 成功 toast 必须带书名`)

    const partial = i18n.t('shelf.clearCachePartial', { name: 'TEST_BOOK', parts: shelf.clearCachePartLocal })
    assert.ok(partial.includes('TEST_BOOK'), `${locale} 部分失败 toast 必须带书名`)
    assert.ok(partial.includes(shelf.clearCachePartLocal), `${locale} 部分失败 toast 必须点名没清掉的部分`)
    assert.ok(!partial.includes('{{'), `${locale} 部分失败 toast 不得残留占位符`)
  }
  await changeAppLanguage('zh-CN')
})
