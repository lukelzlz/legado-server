package io.legado.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 备份导出的**设置**与**定时调度**。
 *
 * 设置存在 `app_setting` 表（与 `progress_sync_directory` 同一机制）。三条自动导出路径：
 *
 * | 触发 | 开关 |
 * | :--- | :--- |
 * | 关闭网页 | [BackupExportSettings.autoExport] + [BackupExportSettings.exportOnPageClose] |
 * | 关闭书籍 | [BackupExportSettings.autoExport] + [BackupExportSettings.exportOnBookClose] |
 * | 定时 | [BackupExportSettings.autoExport] + [BackupExportSettings.scheduledExport] + [BackupExportSettings.scheduledTime] |
 *
 * [BackupExportSettings.autoExport] 是**总开关**：它关掉时另外三个子开关一律不生效。
 */
private const val BACKUP_EXPORT_DIR_KEY = "backup_export_dir"
private const val BACKUP_EXPORT_DEVICE_KEY = "backup_export_device"
private const val BACKUP_EXPORT_AUTO_KEY = "backup_export_auto"
private const val BACKUP_EXPORT_ON_PAGE_CLOSE_KEY = "backup_export_on_page_close"
private const val BACKUP_EXPORT_ON_BOOK_CLOSE_KEY = "backup_export_on_book_close"
private const val BACKUP_EXPORT_SCHEDULED_KEY = "backup_export_scheduled"
private const val BACKUP_EXPORT_SCHEDULED_TIME_KEY = "backup_export_scheduled_time"

/** 自动导出的触发点标识（前端上报，服务端据此查对应开关）。 */
internal const val TRIGGER_PAGE_CLOSE = "page"
internal const val TRIGGER_BOOK_CLOSE = "book"

/** 默认导出到 WebDAV 根目录下的 `legado`（与手机端备份的落点习惯一致）。 */
internal const val DEFAULT_BACKUP_EXPORT_DIR = "legado"

/** 默认设备名后缀为 `web`，与手机端（如 `CD_Watch_A`）区分开。 */
internal const val DEFAULT_BACKUP_DEVICE_NAME = "web"

/** 默认定时时间：每天凌晨 3 点（24 小时制）。 */
internal const val DEFAULT_BACKUP_SCHEDULED_TIME = "03:00"

/**
 * 清洗设备名：它会拼进**文件名**，因此必须挡掉路径分隔符与各平台非法字符。
 *
 * 允许中文（用户会用「客厅平板」这类名字），但不允许 `/ \ : * ? " < > |` 与控制字符。
 */
internal fun sanitizeBackupDeviceName(raw: String): String =
    raw.trim()
        .filter { it.code >= 0x20 && it !in "\\/:*?\"<>|" }
        .take(48)
        .trim()

/**
 * 归一化定时时间到 `HH:mm`（24 小时制）。
 *
 * 宽松接受 `3:00` / `03：00`（全角冒号）/ 前后空格，返回 null 表示无法识别。
 * 存库一律是补零后的 `HH:mm`，比较与展示都只认这一种形态。
 */
internal fun parseScheduledTime(raw: String): String? {
    val cleaned = raw.trim().replace('：', ':')
    val match = Regex("""^(\d{1,2}):(\d{1,2})$""").find(cleaned) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
}

internal fun readBackupExportSettings(database: Database): BackupExportSettings = BackupExportSettings(
    exportDir = database.getSetting(BACKUP_EXPORT_DIR_KEY)?.trim()?.trim('/') ?: DEFAULT_BACKUP_EXPORT_DIR,
    deviceName = database.getSetting(BACKUP_EXPORT_DEVICE_KEY) ?: DEFAULT_BACKUP_DEVICE_NAME,
    autoExport = database.getSetting(BACKUP_EXPORT_AUTO_KEY) == "true",
    exportOnPageClose = database.getSetting(BACKUP_EXPORT_ON_PAGE_CLOSE_KEY) == "true",
    exportOnBookClose = database.getSetting(BACKUP_EXPORT_ON_BOOK_CLOSE_KEY) == "true",
    scheduledExport = database.getSetting(BACKUP_EXPORT_SCHEDULED_KEY) == "true",
    scheduledTime = database.getSetting(BACKUP_EXPORT_SCHEDULED_TIME_KEY)
        ?.let(::parseScheduledTime)
        ?: DEFAULT_BACKUP_SCHEDULED_TIME,
)

/**
 * 局部更新：**只写请求里出现过的字段**。
 *
 * 页面上「导出路径」「设备名后缀」各自有独立的保存按钮，勾选类开关则是**勾了就存**，
 * 因此契约必须是「改哪个存哪个」，否则点一下开关会把用户还没保存的输入一起写进去。
 *
 * @throws IllegalArgumentException 导出路径含非法片段，或定时时间无法识别
 */
internal fun applyBackupExportSettingsUpdate(database: Database, update: BackupExportSettingsUpdate): BackupExportSettings {
    update.exportDir?.let { raw ->
        val dir = raw.trim().trim('/')
        // 交给同一个校验口径：越界路径由 WebDavStorage.resolve 判定，这里先做便宜的语法检查
        require(dir.split('/').none { it == ".." || it.contains('\\') || it.contains('\u0000') }) { "导出路径不合法" }
        database.setSetting(BACKUP_EXPORT_DIR_KEY, dir)
    }
    update.deviceName?.let { database.setSetting(BACKUP_EXPORT_DEVICE_KEY, sanitizeBackupDeviceName(it)) }
    update.autoExport?.let { database.setSetting(BACKUP_EXPORT_AUTO_KEY, it.toString()) }
    update.exportOnPageClose?.let { database.setSetting(BACKUP_EXPORT_ON_PAGE_CLOSE_KEY, it.toString()) }
    update.exportOnBookClose?.let { database.setSetting(BACKUP_EXPORT_ON_BOOK_CLOSE_KEY, it.toString()) }
    update.scheduledExport?.let { database.setSetting(BACKUP_EXPORT_SCHEDULED_KEY, it.toString()) }
    update.scheduledTime?.let { raw ->
        val time = parseScheduledTime(raw) ?: throw IllegalArgumentException("定时时间需为 HH:mm（24 小时制）")
        database.setSetting(BACKUP_EXPORT_SCHEDULED_TIME_KEY, time)
    }
    return readBackupExportSettings(database)
}

/** 落盘后的结果（文件名、绝对路径、各部分条数）。 */
internal data class BackupExportOutcome(val result: BackupExporter.Result, val target: Path) {
    fun toPayload(storage: WebDavStorage) = BackupExportResult(
        fileName = result.fileName,
        path = storage.root.relativize(target).toString().replace('\\', '/'),
        size = result.bytes.size.toLong(),
        books = result.books,
        sources = result.sources,
        bookmarks = result.bookmarks,
        groups = result.groups,
    )
}

/**
 * 真正落盘的那一步：**手动导出、特定情况导出、定时导出三条路径共用**，
 * 避免各写一遍覆盖语义。
 *
 * 同名文件（同一天 + 同设备名）**直接覆盖** —— `Files.write` 默认 `CREATE + TRUNCATE_EXISTING`，
 * 因此「同一天产生多个备份」只会留下一份最新的，不会堆一堆副本。
 * 文件名刻意**只到日期**（与真实备份 `backup2026-09-28-CD_Watch_A.zip` 同构），覆盖语义才成立；
 * 若哪天要在文件名里加时分，就必须额外写「删除同日旧文件」的逻辑。
 */
internal suspend fun performBackupExport(
    storage: WebDavStorage,
    exporter: BackupExporter,
    settings: BackupExportSettings,
    today: LocalDate = LocalDate.now(),
): BackupExportOutcome {
    val directory = storage.resolve(settings.exportDir) ?: throw IllegalArgumentException("导出路径不合法")
    val result = exporter.export(settings.deviceName, today)
    val target = directory.resolve(result.fileName)
    Files.createDirectories(directory)
    Files.write(target, result.bytes)
    return BackupExportOutcome(result, target)
}

/**
 * 定时导出调度器。
 *
 * 每 [TICK_MS] 醒一次，看当前本地时间是否正好落在设置的时刻上，且今天还没跑过。
 *
 * 取舍（如实说明）：
 * - 只在**时刻匹配**时触发，不做「错过后补跑」—— 那需要额外落库记住上次执行日，
 *   而备份本身是**幂等覆盖**的（同日只留一份），错过一次的下次触发就会带来最新数据。
 * - 用 `HH:mm` 字符串做去重键而不是 `LocalDateTime`，重启后同一分钟不会重复导出。
 */
class BackupExportScheduler(
    private val database: Database,
    private val storage: WebDavStorage,
    private val exporter: BackupExporter,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    /** 上一次真正执行过的「日期 + 时刻」，避免同一分钟内被多次 tick 重复触发。 */
    private var lastRunKey: String? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { log("scheduled backup export failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    fun stop() { scope.cancel() }

    /**
     * 判断「现在是否该导出」，该导就导。
     *
     * @return 本次是否真的导出了（便于测试与日志判断）
     */
    internal suspend fun tick(now: LocalDateTime = LocalDateTime.now()): Boolean = mutex.withLock {
        val settings = readBackupExportSettings(database)
        // 总开关关掉时，定时子开关一律不生效
        if (!settings.autoExport || !settings.scheduledExport) return@withLock false
        val target = LocalTime.parse(settings.scheduledTime)
        if (now.hour != target.hour || now.minute != target.minute) return@withLock false
        val key = "${now.toLocalDate()} ${settings.scheduledTime}"
        if (lastRunKey == key) return@withLock false
        lastRunKey = key
        val outcome = performBackupExport(storage, exporter, settings, now.toLocalDate())
        log("scheduled backup exported: ${outcome.target.fileName} (${outcome.result.bytes.size} bytes)")
        true
    }

    private companion object {
        /** 20 秒一跳：远小于 1 分钟，因此不会错过任何一个整分。 */
        const val TICK_MS = 20_000L
    }
}
