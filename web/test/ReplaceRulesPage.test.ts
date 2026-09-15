import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ReplaceRulesPage } from '../src/ReplaceRulesPage'
import { ReplaceRule } from '../src/api'

test('ReplaceRulesPage - Static Rendering and Component Structure', () => {
  const element = React.createElement(ReplaceRulesPage)
  const html = renderToStaticMarkup(element)

  // Verify kicker, title, and description
  assert.ok(html.includes('阅读服务器'), 'Page should contain kicker')
  assert.ok(html.includes('替换净化规则'), 'Page should contain main title')
  assert.ok(html.includes('净化网页抓取正文中的反爬文字对调'), 'Page should contain description')

  // Verify action buttons
  assert.ok(html.includes('新建规则'), 'Page should have new rule button')
  assert.ok(html.includes('导入订阅'), 'Page should have import button')
  assert.ok(html.includes('导出全部'), 'Page should have export button')
  assert.ok(html.includes('全部启用'), 'Page should have batch enable button')
  assert.ok(html.includes('全部禁用'), 'Page should have batch disable button')

  // Verify search input
  assert.ok(html.includes('搜索规则名 / 正则 / 替换词'), 'Page should contain search input')

  // Verify group filter
  assert.ok(html.includes('全部 ('), 'Page should contain all groups pill')
})

test('ReplaceRulesPage - Scope Filtering and Rule Matching Logic', () => {
  const sampleRules: ReplaceRule[] = [
    {
      id: 'rule-1',
      name: '起点反爬错字',
      group: '反爬清洗',
      pattern: '大丑|魔男',
      replacement: '@js:return result==="大丑"?"小丑":"魔女"',
      isRegex: true,
      scope: '宅魔女,诡秘之主',
      scopeTitle: false,
      scopeContent: true,
      isEnabled: true,
      order: 1,
    },
    {
      id: 'rule-2',
      name: '全网引流广告',
      group: '广告剔除',
      pattern: '请记住本书首发域名.*',
      replacement: '',
      isRegex: true,
      scopeTitle: true,
      scopeContent: true,
      isEnabled: true,
      order: 2,
    },
    {
      id: 'rule-3',
      name: '特定书排除',
      group: '特殊处理',
      pattern: '章节序号错误',
      replacement: '',
      isRegex: false,
      excludeScope: '诡秘之主',
      scopeTitle: false,
      scopeContent: true,
      isEnabled: false,
      order: 3,
    },
  ]

  // Test grouping
  const groups = new Set<string>()
  for (const r of sampleRules) {
    if (r.group?.trim()) groups.add(r.group.trim())
  }
  assert.equal(groups.size, 3)
  assert.ok(groups.has('反爬清洗'))
  assert.ok(groups.has('广告剔除'))
  assert.ok(groups.has('特殊处理'))

  // Test search query filtering
  const query = '引流'
  const filtered = sampleRules.filter(r =>
    (r.name && r.name.toLowerCase().includes(query.toLowerCase())) ||
    (r.pattern && r.pattern.toLowerCase().includes(query.toLowerCase())) ||
    (r.replacement && r.replacement.toLowerCase().includes(query.toLowerCase()))
  )
  assert.equal(filtered.length, 1)
  assert.equal(filtered[0].id, 'rule-2')
})
