import test from 'node:test'
import assert from 'node:assert/strict'
import i18n, { changeAppLanguage, getCurrentLocale, resources } from '../src/i18n'
import {
  DEFAULT_LOCALE,
  normalizeLocale,
  STORAGE_LOCALE_KEY,
  SUPPORTED_LOCALES,
} from '../src/i18n/types'
import { api } from '../src/api'

test('i18n - supported locales metadata', () => {
  assert.equal(SUPPORTED_LOCALES.length, 4)
  const codes = SUPPORTED_LOCALES.map(l => l.code)
  assert.deepEqual(codes, ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'])
  assert.equal(DEFAULT_LOCALE, 'zh-CN')
})

test('i18n - normalizeLocale matches standard and partial tags', () => {
  assert.equal(normalizeLocale(null), 'zh-CN')
  assert.equal(normalizeLocale(''), 'zh-CN')
  assert.equal(normalizeLocale('zh-CN'), 'zh-CN')
  assert.equal(normalizeLocale('zh'), 'zh-CN')
  assert.equal(normalizeLocale('zh-hans'), 'zh-CN')

  assert.equal(normalizeLocale('zh-TW'), 'zh-TW')
  assert.equal(normalizeLocale('zh-HK'), 'zh-TW')
  assert.equal(normalizeLocale('zh-hant'), 'zh-TW')
  assert.equal(normalizeLocale('zh-MO'), 'zh-TW')

  assert.equal(normalizeLocale('en-US'), 'en-US')
  assert.equal(normalizeLocale('en'), 'en-US')
  assert.equal(normalizeLocale('en-GB'), 'en-US')

  assert.equal(normalizeLocale('ja-JP'), 'ja-JP')
  assert.equal(normalizeLocale('ja'), 'ja-JP')

  assert.equal(normalizeLocale('fr-FR'), 'zh-CN')
})

test('i18n - runtime translation and language switching', async () => {
  // 1. Initial should be zh-CN
  await changeAppLanguage('zh-CN')
  assert.equal(getCurrentLocale(), 'zh-CN')
  assert.equal(i18n.t('header.shelf'), '书架')
  assert.equal(i18n.t('header.library'), '书库')
  assert.equal(i18n.t('common.ok'), '确定')
  assert.equal(i18n.t('common.selected', { count: 3 }), '已选 3 项')

  // 2. Switch to English
  await changeAppLanguage('en-US')
  assert.equal(getCurrentLocale(), 'en-US')
  assert.equal(i18n.t('header.shelf'), 'Bookshelf')
  assert.equal(i18n.t('header.library'), 'Library')
  assert.equal(i18n.t('common.ok'), 'OK')
  assert.equal(i18n.t('common.selected', { count: 3 }), '3 selected')

  // 3. Switch to Traditional Chinese
  await changeAppLanguage('zh-TW')
  assert.equal(getCurrentLocale(), 'zh-TW')
  assert.equal(i18n.t('header.shelf'), '書架')
  assert.equal(i18n.t('header.library'), '書庫')
  assert.equal(i18n.t('common.ok'), '確定')
  assert.equal(i18n.t('common.selected', { count: 3 }), '已選 3 項')

  // 4. Switch to Japanese
  await changeAppLanguage('ja-JP')
  assert.equal(getCurrentLocale(), 'ja-JP')
  assert.equal(i18n.t('header.shelf'), '本棚')
  assert.equal(i18n.t('header.library'), 'ライブラリ')
  assert.equal(i18n.t('common.ok'), 'OK')
  assert.equal(i18n.t('common.selected', { count: 3 }), '3 件選択中')

  // 5. Fallback to zh-CN
  await changeAppLanguage('zh-CN')
  assert.equal(getCurrentLocale(), 'zh-CN')
  assert.equal(i18n.t('header.shelf'), '书架')
})

test('i18n - api client automatically sends Accept-Language header', async () => {
  await changeAppLanguage('en-US')
  let recordedHeaders: Headers | null = null

  const originalFetch = globalThis.fetch
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    recordedHeaders = new Headers(init?.headers)
    return new Response(JSON.stringify({ locale: 'en-US' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as typeof fetch

  try {
    const res = await api.getLocale()
    assert.equal(res.locale, 'en-US')
    assert.ok(recordedHeaders)
    assert.equal((recordedHeaders as Headers).get('Accept-Language'), 'en-US')

    // Switch to Japanese and test again
    await changeAppLanguage('ja-JP')
    await api.setLocale('ja-JP')
    assert.equal((recordedHeaders as Headers).get('Accept-Language'), 'ja-JP')
  } finally {
    globalThis.fetch = originalFetch
    await changeAppLanguage('zh-CN')
  }
})

test('i18n - all 4 locales have 100% key parity and non-empty strings', async () => {
  const locales = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const
  const data: Record<string, Record<string, unknown>> = {
    'zh-CN': resources['zh-CN'].translation as Record<string, unknown>,
    'zh-TW': resources['zh-TW'].translation as Record<string, unknown>,
    'en-US': resources['en-US'].translation as Record<string, unknown>,
    'ja-JP': resources['ja-JP'].translation as Record<string, unknown>,
  }

  function getLeafKeys(obj: Record<string, unknown>, prefix = ''): Record<string, string> {
    const res: Record<string, string> = {}
    for (const [k, v] of Object.entries(obj)) {
      const full = prefix ? `${prefix}.${k}` : k
      if (typeof v === 'object' && v !== null && !Array.isArray(v)) {
        Object.assign(res, getLeafKeys(v as Record<string, unknown>, full))
      } else if (typeof v === 'string') {
        res[full] = v
      }
    }
    return res
  }

  const baseKeys = getLeafKeys(data['zh-CN'])
  assert.ok(Object.keys(baseKeys).length > 800, 'zh-CN should have over 800 keys')

  for (const loc of locales) {
    const locKeys = getLeafKeys(data[loc])
    const missingKeys = Object.keys(baseKeys).filter(k => !(k in locKeys))
    assert.deepEqual(missingKeys, [], `${loc} must not have any missing keys from zh-CN`)

    // Verify all keys are non-empty
    for (const [k, val] of Object.entries(locKeys)) {
      assert.ok(val.trim().length > 0, `${loc} key "${k}" must not be empty`)
    }
  }
})

test('i18n - AppHeader and Login render dynamically across languages without Chinese in en-US', async () => {
  const React = await import('react')
  const { renderToStaticMarkup } = await import('react-dom/server')
  const { AppHeader } = await import('../src/AppHeader')
  const { Login } = await import('../src/Login')
  const { defaultReaderSettings } = await import('../src/readerSettings')

  try {
    // 1. zh-CN default
    await changeAppLanguage('zh-CN')
    const zhHeader = renderToStaticMarkup(
      React.createElement(AppHeader, {
        page: 'shelf',
        settings: defaultReaderSettings,
        onSettingsChange: () => {},
        onNavigate: () => {},
        onLogout: () => {},
      })
    )
    assert.ok(zhHeader.includes('书架'), 'zh-CN header should contain 书架')
    assert.ok(zhHeader.includes('书库'), 'zh-CN header should contain 书库')
    assert.ok(zhHeader.includes('书源'), 'zh-CN header should contain 书源')

    // 2. Switch to en-US
    await changeAppLanguage('en-US')
    const enHeader = renderToStaticMarkup(
      React.createElement(AppHeader, {
        page: 'shelf',
        settings: defaultReaderSettings,
        onSettingsChange: () => {},
        onNavigate: () => {},
        onLogout: () => {},
      })
    )
    assert.ok(enHeader.includes('Bookshelf'), 'en-US header should contain Bookshelf')
    assert.ok(enHeader.includes('Library'), 'en-US header should contain Library')
    assert.ok(enHeader.includes('Sources'), 'en-US header should contain Sources')
    assert.ok(enHeader.includes('Subscriptions'), 'en-US header should contain Subscriptions')
    assert.ok(enHeader.includes('Rules'), 'en-US header should contain Rules')
    assert.ok(!enHeader.includes('书架'), 'en-US header should not contain Chinese 书架')
    assert.ok(!enHeader.includes('书库'), 'en-US header should not contain Chinese 书库')

    const enLogin = renderToStaticMarkup(
      React.createElement(Login, {
        onSuccess: () => {},
      })
    )
    assert.ok(enLogin.includes('Login'), 'en-US login should contain Login')
    assert.ok(enLogin.includes('Password'), 'en-US login should contain Password')
    assert.ok(!enLogin.includes('管理员密码'), 'en-US login should not contain Chinese 管理员密码')

    // 3. Switch to ja-JP
    await changeAppLanguage('ja-JP')
    const jaHeader = renderToStaticMarkup(
      React.createElement(AppHeader, {
        page: 'shelf',
        settings: defaultReaderSettings,
        onSettingsChange: () => {},
        onNavigate: () => {},
        onLogout: () => {},
      })
    )
    assert.ok(jaHeader.includes('本棚'), 'ja-JP header should contain 本棚')
    assert.ok(jaHeader.includes('ライブラリ'), 'ja-JP header should contain ライブラリ')
    assert.ok(jaHeader.includes('ブックソース'), 'ja-JP header should contain ブックソース')
    assert.ok(jaHeader.includes('購読'), 'ja-JP header should contain 購読')
    assert.ok(jaHeader.includes('置換ルール'), 'ja-JP header should contain 置換ルール')

    // 4. Switch to zh-TW
    await changeAppLanguage('zh-TW')
    const twHeader = renderToStaticMarkup(
      React.createElement(AppHeader, {
        page: 'shelf',
        settings: defaultReaderSettings,
        onSettingsChange: () => {},
        onNavigate: () => {},
        onLogout: () => {},
      })
    )
    assert.ok(twHeader.includes('書架'), 'zh-TW header should contain 書架')
    assert.ok(twHeader.includes('書庫'), 'zh-TW header should contain 書庫')
    assert.ok(twHeader.includes('書源'), 'zh-TW header should contain 書源')
    assert.ok(twHeader.includes('訂閱'), 'zh-TW header should contain 訂閱')
    assert.ok(twHeader.includes('規則'), 'zh-TW header should contain 規則')

  } finally {
    await changeAppLanguage('zh-CN')
  }
})
