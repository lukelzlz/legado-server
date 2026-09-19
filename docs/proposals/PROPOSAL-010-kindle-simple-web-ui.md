---
id: PROPOSAL-010
title: 支持 Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API
status: implemented # draft | review | accepted | implemented | rejected
author: Agent & User
date: 2026-09-19
---

# PROPOSAL-010: 支持 Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API

## 1. 业务背景与问题痛点

Legado 服务端（`io.legado.server`）目前具备现代化的 React 19 + TypeScript Web 客户端，但在 Kindle、墨水屏电子书阅读器（E-ink 设备）或低性能旧浏览器上：
1. **现代前端资源过重与兼容性局限**：现代 React 19 单页应用依赖较新的 ECMAScript 特性与 DOM 布局计算，且界面包含复杂动画与流式渲染，在 Kindle 原生实验性浏览器（Kindle Experimental Browser）或低端墨水屏设备上加载缓慢、甚至白屏或无法流畅翻页。
2. **缺乏针对墨水屏残影与实体/虚拟按键的专用优化**：E-ink 墨水屏需要极简的高对比度黑白排版、硬分页（分页而非无级平滑滚动）、残影清除（Kindle Clean Screen 闪烁刷新机制）以及大触控区域的分页按键。
3. **架构整洁与无头服务端规范**：服务端已有统一的 RESTful API（`/api/*`），无需在服务端为废弃的历史项目维护冗余的旧版 API 兼容层。通过直接改造 Simple-Web 前端适配标准 API，实现架构与体验的最佳平衡。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### 目标 (Goals)
1. **内置极简 Kindle / 墨水屏版 Web UI (`simple-web`)**：
   - 将完整轻量前端资源（`index.html`, `reader.html`, `search.html`, `rss.html`, 样式、模板与脚本）打包至服务端 JAR 静态资源目录（`/simple/`），零外部网络依赖，离线可用。
2. **改造 Simple-Web 前端直接对接标准 REST API (`/api/*`)**：
   - 适配标准 API：`/api/bookshelf`、`/api/books/chapters`、`/api/books/content`、`/api/reading-progress`、`/api/search`、`/api/sources`、`/api/auth/*`。
   - 自动支持 CSRF Token 注入与 Session 鉴权。
3. **现代化 Web UI 与 Kindle 模式双向互通**：
   - 在现代化 React Web 客户端（设置/导航栏）中提供一键“Kindle / 墨水屏版”快捷入口；
   - 在 Kindle 版菜单中提供返回现代 Web 版入口。
4. **Kindle 实验性浏览器特化适配**：
   - 适配 Kindle 浏览器 UA 自动识别与视口、分页计算、定时白屏刷新消除残影机制。

### 非目标 (Non-Goals)
1. **不在服务端新增冗余的旧版 API 兼容路由**：保持 Ktor 后端完全统一于 `/api/*`。
2. **不修改核心持久化与数据库架构**：复用现有的 SQLite `book_shelf`、`book_source`、`BookCacheService` 与 `RuleRunner`。

---

## 3. 核心用户故事 (User Stories)

- **Story 1（Kindle 设备直接访问与阅读）**：
  作为一名 Kindle 墨水屏用户，使用 Kindle 自带体验版浏览器访问 `http://<server-ip>:8080/kindle`（或 `/simple/`），页面瞬间秒开加载出黑白高对比度书架，点击书籍进入纯净阅读页，点击左右两侧即可流畅硬翻页并自动同步最新进度至 SQLite。
- **Story 2（低功耗与残影消除）**：
  在 Kindle 阅读过程中，翻页时阅读器触发轻量闪屏（`cleanScreen`）消除电子墨水残影，字体与行高设置可在弹出菜单中调整并即时持久化到本地。
- **Story 3（书源搜索与加书）**：
  在 Kindle 版搜索页（`search.html`）输入关键词，前端调用 `/api/search` 并发抓取，用户可直接在 Kindle 上一键添加书籍至书架。
- **Story 4（双 UI 无缝切换与现代端联动）**：
  用户在电脑或手机上使用现代 React 客户端管理书架与缓存，点击导航菜单“Kindle / 墨水屏版”，URL 平滑切换到 `/simple/`；在 Kindle 端阅读的进度，电脑端刷新后无缝接续。

---

## 5. 验收基准 (Acceptance Criteria)

- [x] Simple-Web 前端完整适配 `/api/*` RESTful 规范，支持 CSRF 与 Session 鉴权。
- [x] 访问 `http://127.0.0.1:8080/kindle` 自动 302 重定向跳转至 `/simple/`。
- [x] 访问 `http://127.0.0.1:8080/simple/` 成功加载 Kindle 书架与阅读器，静态资源（JS/CSS/模板）全部 200 返回。
- [x] 现代 Web 客户端与 Simple-Web 之间支持双向无缝切换。
- [x] 运行 `./gradlew :server:test` 与 `npm --prefix web run check && npx tsx web/test/run-all.ts` 零报错通过。
