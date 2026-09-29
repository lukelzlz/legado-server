package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 用**用户手机的真实备份包**验证：书源分组确实随备份一起进来了。
 *
 * 备份包位于仓库之外的工作区里，因此文件不存在时**跳过**（`assumeTrue`）而不是失败 ——
 * CI 上没有这份文件是正常的（与 [RealBackupGroupAndBookmarkTest] 同一套约定）。
 *
 * 本次数据（`backup2026-07-12-rk3399pro_pcie.zip`）的特征：
 * `bookSource.json` 只有 **1 个**书源（`🍅大灰狼聚合5.5.22(vip完全版)`，聚合源），
 * 它的 `bookSourceGroup` 是 `大灰狼聚合` —— 也就是说这份备份里**唯一**的书源分组就是它。
 * 这里刻意不写死「只有 1 个分组」以外的强假设，只锁定「分组被带进来了、且能被按组搜到」。
 */
class RealBackupSourceGroupTest {

    private val candidates = listOf(
        "../backup2026-07-12-rk3399pro_pcie.zip", // Gradle 测试的工作目录是 legado-server/
        "backup2026-07-12-rk3399pro_pcie.zip", // 工作目录是工作区根（legado/）
        "../../backup2026-07-12-rk3399pro_pcie.zip", // 工作目录是 legado-server/server/
    )

    private fun findBackup(): Path? = candidates.map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

    @Test
    fun `real phone backup carries book source groups into searchable groups`() {
        val backup = findBackup()
        assumeTrue("真实备份包不存在，跳过（CI 环境正常）", backup != null)

        val dir = Files.createTempDirectory("real-backup-source-group")
        val database = Database(dir.resolve("legado.sqlite").toString())
        database.initialize("test-pass")
        try {
            val summary = BackupImporter(database).import(backup!!)

            assertTrue("这份备份至少带进来一个书源", summary.sources > 0)
            assertTrue("书源分组必须随备份一起导入", summary.sourceGroups > 0)

            val groups = database.listSourceGroups()
            assertTrue(
                "备份里书源的 bookSourceGroup 应成为可搜的分组，实际=${groups.map { it.name }}",
                groups.any { it.name == "大灰狼聚合" },
            )

            // 每个分组报出的数量，必须与「按该分组取搜索范围」的结果完全一致，
            // 否则前端下拉里显示的条数就是骗人的。
            for (group in groups) {
                assertEquals(
                    "分组「${group.name}」的启用数应与按组搜索选中的书源数一致",
                    group.enabledCount,
                    database.listSearchSourceRecords(null, group.name).size,
                )
            }

            // 未分组范围：剩下的书源数 = 总数 - 各组已启用数（本服务只把已启用的书源纳入搜索）
            val ungrouped = database.listSearchSourceRecords(null, SourceGroupFilter.UNGROUPED)
            assertTrue("未分组范围也必须可用（返回值可以为 0，但不能抛异常）", ungrouped.size >= 0)
            assertEquals(
                database.listSearchSourceRecords(null, null).size - groups.sumOf { it.enabledCount },
                ungrouped.size,
            )
        } finally {
            database.close()
            dir.toFile().deleteRecursively()
        }
    }
}
