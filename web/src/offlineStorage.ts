/**
 * Offline Storage & Client-side Two-tier Cache Engine
 * Uses IndexedDB for rich structured offline chapter storage,
 * localStorage for bookshelf snapshots and offline reading progress queues.
 */

export interface OfflineChapterRecord {
  key: string // `${sourceId}::${bookUrl}::${chapterUrl}`
  sourceId: string
  bookUrl: string
  chapterUrl: string
  title: string | null
  content: string
  cachedAt: number
  byteSize: number
}

export interface OfflineBookStat {
  sourceId: string
  bookUrl: string
  name?: string
  chapterCount: number
  totalBytes: number
}

export interface OfflineStorageStats {
  books: OfflineBookStat[]
  totalChapters: number
  totalBytes: number
}

const DB_NAME = 'legado_offline_db'
const DB_VERSION = 1
const STORE_CHAPTERS = 'chapters'

const SNAPSHOT_SHELF_KEY = 'legado_shelf_snapshot'
const OFFLINE_PROGRESS_QUEUE_KEY = 'legado_offline_progress_queue'
const LRU_EVICT_KEY = 'legado_lru_evict_enabled'

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      return reject(new Error('IndexedDB not supported in current environment'))
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_CHAPTERS)) {
        const store = db.createObjectStore(STORE_CHAPTERS, { keyPath: 'key' })
        store.createIndex('by_book', ['sourceId', 'bookUrl'], { unique: false })
        store.createIndex('by_cached_at', 'cachedAt', { unique: false })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  return dbPromise
}

function makeChapterKey(sourceId: string, bookUrl: string, chapterUrl: string): string {
  return `${sourceId}::${bookUrl}::${chapterUrl}`
}

/**
 * Saves a chapter to client-side IndexedDB offline pool
 */
export async function putOfflineChapter(
  sourceId: string,
  bookUrl: string,
  chapterUrl: string,
  title: string | null,
  content: string
): Promise<void> {
  const db = await openDb()
  const key = makeChapterKey(sourceId, bookUrl, chapterUrl)
  const byteSize = new Blob([content]).size
  const record: OfflineChapterRecord = {
    key,
    sourceId,
    bookUrl,
    chapterUrl,
    title,
    content,
    cachedAt: Date.now(),
    byteSize,
  }

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readwrite')
      const store = tx.objectStore(STORE_CHAPTERS)
      const req = store.put(record)
      req.onsuccess = () => resolve()
      req.onerror = () => {
        if (req.error && req.error.name === 'QuotaExceededError' && isLruEvictEnabled()) {
          // Attempt automatic LRU eviction and retry once
          void evictLruOfflineBooks(byteSize * 10).then(() => {
            const retryTx = db.transaction(STORE_CHAPTERS, 'readwrite')
            const retryReq = retryTx.objectStore(STORE_CHAPTERS).put(record)
            retryReq.onsuccess = () => resolve()
            retryReq.onerror = () => reject(retryReq.error)
          }).catch(reject)
        } else {
          reject(req.error)
        }
      }
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Retrieves a chapter from client-side IndexedDB offline pool
 */
export async function getOfflineChapter(
  sourceId: string,
  bookUrl: string,
  chapterUrl: string
): Promise<{ title: string | null; content: string } | null> {
  const db = await openDb()
  const key = makeChapterKey(sourceId, bookUrl, chapterUrl)

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readonly')
      const store = tx.objectStore(STORE_CHAPTERS)
      const req = store.get(key)
      req.onsuccess = () => {
        const record = req.result as OfflineChapterRecord | undefined
        if (record && record.content) {
          resolve({ title: record.title, content: record.content })
        } else {
          resolve(null)
        }
      }
      req.onerror = () => reject(req.error)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Returns a Set of cached chapterUrls for a specific book in client IndexedDB
 */
export async function getOfflineChaptersSet(
  sourceId: string,
  bookUrl: string
): Promise<Set<string>> {
  const db = await openDb()

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readonly')
      const store = tx.objectStore(STORE_CHAPTERS)
      const index = store.index('by_book')
      const range = IDBKeyRange.only([sourceId, bookUrl])
      const req = index.openCursor(range)
      const result = new Set<string>()

      req.onsuccess = () => {
        const cursor = req.result
        if (cursor) {
          const record = cursor.value as OfflineChapterRecord
          if (record && record.chapterUrl) {
            result.add(record.chapterUrl)
          }
          cursor.continue()
        } else {
          resolve(result)
        }
      }
      req.onerror = () => reject(req.error)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Calculates storage stats across all offline books
 */
export async function getOfflineStorageStats(): Promise<OfflineStorageStats> {
  const db = await openDb()

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readonly')
      const store = tx.objectStore(STORE_CHAPTERS)
      const req = store.openCursor()
      const bookMap = new Map<string, OfflineBookStat>()
      let totalChapters = 0
      let totalBytes = 0

      req.onsuccess = () => {
        const cursor = req.result
        if (cursor) {
          const record = cursor.value as OfflineChapterRecord
          const bookKey = `${record.sourceId}::${record.bookUrl}`
          const stat = bookMap.get(bookKey) || {
            sourceId: record.sourceId,
            bookUrl: record.bookUrl,
            chapterCount: 0,
            totalBytes: 0,
          }
          stat.chapterCount += 1
          const bytes = record.byteSize || (record.content?.length || 0) * 2
          stat.totalBytes += bytes
          bookMap.set(bookKey, stat)

          totalChapters += 1
          totalBytes += bytes
          cursor.continue()
        } else {
          resolve({
            books: Array.from(bookMap.values()),
            totalChapters,
            totalBytes,
          })
        }
      }
      req.onerror = () => reject(req.error)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Clears offline chapters for a specific book
 */
export async function clearOfflineBook(sourceId: string, bookUrl: string): Promise<number> {
  const db = await openDb()

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readwrite')
      const store = tx.objectStore(STORE_CHAPTERS)
      const index = store.index('by_book')
      const range = IDBKeyRange.only([sourceId, bookUrl])
      const req = index.openKeyCursor(range)
      const keysToDelete: IDBValidKey[] = []

      req.onsuccess = () => {
        const cursor = req.result
        if (cursor) {
          keysToDelete.push(cursor.primaryKey)
          cursor.continue()
        } else {
          for (const key of keysToDelete) {
            store.delete(key)
          }
          resolve(keysToDelete.length)
        }
      }
      req.onerror = () => reject(req.error)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Clears all offline chapters
 */
export async function clearAllOffline(): Promise<void> {
  const db = await openDb()

  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_CHAPTERS, 'readwrite')
      const store = tx.objectStore(STORE_CHAPTERS)
      const req = store.clear()
      req.onsuccess = () => resolve()
      req.onerror = () => reject(req.error)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Automatic LRU Eviction: Removes oldest cached books when storage is tight
 */
export async function evictLruOfflineBooks(targetBytesToFree = 10 * 1024 * 1024): Promise<number> {
  const stats = await getOfflineStorageStats()
  if (stats.books.length <= 1) return 0

  let freed = 0
  for (const book of stats.books) {
    if (freed >= targetBytesToFree) break
    const count = await clearOfflineBook(book.sourceId, book.bookUrl)
    if (count > 0) {
      freed += book.totalBytes
    }
  }
  return freed
}

export function isLruEvictEnabled(): boolean {
  try {
    return localStorage.getItem(LRU_EVICT_KEY) === 'true'
  } catch {
    return false
  }
}

export function setLruEvictEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(LRU_EVICT_KEY, enabled ? 'true' : 'false')
  } catch {
    // ignore
  }
}

/**
 * Bookshelf Snapshot for instant offline display
 */
export function saveShelfSnapshot(items: any[]): void {
  try {
    localStorage.setItem(SNAPSHOT_SHELF_KEY, JSON.stringify({
      items,
      updatedAt: Date.now(),
    }))
  } catch {
    // ignore
  }
}

export function getShelfSnapshot(): any[] | null {
  try {
    const raw = localStorage.getItem(SNAPSHOT_SHELF_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed?.items) ? parsed.items : null
  } catch {
    return null
  }
}

/**
 * Offline Reading Progress Queue
 */
export interface OfflineProgressItem {
  sourceId: string
  bookUrl: string
  chapterUrl: string
  chapterIndex: number
  scrollPosition: number
  updatedAt: number
}

export function enqueueOfflineProgress(progress: OfflineProgressItem): void {
  try {
    const raw = localStorage.getItem(OFFLINE_PROGRESS_QUEUE_KEY)
    const queue: OfflineProgressItem[] = raw ? JSON.parse(raw) : []
    // Remove older entries for same book
    const filtered = queue.filter(
      (item) => !(item.sourceId === progress.sourceId && item.bookUrl === progress.bookUrl)
    )
    filtered.push(progress)
    localStorage.setItem(OFFLINE_PROGRESS_QUEUE_KEY, JSON.stringify(filtered))
  } catch {
    // ignore
  }
}

export async function flushOfflineProgress(
  sendProgressApi: (item: OfflineProgressItem) => Promise<any>
): Promise<number> {
  try {
    const raw = localStorage.getItem(OFFLINE_PROGRESS_QUEUE_KEY)
    if (!raw) return 0
    const queue: OfflineProgressItem[] = JSON.parse(raw)
    if (!queue.length) return 0

    let flushedCount = 0
    const remaining: OfflineProgressItem[] = []

    for (const item of queue) {
      try {
        await sendProgressApi(item)
        flushedCount++
      } catch {
        remaining.push(item)
      }
    }

    if (remaining.length > 0) {
      localStorage.setItem(OFFLINE_PROGRESS_QUEUE_KEY, JSON.stringify(remaining))
    } else {
      localStorage.removeItem(OFFLINE_PROGRESS_QUEUE_KEY)
    }

    return flushedCount
  } catch {
    return 0
  }
}

/**
 * 4-concurrency sliding window offline chapter downloader
 */
export async function downloadChaptersToOffline(
  sourceId: string,
  bookUrl: string,
  chapters: Array<{ index: number; title: string; url: string }>,
  fetchChapterContent: (chapterUrl: string) => Promise<{ title?: string | null; content: string }>,
  onProgress?: (completed: number, total: number) => void,
  signal?: AbortSignal
): Promise<{ successful: number; failed: number }> {
  const existingSet = await getOfflineChaptersSet(sourceId, bookUrl)
  const remaining = chapters.filter((c) => !existingSet.has(c.url))
  const total = remaining.length

  if (total === 0) {
    onProgress?.(chapters.length, chapters.length)
    return { successful: 0, failed: 0 }
  }

  const CONCURRENCY = 4
  let completed = 0
  let successful = 0
  let failed = 0
  let currentIndex = 0

  const runWorker = async (): Promise<void> => {
    while (currentIndex < remaining.length) {
      if (signal?.aborted) break
      const item = remaining[currentIndex++]
      if (!item) break

      try {
        const result = await fetchChapterContent(item.url)
        if (result && result.content) {
          await putOfflineChapter(sourceId, bookUrl, item.url, result.title || item.title, result.content)
          successful++
        } else {
          failed++
        }
      } catch (err) {
        if (signal?.aborted) break
        failed++
      } finally {
        completed++
        onProgress?.(existingSet.size + completed, chapters.length)
      }
    }
  }

  const workers = Array.from({ length: Math.min(CONCURRENCY, remaining.length) }, () => runWorker())
  await Promise.all(workers)

  return { successful, failed }
}
