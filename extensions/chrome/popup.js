document.addEventListener('DOMContentLoaded', async () => {
  const currentHostEl = document.getElementById('current-host')
  const serverUrlInput = document.getElementById('server-url')
  const syncBtn = document.getElementById('sync-btn')
  const statusEl = document.getElementById('status')

  const saved = await chrome.storage.local.get(['legadoServerUrl'])
  if (saved.legadoServerUrl) {
    serverUrlInput.value = saved.legadoServerUrl
  }

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  if (!tab || !tab.url) {
    currentHostEl.textContent = '无法获取当前网页'
    syncBtn.disabled = true
    return
  }

  let urlObj
  try {
    urlObj = new URL(tab.url)
    currentHostEl.textContent = urlObj.origin
  } catch (e) {
    currentHostEl.textContent = tab.url
    syncBtn.disabled = true
    return
  }

  syncBtn.addEventListener('click', async () => {
    syncBtn.disabled = true
    statusEl.className = ''
    statusEl.style.display = 'none'

    try {
      const serverUrl = serverUrlInput.value.trim().replace(/\/+$/, '')
      await chrome.storage.local.set({ legadoServerUrl: serverUrl })

      const cookies = await chrome.cookies.getAll({ url: tab.url })
      if (!cookies || cookies.length === 0) {
        statusEl.className = 'error'
        statusEl.textContent = '⚠️ 当前站点未检测到任何 Cookie！'
        syncBtn.disabled = false
        return
      }

      const cookieStr = cookies.map(c => `${c.name}=${c.value}`).join('; ')
      const targetSourceId = urlObj.origin

      const resp = await fetch(`${serverUrl}/api/sources/${encodeURIComponent(targetSourceId)}/login-cookie`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cookie: cookieStr, url: tab.url }),
      })

      const data = await resp.json()
      if (resp.ok && data.ok) {
        statusEl.className = 'success'
        statusEl.textContent = `✅ 成功同步 ${cookies.length} 个 Cookie (含 HttpOnly) 到阅读服务器！`
      } else {
        statusEl.className = 'error'
        statusEl.textContent = `❌ 同步失败: ${data.message || resp.statusText}`
      }
    } catch (err) {
      statusEl.className = 'error'
      statusEl.textContent = `❌ 网络错误: ${err.message}`
    } finally {
      syncBtn.disabled = false
    }
  })
})
