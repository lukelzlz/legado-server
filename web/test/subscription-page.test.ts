import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { onlineImportRoute } from '../src/onlineImport'
import { rssIconSrc, SubscriptionPage, SUBSCRIPTION_KINDS } from '../src/SubscriptionPage'
import { rssBrowserId } from '../src/api'

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

test('一键导入 - 按类型分发（对齐开源阅读 OnLineImportActivity）', () => {
  assert.equal(onlineImportRoute('bookSource'), 'preview', '书源走预览勾选')
  assert.equal(onlineImportRoute('rssSource'), 'confirm')
  assert.equal(onlineImportRoute('replaceRule'), 'confirm')
  assert.equal(onlineImportRoute('httpTts'), 'confirm')
  assert.equal(onlineImportRoute('auto'), 'detect', '类型未知时交给服务端按内容判别')
  assert.equal(onlineImportRoute(undefined), 'detect')
  assert.equal(onlineImportRoute('theme'), 'unsupported', '本服务没有的类型要如实提示，不能当书源硬导')
  assert.equal(onlineImportRoute('addtobookshelf'), 'unsupported')
})

test('规则订阅 - 类型下标与开源阅读 RuleSub.type 一致', () => {
  assert.deepEqual([...SUBSCRIPTION_KINDS], ['bookSource', 'rssSource', 'replaceRule'])
})

test('订阅源 - 内置浏览器会话 id 带 rss:: 前缀，且与服务端常量一致', () => {
  assert.equal(rssBrowserId('http://a.example.com/gx.html'), 'rss::http://a.example.com/gx.html')
  assert.ok(read('../../server/src/main/kotlin/io/legado/server/Routes.kt').includes('RSS_BROWSER_PREFIX = "rss::"'))
})

test('订阅源 - 图标只放行 http(s) 与位图 data URI', () => {
  assert.equal(rssIconSrc('data:image/png;base64,iVBORw0KGgo='), 'data:image/png;base64,iVBORw0KGgo=')
  assert.equal(rssIconSrc('http://a.example.com/i.jpg'), 'http://a.example.com/i.jpg')
  assert.equal(rssIconSrc('data:image/svg+xml;base64,PHN2Zz4='), null, 'SVG 可内嵌脚本，不放行')
  assert.equal(rssIconSrc('data:text/html;base64,PGI+'), null)
  assert.equal(rssIconSrc('javascript:alert(1)'), null)
  assert.equal(rssIconSrc(''), null)
  assert.equal(rssIconSrc(null), null)
})

test('订阅页 - 同时提供订阅源与规则订阅两个分区，表单按钮不误触发提交', () => {
  const html = renderToStaticMarkup(React.createElement(SubscriptionPage, { onSourcesChange: () => {} }))
  assert.ok(html.includes('订阅源'), '必须有订阅源分区')
  assert.ok(html.includes('规则订阅'), '必须有规则订阅分区')
  for (const label of ['书源', '订阅源', '替换规则']) assert.ok(html.includes(`>${label}</option>`), `类型下拉缺少「${label}」`)
  assert.ok(html.includes('自动更新'))
  // 表单内的辅助按钮必须显式 type="button"（否则点击会触发表单提交）
  const forms = html.match(/<form[\s\S]*?<\/form>/g) ?? []
  assert.equal(forms.length, 2)
  for (const form of forms) {
    const buttons = form.match(/<button[^>]*>/g) ?? []
    assert.equal(buttons.filter(button => !button.includes('type="button"')).length, 1, '每个表单只能有一个提交按钮')
  }
})

test('内置浏览器 - 代理层拦截 legado:// 与 yuedu:// 全部一键导入链接并回报类型', () => {
  const proxy = read('../../server/src/main/kotlin/io/legado/server/WebViewProxy.kt')
  assert.ok(proxy.includes('(?:yuedu|legado):'), '两个协议名都要拦')
  assert.ok(proxy.includes("kind:kind"), '必须把类型回报给宿主')
  const modal = read('../src/SourceWebViewModal.tsx')
  assert.ok(modal.includes('data.kind'), '宿主必须读取类型并下传')
})

test('内置浏览器 - 订阅源模式下隐藏登录按钮和Cookie徽标，并展示源名称', () => {
  const modal = read('../src/SourceWebViewModal.tsx')
  assert.ok(modal.includes('isBrowserMode'), '必须具备普通网页/订阅源浏览器模式判据')
  assert.ok(modal.includes('source-webview-title'), '工具栏必须渲染标题标签')
  assert.ok(modal.includes('!isBrowserMode && cookieCount !== null'), '普通浏览器模式下不展示Cookie徽标')
  assert.ok(modal.includes('!isBrowserMode && ('), '普通浏览器模式下不展示登录完成按钮')
})
