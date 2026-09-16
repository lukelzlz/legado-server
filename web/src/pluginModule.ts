import type { PluginWebModule } from './pluginSdk'

/**
 * 插件 `web.js` 的导出形态归一化。
 *
 * 单独成文件（而不是塞在 `pluginHost.ts` 里）是有意的：这是纯函数逻辑，不依赖 React、宿主 API
 * 或浏览器环境，因此可以在 node 测试里直接断言，不必为了测三行解析去启动整个宿主。
 */

const asFunction = (value: unknown): ((...args: never[]) => unknown) | undefined =>
  typeof value === 'function' ? (value as (...args: never[]) => unknown) : undefined

/**
 * 把插件模块归一化成 `{ activate, deactivate }`。
 *
 * 插件作者会自然地写出三种等价形式，宿主全部接受：
 *   `export default function activate(sdk) {}`
 *   `export function activate(sdk) {}`
 *   `export default { activate, deactivate }`
 *
 * 只认其中一种会造成「明明导出了 activate，却报未导出 activate 函数」——报错与事实相反，
 * 排查方向会被彻底带偏，所以这里显式把三种写法收敛到同一个形状。
 * 约定写法是第一种，见 `docs/plugins/PLUGIN-SDK.md`。
 */
export function normalizePluginModule(loaded: unknown): PluginWebModule {
  if (!loaded || (typeof loaded !== 'object' && typeof loaded !== 'function')) {
    throw new Error('web.js 未导出任何内容（必须是 ES module）')
  }
  const namespace = loaded as { activate?: unknown; deactivate?: unknown; default?: unknown }
  const fallback = namespace.default
  const nested = fallback !== null && typeof fallback === 'object' ? (fallback as Record<string, unknown>) : undefined
  const nestedActivate = nested ? asFunction(nested.activate) : undefined
  const nestedDeactivate = nested ? asFunction(nested.deactivate) : undefined

  const activate =
    asFunction(namespace.activate) ?? asFunction(fallback) ?? (nestedActivate ? nestedActivate.bind(fallback) : undefined)
  if (!activate) {
    throw new Error(
      'web.js 未导出 activate 函数：请用 export default function activate(sdk) 或 export function activate(sdk)',
    )
  }

  return {
    activate: activate as PluginWebModule['activate'],
    // 从默认导出对象里取出的方法绑定回原对象，保留作者对 `this` 的预期。
    deactivate: (asFunction(namespace.deactivate) ??
      (nestedDeactivate ? nestedDeactivate.bind(fallback) : undefined)) as PluginWebModule['deactivate'],
  }
}
