package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用**真实备份包**验证分组与书签导入。
 *
 * 备份包位于工作区的 `a/data/webdav/legado/backup2026-09-27-PEPM00.zip`，
 * 不在仓库内，因此文件不存在时**跳过**（`assumeTrue`）而不是失败——
 * 这样 CI 上不会因为缺少本地文件而红。
 *
 * ## 真实数据的期望值（已人工核对，**不要照抄 `te/bookshelf.json` 的数字**）
 *
 * `te/bookshelf.json`（434 条）与本备份（220 条）是**两份不同的数据**：
 * ```
 *                te/bookshelf.json   本备份
 * 总条数                434            220
 * 本地图书               14              5
 * 音频（听书）           31              0
 * 在线小说              389            215
 * ```
 * **但分组完全一致**：FQ=27 / 完结=74 / 15=2 —— 分组不随书架条目变化。
 *
 * `bookGroup.json` 16 个分组里有 12 个是空的内置智能分组，只有 3 个有书；
 * `bookmark.json` 47 条里只有 18 条的书还在（389 条那版的）书架上。
 */
class RealBackupGroupAndBookmarkTest {

    private val candidates = listOf(
        "a/data/webdav/legado/backup2026-09-27-PEPM00.zip",
        "../a/data/webdav/legado/backup2026-09-27-PEPM00.zip",
        "E:/Desktop/1234/a/data/webdav/legado/backup2026-09-27-PEPM00.zip",
    )

    private fun findBackup(): Path? = candidates.map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

    @Test
    fun `real backup imports groups and bookmarks with expected counts`() {
        val backup = findBackup()
        assumeTrue("真实备份包不存在，跳过（CI 环境正常）", backup != null)

        val dir = Files.createTempDirectory("real-backup-test")
        val dbPath = dir.resolve("legado.sqlite")
        val db = Database(dbPath.toString())
        db.initialize("test-pass")
        try {
            val summary = BackupImporter(db).import(backup!!)

            // ---- 书架：220 条里过滤掉 5 条本地图书（本备份没有音频）----
            assertEquals("本备份应跳过 5 本本地图书", 5, summary.skippedLocal)
            assertEquals("本备份没有音频条目", 0, summary.skippedAudio)
            assertEquals(
                "在线小说应为 215 本（220 - 5）",
                215,
                summary.books + summary.booksUpdated,
            )

            // ---- 分组：只导入有书的 FQ / 完结 / 15，不导入 12 个空的内置分组 ----
            val groups = db.listBookGroups()
            val names = groups.map { it.name }.toSet()
            assertEquals("只应导入有书的 3 个分组，实际 $names", setOf("FQ", "完结", "15"), names)
            assertEquals("FQ 应有 27 本书", 27, groups.first { it.name == "FQ" }.bookCount)
            assertEquals("完结 应有 74 本书", 74, groups.first { it.name == "完结" }.bookCount)
            assertEquals("15 应有 2 本书", 2, groups.first { it.name == "15" }.bookCount)

            // 空的内置智能分组必须一个都不要
            for (builtIn in listOf(
                "在读", "未读", "已读", "小说", "漫画", "视频",
                "全部", "本地", "音频", "网络未分组", "本地未分组", "更新失败",
            )) {
                assertTrue("内置空分组「$builtIn」不应被导入", builtIn !in names)
            }

            // ---- 书签：只导入书仍在书架上的 ----
            assertEquals(
                "本备份应导入 18 条书签",
                18,
                summary.bookmarks,
            )
            assertEquals("应报告跳过 29 条书签（书已被过滤）", 29, summary.bookmarksSkipped)

            // 抽查一条真实书签确实入库了
            val known = db.listBookmarks("被废修为后，我能否逃离她的魔爪", "小白在放鸽子")
            assertEquals("这本真实书应有 2 条书签", 2, known.size)
            assertNotNull(known.first().chapterName)
        } finally {
            runCatching { db.close() }
            runCatching {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
