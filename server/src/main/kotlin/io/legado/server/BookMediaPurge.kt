package io.legado.server

/**
 * 「书移出书架」时，把这本书的**媒体缓存**（漫画图片 / 有声音频 / 正文插图）按书清干净。
 *
 * ## 为什么必须只有一个入口
 * 除了正文/目录/封面，一本书还会在三个媒体根目录下留字节（`comic/`、`audio/`、`images/`），
 * 并各自有一张索引表（`manga_image_cache` + `manga_chapter_cache`、`audio_cache`、
 * `illustration_cache`）。过去 `DELETE /bookshelf` 只删正文/目录/封面，这三类**连行带盘全部留下**
 * —— 用户看到的就是「书移出书架后漫画，音频，插图没有同步删了」；更糟的是这些字节
 * **再也无法从任何界面找回**（书架里已经没有这本书，缓存管理页只按现存书籍翻），只能占盘到配额 GC。
 *
 * 因此单本移出（`DELETE /bookshelf`）与批量移出（`POST /bookshelf/batch`）都只调这里，
 * 绝不各写一份删除逻辑（"修一条忘一条 = 没修"）。
 *
 * ## 三条硬约束
 * 1. **严格按 `(sourceId, bookUrl)` 定位**，绝不按书名/URL 前缀模糊匹配 ⇒ 不可能误删别的书。
 * 2. **先停任务、再删数据**：还在跑的预取会把图/音频在删除之后重新抓回磁盘，用户会看到"清不干净"。
 * 3. **每一步独立兜底**：任何一类清理失败只记一行日志，绝不阻断"移出书架"本身；
 *    行不存在、文件已被删都是正常路径（各 `deleteBook` 本身按行驱动、天然幂等）。
 *
 * ## 会不会误删"被别人共用的字节"
 * 三个 `deleteBook` 都是**先删行、再按引用计数回收文件**（`count*Paths(...) == 0` 才删物理文件），
 * 所以同一份字节被别的书/别的章引用时不会被删；而"行"按 `source_id + book_url` 精确删除，
 * 别的书的行根本不在删除范围里。
 *
 * ## 本地书（`loc_book` / `local://`）
 * 本地书的正文在 `book_content_cache`，由 [Database.removeBookshelf] 按既有语义处理，**本函数不碰**；
 * 这里只清媒体缓存（本地书通常没有媒体行 ⇒ 全部是 no-op，绝不抛错）。
 */
fun purgeBookMediaCaches(
    sourceId: String,
    bookUrl: String,
    database: Database,
    imageCache: ImageCache,
    audioCache: AudioCache,
    illustrationCache: IllustrationCache? = null,
    mangaPrefetch: MangaPrefetchService? = null,
    audioPrefetch: AudioPrefetchService? = null,
    log: (String) -> Unit = {},
) {
    // ① 先停任务。`cancel` 是幂等的（没有正在跑的任务也算成功），服务为 null 表示预取被关掉，
    //    同样等价于"没有任务在跑"。
    runCatching { mangaPrefetch?.cancel(sourceId, bookUrl) }
        .onFailure { log("book purge: cancel manga prefetch failed: $sourceId $bookUrl (${it.message})") }
    runCatching { audioPrefetch?.cancel(sourceId, bookUrl) }
        .onFailure { log("book purge: cancel audio prefetch failed: $sourceId $bookUrl (${it.message})") }

    // ② 漫画：图片索引 + 章节目录清单。
    //    ⚠️ 清单必须一起删：`pinned`（整本离线免死金牌）就存在这张表上，书都移出书架了还留着它，
    //    GC 就永远不敢回收这本书的字节 —— 等于把"泄漏"升级成"永久泄漏"。
    val mangaImages = runCatching { imageCache.deleteBook(sourceId, bookUrl) }
        .onFailure { log("book purge: manga image cache failed: $sourceId $bookUrl (${it.message})") }
        .getOrDefault(0)
    val mangaChapters = runCatching { database.deleteMangaChapters(sourceId, bookUrl) }
        .onFailure { log("book purge: manga chapter manifest failed: $sourceId $bookUrl (${it.message})") }
        .getOrDefault(0)

    // ③ 有声书：整章音频分段（音频没有单独的清单表，就绪度是 `having` 算出来的）。
    val audioSegments = runCatching { audioCache.deleteBook(sourceId, bookUrl) }
        .onFailure { log("book purge: audio cache failed: $sourceId $bookUrl (${it.message})") }
        .getOrDefault(0)

    // ④ 小说正文插图（缓存实例可为 null ⇒ 该功能未接线，视作没有插图）。
    val illustrations = runCatching { illustrationCache?.deleteBook(sourceId, bookUrl) ?: 0 }
        .onFailure { log("book purge: illustration cache failed: $sourceId $bookUrl (${it.message})") }
        .getOrDefault(0)

    // 只在真的清掉了东西时留一行日志：整架书都是小说时不该刷屏。
    val total = mangaImages + mangaChapters + audioSegments + illustrations
    if (total > 0) {
        log(
            "book purge: $sourceId $bookUrl -> manga images=$mangaImages chapters=$mangaChapters " +
                "audio segments=$audioSegments illustrations=$illustrations",
        )
    }
}
