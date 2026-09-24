---
id: ADR-010
title: Simple-Web 极简前端直接适配标准 REST API 与静态资源内置架构
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-09-19
---

# ADR-010: Simple-Web 极简前端直接适配标准 REST API 与静态资源内置架构

## 1. 决策背景 (Context)

Legado 具备活跃的电子墨水屏与 Kindle 用户群。原 Android 版本的 Web 服务内置了一套使用原生 DOM 与轻量 Zepto 构建的 `simple-web`，专为低性能浏览器与墨水屏优化（支持硬分页、按键翻页、黑白高对比排版、墨水残影白屏消除等）。
在无头服务端（`io.legado.server`）中，我们需要：
1. 内置该套独立、轻量的前端资源，供 Kindle 或墨水屏设备访问；
2. 确保墨水屏客户端与现有无头服务端顺利通信，同时保持服务端技术栈的整洁与纯粹；
3. 确保与现有现代 React Web 前端（`web/`）互不冲突、共享同一套底层 SQLite 数据库与解析缓存服务。

---

## 2. 裁定方案 (Decision)

### 2.1 架构路线裁定：前端适配器模式（UI 改适程序）
- **否决在服务端引入冗余 Legacy API 兼容层**：原 reader3/Legado Web API 历史包袱重，存在路径重复（如 `/reader3/getBookshelf`）、契约分散、缺少 CSRF 防御等问题。由于 reader3 原项目已停更且无上游需要兼容，服务端不增加任何冗余 Legacy 路由。
- **直接改造 Simple-Web 前端适配标准 REST API (`/api/*`)**：
  - 将 `simple-web` 的全部前端资源放入 `server/src/main/resources/simple/` 目录下（包含 `index.html`, `reader.html`, `search.html`, `rss.html`, `assets/css/`, `assets/js/`, `assets/template/`）。
  - 改造 `common-*.js` 的 `_$.ajax` 底层网络库，注入 `X-CSRF-Token` 头与 `withCredentials = true`，自动支持 Session 鉴权与 401 拦截。
  - 改造 `BookApi`、`TocApi`、`Reader`、`SearchPage` 的数据获取与交互方法，直接调用现代 REST API：
    - 书架加载：`GET /api/bookshelf`（自动映射 `durChapterIndex`, `durChapterTitle` 等）。
    - 目录获取：`POST /api/books/chapters`（入参 `{ sourceId, bookUrl }`）。
    - 正文读取：`POST /api/books/content`（入参 `{ sourceId, chapterUrl, chapterIndex, bookUrl }`）。
    - 进度持久化：`PUT /api/reading-progress`（入参 `{ sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition, updatedAt }`）。
    - 全网搜索：`POST /api/search`（入参 `{ query, group }`）。
    - 书源列表：`GET /api/sources`。
    - 书源换源：`POST /api/bookshelf/switch-source`。
    - 用户鉴权：`GET /api/auth/session`, `POST /api/auth/login`, `POST /api/auth/logout`。

### 2.2 资源挂载与路由
- Ktor 路由中通过 `staticResources("/simple", "simple", index = "index.html")` 提供极简静态资源直出。
- 提供 `/kindle` 重定向（302 -> `/simple/`），便于 Kindle 浏览器快速访问。
- 根路径 `/` 保留给现代化 React 客户端，两套 UI 各司其职，并在界面中互相提供快捷跳转链接。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：服务端实现 Legacy Web API 兼容层 (`LegacyRoutes.kt`)**
  - *否决理由*：reader3 原项目已停更，没有外部生态需要保持协议一致。在服务端新增一套重复的 Legacy 路由会增加长期的维护成本、测试面与架构复杂度。
- **备选方案 B：仅用现代 React UI 通过 CSS 媒体查询 (`@media (hover: none) and (pointer: coarse)`) 自适应**
  - *否决理由*：Kindle 实验性浏览器内核陈旧，对现代 React 19、复杂 Virtual DOM 与 CSS 变量支持不佳，容易白屏或卡顿。专用的轻量 DOM `simple-web` 启动速度远超现代 SPA。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益
1. **后端高度纯粹**：服务端 API 100% 统一收敛在 `/api/*` RESTful 规范下，零遗留代码与重复路由。
2. **多终端体验极致化**：PC/移动端享受现代 React 19 富交互与大目录虚拟化；Kindle/墨水屏享受零延迟、无动画、省电的纯黑白硬分页排版。
3. **安全与鉴权一致**：Simple-Web 同样享受 CSRF 保护与标准 Session 鉴权，不再依赖不安全的 URL 参数鉴权。

### 潜在风险与防御策略
- **Simple-Web 代码压缩/混淆后的维护**：Simple-Web JS 已经过模块化梳理，关键网络请求与 API 接口均在 `assets/js/*.js` 中做了标准化封装，结构清晰稳定。
