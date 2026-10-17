import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { BookCover, coverSources } from '../src/BookCover'

// 书架封面必须**不裂图**。
//
// 用户实测：「导入书籍封面全是裂图」。两条根因：
// 1. 备份里的 `coverUrl` 多为**带过期签名**的图床地址（实测番茄 `x-expires` 已过期 ⇒ HTTP 403）
//    ⇒ 补抓失败、没有本地副本；此时若前端只认 `coverKey`，就没有任何可显示来源；
// 2. `coverKey` 有值但服务端取不到文件（历史数据/文件被清）时，`<img>` 只会画一个裂图图标，
//    既**不会回退到 `coverUrl`**，也**不会回退到文字占位**。
//
// 修复方式：候选链「本地副本 → 外链」逐个 `onError` 回退，全部失败显示首字占位。

const source = readFileSync(new URL('../src/main.tsx', import.meta.url), 'utf8')

test('coverSources - 本地副本优先，外链作为第二候选', () => {
  assert.deepEqual(
    coverSources({ coverKey: 'a'.repeat(64), coverUrl: 'https://img.example.com/c.jpg' }),
    ['/api/covers/' + 'a'.repeat(64), 'https://img.example.com/c.jpg'],
    '必须既能用本地副本，也能在外链上兜底',
  )
})

test('coverSources - 只有外链时直接用外链，没有任何来源时返回空', () => {
  assert.deepEqual(coverSources({ coverUrl: 'https://img.example.com/c.jpg' }), ['https://img.example.com/c.jpg'])
  assert.deepEqual(coverSources({}), [])
  assert.deepEqual(coverSources({ coverUrl: '   ' }), [])
})

test('coverSources - 非法协议不进候选（防 javascript:/data: 之类）', () => {
  assert.deepEqual(coverSources({ coverUrl: 'javascript:alert(1)' }), [])
  assert.deepEqual(coverSources({ coverKey: 'k', coverUrl: 'javascript:alert(1)' }), ['/api/covers/k'])
})

test('coverSources - 自引用（coverUrl 就是自己的 covers 接口）不会重复请求', () => {
  const key = 'b'.repeat(64)
  assert.deepEqual(
    coverSources({ coverKey: key, coverUrl: `/api/covers/${key}` }),
    [`/api/covers/${key}`],
    '同一个地址只该试一次',
  )
})

test('BookCover - 首帧渲染本地副本，并声明 no-referrer', () => {
  const html = renderToStaticMarkup(
    React.createElement(BookCover, { coverKey: 'c'.repeat(64), coverUrl: 'https://img.example.com/c.jpg', name: '测试书' }),
  )
  assert.ok(html.includes('src="/api/covers/' + 'c'.repeat(64) + '"'), `首帧必须是本地副本：${html}`)
  assert.ok(html.toLowerCase().includes('referrerpolicy="no-referrer"'), `外链要带 no-referrer 绕开防盗链：${html}`)
  assert.ok(html.includes('alt=""'), '封面图是装饰性图片（书名已在旁边显示）')
})

test('BookCover - 没有任何可用地址时显示首字占位而不是空 img', () => {
  const html = renderToStaticMarkup(React.createElement(BookCover, { name: '测试书' }))
  assert.equal(html.includes('<img'), false, `没有地址时不得渲染 img（否则就是裂图图标）：${html}`)
  assert.ok(html.includes('测'), `应显示书名首字占位：${html}`)
})

test('BookCover - 占位与图片使用各自的 class（样式不能混用）', () => {
  const placeholder = renderToStaticMarkup(
    React.createElement(BookCover, { name: '书', fallbackClassName: 'cover-fallback' }),
  )
  assert.ok(placeholder.includes('class="cover-fallback"'), placeholder)
  const img = renderToStaticMarkup(
    React.createElement(BookCover, { coverKey: 'k', name: '书', fallbackClassName: 'cover-fallback' }),
  )
  assert.equal(img.includes('cover-fallback'), false, `图片不该带占位的 class：${img}`)
})

test('书架三处封面渲染都走 BookCover（不再用裸 img + coverKey 单点）', () => {
  const occurrences = source.split('<BookCover').length - 1
  assert.ok(occurrences >= 3, `至少三处（书籍管理/清缓存/书架卡片）应改用 BookCover，实际 ${occurrences}`)
  assert.equal(
    /resolveShelfCover\(item\)\s*\?\s*<img/.test(source),
    false,
    '不能再出现「resolveShelfCover(item) ? <img ...>」这种单点渲染：coverKey 404 时会直接裂图',
  )
})
