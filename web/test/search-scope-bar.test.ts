import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { removeGroupSourceOverrides, SearchScopeBar } from '../src/SearchScopeBar.tsx'
import { expandGroupWithoutSource, SearchScopePickerModal, selectedGroupForSource } from '../src/SearchScopePickerModal.tsx'
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

test('search scope bar - source picker entry comes before all-sources and group tabs', () => {
  const html = render({ onOpenPicker: () => {} })
  const pickerIndex = html.indexOf('选择书源')
  const allIndex = html.indexOf('全部书源')
  const groupIndex = html.indexOf('大灰狼聚合')
  assert.ok(pickerIndex >= 0, '必须保留书源选择入口')
  assert.ok(pickerIndex < allIndex, '书源选择入口应排在全部书源之前')
  assert.ok(allIndex < groupIndex, '全部书源仍应排在分组选项之前')
})

test('search scope bar - selected group name is visible on the picker entry', () => {
  const html = render({
    selectedGroups: [UNGROUPED_SOURCE_GROUP],
    selectedSourceIds: [],
    onOpenPicker: () => {},
  })
  assert.ok(html.includes('已选分组：未分组'), '入口必须显示具体分组名称，而不是只显示数量')
})

test('search scope bar - mixed multi-scope selection exposes the picker summary', () => {
  const html = render({
    selectedGroups: ['大灰狼聚合', '出版'],
    selectedSourceIds: ['https://a.example/', 'https://b.example/'],
    onOpenPicker: () => {},
    onToggleGroup: () => {},
  })
  assert.ok(html.includes('2 个分组 + 2 个书源'))
  assert.ok(html.includes('scope-picker-trigger active'))
  assert.ok(html.includes('aria-selected="true"'))
})

test('search scope picker - only renders source selection because groups stay outside', () => {
  const html = renderToStaticMarkup(
    React.createElement(SearchScopePickerModal, {
      selectedGroups: ['出版'],
      selectedSourceIds: ['https://a.example/'],
      onApply: () => {},
      onClose: () => {},
    }),
  )
  assert.ok(html.includes('选择搜索范围'))
  assert.equal(html.includes('选择分组'), false, '弹窗不再重复提供外层的分组选择')
  assert.ok(html.includes('选择单个书源'))
  assert.ok(html.includes('已选 1 个分组 + 1 个书源'))
  assert.equal(html.includes('search-scope-picker-selected-groups'), false, '分组不再显示在弹窗顶部')
  assert.ok(html.includes('输入书源名称、地址或分组名称'))
})

test('search scope picker - selected groups check their sources including ungrouped', () => {
  assert.equal(selectedGroupForSource(sources[1], ['出版']), '出版')
  assert.equal(selectedGroupForSource(sources[1], ['其他']), undefined)
  assert.equal(selectedGroupForSource(sources[0], [UNGROUPED_SOURCE_GROUP]), UNGROUPED_SOURCE_GROUP)
})

test('search scope picker - excluding one group member preserves every other member', () => {
  const next = expandGroupWithoutSource(
    ['出版', '其他'],
    ['https://outside.example/', 'https://a.example/'],
    '出版',
    'https://a.example/',
    ['https://a.example/', 'https://b.example/', 'https://c.example/'],
  )
  assert.deepEqual(next.groups, ['其他'], '只取消当前书源对应的分组')
  assert.deepEqual(
    next.sourceIds,
    ['https://outside.example/', 'https://b.example/', 'https://c.example/'],
    '保留额外书源及同组其他成员，只排除当前书源',
  )
})

test('search scope bar - reselecting a group reclaims its remaining source overrides', () => {
  const sourceIds = removeGroupSourceOverrides(
    ['https://outside.example/', 'https://b.example/', 'https://c.example/'],
    ['https://a.example/', 'https://b.example/', 'https://c.example/'],
  )
  assert.deepEqual(sourceIds, ['https://outside.example/'], '组内遗留独立勾选被收回，组外单源不受影响')

  const main = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')
  assert.ok(main.includes("void api.sourcePicker('', [group])"), '外层选组必须查询该组成员')
  assert.ok(main.includes('removeGroupSourceOverrides('), '外层选组必须实际清理组内独立勾选')
  assert.ok(main.includes('searchStore.setSelectedSourceIds(sourceIds)'), '清理必须作用于最新搜索范围')
})

test('search scope picker - blank query loads and displays all sources', () => {
  const source = readFileSync(new URL('../src/SearchScopePickerModal.tsx', import.meta.url), 'utf8')
  assert.ok(source.includes('void api.sourcePicker(trimmed, [], controller.signal)'), '空关键词也必须调用候选接口')
  assert.equal(source.includes('if (!trimmed)'), false, '不得再对空关键词提前返回空列表')
  assert.equal(source.includes('!query.trim()'), false, '空关键词不得显示提示或隐藏候选列表')
  assert.ok(source.includes('!loading && !error && items.map'), '接口返回的全部候选必须直接进入渲染列表')
})
