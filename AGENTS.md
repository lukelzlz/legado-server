# Repository Guidelines

## Project Core Mission & Objective

> **核心项目定位**：将 **Legado（开源阅读 Android 平台应用）** 完整迁移与重构为可在标准服务器与 Docker 容器中独立运行的高性能**无头后端（Ktor + Kotlin JVM + SQLite）**与现代化**Web 客户端（React 19 + TypeScript + Vite）**。

### 关键迁移与设计原则
1. **彻底解耦 Android 平台依赖**：严禁在 `server/` 或 `web/` 模块中引入 Android Framework 原生组件（如 Activity、Context、Room、Android WebView、Android UI 等），必须使用跨平台的纯 JVM 技术栈与标准 Web API 替代。
2. **服务端无头（Headless）化架构**：服务端作为独立的中心服务，承载书源规则解析与执行沙箱（Rhino JS + Jsoup + JsonPath）、多书源并发搜索、离线书籍与封面缓存、订阅同步、PBKDF2/Session 鉴权与 SQLite 数据持久化。
3. **深度兼容书源生态**：完全复用并兼容 Legado 现有丰富的书源协议与规则定义，确保现有网络书源可在服务端正确、安全且高效地解析。
4. **现代化 Web 阅读体验**：Web 端作为跨平台终端，提供大章节目录虚拟化、单书源直连秒开、离线缓存进度同步及沉浸式阅读器交互。

---

## Project Structure & Module Organization

This repository is dedicated to the standalone Legado Server ecosystem: the standalone backend server and the web client.

- **`server/`**: Standalone headless backend server built with Ktor (`io.legado.server`), Kotlin JVM, and SQLite. Handles source parsing and execution (`RuleRunner` with Jsoup, JsonPath, and Rhino JS engine), authentication/sessions, book and cover caching, source subscription synchronization, and API routing.
- **`web/`**: Web reader and management UI (`legado-server-web`) built with React 19, TypeScript, and Vite. Communicates with `server/` APIs to manage sources, search books across sources, debug rules, and read books.

## Build, Test, and Development Commands

### Server Backend (`server/`)
- `./gradlew :server:test`: Run local JVM unit tests for the server.
- `./gradlew :server:run`: Run the Ktor server locally.
- `./gradlew :server:jar`: Package the server application JAR.
- Docker:
  - `docker compose --env-file .env -f compose.server.yml up -d --build`
  - `docker compose --env-file .env -f compose.server.yml run --rm legado-server reset-password '<new-password>'`

### Web Frontend (`web/`)
- `cd web && npm install`: Install frontend dependencies.
- `cd web && npm run dev`: Start local Vite development server.
- `cd web && npm run check`: Run TypeScript type checking without emitting files.
- `cd web && npm run build`: Typecheck and build static production bundle to `web/dist`.
- `npx tsx web/test/run-all.ts`: Run frontend comprehensive test suite.

Java 17+ / Kotlin JVM is used across the Gradle build. Use the checked-in Gradle wrapper (`./gradlew`).

## Coding Style & Naming Conventions

- **Kotlin**: Four-space indentation, idiomatic Kotlin constructs. Match nearby code for brace placement, imports, and null handling. Use PascalCase for classes and files (`BookCacheService.kt`), camelCase for functions and properties.
- **TypeScript / React**: Modern functional components with hooks, strict TypeScript types, and organized CSS in `web/src/styles.css`.
- **Security & Reliability**: Standalone server must enforce authentication, PBKDF2 password hashing, session cookies, CSRF protection, and sandboxed JS evaluation.

## Testing Guidelines

- Add or update focused regression tests for any behavior changes (e.g. `RuleRunnerTest.kt`, `DatabaseTest.kt`, `CoverCacheTest.kt`, `BookCacheServiceTest.kt`).
- Prefer deterministic unit tests in `src/test/`.
- Ensure all relevant test suites pass before submitting changes (`./gradlew :server:test`, `npm run check`, `npx tsx web/test/run-all.ts`, etc.).

## Commit & Pull Request Guidelines

- Recent commits use concise Chinese imperative summaries (e.g. `优化流式搜索与移动阅读体验`, `新增书籍缓存与多书源切换`, `修复正文请求参数校验`).
- Keep commits focused and describe the user-visible change or module scope.
- Never commit passwords, signing credentials, API tokens, or local `.env` secrets.

## Engineering Principles

- 不保留向后兼容性。应删除过时的路径，而不是添加兼容层、回退机制或迁移方案。
- 选择能够完全满足当前需求的最简单实现。避免引入臆测性的抽象、配置和间接层。
- 以分层方式逐步构建系统。先实现能够端到端运行的最小版本，再在已有可用产品的基础上逐项增加新能力。绝不要为了尚未完成的复杂设计而牺牲一个可正常运行的产品。
- 保持组件模块化，并清晰分离各项职责（服务端解析、前端交互、数据持久化各司其职）。
- 当成熟且维护良好的库能够降低整体复杂度或提高可靠性时，应优先使用。除非有明确理由，否则不要重复实现常见功能。
