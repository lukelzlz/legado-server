# Project: Legado Server (Android 阅读软件服务端化迁移与 Web 重构)

## Core Mission & Architecture
> **核心使命**：将 **Legado（开源阅读 Android 平台应用）** 完整迁移与重构为一个无需 Android 运行时、可在标准服务器与 Docker 容器中独立运行的**无头后端（Ktor + Kotlin JVM + SQLite）**与现代化**Web 客户端（React 19 + TypeScript + Vite）**。

Legado is a high-performance reader ecosystem consisting of:
- **Server (`server/`)**: Headless Ktor backend handling rule execution (Rhino JS, Jsoup, JsonPath), SQLite database persistence, offline book caching, and REST/WebSocket APIs without any Android framework dependencies.
- **Web Frontend (`web/`)**: React 19 + TypeScript + Vite reader UI consuming server APIs with virtualized large TOC lists and lazy candidate source loading.


## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Non-blocking Search Candidate Validation | Eliminate 3-tier serial network cascade in search candidates; return candidates directly upon search completion | M1 | ORIGINAL_REQUEST §R1 |
| 2 | Relaxed Cover URL Validation | Make cover URL optional and non-blocking in search results | M1 | ORIGINAL_REQUEST §R1 |
| 3 | Streaming Search Concurrency Optimization | Scale search concurrency to 16–32 workers and emit immediate WebSocket results per source | M1 | ORIGINAL_REQUEST §R1 |
| 4 | SQLite Connection Lifecycle & Config | Persistent write connection & reusable read connections with WAL mode, busy timeout 5000ms, normal synchronous | M1 | ORIGINAL_REQUEST §R3 |
| 5 | `listSources` Payload Query Optimization | Use explicit column projection in `listSources` to avoid loading massive JSON payloads into memory | M1 | ORIGINAL_REQUEST §R3 |
| 6 | Case-Insensitive Source Indexing | Add `NOCASE` index on `source(name COLLATE NOCASE)` for fast source lookups | M1 | ORIGINAL_REQUEST §R3 |
| 7 | Bounded Offline Cache Concurrency | Refactor `BookCacheService` with Coroutines Semaphore (4–8 concurrent chapter downloads) | M2 | ORIGINAL_REQUEST §R2 |
| 8 | Breakpoint Resume & Chapter Skipping | Pre-fetch cached chapter URLs and skip already-cached chapters in O(1) time | M2 | ORIGINAL_REQUEST §R2 |
| 9 | In-Memory Atomic Counters & Throttled Status Updates | Remove `SELECT COUNT(*)` from `cacheBookContent`; use atomic counters and throttled 1000ms updates to DB | M2 | ORIGINAL_REQUEST §R2 |
| 10 | Cache Service Test Suite | Add comprehensive `BookCacheServiceTest.kt` verifying concurrency, resume, cancellation, error tolerance | M2 | ORIGINAL_REQUEST §R2 |
| 11 | Lazy Candidate Source Loading on Book Open | Eliminate 40+ cascading candidate API calls; fetch active source first, load candidate sources on-demand | M3 | ORIGINAL_REQUEST §R4 |
| 12 | Virtualized Large TOC Rendering | Implement `VirtualChapterList` in reader for 5,000+ chapter TOCs rendering ~25 DOM nodes with auto-scroll | M3 | ORIGINAL_REQUEST §R4 |
| 13 | Reader Paragraph Memoization & Scroll Throttling | Memoize split paragraphs and throttle scroll tracking to eliminate layout thrashing | M3 | ORIGINAL_REQUEST §R4 |
| 14 | E2E Testing & Verification Harness | Automated verification of build, streaming search, instant book open, virtual TOC, and cache resume | M4 | ORIGINAL_REQUEST §R5 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Server Search & Database Lifecycle | R1 (non-blocking search, cover relaxation, concurrency) + R3 (DB pooling/config, listSources projection) | none | IN_PROGRESS |
| M2 | Book Offline Cache Concurrency & Resume | R2 (bounded concurrency, breakpoint resume, atomic counters, BookCacheServiceTest) | M1 (DB helper methods) | PLANNED |
| M3 | Web Frontend Performance & Virtual TOC | R4 (lazy candidate loading, virtualized TOC list, reader memoization) | none | IN_PROGRESS |
| M4 | Final E2E Test Pass & Real Verification | R5 (Full test suite, 100% E2E test pass, browser & real data verification) | M1, M2, M3 | PLANNED |

## Interface Contracts
### Server Database ↔ Services
- `Database.cachedChapterUrls(sourceId: String, bookUrl: String): Set<String>`
- `Database.updateBookCacheProgress(sourceId: String, bookUrl: String, cachedCount: Int)`
- `Database.cacheBookContent(sourceId: String, bookUrl: String, chapterUrl: String, content: ChapterContent): Unit`
- `Database.listSources(query: String?): List<SourceSummary>` (projection: `id, name, source_url, source_group, enabled, is_js, updated_at, version`)

### Server Search ↔ Web Client
- `GET /api/search?keyword=...` / WebSocket `/api/search/stream`: Returns `SearchResult` without blocking on detail/TOC/content. Cover URLs may be null/empty or fallback.
- `GET /api/books/details`, `GET /api/books/chapters`: Called on demand per source.

### Web Frontend Candidate Loading
- `SourceChoice`: `{ result: SearchResult, book?: BookDetail, preview?: string, status: 'idle' | 'loading' | 'loaded' | 'error', error?: string }`
- Lazy loading triggered only upon expanding source list or clicking alternative source.

## Code Layout
- `server/src/main/kotlin/io/legado/server/Database.kt` (SQLite persistence, connection management)
- `server/src/main/kotlin/io/legado/server/RuleRunner.kt` (Rule execution, search candidate handling)
- `server/src/main/kotlin/io/legado/server/Routes.kt` (Ktor routing, search concurrency, streaming)
- `server/src/main/kotlin/io/legado/server/BookCacheService.kt` (Offline caching engine)
- `server/src/test/kotlin/io/legado/server/` (Unit tests)
- `web/src/main.tsx` (App entry, Library page, Bookshelf page, Source choice logic)
- `web/src/ReaderScreen.tsx` (Reader UI, TOC modal, VirtualChapterList)
- `web/src/styles.css` (Virtual list and reader styles)
