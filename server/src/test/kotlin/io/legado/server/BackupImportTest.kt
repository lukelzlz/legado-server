package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 备份包导入：只认备份内的固定文件名，且不因缺失条目而失败。 */
class BackupImportTest {
    @Test
    fun `imports sources replace rules shelf and reading progress`() {
        val directory = Files.createTempDirectory("legado-backup-import")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val archive = directory.resolve("backup2026-07-12-rk3399pro_pcie.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookSource.json" to
                        """[{"bookSourceUrl":"https://example.com/##聚合","bookSourceName":"示例聚合源","enabled":true}]""",
                    "replaceRule.json" to
                        """[{"id":1767407348980,"name":"去广告","pattern":"广告.*","replacement":"","isRegex":true,"isEnabled":true,"order":1,"timeoutMillisecond":3000}]""",
                    "bookshelf.json" to
                        """[{"name":"十日终焉","author":"杀虫队队员","bookUrl":"https://example.com/book/1","origin":"https://example.com/##聚合","tocUrl":"https://example.com/book/1","coverUrl":"https://example.com/cover.jpg","kind":"连载中,7.9分","durChapterIndex":261,"durChapterPos":1334,"durChapterTime":1782343455600}]""",
                    "readRecord.json" to """[{"bookName":"十日终焉","readTime":100}]""",
                ),
            )

            val summary = BackupImporter(database).import(archive)

            assertEquals(1, summary.sources)
            assertEquals(1, summary.rules)
            assertEquals(1, summary.books)
            assertEquals(1, summary.progress)

            // 书源 id 与书架 origin 都必须归一化到同一个 sourceId，否则书架会挂在不存在的书源上。
            val sources = database.listSources(null)
            assertEquals(1, sources.size)
            assertEquals("https://example.com/", sources.first().id)
            assertEquals(1, database.listReplaceRules().size)

            val shelf = database.listBookshelf()
            assertEquals(1, shelf.size)
            assertEquals("十日终焉", shelf.first().name)
            assertEquals("https://example.com/", shelf.first().sourceId)
            assertEquals(261, shelf.first().chapterIndex)

            val progress = database.getProgress("https://example.com/", "https://example.com/book/1")
            assertNotNull(progress)
            assertEquals(261, progress!!.chapterIndex)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 回归：备份导入的书架条目必须把 `coverUrl` 暴露给前端。
     *
     * 备份包只带封面 URL、不带图片本体，导入后 `cover_key` 必为空。
     * 若书架查询不 SELECT `cover_url`，前端既拿不到 key 也拿不到 url，
     * 整架书的封面都会退化成文字占位符（用户可见现象：封面加载不出来）。
     */
    @Test
    fun `shelf items expose coverUrl after backup import`() {
        val directory = Files.createTempDirectory("legado-backup-coverurl")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val archive = directory.resolve("backup.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookshelf.json" to
                        """[{"name":"十日终焉","bookUrl":"https://example.com/book/1","origin":"https://example.com/","tocUrl":"https://example.com/book/1","coverUrl":"https://cdn.example.com/cover.jpg"}]""",
                ),
            )
            BackupImporter(database).import(archive)

            val item = database.listBookshelf().single()
            assertEquals(
                "备份导入的封面 URL 必须透出给前端做回退加载",
                "https://cdn.example.com/cover.jpg",
                item.coverUrl,
            )
            // 无 coverCache 时不会补抓，因此 key 必然为空。
            assertEquals(null, item.coverKey)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 回归：提供 [CoverCache] 时必须把封面抓成本地副本并回写 `cover_key`，
     * 使封面不依赖外部图床（图床挂掉/离线阅读仍可显示）。
     */
    @Test
    fun `backfills cover cache and writes cover key when cover cache is provided`() {
        val directory = Files.createTempDirectory("legado-backup-coverbackfill")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val coverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
            val coverCache = CoverCache(
                directory.resolve("covers"),
                fetcher = { "image/png" to coverBytes },
            )
            val archive = directory.resolve("backup.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookshelf.json" to
                        """[{"name":"十日终焉","bookUrl":"https://example.com/book/1","origin":"https://example.com/","tocUrl":"https://example.com/book/1","coverUrl":"https://cdn.example.com/cover.jpg"}]""",
                ),
            )
            BackupImporter(database, coverCache).import(archive)

            val item = database.listBookshelf().single()
            assertNotNull("应回写 cover_key", item.coverKey)
            assertTrue(
                "cover_key 应为 64 位 sha256 hex，实际：${item.coverKey}",
                item.coverKey!!.matches(Regex("[0-9a-f]{64}")),
            )
            // 缓存文件必须真实落盘，且封面接口能取到。
            assertNotNull("封面文件应已落盘", coverCache.file(item.coverKey!!))
            assertEquals("image/png", database.coverContentType(item.coverKey!!))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * 回归：封面抓取失败绝不能影响导入本身（图床 404/超时是常态）。
     */
    @Test
    fun `cover fetch failure does not break the import`() {
        val directory = Files.createTempDirectory("legado-backup-coverfail")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val coverCache = CoverCache(
                directory.resolve("covers"),
                fetcher = { throw IllegalStateException("图床 404") },
            )
            val archive = directory.resolve("backup.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookshelf.json" to
                        """[{"name":"十日终焉","bookUrl":"https://example.com/book/1","origin":"https://example.com/","tocUrl":"https://example.com/book/1","coverUrl":"https://cdn.example.com/broken.jpg"}]""",
                ),
            )
            val summary = BackupImporter(database, coverCache).import(archive)

            assertEquals("导入本身必须成功", 1, summary.books)
            val item = database.listBookshelf().single()
            assertEquals("抓取失败时 cover_key 保持为空", null, item.coverKey)
            assertEquals(
                "仍应保留 coverUrl 供前端直连回退",
                "https://cdn.example.com/broken.jpg",
                item.coverUrl,
            )
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps newer reading progress instead of the older backup value`() {
        val directory = Files.createTempDirectory("legado-backup-progress")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            database.saveProgress(ReadingProgress("https://example.com/", "https://example.com/book/1", "chapter", 900, 0.5))

            val archive = directory.resolve("backup.zip")
            writeArchive(
                archive,
                mapOf(
                    "bookshelf.json" to
                        """[{"name":"十日终焉","bookUrl":"https://example.com/book/1","origin":"https://example.com/","tocUrl":"https://example.com/book/1","durChapterIndex":12,"durChapterTime":1}]""",
                ),
            )

            val summary = BackupImporter(database).import(archive)

            assertEquals(0, summary.progress)
            assertEquals(900, database.getProgress("https://example.com/", "https://example.com/book/1")!!.chapterIndex)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects archives without any known backup section`() {
        val directory = Files.createTempDirectory("legado-backup-invalid")
        val database = Database(directory.resolve("legado.sqlite").toString())
        try {
            database.initialize("password-for-test")
            val archive = directory.resolve("random.zip")
            writeArchive(archive, mapOf("notes.txt" to "hello"))

            val error = runCatching { BackupImporter(database).import(archive) }.exceptionOrNull()

            assertTrue("unexpected error: $error", error is IllegalArgumentException)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeArchive(target: Path, sections: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            sections.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
