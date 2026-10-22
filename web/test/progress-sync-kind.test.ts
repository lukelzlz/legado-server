import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/api'

/**
 * 验证前端调用 saveProgress 和 mergeProgress 时对 bookKind 的正确封装与传递，
 * 保证小说（无后缀）、听书（_audio）、漫画（_comic）在前端上报与合并时携带正确的类别信息。
 */
test('api.saveProgress and mergeProgress carry bookKind', async () => {
  const originalFetch = globalThis.fetch
  const capturedRequests: { url: string; method: string; body: any }[] = []

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : null
    capturedRequests.push({ url, method, body })

    if (url.includes('/api/reading-progress')) {
      return new Response(JSON.stringify({ ...body, updatedAt: Date.now() }), { status: 200 })
    }
    if (url.includes('/api/progress-sync/merge')) {
      return new Response(JSON.stringify({ source: 'database', fileFound: true }), { status: 200 })
    }
    return new Response(JSON.stringify({}), { status: 200 })
  }

  try {
    // 1. 小说保存进度
    await api.saveProgress('src-1', 'book-novel', 'ch-1', 10, 0.5, '第十章', undefined, 'novel')
    const novelReq = capturedRequests.find(r => r.url.includes('/api/reading-progress') && r.body?.bookUrl === 'book-novel')
    assert.ok(novelReq, 'should capture novel save request')
    assert.equal(novelReq.body.bookKind, 'novel')

    // 2. 有声书保存进度
    await api.saveProgress('src-1', 'book-audio', 'ch-2', 5, 0.0, '第5集', 60_000, 'audio')
    const audioReq = capturedRequests.find(r => r.url.includes('/api/reading-progress') && r.body?.bookUrl === 'book-audio')
    assert.ok(audioReq, 'should capture audio save request')
    assert.equal(audioReq.body.bookKind, 'audio')
    assert.equal(audioReq.body.positionMs, 60_000)

    // 3. 漫画保存进度
    await api.saveProgress('src-1', 'book-comic', 'ch-3', 2, 0.8, '第2话', undefined, 'comic')
    const comicReq = capturedRequests.find(r => r.url.includes('/api/reading-progress') && r.body?.bookUrl === 'book-comic')
    assert.ok(comicReq, 'should capture comic save request')
    assert.equal(comicReq.body.bookKind, 'comic')

    // 4. 合并进度请求
    await api.mergeProgress({
      sourceId: 'src-1',
      bookUrl: 'book-comic',
      chapters: [{ index: 0, title: '序章', url: 'intro' }],
      bookKind: 'comic',
    })
    const mergeReq = capturedRequests.find(r => r.url.includes('/api/progress-sync/merge'))
    assert.ok(mergeReq, 'should capture merge request')
    assert.equal(mergeReq.body.bookKind, 'comic')
  } finally {
    globalThis.fetch = originalFetch
  }
})
