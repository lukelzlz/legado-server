import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import {
  ILLUSTRATION_PROXY_PATH,
  illustrationProxyUrl,
  parseIllustrationTag,
  splitIllustrationSegments,
  stripIllustrations,
  stripIllustrationTags,
} from '../src/inlineIllustrations.ts'
import {
  IllustrationLightbox,
  InlineIllustration,
  buildIllustrationButtonProps,
  buildLightboxBackdropProps,
  illustrationImageView,
} from '../src/ReaderIllustration.tsx'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const context = {
  sourceId: 'https://s1.test',
  bookUrl: 'https://book.example/1',
  chapterUrl: 'https://book.example/c/1',
  chapterIndex: 3,
}
const upstream = 'https://cdn.example.com/a.jpg?w=1&h=2'

test('inline illustrations - segments split text and images in document order', () => {
  const line = '文字一<img src="https://cdn.example.com/a.jpg" alt="图A">文字二'
  const segments = splitIllustrationSegments(line)
  assert.deepEqual(segments, [
    { kind: 'text', text: '文字一' },
    { kind: 'image', src: 'https://cdn.example.com/a.jpg', alt: '图A' },
    { kind: 'text', text: '文字二' },
  ])

  // 整行只有一张图（服务端让插图独占一行）：不应产生空文字片段
  assert.deepEqual(splitIllustrationSegments('<img src="https://cdn.example.com/b.png">'), [
    { kind: 'image', src: 'https://cdn.example.com/b.png', alt: '' },
  ])
  // 没有插图：返回空数组（调用方走原来的纯文本渲染，零开销）
  assert.deepEqual(splitIllustrationSegments('纯文字段落'), [])
  // 危险协议即使漏到前端也绝不渲染（服务端已经过滤，这里是第二道闸）
  assert.deepEqual(splitIllustrationSegments('前<img src="javascript:alert(1)">后'), [{ kind: 'text', text: '前后' }])
  assert.equal(parseIllustrationTag('<img src="data:image/png;base64,AA">'), null)
})

test('inline illustrations - proxy url carries the book context and a decoded upstream url', () => {
  const url = illustrationProxyUrl(context, upstream)
  assert.ok(url.startsWith(`${ILLUSTRATION_PROXY_PATH}?`), url)
  // 相对路径：不依赖 location.origin（组件在 Node 里渲染也不会炸）
  assert.ok(url.startsWith('/'), url)
  assert.ok(url.includes('sourceId=https%3A%2F%2Fs1.test'), url)
  assert.ok(url.includes('bookUrl=https%3A%2F%2Fbook.example%2F1'), url)
  assert.ok(url.includes('chapterUrl=https%3A%2F%2Fbook.example%2Fc%2F1'), url)
  assert.ok(url.includes('chapterIndex=3'), url)
  // 上游地址里的 & 必须被 percent 编码，否则会被当成代理自己的第二个参数
  assert.ok(url.includes('url=https%3A%2F%2Fcdn.example.com%2Fa.jpg%3Fw%3D1%26h%3D2'), url)

  // 服务端把 & 转义成 &amp; 写进正文 → 前端必须还原，否则图床会收到带 &amp; 的 query
  assert.deepEqual(parseIllustrationTag('<img src="https://cdn.example.com/a.jpg?w=1&amp;h=2">'), {
    src: 'https://cdn.example.com/a.jpg?w=1&h=2',
    alt: '',
  })
})

test('inline illustrations - images render through the local proxy with lazy loading and no referrer', () => {
  const html = renderToStaticMarkup(
    React.createElement(InlineIllustration, {
      context,
      upstreamUrl: upstream,
      alt: '图A',
      openLabel: '查看插图',
      failedLabel: '插图加载失败',
      onOpen: () => undefined,
    }),
  )

  assert.ok(html.includes(`src="${ILLUSTRATION_PROXY_PATH}?`), html)
  // **绝不直连上游**：上游地址只能出现在 url 参数里（被 percent 编码）
  assert.ok(!html.includes('src="https://cdn.example.com'), html)
  assert.ok(html.includes('loading="lazy"'), html)
  // HTML 属性名大小写不敏感：React 会输出 `referrerPolicy=`，浏览器按 `referrerpolicy` 生效
  assert.match(html, /referrerpolicy="no-referrer"/i, html)
  // 可点击 + 可键盘操作（点击开灯箱，且阅读器手势把它当交互元素）
  assert.ok(html.includes('<button'), html)
  assert.ok(html.includes('type="button"'), html)
  assert.ok(html.includes('data-reader-illustration="true"'), html)
  assert.ok(html.includes('reader-illustration'), html)
  assert.ok(html.includes('alt="图A"'), html)
  // 上游/危险属性绝不能被透传出来
  assert.ok(!html.includes('onerror'), html)
  assert.ok(!html.includes('javascript:'), html)
})

test('inline illustrations - click opens the lightbox with the proxy url', () => {
  const opened: Array<{ src: string; alt: string }> = []
  const props = buildIllustrationButtonProps(
    `${ILLUSTRATION_PROXY_PATH}?url=x`,
    '图A',
    (src, alt) => opened.push({ src, alt }),
    '查看插图',
  )
  let stopped = false
  props.onClick({ stopPropagation: () => { stopped = true } } as unknown as React.MouseEvent<HTMLButtonElement>)

  assert.equal(opened.length, 1, '点击插图必须打开灯箱')
  assert.equal(opened[0].src, `${ILLUSTRATION_PROXY_PATH}?url=x`)
  assert.equal(opened[0].alt, '图A')
  assert.ok(stopped, '必须阻止冒泡，否则会同时触发翻页/TTS 点选')
})

test('inline illustrations - failed image renders a placeholder instead of a broken image', () => {
  const failedHtml = renderToStaticMarkup(
    illustrationImageView({
      imageSrc: '/api/books/images/illustration?url=x',
      alt: '图A',
      failed: true,
      openLabel: '查看插图',
      failedLabel: '插图加载失败',
      onOpen: () => undefined,
      onError: () => undefined,
    }),
  )
  assert.ok(failedHtml.includes('is-failed'), failedHtml)
  assert.ok(failedHtml.includes('插图加载失败'), failedHtml)
  assert.ok(!failedHtml.includes('<img'), '失败时不应再渲染 <img>（浏览器会画裂图图标）')

  const okHtml = renderToStaticMarkup(
    illustrationImageView({
      imageSrc: '/api/books/images/illustration?url=x',
      alt: '图A',
      failed: false,
      openLabel: '查看插图',
      failedLabel: '插图加载失败',
      onOpen: () => undefined,
      onError: () => undefined,
    }),
  )
  assert.ok(okHtml.includes('<img'), okHtml)
  assert.ok(!okHtml.includes('is-failed'), okHtml)
})

test('inline illustrations - lightbox renders the proxied image and closes on backdrop click', () => {
  const html = renderToStaticMarkup(
    React.createElement(IllustrationLightbox, {
      src: `${ILLUSTRATION_PROXY_PATH}?url=x`,
      alt: '图A',
      closeLabel: '关闭插图',
      onClose: () => undefined,
    }),
  )
  assert.ok(html.includes('data-illustration-lightbox="true"'), html)
  assert.ok(html.includes('role="dialog"'), html)
  assert.ok(html.includes(`src="${ILLUSTRATION_PROXY_PATH}?url=x"`), html)
  assert.ok(html.includes('插图') || html.includes('aria-label="关闭插图"'), html)
  assert.ok(html.includes('illustration-lightbox-image'), html)

  let closed = 0
  buildLightboxBackdropProps(() => { closed += 1 }).onClick({} as unknown as React.MouseEvent<HTMLDivElement>)
  assert.equal(closed, 1, '点空白处必须关闭灯箱')
})

test('inline illustrations - TTS input has illustration tags stripped but keeps paragraph count', () => {
  const paragraphs = ['第一段', '<img src="https://cdn.example.com/a.jpg">', '第二段<img src="https://cdn.example.com/b.jpg">尾巴']
  assert.equal(stripIllustrationTags('<img src="https://cdn.example.com/a.jpg">'), '')
  // 用空格而非空串替换：否则英文正文 "before<img>after" 会粘成 "beforeafter"
  assert.equal(stripIllustrationTags('前<img src="https://cdn.example.com/a.jpg">后'), '前 后')

  const stripped = stripIllustrations(paragraphs)
  assert.equal(stripped.length, paragraphs.length, '只删标签、不删段落：TTS 的高亮下标必须与段落一一对应')
  assert.equal(stripped[0], '第一段')
  assert.equal(stripped[1], '')
  assert.ok(!stripped.join('').includes('<img'), stripped.join('|'))
  assert.ok(stripped[2].includes('尾巴'), stripped[2])
})

test('inline illustrations - reader wiring: proxy rendering, TTS stripping and the lightbox are all connected', () => {
  // 仓库既有教训（manga-seamless）：只写纯函数/CSS 而组件忘接 = 静默失效。
  // 这条钉住三处"必须同时在场"的接线，任意一处断开都会红。
  const reader = fs.readFileSync(path.resolve(__dirname, '../src/ReaderScreen.tsx'), 'utf8')
  assert.ok(reader.includes('splitIllustrationSegments('), 'ReaderScreen 必须用插图切分渲染正文')
  assert.ok(reader.includes('stripIllustrations(paragraphs)'), 'TTS 输入必须剔除插图标签')
  assert.ok(reader.includes('<IllustrationLightbox'), '必须挂载插图灯箱')
  assert.ok(reader.includes('renderParagraphLine(line, chapter?.url'), '当前章必须用它自己的章节地址拼代理')
  assert.ok(reader.includes('currentBook.chapters[entry.index]?.url'), '相邻章必须用各自的章节地址拼代理')
})
