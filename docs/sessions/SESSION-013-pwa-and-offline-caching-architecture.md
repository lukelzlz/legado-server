# 工作记忆归档：完整 PWA 能力、用户自主正文分段离线缓存与沉浸式全屏抽屉适配 (SESSION-013)

> **关联提案**：[`docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md`](file:///root/legado-server/docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md)  
> **关联架构决策**：[`docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md`](file:///root/legado-server/docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md)  
> **关联验收手册**：[`docs/acceptance/ACCEPT-012-pwa-and-offline-cache.md`](file:///root/legado-server/docs/acceptance/ACCEPT-012-pwa-and-offline-cache.md)

---

## 1. 核心需求与背景
用户提出重构离线缓存并赋予 Legado Web 完整的 PWA 体验：
1. **PWA 体验闭环**：Web App Manifest（图标、shortcuts、standalone 模式）、Service Worker 离线应用壳缓存、版本更新自动感知与一键重载、安装到桌面/主屏幕引导；全屏沉浸式适配 iOS/Android 刘海屏安全区（`safe-area-inset-*`）与禁用橡皮筋下拉跳动（`overscroll-behavior: none`）。
2. **正文离线缓存用户自主化**：
   - 彻底打破以往只能全本盲目后台下载的单一模式；
   - 支持按「后 50 章」、「后 100 章」、「全本离线」、「自定义范围（如 10 ~ 80 章）」灵活选择分段；
   - 采用 4 并发滑动窗口分片并发下载，支持实时进度条展示与随时取消；
   - 客户端 IndexedDB 脱机离线持久化存储；
   - 目录列表中所有已离线章节实时标绿点徽标（`●`）；
   - 断网离线翻页进度进入本地暂存队列，网络恢复（`online` 事件）时静默 Flush 同步到服务端，时间戳最新胜出；
   - 本地离线存储配额管理面板，直观查看各书籍占用体积、一键单书清理与 LRU 超限自动淘汰机制。

---

## 2. 关键架构设计与踩坑解决

### 1. IndexedDB 异步游标与批量清除
- **问题**：客户端直接存储几十本甚至上百本长篇小说的离线正文，单键存储（`sourceId_bookUrl_chapterUrl`）在需要按书籍清理或列出该书全部章节时，若全量扫描库会消耗不必要的 CPU。
- **解决**：在 IndexedDB `chapters` 对象仓库上建立复合索引 `by_book`（`['sourceId', 'bookUrl']`），支持通过 `IDBKeyRange.only([sourceId, bookUrl])` 精准范围检索与 `openKeyCursor` 极速单书清除。

### 2. PWA Service Worker 与实时音频/SSE 通道的隔离
- **问题**：Workbox 若粗暴缓存所有请求，会导致 `/api/tts/stream`、`/api/search/stream` 等长连接实时流被 Service Worker 拦截甚至损坏流式传输。
- **解决**：在 `vite.config.ts` 的 `VitePWA` 配置中将 `/api/tts/stream`、`/api/search/stream`、`/api/bookshelf/cache` 等明确排除，仅对静态 JS/CSS/SVG/字体及书籍元数据做 NetworkFirst / StaleWhileRevalidate 缓存。

### 3. 断网离线听书平滑降级
- **问题**：用户脱机阅读时若点击 TTS 听书，远程 Edge-TTS 无法连接会报错。
- **解决**：阅读器在点击朗读瞬间通过 `navigator.onLine` 守卫感知断网，若当前配置为网络引擎则弹出友好降级对话框，一键切换至浏览器内置的本地 Web Speech 语音引擎，保障离线沉浸听书体验。

---

## 3. 验证与通过状态
- **TypeScript 编译检查**：`npm --prefix web run check` -> PASS (0 错误)
- **前端全部自动化测试**：`npx tsx web/test/run-all.ts` -> PASS (127/127 测试通过)
- **服务端本地 JVM 单元测试**：`./gradlew :server:test` -> PASS (BUILD SUCCESSFUL)
- **完成度状态**：`[Tested 单测/检查通过]`
