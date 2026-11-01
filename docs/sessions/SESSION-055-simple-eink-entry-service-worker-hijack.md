# SESSION-055 「URL 是 simple、界面却是电脑版」：Service Worker 的 navigateFallback 劫持

- 日期：2026-11-01
- 分支：fix/simple-eink-entry（基于 main，不合并）
- 关联：`web/src/simpleEntry.ts`、`web/vite.config.ts`、`web/test/e2e-simple-eink-browser.ts`

## 用户报障

> 「优化url，`http://127.0.0.1:8080/simple/#shelf` 和 `http://127.0.0.1:8080/#shelf` 都是书架，
> 有 simple 的时候电脑为啥显示的还是电脑的界面，要求 simple 就是打开墨水屏版」

## 复现（真浏览器，4 步就锁定了）

写了个 puppeteer 脚本按「干净上下文 → 打开桌面版 → 刷新让 SW 接管 → 再打开 /simple/」走一遍：

| 步骤 | 路径 | `#pageBox`（墨水屏壳） | `.app-shell`（桌面壳） | `navigator.serviceWorker.controller` |
| --- | --- | --- | --- | --- |
| A 全新上下文 | `/simple/` | ✅ | ❌ | false |
| B 打开桌面版 | `/` | ❌ | ✅ | false |
| C 刷新一次 | `/` | ❌ | ✅ | **true** ← SW 接管 |
| D 再开 simple | `/simple/` | ❌ | **✅ 劫持** | true |
| E 带 hash | `/simple/#shelf` | ❌ | **✅ 劫持** | true |

⇒ **不是服务端的问题**（`curl /simple/` 一直是正确的静态页），而是 **Service Worker 把导航请求截胡了**。

## 根因

桌面版用 `vite-plugin-pwa` 的 Workbox，`scope: '/'` + `navigateFallback: '/index.html'`，
而 `navigateFallbackDenylist` 只排除了 `/api/`：

```ts
navigateFallback: '/index.html',
navigateFallbackDenylist: [/^\/api\//],   // ← /simple/ 没被排除
```

Workbox 的 `NavigationRoute` 对**所有** `mode === 'navigate'` 的请求生效（只排除 denylist），
所以 `/simple/`、`/simple/index.html`、`/kindle` 全被回退成缓存里的桌面 `index.html`。
**只要这台浏览器访问过一次桌面版（SW 装好并接管页面），simple 地址就再也拿不到服务端的静态页。**

## 修复（两层）

1. **构建期**（根治）：`navigateFallbackDenylist` 增加 `/^\/simple(\/|$)/` 与 `/^\/kindle(\/|$)/`。
   新装/更新后的 SW 不再兜底这两类导航。
2. **运行期自救**（`web/src/simpleEntry.ts`，在 `createRoot` 之前调用）：桌面版一启动就检查
   `location.pathname`，若发现「自己在 `/simple/` 或 `/kindle` 路径上」⇒ 说明被 SW 塞进来了，
   于是 `unregister()` 所有 SW + 清空 Cache Storage + `location.replace()` 原地址（含 hash）重载一次。
   用 `sessionStorage` 做一次性闸门，避免刷新循环。

### 必须承认的边界

旧 SW 的 precache 里装的是**旧 bundle**，所以"自救守卫"只对已经拿到新 bundle 的浏览器有效；
真正让老客户端恢复的动作是 SW 被替换（正常更新提示/强制刷新）。
因此本次交付同时包含 **denylist**（永久修复）+ **守卫**（防未来回归、覆盖 SW 存在但不兜底的场景），
并在验收说明里写清"第一次可能需要让新 SW 生效"。

## 验证

- `web/test/simple-entry.test.ts`：7/7（路径判定 / 非 simple 不动手 / 保留 hash / 只救一次 / kindle /
  vite denylist 契约 / main.tsx 调用顺序）。
- `web/test/e2e-simple-eink-browser.ts`（真 Edge）：4/4 通过，关键输出：

```
1/4 fresh /simple/ -> simple=true title=书架(0)
2/4 desktop page is now controlled by the service worker
3/4 /simple/#shelf under SW control -> simple=true title=书架(0)     ← 修复前这里是 desktop=true
4/4 /kindle/ -> /simple/ simple=true
ALL SIMPLE-EINK ENTRY CHECKS PASSED
```

## 教训（已写进 AGENTS.md）

**SW 的 `navigateFallback` 是"全站导航兜底"，不是"SPA 路由兜底"**：同一 origin 下的任何
非 denylist 导航（独立静态页、`/kindle` 这类别名、甚至别的前端）都会被它换成 SPA 的 index.html。
加独立页面/子应用时，必须同步把路径加进 denylist，并用「先让 SW 接管、再访问该路径」的 e2e 锁死。
