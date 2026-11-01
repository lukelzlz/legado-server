/**
 * 「`/simple/` 被 Service Worker 劫持」的自愈守卫。
 *
 * ## 现象（2026-11-01 用户报障 + 真浏览器复现）
 *
 * 访问 `http://host/simple/#shelf` 本该打开**墨水屏版**，实际打开的却是**桌面版界面**；
 * 而全新浏览器上下文（没有 Service Worker）访问同一个地址是正常的。
 *
 * ## 根因
 *
 * 桌面版注册的 Workbox SW 在 `scope: '/'` 下带 `navigateFallback: '/index.html'`，
 * 而 denylist 只排除了 `/api/`：于是**任何**导航请求（包括 `/simple/`、`/kindle`）都被
 * 回退成缓存里的桌面 `index.html`。只要这台浏览器访问过一次桌面版（SW 装好并控制页面），
 * `/simple/` 就再也拿不到服务端那份静态墨水屏页面。
 *
 * ## 两层修复
 *
 * 1. 构建期：给 SW 的 `navigateFallbackDenylist` 加上 `/simple/` 与 `/kindle`（新装/更新后的 SW 不再劫持）；
 * 2. 运行期（本文件）：**已经被旧 SW 控制的浏览器**在打开 simple 路径时，桌面版一启动就发现自己
 *    "被塞进了 simple 路径" ⇒ 注销 SW、清掉 Workbox 缓存、重新加载一次，从而立刻拿到真正的墨水屏版。
 *    用 sessionStorage 做一次性闸门，避免刷新循环。
 */

/** 是不是「墨水屏版」的入口路径（含 `/simple`、`/simple/...`、`/kindle`、`/kindle/...`）。 */
export function isSimpleEntryPath(pathname: string): boolean {
  return /^\/(simple|kindle)(\/|$)/.test(pathname)
}

const GUARD_KEY = 'legado-simple-entry-guard'

/**
 * 若当前是 simple 路径却跑着桌面版，则自救一次（注销 SW + 清缓存 + 重载）。
 *
 * 返回值：是否真的触发了自救（便于单测/排障；浏览器里通常是 fire-and-forget 调用）。
 */
export async function escapeHijackedSimpleEntry(
  location: Pick<Location, 'pathname' | 'search' | 'hash' | 'replace'> = window.location,
  storage: Pick<Storage, 'getItem' | 'setItem'> = window.sessionStorage,
): Promise<boolean> {
  if (!isSimpleEntryPath(location.pathname)) return false
  // 已经救过一次还这样：说明不是 SW 干的（或浏览器拒绝注销），别刷新成死循环
  if (storage.getItem(GUARD_KEY) === '1') return false
  storage.setItem(GUARD_KEY, '1')

  try {
    const registrations = navigator.serviceWorker ? await navigator.serviceWorker.getRegistrations() : []
    await Promise.all(registrations.map(registration => registration.unregister()))
  } catch {
    // 注销失败也继续清缓存 + 重载，最坏情况是多刷一次
  }
  try {
    if (typeof caches !== 'undefined') {
      const names = await caches.keys()
      await Promise.all(names.map(name => caches.delete(name)))
    }
  } catch {
    // 缓存清不掉不影响重载后拿到新页面
  }

  location.replace(`${location.pathname}${location.search}${location.hash}`)
  return true
}
