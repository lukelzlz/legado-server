---
id: SESSION-051
title: 移出书架同步清理媒体缓存（漫画、音频、插图与预取取消）
date: 2026-10-18
author: Agent
tags: [shelf, cache, manga, audio, illustration, prefetch, cleanup, review]
branch: main
pr: 80
---

# SESSION-051: 移出书架同步清理媒体缓存（漫画、音频、插图与预取取消）

## 1. 现象与根因推演 (Investigation & Analysis)
- **用户反馈**：「为什么书移出书架后漫画，音频，插图没有同步删了」。
- **根因分析**：
  - 既有 `Database.removeBookshelf` 与 `Database.batchBookshelfOperation(action = "delete")` 仅负责清理：
    - `book_shelf`
    - `reading_progress`
    - `book_content_cache`（文本小说正文）
    - `book_cache_status`
    - `book_toc_cache`
    - 孤儿封面 `cover_cache`
  - 而后续陆续引入的独立媒体缓存体系：
    - 漫画：`manga_image_cache` 表、`manga_chapter_cache` 表（含 `pinned` 状态）及磁盘 `comic/`
    - 有声书：`audio_cache` 表及磁盘 `audio/`
    - 正文插图：`illustration_cache` 表及磁盘 `images/`
  - **从未接入移出书架的处理路径**！
  - 当书籍从书架被删除后，书籍身份从数据库消失，Web 界面上再也找不到该书入口，用户也无法再通过“清除缓存”入口进行清理，导致这三类媒体的数据库行与磁盘物理文件永久泄漏，直至磁盘触发配额 GC。

## 2. 方案设计与对抗审查 (Review & Architecture)
- **单一收口入口**：
  - 新增 `BookMediaPurge.kt`，提供顶层统一函数 `purgeBookMediaCaches(sourceId, bookUrl, ...)`。
  - 单本移出（`DELETE /api/bookshelf`）与批量移出（`POST /api/bookshelf/batch action=delete`）统一调用此函数，坚决杜绝分散实现或两条删除逻辑分叉。
- **关键设计守卫**：
  1. **先取消预取任务，再删缓存**：
     - 如果不先 cancel 正在跑的后台协程任务（`MangaPrefetchService.cancel` / `AudioPrefetchService.cancel`），刚删掉的磁盘文件和数据行会被网络拉取协程重新写回，导致删除不彻底。
  2. **引用计数保护共享物理文件**：
     - 复用各媒体 cache 模块的 `deleteBook` 实现（先删本条记录，再通过 `countPaths == 0` 判定物理文件是否孤立），避免多本书引用同一张公共插图或音频分段时造成误删。
  3. **章节清单（`manga_chapter_cache`）必须同步清理**：
     - `pinned`（整本免死金牌标记）存储在章节清单上，若移出书架后保留该表，配额 GC 永远不敢回收该书字节，直接把普通泄漏恶化为永久泄漏。
  4. **每步独立容错（`runCatching`）**：
     - 单类媒体清理异常只记日志，绝不抛出异常阻断移出书架主流程。
  5. **本地书与文本小说安全**：
     - 本地书（`loc_book` / `local://`）正文由 `Database.removeBookshelf` 按既有语义处理，媒体清理返回 0，无副作用。
  6. **日志静默**：
     - 仅当 `total > 0`（确实清理了媒体字节）时记录一行摘要日志，避免纯文本小说移出书架时日志刷屏。

## 3. 验证与测试
- **单测覆盖**：
  - `RemoveShelfMediaPurgeTest`（3 项完备用例）：
    1. `removing a book from the shelf purges its manga audio and illustration caches only`：断言单本移出时，三类媒体表行数清零、磁盘文件删除、正在跑的预取任务取消、旁观书数据与文件完整保留、二次删除幂等；
    2. `purge is idempotent tolerates missing files keeps local content and cancels audio prefetch`：验证幂等性、物理文件已丢失时的容错性、本地书正文不受影响、音频预取任务取消；
    3. `purge keeps a physical file another book still references`：验证共享物理文件时引用计数机制工作正常，未归零前不删物理文件，最后一个引用者离开才回收。
- **全量测试回归**：
  - 前端：`npm --prefix web run check` exit 0，352 项单测全绿；
  - 后端：`./gradlew :server:test` BUILD SUCCESSFUL，全量用例通过，零回归。

## 4. 沉淀的教训与部落知识 (Lessons Learned)
- **[书架/媒体生命周期] 移出书架必须连带清理全生命周期衍生数据**：系统演进中新增的旁路缓存（漫画、音频、插图）若未接进核心生命周期节点（移出书架），就会随着主键删除而成为无法通过界面再触达的死数据。任何新增书籍资产类型时，必须审视其在 `removeBookshelf` 与 `batchBookshelfOperation(delete)` 时的清理连带责任。
- **[缓存清理/时序] 清理缓存必须「先停后台任务，再清盘表」**：后台预取任务以异步协程运行，若先删数据后停任务，网络 IO 完成后的写盘逻辑会瞬间复活刚被删除的磁盘文件。
