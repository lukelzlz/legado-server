import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { readFileSync } from 'node:fs'
import { renderToStaticMarkup } from 'react-dom/server'
import { changeAppLanguage, resources, SUPPORTED_LOCALES } from '../src/i18n'
import { ShelfKindTag } from '../src/ShelfKindTag'

// 书架卡片右上角的书籍类型角标：漫画 / 听书 / 小说。
// 用户需求：「给小说右上角也添上标签」——即 bookKind 不是 comic/audio 时按小说显示。
//
// 组件渲染期**不读 `location`**：这里是 `renderToStaticMarkup` 的无头渲染。

const TAG_CLASSES = ['shelf-card-tag-comic', 'shelf-card-tag-audio', 'shelf-card-tag-novel'] as const

/** 渲染类型角标，并返回每个角标 class 出现的次数。 */
function renderTagCounts(bookKind?: string): Record<string, number> {
  const html = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind }))
  return Object.fromEntries(TAG_CLASSES.map(cls => [cls, html.split(cls).length - 1]))
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
    }
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - 漫画 / 听书绝不渲染小说角标（右上角不会出现两个标签）', async () => {
  try {
    await changeAppLanguage('zh-CN')

    const comic = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind: 'comic' }))
    assert.ok(comic.includes('shelf-card-tag-comic'), '漫画书必须渲染漫画角标')
    assert.ok(!comic.includes('shelf-card-tag-novel'), '漫画书不得再渲染小说角标（否则右上角叠两个标签）')

    const audio = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind: 'audio' }))
    assert.ok(audio.includes('shelf-card-tag-audio'), '听书必须渲染听书角标')
    assert.ok(!audio.includes('shelf-card-tag-novel'), '听书不得再渲染小说角标（否则右上角叠两个标签）')
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - 任一 bookKind 都恰好只有一个类型角标', () => {
  for (const bookKind of ['novel', 'comic', 'audio', undefined, 'video'] as const) {
    const counts = renderTagCounts(bookKind)
    const total = TAG_CLASSES.reduce((sum, cls) => sum + counts[cls], 0)
    assert.equal(
      total,
      1,
      `bookKind=${String(bookKind)} 应恰好渲染 1 个类型角标，实际 ${JSON.stringify(counts)}`,
    )
  }
})

test('书架角标 - 四种语言的漫画 / 听书 / 小说徽标文案都正确', async () => {
  const expected: Array<[Parameters<typeof changeAppLanguage>[0], { comic: string; audio: string; novel: string }]> = [
    ['zh-CN', { comic: '漫画', audio: '听书', novel: '小说' }],
    ['zh-TW', { comic: '漫畫', audio: '聽書', novel: '小說' }],
    ['en-US', { comic: 'Comic', audio: 'Audio', novel: 'Novel' }],
    ['ja-JP', { comic: '漫画', audio: '朗読', novel: '小説' }],
  ]

  try {
    for (const [locale, texts] of expected) {
      await changeAppLanguage(locale)
      for (const [bookKind, text] of [['comic', texts.comic], ['audio', texts.audio], ['novel', texts.novel]] as const) {
        const html = renderToStaticMarkup(React.createElement(ShelfKindTag, { bookKind }))
        assert.ok(html.includes(`>${text}<`), `${locale} 的 ${bookKind} 角标文案应为「${text}」`)
      }
    }
  } finally {
    await changeAppLanguage('zh-CN')
  }
})

test('书架角标 - comicTag / audioTag / novelTag 四语言都存在且非空（不许再靠中文默认值兜底）', () => {
  const keys = ['shelf.comicTag', 'shelf.audioTag', 'shelf.novelTag'] as const

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

test('书架角标 - .shelf-card-tag-novel 几何与漫画 / 听书完全一致，仅背景色为深绿', () => {
  const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

  const ruleBody = (cls: string): string => {
    const matched = css.match(new RegExp(`\\.${cls}\\s*\\{([^}]*)\\}`))
    assert.ok(matched, `styles.css 缺少 .${cls}`)
    return matched![1]
  }

  // 去掉背景色后应逐字相同：位置/内边距/圆角/字号/字重/字距/毛玻璃/阴影不许各写各的。
  const withoutBackground = (body: string) => body.replace(/background:[^;]*;?/, '').replace(/\s+/g, ' ').trim()
  const novel = ruleBody('shelf-card-tag-novel')

  assert.equal(withoutBackground(novel), withoutBackground(ruleBody('shelf-card-tag-comic')), '小说角标几何必须与漫画角标一致')
  assert.equal(withoutBackground(novel), withoutBackground(ruleBody('shelf-card-tag-audio')), '小说角标几何必须与听书角标一致')

  const background = novel.match(/background:([^;]*)/)?.[1] ?? ''
  assert.match(background, /rgba\(21,\s*128,\s*61,\s*0\.92\)/, '小说角标背景色必须是深绿 rgba(21, 128, 61, 0.92)')
  for (const other of ['rgba(180, 83, 9, 0.92)', 'rgba(29, 78, 216, 0.92)']) {
    assert.ok(!background.includes(other), `小说角标背景色不得与既有角标重复：${other}`)
  }
})

test('书架角标 - ShelfPage 确实挂载 ShelfKindTag（本地书 = 左上「本地」+ 右上「小说」，现状如此）', () => {
  // main.tsx 末尾有 createRoot(document.getElementById('root')!)，无法在 Node 里 import，
  // 因此这里只做接线断言：保证抽出的组件没有变成没人用的死代码。
  const source = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')

  assert.ok(
    source.includes('<ShelfKindTag bookKind={item.bookKind} />'),
    '书架卡片必须挂载 <ShelfKindTag bookKind={item.bookKind} />',
  )
  assert.ok(
    source.includes('{isLocal && <span className="shelf-card-tag-local">'),
    '左上角「本地」角标应保持原样',
  )
  // 现状（用户已知悉，未要求更改）：ShelfKindTag 不看 sourceId，而本地书 bookKind 通常是 novel，
  // 因此本地书会同时有左上「本地」与右上「小说」。若日后决定本地书不出小说标签，
  // 需要在本测试中把这条接线断言一并改掉（这正是它存在的意义）。
})
