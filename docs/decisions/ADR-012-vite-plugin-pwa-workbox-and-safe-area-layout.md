---
id: ADR-012
title: 采用 vite-plugin-pwa 构筑双层用户可控离线缓存体系与 Safe-Area 沉浸式安全区适配
status: accepted
date: 2026-09-15
---

# ADR-012: 采用 vite-plugin-pwa 构筑双层用户可控离线缓存体系与 Safe-Area 沉浸式安全区适配

## 1. 决策背景 (Context)

为实现工业级 PWA（Progressive Web App）能力与真·脱机离线阅读体验，原有“粗暴全本缓存”已无法满足用户按需阅读、节省流量与设备存储的诉求。在经过深度 `/grill-me` 对齐后，我们需要解决：
1. **用户自主可控的离线分段缓存体系**：支持后 N 章、自定义区间、全本缓存与追更预载，并在目录中显示缓存状态。
2. **服务端与客户端双层缓存协同**：服务端 SQLite 负责高并发批量抓取与持久化；客户端 IndexedDB / CacheStorage 作为本地离线池，使手机在完全断网且无法连通服务端时依然可以秒开已缓存正文。
3. **离线进度暂存与静默自动回写**：断网阅读时在本地暂存进度，网络恢复时静默向服务端 Flush，防进度覆盖。
4. **离线 TTS 弹窗降级**：断网时点击朗读弹窗确认并平滑降级至系统本地 Web Speech 语音。
5. **PWA 全屏与异形屏安全区适配**：根治 iOS / Android PWA Standalone 全屏模式下设置抽屉与顶栏超出屏幕上界与刘海遮挡的 CSS 布局问题，全局禁用 rubber-banding 弹性下拉。

---

## 2. 裁定方案 (Decision)

### 2.1 PWA 与 Service Worker 构建工具链
- 采用 **`vite-plugin-pwa`**（基于 Google Workbox）作为 PWA 核心插件集成到 Vite 构建流水线中。
- 配置 `registerType: 'prompt'` 模式，精准捕获更新事件并提供用户受控的更新体验（轻量 Toast“发现新版本，点击即刻更新 [立即更新]”）。
- 自动生成 `manifest.webmanifest`，包含 `name: "阅读"`, `short_name: "阅读"`, `display: "standalone"`, `theme_color`, `background_color`, `shortcuts` 与标准分辨率图标套件。
- 初次访问（非 Standalone 模式）在底部弹出轻量气泡卡片“将阅读添加到主屏幕，享受全屏沉浸体验”，并在设置抽屉常驻安装入口。

### 2.2 双层缓存架构与 API 协议升级

```
[ 用户选择缓存范围 (后50章/后100章/全本/自定义) ]
        │
        ▼
[ 后端 API: POST /api/bookshelf/cache ] ── (携带 startIndex, endIndex, count)
        │
        ▼
[ 服务端 BookCacheService ] ── (按需切片 4 并发下载 -> 净化清洗 -> 入库 SQLite book_cache)
        │
        ▼
[ 后端 API: GET /api/bookshelf/cached-chapters ] ── (返回已离线章节 URL 清单)
        │
        ▼
[ 前端 TOC 目录标注绿色圆点 & 客户端 IndexedDB 离线同步池 (4并发滑动窗口) ]
```

1. **服务端接口升级**：
   - `POST /api/bookshelf/cache`：请求体扩展 `BookCacheRangeRequest(sourceId, bookUrl, startIndex, endIndex, count)`，支持按范围精准入队。
   - `GET /api/bookshelf/cached-chapters?sourceId=...&bookUrl=...`：返回当前书籍所有已缓存章节的 URL 列表。
   - `DELETE /api/bookshelf/cache`：支持查询参数 `clearData=true`，可选彻底清空该书的服务端正文缓存。
   - `StaticWeb.kt`：对 `/sw.js`, `/registerSW.js`, `/manifest.webmanifest` 严格下发 `no-cache, no-store, must-revalidate`，避免僵尸缓存。
2. **客户端本地离线池 (IndexedDB)**：
   - 前端创建 `IndexedDB` 存储库 `legado_offline_db`（对象仓库：`chapters`，键为 `${sourceId}::${bookUrl}::${chapterUrl}`）。
   - 阅读器在获取到正文时自动写入 IndexedDB；同时在离线下载完成时提供“同步离线到本设备”能力。
   - 在 API 请求层（`api.getBookContent`）建立优先拦截器：当 `navigator.onLine === false` 或网络请求失败时，直接回退读取本地 IndexedDB。
   - **离线进度暂存与 Flush**：离线阅读时将最新进度记入 `localStorage` 队列；监听 `window.addEventListener('online')` 与 API 请求恢复时，自动将本地最新进度 Flush 给服务端 `/api/bookshelf/progress`。
   - **离线 TTS 弹窗降级**：离线状态下检测到云端 TTS 不可用时，弹出对话框“当前处于离线状态，是否使用系统本地语音朗读？”，用户确认后调用 `window.speechSynthesis`。
   - **离线书架快照**：本地持久化最近一次书架 JSON，离线断网打开时直接展示本地快照并标注可离线阅读书籍。
   - **存储配额与空间管理**：提供管理面板查看各书占用空间与一键清理，超限时优雅弹窗熔断，并在设置中支持配置是否开启自动 LRU 淘汰。
3. **缓存策略分层矩阵 (Workbox)**：
   - **App Shell 静态资源**：`Precache + CacheFirst`
   - **封面与书架元数据**：`StaleWhileRevalidate`
   - **TTS 音频与 SSE 流**：`NetworkOnly`（绝不拦截）

### 2.3 全屏与安全区布局适配 (Safe Area Adaptation)
1. **Viewport 与手势配置**：
   - 在 `index.html` 中声明 `<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />` 与 iOS 状态栏配置。
   - 全局 CSS 设置 `overscroll-behavior: none` 与 `touch-action: pan-x pan-y`，杜绝 iOS 橡皮筋弹性下拉导致的抖动。
2. **CSS 安全区变量与抽屉模型重构**：
   - 全局引入 `--safe-top: env(safe-area-inset-top, 0px)` 与 `--safe-bottom: env(safe-area-inset-bottom, 0px)`。
   - `.reader-drawer` 统一采用 `height: 100dvh` / `top: 0; bottom: 0;` 并增加顶部安全边距 padding，将 `.drawer-header` 限制在状态栏/刘海屏下方。
   - 保证 `.drawer-scroll-content` 垂直滚动区域不发生裁剪，底部与 `TtsPlayerBar` 留足 `--safe-bottom` 间距。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

### 备选方案 A：仅由 Service Worker CacheStorage 拦截 HTTP GET 响应
- *否决理由*：CacheStorage 适合 URL 固定的 GET 请求，但对于带有复杂 POST 换源、阅读进度关联及批量存储管理（查看 MB 大小、章节粒度删除）极难维护；采用 IndexedDB 结构化存储正文数据，既支持精准的存储空间计算，又能与 UI 的 TOC 缓存小绿点无缝绑定。

### 备选方案 B：仅存服务端，手机不保留客户端离线数据
- *否决理由*：在地铁断网或手机开启飞行模式时，无法连通局域网/公网服务端，离线功能直接形同虚设。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益 (Pros)
1. **完全掌控**：用户可根据剩余流量与存储空间，自由选择离线 50 章、100 章或全本。
2. **真·断网脱机可用**：无论手机处于何种离线环境，只要点击过离线同步，即可随时翻页阅读，且阅读进度在联网后自动无缝同步回服务端。
3. **目录一目了然**：绿点清晰呈现离线状态，避免用户盲目猜测。
4. **视觉与交互零瑕疵**：PWA 全屏模式下告别抽屉越界、刘海遮挡与橡皮筋下拉抖动。
