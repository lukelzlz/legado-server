import test from 'node:test'
import assert from 'node:assert/strict'
import { BookshelfItem, LocalBookImportResponse } from '../src/api'

test('LocalBookImport - api types and local book shelf item identification', () => {
  const localItem: BookshelfItem = {
    sourceId: 'loc_book',
    bookUrl: 'local://123456',
    name: '凡人修仙传',
    author: '忘语',
    tocUrl: 'local://123456/toc',
    coverKey: 'local_cover_hash',
    lastReadAt: Date.now(),
    cachedChapters: 2400,
    totalChapters: 2400,
    cacheState: 'ready',
    completed: false,
  }

  assert.equal(localItem.sourceId, 'loc_book')
  assert.equal(localItem.bookUrl.startsWith('local://'), true)
  assert.equal(localItem.cacheState, 'ready')

  const importResponse: LocalBookImportResponse = {
    total: 2,
    imported: 2,
    failed: 0,
    results: [
      {
        filename: '凡人修仙传.txt',
        success: true,
        bookUrl: 'local://123456',
        name: '凡人修仙传',
        author: '忘语',
        totalChapters: 2400,
      },
      {
        filename: '三体.epub',
        success: true,
        bookUrl: 'local://789012',
        name: '三体',
        author: '刘慈欣',
        totalChapters: 36,
      }
    ]
  }

  assert.equal(importResponse.imported, 2)
  assert.equal(importResponse.failed, 0)
  assert.equal(importResponse.results[0].name, '凡人修仙传')
  assert.equal(importResponse.results[1].author, '刘慈欣')
})
