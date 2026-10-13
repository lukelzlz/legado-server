import assert from 'node:assert/strict'
import test from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { ReplaceRulesPage } from '../src/ReplaceRulesPage'
import {
  NEW_RULE_GROUP_OPTION,
  resolveRuleTargetGroup,
  ruleGroupDeleteConfirmMessage,
  ruleGroupInitial,
  ReplaceRuleGroupManagerModal,
} from '../src/ReplaceRuleGroupManagerModal'
import { ReplaceRuleBatchMoveModal } from '../src/ReplaceRuleBatchMoveModal'
import { ReplaceRuleGroupSummary } from '../src/api'

test('ReplaceRuleGroupManagerModal - helpers and target group resolution', () => {
  assert.equal(resolveRuleTargetGroup('广告过滤', ''), '广告过滤')
  assert.equal(resolveRuleTargetGroup(NEW_RULE_GROUP_OPTION, '新规则组'), '新规则组')
  assert.equal(resolveRuleTargetGroup(NEW_RULE_GROUP_OPTION, '   '), null)
  assert.equal(resolveRuleTargetGroup('__ungrouped__', ''), null)
  assert.equal(resolveRuleTargetGroup('__UNGROUPED__', ''), null)

  const group: ReplaceRuleGroupSummary = { name: '错字修正', ruleCount: 15, enabledCount: 12 }
  const confirmMsg = ruleGroupDeleteConfirmMessage(group)
  assert.ok(confirmMsg.includes('错字修正'))
  assert.ok(confirmMsg.includes('15'))
  assert.ok(confirmMsg.includes('不会被删除'))

  assert.equal(ruleGroupInitial('广告'), '广')
  assert.equal(ruleGroupInitial('  regex  '), 'R')
  assert.equal(ruleGroupInitial(''), '')
  assert.equal(ruleGroupInitial('🛡️过滤'), '🛡')
})

test('ReplaceRulesPage - renders batch management and group management buttons in sidebar', () => {
  const html = renderToStaticMarkup(React.createElement(ReplaceRulesPage))

  // 1. 标题与侧边栏操作区
  assert.ok(html.includes('source-sidebar-top-actions'), '应包含侧栏顶部动作栏')
  assert.ok(html.includes('batch-mode-btn'), '应包含批量管理按钮')
  assert.ok(html.includes('group-manager-btn'), '应包含分组管理按钮')
  assert.ok(html.includes('rules-toolbar'), '应包含规则工具条')
})

test('ReplaceRuleGroupManagerModal - static rendering structure and initial stats', () => {
  const mockGroups: ReplaceRuleGroupSummary[] = [
    { name: '广告过滤', ruleCount: 12, enabledCount: 10 },
    { name: '反爬乱码', ruleCount: 8, enabledCount: 8 },
  ]
  const html = renderToStaticMarkup(
    React.createElement(ReplaceRuleGroupManagerModal, {
      groups: mockGroups,
      onChanged: () => {},
      onClose: () => {},
    })
  )

  // 概览统计数字
  assert.ok(html.includes('sgm-overview'), '应包含顶部概览卡片')
  assert.ok(html.includes('已有分组'), '应展示已有分组统计')
  assert.ok(html.includes('规则总数'), '应展示规则总数统计')

  // 分组项
  assert.ok(html.includes('广告过滤'), '列表中应渲染「广告过滤」分组')
  assert.ok(html.includes('反爬乱码'), '列表中应渲染「反爬乱码」分组')
  assert.ok(html.includes('规则归类工作台'), '应包含归类工作台区域')
})

test('ReplaceRuleBatchMoveModal - static rendering options', () => {
  const mockGroups: ReplaceRuleGroupSummary[] = [
    { name: 'VIP截断', ruleCount: 5, enabledCount: 5 },
    { name: '排版美化', ruleCount: 20, enabledCount: 18 },
  ]
  const html = renderToStaticMarkup(
    React.createElement(ReplaceRuleBatchMoveModal, {
      groups: mockGroups,
      selectedCount: 3,
      onClose: () => {},
      onSelectGroup: async () => {},
    })
  )

  assert.ok(html.includes('batch-move-modal'), '应包含批量移动弹窗容器')
  assert.ok(html.includes('未分组'), '应包含未分组解绑选项')
  assert.ok(html.includes('VIP截断'), '应包含目标分组选项')
  assert.ok(html.includes('排版美化'), '应包含目标分组选项')
  assert.ok(html.includes('3'), '应显示已选择 3 条规则')
})
