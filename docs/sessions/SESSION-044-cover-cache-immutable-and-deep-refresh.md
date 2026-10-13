---
id: SESSION-044
title: 审查与合入 PR #39：服务器封面缓存永不过期与深度回源补抓
date: 2026-10-04
author: wfanan, Agent
tags: [covers, cache-control, immutable, deep-refresh, cdn-signature, worktree]
branch: feat/server-cached-covers
worktree: ../legado-server-pr39
---

# SESSION-044: 审查与合入 PR #39：服务器封面缓存永不过期与深度回源补抓

## 1. 背景与核心诉求 (Background & Problems)

在开源阅读 Headless 服务端运行与书架同步过程中，书架封面存在两项痛点：
1. **封面协商请求冗余**：
   `GET /api/covers/{key}` 原先仅配置了 `CacheControl.MaxAge(7 days, Private)`，未声明 `immutable`。然而本项目的 `cover_key` 本质是图片二进制内容的 SHA256 哈希摘要（`CoverCache.sha256Hex(bytes)`），同一个 key 对应的二进制内容在物理意义上永不变更。原先的 7 天缓存策略导致浏览器在过期后仍频繁发出 304 协商请求，在拥有几百本书的书架上引发不必要的网络往返开销。
2. **过期签名 CDN 链接失效与死图**：
   书架中大量来自番茄等书源（`fqnovelpic.com`）的封面外链采用了约 60 天有效期的临时签名机制（`?x-expires=...&x-signature=...`）。早期导入的书籍如果当时未成功物化（`cover_key` 为空），数月后原有的 `cover_url` 已经彻底失效（403 Forbidden）。常规的 `/api/bookshelf/refresh-covers` 仅在已有失效外链上重试，注定抓取失败，导致客户端即便回退外链也只能显示裂图或文字占位符。

---

## 2. 方案设计与对抗审查 (Skeptic Audit & Solution)

### 2.1 服务器封面缓存永不过期与不可变声明 (Immutable Header)
- **实现**：在 `GET /api/covers/{key}` 的响应头中，将 `Cache-Control` 声明升级为：
  ```http
  Cache-Control: private, max-age=31536000, immutable
  ```
- **红蓝对抗审查**：
  - *Skeptic*：为什么不使用 `public` 缓存？
  - *Defender*：书籍封面属于用户个人书架数据，标记为 `private` 可以防止多租户反代或公共代理缓存跨用户窥探，同时客户端浏览器依然能够完整享受 1 年的本地强缓存与 `immutable` 零协商直出。
  - *Skeptic*：封面如果更换怎么办？
  - *Defender*：若书籍更换封面，重新抓取后生成的是全新的 SHA256 `cover_key`，URL 随之改变，老缓存自然不会污染新封面。

### 2.2 封面深度回源补抓 (Deep Refresh Pipeline)
- **接口扩展**：`CoverRefreshRequest` 新增 `deep: Boolean = false`，默认 `false` 严格保持向后兼容。响应中新增 `resourced: Int` 统计回源获得新地址的条目数。
- **两步补抓流程**：
  1. **回源获取新鲜地址**：将 `/books/details` 中的详情拉取逻辑抽象为 `fetchBookDetails(database, runner, sourceId, bookUrl)`。深度模式下针对 `coverKey == null && !coverUrl.isNullOrBlank()` 的书籍，通过书源规则动态拉取最新的书本详情，解析出当前可用的新签名封面 URL（`resolveFreshCoverUrl`）。
  2. **物化并联动更新外链**：调用 `materializeCover`（通过 `CoverCache.cache` 内置魔数校验与 gzip 自动解压），物化成功后调用 `database.updateBookshelfCover`，同时更新 `cover_key` 与 `cover_url`，确保后续哪怕切换源或直接读取外链兜底也是活地址。

### 2.3 异常边界与并发竞态修复 (Concurreny & Failure Isolation)
- **消除协程数据竞态**：原代码在 `async(Dispatchers.IO)` 内直接执行普通变量 `refreshed++` / `failed++`，存在并发数据丢失风险；PR 39 将其重构为 `AtomicInteger`（`incrementAndGet()`），彻底消除竞态。
- **单本故障完全隔离**：回源抛异常（书源失效、网络超时、上游 502）、解析不到新 URL、图床下载 404 等，均由 `runCatching` 兜底返回 null 并递增 `failed` 计数，绝不抛出异常中断整批处理队列。
- **并发控制**：沿用 `Semaphore(6)` 严格压制整架回源并发，避免瞬间冲垮书源匿名频控与服务端连接池。

---

## 3. 测试验证与结果 (Verification Results)

在独立 Git Worktree（`../legado-server-pr39`）中执行完整最短验证命令集：

1. **前端类型检查与自动化测试**：
   ```sh
   npm --prefix web run check
   npx tsx web/test/run-all.ts
   ```
   - **结果**：TypeScript 编译零错误；前端 208 项单测 100% 全部通过。

2. **服务端单元测试套件**：
   ```sh
   ./gradlew :server:test --tests "io.legado.server.CoverDeepRefreshTest"
   ./gradlew :server:test
   ```
   - **结果**：
     - `CoverDeepRefreshTest` 6 个契约用例全部通过（涵盖 `deep=false` 不回源、`deep=true` 回源更新外链与落库、回源失败隔离、接缝异常静默、本地书地址兼容、SQL 字段选择性更新）。
     - 测试全程零网络请求（利用聚合源 `data:;base64,...` 书源规则本地求值与 `CoverCache` 预置缓存接缝）。
     - 全量 JVM 单元测试 100% 成功（零失败，无回归）。

---

## 4. 沉淀的教训与部落知识 (Lessons Learned)

1. **[HTTP/缓存] 内容哈希（Content Hash）资源应无条件声明 `immutable`**：以文件内容的 SHA256 作为 URL 路径的静态二进制接口（如 `/api/covers/{sha256}`），其内容天然恒定不可变。设置 `Cache-Control: private, max-age=31536000, immutable` 可以完全阻止浏览器在刷新和滚动时发出 304 协商请求，显著提升大规模列表浏览的流畅度。
2. **[数据同步/封面] 临时签名 CDN 外链必须通过「回源+更新外链」双重救活**：主流平台 CDN 封面大多携带约 60 天的短期鉴权签名。仅靠本地重试原有 URL 无法自愈，必须通过书源详情接口重新拉取活的新签名 URL，并在物化本地缓存的同时更新 `cover_url`，防止外链失效导致兜底机制瘫痪。
3. **[Kotlin/并发] 协程批处理计数必须使用原子类（AtomicInteger）**：在 `coroutineScope` 中使用 `async(Dispatchers.IO)` 并发执行任务时，严禁用普通 `var count = 0` 进行 `count++` 操作，必须使用 `AtomicInteger`，防止高并发下计数丢失导致统计口径失真。
