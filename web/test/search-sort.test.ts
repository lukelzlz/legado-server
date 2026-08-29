import test from 'node:test'
import assert from 'node:assert/strict'
import { SearchResult } from '../src/api'
import {
  cleanAuthor,
  cleanTitle,
  calculateRelevanceScore,
  sortSearchGroups,
  filterSearchGroups,
  isExactMatch,
  isPopularMatch,
  defaultSearchFilters,
  SearchGroup,
} from '../src/searchFilters'
import { bookKey, groupSearchResults } from '../src/searchStore'

test('SearchSort - cleanTitle and cleanAuthor strip noise and symbols', () => {
  assert.equal(cleanTitle('[“我是大玩家”]'), '我是大玩家')
  assert.equal(cleanTitle('《诡秘之主》'), '诡秘之主')
  assert.equal(cleanTitle('【都市】重生之大玩家'), '重生之大玩家')
  assert.equal(cleanTitle('  "凡人修仙传"  '), '凡人修仙传')

  assert.equal(cleanAuthor('[“会说话的肘子”]'), '会说话的肘子')
  assert.equal(cleanAuthor('爱潜水的乌贼'), '爱潜水的乌贼')
  assert.equal(cleanAuthor('未知作者'), undefined)
  assert.equal(cleanAuthor('未知'), undefined)
  assert.equal(cleanAuthor(''), undefined)
  assert.equal(cleanAuthor(undefined), undefined)
})

test('SearchSort - groupSearchResults cleans titles and merges unknown authors into known author groups', () => {
  const rawResults: SearchResult[] = [
    { sourceId: 'src-1', bookUrl: 'http://s1/1', name: '[“我是大玩家”]', author: '未知作者' },
    { sourceId: 'src-2', bookUrl: 'http://s2/1', name: '我是大玩家', author: '[“会说话的肘子”]' },
    { sourceId: 'src-3', bookUrl: 'http://s3/1', name: '我是大玩家', author: '会说话的肘子' },
    { sourceId: 'src-4', bookUrl: 'http://s4/2', name: '大玩家', author: '给您添蘑菇啦' },
  ]

  const groups = groupSearchResults(rawResults)
  assert.equal(groups.length, 2, 'should produce 2 clean groups')

  const target = groups.find(g => g.name === '我是大玩家')
  assert.ok(target, 'target book found')
  assert.equal(target!.name, '我是大玩家')
  assert.equal(target!.author, '会说话的肘子')
  assert.equal(target!.sources.length, 3, 'unknown author should be merged into known author group')

  const other = groups.find(g => g.name === '大玩家')
  assert.ok(other)
  assert.equal(other!.author, '给您添蘑菇啦')
  assert.equal(other!.sources.length, 1)
})

test('SearchSort - calculateRelevanceScore prioritizes exact match and multi-source items', () => {
  const keyword = '我是大玩家'

  const exactMultiSource: SearchGroup = {
    key: '我是大玩家\u0000会说话的肘子',
    name: '我是大玩家',
    author: '会说话的肘子',
    sources: [
      { sourceId: 's1', bookUrl: 'u1', name: '我是大玩家', author: '会说话的肘子', coverUrl: 'http://c.jpg' },
      { sourceId: 's2', bookUrl: 'u2', name: '我是大玩家', author: '会说话的肘子', intro: '简介' },
      { sourceId: 's3', bookUrl: 'u3', name: '我是大玩家', author: '会说话的肘子' },
    ],
  }

  const exactSingleSource: SearchGroup = {
    key: '我是大玩家\u0000会说话的肘子',
    name: '我是大玩家',
    author: '会说话的肘子',
    sources: [
      { sourceId: 's1', bookUrl: 'u1', name: '我是大玩家', author: '会说话的肘子' },
    ],
  }

  const prefixMatch: SearchGroup = {
    key: '我是超能大玩家\u0000悠悠得',
    name: '我是超能大玩家',
    author: '悠悠得',
    sources: [
      { sourceId: 's1', bookUrl: 'u4', name: '我是超能大玩家', author: '悠悠得' },
    ],
  }

  const irrelevantSingleSource: SearchGroup = {
    key: '死喊生：维纳斯沦陷录\u0000未知',
    name: '死喊生：维纳斯沦陷录',
    author: undefined,
    sources: [
      { sourceId: 's5', bookUrl: 'u5', name: '死喊生：维纳斯沦陷录' },
    ],
  }

  const scoreExactMulti = calculateRelevanceScore(exactMultiSource, keyword)
  const scoreExactSingle = calculateRelevanceScore(exactSingleSource, keyword)
  const scorePrefix = calculateRelevanceScore(prefixMatch, keyword)
  const scoreIrrelevant = calculateRelevanceScore(irrelevantSingleSource, keyword)

  assert.ok(scoreExactMulti > scoreExactSingle, 'multi-source exact should outscore single-source exact')
  assert.ok(scoreExactSingle > scorePrefix, 'exact title match should outscore partial title match')
  assert.ok(scorePrefix > scoreIrrelevant, 'relevant title should outscore completely irrelevant title')
})

test('SearchSort - sortSearchGroups respects various SortMode values', () => {
  const keyword = '大玩家'

  const bookA: SearchGroup = {
    key: '我是大玩家\u0000会说话的肘子',
    name: '我是大玩家',
    author: '会说话的肘子',
    sources: Array(5).fill({ sourceId: 's', bookUrl: 'u', name: '我是大玩家' }),
  }

  const bookB: SearchGroup = {
    key: '大玩家\u0000给您添蘑菇啦',
    name: '大玩家',
    author: '给您添蘑菇啦',
    sources: Array(2).fill({ sourceId: 's', bookUrl: 'u', name: '大玩家' }),
  }

  const bookC: SearchGroup = {
    key: '超能玩家\u0000某某',
    name: '超能玩家',
    author: '某某',
    sources: Array(10).fill({ sourceId: 's', bookUrl: 'u', name: '超能玩家' }),
  }

  const groups = [bookA, bookB, bookC]

  // Smart sort (Exact match '大玩家' should rank high, or multi-source '我是大玩家' + '大玩家')
  const smartSorted = sortSearchGroups(groups, 'smart', keyword)
  assert.equal(smartSorted[0].name, '大玩家', 'exact title match should lead in smart mode')

  // Sources count sort
  const sourcesSorted = sortSearchGroups(groups, 'sources', keyword)
  assert.equal(sourcesSorted[0].name, '超能玩家', 'highest sources count should be first')
  assert.equal(sourcesSorted[1].name, '我是大玩家')
  assert.equal(sourcesSorted[2].name, '大玩家')

  // Exact sort
  const exactSorted = sortSearchGroups(groups, 'exact', keyword)
  assert.equal(exactSorted[0].name, '大玩家', 'exact match is first')
})

test('SearchSort - isExactMatch and isPopularMatch helpers', () => {
  const groupExact: SearchGroup = {
    key: '我是大玩家\u0000肘子',
    name: '我是大玩家',
    sources: [1, 2, 3].map(i => ({ sourceId: `s${i}`, bookUrl: `u${i}`, name: '我是大玩家' })),
  }

  assert.equal(isExactMatch(groupExact, '我是大玩家'), true)
  assert.equal(isExactMatch(groupExact, ' [“我是大玩家”] '), true)
  assert.equal(isExactMatch(groupExact, '其他书'), false)

  assert.equal(isPopularMatch(groupExact), true)

  const groupSingle: SearchGroup = {
    key: '单源书\u0000',
    name: '单源书',
    sources: [{ sourceId: 's1', bookUrl: 'u1', name: '单源书' }],
  }
  assert.equal(isPopularMatch(groupSingle), false)
})
