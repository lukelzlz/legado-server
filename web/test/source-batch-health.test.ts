import test from 'node:test'
import assert from 'node:assert/strict'
import { api, setCsrfToken, BatchSourceRequest, BatchSourceResponse, SourceHealthCheckResponse } from '../src/api'

test('Source Batch Management & Health Check API Test Suite', async (t) => {
  setCsrfToken('mock-csrf-token')

  await t.test('api.batchSources should format enable action request correctly', async () => {
    const originalFetch = globalThis.fetch
    let capturedUrl = ''
    let capturedMethod = ''
    let capturedBody = ''
    let capturedCsrf = ''

    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = input.toString()
      capturedMethod = init?.method ?? 'GET'
      capturedBody = init?.body as string
      capturedCsrf = (init?.headers as Headers).get('X-CSRF-Token') ?? ''

      const res: BatchSourceResponse = {
        ok: true,
        affected: 3,
        action: 'enable',
        message: '已成功启用 3 个书源',
      }
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }) as unknown as typeof fetch

    try {
      const resp = await api.batchSources('enable', ['https://s1.com', 'https://s2.com', 'https://s3.com'])
      assert.strictEqual(capturedUrl, '/api/sources/batch')
      assert.strictEqual(capturedMethod, 'POST')
      assert.strictEqual(capturedCsrf, 'mock-csrf-token')
      const parsedBody = JSON.parse(capturedBody)
      assert.strictEqual(parsedBody.action, 'enable')
      assert.deepStrictEqual(parsedBody.ids, ['https://s1.com', 'https://s2.com', 'https://s3.com'])
      assert.strictEqual(resp.ok, true)
      assert.strictEqual(resp.affected, 3)
      assert.strictEqual(resp.action, 'enable')
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  await t.test('api.batchSources should format set_group action request correctly', async () => {
    const originalFetch = globalThis.fetch
    let capturedBody = ''

    globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
      capturedBody = init?.body as string
      const res: BatchSourceResponse = {
        ok: true,
        affected: 2,
        action: 'set_group',
        message: '已将 2 个书源移至分组“精品推荐”',
      }
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }) as unknown as typeof fetch

    try {
      const resp = await api.batchSources('set_group', ['https://s1.com', 'https://s2.com'], '精品推荐')
      const parsedBody = JSON.parse(capturedBody)
      assert.strictEqual(parsedBody.action, 'set_group')
      assert.strictEqual(parsedBody.group, '精品推荐')
      assert.strictEqual(resp.ok, true)
      assert.strictEqual(resp.affected, 2)
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  await t.test('api.healthCheckSources should send request and parse multi-state health items', async () => {
    const originalFetch = globalThis.fetch
    let capturedUrl = ''
    let capturedBody = ''

    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = input.toString()
      capturedBody = init?.body as string

      const res: SourceHealthCheckResponse = {
        total: 3,
        successCount: 1,
        slowCount: 1,
        failedCount: 1,
        durationMs: 420,
        results: [
          {
            id: 'https://valid.com',
            name: '有效源',
            ok: true,
            latencyMs: 85,
            statusCode: 200,
            statusCategory: 'valid',
          },
          {
            id: 'https://slow.com',
            name: '迟缓源',
            ok: true,
            latencyMs: 2890,
            statusCode: 200,
            statusCategory: 'slow',
          },
          {
            id: 'https://dead.com',
            name: '失效源',
            ok: false,
            latencyMs: 5001,
            statusCode: 0,
            statusCategory: 'failed',
            error: 'Connect timed out (5000ms)',
          },
        ],
      }
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }) as unknown as typeof fetch

    try {
      const resp = await api.healthCheckSources(['https://valid.com', 'https://slow.com', 'https://dead.com'], 5000)
      assert.strictEqual(capturedUrl, '/api/sources/health-check')
      const parsedBody = JSON.parse(capturedBody)
      assert.deepStrictEqual(parsedBody.ids, ['https://valid.com', 'https://slow.com', 'https://dead.com'])
      assert.strictEqual(parsedBody.timeoutMs, 5000)
      assert.strictEqual(resp.total, 3)
      assert.strictEqual(resp.successCount, 1)
      assert.strictEqual(resp.slowCount, 1)
      assert.strictEqual(resp.failedCount, 1)
      assert.strictEqual(resp.results[0].statusCategory, 'valid')
      assert.strictEqual(resp.results[1].statusCategory, 'slow')
      assert.strictEqual(resp.results[2].statusCategory, 'failed')
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  await t.test('api.exportSources should serialize ids properly', async () => {
    const originalFetch = globalThis.fetch
    let capturedUrl = ''

    globalThis.fetch = (async (input: RequestInfo | URL) => {
      capturedUrl = input.toString()
      return new Response(JSON.stringify(['{"bookSourceUrl":"https://s1.com"}']), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }) as unknown as typeof fetch

    try {
      const resp = await api.exportSources(['https://s1.com', 'https://s2.com'])
      assert.strictEqual(capturedUrl, '/api/sources/export?id=https%3A%2F%2Fs1.com&id=https%3A%2F%2Fs2.com')
      assert.strictEqual(resp.length, 1)
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
