import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
// 先初始化 i18n，让 t() 解析到真实文案（与 search-scope-bar.test.ts 同一条路径）。
import '../src/i18n'
import { clearSearchInput, handleSearchKeyDown, SearchBox } from '../src/SearchBox.tsx'

/**
 * 搜索界面 UI 优化（issue #73）的回归锁。
 *
 * 锁的是一条条**可观察的行为**，而不是「长得像哪版样式」：
 * ① 清空按钮只在有内容时出现，且必须是 `type="button"`（否则会触发 form submit 误重启搜索）；
 * ② 空输入时提交按钮 disabled（searchStore.startSearch 对空串本来也是直接 return）；
 * ③ `Esc` 清空并把焦点交还输入框，`Enter` 只走 form submit 一条路（不能重复接线）；
 * ④ 加载中显示 Spinner 并提供「停止搜索」，停止按钮任何时候都可点；
 * ⑤ 聚焦环只用主题变量，不写死色值（三套主题才不会各自脏掉）。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const sourceOf = (file: string) => fs.readFileSync(path.join(here, '..', 'src', file), 'utf8')
const stylesCss = () => sourceOf('styles.css')

const render = (overrides: Partial<React.ComponentProps<typeof SearchBox>> = {}) =>
  renderToStaticMarkup(
    React.createElement(SearchBox, {
      keyword: '',
      loading: false,
      onChange: () => {},
      onSubmit: () => {},
      onStop: () => {},
      ...overrides,
    }),
  )

/** 取出某个 class 的开标签，便于逐条断言属性（不依赖属性输出顺序）。 */
function tagOf(html: string, className: string): string | null {
  const re = new RegExp(`<[a-z]+\\b[^>]*${className}[^>]*>`, 'i')
  return re.exec(html)?.[0] ?? null
}

test('search box - 结果二次筛选输入框填满可用宽度，且不露出原生输入框边框', () => {
  const css = stylesCss()
  assert.match(css, /\.library-result-filters label:first-child input\s*\{[^}]*flex:\s*1\s*;[^}]*width:\s*100%\s*;[^}]*min-width:\s*0\s*;[^}]*border:\s*0\s*;/,
    '筛选输入框必须撑满 label，而不是以原生宽度渲染一个内层小方框')
  assert.match(css, /\.library-result-filters label:first-child\s*\{[^}]*flex:\s*1\s+1\s+180px/,
    '筛选标签必须占据剩余空间')
})

test('search box - 空输入：不出现清空按钮，提交按钮 disabled', () => {
  const html = render({ keyword: '' })

  assert.ok(html.includes('role="search"'), '搜索框应是一个 search landmark（form role="search"）')
  assert.equal(html.includes('library-search-clear'), false, '空输入时不应渲染清空按钮')

  const submit = tagOf(html, 'library-search-submit')
  assert.ok(submit, '必须存在主搜索按钮')
  assert.ok(submit!.includes('type="submit"'), '主按钮必须走表单提交（Enter 与点击同一条路径）')
  assert.ok(submit!.includes('disabled'), '空输入时主按钮必须 disabled')
  assert.equal(html.includes('type="button"'), false, '空输入且空闲时不该有任何辅助按钮')
})

test('search box - 有内容：清空按钮出现，且必须是 type="button"（不得触发提交）', () => {
  const html = render({ keyword: '十日终焉' })

  const clear = tagOf(html, 'library-search-clear')
  assert.ok(clear, '有内容时必须出现清空按钮')
  assert.ok(clear!.includes('type="button"'), '清空按钮必须显式 type="button"，否则点击会提交表单重启搜索')
  assert.equal(clear!.includes('disabled'), false, '清空按钮不应被禁用')

  const submit = tagOf(html, 'library-search-submit')
  assert.ok(submit && !submit.includes('disabled'), '有内容时主按钮可用')
  assert.equal(html.includes('<strong>'), false)
})

test('search box - 纯空白输入等同空输入：清空按钮仍在，但主按钮 disabled', () => {
  const html = render({ keyword: '   ' })

  assert.ok(tagOf(html, 'library-search-clear'), '有字符（含空格）就应能一键清空')
  const submit = tagOf(html, 'library-search-submit')
  assert.ok(submit!.includes('disabled'), '纯空白不应允许提交（startSearch 对空串会直接 return）')
})

test('search box - 加载中：显示本地内联 Spinner 与「停止搜索」，停止按钮始终可点', () => {
  const html = render({ keyword: '', loading: true })

  assert.ok(html.includes('library-search-spinner'), '搜索进行中必须有加载指示器')
  assert.ok(/role="status"/.test(html), '加载指示器要有 role="status"，供读屏播报')

  const stop = tagOf(html, 'library-search-submit')
  assert.ok(stop, '加载中主按钮位置换成「停止搜索」')
  assert.ok(stop!.includes('type="button"'), '停止搜索必须 type="button"，否则会提交表单重新开搜')
  assert.equal(stop!.includes('disabled'), false, '停止按钮不得禁用（哪怕关键词为空也要能停）')
  assert.ok(html.includes('>停止搜索</button>'), '文案应沿用既有「停止搜索」')
})

test('search box - 清空动作：置空关键词并把焦点交还输入框', () => {
  const changes: string[] = []
  let focused = 0

  clearSearchInput({ current: { focus: () => { focused++ } } }, value => changes.push(value))

  assert.deepEqual(changes, [''], '清空应只回调一次空串')
  assert.equal(focused, 1, '清空后必须重新聚焦输入框（否则用户还得再点一下才能继续输入）')
})

test('search box - 键盘：Esc 清空并阻止默认行为，Enter 不在这里处理（交给 form submit）', () => {
  let cleared = 0
  let prevented = 0
  const event = (key: string) => ({ key, preventDefault: () => { prevented++ } })

  handleSearchKeyDown(event('Escape'), () => { cleared++ })
  assert.equal(cleared, 1, 'Esc 必须清空输入')
  assert.equal(prevented, 1, 'Esc 应 preventDefault（避免浏览器默认行为抢焦点）')

  // Enter 走外层 <form> 的 submit：这里再清空/拦截就会「回车不搜」或「搜两次」。
  handleSearchKeyDown(event('Enter'), () => { cleared++ })
  assert.equal(cleared, 1, 'Enter 不应由按键处理器清空')
  assert.equal(prevented, 1, 'Enter 不应被按键处理器拦截')

  handleSearchKeyDown(event('a'), () => { cleared++ })
  assert.equal(cleared, 1, '普通字符按键不得触发清空')
  assert.equal(prevented, 1)
})

test('search box - Enter 提交只保留一条接线（form onSubmit），不得另有 Enter 分支', () => {
  const source = sourceOf('SearchBox.tsx')

  assert.ok(/<form[\s\S]*?onSubmit=/.test(source), '提交必须由 form 的 onSubmit 驱动（回车天然走这条）')
  assert.ok(source.includes('onSubmit()'), 'onSubmit 里要真正调用提交回调')
  assert.equal(source.includes("'Enter'"), false, '不得在按键处理器里再处理 Enter（会与 form submit 重复触发）')
  assert.ok(source.includes("event.key !== 'Escape'"), 'Escape 分支应显式判定，其余按键直接放行')
})

test('search box - 聚焦环只用主题变量，不写死色值', () => {
  const css = stylesCss()

  const focusRing = /\.library-hero form:focus-within \{([^}]*)\}/.exec(css)
  assert.ok(focusRing, '必须有 .library-hero form:focus-within 的聚焦态样式')
  const body = focusRing![1]
  assert.ok(body.includes('var(--accent)'), '聚焦环边框色应取 --accent')
  assert.ok(body.includes('var(--accent-soft)'), '聚焦环光晕应取 --accent-soft')
  assert.equal(/#[0-9a-f]{3,8}\b/i.test(body), false, `聚焦态不得写死十六进制色值：${body.trim()}`)
  assert.equal(/\b(rgb|rgba|hsl)\(/i.test(body), false, `聚焦态不得写死 rgb()/hsl() 色值：${body.trim()}`)

  // 输入框自身不再画第二层焦点框（由容器 :focus-within 统一表达）
  assert.ok(css.includes('.library-hero form input:focus-visible { outline: none; }'), '输入框不应再叠一层焦点边框')
})

test('search box - 禁用态与 Spinner 同样走主题变量，且规则不与既有 .search-* 冲突', () => {
  const css = stylesCss()

  const disabled = /\.library-hero form \.library-search-submit:disabled \{([^}]*)\}/.exec(css)
  assert.ok(disabled, '空输入禁用态需要有专门的视觉表达（不是整块变透明）')
  assert.ok(disabled![1].includes('var(--accent-soft)') && disabled![1].includes('var(--accent)'))

  const spinner = /\.library-search-spinner \{([^}]*)\}/.exec(css)
  assert.ok(spinner, 'Spinner 样式必须声明')
  assert.ok(spinner![1].includes('var(--accent)') && spinner![1].includes('var(--line)'))
  assert.ok(css.includes('@keyframes library-search-spin'), 'Spinner 需要有旋转动画')
  assert.ok(
    /@media \(prefers-reduced-motion: reduce\) \{ \.library-search-spinner \{ animation: none; \}/.test(css),
    '应尊重「减少动效」偏好',
  )

  // 唯一性：新样式块不得被别处重复声明（否则合并时出现互相覆盖的隐形规则）
  for (const selector of ['.library-search-clear', '.library-search-submit', '.library-search-spinner']) {
    assert.ok(css.includes(selector), `styles.css 必须声明 ${selector}`)
  }
  assert.equal((css.match(/^\.library-search-clear \{/gm) ?? []).length, 1, '.library-search-clear 只应声明一次')
  assert.equal((css.match(/^\.library-search-spinner \{/gm) ?? []).length, 1, '.library-search-spinner 只应声明一次')
})

test('search box - 移动端媒体查询保留安全区变量，不硬编码覆盖', () => {
  const css = stylesCss()

  const mobilePage = /\.library-page \{ gap: 22px; padding: 42px ([^;]*); \}/.exec(css)
  assert.ok(mobilePage, '移动端应在 @media 里保留 .library-page 的内距规则')
  assert.ok(mobilePage![1].includes('var(--safe-left)'), '左右内距必须叠加 --safe-left（刘海/圆角屏）')
  assert.ok(mobilePage![1].includes('var(--safe-right)'), '左右内距必须叠加 --safe-right')
  assert.equal(/\bpadding: 42px 18px;/.test(css), false, '不得再把移动端内距硬编码成 42px 18px')
})

test('search box - 书库页复用同一个 SearchBox（不得在 main.tsx 里再内联一份搜索框）', () => {
  const main = sourceOf('main.tsx')

  assert.ok(/import \{ SearchBox \} from '\.\/SearchBox'/.test(main), 'main.tsx 必须复用 SearchBox 组件')
  assert.ok(main.includes('<SearchBox'), 'LibraryPage 必须渲染 SearchBox')
  assert.equal(
    /<form onSubmit=\{handleSearch\}>/.test(main),
    false,
    '旧的内联搜索框表单必须移除，搜索框只有一份实现',
  )
})
