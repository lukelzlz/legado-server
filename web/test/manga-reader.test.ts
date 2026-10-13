import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import '../src/i18n'
import { MangaReaderScreen } from '../src/MangaReaderScreen'
import { api } from '../src/api'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

const openBook = {
  details: {
    sourceId: 'https://manga.example.com',
    name: '测试漫画',
    tocUrl: 'https://manga.example.com/book/1',
  },
  bookUrl: 'https://manga.example.com/book/1',
  bookKind: 'comic',
  chapters: [
    { index: 0, title: '第 1 话', url: 'https://manga.example.com/c/1' },
    { index: 1, title: '第 2 话', url: 'https://manga.example.com/c/2' },
  ],
}

test('MangaReaderScreen - 渲染纵向阅读外壳与章节导航', () => {
  const html = renderToStaticMarkup(
    React.createElement(MangaReaderScreen, { openBook, startIndex: 0, onClose: () => {} }),
  )

  // 纵向瀑布容器 + 书架工作面（复用既有 reader 主题/safe-area）
  assert.ok(html.includes('manga-scroll'), '必须渲染纵向滚动容器')
  assert.ok(html.includes('reader-workspace'), '必须复用既有 reader-workspace 外壳')
  // 章节导航（上一章 / 下一章 / 滑块）
  assert.ok(html.includes('reader-chapter-control-bar'), '必须渲染章节控制条')
  assert.ok(html.includes('reader-chapter-slider'), '必须渲染章节滑块')
  // 目录抽屉复用虚拟章节列表
  assert.ok(html.includes('chapter-search'), '目录抽屉必须带章节搜索')
})

test('MangaReaderScreen - 漫画刻意不提供 TTS 与替换净化', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // 检查真正的「接线」而不是文案：注释里出现 TTS 字样是允许的（那是说明为什么不接）。
  for (const forbidden of ['./ttsEngine', 'TtsPlayerBar', 'toggleTts', 'playTts', 'ttsActive', 'replaceRules', 'paginate']) {
    assert.ok(!source.includes(forbidden), `漫画阅读器不得接线 ${forbidden}`)
  }
})

test('MangaReaderScreen - 单张图片失败必须显式提示且可重试', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(source.includes('onError'), '必须有 onError 兜底')
  assert.ok(source.includes('manga-image-failed'), '失败图必须渲染占位与重试入口')
  assert.ok(source.includes('emptyManifest'), '空清单必须给明确文案，绝不静默留白')
})

test('MangaReaderScreen - 进度语义按图片序号而非像素偏移', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(
    source.includes('(imageIndex + 1) / imageTotal'),
    'scrollPosition 必须是 (图片序号 + 1) / 总张数',
  )
  assert.ok(!/scrollTop\s*\/\s*scrollHeight/.test(source), '严禁用像素偏移充当进度（懒加载会让高度漂移）')
})

test('api client - 暴露 mangaManifest 且书架条目带 bookKind', () => {
  assert.equal(typeof api.mangaManifest, 'function', 'api.mangaManifest 必须存在')

  const apiSource = read('../src/api.ts')
  assert.ok(apiSource.includes("'/api/books/images/manifest'"), '必须指向真实的清单路由')
  assert.ok(/BookshelfItem = \{[^}]*bookKind/.test(apiSource), 'BookshelfItem 必须透出 bookKind')
})

test('main.tsx - 按 bookKind 分派到漫画阅读器', () => {
  const source = read('../src/main.tsx')

  assert.ok(source.includes("bookKind === 'comic'"), '必须按 bookKind 分派')
  assert.ok(source.includes('MangaReaderScreen'), '必须挂载漫画阅读器')
  assert.ok(source.includes('shelf-card-tag-comic'), '书架卡片必须有漫画徽标')
})

test('styles.css - 漫画样式已声明（含书架漫画徽标）', () => {
  const css = read('../src/styles.css')

  for (const selector of ['.manga-scroll', '.manga-image-frame', '.manga-image-failed', '.shelf-card-tag-comic']) {
    assert.ok(css.includes(selector), `styles.css 必须声明 ${selector}`)
  }
})
