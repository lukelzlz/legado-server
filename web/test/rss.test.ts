import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { RssPage } from '../src/RssPage'

/**
 * `RssPage` 静态渲染测试。
 *
 * ⚠️ `renderToStaticMarkup` **不执行 `useEffect`**，因此这里只能断言「首屏无数据态」的文案
 * （标题、按钮、空态）。依赖接口返回的内容在静态渲染时不存在 —— 这是既有页面的统一约定
 * （见 `ReplaceRulesPage.test.ts`）。
 *
 * 同时验证组件在 **Node 环境**下可渲染（渲染期不得访问 `location`/`window`/`localStorage`）。
 */
test('RssPage - static rendering and page structure', () => {
  const html = renderToStaticMarkup(React.createElement(RssPage))

  // 页头：kicker + 标题 + 说明
  assert.ok(html.includes('订阅'), 'Page should contain the section kicker')
  assert.ok(html.includes('订阅源'), 'Page should contain the feed title')
  assert.ok(html.includes('抓取订阅源的文章列表'), 'Page should contain the page description')

  // 侧栏：源列表标题与计数徽标
  assert.ok(html.includes('rules-sidebar'), 'Page should reuse the web sidebar layout')
  assert.ok(html.includes('rules-count-badge'), 'Page should render the count badge')

  // 主操作按钮
  assert.ok(html.includes('全部刷新'), 'Page should have a refresh-all button')
  assert.ok(html.includes('导入'), 'Page should have an import button')
  assert.ok(html.includes('立即刷新'), 'Page should have a refresh button')
  assert.ok(html.includes('只看未读'), 'Page should have an unread-only toggle')
  assert.ok(html.includes('全部已读'), 'Page should have a mark-all-read button')

  // 空态提示（首屏无数据时）
  assert.ok(html.includes('还没有订阅源'), 'Page should show the empty feeds hint')
  assert.ok(html.includes('在左侧选择一个订阅源'), 'Page should show the select-a-feed hint')

  // 导入弹窗默认关闭
  assert.ok(!html.includes('modal-backdrop'), 'Import modal should be closed initially')
  assert.ok(!html.includes('选择文件'), 'Import modal contents should not render initially')
})

test('RssPage - reuses web-consistent styling and avoids phone-only classes', () => {
  const html = renderToStaticMarkup(React.createElement(RssPage))

  // 复用既有布局类，而不是照搬手机版视觉
  assert.ok(html.includes('rules-page-container'), 'Should reuse the existing page grid')
  assert.ok(html.includes('rules-content-area'), 'Should reuse the existing content area')
  assert.ok(html.includes('page-title'), 'Should reuse the existing page title block')
  assert.ok(html.includes('primary-button'), 'Should reuse the existing primary button')
  assert.ok(html.includes('subtle-button'), 'Should reuse the existing subtle button')

  // 刻意不引入手机版专属结构
  assert.ok(!html.includes('mui-'), 'Should not pull in mobile-only markup')
})
