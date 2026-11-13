import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { escapeHijackedSimpleEntry, isSimpleEntryPath } from '../src/simpleEntry'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = (name: string) => fs.readFileSync(path.resolve(here, '../src', name), 'utf-8')

/** 最小可用的假 location / sessionStorage，用来驱动自愈守卫（Node 里没有浏览器环境）。 */
function fakeEnv(pathname: string, hash = '') {
  const replaced: string[] = []
  const store = new Map<string, string>()
  return {
    replaced,
    location: {
      pathname,
      search: '',
      hash,
      replace: (url: string) => { replaced.push(url) },
    },
    storage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => { store.set(key, value) },
    },
  }
}

// ---------------------------------------------------------------------------
// 1. 路径判定：simple / kindle 的**所有**形态都算，别的都不算
// ---------------------------------------------------------------------------
test('isSimpleEntryPath - 只认 /simple 与 /kindle 两种前缀', () => {
  for (const pathname of ['/simple', '/simple/', '/simple/index.html', '/simple/reader.html', '/kindle', '/kindle/']) {
    assert.equal(isSimpleEntryPath(pathname), true, `应判为墨水屏版：${pathname}`)
  }
  for (const pathname of ['/', '/index.html', '/api/simple', '/simplex', '/simple-web', '/assets/index.js']) {
    assert.equal(isSimpleEntryPath(pathname), false, `不应判为墨水屏版：${pathname}`)
  }
})

// ---------------------------------------------------------------------------
// 2. 自愈守卫：只有「simple 路径却跑桌面版」时才动手，且只动一次
// ---------------------------------------------------------------------------
test('escapeHijackedSimpleEntry - 非 simple 路径什么都不做', async () => {
  const env = fakeEnv('/')
  const acted = await escapeHijackedSimpleEntry(env.location, env.storage)
  assert.equal(acted, false)
  assert.deepEqual(env.replaced, [], '桌面版正常路径绝不能触发重载')
})

test('escapeHijackedSimpleEntry - simple 路径被劫持时重载一次到同一地址（保留 hash）', async () => {
  const env = fakeEnv('/simple/', '#shelf')
  const acted = await escapeHijackedSimpleEntry(env.location, env.storage)
  assert.equal(acted, true)
  assert.deepEqual(env.replaced, ['/simple/#shelf'], '必须重载到原地址（含 hash），用户才不会"跳回书架"')
})

test('escapeHijackedSimpleEntry - 同一个窗口只救一次，避免刷新死循环', async () => {
  const env = fakeEnv('/simple/')
  assert.equal(await escapeHijackedSimpleEntry(env.location, env.storage), true)
  assert.equal(await escapeHijackedSimpleEntry(env.location, env.storage), false, '第二次必须放弃')
  assert.equal(env.replaced.length, 1)
})

test('escapeHijackedSimpleEntry - /kindle 同样会被救', async () => {
  const env = fakeEnv('/kindle/')
  assert.equal(await escapeHijackedSimpleEntry(env.location, env.storage), true)
  assert.deepEqual(env.replaced, ['/kindle/'])
})

// ---------------------------------------------------------------------------
// 3. 接线契约：构建期 denylist + 入口处调用，缺一层都会复发
// ---------------------------------------------------------------------------
test('vite.config - Service Worker 的 navigateFallback 必须排除 /simple 与 /kindle', () => {
  const config = fs.readFileSync(path.resolve(here, '../vite.config.ts'), 'utf-8')
  const denylistBlock = config.slice(config.indexOf('navigateFallbackDenylist'), config.indexOf('runtimeCaching'))
  assert.ok(denylistBlock.length > 0, '必须能找到 navigateFallbackDenylist')
  assert.match(denylistBlock, /\/\^\\\/simple\(\\\/\|\$\)\//, '缺少 /^\\/simple(\\/|$)/ 排除项：SW 仍会把 /simple/ 回退成桌面 index.html')
  assert.match(denylistBlock, /\/\^\\\/kindle\(\\\/\|\$\)\//, '缺少 /^\\/kindle(\\/|$)/ 排除项')
})

test('main.tsx - 必须在 createRoot 之前调用自愈守卫', () => {
  const entry = src('main.tsx')
  assert.match(entry, /import \{ escapeHijackedSimpleEntry \} from '\.\/simpleEntry'/)
  const guardIndex = entry.indexOf('escapeHijackedSimpleEntry()')
  const rootIndex = entry.indexOf('createRoot(document.getElementById')
  assert.ok(guardIndex > 0, '入口必须调用自愈守卫')
  assert.ok(rootIndex > 0, '必须还能找到 createRoot')
  assert.ok(guardIndex < rootIndex, '守卫必须在渲染之前跑（否则用户先看到桌面版再被刷掉）')
})
