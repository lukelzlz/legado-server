# Repository Guidelines & Constitutional Conventions

## 1. Project Core Mission & Objective

> **核心项目定位**：将 **Legado（开源阅读 Android 平台应用）** 完整迁移与重构为可在标准服务器与 Docker 容器中独立运行的高性能**无头后端（Ktor + Kotlin JVM + SQLite）**与现代化**Web 客户端（React 19 + TypeScript + Vite）**。

### 关键迁移与设计原则
1. **彻底解耦 Android 平台依赖**：严禁在 `server/` 或 `web/` 模块中引入 Android Framework 原生组件（如 Activity、Context、Room、Android WebView、Android UI 等），必须使用跨平台的纯 JVM 技术栈与标准 Web API 替代。
2. **服务端无头（Headless）化架构**：服务端作为独立的中心服务，承载书源规则解析与执行沙箱（Rhino JS + Jsoup + JsonPath）、多书源并发搜索、离线书籍与封面缓存、订阅同步、PBKDF2/Session 鉴权与 SQLite 数据持久化。
3. **深度兼容书源生态**：完全复用并兼容 Legado 现有丰富的书源协议与规则定义，确保现有网络书源可在服务端正确、安全且高效地解析。
4. **现代化 Web 阅读体验**：Web 端作为跨平台终端，提供大章节目录虚拟化、单书源直连秒开、离线缓存进度同步及沉浸式阅读器交互。

---

## 2. 仓库边界与文档驱动规范 (Repository Boundary & Cleanliness)

本项目严格执行**文档驱动开发（Doc-Driven Development）**与工程整洁度控制：

- **严禁随地大小便**：严禁在项目根目录或业务源码目录中散落 `PLAN.md`, `NOTES.md`, `TODO.md`, `temp/`, `plan/` 等临时或未受管文档。
- **文档统一收敛机制**：
  - 需求与功能提案 (PRD) 归入 [`docs/proposals/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/)
  - 架构决策记录 (ADR) 归入 [`docs/decisions/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/)
  - 原始推演、历史排错与工作记忆归入 [`docs/sessions/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/)
  - 用户实操验收手册与验证清单归入 [`docs/acceptance/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/)
- **工程复杂度惩罚**：严禁为了单一补丁随意增加无意义的抽象层、胶水层或重复工具类。优先采用满足当前需求的最简、最直接解法。

---

## 3. 最短验证命令集 (Shortest Verification Commands)

AI 在修改代码后，必须优先执行本节定义的最短、最精准命令自测：

```sh
# --- 1. 前端类型检查与自动化测试套件 ---
npm --prefix web run check
npx tsx web/test/run-all.ts

# --- 2. 服务端本地 JVM 单元测试 ---
./gradlew :server:test

# --- 3. 前后端综合一键验证 ---
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test

# --- 4. 本地 Docker 容器构建与热更新验证 (用户常用测试基准) ---
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

---

## 4. 完成度状态阶梯 (Completion States)

AI 与人类协作时必须明确当前达到的完成度阶梯，严禁混淆概念：
1. `[Modified 本地改动]`：代码已编写完成，但尚未执行测试。
2. `[Tested 单测/检查通过]`：已通过本地最短单测（`gradlew :server:test`）与静态类型检查（`npm run check` / `tsx run-all.ts`）。
3. `[Deployed 容器/服务联调完成]`：已部署/更新至本地 Docker 容器并启动，且日志与接口无报错。
4. `[Accepted 用户签字确认]`：用户已根据验收手册实操验证并明确回复“验收通过/LGTM”。
5. `[Pushed 提交与推送]`：代码已提交至 Git 并推送到远端仓库。

---

## 5. 部落知识库与历史踩坑 (Tribal Knowledge)

基于历史会话全量扫描提炼的架构潜规则与避坑指南：

- **[书源解析/兼容] `bookSourceUrl` 允许任意非空唯一字符串**：Legado 书源生态中部分聚合或定制书源（如 `大灰狼融合VIP5.0`）使用自定义中文或标识作为 `bookSourceUrl`，服务端严禁粗暴强制要求 `http(s)://`；同时前端与服务端导入均需兼容 UTF-8 BOM 编码及 `{ data: [...] }` / `{ sources: [...] }` / `{ bookSources: [...] }` 等外层包装结构。
- **[SQL/Kotlin] 严禁使用可空列做存在性 Elvis 判断**：在 JDBC / SQLite 结果集提取中，务必区分“字段值为 NULL”与“数据行不存在”。例如 `SELECT cover_key FROM book_shelf`，若书籍无封面则字段为 `NULL`，直接 `rs.getString(...) ?: return null` 会误判书籍不存在。
- **[前端/Form] 按钮显式声明 `type="button"`**：表单内的所有辅助操作按钮（如停止搜索、清空、排序切换）必须显式标注 `type="button"`，否则点击会触发 HTML 表单默认 `submit` 事件导致搜索意外重启。
- **[翻页/排版] 跨章逆向翻页定位守卫**：从章节开头回翻到上一章时，必须携带 `targetPosition = 'bottom'` 标记，且必须在 DOM/分栏异步排版完成后再执行末尾定位，严禁在未完成排版前盲目计算滚动高度。
- **[性能] 缓存优先直出与流式防抖**：进入阅读器时优先命中本地 `BookCacheService` 离线缓存分片，避免等待全量远程 TOC；流式搜索推送高频数据时前端需保持批量节流合并渲染。
- **[容器/云原生] 阿里云计算巢与 ECI 部署**：ROS 模板必须包含完整 VPC/安全组声明、ECI 容器组规格与数据持久化挂载；国内推荐使用阿里云个人镜像加速源。

---

## 6. 标准文档体系索引 (Proposals, Decisions & Sessions Index)

### 需求与功能提案 (PRD Proposals)
| 编号 | 标题 / 议题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- |
| PROPOSAL-001 | Legado 规则执行引擎无头化迁移与 Ktor 服务端架构 | [`docs/proposals/PROPOSAL-001-headless-rule-engine-and-server-migration.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-001-headless-rule-engine-and-server-migration.md) | Implemented |
| PROPOSAL-002 | 多书源高并发流式搜索与多维智能排序引擎 | [`docs/proposals/PROPOSAL-002-multi-source-streaming-search-and-smart-ranking.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-002-multi-source-streaming-search-and-smart-ranking.md) | Implemented |
| PROPOSAL-003 | 整书离线缓存、断点续传与跨书源书架持久化 | [`docs/proposals/PROPOSAL-003-offline-book-caching-and-breakpoint-resume.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-003-offline-book-caching-and-breakpoint-resume.md) | Implemented |
| PROPOSAL-004 | 现代化 Web 阅读器、超大目录虚拟化与双栏宽屏排版 | [`docs/proposals/PROPOSAL-004-modern-web-reader-and-toc-virtualization.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-004-modern-web-reader-and-toc-virtualization.md) | Implemented |
| PROPOSAL-005 | 支持书源登录、凭据持久化与动态 LoginUI 交互 | [`docs/proposals/PROPOSAL-005-book-source-login-and-credential-storage.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-005-book-source-login-and-credential-storage.md) | Implemented |

### 架构决策记录 (ADR)
| 编号 | 决策标题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- |
| ADR-001 | 彻底解耦 Android 原生平台，采用 Ktor + Kotlin JVM + Rhino JS 沙箱 | [`docs/decisions/ADR-001-pure-jvm-ktor-and-rhino-sandbox.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-001-pure-jvm-ktor-and-rhino-sandbox.md) | Accepted |
| ADR-002 | SQLite 读写分离、WAL 模式与原子状态计数优化 | [`docs/decisions/ADR-002-sqlite-wal-pooling-and-in-memory-counters.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-002-sqlite-wal-pooling-and-in-memory-counters.md) | Accepted |
| ADR-003 | 开书级联请求消除：单书源秒开 + 候补源懒加载 + TOC 虚拟滚动 | [`docs/decisions/ADR-003-lazy-candidate-loading-and-toc-virtualization.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-003-lazy-candidate-loading-and-toc-virtualization.md) | Accepted |
| ADR-004 | 阅读器跨章反向翻页定位状态机与异步排版守卫 | [`docs/decisions/ADR-004-cross-chapter-navigation-state-machine.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-004-cross-chapter-navigation-state-machine.md) | Accepted |
| ADR-005 | 书源登录鉴权、动态 LoginUI 驱动与凭据状态持久化 | [`docs/decisions/ADR-005-book-source-login-ui-and-session-state.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-005-book-source-login-ui-and-session-state.md) | Accepted |

### 工作记忆与历史推演归档 (Sessions Chronicle)
| 日期 / ID | 类型 | 标题 / 议题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| 2026-08-15 | Hist | 开书延迟、正文离线缓存吞吐与目录性能重构 | [`docs/sessions/SESSION-HIST-001-reading-performance-and-cache.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-001-reading-performance-and-cache.md) | Pushed |
| 2026-08-30 | Hist | 阅读器跨章反向翻页定位与桌面端双栏/宽屏排版 | [`docs/sessions/SESSION-HIST-002-reverse-chapter-navigation-and-desktop-layout.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-002-reverse-chapter-navigation-and-desktop-layout.md) | Pushed |
| 2026-08-29 | Hist | 跨书源封面与正文融合、书架空值判定与搜索中断修复 | [`docs/sessions/SESSION-HIST-003-multi-source-fusion-and-shelf-null-handling.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-003-multi-source-fusion-and-shelf-null-handling.md) | Pushed |
| 2026-08-30 | Init | 初始化标准文档体系与历史会话挖掘继承 (`/doc-init`) | [`docs/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/) | Accepted |
| 2026-08-30 | Fix | 统一登录界面品牌 Logo 与设计语言微调 | - | Pushed |
| 2026-08-31 | Feat | 配置 GitHub Actions 自动编译与分发可执行 JAR 及分发包 | - | Pushed |
| 2026-08-31 | Feat | 支持书源登录鉴权、动态 LoginUI 与凭据状态持久化 | [`docs/sessions/SESSION-HIST-004-book-source-login-implementation.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-004-book-source-login-implementation.md) | Tested |
| 2026-08-31 | Fix | 修复书源导入解析（支持自定义标识/BOM/外层包装），优化交互反馈 | - | Tested |

---

## 7. Project Structure & Module Organization

This repository is dedicated to the standalone Legado Server ecosystem: the standalone backend server and the web client.

- **`server/`**: Standalone headless backend server built with Ktor (`io.legado.server`), Kotlin JVM, and SQLite. Handles source parsing and execution (`RuleRunner` with Jsoup, JsonPath, and Rhino JS engine), authentication/sessions, book and cover caching, source subscription synchronization, and API routing.
- **`web/`**: Web reader and management UI (`legado-server-web`) built with React 19, TypeScript, and Vite. Communicates with `server/` APIs to manage sources, search books across sources, debug rules, and read books.

---

## 8. Coding Style & Naming Conventions

- **Kotlin**: Four-space indentation, idiomatic Kotlin constructs. Match nearby code for brace placement, imports, and null handling. Use PascalCase for classes and files (`BookCacheService.kt`), camelCase for functions and properties.
- **TypeScript / React**: Modern functional components with hooks, strict TypeScript types, and organized CSS in `web/src/styles.css`.
- **Security & Reliability**: Standalone server must enforce authentication, PBKDF2 password hashing, session cookies, CSRF protection, and sandboxed JS evaluation.

---

## 9. Testing Guidelines

- Add or update focused regression tests for any behavior changes (e.g. `RuleRunnerTest.kt`, `DatabaseTest.kt`, `CoverCacheTest.kt`, `BookCacheServiceTest.kt`).
- Prefer deterministic unit tests in `src/test/`.
- Ensure all relevant test suites pass before submitting changes (`./gradlew :server:test`, `npm run check`, `npx tsx web/test/run-all.ts`, etc.).

---

## 10. Commit & Pull Request Guidelines

- Recent commits use concise Chinese imperative summaries or conventional commit format (e.g. `优化翻页交互：从章节开头回翻定位至上一章末尾`, `fix(web): 修复点击停止搜索误触表单提交导致重新发起搜索的问题`).
- Keep commits focused and describe the user-visible change or module scope.
- Never commit passwords, signing credentials, API tokens, or local `.env` secrets.

---

## 11. Engineering Principles

- 不保留向后兼容性。应删除过时的路径，而不是添加兼容层、回退机制或迁移方案。
- 选择能够完全满足当前需求的最简单实现。避免引入臆测性的抽象、配置和间接层。
- 以分层方式逐步构建系统。先实现能够端到端运行的最小版本，再在已有可用产品的基础上逐项增加新能力。绝不要为了尚未完成的复杂设计而牺牲一个可正常运行的产品。
- 保持组件模块化，并清晰分离各项职责（服务端解析、前端交互、数据持久化各司其职）。
- 当成熟且维护良好的库能够降低整体复杂度或提高可靠性时，应优先使用。除非有明确理由，否则不要重复实现常见功能。
