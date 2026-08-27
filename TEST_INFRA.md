# E2E Test Infra: Legado Server & Web Performance Refactoring

## Test Philosophy
- Opaque-box, requirement-driven. Derives test cases directly from user requirements (R1–R5).
- Multi-tier coverage:
  - **Tier 1: Feature Coverage (>=5 per feature)**: Unit and API-level tests verifying each feature in isolation.
  - **Tier 2: Boundary & Corner Cases (>=5 per feature)**: Edge cases, empty inputs, network timeouts, invalid covers, 0-chapter books, cancelled operations.
  - **Tier 3: Cross-Feature Combinations (pairwise coverage)**: Search + offline caching, multi-source switching + reading progress persistence, connection pooling under concurrent caching + searching.
  - **Tier 4: Real-World Workload Scenarios**: Complete reader lifecycle (search -> instant book open -> 5,000 chapter TOC navigation -> offline caching -> breakpoint resume).

## Feature Inventory & Test Coverage
| # | Feature | Source (Requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---------|---------------------|:------:|:------:|:------:|
| 1 | Non-blocking Search Candidates | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 2 | Relaxed Cover URL Validation | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | Streaming Search Concurrency | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 4 | SQLite Connection Lifecycle | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 5 | `listSources` Projection Query | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 6 | Case-Insensitive Source Index | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |
| 7 | Offline Cache Concurrency | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 8 | Breakpoint Resume | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 9 | In-Memory Counters & DB Throttling | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ |
| 10 | Lazy Candidate Source Loading | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |
| 11 | Virtualized Large TOC Rendering | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |
| 12 | Reader Paragraph & Scroll Optimization | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ |

## Test Architecture
- **Backend Test Runner**: `./gradlew :server:test` (Kotlin JVM unit tests in `server/src/test/kotlin/io/legado/server/`)
- **Frontend Type & Build Runner**: `cd web && npm run check && npm run build`
- **E2E Integration Verification**: Deterministic script & headless API test fixtures.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Multi-source streaming search & instant open | Search Concurrency + Lazy Candidate Loading + Cover Relaxation | High |
| 2 | Offline caching 2,000 chapters with concurrent reading | Cache Concurrency + SQLite Connection Lifecycle + Atomic Progress | High |
| 3 | Cache interruption, restart & instant breakpoint resume | Cache Resume + DB Chapter URL Pre-fetching | Medium |
| 4 | Large Book (5,000+ chapters) TOC virtual browsing & search | TOC Virtualization + Reader Reflow + Paragraph Memoization | High |
| 5 | High concurrency source management & query | `listSources` Projection + Case-Insensitive Indexing + Connection Pool | Medium |

## Acceptance Criteria
- All tests in `./gradlew :server:test` pass with 0 errors.
- `cd web && npm run check` and `npm run build` pass with 0 errors.
- Book open makes only active source calls (no 40+ request storm).
- TOC with 5,000+ chapters renders ~25 DOM elements with zero keystroke lag.
- Cache service downloads with bounded concurrency and resumes existing chapters without re-downloading.
