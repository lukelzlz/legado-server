package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 备份导出的**设置**与**定时调度**。
 *
 * 三条自动导出路径都挂在总开关 [BackupExportSettings.autoExport] 下，这里逐条验证门禁关系 ——
 * 「总开关关掉时子开关一律不生效」是**服务端**的职责（客户端只上报触发，不决定写盘）。
 */
class BackupExportSchedulerTest {

    private class Fixture(val dataDir: Path, val database: Database, val storage: WebDavStorage, val dbPath: String)

    private fun withFixture(block: (Fixture) -> Unit) {
        val dataDir = Files.createTempDirectory("legado-backup-export")
        val dbPath = dataDir.resolve("legado.sqlite").toString()
        var db: Database? = null
        try {
            db = Database(dbPath)
            db.initialize("test-password-1234")
            block(Fixture(dataDir, db, WebDavStorage(dataDir.resolve("webdav")), dbPath))
        } finally {
            db?.close()
            dataDir.toFile().deleteRecursively()
        }
    }

    private fun scheduler(fixture: Fixture) = BackupExportScheduler(
        fixture.database,
        fixture.storage,
        BackupExporter(fixture.database),
    ) { }

    private fun zipCount(fixture: Fixture, dir: String = DEFAULT_BACKUP_EXPORT_DIR): Int {
        val path = fixture.dataDir.resolve("webdav").resolve(dir)
        if (!Files.isDirectory(path)) return 0
        return Files.list(path).use { stream -> stream.toList() }.count { it.fileName.toString().endsWith(".zip") }.toInt()
    }

    @Test
    fun `settings default to legado folder, web device name and 03 00 daily schedule`() = withFixture { fixture ->
        val settings = readBackupExportSettings(fixture.database)
        assertEquals("legado", settings.exportDir)
        assertEquals("web", settings.deviceName)
        assertEquals("03:00", settings.scheduledTime)
        // 自动导出默认**关**：不主动打开就不该有任何后台写盘
        assertFalse(settings.autoExport)
        assertFalse(settings.scheduledExport)
        assertFalse(settings.exportOnPageClose)
        assertFalse(settings.exportOnBookClose)
    }

    /** 局部更新必须「改哪个存哪个」，否则点一下开关会把用户没保存的输入一起写进去。 */
    @Test
    fun `partial update only touches the provided fields`() = withFixture { fixture ->
        val afterDir = applyBackupExportSettingsUpdate(fixture.database, BackupExportSettingsUpdate(exportDir = "/backup-out/"))
        assertEquals("backup-out", afterDir.exportDir)
        // 其余字段保持默认，没有被这次更新带偏
        assertEquals("web", afterDir.deviceName)
        assertEquals("03:00", afterDir.scheduledTime)

        val afterSwitch = applyBackupExportSettingsUpdate(fixture.database, BackupExportSettingsUpdate(autoExport = true))
        assertEquals("backup-out", afterSwitch.exportDir)
        assertTrue(afterSwitch.autoExport)
    }

    @Test
    fun `scheduled time accepts lenient input and normalizes to HH mm`() {
        assertEquals("03:00", parseScheduledTime("3:00"))
        assertEquals("03:00", parseScheduledTime(" 03:00 "))
        assertEquals("14:30", parseScheduledTime("14:30"))
        // 全角冒号：中文输入法下很容易打出来
        assertEquals("09:05", parseScheduledTime("9：05"))
        assertNull(parseScheduledTime("24:00"))
        assertNull(parseScheduledTime("12:60"))
        assertNull(parseScheduledTime("abc"))
        assertNull(parseScheduledTime(""))
    }

    @Test
    fun `invalid scheduled time is rejected instead of silently stored`() = withFixture { fixture ->
        val error = runCatching {
            applyBackupExportSettingsUpdate(fixture.database, BackupExportSettingsUpdate(scheduledTime = "25:00"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        // 拒绝之后库里仍是默认值，没有被写坏
        assertEquals("03:00", readBackupExportSettings(fixture.database).scheduledTime)
    }

    @Test
    fun `scheduled export stays off while the master switch is off`() = withFixture { fixture -> runBlocking {
            // 定时子开关打开，但总开关关着 ⇒ 不导出
            applyBackupExportSettingsUpdate(
                fixture.database,
                BackupExportSettingsUpdate(scheduledExport = true, scheduledTime = "03:00"),
            )
            assertFalse(scheduler(fixture).tick(LocalDateTime.of(2026, 10, 1, 3, 0)))
            assertEquals(0, zipCount(fixture))
        }
    }

    @Test
    fun `scheduled export runs once at the configured minute and only once per day`() = withFixture { fixture -> runBlocking {
            applyBackupExportSettingsUpdate(
                fixture.database,
                BackupExportSettingsUpdate(autoExport = true, scheduledExport = true, scheduledTime = "14:30"),
            )
            val scheduler = scheduler(fixture)

            // 其他分钟不触发
            assertFalse(scheduler.tick(LocalDateTime.of(2026, 10, 1, 14, 29)))
            assertEquals(0, zipCount(fixture))

            // 到点触发
            assertTrue(scheduler.tick(LocalDateTime.of(2026, 10, 1, 14, 30)))
            assertEquals(1, zipCount(fixture))

            // 同一分钟内再 tick 不该重复导出（调度器 20 秒一跳，必然会重复 tick）
            assertFalse(scheduler.tick(LocalDateTime.of(2026, 10, 1, 14, 30)))

            // 次日同一时刻会再跑一次，且仍是覆盖（同一天只有一份）
            assertTrue(scheduler.tick(LocalDateTime.of(2026, 10, 2, 14, 30)))
            assertEquals("同一天只该留一份", 1, zipCount(fixture))
        }
    }

    @Test
    fun `default device name produces a backup-web file name`() = withFixture { fixture -> runBlocking {
        applyBackupExportSettingsUpdate(fixture.database, BackupExportSettingsUpdate(autoExport = true, scheduledExport = true, scheduledTime = "03:00"))
        assertTrue(scheduler(fixture).tick(LocalDateTime.of(2026, 10, 1, 3, 0)))
        val files = Files.list(fixture.dataDir.resolve("webdav/legado")).use { it.toList() }
        assertEquals(1, files.size)
        val name = files.single().fileName.toString()
        assertTrue("文件名应带默认设备名 web: $name", name.startsWith("backup") && name.endsWith("-web.zip"))
        assertTrue(name.contains(LocalDate.of(2026, 10, 1).toString()))
    } }
}
