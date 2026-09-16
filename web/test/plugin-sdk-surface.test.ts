import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createPluginSdk, type PluginManifestSummary } from '../src/pluginSdk.ts'

/**
 * 插件 SDK 的公共面。
 *
 * 这里钉住的是 SDK 的顶层成员集合与 `api` 的成员集合，原因是一次真实的翻车：插件代码写了
 * `api.settings.get()`，但 `settings` 其实是 SDK 顶层成员、`api` 上只有通用请求方法，
 * 运行时直接抛 `Cannot read properties of undefined (reading 'get')`。
 * 插件的 `web.js` 是未编译的原生 ES module，tsc 拦不住这类错，所以用测试把契约钉死。
 */

const manifestOf = (id: string): PluginManifestSummary => ({
  id,
  name: id,
  version: '1.0.0',
  apiVersion: 1,
  enabled: true,
  loaded: true,
  runtime: 'js',
  hasServer: true,
  hasWeb: true,
  permissions: [],
  settingsSchema: [],
  directory: `/plugins/${id}`,
})

test('SDK 顶层成员集合与文档一致（防止契约被悄悄改动）', () => {
  const sdk = createPluginSdk(manifestOf('surface'), {
    navigate: () => {},
    registerNavItem: () => {},
    registerMenuItem: () => {},
    registerPage: () => {},
  })

  assert.deepEqual(Object.keys(sdk).sort(), [
    'React',
    'api',
    'hooks',
    'navigate',
    'pages',
    'plugin',
    'registerMenuItem',
    'registerNavItem',
    'registerPage',
    'settings',
    'storage',
    'toast',
    'ui',
  ])
  // `settings` 属于顶层；`api` 只负责通用请求，不要把两者混在一起。
  assert.deepEqual(Object.keys(sdk.api).sort(), ['del', 'get', 'plugin', 'pluginRaw', 'post', 'put'])
})
