package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导入的**封面补抓**必须走缓存布局 v3（`书名 (书哈希).扩展名` + `cover_cache.file_path`），
 * 否则直出路由 `GET /api/covers/{key}` 定位不到文件 ⇒ 导入的书架**全部裂图**。
 *
 * ## 这条用例能抓到的真实缺陷
 * 缓存布局 v3 把封面文件名从 `covers/<key>` 改成了可读名，并新增了 `cover_cache.file_path` 列。
 * 「备份导入补抓」是**另一条写入路径**（`BackupImporter.backfillCovers`）。如果它自己拼旧文件名、
 * 或写盘时不回填 `file_path`，就会出现：
 * - `covers/` 下文件名与 v3 规则不一致（直出按 v3 命名找不到）；
 * - `cover_cache.file_path` 为空（快路径失效）；
 * - 结果是「导入后封面全裂」。
 *
 * 因此这里刻意用**另一个 CoverCache 实例**去读（模拟直出路由），只依赖磁盘命名 + 数据库索引
 * ——进程内 `written` 缓存救不了跨实例的读取，能真正证明落盘契约成立。
 */
class BackupImportCoverTest {

    private val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private companion object {
        const val SOURCE_URL = "https://cover.example.com"
        const val BOOK_NAME = "封面测试书"
        const val COVER_URL = "https://img.example.com/cover.png"
        /** 回源（详情页）给出的**新**封面地址：与备份里那条 403 的死链不同。 */
        const val FRESH_COVER_URL = "https://img.example.com/cover-fresh.png"
    }

    private val bookUrl = "$SOURCE_URL/book/1"

    // ------------------------------------------------------------------
    // v3 落盘 + 直出可取
    // ------------------------------------------------------------------

    @Test
    fun `imported cover lands under the v3 name and is readable from a fresh cache instance`() {
        withImport(fetcher = { "image/png" to pngBytes }) { database, coverDir, summary ->
            assertEquals("封面必须补抓成功", 1, summary.coversFetched)
            assertEquals(0, summary.coversFailed)

            val shelf = database.listBookshelf().single()
            assertNotNull("补抓成功必须回写 cover_key", shelf.coverKey)
            val key = shelf.coverKey!!

            // 1) 磁盘命名必须是 v3：`书名 (书哈希).扩展名`
            val files = Files.list(coverDir).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            assertEquals("covers/ 下应恰好一张封面，实际 $files", 1, files.size)
            val fileName = files.single().fileName.toString()
            assertEquals(
                "文件名必须是 v3 的 `书名 (书哈希).png`（自己拼旧命名会让直出定位不到）",
                "封面测试书 (${bookCacheHash(SOURCE_URL, bookUrl)}).png",
                fileName,
            )

            // 2) 索引里的快路径必须非空，且指向那个 v3 文件名
            assertEquals(
                "cover_cache.file_path 必须回填 v3 命名（为空 ⇒ 直出只能靠扫盘兜底）",
                fileName,
                database.coverFilePath(key),
            )

            // 3) **换一个 CoverCache 实例**（= 直出路由那一侧）按 key 取文件，必须能取到真图片
            val reader = CoverCache(coverDir, database)
            val located = reader.file(key)
            assertNotNull("直出路由必须能按 cover_key 定位到封面文件（否则书架全裂）", located)
            val bytes = Files.readAllBytes(located!!)
            assertTrue(
                "取到的必须是真的 PNG 字节（魔数 89 50 4E 47），而不是别的文件或空文件",
                bytes.size > pngMagic.size && pngMagic.indices.all { bytes[it] == pngMagic[it] },
            )
        }
    }

    /** 通过 `getShelfBookByUrl` 的返回也必须是 `cover_key`（前端据此拼 `/api/covers/<key>`）。 */
    @Test
    fun `shelf item exposes the cover key so the frontend can request it`() {
        withImport(fetcher = { "image/png" to pngBytes }) { database, _, _ ->
            val item = database.getShelfBookByUrl(bookUrl)
            assertNotNull("书必须入架", item)
            assertNotNull("书架行必须带 cover_key", item!!.coverKey)
            // 关键断言：cover_key 是**内容哈希**（64 位 hex），不是文件名
            assertTrue(
                "cover_key 必须是 64 位 hex 内容哈希（接口路径就靠它），实际 ${item.coverKey}",
                Regex("[0-9a-f]{64}").matches(item.coverKey!!),
            )
        }
    }

    // ------------------------------------------------------------------
    // 图床失效：不裂图、不影响导入、如实计数
    // ------------------------------------------------------------------

    /**
     * 备份里的 `coverUrl` 常是**带过期签名的图床地址**（实测番茄 `x-expires` 已过期 ⇒ HTTP 403）。
     * 规则：失败**绝不影响导入**（书照常入架、`cover_key` 留空由前端回退文字占位），
     * 但必须**如实报告失败张数**，否则用户只看到"封面全裂"却不知原因。
     */
    @Test
    fun `cover fetch failure is counted and never breaks the import`() {
        withImport(fetcher = { throw IllegalArgumentException("封面上游返回 HTTP 403") }) { database, coverDir, summary ->
            assertEquals("书必须照常导入（封面失败绝不能影响导入）", 1, summary.books)
            assertEquals("失败张数必须如实上报", 1, summary.coversFailed)
            assertEquals("失败时没有成功张数", 0, summary.coversFetched)

            val shelf = database.listBookshelf().single()
            assertNull("抓不到就没有本地副本 ⇒ cover_key 留空，前端回退 coverUrl/文字占位", shelf.coverKey)
            assertEquals("外链必须保留，供前端直连兜底", COVER_URL, shelf.coverUrl)
            assertEquals("失败时不得留下半个文件", 0L, Files.list(coverDir).use { it.count() })
        }
    }

    // ------------------------------------------------------------------
    // 回源：备份地址失效时，回书源详情页取新封面（用户明确要求"自动重新请求添上"）
    // ------------------------------------------------------------------

    /**
     * 备份 `coverUrl` 403，但书源详情页给出**新的** `coverUrl` ⇒ 必须自动补上。
     *
     * 这是用户报的真实场景：番茄等图床的地址带 `x-expires` 签名，备份放久了必然 403，
     * 而书源详情页每次都会给一个**新签名**的地址。
     */
    @Test
    fun `failed backup cover is re-sourced from the book source details`() {
        var detailsCalls = 0
        withImport(
            fetcher = { url -> if (url == COVER_URL) throw IllegalArgumentException("封面上游返回 HTTP 403") else "image/png" to pngBytes },
            fetchDetails = { _, _ ->
                detailsCalls++
                BookDetails(
                    sourceId = SOURCE_URL,
                    name = "详情页给的书名（不该写库）",
                    author = "详情页作者（不该写库）",
                    coverUrl = FRESH_COVER_URL,
                    tocUrl = bookUrl,
                )
            },
        ) { database, coverDir, summary ->
            assertEquals("回源必须被真的调用", 1, detailsCalls)
            assertEquals("书必须导入成功（回源是增强，不是依赖）", 1, summary.books)
            assertEquals("最终拿到封面 ⇒ 记为成功", 1, summary.coversFetched)
            assertEquals("其中 1 张来自回源", 1, summary.coversResourced)
            assertEquals("没有失败", 0, summary.coversFailed)

            val shelf = database.listBookshelf().single()
            assertNotNull("回源成功后必须回写 cover_key", shelf.coverKey)
            val key = shelf.coverKey!!
            val files = Files.list(coverDir).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            val fileName = files.single().fileName.toString()
            assertEquals(
                "回源的封面同样必须按 v3 命名落盘（不能是第二套命名规则）",
                "封面测试书 (${bookCacheHash(SOURCE_URL, bookUrl)}).png",
                fileName,
            )
            assertEquals("file_path 必须非空（直出快路径）", fileName, database.coverFilePath(key))
            // 换一个实例（= 直出路由）按 key 取文件，必须是真 PNG
            val located = CoverCache(coverDir, database).file(key)
            assertNotNull("直出路由必须能按 cover_key 取到回源补上的文件", located)
            val bytes = Files.readAllBytes(located!!)
            assertTrue("必须是真图片字节", pngMagic.indices.all { bytes[it] == pngMagic[it] })
            assertEquals(
                "回源拿到的新地址要写回 cover_url（否则前端外链兜底仍是那条 403 的死链）",
                FRESH_COVER_URL,
                shelf.coverUrl,
            )
            // 导入是**恢复不是校正**：详情页带回来的书名/作者绝不能写库
            assertEquals("书名必须保持备份里的值", BOOK_NAME, shelf.name)
            assertEquals("作者必须保持备份里的值", "作者", shelf.author)
        }
    }

    /** 备份地址与回源**都**失败 ⇒ 不抛异常、书照常入库、计数正确、前端有文字占位可退。 */
    @Test
    fun `cover failure after a failed re-source still imports the book`() {
        withImport(
            fetcher = { throw IllegalArgumentException("封面上游返回 HTTP 403") },
            fetchDetails = { _, _ -> throw IllegalStateException("书源已失效") },
        ) { database, coverDir, summary ->
            assertEquals("两处都失败也必须把书导进来", 1, summary.books)
            assertEquals(0, summary.coversFetched)
            assertEquals("回源失败不算回源成功", 0, summary.coversResourced)
            assertEquals("全部失败要如实报 1 张", 1, summary.coversFailed)
            val shelf = database.listBookshelf().single()
            assertNull("没有任何副本 ⇒ cover_key 必须为空（前端显示文字占位，不裂图）", shelf.coverKey)
            assertEquals("外链仍保留，供前端最后兜底", COVER_URL, shelf.coverUrl)
            assertEquals("失败不得留下半截文件", 0L, Files.list(coverDir).use { it.count() })
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    private fun withImport(
        fetcher: (String) -> Pair<String, ByteArray>,
        fetchDetails: ((String, String) -> BookDetails?)? = null,
        block: (Database, Path, BackupImportSummary) -> Unit,
    ) {
        val directory = Files.createTempDirectory("legado-import-cover")
        val database = Database(directory.resolve("legado.sqlite").toString())
        val coverDir = directory.resolve("covers")
        try {
            database.initialize("password-for-test")
            val cache = CoverCache(coverDir, database = database, fetcher = fetcher)
            val archive = directory.resolve("backup.zip")
            writeArchive(archive)
            val summary = BackupImporter(database, cache, fetchDetails = fetchDetails).import(archive)
            block(database, coverDir, summary)
        } finally {
            database.close()
            val dbPath = directory.resolve("legado.sqlite").toString()
            listOf("", "-wal", "-shm").forEach { runCatching { Files.deleteIfExists(Path.of(dbPath + it)) } }
            runCatching { directory.toFile().deleteRecursively() }
        }
    }

    private fun writeArchive(target: Path) {
        val shelf = """[{"name":"$BOOK_NAME","author":"作者","bookUrl":"$bookUrl","origin":"$SOURCE_URL",""" +
            """"tocUrl":"$bookUrl","coverUrl":"$COVER_URL","type":8,"group":0,"durChapterIndex":1,"durChapterTime":1790000000000}]"""
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("bookSource.json"))
            zip.write("""[{"bookSourceUrl":"$SOURCE_URL","bookSourceName":"封面源","enabled":true}]""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bookshelf.json"))
            zip.write(shelf.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
