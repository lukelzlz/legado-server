import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { WebDavSettingsPage } from '../src/WebDavSettingsPage'

/**
 * 阅读进度同步（手机端 bookProgress）在「文件」设置页的呈现。
 *
 * 这部分能力把网页与 Legado 手机的阅读进度打通，属于用户可见的核心配置，
 * 因此除了服务端单测，也锁定 UI 文案与关键提示（避免被后续改动无声抹掉）。
 */
test('WebDavSettingsPage - renders progress sync section', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  assert.ok(html.includes('阅读进度同步'), 'page should contain the progress sync section title')
  assert.ok(html.includes('进度文件夹名'), 'page should label the folder setting')
  assert.ok(html.includes('bookProgress'), 'page should show the default folder name as placeholder')

  // 必须解释清楚「相对 WebDAV 根目录」且给出手机端常见的 legado 层级，
  // 否则用户会填错层级（实测踩过：默认少了 legado 一层就读不到真实进度文件）
  assert.ok(html.includes('legado/bookProgress'), 'hint should mention the legado/bookProgress layout')
  assert.ok(html.includes('只允许单层目录名') || html.includes('相对 WebDAV 根目录'), 'hint should explain the path semantics')

  assert.ok(html.includes('保存'), 'page should offer a save button')
})
