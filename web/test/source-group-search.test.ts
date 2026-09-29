import test from 'node:test'
import assert from 'node:assert/strict'
import { api, setCsrfToken, streamSearch, UNGROUPED_SOURCE_GROUP } from '../src/api.ts'
import { SearchStore } from '../src/searchStore.ts'

/**
 * 书源分组：前端契约与「按分组搜书」的范围传递。
 *
 * 这里锁的是三件容易静默出错的事：
 * 1. WebSocket 搜索载荷里**只在选了分组时**才带 `group`（否则会污染既有报文契约）；
 * 2. 分组与单源是互斥的（两个都发出去，服务端会按 AND 取交集，用户看到的是「搜不到东西」）；
 * 3. 分组接口的 URL / 方法与「未分组」哨兵值。
 */

const installMockWebSocket = () => {
  const listeners: Record<string, ((event?: any) => void)[]> = {}
  const sentMessages: string[] = []
  class MockWebSocket {
    static OPEN = 1
    readyState = 1
    addEventListener(event: string, cb: (event?: any) => void) {
      listeners[event] = listeners[event] || []
      listeners[event].push(cb)
    }
    send(data: string) { sentMessages.push(data) }
    close() { listeners['close']?.forEach(cb => cb()) }
  }
  const originalWebSocket = globalThis.WebSocket
  const originalLocation = globalThis.location
  ;(globalThis as any).WebSocket = MockWebSocket
  ;(globalThis as any).location = { protocol: 'http:', host: 'localhost:8080' }
  return {
    sentMessages,
    flushOpen: () => listeners['open']?.forEach(cb => cb()),
    restore: () => {
      globalThis.WebSocket = originalWebSocket
      ;(globalThis as any).location = originalLocation
    },
  }
}

const flushMicrotasks = () => new Promise(resolve => queueMicrotask(resolve))

test('source group - ungrouped sentinel matches the server contract', () => {
  // 与服务端 SourceGroupFilter.UNGROUPED 必须逐字一致：改一边不改另一边 = 未分组搜索静默 0 结果
  assert.equal(UNGROUPED_SOURCE_GROUP, '__ungrouped__')
})

test('source group - streamSearch only carries group when one is selected', async () => {
  // 两次调用各用一个独立的 mock：同一 mock 里的 open 监听器会累积，flush 时两个 socket 都会补发，
  // 断言下标会变得依赖调用顺序（踩过一次）。
  const plain = installMockWebSocket()
  try {
    setCsrfToken('mock-csrf-group')
    // 不选分组：报文里不能凭空多出 group 字段（保持既有契约不变）
    streamSearch('凡人修仙传', ['https://src1.com'], () => {}, () => {}, () => {})
    plain.flushOpen()
    await flushMicrotasks()
    assert.equal(plain.sentMessages.length, 1)
    assert.deepEqual(JSON.parse(plain.sentMessages[0]), { keyword: '凡人修仙传', sourceIds: ['https://src1.com'] })
    assert.equal('group' in JSON.parse(plain.sentMessages[0]), false)
  } finally {
    plain.restore()
  }

  const scoped = installMockWebSocket()
  try {
    setCsrfToken('mock-csrf-group')
    // 选了分组：带上 group，且不携带 sourceIds
    streamSearch('凡人修仙传', undefined, () => {}, () => {}, () => {}, '大灰狼聚合')
    scoped.flushOpen()
    await flushMicrotasks()
    assert.equal(scoped.sentMessages.length, 1)
    assert.deepEqual(JSON.parse(scoped.sentMessages[0]), { keyword: '凡人修仙传', group: '大灰狼聚合' })
  } finally {
    scoped.restore()
  }

  const ungrouped = installMockWebSocket()
  try {
    setCsrfToken('mock-csrf-group')
    // 「未分组」也是分组范围的一种，必须照样发出去（否则服务端会退化成搜全部书源）
    streamSearch('凡人修仙传', undefined, () => {}, () => {}, () => {}, UNGROUPED_SOURCE_GROUP)
    ungrouped.flushOpen()
    await flushMicrotasks()
    assert.deepEqual(JSON.parse(ungrouped.sentMessages[0]), { keyword: '凡人修仙传', group: UNGROUPED_SOURCE_GROUP })
  } finally {
    ungrouped.restore()
  }
})

test('source group - store keeps group and single source mutually exclusive', async () => {
  const mock = installMockWebSocket()
  try {
    setCsrfToken('mock-csrf-group-store')
    const store = new SearchStore()

    store.setSelectedGroup('大灰狼聚合')
    assert.equal(store.getSnapshot().selectedGroup, '大灰狼聚合')
    assert.equal(store.getSnapshot().selectedSourceId, '')

    // 再选具体书源：分组必须被清掉，否则范围语义自相矛盾
    store.setSelectedSourceId('https://src1.com')
    assert.equal(store.getSnapshot().selectedSourceId, 'https://src1.com')
    assert.equal(store.getSnapshot().selectedGroup, '')

    // 反向同理
    store.setSelectedGroup(UNGROUPED_SOURCE_GROUP)
    assert.equal(store.getSnapshot().selectedSourceId, '')

    // 搜索时把分组交给搜索通道
    store.setKeyword('十日终焉')
    store.startSearch()
    mock.flushOpen()
    await flushMicrotasks()
    assert.deepEqual(JSON.parse(mock.sentMessages[0]), { keyword: '十日终焉', group: UNGROUPED_SOURCE_GROUP })

    store.stopSearch()
    store.reset()
    assert.equal(store.getSnapshot().selectedGroup, '', 'reset 必须把搜索范围也还原')
  } finally {
    mock.restore()
  }
})

test('source group - single source narrows scope without group', async () => {
  const mock = installMockWebSocket()
  try {
    setCsrfToken('mock-csrf-group-single')
    const store = new SearchStore()
    store.setSelectedSourceId('https://src1.com')
    store.startSearch('十日终焉')
    mock.flushOpen()
    await flushMicrotasks()
    assert.deepEqual(JSON.parse(mock.sentMessages[0]), { keyword: '十日终焉', sourceIds: ['https://src1.com'] })
    store.stopSearch()
    store.reset()
  } finally {
    mock.restore()
  }
})

test('source group - group management endpoints hit the documented urls', async () => {
  const originalFetch = globalThis.fetch
  const captured: { url: string; method?: string; body?: any }[] = []
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    captured.push({ url: input.toString(), method: init?.method, body: init?.body })
    if (init?.method === 'DELETE') {
      return new Response(JSON.stringify({ ok: true, affected: 2, message: '已删除分组' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify([{ name: '大灰狼聚合', sourceCount: 3, enabledCount: 2 }]), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  try {
    setCsrfToken('csrf-group-api')

    const groups = await api.sourceGroups()
    assert.equal(captured[0].url, '/api/source-groups')
    assert.equal(groups[0].name, '大灰狼聚合')
    assert.equal(groups[0].enabledCount, 2)

    await api.renameSourceGroup('旧分组', '新分组')
    assert.equal(captured[1].url, '/api/source-groups/rename')
    assert.equal(captured[1].method, 'PUT')
    assert.deepEqual(JSON.parse(captured[1].body), { from: '旧分组', to: '新分组' })

    // 分组名可能含中文/空格，必须编码后再放进 query
    await api.clearSourceGroup('大灰狼 聚合')
    assert.equal(captured[2].method, 'DELETE')
    assert.equal(captured[2].url, `/api/source-groups?name=${encodeURIComponent('大灰狼 聚合')}`)
  } finally {
    globalThis.fetch = originalFetch
  }
})
