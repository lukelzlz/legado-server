import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { MangaReaderScreen } from '../src/MangaReaderScreen'

/**
 * 漫画「目录缓存栏」（用户原话：「在漫画的目录界面添上缓存栏，缓存到浏览器，还有换源什么的，
 * 像小说的那个一样」）。
 *
 * 本文件只锁「缓存栏」这一半（换源是独立任务，未接线是**故意**的）：
 * ① 底栏在**目录抽屉**里、章节列表**之后**，四个按钮全部显式 type="button"；
 * ② 四个按钮与「本章 / 后 50 章 / 后 100 章 / 全本」的既有调用**逐一接线**（源码级断言，
 *    因为 renderToStaticMarkup 拿不到 onClick）；
 * ③ 设置抽屉里那一节**搬走了**（不是复制），避免同一功能两处入口；
 * ④ 绿点图例仍在页面上（它是"哪些章可离线读"的唯一解释，绝不能顺手弄丢）；
 * ⑤ 用到的文案键在四种语言里都存在且非空。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

const openBook = {
  details: {
    sourceId: 'https://manga.example.com',
    name: '测试漫画',
    tocUrl: 'https://manga.example.com/book/1',
  },
  bookUrl: 'https://manga.example.com/book/1',
  bookKind: 'comic',
  chapters: [
    { index: 0, title: '第 1 话', url: 'https://manga.example.com/c/1' },
    { index: 1, title: '第 2 话', url: 'https://manga.example.com/c/2' },
  ],
}

/** 从 `marker` 起截到其后第一个 `</aside>`：漫画的抽屉内部不会再嵌套 aside。 */
function asideSegment(text: string, marker: string): string {
  const start = text.indexOf(marker)
  assert.ok(start >= 0, `必须存在 ${marker}`)
  const end = text.indexOf('</aside>', start)
  assert.ok(end > start, `${marker} 之后必须有闭合的 </aside>`)
  return text.slice(start, end)
}

/** 底栏片段（`<footer class="drawer-footer">` … `</footer>`）。 */
function footerSegment(text: string): string {
  const start = text.indexOf('<footer className="drawer-footer">') >= 0
    ? text.indexOf('<footer className="drawer-footer">')
    : text.indexOf('<footer class="drawer-footer">')
  assert.ok(start >= 0, '必须存在 <footer class="drawer-footer">')
  const end = text.indexOf('</footer>', start)
  assert.ok(end > start, '底栏必须有闭合的 </footer>')
  return text.slice(start, end + '</footer>'.length)
}

const FOOTER_CLASS = 'class="drawer-footer"'

test('漫画目录底栏 - 恰好一个 drawer-footer，位于章节列表之后，含四个 type="button" 按钮', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )
  const leftDrawer = asideSegment(html, 'reader-drawer-left')

  // ① 恰好一个（不是"至少一个"）：多了就是复制了一份底栏
  assert.equal(leftDrawer.split(FOOTER_CLASS).length - 1, 1, '目录抽屉里必须恰好有一个 .drawer-footer')
  // ② 位于章节列表之后（列表在上、动作在下，才是"底栏"）
  const listAt = leftDrawer.indexOf('reader-chapters')
  const footerAt = leftDrawer.indexOf(FOOTER_CLASS)
  assert.ok(listAt >= 0, '目录抽屉必须复用 VirtualChapterList（reader-chapters）')
  assert.ok(listAt < footerAt, '底栏必须在章节列表**之后**')
  // 底栏是抽屉的最后一个子节点（否则就不是固定在底部的那一栏）
  assert.ok(leftDrawer.trimEnd().endsWith('</footer>'), '底栏必须是目录抽屉的最后一个子节点')

  // ③ 四个按钮，全部显式 type="button"（仓库铁律：不写会触发 form 默认 submit）
  const footer = footerSegment(html)
  const buttons = footer.match(/<button[^>]*>/g) ?? []
  assert.equal(buttons.length, 4, `底栏必须恰好四个缓存按钮（实测 ${buttons.length}）`)
  for (const button of buttons) {
    assert.ok(button.includes('type="button"'), `缓存按钮必须显式 type="button"：${button}`)
    assert.ok(button.includes('cache-range-btn'), `缓存按钮必须复用 .cache-range-btn：${button}`)
  }

  // ④ 全页只有这四个 .cache-range-btn：设置抽屉里那一份是**搬走**而不是复制
  const pageWide = html.match(/<button[^>]*cache-range-btn[^>]*>/g) ?? []
  assert.equal(pageWide.length, 4, `全页只允许这四个缓存按钮（实测 ${pageWide.length}）`)
})

test('漫画目录底栏 - 四个按钮逐一接线到「本章 / 50 / 100 / 全本」的既有调用', () => {
  const source = read('../src/MangaReaderScreen.tsx')
  const footer = footerSegment(asideSegment(source, 'reader-drawer-left'))

  const buttons = footer.match(/<button[\s\S]*?<\/button>/g) ?? []
  assert.equal(buttons.length, 4, '底栏必须恰好四个按钮')

  // 顺序与有声书/小说那套一致：本章 → 后 50 章 → 后 100 章 → 全本
  const wiring = [
    { label: "t('manga.offlineCurrent'", title: "t('manga.offlineCurrentTitle'", call: 'requestOffline(0)' },
    { label: "t('reader.cacheNext50'", title: "t('reader.cacheNext50Title'", call: 'requestOffline(50)' },
    { label: "t('reader.cacheNext100'", title: "t('reader.cacheNext100Title'", call: 'requestOffline(100)' },
    { label: "t('reader.cacheAll'", title: "t('manga.offlineAllTitle'", call: 'requestOffline(chapters.length)' },
  ]
  wiring.forEach((item, index) => {
    const button = buttons[index]
    assert.ok(button.includes(item.call), `第 ${index + 1} 个按钮必须调用 ${item.call}`)
    assert.ok(button.includes(item.label), `第 ${index + 1} 个按钮的文案必须用 ${item.label}`)
    assert.ok(button.includes(item.title), `第 ${index + 1} 个按钮的 title 必须用 ${item.title}`)
    assert.ok(button.includes("type=\"button\""), `第 ${index + 1} 个按钮必须显式 type="button"`)
  })

  // 「整本」必须是 chapters.length（= 覆盖到书末 ⇒ requestOffline 里判定为 whole ⇒ pin 免死金牌）
  assert.ok(footer.includes('requestOffline(chapters.length)'), '全本缓存必须传 chapters.length')

  // 进行中反馈：沿用既有的 offlineRunning，不新造状态机；禁用而不是把抽屉关掉
  assert.equal(
    footer.split('disabled={offlineRunning}').length - 1,
    4,
    '缓存进行中必须让四个按钮都置灰（沿用 offlineRunning 这一个既有状态）',
  )
  // 点击**不关**抽屉：否则绿点当场看不见，等于把缓存做成了盲盒
  assert.ok(!footer.includes('setActiveDrawer'), '底栏按钮不得关闭抽屉（要保持目录可见，让绿点当场亮）')
})

test('漫画目录底栏 - 离线缓存那一节已从设置抽屉**搬走**（不是复制）', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )
  const settingsDrawer = asideSegment(html, 'reader-drawer-right')

  for (const gone of ['cache-range-btn', 'manga.offlineCurrent', 'offlineCache', '绿点']) {
    assert.ok(!settingsDrawer.includes(gone), `设置抽屉里不该再有离线缓存那一节（残留 "${gone}"）`)
  }
  assert.ok(!settingsDrawer.includes(FOOTER_CLASS), '设置抽屉里不该有底栏')

  // 源码级同样断言：避免只是 defaultProps 之类把文案藏起来
  const source = asideSegment(read('../src/MangaReaderScreen.tsx'), 'reader-drawer-right')
  for (const gone of ['cache-range-btn', 'requestOffline', 'manga.offlineCache', 'manga.offlineHint']) {
    assert.ok(!source.includes(gone), `设置抽屉源码里不该再有 "${gone}"`)
  }
})

test('漫画目录底栏 - 绿点图例（已离线 N 章 · 绿点）仍在页面上渲染得到', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {}, theme: 'dark' }),
  )
  const leftDrawer = asideSegment(html, 'reader-drawer-left')
  const footer = leftDrawer.slice(leftDrawer.indexOf(FOOTER_CLASS))

  // 绿点图例是"整章可离线阅读"的唯一解释：搬家的过程中绝不能弄丢
  assert.ok(footer.includes('绿点'), '底栏必须保留绿点图例（已离线 N 章 · 绿点表示整章可离线阅读）')
  assert.ok(footer.includes('已离线 0 章'), '空闲态必须显示已离线章数（初始 0 章）')

  // 运行态与空闲态两种文案都还在（沿用既有键，不新造）
  const source = footerSegment(asideSegment(read('../src/MangaReaderScreen.tsx'), 'reader-drawer-left'))
  assert.ok(source.includes("t('manga.offlineHint'"), '必须保留 offlineHint（空闲态图例）')
  assert.ok(source.includes("t('manga.offlineRunning'"), '必须保留 offlineRunning（运行态提示）')
})

test('漫画目录底栏 - 四种语言的文案键齐全、非空且完全一致', () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const required = {
    manga: ['offlineCurrent', 'offlineCurrentTitle', 'offlineAllTitle', 'offlineHint', 'offlineRunning'],
    reader: ['cacheNext50', 'cacheNext50Title', 'cacheNext100', 'cacheNext100Title', 'cacheAll'],
  }

  const shapes = locales.map(locale => {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`)) as Record<string, Record<string, string>>
    for (const [namespace, keys] of Object.entries(required)) {
      assert.ok(data[namespace], `${locale} 必须包含 ${namespace} 命名空间`)
      for (const key of keys) {
        const value = data[namespace][key]
        assert.ok(
          typeof value === 'string' && value.trim().length > 0,
          `${locale}.${namespace}.${key} 必须存在且非空`,
        )
      }
    }
    // 底栏文案键在四语言里的集合必须一致（i18n 全量对齐测试会强制，这里提前锁住）
    return Object.keys(data.manga).sort().join(',')
  })
  for (const shape of shapes.slice(1)) {
    assert.equal(shape, shapes[0], '四种语言的 manga 键集合必须完全一致')
  }

  // 插值占位符必须留着，否则"已离线 N 章"会退化成没有数字的一句话
  for (const locale of locales) {
    const data = JSON.parse(read(`../src/i18n/locales/${locale}.json`)) as Record<string, Record<string, string>>
    assert.ok(data.manga.offlineHint.includes('{{count}}'), `${locale}.manga.offlineHint 必须保留 {{count}} 插值`)
  }
})
