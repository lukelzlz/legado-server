import test from 'node:test'
import assert from 'node:assert/strict'
import { BookshelfItem, BookGroup } from '../src/api'

test('ShelfGrouping - group and status filtering', () => {
  const items: BookshelfItem[] = [
    { sourceId: 's1', bookUrl: 'https://b1', name: '凡人修仙传', author: '忘语', tocUrl: 'toc1', lastReadAt: 1000, cachedChapters: 10, totalChapters: 100, cacheState: 'ready', completed: false, groupName: '修仙' },
    { sourceId: 's1', bookUrl: 'https://b2', name: '仙逆', author: '耳根', tocUrl: 'toc2', lastReadAt: 2000, cachedChapters: 50, totalChapters: 50, cacheState: 'ready', completed: true, groupName: '修仙' },
    { sourceId: 's1', bookUrl: 'https://b3', name: '三体', author: '刘慈欣', tocUrl: 'toc3', lastReadAt: 3000, cachedChapters: 30, totalChapters: 30, cacheState: 'ready', completed: true, groupName: '科幻' },
    { sourceId: 's1', bookUrl: 'https://b4', name: '无分组书', author: '佚名', tocUrl: 'toc4', lastReadAt: 4000, cachedChapters: 0, totalChapters: 10, cacheState: 'idle', completed: false },
  ]

  const filterItems = (selectedGroup: string, statusFilter: 'all' | 'reading' | 'completed') => {
    const groupFiltered = selectedGroup === 'all'
      ? items
      : selectedGroup === '__ungrouped__'
      ? items.filter(i => !i.groupName)
      : items.filter(i => i.groupName === selectedGroup)

    if (statusFilter === 'all') return groupFiltered
    if (statusFilter === 'completed') return groupFiltered.filter(i => i.completed)
    return groupFiltered.filter(i => !i.completed)
  }

  // 1. All groups, all status
  assert.equal(filterItems('all', 'all').length, 4)

  // 2. All groups, reading only
  assert.equal(filterItems('all', 'reading').length, 2)
  assert.deepEqual(filterItems('all', 'reading').map(i => i.name), ['凡人修仙传', '无分组书'])

  // 3. '修仙' group, all status
  assert.equal(filterItems('修仙', 'all').length, 2)
  assert.deepEqual(filterItems('修仙', 'all').map(i => i.name), ['凡人修仙传', '仙逆'])

  // 4. '修仙' group, completed only
  assert.equal(filterItems('修仙', 'completed').length, 1)
  assert.equal(filterItems('修仙', 'completed')[0].name, '仙逆')

  // 5. '__ungrouped__' group
  assert.equal(filterItems('__ungrouped__', 'all').length, 1)
  assert.equal(filterItems('__ungrouped__', 'all')[0].name, '无分组书')
})

test('ShelfGrouping - group and status counts calculation', () => {
  const items: BookshelfItem[] = [
    { sourceId: 's1', bookUrl: 'https://b1', name: '书1', tocUrl: 'toc1', lastReadAt: 1000, cachedChapters: 0, totalChapters: 10, cacheState: 'idle', completed: false, groupName: '分组A' },
    { sourceId: 's1', bookUrl: 'https://b2', name: '书2', tocUrl: 'toc2', lastReadAt: 2000, cachedChapters: 0, totalChapters: 10, cacheState: 'idle', completed: true, groupName: '分组A' },
    { sourceId: 's1', bookUrl: 'https://b3', name: '书3', tocUrl: 'toc3', lastReadAt: 3000, cachedChapters: 0, totalChapters: 10, cacheState: 'idle', completed: false },
  ]

  let ungrouped = 0
  items.forEach(i => {
    if (!i.groupName) ungrouped++
  })

  assert.equal(items.length, 3)
  assert.equal(ungrouped, 1)

  const groupAItems = items.filter(i => i.groupName === '分组A')
  assert.equal(groupAItems.length, 2)
  assert.equal(groupAItems.filter(i => !i.completed).length, 1)
  assert.equal(groupAItems.filter(i => i.completed).length, 1)
})
