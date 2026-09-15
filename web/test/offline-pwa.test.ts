import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  putOfflineChapter,
  getOfflineChapter,
  getOfflineChaptersSet,
  clearOfflineBook,
  clearAllOffline,
  saveShelfSnapshot,
  getShelfSnapshot,
  enqueueOfflineProgress,
  flushOfflineProgress,
  getOfflineStorageStats,
  isLruEvictEnabled,
  setLruEvictEnabled
} from '../src/offlineStorage.js'

// Mock IndexedDB implementation for test runner
class MockIDBRequest {
  result: any = null
  error: any = null
  onsuccess: ((e: any) => void) | null = null
  onerror: ((e: any) => void) | null = null
}

class MockIDBCursor {
  records: any[]
  index = 0
  req: MockIDBRequest
  constructor(records: any[], req: MockIDBRequest) {
    this.records = records
    this.req = req
  }
  get value() {
    return this.records[this.index]
  }
  get primaryKey() {
    const rec = this.records[this.index]
    return rec?.key ?? `${rec?.sourceId}_${rec?.bookUrl}_${rec?.chapterUrl}`
  }
  continue() {
    this.index++
    setTimeout(() => {
      if (this.index < this.records.length) {
        this.req.result = this
      } else {
        this.req.result = null
      }
      this.req.onsuccess?.({ target: this.req })
    }, 0)
  }
}

class MockIDBIndex {
  store: MockIDBObjectStore
  constructor(store: MockIDBObjectStore) {
    this.store = store
  }
  openCursor(range?: any) {
    return this.store.openCursor(range)
  }
  openKeyCursor(range?: any) {
    return this.store.openCursor(range)
  }
}

class MockIDBObjectStore {
  data = new Map<string, any>()
  name: string
  constructor(name: string) {
    this.name = name
  }
  put(value: any, key?: any) {
    const actualKey = key ?? value.key ?? `${value.sourceId}_${value.bookUrl}_${value.chapterUrl}`
    this.data.set(String(actualKey), value)
    const req = new MockIDBRequest()
    req.result = actualKey
    setTimeout(() => req.onsuccess?.({ target: req }), 0)
    return req
  }
  get(key: any) {
    const req = new MockIDBRequest()
    req.result = this.data.get(String(key))
    setTimeout(() => req.onsuccess?.({ target: req }), 0)
    return req
  }
  delete(key: any) {
    this.data.delete(String(key))
    const req = new MockIDBRequest()
    setTimeout(() => req.onsuccess?.({ target: req }), 0)
    return req
  }
  clear() {
    this.data.clear()
    const req = new MockIDBRequest()
    setTimeout(() => req.onsuccess?.({ target: req }), 0)
    return req
  }
  index(_name: string) {
    return new MockIDBIndex(this)
  }
  openCursor(range?: any) {
    const req = new MockIDBRequest()
    let records = Array.from(this.data.values())
    if (Array.isArray(range) && range.length === 2) {
      const [src, bUrl] = range
      records = records.filter(r => r.sourceId === src && r.bookUrl === bUrl)
    }
    setTimeout(() => {
      if (records.length > 0) {
        req.result = new MockIDBCursor(records, req)
      } else {
        req.result = null
      }
      req.onsuccess?.({ target: req })
    }, 0)
    return req
  }
}

class MockIDBTransaction {
  store: MockIDBObjectStore
  constructor(store: MockIDBObjectStore) {
    this.store = store
  }
  objectStore(_name: string) {
    return this.store
  }
}

class MockIDBDatabase {
  stores = new Map<string, MockIDBObjectStore>()
  objectStoreNames = {
    contains: (name: string) => this.stores.has(name)
  }
  createObjectStore(name: string) {
    const store = new MockIDBObjectStore(name)
    this.stores.set(name, store)
    return store
  }
  transaction(name: string, _mode: string) {
    let store = this.stores.get(name)
    if (!store) {
      store = new MockIDBObjectStore(name)
      this.stores.set(name, store)
    }
    return new MockIDBTransaction(store)
  }
}

let currentDbInstance: MockIDBDatabase | null = null

function setupMockIndexedDB() {
  mockStorage.clear()
  currentDbInstance = new MockIDBDatabase()
  currentDbInstance.createObjectStore('chapters')

  const mockFactory = {
    open: (_name: string, _version: number) => {
      const req = new MockIDBRequest()
      req.result = currentDbInstance
      setTimeout(() => {
        if (req.onsuccess) req.onsuccess({ target: req })
      }, 0)
      return req
    }
  }

  ;(globalThis as any).indexedDB = mockFactory
  ;(globalThis as any).IDBKeyRange = {
    only: (val: any) => val
  }
  return currentDbInstance
}

// Mock localStorage
const mockStorage = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (key: string) => mockStorage.get(key) ?? null,
  setItem: (key: string, val: string) => mockStorage.set(key, String(val)),
  removeItem: (key: string) => mockStorage.delete(key),
  clear: () => mockStorage.clear()
}

test('OfflineStorage - Chapter saving, retrieving, and list cached URLs', async () => {
  setupMockIndexedDB()

  await putOfflineChapter('src-1', 'book-url-1', 'chapter-url-1', '第1章', '这是第1章正文内容', '测试书1')
  await putOfflineChapter('src-1', 'book-url-1', 'chapter-url-2', '第2章', '这是第2章正文内容', '测试书1')

  const result1 = await getOfflineChapter('src-1', 'book-url-1', 'chapter-url-1')
  assert.ok(result1)
  assert.equal(result1?.title, '第1章')
  assert.equal(result1?.content, '这是第1章正文内容')

  const cachedUrls = await getOfflineChaptersSet('src-1', 'book-url-1')
  assert.equal(cachedUrls.size, 2)
  assert.ok(cachedUrls.has('chapter-url-1'))
  assert.ok(cachedUrls.has('chapter-url-2'))

  // Delete single book
  await clearOfflineBook('src-1', 'book-url-1')
  const cachedUrlsAfterDelete = await getOfflineChaptersSet('src-1', 'book-url-1')
  assert.equal(cachedUrlsAfterDelete.size, 0)
})

test('OfflineStorage - Shelf Snapshot persistence and retrieval', () => {
  mockStorage.clear()
  const dummyShelf: any = [
    { sourceId: 'src-1', bookUrl: 'b1', title: '测试书1', author: '作者1', durChapterTitle: '第1章' },
    { sourceId: 'src-2', bookUrl: 'b2', title: '测试书2', author: '作者2', durChapterTitle: '第2章' }
  ]

  saveShelfSnapshot(dummyShelf)
  const retrieved = getShelfSnapshot()

  assert.ok(retrieved)
  assert.equal(retrieved?.length, 2)
  assert.equal(retrieved?.[0].title, '测试书1')
})

test('OfflineStorage - Offline Reading Progress Queue Lifecycle and Flush', async () => {
  mockStorage.clear()

  enqueueOfflineProgress({
    sourceId: 'src-1',
    bookUrl: 'b1',
    chapterIndex: 5,
    chapterTitle: '第5章',
    readProgress: 0.8,
    updatedAt: Date.now()
  })

  enqueueOfflineProgress({
    sourceId: 'src-2',
    bookUrl: 'b2',
    chapterIndex: 10,
    chapterTitle: '第10章',
    readProgress: 0.2,
    updatedAt: Date.now() + 100
  })

  let syncCount = 0
  const syncCallback = async (_item: any) => {
    syncCount++
  }

  await flushOfflineProgress(syncCallback)
  assert.equal(syncCount, 2)

  // Flush again should be empty
  let syncCount2 = 0
  await flushOfflineProgress(async () => { syncCount2++ })
  assert.equal(syncCount2, 0)
})

test('OfflineStorage - Storage Stats, LRU Toggle and Eviction', async () => {
  setupMockIndexedDB()

  // Save chapters for book 1
  await putOfflineChapter('src-1', 'b1', 'c1', '第1章', '内容1'.repeat(100), 'Book 1')
  
  const statsBefore = await getOfflineStorageStats()
  assert.equal(statsBefore.books.length, 1)
  assert.equal(statsBefore.books[0].chapterCount, 1)
  assert.ok(statsBefore.totalBytes > 0)

  // LRU Toggle
  assert.equal(isLruEvictEnabled(), false)
  setLruEvictEnabled(true)
  assert.equal(isLruEvictEnabled(), true)
  setLruEvictEnabled(false)
  assert.equal(isLruEvictEnabled(), false)

  // Clean data
  await clearAllOffline()
  const statsAfter = await getOfflineStorageStats()
  assert.equal(statsAfter.books.length, 0)
  assert.equal(statsAfter.totalBytes, 0)
})

test('Reader Offline Cache Range Math Calculation', () => {
  const totalChapters = 350
  const curIndex = 120

  // 1. Next 50
  const next50Start = curIndex
  const next50End = Math.min(totalChapters - 1, curIndex + 49)
  const next50Count = next50End - next50Start + 1
  assert.equal(next50Start, 120)
  assert.equal(next50End, 169)
  assert.equal(next50Count, 50)

  // 2. Next 100 near end
  const nearEndCurIndex = 320
  const next100Start = nearEndCurIndex
  const next100End = Math.min(totalChapters - 1, nearEndCurIndex + 99)
  const next100Count = next100End - next100Start + 1
  assert.equal(next100Start, 320)
  assert.equal(next100End, 349)
  assert.equal(next100Count, 30) // Clamped to remaining

  // 3. Full book
  const fullStart = 0
  const fullEnd = totalChapters - 1
  const fullCount = totalChapters
  assert.equal(fullStart, 0)
  assert.equal(fullEnd, 349)
  assert.equal(fullCount, 350)
})
