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
