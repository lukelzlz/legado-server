# 工作记忆归档 SESSION-009: Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API

> **对应需求提案**：[`PROPOSAL-010`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-010-kindle-simple-web-ui.md)  
> **架构决策**：[`ADR-010`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-010-kindle-simple-web-and-legacy-api-compatibility.md)  
> **验收手册**：[`ACCEPT-010`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-010-kindle-simple-web.md)  
> **执行日期**：2026-09-19

---

## 1. 核心任务与架构重构

1. **提取并内嵌 Simple-Web 极简版资源**：
   - 从微信接收的文件 `reader-src.zip` 中解压出 `simple-web` 的完整静态资源（HTML、CSS、JS、Templates 等），安全放入 `server/src/main/resources/simple/`。
2. **架构路线裁定：前端适配器方案（改 UI 适配程序）**：
   - 彻底删除服务端原本拟增加的 `LegacyRoutes.kt`，保持 Ktor 后端 100% 纯粹，完全基于标准 RESTful `/api/*`。
   - 改造 Simple-Web 前端 JS 库直接对接标准 RESTful API：
     - `common-*.js`：改造 `_$.ajax` 底层，注入 `X-CSRF-Token`、`withCredentials = true` 及 401 拦截；改造 `BookApi` 对接 `/api/bookshelf`、`doLogin` 对接 `/api/auth/login` 等。
     - `indexPage-*.js`：改造 `refreshChapterList`（`POST /api/books/chapters`）与 `changeSource`（`POST /api/search`）。
     - `readerPage-*.js`：改造 `TocApi`（`POST /api/books/chapters`）、`Reader.prototype.getContent`（`POST /api/books/content` 与 `PUT /api/reading-progress`）及换源弹窗。
     - `searchPage-*.js`：改造 `loadBookSourceList`（`GET /api/sources`）、`searchBookWithConfig`（`POST /api/search`）与 `saveBook`（`POST /api/bookshelf`）。
3. **静态路由与重定向**：
   - `/simple/` 与 `/simple/*` 映射至 `server/src/main/resources/simple/`。
   - `/kindle` 提供 302 重定向至 `/simple/`。
4. **双向切换能力**：
   - 现代版 React Web 端在 `AppHeader.tsx` 右上角菜单中新增“Kindle / 墨水屏版”链接。
   - `simple-web` 底部菜单新增“现代Web版”按钮与 `window.gotoModernWeb()`。
5. **测试与质量保障**：
   - `ServerConfigAndStaticTest.kt` 覆盖 `/kindle` 302 重定向与 `/simple/index.html` 静态资源响应。
   - 验证前后端完整自动化测试。

---

## 2. 踩坑与技术细节 (Tribal Knowledge)

1. **ZIP 文件非 UTF-8 字体文件名编码问题**：
   - `reader-src.zip` 中包含非 UTF-8 编码的特殊字体文件名，直接使用原生 unzip 可能会报错中断。
   - 解压时排除异常文件，并妥善保留 `Myuppy.ttf` 字体文件。
2. **Ktor 嵌入式测试中的 302 重定向跟随**：
   - 在 Ktor `testApplication` 中，默认 `client` 会自动跟随 HTTP 302 重定向到目标 URL（导致返回 200 OK 而非 302 Found）。
   - 在测试重定向路由时，需显式使用 `createClient { followRedirects = false }` 以精确断言 `HttpStatusCode.Found`。
3. **Simple-Web REST 数据模型映射**：
   - `/api/bookshelf` 返回 `BookshelfItem`（包含 `sourceId`, `bookUrl`, `name`, `author`, `tocUrl`, `coverKey`, `chapterIndex`, `scrollPosition`, `lastReadAt`, `totalChapters`）。
   - Simple-Web 的 `BookApi` 自动将这些字段映射为 UI 视图渲染所需的 `durChapterIndex`, `durChapterTitle`, `origin` 等。

---

## 3. 验证结果

- 前端类型检查与单元测试：`npm --prefix web run check && npx tsx web/test/run-all.ts`（122/122 passed）。
- 后端单元测试：`./gradlew :server:test`（全部通过，包含静态资源与重定向测试）。
