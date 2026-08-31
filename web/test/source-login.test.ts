import test from 'node:test'
import assert from 'node:assert/strict'
import { api, setCsrfToken } from '../src/api.ts'

test('Source Login API Client - getSourceLoginUi, save, action, check, and delete', async () => {
  const originalFetch = globalThis.fetch
  const capturedRequests: { url: string; method?: string; headers: Headers; body?: any }[] = []

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const headers = new Headers(init?.headers)
    capturedRequests.push({
      url: input.toString(),
      method: init?.method || 'GET',
      headers,
      body: init?.body ? JSON.parse(init.body as string) : undefined,
    })

    const urlStr = input.toString()
    if (urlStr.includes('/login-ui')) {
      return new Response(JSON.stringify({
        sourceId: 'https://test.com',
        sourceName: '测试源',
        hasLogin: true,
        loginUi: [
          { name: '用户名', type: 'text' },
          { name: '密码', type: 'password' },
          { name: '登录', type: 'button', action: 'login(true)' }
        ],
        loginInfo: { 用户名: 'admin' },
        loginHeader: '{"Token":"abc"}',
        sourceVariable: 'v123',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    if (urlStr.includes('/login-action')) {
      return new Response(JSON.stringify({
        success: true,
        toastMessages: ['登录成功'],
        openUrl: 'https://test.com/home',
        copyText: 'token123',
        reRenderUi: true,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    if (urlStr.includes('/login-check')) {
      return new Response(JSON.stringify({
        loggedIn: true,
        message: '登录态有效',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }

    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    setCsrfToken('csrf-token-login')

    // 1. getSourceLoginUi
    const ui = await api.getSourceLoginUi('https://test.com')
    assert.equal(ui.sourceName, '测试源')
    assert.equal(ui.loginUi.length, 3)
    assert.equal(ui.loginInfo['用户名'], 'admin')
    assert.equal(capturedRequests[0].method, 'GET')
    assert.ok(capturedRequests[0].url.includes('/api/sources/https%3A%2F%2Ftest.com/login-ui'))

    // 2. saveSourceLoginInfo
    const saveRes = await api.saveSourceLoginInfo('https://test.com', { 用户名: 'newuser', 密码: 'pass' })
    assert.equal(saveRes.ok, true)
    assert.equal(capturedRequests[1].method, 'POST')
    assert.equal(capturedRequests[1].headers.get('X-CSRF-Token'), 'csrf-token-login')
    assert.deepEqual(capturedRequests[1].body, { loginInfo: { 用户名: 'newuser', 密码: 'pass' } })

    // 3. executeSourceLoginAction
    const actionRes = await api.executeSourceLoginAction('https://test.com', 'login(true)', { 用户名: 'newuser' }, false)
    assert.equal(actionRes.success, true)
    assert.deepEqual(actionRes.toastMessages, ['登录成功'])
    assert.equal(actionRes.openUrl, 'https://test.com/home')
    assert.equal(actionRes.copyText, 'token123')
    assert.equal(actionRes.reRenderUi, true)
    assert.equal(capturedRequests[2].method, 'POST')

    // 4. checkSourceLogin
    const checkRes = await api.checkSourceLogin('https://test.com')
    assert.equal(checkRes.loggedIn, true)
    assert.equal(checkRes.message, '登录态有效')

    // 5. clearSourceLoginHeader
    const delHeader = await api.clearSourceLoginHeader('https://test.com')
    assert.equal(delHeader.ok, true)
    assert.equal(capturedRequests[4].method, 'DELETE')

    // 6. clearSourceLoginInfo
    const delInfo = await api.clearSourceLoginInfo('https://test.com')
    assert.equal(delInfo.ok, true)
    assert.equal(capturedRequests[5].method, 'DELETE')
  } finally {
    globalThis.fetch = originalFetch
  }
})
