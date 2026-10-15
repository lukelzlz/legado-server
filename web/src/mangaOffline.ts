import type { MangaImageItem } from './api'

/**
 * 漫画离线（方案 E · P3）。
 *
 * ## 为什么需要"快照"这一层
 * 服务端直出图片走 Service Worker 的 `legado-manga` 缓存，**断网也能出图**；
 * 但"这一章有哪些图"（清单）是 POST 接口的结果，SW 默认不缓存 POST。
 * 于是脱网时会出现最难堪的形态：**图都在本地缓存里，界面却因为拿不到清单而全白**。
 * 因此清单必须由前端自己留一份快照（IndexedDB），脱网时直接用它渲染。
 *
 * ## 为什么不做"章节前缀预热"
 * 方案 E 原稿设想按 `/api/books/images/{dirKey}/{chapterKey}/` **前缀**逐张预热，
 * 那是"定位 URL"形态下的做法。P1 把 key 改成**按内容寻址**（`/api/images/<sha256>`）后，
 * 一张图属于哪一章只能由清单告诉我们 ⇒ 预热改为**按清单里的 key 逐张 fetch**，
 * 语义与效果完全一致（都是"把这一章塞进 SW 缓存"），且不需要任何新接口。
 */

const DB_NAME = 'legado_manga_offline_db'
const DB_VERSION = 1
const STORE_MANIFESTS = 'manifests'
/** 单次 IndexedDB 操作的超时（见 [withTimeout]：宁可没有快照，也不能让加载框永远转）。 */
const DB_TIMEOUT_MS = 1500

/** 一章的清单快照（脱网渲染的唯一依据）。 */
export interface MangaManifestSnapshot {
  key: string
  sourceId: string
  bookUrl: string
  chapterUrl: string
  chapterIndex: number
  title?: string | null
  images: MangaImageItem[]
  savedAt: number
}

/**
 * 快照主键。
 *
 * 用「**源 ID 长度** + ':' + 源 ID + 章节地址」而不是 `${a}\u0000${b}`：
 * 后者**不是单射**（`("a\0b","c")` 与 `("a","b\0c")` 会撞成同一个 key）。
 * 长度前缀是无条件单射的，且不依赖"值里不会出现某个字符"这种假设。
 */
export function mangaManifestKey(sourceId: string, chapterUrl: string): string {
  return `${sourceId.length}:${sourceId}${chapterUrl}`
}

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('当前环境不支持 IndexedDB'))
      return
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_MANIFESTS)) {
        const store = db.createObjectStore(STORE_MANIFESTS, { keyPath: 'key' })
        store.createIndex('by_book', ['sourceId', 'bookUrl'], { unique: false })
        store.createIndex('by_saved_at', 'savedAt', { unique: false })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('打开离线库失败'))
  })
  return dbPromise
}

/**
 * 超时兜底：**任何 IndexedDB 操作都必须在有限时间内 settle**。
 *
 * ⚠️ 这不是防御性编程，是必需：隐私模式 / 配额损坏 / 某些实现的 `indexedDB.open`
 * **永远不回调**（既不 success 也不 error）。而离线回退路径是 `await getMangaManifest(...)`，
 * 一旦它永不 settle，用户看到的是**永远转圈的加载框**——比"显示不出图"更糟。
 */
function withTimeout<T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise<T>(resolve => {
    const timer = setTimeout(() => resolve(fallback), ms)
    const settle = (value: T) => { clearTimeout(timer); resolve(value) }
    promise.then(settle, () => settle(fallback))
  })
}

/**
 * 保存一章的清单快照（幂等覆盖）。
 *
 * 失败**静默**：快照只是"离线时的兜底"，写不进去也绝不能影响在线阅读。
 */
export async function putMangaManifest(snapshot: Omit<MangaManifestSnapshot, 'key' | 'savedAt'>): Promise<void> {
  try {
    const db = await withTimeout(openDb(), DB_TIMEOUT_MS, null)
    if (!db) return
    const record: MangaManifestSnapshot = {
      ...snapshot,
      key: mangaManifestKey(snapshot.sourceId, snapshot.chapterUrl),
      savedAt: Date.now(),
    }
    await withTimeout(new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_MANIFESTS, 'readwrite')
      tx.objectStore(STORE_MANIFESTS).put(record)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error ?? new Error('写入快照失败'))
    }), DB_TIMEOUT_MS, undefined)
  } catch {
    // 离线快照属于增强项，失败不冒泡
  }
}

/** 取一章的清单快照；没有 / 读不到 / 超时都返回 null。 */
export async function getMangaManifest(sourceId: string, chapterUrl: string): Promise<MangaManifestSnapshot | null> {
  try {
    const db = await withTimeout(openDb(), DB_TIMEOUT_MS, null)
    if (!db) return null
    return await withTimeout(new Promise<MangaManifestSnapshot | null>((resolve, reject) => {
      const tx = db.transaction(STORE_MANIFESTS, 'readonly')
      const request = tx.objectStore(STORE_MANIFESTS).get(mangaManifestKey(sourceId, chapterUrl))
      request.onsuccess = () => resolve((request.result as MangaManifestSnapshot | undefined) ?? null)
      request.onerror = () => reject(request.error ?? new Error('读取快照失败'))
    }), DB_TIMEOUT_MS, null)
  } catch {
    return null
  }
}

/** 清掉某本书（或全部）的清单快照。只删元数据，SW 里的图片字节交给 SW 自己的配额策略。 */
export async function clearMangaManifests(sourceId?: string, bookUrl?: string): Promise<number> {
  try {
    const db = await withTimeout(openDb(), DB_TIMEOUT_MS, null)
    if (!db) return 0
    return await withTimeout(new Promise<number>((resolve, reject) => {
      const tx = db.transaction(STORE_MANIFESTS, 'readwrite')
      const store = tx.objectStore(STORE_MANIFESTS)
      let removed = 0
      const range = sourceId && bookUrl ? IDBKeyRange.only([sourceId, bookUrl]) : null
      const cursorRequest = range ? store.index('by_book').openCursor(range) : store.openCursor()
      cursorRequest.onsuccess = () => {
        const cursor = cursorRequest.result
        if (!cursor) return
        cursor.delete()
        removed++
        cursor.continue()
      }
      tx.oncomplete = () => resolve(removed)
      tx.onerror = () => reject(tx.error ?? new Error('清理快照失败'))
    }), DB_TIMEOUT_MS, 0)
  } catch {
    return 0
  }
}

/** 预热结果：成功进了 SW 缓存的张数 / 总数（总数 0 表示本章没有图）。 */
export interface WarmResult {
  warmed: number
  total: number
}

/**
 * 整章预热：把这一章的所有图片塞进 Service Worker 的 `legado-manga` 缓存。
 *
 * ## 设计要点
 * - **逐张 fetch 就是唯一正确的做法**：请求经过 SW 的 `CacheFirst` 规则被缓存下来，
 *   断网时同一张图会被 SW 直接从缓存返回。
 * - **并发 4**（与正文离线切片下载同思想）：并发过高会把上游站掐连接、也让前台掉帧。
 * - **失败一律吞掉**：上游 404/防盗链/离线都是常态，预热只是"尽力而为"。
 * - `fetchImpl` 是注入接缝：让 Node 单测能验证并发与失败计数，不必起浏览器。
 */
export async function warmChapterImages(
  keys: string[],
  options: {
    signal?: AbortSignal
    concurrency?: number
    imageUrl?: (key: string) => string
    fetchImpl?: typeof fetch
  } = {},
): Promise<WarmResult> {
  const concurrency = Math.max(1, options.concurrency ?? 4)
  const toUrl = options.imageUrl ?? ((key: string) => `/api/images/${key}`)
  const doFetch = options.fetchImpl ?? (typeof fetch === 'function' ? fetch.bind(globalThis) : null)
  if (!doFetch || keys.length === 0) return { warmed: 0, total: keys.length }

  let warmed = 0
  let cursor = 0
  const workers = Array.from({ length: Math.min(concurrency, keys.length) }, async () => {
    while (true) {
      if (options.signal?.aborted) return
      const index = cursor++
      if (index >= keys.length) return
      try {
        const response = await doFetch(toUrl(keys[index]), { signal: options.signal })
        // 只有真正拿到 200 才算"进了缓存"；其余（离线、防盗链 403、404）都只是没预热到
        if (response.ok) warmed++
      } catch {
        // 静默：预热失败不影响任何阅读路径
      }
    }
  })
  await Promise.all(workers)
  return { warmed, total: keys.length }
}
