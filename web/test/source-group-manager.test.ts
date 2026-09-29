import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { NEW_GROUP_OPTION, groupDeleteConfirmMessage, groupInitial, resolveTargetGroup, SourceGroupManagerModal } from '../src/SourceGroupManagerModal.tsx'

/**
 * 「分组管理」面板：结构 + 「不自动分组」的目标分组判定。
 *
 * 用户的三点要求在这里落锁：
 * ① 分组管理是**独立入口**（不再是批量管理里的一个动作）；
 * ② 分组可删除（且文案必须写明书源不会被删）；
 * ③ **不自动分组**：批量勾选后必须显式选定目标分组（已有分组或新名字），否则拒绝提交。
 */

const groups = [
  { name: '大灰狼聚合', sourceCount: 3, enabledCount: 2 },
  { name: '出版', sourceCount: 1, enabledCount: 1 },
]

const render = (props: Partial<React.ComponentProps<typeof SourceGroupManagerModal>> = {}) =>
  renderToStaticMarkup(
    React.createElement(SourceGroupManagerModal, {
      groups,
      onChanged: () => {},
      onClose: () => {},
      ...props,
    }),
  )

test('source group manager - renders group list with rename and delete entries', () => {
  const html = render()

  assert.ok(html.includes('分组管理'), '面板标题必须是「分组管理」')
  assert.ok(html.includes('aria-label="分组管理"'))
  assert.ok(html.includes('大灰狼聚合'))
  assert.ok(html.includes('3 个'), '要给出组内总数')
  assert.ok(html.includes('启用 2'), '也要给出已启用数（停用源不参与搜索）')
  assert.ok(html.includes('aria-label="重命名"'), '分组可重命名（图标按钮，与书架分组管理一致）')
  assert.ok(html.includes('aria-label="删除"'), '分组可删除')
  assert.ok(html.includes('书源加入分组'), '要有「把书源加入分组」区域')
  assert.ok(html.includes('>加入分组<'))
  assert.ok(html.includes('>移出分组<'), '也要能一次把书源移出分组（回到未分组）')
  // 复用书架分组管理的骨架类：弹窗外壳/头部/底部一致，避免出现第二套视觉语言
  assert.ok(html.includes('group-manage-modal'))
  assert.ok(html.includes('group-manage-header'))
  assert.ok(html.includes('group-manage-footer'))
})

test('source group manager - target group must be chosen explicitly (no auto grouping)', () => {
  // 已有分组名（含首尾空白）→ 取 trim 后的名字
  assert.equal(resolveTargetGroup('出版', ''), '出版')
  assert.equal(resolveTargetGroup('  出版  ', ''), '出版')

  // 「新建分组…」必须同时填名字，否则视为未选择（不能悄悄拿书源名/其他字段当分组名）
  assert.equal(resolveTargetGroup(NEW_GROUP_OPTION, '  聚合源 '), '聚合源')
  assert.equal(resolveTargetGroup(NEW_GROUP_OPTION, '   '), null)

  // 严禁使用系统保留字 __ungrouped__
  assert.equal(resolveTargetGroup('__ungrouped__', ''), null)
  assert.equal(resolveTargetGroup('  __UNGROUPED__ ', ''), null)
  assert.equal(resolveTargetGroup(NEW_GROUP_OPTION, '__ungrouped__'), null)

  // 没选任何目标 → null（调用方据此提示「请选择目标分组」，而不是默认塞进某个组）
  assert.equal(resolveTargetGroup('', ''), null)
  assert.equal(resolveTargetGroup('   ', ''), null)
})

test('source group manager - delete confirmation wording keeps the sources', () => {
  // 删除的是**分组**，不是书源：这句话必须由面板统一给出，且带上组内数量。
  const message = groupDeleteConfirmMessage({ name: '大灰狼聚合', sourceCount: 3, enabledCount: 2 })
  assert.ok(message.includes('删除分组「大灰狼聚合」'))
  assert.ok(message.includes('3 个书源不会被删除'), '必须写明组内书源不会被删')
  assert.ok(message.includes('未分组'), '必须说明这些书源会变成未分组')
})

/**
 * UI 改版（更美观）的结构锁：头部概览数字、两个分区标题、分组卡片首字标记、底部选中摘要。
 *
 * 这里只锁「结构存在」，不锁像素 —— 视觉是否好看由真机截图取证，测试负责防止改版时把
 * 概览/分区/标记这些信息层级悄悄丢掉（它们不是装饰，而是在回答「现在什么状态」）。
 */
test('source group manager - header stats, sections and group avatar are present', () => {
  const html = render()

  assert.ok(html.includes('aria-label="书源分组概览"'), '头部要有分组概览')
  assert.ok(html.includes('sgm-stat'), '概览要由数字块组成')
  assert.ok(html.includes('未分组'), '概览必须包含「未分组」这一项')

  assert.ok(html.includes('已有分组'), '分组列表要有分区标题')
  assert.ok(html.includes('sgm-section'), '两段内容要有分区骨架')
  assert.ok(html.includes('sgm-group-avatar'), '分组卡片要有首字标记')

  assert.ok(html.includes('sgm-search'), '筛选框要有图表化的搜索框包装')
  assert.ok(html.includes('勾选书源后可批量归类'), '底部要给出当前选中摘要')
})

test('source group manager - groupInitial never renders undefined and handles emoji properly', () => {
  assert.equal(groupInitial('大灰狼聚合'), '大')
  assert.equal(groupInitial('  ab  '), 'A', '取首个非空白字符并大写')
  assert.equal(groupInitial('   '), '', '空名兜底成空串，而不是渲染出 undefined')
  assert.equal(groupInitial('📚小说'), '📚', 'Emoji 代理对不被截断')
})
