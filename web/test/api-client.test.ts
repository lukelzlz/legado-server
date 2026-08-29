import test from 'node:test'
import assert from 'node:assert/strict'
import { api, setCsrfToken, streamSearch } from '../src/api.ts'

test('api client - setCsrfToken and header injection in requests', async () => {
  const originalFetch = globalThis.fetch
  const capturedRequests: { url: string; method?: string; headers: Headers; body?: any }[] = []

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const headers = new Headers(init?.headers)
    capturedRequests.push({
      url: input.toString(),
      method: init?.method,
      headers,
      body: init?.body,
    })

    if (input.toString().includes('/api/auth/session')) {
      return new Response(JSON.stringify({ authenticated: true, csrfToken: 'test-csrf-123' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (init?.method === 'DELETE') {
      return new Response(null, { status: 204 })
    }
    return new Response(JSON.stringify({ success: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    setCsrfToken('csrf-token-abc')

    // GET request (no CSRF header required)
    const session = await api.session()
    assert.equal(session.authenticated, true)
    assert.equal(capturedRequests[0].headers.get('X-CSRF-Token'), null)

    // POST request (must inject X-CSRF-Token and Content-Type)
    await api.login('mypassword')
    assert.equal(capturedRequests[1].method, 'POST')
    assert.equal(capturedRequests[1].headers.get('X-CSRF-Token'), 'csrf-token-abc')
    assert.equal(capturedRequests[1].headers.get('Content-Type'), 'application/json')

    // DELETE request (204 returns undefined)
    const delResult = await api.remove('https://source1.com')
    assert.equal(delResult, undefined)

    // Error handling when response is not ok
    globalThis.fetch = async (): Promise<Response> => {
      return new Response(JSON.stringify({ message: '自定义错误原因' }), { status: 400 })
    }
    await assert.rejects(
      async () => {
        await api.sources()
      },
      (err: Error) => {
        assert.equal(err.message, '自定义错误原因')
        return true
      }
    )

    // Error handling fallback when response body is not JSON
    globalThis.fetch = async (): Promise<Response> => {
      return new Response('<html>502 Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' })
    }
    await assert.rejects(
      async () => {
        await api.sources()
      },
      (err: Error) => {
        assert.equal(err.message, 'Bad Gateway')
        return true
      }
    )
  } finally {
    globalThis.fetch = originalFetch
    setCsrfToken(null)
  }
})

test('api client - cover helper URL encoding', () => {
  const coverUrl = api.cover('covers/book1?key=val')
  assert.equal(coverUrl, '/api/covers/covers%2Fbook1%3Fkey%3Dval')
})

test('api client - streamSearch WebSocket lifecycle and error handling', async () => {
  const listeners: Record<string, ((event?: any) => void)[]> = {}
  const sentMessages: string[] = []
  let socketClosed = false

  class MockWebSocket {
    static OPEN = 1
    readyState = 1

    addEventListener(event: string, cb: (event?: any) => void) {
      listeners[event] = listeners[event] || []
      listeners[event].push(cb)
    }

    send(data: string) {
      sentMessages.push(data)
    }

    close() {
      socketClosed = true
      listeners['close']?.forEach(cb => cb())
    }
  }

  const originalWebSocket = globalThis.WebSocket
  const originalLocation = globalThis.location
  ;(globalThis as any).WebSocket = MockWebSocket
  ;(globalThis as any).location = { protocol: 'http:', host: 'localhost:8080' }

  try {
    setCsrfToken('mock-csrf-ws')
    const events: any[] = []
    let reportedError: string | null = null
    let closed = false

    const socket = streamSearch(
      '凡人修仙传',
      ['https://src1.com'],
      evt => events.push(evt),
      err => { reportedError = err },
      () => { closed = true }
    )

    assert.ok(socket)

    // Trigger open event
    listeners['open']?.forEach(cb => cb())
    // Wait microtask
    await new Promise(resolve => queueMicrotask(resolve))
    assert.equal(sentMessages.length, 1)
    assert.deepEqual(JSON.parse(sentMessages[0]), { keyword: '凡人修仙传', sourceIds: ['https://src1.com'] })

    // Trigger valid message event
    listeners['message']?.forEach(cb => cb({ data: JSON.stringify({ type: 'results', results: [{ name: '凡人' }] }) }))
    assert.equal(events.length, 1)
    assert.equal(events[0].type, 'results')

    // Trigger invalid json message -> onError
    listeners['message']?.forEach(cb => cb({ data: 'not-valid-json{{{' }))
    assert.equal(reportedError, '搜索响应格式无效')

    // Close event
    socket.close()
    assert.equal(socketClosed, true)
  } finally {
    globalThis.WebSocket = originalWebSocket
    globalThis.location = originalLocation
    setCsrfToken(null)
  }
})
