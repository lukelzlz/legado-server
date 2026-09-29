import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { SearchScopeBar } from '../src/SearchScopeBar.tsx'
import { UNGROUPED_SOURCE_GROUP } from '../src/api.ts'

/**
 * 书库页「搜索栏下面的分组选项卡」静态渲染。
 *
 * 需求明确要求：① 分组选择在**书库**页搜索栏下面；② **必须留一个「全部书源」选项卡**。
 * 这两点是纯 DOM 结构事实，静态渲染即可锁定（无需起服务）。
 */
const groups = [
  { name: '大灰狼聚合', sourceCount: 12, enabledCount: 9 },
  { name: '出版', sourceCount: 3, enabledCount: 3 },
]
const sources = [
  { id: 'https://a.example/', name: '书源A', url: 'https://a.example/', enabled: true, isJsSource: false, hasLogin: false, updatedAt: 0, version: 1 },
  { id: 'https://b.example/', name: '书源B', url: 'https://b.example/', group: '出版', enabled: true, isJsSource: false, hasLogin: false, updatedAt: 0, version: 1 },
]

const render = (overrides: Partial<React.ComponentProps<typeof SearchScopeBar>> = {}) =>
  renderToStaticMarkup(
    React.createElement(SearchScopeBar, {
      groups,
      sources,
      selectedGroup: '',
      selectedSourceId: '',
      onSelectAll: () => {},
      onSelectGroup: () => {},
      onSelectSource: () => {},
      ...overrides,
    }),
  )

test('search scope bar - lives under the search box with an explicit all-sources tab', () => {
  const html = render()

  assert.ok(html.includes('role="tablist"'), '搜索范围应是一组选项卡')
  assert.ok(html.includes('搜索范围'), '需要「搜索范围」这行说明文字')

  // 「全部书源」选项卡必须存在，而且排在所有分组之前（默认项）
  const allIndex = html.indexOf('全部书源')
  const firstGroupIndex = html.indexOf('大灰狼聚合')
  assert.ok(allIndex >= 0, '必须保留「全部书源」选项卡')
  assert.ok(firstGroupIndex > allIndex, '「全部书源」应是第一项，位于分组选项卡之前')

  // 每个分组一个选项卡，并露出「已启用」数量（停用的源不参与搜索，显示总数会误导）
  assert.ok(html.includes('出版'))
  assert.ok(html.includes('<small>9</small>'), '分组选项卡应显示已启用书源数')
  assert.ok(html.includes('未分组'), '未分组范围同样可选')
  assert.equal(html.includes('group:'), false, '不应把取值编码泄漏进 DOM')

  // 默认选中「全部书源」：唯一激活的选项卡是它
  assert.equal((html.match(/scope-tab active/g) || []).length, 1)
  assert.ok(
    html.includes('aria-selected="true" class="scope-tab active" title="不限定分组，搜索全部已启用的书源">全部书源'),
    '默认应停留在「全部书源」',
  )
})

test('search scope bar - selecting a group activates exactly that tab', () => {
  const html = render({ selectedGroup: '出版' })

  assert.equal((html.match(/scope-tab active/g) || []).length, 1, '同时只能有一个范围生效')
  assert.ok(html.includes('aria-selected="true" class="scope-tab active"'))
  assert.ok(html.includes('>出版<small>3</small>'))
  // 「全部书源」不再激活，但仍必须在（用户随时能切回去）
  assert.ok(html.includes('全部书源'))
  assert.equal(html.includes('title="不限定分组，搜索全部已启用的书源">全部书源') && html.includes('aria-selected="true" class="scope-tab active" title="不限定分组'), false)
})

test('search scope bar - ungrouped and single source scopes', () => {
  const ungrouped = render({ selectedGroup: UNGROUPED_SOURCE_GROUP })
  assert.ok(
    ungrouped.includes('aria-selected="true" class="scope-tab active"') && ungrouped.includes('>未分组<'),
    '未分组选项卡应可被选中',
  )

  const single = render({ selectedSourceId: 'https://a.example/' })
  assert.ok(single.includes('指定单个书源'), '仍要保留「指定单个书源」这条既有能力')
  assert.ok(single.includes('书源A'))
  assert.equal((single.match(/scope-tab active/g) || []).length, 0, '选了单源时分组选项卡都不应处于激活态')
})
