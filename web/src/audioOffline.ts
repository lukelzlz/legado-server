import type { AudioTrackItem } from './api'

/**
 * 有声书离线（方案 G2 · P3）。
 *
 * ## 与 `mangaOffline` 完全同构，理由也一模一样
 * 音频字节由 Service Worker 的 `legado-audio` 规则缓存，**断网也能放**；
 * 但"这一章有哪些段音频"（清单）是 `POST /api/books/audio` 的结果，SW 默认不缓存 POST。
 * 于是脱网时会出现最难堪的形态：**音频字节都在本地，界面却因为拿不到清单而打不开**。
 * 因此清单必须由前端自己留一份快照（IndexedDB），脱网时直接用它渲染。
 *
 * ## 两条从漫画那边继承来的硬约束
 * 1. **所有 IndexedDB 操作必须带超时**：某些环境 `indexedDB.open` 既不 success 也不 error
 *    ⇒ `await` 永不 settle ⇒ 离线回退**永远转圈**（实测踩过）。
 * 2. 冻结的 `AudioTrackItem[]` 直接复用服务端契约类型，不另造一套结构（少一处漂移）。
 */

const DB_NAME = 'legado_audio_offline_db'
const DB_VERSION = 1
const STORE_MANIFESTS = 'manifests'
/** 单次 IndexedDB 操作的超时（宁可没有快照，也不能让加载框永远转）。 */
const DB_TIMEOUT_MS = 1500

/** 一章的音频清单快照（脱网播放的唯一依据）。 */
export interface AudioManifestSnapshot {
  key: string
  sourceId: string
  bookUrl: string
  chapterUrl: string
  chapterIndex: number
  tracks: AudioTrackItem[]
  savedAt: number
}

/**
 * 快照主键。
 *
 * 用「源 ID 长度 + ':' + 源 ID + 章节地址」而不是 `${a}\u0000${b}`：后者**不是单射**
 * （`("a\0b","c")` 与 `("a","b\0c")` 会撞成同一个 key）。长度前缀无条件单射。
 */
export function audioManifestKey(sourceId: string, chapterUrl: string): string {
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

function withTimeout<T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise<T>(resolve => {
    const timer = setTimeout(() => resolve(fallback), ms)
    const settle = (value: T) => { clearTimeout(timer); resolve(value) }
    promise.then(settle, () => settle(fallback))
  })
}

/** 保存一章的清单快照（幂等覆盖）。失败**静默**：快照只是离线兜底。 */
export async function putAudioManifest(snapshot: Omit<AudioManifestSnapshot, 'key' | 'savedAt'>): Promise<void> {
  try {
    const db = await withTimeout(openDb(), DB_TIMEOUT_MS, null)
    if (!db) return
    const record: AudioManifestSnapshot = {
      ...snapshot,
      key: audioManifestKey(snapshot.sourceId, snapshot.chapterUrl),
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
export async function getAudioManifest(sourceId: string, chapterUrl: string): Promise<AudioManifestSnapshot | null> {
  try {
    const db = await withTimeout(openDb(), DB_TIMEOUT_MS, null)
    if (!db) return null
    return await withTimeout(new Promise<AudioManifestSnapshot | null>((resolve, reject) => {
      const tx = db.transaction(STORE_MANIFESTS, 'readonly')
      const request = tx.objectStore(STORE_MANIFESTS).get(audioManifestKey(sourceId, chapterUrl))
      request.onsuccess = () => resolve((request.result as AudioManifestSnapshot | undefined) ?? null)
      request.onerror = () => reject(request.error ?? new Error('读取快照失败'))
    }), DB_TIMEOUT_MS, null)
  } catch {
    return null
  }
}

/** 清掉某本书（或全部）的清单快照。 */
export async function clearAudioManifests(sourceId?: string, bookUrl?: string): Promise<number> {
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

/** 预热结果：成功进了 SW 缓存的段数 / 总段数。 */
export interface AudioWarmResult {
  warmed: number
  total: number
}

/**
 * 整章预热：把这一章的所有音频段塞进 Service Worker 的 `legado-audio` 缓存。
 *
 * - **并发 2**（比图片的 4 更低）：一段音频 10~60 MiB，并发 4 会把带宽吃满、前台播放直接卡住。
 * - **失败一律吞掉**：上游 403/404、离线都是常态，预热只是"尽力而为"。
 * - `fetchImpl` 是注入接缝：Node 单测可验证并发与失败计数。
 */
export async function warmChapterAudio(
  keys: string[],
  options: {
    signal?: AbortSignal
    concurrency?: number
    audioUrl?: (key: string) => string
    fetchImpl?: typeof fetch
  } = {},
): Promise<AudioWarmResult> {
  const concurrency = Math.max(1, options.concurrency ?? 2)
  const toUrl = options.audioUrl ?? ((key: string) => `/api/audio/${key}`)
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
        // 只有真正拿到 200 才算"进了缓存"；其余（离线、403、404）都只是没预热到
        if (response.ok) warmed++
      } catch {
        // 静默：预热失败不影响任何播放路径
      }
    }
  })
  await Promise.all(workers)
  return { warmed, total: keys.length }
}
