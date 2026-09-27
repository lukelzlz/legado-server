# 封面诊断：在你**自己的浏览器**里跑，直接打印真实原因
#
# 用法：在书架页按 F12 → Console → 整段粘贴回车
# 它会自查 5 件事，并直接告诉你是哪一层坏了。

(async () => {
  const say = (ok, msg, extra) =>
    console.log(`%c${ok ? '✔' : '✘'} ${msg}`, `color:${ok ? '#2a2' : '#d33'};font-weight:bold`, extra ?? '')

  console.log('%c=== 封面诊断开始 ===', 'font-size:14px;font-weight:bold')

  // 1) 当前页面用的是哪个 bundle？（判断有没有被 Service Worker 缓存成旧版）
  const bundle = [...document.querySelectorAll('script[src*="assets/index-"]')]
    .map(s => s.getAttribute('src')).join(', ')
  say(true, '当前页面 bundle', bundle || '(未找到)')

  // 2) Service Worker 与缓存状态
  const regs = await navigator.serviceWorker.getRegistrations()
  const names = await caches.keys()
  say(regs.length > 0, `Service Worker 注册数: ${regs.length}（>0 说明有 SW 在接管）`)
  console.log('  缓存桶:', names)

  // 3) 书架数据里 coverKey 是否存在
  let shelf = []
  try {
    const r = await fetch('/api/bookshelf', { credentials: 'include' })
    shelf = await r.json()
    const withKey = shelf.filter(x => x.coverKey).length
    say(withKey > 0, `书架 ${shelf.length} 本，其中 ${withKey} 本有 coverKey`)
  } catch (e) {
    say(false, '拉取 /api/bookshelf 失败', String(e))
  }

  // 4) DOM 里 <img> 的真实解码结果（这是最关键的判据）
  const imgs = [...document.querySelectorAll('.shelf-card-cover img, .manage-sheet-cover img')]
  const cards = document.querySelectorAll('.shelf-card-cover').length
  const fallbacks = document.querySelectorAll('.shelf-card-cover .cover-fallback').length
  console.log(`%c书架卡片=${cards}  <img>=${imgs.length}  文字占位符=${fallbacks}`,
    'color:#669;font-weight:bold')

  if (imgs.length === 0 && cards > 0) {
    say(false, '有卡片但没有 <img> —— 全部退化成了文字占位符（说明 resolveShelfCover 返回了 null）')
  }

  let broken = 0
  for (const el of imgs) {
    const ok = el.complete && el.naturalWidth > 0 && el.naturalHeight > 0
    if (!ok) broken++
    say(ok, `${el.getAttribute('src')}`, `natural=${el.naturalWidth}x${el.naturalHeight} complete=${el.complete}`)
  }

  // 5) 直接重新拉一次封面字节，看魔数（ff d8 = JPEG，1f 8b = 未解压的 gzip）
  console.log('%c--- 逐个重取封面字节，检查魔数 ---', 'color:#669')
  for (const item of shelf.filter(x => x.coverKey)) {
    try {
      const r = await fetch(`/api/covers/${item.coverKey}`, { credentials: 'include' })
      const buf = new Uint8Array(await r.arrayBuffer())
      const magic = [...buf.slice(0, 4)].map(b => b.toString(16).padStart(2, '0')).join(' ')
      const isJpeg = magic.startsWith('ff d8')
      say(isJpeg, `${item.coverKey.slice(0, 12)}… HTTP ${r.status} bytes=${buf.length} magic=${magic}`,
        isJpeg ? '' : '← 这不是 JPEG！')
    } catch (e) {
      say(false, `${item.coverKey.slice(0, 12)}… 请求异常`, String(e))
    }
  }

  console.log('%c=== 诊断结束 ===', 'font-size:14px;font-weight:bold')
  console.log('如果上面全是 ✔ 但页面仍是空白：按 Ctrl+Shift+R 强制刷新，或 DevTools → Application → Service Workers → Unregister 后再刷新。')
})()
