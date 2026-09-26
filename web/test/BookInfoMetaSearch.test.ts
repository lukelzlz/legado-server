import test from 'node:test'
import assert from 'node:assert/strict'
import { BookshelfItem, SearchResult } from '../src/api'

test('BookInfoMetaSearch - candidate covers generation from alternateSources', () => {
  const localItem: BookshelfItem = {
    sourceId: 'loc_book',
    bookUrl: 'local://book-test-1',
    name: '变成魔女，但是她们都想跟我恋爱',
    author: undefined,
    tocUrl: 'local://book-test-1/toc',
    coverKey: undefined,
    lastReadAt: Date.now(),
    cachedChapters: 10,
    totalChapters: 10,
    cacheState: 'ready',
    completed: false,
    alternateSources: [
      {
        sourceId: '刺猬猫',
        name: '变成魔女，但是她们都想跟我恋爱',
        author: '魔女作者',
        bookUrl: 'https://ciweimao.example/book/1',
        coverUrl: 'https://ciweimao.example/cover.jpg',
      },
      {
        sourceId: '起点',
        name: '变成魔女，但是她们都想跟我恋爱',
        author: '魔女作者',
        bookUrl: 'https://qidian.example/book/2',
        coverUrl: 'https://qidian.example/cover2.jpg',
      },
      {
        sourceId: '聚合源',
        name: '变成魔女，但是她们都想跟我恋爱',
        author: '魔女作者',
        bookUrl: 'https://juhe.example/book/3',
        coverUrl: 'https://ciweimao.example/cover.jpg', // Duplicate cover
      }
    ]
  }

  // Deduplicate covers by URL
  const map = new Map<string, { sourceId: string; coverUrl: string }>()
  for (const alt of localItem.alternateSources || []) {
    if (alt.coverUrl && !map.has(alt.coverUrl)) {
      map.set(alt.coverUrl, { sourceId: alt.sourceId, coverUrl: alt.coverUrl })
    }
  }
  const candidateCovers = Array.from(map.values())

  assert.equal(candidateCovers.length, 2)
  assert.equal(candidateCovers[0].sourceId, '刺猬猫')
  assert.equal(candidateCovers[0].coverUrl, 'https://ciweimao.example/cover.jpg')
  assert.equal(candidateCovers[1].sourceId, '起点')
  assert.equal(candidateCovers[1].coverUrl, 'https://qidian.example/cover2.jpg')
})

test('BookInfoMetaSearch - candidate merge logic on selection', () => {
  const existingAlternates: SearchResult[] = [
    {
      sourceId: 'src-1',
      name: '小说名',
      author: '作者A',
      bookUrl: 'https://src-1.example/b1',
      coverUrl: 'https://src-1.example/c1.jpg',
    }
  ]

  const searchResults: SearchResult[] = [
    {
      sourceId: 'src-2',
      name: '小说名',
      author: '作者B',
      bookUrl: 'https://src-2.example/b2',
      coverUrl: 'https://src-2.example/c2.jpg',
    },
    {
      sourceId: 'src-3',
      name: '小说名',
      author: '作者B',
      bookUrl: 'https://src-3.example/b3',
      coverUrl: 'https://src-3.example/c3.jpg',
    }
  ]

  const chosenCandidate = searchResults[0] // src-2

  // Simulate handleApplyCandidate merge logic
  const existing = new Set(existingAlternates.map(s => `${s.sourceId}\u0000${s.bookUrl}`))
  const toAdd: SearchResult[] = []
  const candKey = `${chosenCandidate.sourceId}\u0000${chosenCandidate.bookUrl}`
  if (!existing.has(candKey)) {
    toAdd.push(chosenCandidate)
    existing.add(candKey)
  }
  for (const r of searchResults) {
    const k = `${r.sourceId}\u0000${r.bookUrl}`
    if (!existing.has(k)) {
      toAdd.push(r)
      existing.add(k)
    }
  }
  const merged = [...toAdd, ...existingAlternates]

  assert.equal(merged.length, 3)
  assert.equal(merged[0].sourceId, 'src-2') // chosen candidate prioritized
  assert.equal(merged[1].sourceId, 'src-3')
  assert.equal(merged[2].sourceId, 'src-1')
})
