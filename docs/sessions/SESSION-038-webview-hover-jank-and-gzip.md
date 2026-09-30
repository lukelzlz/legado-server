---
id: SESSION-038
title: 内置浏览器 hover 卡顿（8 FPS）与 gzip 资源乱码的定位与修复
date: 2026-09-30
type: fix
related:
  - docs/sessions/SESSION-036-source-settings-result-return.md
  - docs/sessions/SESSION-027-cover-gzip-and-data-quality.md
---

# SESSION-038：内置浏览器 hover 卡顿 + gzip 资源乱码

## 0. 用户报告

> 「优化 webview 性能，现在感觉很卡」→ 追问后澄清：「**鼠标放到一些按键上，按键不是有动画吗，就很卡**」

关键信息：不是网络慢，而是 **hover 动画掉帧**。这个澄清把排查方向从「网络/缓存」直接扭到了「合成与重绘」。

---

## 1. 第一个真凶：两层 `backdrop-filter` 包着 iframe（8 FPS）

内置浏览器不是独立窗口，而是主页面里的一个 **iframe**（`SourceWebViewModal`），
而 `SourceLoginModal` 把它渲染在**自己那层 `.modal-backdrop` 内部**，于是 iframe 的祖先链是：

```
[1] .source-webview-dialog   animation=scaleUp
[2] .modal-backdrop          backdrop-filter=blur(4px) | animation=fadeIn   ← 内层
[3] .source-login-dialog     animation=scaleUp
[4] .modal-backdrop          backdrop-filter=blur(4px) | animation=fadeIn   ← 外层
```

实测（真实 Edge，hover 上游页面按钮期间测 iframe 内 `requestAnimationFrame` 帧率）：

| 场景 | 帧率 |
| :--- | ---: |
| 普通弹窗（**无 iframe**，带 blur） | **61 FPS** |
| 普通弹窗（无 iframe，去掉 blur） | 60 FPS |
| webview：两层 blur（原状） | **8 FPS** |
| webview：只去内层 blur | 23 FPS |
| webview：两层 blur 都去 | 58 FPS |

**结论：不是 `backdrop-filter` 本身贵，而是「blur 层 + 其内部 iframe 持续重绘」的组合。**
被代理页面每一帧的重绘都会让祖先的 `backdrop-filter` 失效并重新模糊，两层就模糊两遍。
8 FPS 正是用户说的「很卡」。

### 试过但**无效**的替代方案（都实测过，避免后人重复试）

| 方案 | 帧率 |
| :--- | ---: |
| 把 iframe 提升为独立合成层（`transform: translateZ(0)` + `will-change: transform`） | 9 FPS |
| iframe `contain: strict` | 8 FPS |
| 遮罩 `isolation: isolate` | 8 FPS |

⇒ 只能在这两处**关掉 blur**。

### 修法（刻意不扩大打击面）

- 新增 `.modal-backdrop.is-blurless { backdrop-filter: none; background: rgba(0,0,0,.58); }`；
- `SourceWebViewModal` 的遮罩恒加 `is-blurless`（内层）；
- `SourceLoginModal` 的遮罩在 `webViewOpen` 时加 `is-blurless`（外层）；
- **刻意不动全局 `.modal-backdrop`** —— 普通弹窗带 blur 仍是 61 FPS，毛玻璃是免费的好效果，
  只在这一处关掉即可。

**修复后复测：hover 帧率 8 FPS → 60 FPS。**

---

## 2. 顺手挖出的第二个真 bug：gzip 资源被当成乱码交给浏览器

排查网络侧时用 `curl` 直接读代理响应，发现 `jquery.min.js` 经代理后：

```
Content-Type: application/javascript; charset=utf-8
大小: 30462 B
前 4 字节: 1F 8B 08 00     ← GZIP 魔数！
```

上游确实回了 `Content-Encoding: gzip`，而**JDK HttpClient 不会自动解压**，
代理又把该响应头丢掉了 ⇒ 浏览器拿到 gzip 字节却按 JS 解析 ⇒ **脚本根本无法执行**。

根因：`WebViewProxy.openResource()` 只对 `text/html` 与 `text/css` 走 `decodeBody` 解压，
**其余资源（JS / JSON / SVG / 字体）一律直接透传 `response.body()`**；而且 `decodeBody`
是**字符串精确匹配** `contentEncoding`（`"gzip"`/`"deflate"`），`"gzip, br"` 之类就漏过。

这与仓库既有教训 **SESSION-027**（「`BodyHandlers.ofByteArray()` 不解压 gzip ⇒ 封面全是裂图」）
是**同一条规则在另一种资源上重演**：二进制/非文本资源显示不出来时，第一步看**前几个字节的魔数**。

修法（按仓库自己的原则做在**唯一收口**）：

- 新增 `bodyOf(response)`：**所有**读取 body 的地方都经它，内部调 `decompress`；
- `decompress` **魔数优先**（`1F 8B` → gunzip），头里说 gzip/deflate 时再兜底，
  并且**流的构造与读取一起包在 `runCatching`** 里；
- `readBounded(MAX_BODY_BYTES)` 给解压结果加上限，防解压炸弹；
- 删掉 `decodeBody`（解压职责已上移到唯一收口），三处调用点改成 `String(raw, charset)`。

**修复后验证**：`jquery.min.js` 30462 B(gzip 乱码) → **87533 B 合法 JS**（`/*! …`）；
`vue.global.prod.min.js` 同理正常。

### ⚠️ 单测当场抓到我自己的一个错

第一版把 `GZIPInputStream(bytes.inputStream())` 的**构造**写在了 `runCatching` **外面**：

```kotlin
val stream = when { gzipMagic -> GZIPInputStream(...) ... }   // ← 在这里就可能抛 ZipException
return runCatching { stream.use { ... } }.getOrDefault(bytes) // ← 只包住了读取
```

魔数对但内容损坏、或上游谎报 gzip 时，异常会**冒泡出 `decompress`**，
而调用方只捕获 `WebViewException` ⇒ **整个资源请求变成 500**。
这是我自己引入的健壮性回归，**被 `WebViewProxyDecompressTest` 当场抓住**（3 例失败）。
修法：把流的构造与读取**一起**放进 `runCatching`。

> 再次印证仓库既有教训：**「静默丢数据/异常冒泡」这类问题，靠读代码看不出来，必须写测试**。

---

## 3. 第三个发现：上游缓存头一个都没转发（已修，属改善而非 bug）

代理响应此前只有 `Access-Control-Allow-Origin` / `Content-Length` / `Content-Type`
—— **没有任何缓存头**。而实测上游侧：

| 上游 | Cache-Control |
| :--- | :--- |
| cdnjs（字体 / JS / CSS） | `public, max-age=30672000, immutable` |
| 目标站点 langge.uk（HTML / JSON） | **完全没有** |

也就是说浏览器**什么都缓存不了**，连 CDN 明确声明可缓存一年的字体也要每次重下。

修法：`ProxiedPayload` 增加 `cacheHeaders`，在**唯一收口**（`respondProxied`）把上游的
`Cache-Control` / `ETag` / `Last-Modified` / `Expires` 原样转发。
**只做忠实转发，不自己编造 `max-age`** —— 上游说能缓存多久就多久；上游没给就不缓存，
避免把可能随登录态变化的页面缓存成陈旧副本。

> 已知限制：每次打开内置浏览器都会签发**新 token**，子资源 URL 随之改变 ⇒
> 浏览器缓存**跨次打开**失效，只在单次会话内生效。要跨次受益需要服务端响应缓存，
> 那是另一个议题（涉及陈旧与串号风险），本次**未做**。

---

## 4. 验证

- `WebViewProxyDecompressTest`（新）**7** 例：魔数解压、头谎报时不误伤、坏数据回退、二进制透传、
  deflate、无编码直通。
- `WebViewProxyTest` 既有用例全绿。
- 真实 Edge 端到端：hover 帧率 **8 → 60 FPS**；gzip JS 恢复正常体积与内容。
- 全量服务端测试与干净基线**逐条比对失败集合**（见 §5）。

## 5. 零回归

与基线（`main` @ `b98c795`，即含已合并的 #16）比对**失败集合**而非数量：
两边均为 **54** 条且**双向对称差集为 0**，全部是既有的
`java.nio.file.FileSystemException`（Windows 下测试删除被 WAL 占用的 sqlite）噪声。

## 6. 变更文件

- `server/src/main/kotlin/io/legado/server/WebViewProxy.kt`：`bodyOf` / `decompress` / `readBounded` /
  `cacheHeadersOf`；删除 `decodeBody`；`ProxiedPayload` 增加 `cacheHeaders`
- `server/src/main/kotlin/io/legado/server/Routes.kt`：`respondProxied` 透传缓存头
- `server/src/test/kotlin/io/legado/server/WebViewProxyDecompressTest.kt`（新）
- `web/src/styles.css`：`.modal-backdrop.is-blurless`（含完整实测数据注释）
- `web/src/SourceWebViewModal.tsx` / `web/src/SourceLoginModal.tsx`：按条件加 `is-blurless`
