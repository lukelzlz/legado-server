import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizePluginModule } from '../src/pluginModule.ts'

/**
 * 插件 web.js 的导出形态解析。
 *
 * 这里守着的是一个真实踩过的坑：宿主最初只读具名导出 `activate`，而插件按文档写的是
 * `export default function activate(sdk)`，于是作者明明导出了 activate，界面却报
 * 「web.js 未导出 activate 函数」——报错与事实相反，排查方向被彻底带偏。
 * 因此三种等价写法都必须被接受，且失败时的报错要指明正确写法。
 */

test('normalizePluginModule 接受 export default function activate', () => {
  const activate = (sdk: unknown) => sdk
  const module = normalizePluginModule({ default: activate })

  assert.equal(module.activate, activate)
  assert.equal(module.deactivate, undefined)
})

test('normalizePluginModule 接受具名导出 activate 与 deactivate', () => {
  const activate = () => undefined
  const deactivate = () => undefined
  const module = normalizePluginModule({ activate, deactivate })

  assert.equal(module.activate, activate)
  assert.equal(module.deactivate, deactivate)
})

test('normalizePluginModule 接受 default 导出的对象', () => {
  const activate = () => undefined
  const deactivate = () => undefined
  const module = normalizePluginModule({ default: { activate, deactivate } })

  assert.equal(typeof module.activate, 'function')
  assert.equal(typeof module.deactivate, 'function')
})

test('normalizePluginModule 保留 default 对象方法里的 this 指向', () => {
  const exportsObject = {
    calls: 0,
    activate() {
      this.calls += 1
    },
    deactivate() {
      this.calls += 1
    },
  }
  const module = normalizePluginModule({ default: exportsObject })

  module.activate?.({} as never)
  module.deactivate?.()
  assert.equal(exportsObject.calls, 2)
})

test('normalizePluginModule 用具名 activate 覆盖 default 导出对象', () => {
  const named = () => undefined
  const module = normalizePluginModule({ activate: named, default: { activate: () => 'other' } })

  assert.equal(module.activate, named)
})

test('normalizePluginModule 在缺少 activate 时给出指明写法的报错', () => {
  assert.throws(() => normalizePluginModule({}), /export default function activate/)
  assert.throws(() => normalizePluginModule({ default: {} }), /未导出 activate 函数/)
  assert.throws(() => normalizePluginModule({ activate: 'not-a-function' }), /未导出 activate 函数/)
  assert.throws(() => normalizePluginModule(null), /必须是 ES module/)
})
