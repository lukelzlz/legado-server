import test from 'node:test'
import assert from 'node:assert/strict'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import {
  AUTO_CACHE_BOOKS_RANGE,
  AUTO_CACHE_INTERVAL_RANGE,
  WebDavSettingsPage,
} from '../src/WebDavSettingsPage'
import { api, setCsrfToken } from '../src/api'
import zhCN from '../src/i18n/locales/zh-CN.json'
import zhTW from '../src/i18n/locales/zh-TW.json'
import enUS from '../src/i18n/locales/en-US.json'
import jaJP from '../src/i18n/locales/ja-JP.json'

const RESOURCES: Record<string, any> = { 'zh-CN': zhCN, 'zh-TW': zhTW, 'en-US': enUS, 'ja-JP': jaJP }

/** 自动缓存区块用到的**全部** i18n key（服务端与前端共享同一组语义）。 */
const AUTO_CACHE_KEYS = [
  'autoCacheTitle',
  'autoCacheDesc',
  'autoCacheNovel',
  'autoCacheComic',
  'autoCacheAudio',
  'autoCacheEnable',
  'autoCacheEvery',
  'autoCacheMinutes',
  'autoCachePerRound',
  'autoCacheBooks',
  'autoCacheIntervalAria',
  'autoCacheBooksAria',
  'autoCacheIgnoreQuota',
  'autoCacheHint',
  'autoCacheIntervalInvalid',
  'autoCacheBooksInvalid',
  'autoCacheSaved',
] as const

test('auto cache - every key exists and is non-empty in all four locales', () => {
  const missing: string[] = []
  for (const [locale, resource] of Object.entries(RESOURCES)) {
    for (const key of AUTO_CACHE_KEYS) {
      const value = resource?.webdav?.[key]
      if (typeof value !== 'string' || value.trim().length === 0) missing.push(`${locale} webdav.${key}`)
    }
  }
  assert.deepEqual(missing, [], `自动缓存的 i18n key 必须四语言齐全：\n  ${missing.join('\n  ')}`)
})

test('auto cache - Chinese copy says what the user asked for', () => {
  // 用户原话里的关键词：自动缓存 / 小说 / 漫画 / 有声 / 忽略内存大小限制 / 每隔 N 分钟 / 每轮 N 本
  assert.equal(zhCN.webdav.autoCacheTitle, '自动缓存')
  assert.equal(zhCN.webdav.autoCacheNovel, '小说')
  assert.equal(zhCN.webdav.autoCacheComic, '漫画')
  assert.equal(zhCN.webdav.autoCacheAudio, '有声')
  assert.equal(zhCN.webdav.autoCacheIgnoreQuota, '忽略内存大小限制')
  assert.ok(zhCN.webdav.autoCacheIntervalAria.includes('每隔'))
  assert.ok(zhCN.webdav.autoCacheIntervalAria.includes('分钟'))
  assert.ok(zhCN.webdav.autoCacheBooksAria.includes('每轮'))
  assert.ok(zhCN.webdav.autoCacheBooksAria.includes('本'))
  // 其它三语言不能是中文占位（否则用户切语言后看到的还是中文）
  assert.notEqual(enUS.webdav.autoCacheTitle, zhCN.webdav.autoCacheTitle)
  assert.notEqual(jaJP.webdav.autoCacheTitle, zhCN.webdav.autoCacheTitle)
  assert.notEqual(zhTW.webdav.autoCacheTitle, zhCN.webdav.autoCacheTitle)
})

test('auto cache - page renders three separate groups with two ignore-quota checkboxes', () => {
  const html = renderToStaticMarkup(React.createElement(WebDavSettingsPage))

  // 区块标题与说明
  assert.ok(html.includes('自动缓存'), 'page should contain the auto-cache section')
  // 三组**视觉上分开**（各自一个 .auto-cache-group 卡片）
  const groups = (html.match(/class="auto-cache-group"/g) || []).length
  assert.equal(groups, 3, `expected 3 auto-cache groups, got ${groups}`)
  assert.ok(html.includes('data-kind="novel"'), 'novel group should be identifiable')
  assert.ok(html.includes('data-kind="comic"'), 'comic group should be identifiable')
  assert.ok(html.includes('data-kind="audio"'), 'audio group should be identifiable')

  // 每组都有「启用」勾选 + 间隔 + 每轮本数
  const enableSwitches = (html.match(/>启用</g) || []).length
  assert.equal(enableSwitches, 3, `expected an enable switch in each of the 3 groups, got ${enableSwitches}`)
  assert.ok(html.includes('每隔'), 'interval label should be rendered')
  assert.ok(html.includes('每轮'), 'books-per-round label should be rendered')
  assert.equal((html.match(/type="number"/g) || []).length, 6, 'each group needs an interval and a books input')

  // 「忽略内存大小限制」**只有漫画与有声有**，且各自在自己那组下面
  const quotaChecks = (html.match(/>忽略内存大小限制</g) || []).length
  assert.equal(quotaChecks, 2, `expected exactly 2 ignore-quota checkboxes, got ${quotaChecks}`)

  // 组内沿用设置页既有样式，保证视觉一致
  assert.ok(html.includes('class="auto-cache-groups"'), 'groups should live in the shared grid')
  assert.ok(html.includes('export-auto-switch'), 'checkboxes should reuse the settings-page switch style')
})

test('auto cache - range constants match the server validation', () => {
  // 服务端：间隔 1..1440、每轮 1..20（AutoCacheScheduler.kt 的 AUTO_CACHE_MIN/MAX_*）
  assert.deepEqual([...AUTO_CACHE_INTERVAL_RANGE], [1, 1440])
  assert.deepEqual([...AUTO_CACHE_BOOKS_RANGE], [1, 20])
})

test('auto cache - api exposes settings/run-now on the documented paths', async () => {
  const originalFetch = globalThis.fetch
  const captured: { url: string; method?: string; body?: any }[] = []

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    captured.push({ url: input.toString(), method: init?.method, body: init?.body })
    return new Response(JSON.stringify({ novelEnabled: false }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    setCsrfToken('csrf-token-abc')

    await api.autoCacheSettings()
    assert.equal(captured[0].url, '/api/auto-cache/settings')
    assert.equal(captured[0].method, undefined, 'GET should not force a method')

    // 局部更新：只提交传进来的字段
    await api.saveAutoCacheSettings({ comicEnabled: true })
    assert.equal(captured[1].url, '/api/auto-cache/settings')
    assert.equal(captured[1].method, 'PUT')
    assert.deepEqual(JSON.parse(captured[1].body), { comicEnabled: true })

    await api.saveAutoCacheSettings({ comicIgnoreQuota: true })
    assert.deepEqual(JSON.parse(captured[2].body), { comicIgnoreQuota: true })

    await api.runAutoCacheNow()
    assert.equal(captured[3].url, '/api/auto-cache/run-now')
    assert.equal(captured[3].method, 'POST')
  } finally {
    globalThis.fetch = originalFetch
  }
})
