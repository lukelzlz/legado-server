import test from 'node:test'
import assert from 'node:assert/strict'
import { SearchStore, bookKey, groupSearchResults } from '../src/searchStore'
import { SearchResult } from '../src/api'

test('SearchStatePersistence - store state updates and reactive subscriptions', () => {
  const store = new SearchStore()
  let notifiedCount = 0
  const unsubscribe = store.subscribe(() => {
    notifiedCount++
  })

  assert.equal(store.getSnapshot().keyword, '')
  assert.equal(store.getSnapshot().selectedSourceId, '')
  assert.equal(store.getSnapshot().results.length, 0)

  store.setKeyword('凡人修仙传')
  assert.equal(store.getSnapshot().keyword, '凡人修仙传')
  assert.equal(notifiedCount, 1)

  store.setSelectedSourceId('source-1')
  assert.equal(store.getSnapshot().selectedSourceId, 'source-1')
  assert.equal(notifiedCount, 2)

  store.setFilters(prev => ({ ...prev, withIntro: true }))
  assert.equal(store.getSnapshot().filters.withIntro, true)
  assert.equal(notifiedCount, 3)

  unsubscribe()
  store.setKeyword('大奉打更人')
  assert.equal(notifiedCount, 3, 'listener should not be notified after unsubscribe')
  assert.equal(store.getSnapshot().keyword, '大奉打更人')
})

test('SearchStatePersistence - groupSearchResults groups by name and author with case-insensitivity', () => {
  const mockResults: SearchResult[] = [
    { sourceId: 'source-1', bookUrl: 'http://s1.com/b1', name: ' 诡秘之主 ', author: '爱潜水的乌贼', coverUrl: 'http://c1.jpg' },
    { sourceId: 'source-2', bookUrl: 'http://s2.com/b1', name: '诡秘之主', author: '爱潜水的乌贼', intro: '蒸汽与克苏鲁' },
    { sourceId: 'source-3', bookUrl: 'http://s3.com/b2', name: '诡秘之主', author: '同名其他作者' },
    { sourceId: 'source-1', bookUrl: 'http://s1.com/b3', name: '宿命之环', author: '爱潜水的乌贼' },
  ]

  const groups = groupSearchResults(mockResults)
  assert.equal(groups.length, 3, 'should group into 3 unique book groups')

  const guimiGroup = groups.find(g => g.name.trim() === '诡秘之主' && g.author === '爱潜水的乌贼')
  assert.ok(guimiGroup, 'found guimi by author')
  assert.equal(guimiGroup!.sources.length, 2)
  assert.equal(guimiGroup!.sources[0].sourceId, 'source-1')
  assert.equal(guimiGroup!.sources[1].sourceId, 'source-2')
})

test('SearchStatePersistence - simulated background streaming updates persist across subscriptions', () => {
  const store = new SearchStore()

  // Simulate component A mounting and subscribing
  let viewSnapshot = store.getSnapshot()
  let unmountA = store.subscribe(() => {
    viewSnapshot = store.getSnapshot()
  })

  // Start search
  store.setKeyword('剑来')
  assert.equal(store.getSnapshot().keyword, '剑来')

  // Simulate incoming streaming results
  const result1: SearchResult = { sourceId: 'src-1', bookUrl: 'http://src1.com/jianlai', name: '剑来', author: '烽火戏诸侯' }
  const result2: SearchResult = { sourceId: 'src-2', bookUrl: 'http://src2.com/jianlai', name: '剑来', author: '烽火戏诸侯' }

  store.setChoices([{ result: result1, status: 'idle' }])
  assert.equal(viewSnapshot.choices.length, 1)

  // Component A unmounts (e.g. user navigates to Shelf or Reader)
  unmountA()

  // Background events continue while unmounted
  store.setChoices([
    { result: result1, status: 'idle' },
    { result: result2, status: 'idle' },
  ])

  // Component B mounts (e.g. user navigates back to Library)
  let componentBState = store.getSnapshot()
  const unmountB = store.subscribe(() => {
    componentBState = store.getSnapshot()
  })

  // Verify that state was preserved and updated in background
  assert.equal(componentBState.keyword, '剑来')
  assert.equal(componentBState.choices.length, 2)
  assert.equal(componentBState.choices[1].result.sourceId, 'src-2')

  unmountB()
})

test('SearchStatePersistence - smart origin routing logic', () => {
  type Page = 'sources' | 'subscriptions' | 'library' | 'shelf' | 'reader'

  let currentPage: Page = 'library'
  let readerReturnPage: Page = 'shelf'

  const openReader = (origin: Page) => {
    readerReturnPage = origin
    currentPage = 'reader'
  }

  const closeReader = () => {
    currentPage = readerReturnPage
  }

  // 1. Open from Library (Search results) -> close reader should return to library
  currentPage = 'library'
  openReader('library')
  assert.equal(currentPage, 'reader')
  assert.equal(readerReturnPage, 'library')
  closeReader()
  assert.equal(currentPage, 'library', 'should return to library when opened from search')

  // 2. Open from Shelf -> close reader should return to shelf
  currentPage = 'shelf'
  openReader('shelf')
  assert.equal(currentPage, 'reader')
  assert.equal(readerReturnPage, 'shelf')
  closeReader()
  assert.equal(currentPage, 'shelf', 'should return to shelf when opened from bookshelf')
})

test('SearchStatePersistence - store reset clears all state cleanly', () => {
  const store = new SearchStore()
  store.setKeyword('道诡异仙')
  store.setSelectedSourceId('src-abc')
  store.setFilters({ query: '火旺', minimumSources: 2, withIntro: true, withCover: false })

  assert.equal(store.getSnapshot().keyword, '道诡异仙')
  assert.equal(store.getSnapshot().selectedSourceId, 'src-abc')
  assert.equal(store.getSnapshot().filters.query, '火旺')

  store.reset()

  const snapshot = store.getSnapshot()
  assert.equal(snapshot.keyword, '')
  assert.equal(snapshot.selectedSourceId, '')
  assert.equal(snapshot.results.length, 0)
  assert.equal(snapshot.choices.length, 0)
  assert.equal(snapshot.openBook, null)
  assert.equal(snapshot.loading, false)
  assert.equal(snapshot.filters.query, '')
  assert.equal(snapshot.filters.minimumSources, 1)
})
