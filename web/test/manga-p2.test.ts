import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { api } from '../src/api'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (relative: string) => readFileSync(path.resolve(here, relative), 'utf8')

/**
 * 方案 E · P2 前端契约：预取时机、目录绿点、缓存管理区块。
 *
 * 这里刻意断言**接线**而不是文案：P2 的三件事全是"静默失效"型的
 * （预取没触发 ⇒ 只是慢一点；绿点接错数据源 ⇒ 亮在不该亮的地方；缓存区块混在一起 ⇒ 用户清错东西），
 * 只靠文案断言根本拦不住。
 */
test('P2 契约 - 预取与免死金牌走真实路由', () => {
  const apiSource = read('../src/api.ts')

  assert.equal(typeof api.mangaPrefetch, 'function', 'api.mangaPrefetch 必须存在')
  assert.equal(typeof api.mangaPrefetchStatus, 'function', 'api.mangaPrefetchStatus 必须存在')
  assert.equal(typeof api.pinMangaBook, 'function', 'api.pinMangaBook 必须存在')
  assert.ok(apiSource.includes("'/api/books/images/prefetch'"), '必须指向真实的预取路由')
  assert.ok(apiSource.includes('/api/books/images/cache/pin'), '必须指向真实的 pin 路由')
  // `queued=false` 不是错误：调用方要能区分"没做"与"在做"，但两者都不能当失败抛出来
  assert.ok(/MangaPrefetchResponse = \{ queued: boolean/.test(apiSource), '响应必须带 queued 标记')
})

test('P2 - 目录绿点接的是服务端「整章就绪」口径', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(source.includes('api.getCachedChapters('), '绿点数据必须来自既有 cached-chapters（契约不变，服务端按 kind 换源）')
  assert.ok(source.includes('cachedUrlsSet={cachedChapters}'), '必须把绿点集合交给虚拟章节列表')
  // 本章图片全部解码 ⇒ 服务端此刻已标 ready，立刻刷一次（否则用户要重开才看到绿点）
  assert.ok(
    source.includes('loadedCount === total) refreshCachedChapters()'),
    '整章解码完成必须立刻刷新绿点',
  )
  // 绿点读不到不能打断阅读
  assert.ok(
    /getCachedChapters[\s\S]{0,220}?\.catch\(\(\) => undefined\)/.test(source),
    '绿点失败必须静默（它只是提示）',
  )
})

test('P2 - 预取时机：进章抓满本章 + 滚动 70% 抓下一章（每章只触发一次）', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  // ① 必须在清单加载完成**之后**触发：此刻 L2 已落库，预取走缓存路径，不会再把章节页解析一遍
  const manifestPersist = source.indexOf('persist(chapterIndex, 0, list.length)')
  const prefetchCall = source.indexOf('api.mangaPrefetch(', manifestPersist)
  assert.ok(manifestPersist > 0, '必须能找到清单加载完成点')
  assert.ok(prefetchCall > manifestPersist, '预取必须在清单就绪之后触发（否则同一章会被抓两次）')

  // ② 70% 触发下一章
  assert.ok(source.includes('NEXT_CHAPTER_PREFETCH_RATIO = 0.7'), '必须有 70% 阈值常量')
  assert.ok(source.includes('chapterIndex + 1, 0'), '必须预取下一章')
  assert.ok(source.includes('prefetchedNextRef'), '必须有"每章只触发一次"的守卫（否则每次滚动都投递）')

  // ③ 全部 fire-and-forget：预取失败绝不能冒泡到阅读流程
  assert.ok(source.includes('.catch(() => undefined)'), '预取必须静默失败')
})

test('P2 - 离线缓存入口复用文本阅读器的按钮网格与文案键', () => {
  const source = read('../src/MangaReaderScreen.tsx')

  assert.ok(source.includes('cache-range-btn-grid'), '必须复用文本阅读器的缓存按钮网格（观感一致）')
  assert.ok(source.includes("t('reader.cacheNext50'"), '后 50 章必须复用既有文案键')
  assert.ok(source.includes('requestOffline(chapters.length)'), '全本缓存必须按整本跨度投递')
  assert.ok(source.includes('span >= remaining'), '覆盖到书末才算"整本"（据此打 pinned 免死金牌）')
  assert.ok(source.includes('api.mangaPrefetch('), '离线缓存必须走服务端预取（不新造一套下载器）')
})

test('P2 - 缓存管理：服务端图片缓存与本机缓存分开显示', () => {
  const source = read('../src/OfflineCacheModal.tsx')

  assert.ok(source.includes('api.imageCacheStats('), '必须读服务端缓存统计')
  assert.ok(source.includes('api.clearImageCache('), '必须能清单书/清全部服务端缓存')
  assert.ok(source.includes('api.pinMangaBook('), '必须能切换「整本保留」')
  assert.ok(source.includes('server-cache-section'), '服务端缓存必须是独立区块')
  assert.ok(source.includes('serverImageCacheHint'), '必须明说"与本机缓存分开统计"')
  // 本机 IndexedDB 区块不能被服务端区块挤掉（两者都要在）
  assert.ok(source.includes('getOfflineStorageStats('), '本机缓存统计必须保留')
  // 盘表漂移要提示：表里有行但盘上有残片，用户只会在"清不干净"时才发现
  assert.ok(source.includes('cache-drift-warning'), '必须提示磁盘占用与索引不一致')
})

test('P2 - 服务端缓存区块样式同样只用主题变量', () => {
  const css = read('../src/styles.css')
  const start = css.indexOf('.server-cache-section')
  assert.ok(start > 0, '必须能找到服务端缓存区块样式')
  const block = css.slice(start, start + 1600)

  assert.ok(!/#[0-9a-fA-F]{3,8}\b/.test(block), '严禁十六进制颜色')
  assert.ok(!/\brgba?\(/.test(block), '严禁 rgb()/rgba() 颜色')
  assert.ok(block.includes('var(--line)') && block.includes('var(--surface-muted)'), '必须用主题变量取色')
})
