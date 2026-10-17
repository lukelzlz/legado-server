import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { AudiobookPlayerScreen } from '../src/AudiobookPlayerScreen'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')
const css = read('../src/styles.css')

/**
 * 有声书底部功能条「动态均分」。
 *
 * 回归点：原实现是 `display: grid; grid-template-columns: repeat(5, 1fr)`——
 * 列数被**写死成 5**，而按钮个数是会长变的（例如「缓存」按钮被移进目录抽屉后只剩 4 个），
 * 于是第 5 列空着、四个按钮挤在整条宽度的 4/5 里，右边留一大片空白。
 *
 * 因此这里断言的是「**有几个按钮就均分几份**」，而不是「恰好 4 份」：
 * 1. 规则里不得再出现任何固定份数（`repeat(n, …)` / `grid-template-columns`）；
 * 2. 必须有动态均分机制 + 不让长文案撑宽某一份的 `min-width: 0`；
 * 3. 渲染出的底部功能条**每一个直接子按钮**都必须带 `audiobook-feature`
 *    （漏一个就等于那个按钮不参与均分）；
 * 4. 文案溢出保护。
 *
 * 注意：**刻意不断言按钮个数**——个数由「缓存按钮是否已被移走」那个 PR 决定，
 * 断言数量会让本测试与另一个 PR 耦合。
 */

const openBook = {
  details: {
    sourceId: 'https://audio.example.com',
    name: '测试有声书',
    author: '测试作者',
    tocUrl: 'https://audio.example.com/book/1',
    coverUrl: 'https://audio.example.com/cover.jpg',
  },
  bookUrl: 'https://audio.example.com/book/1',
  bookKind: 'audio',
  chapters: [
    { index: 0, title: '第 1 集', url: 'https://audio.example.com/c/1' },
    { index: 1, title: '第 2 集', url: 'https://audio.example.com/c/2' },
  ],
}

/** 取出某个选择器的规则块（第一个匹配），断言时只关心块内声明。 */
function ruleBlock(selector: string): string {
  const index = css.indexOf(selector)
  assert.ok(index >= 0, `styles.css 必须存在规则：${selector}`)
  const open = css.indexOf('{', index)
  const close = css.indexOf('}', open)
  // 先剥注释：说明性注释里会引用旧写法，不剥掉会拿注释文字去断言实现（假失败）。
  return css.slice(open + 1, close).replace(/\/\*[\s\S]*?\*\//g, '')
}

/**
 * 只收录 **HTML 空元素**。切勿把 `path`/`circle` 等 SVG 标签算进来：
 * React 对 SVG 子元素会成对渲染 `<path …></path>`，按空元素处理会让深度计数错乱、
 * 后面的兄弟节点全部被漏掉（本测试初版就因此误报"只有 2 个直接子元素"）。
 */
const VOID_TAGS = new Set([
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr',
])

/** 取出某容器元素的**直接子元素**开标签（用于断言"每一个子按钮都参与均分"）。 */
function directChildTags(html: string, className: string): string[] {
  const marker = html.indexOf(`class="${className}"`)
  assert.ok(marker >= 0, `渲染结果必须包含 .${className}`)
  const openEnd = html.indexOf('>', marker)
  const children: string[] = []
  let depth = 0
  let i = openEnd + 1
  while (i < html.length) {
    const lt = html.indexOf('<', i)
    if (lt < 0) break
    if (html.startsWith('<!--', lt)) {
      i = html.indexOf('-->', lt) + 3
      continue
    }
    const gt = html.indexOf('>', lt)
    if (gt < 0) break
    const raw = html.slice(lt, gt + 1)
    if (raw.startsWith('</')) {
      if (depth === 0) break
      depth--
    } else {
      const tag = /^<([a-zA-Z][\w-]*)/.exec(raw)?.[1]?.toLowerCase() ?? ''
      const selfClosing = raw.endsWith('/>') || VOID_TAGS.has(tag)
      if (depth === 0) children.push(raw)
      if (!selfClosing) depth++
    }
    i = gt + 1
  }
  return children
}

function renderPlayer(): string {
  return renderToStaticMarkup(
    React.createElement(AudiobookPlayerScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )
}

test('有声书底部功能条 - CSS 不得写死份数，必须是动态均分', () => {
  const block = ruleBlock('.audiobook-bottom-controls {')

  // 回归点：原实现 `repeat(5, 1fr)` 把份数钉死在 5。
  const fixed = block.match(/repeat\([^)]*\)/)?.[0]
  assert.ok(!/repeat\(/.test(block), `底部功能条不得再写死列数（发现：${fixed ?? ''}）`)
  assert.ok(!/grid-template-columns/.test(block), '不得再用固定列模板 grid-template-columns')

  assert.ok(
    /display\s*:\s*flex/.test(block) || /grid-auto-columns\s*:\s*1fr/.test(block),
    '必须包含动态均分机制（display: flex 或 grid-auto-columns: 1fr）',
  )
})

test('有声书底部功能条 - 每份都按 flex 均分，且不被长文案撑宽', () => {
  const feature = ruleBlock('.audiobook-feature {')
  assert.ok(
    /flex\s*:\s*1\s+1\s+0/.test(feature) || /flex-grow\s*:\s*1/.test(feature),
    '每个按钮都必须 flex-grow 均分（flex: 1 1 0）',
  )
  // 少了 min-width: 0 的话，`flex-basis: 0` 会被内容的最小宽度顶开 ⇒ 某一份变宽、均分被破坏。
  assert.ok(/min-width\s*:\s*0/.test(feature), 'min-width: 0 必须存在，否则长文案会把某一份撑宽')
})

test('有声书底部功能条 - 所有直接子按钮都参与均分（与按钮个数无关）', () => {
  const html = renderPlayer()
  const children = directChildTags(html, 'audiobook-bottom-controls')

  assert.ok(children.length > 0, '底部功能条必须至少有一个子按钮')
  for (const raw of children) {
    assert.ok(/^<button\b/.test(raw), `底部功能条的直接子元素必须是 <button>，实际：${raw.slice(0, 80)}`)
    const classAttr = /class="([^"]*)"/.exec(raw)?.[1] ?? ''
    assert.ok(
      classAttr.split(/\s+/).includes('audiobook-feature'),
      `每个子按钮都必须带 audiobook-feature 类（否则它不参与均分），实际 class="${classAttr}"`,
    )
  }

  // 反向守卫：整个渲染里带 audiobook-feature 类的元素，**全部**都得是功能条的直接子元素。
  // （count 口径按 class 分词，避免 `audiobook-feature-value` 被子串误匹配进来。）
  const featureElements = [...html.matchAll(/class="([^"]*)"/g)]
    .filter(match => match[1].split(/\s+/).includes('audiobook-feature'))
    .length
  assert.equal(
    featureElements,
    children.length,
    '带 audiobook-feature 类的元素必须恰好是底部功能条的直接子元素',
  )
})

test('有声书底部功能条 - 文案溢出保护（不换行、不撑破均分）', () => {
  // 两份文字都可能变长（倍速是 `1.0x`、定时最长为 `01:30:00`、按钮个数变多则每份更窄）
  const guard = ruleBlock('.audiobook-feature > span {')
  assert.ok(/white-space\s*:\s*nowrap/.test(guard), '文案不得换行（换行会把整条撑高）')
  assert.ok(/max-width\s*:\s*100%/.test(guard), '文案不得超过按钮宽度')
  assert.ok(
    /overflow\s*:\s*hidden/.test(guard) && /text-overflow\s*:\s*ellipsis/.test(guard),
    '超长文案必须省略号截断而不是溢出到相邻按钮',
  )
})
