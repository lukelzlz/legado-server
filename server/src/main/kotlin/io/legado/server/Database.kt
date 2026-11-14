package io.legado.server

import java.net.URI

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sqlite.SQLiteConfig
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.withLock

/**
 * 数据层（两种引擎共用一套实现）。
 *
 * 引擎差异**只有两处**，都在构造/连接阶段决定，业务代码（280+ 处 `prepareStatement`）无感知：
 * 1. **连接**：sqlite 用本地文件（[SQLiteConfig] 的 WAL/超时设置不变），
 *    postgres 用 [DatabaseSpec] 的 JDBC 串，并把连接包进 [DialectConnection]；
 * 2. **DDL**：sqlite 原样执行 [DatabaseSchema.SQLITE_DDL]，外部库执行按方言转换后的语句。
 *
 * 事务语义与引擎无关：`write {}` 下面那段 `autoCommit = false → commit/rollback → autoCommit = true`
 * 是既有写法（`saveSource` 等 20 多处），两家 JDBC 驱动语义一致，因此**没有**引入行为差异。
 */
class Database internal constructor(private val spec: DatabaseSpec) : Closeable, AutoCloseable {
    /** 便捷构造（保留给全部既有测试与 `reset-password`）：等同于一个 sqlite 规格。 */
    constructor(path: String) : this(DatabaseSpec.sqlite(path))

    private val engine: DatabaseEngine = spec.engine
    private val random = SecureRandom()
    private val writeLock = ReentrantLock()
    private val readPool = ConcurrentLinkedQueue<Connection>()
    private val maxPoolSize = 16
    @Volatile private var isClosed = false
    private var _writeConnection: Connection? = null

    private val sqliteConfig = SQLiteConfig().apply {
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        setBusyTimeout(10000)
        enforceForeignKeys(true)
    }

    init {
        if (!engine.isExternal) {
            Files.createDirectories(Path.of(spec.path).toAbsolutePath().parent)
        }
    }

    private fun createConnection(): Connection = if (engine.isExternal) {
        DialectConnection(spec.openExternalConnection(), engine)
    } else {
        sqliteConfig.createConnection("jdbc:sqlite:${spec.path}")
    }

    private fun getWriteConnection(): Connection {
        var conn = _writeConnection
        if (conn == null || conn.isClosed) {
            conn = createConnection()
            _writeConnection = conn
        }
        return conn
    }

    private fun <T> connect(block: (Connection) -> T): T {
        check(!isClosed) { "Database is closed" }
        var connection = readPool.poll()?.takeIf { !it.isClosed } ?: createConnection()
        return try {
            val result = block(connection)
            if (!isClosed && !connection.isClosed && readPool.size < maxPoolSize) {
                readPool.offer(connection)
            } else {
                runCatching { connection.close() }
            }
            result
        } catch (error: Throwable) {
            runCatching { connection.close() }
            if (error is java.sql.SQLException && !isClosed) {
                val fresh = createConnection()
                try {
                    val result = block(fresh)
                    if (!isClosed && !fresh.isClosed && readPool.size < maxPoolSize) {
                        readPool.offer(fresh)
                    } else {
                        runCatching { fresh.close() }
                    }
                    result
                } catch (retryError: Throwable) {
                    runCatching { fresh.close() }
                    throw retryError
                }
            } else {
                throw error
            }
        }
    }

    private fun <T> write(block: (Connection) -> T): T = writeLock.withLock {
        check(!isClosed) { "Database is closed" }
        block(getWriteConnection())
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        writeLock.withLock {
            _writeConnection?.let { conn -> runCatching { conn.close() } }
            _writeConnection = null
        }
        while (true) {
            val conn = readPool.poll() ?: break
            runCatching { conn.close() }
        }
    }

    /**
     * 启动初始化：**先建库（外部库）再建表**。
     *
     * 「库不存在就自动建库」只在这里做一次（PG 连 `postgres` 库执行 `create database` 并吞掉已存在）。
     */
    fun initialize(initialPassword: String?) {
        spec.ensureDatabase()
        initializeSchema(initialPassword)
    }

    private fun initializeSchema(initialPassword: String?) = write { db ->
        if (engine == DatabaseEngine.SQLITE) {
            // SQLite：pragma 与建表脚本**原样**执行（一个字都不改，保证既有测试零回归）
            db.createStatement().use { statement ->
                statement.execute("pragma journal_mode = wal")
                statement.executeUpdate(DatabaseSchema.SQLITE_DDL)
            }
        } else {
            // PostgreSQL：同一份 schema 按方言转换后逐条执行（见 DatabaseSchema）。
            // 「库不存在就自动建库」发生在更早的 DatabaseSpec.ensureDatabase。
            applyExternalSchema(db, engine) { message -> println(message) }
        }
        migrateBookGroup(db)
        migrateReadingProgress(db)
        migrateBookshelf(db)
        migrateSourceTable(db)
        migrateSubscriptionTable(db)
        migrateBookContentCache(db)
        migrateMangaImageCache(db)
        migrateCacheFilePaths(db)
        healLocalBookCacheStatus(db)
        // 「目录搜索」的存量回填（新增能力，老库的 book_toc_cache 里已经有几千本的目录）：
        // 限量 50 本/次、幂等、失败静默 —— 启动路径绝不能因为一本书的坏 JSON 起不来。
        runCatching { backfillChapterIndex(db, CHAPTER_INDEX_BACKFILL_LIMIT) }
            .onFailure { error -> println("[db] 章节索引回填失败（忽略）：${error.message}") }
        val userExists = db.prepareStatement("select 1 from app_user where id = 1").use { it.executeQuery().next() }
        if (!userExists) {
            require(!initialPassword.isNullOrBlank()) { "首次启动必须提供 ADMIN_PASSWORD" }
            db.prepareStatement("insert into app_user(id, password_hash) values(1, ?)").use {
                it.setString(1, passwordHash(initialPassword))
                it.executeUpdate()
            }
        }
    }

    fun hasPassword(): Boolean = connect { db ->
        db.prepareStatement("select 1 from app_user where id = 1").use { it.executeQuery().next() }
    }

    fun verifyPassword(password: String): Boolean = connect { db ->
        db.prepareStatement("select password_hash from app_user where id = 1").use { query ->
            query.executeQuery().use { result -> result.next() && verifyPassword(result.getString(1), password) }
        }
    }

    fun resetPassword(password: String) = write { db ->
        db.prepareStatement("update app_user set password_hash = ? where id = 1").use {
            it.setString(1, passwordHash(password))
            it.executeUpdate()
        }
        db.createStatement().use { it.executeUpdate("delete from session") }
    }

    fun createSession(now: Long = System.currentTimeMillis()): UserSession {
        val id = secret(); val csrf = secret()
        write { db -> db.prepareStatement("insert into session(id, csrf_token, expires_at) values(?, ?, ?)").use {
            it.setString(1, id); it.setString(2, csrf); it.setLong(3, now + SESSION_TTL); it.executeUpdate()
        } }
        return UserSession(id)
    }

    fun csrfFor(session: UserSession, now: Long = System.currentTimeMillis()): String? = connect { db ->
        db.prepareStatement("select csrf_token from session where id = ? and expires_at > ?").use {
            it.setString(1, session.id); it.setLong(2, now); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun hasValidCsrfToken(csrfToken: String, now: Long = System.currentTimeMillis()): Boolean = connect { db ->
        db.prepareStatement("select 1 from session where csrf_token = ? and expires_at > ?").use { stmt ->
            stmt.setString(1, csrfToken)
            stmt.setLong(2, now)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    fun deleteSession(session: UserSession) = write { db -> db.prepareStatement("delete from session where id = ?").use { it.setString(1, session.id); it.executeUpdate() } }

    fun listSources(query: String?): List<SourceSummary> = connect { db ->
        val sql = if (query.isNullOrBlank()) {
            "select id, name, source_url, source_group, enabled, is_js, updated_at, version, has_login from source order by name collate nocase"
        } else {
            "select id, name, source_url, source_group, enabled, is_js, updated_at, version, has_login from source where name like ? or source_url like ? order by name collate nocase"
        }
        db.prepareStatement(sql).use { statement ->
            if (!query.isNullOrBlank()) { statement.setString(1, "%$query%"); statement.setString(2, "%$query%") }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SourceSummary(
                                id = rs.getString(1),
                                name = rs.getString(2),
                                url = rs.getString(3),
                                group = rs.getString(4),
                                enabled = rs.getInt(5) == 1,
                                isJsSource = rs.getInt(6) == 1,
                                hasLogin = rs.getInt(9) == 1,
                                updatedAt = rs.getLong(7),
                                version = rs.getLong(8),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 书库搜索范围选择器的候选书源查询。
     *
     * 只筛选已启用书源，按名称、地址或分组匹配。这里刻意不加 limit：用户要求
     * 查询结果完整返回，前端通过关键词或分组缩小范围，而不是静默截断候选。
     */
    fun searchSourcePicker(query: String?, groups: List<String> = emptyList()): SourcePickerResponse = connect { db ->
        val conditions = mutableListOf("enabled = 1")
        val params = mutableListOf<String>()
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        if (keyword != null) {
            conditions += "(name like ? collate nocase or source_url like ? collate nocase or coalesce(source_group, '') like ? collate nocase)"
            val pattern = "%$keyword%"
            params += pattern
            params += pattern
            params += pattern
        }
        val groupNames = groups.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (groupNames.isNotEmpty()) {
            val groupConditions = mutableListOf<String>()
            groupNames.forEach { group ->
                if (group == SourceGroupFilter.UNGROUPED) {
                    groupConditions += "(source_group is null or trim(source_group) = '')"
                } else {
                    groupConditions += "source_group = ? collate nocase"
                    params += group
                }
            }
            conditions += "(${groupConditions.joinToString(" or ")})"
        }
        val sql = "select id, name, source_url, source_group, enabled, is_js, updated_at, version, has_login from source where ${conditions.joinToString(" and ")} order by name collate nocase"
        val items = db.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SourceSummary(
                                id = rs.getString(1),
                                name = rs.getString(2),
                                url = rs.getString(3),
                                group = rs.getString(4),
                                enabled = rs.getInt(5) == 1,
                                isJsSource = rs.getInt(6) == 1,
                                hasLogin = rs.getInt(9) == 1,
                                updatedAt = rs.getLong(7),
                                version = rs.getLong(8),
                            )
                        )
                    }
                }
            }
        }
        SourcePickerResponse(items = items, total = items.size)
    }

    /** 列出所有配置了有效 exploreUrl 的书源 */
    fun listExploreSources(): List<ExploreSourceItem> = connect { db ->
        val sql = """
            select id, name, source_group, enabled
            from source
            where json_extract(payload, '$.exploreUrl') is not null
              and trim(json_extract(payload, '$.exploreUrl')) != ''
            order by enabled desc, name collate nocase
        """.trimIndent()
        db.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ExploreSourceItem(
                                id = rs.getString(1),
                                name = rs.getString(2),
                                group = rs.getString(3),
                                enabled = rs.getInt(4) == 1,
                            )
                        )
                    }
                }
            }
        }
    }

    fun getSource(id: String): SourceRecord? = connect { db ->
        db.prepareStatement("select id, payload, version, updated_at, source_group from source where id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                if (rs.next()) {
                    val rawPayload = rs.getString(2)
                    val sourceGroup = rs.getString(5)
                    val syncedPayload = SourceCodec.withGroup(rawPayload, sourceGroup)
                    SourceRecord(rs.getString(1), syncedPayload, rs.getLong(3), rs.getLong(4))
                } else null
            }
        }
    }

    /**
     * 书源分组概览：按 `source_group` 聚合，供「按分组搜书」的下拉与分组管理使用。
     *
     * - 只返回**真实分组**：`source_group` 为 null / 全空白的书源是「未分组」，
     *   它不是分组本身，由前端单独提供选项，这里不合成一行假数据。
     * - 同时给出总数与已启用数：搜索只会用到已启用的书源，两个数字都摆出来，
     *   用户看到「某分组 12 个源」时不会因为其中 5 个被停用而困惑。
     * - 分组名按 `trim()` 后聚合，避免导入数据里带尾空格的分组名被拆成两组。
     * - 聚合用 `collate nocase`：范围过滤（[listSearchSourceRecords]）本身就是大小写不敏感的，
     *   若这里区分大小写，列表会把 `News`/`news` 显示成两组、而点选任一组都会同时命中两者。
     */
    fun listSourceGroups(): List<SourceGroupSummary> = connect { db ->
        db.prepareStatement(
            """
            select min(trim(source_group)) as group_name,
                   count(*) as source_count,
                   sum(case when enabled = 1 then 1 else 0 end) as enabled_count
            from source
            where source_group is not null and trim(source_group) <> ''
            group by lower(trim(source_group))
            order by group_name
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(SourceGroupSummary(name = rs.getString(1), sourceCount = rs.getInt(2), enabledCount = rs.getInt(3)))
                    }
                }
            }
        }
    }

    /**
     * 书源分组改名：把整个分组的书源一次性挂到新名字下。
     *
     * - 目标分组已存在时**等价于合并**（不做重名拦截）：书源分组不像书架分组那样是用户手建的实体，
     *   它只是书源上的一个字符串，合并是两个分组「同名」的必然结果，拦下来反而要用户先删空另一组。
     * - 名字大小写不敏感（`collate nocase`），与 [listSourceGroups] 的展示口径一致。
     * - 严禁使用系统保留字 [SourceGroupFilter.UNGROUPED] 作为目标分组名。
     *
     * @return 受影响的书源数（0 表示原分组不存在）。
     */
    fun renameSourceGroup(from: String, to: String): Int {
        val target = to.trim()
        require(!target.equals(SourceGroupFilter.UNGROUPED, ignoreCase = true)) {
            "不能使用系统保留字「$target」作为分组名称"
        }
        return write { db ->
            db.prepareStatement("update source set source_group = ?, updated_at = ? where source_group = ? collate nocase").use { stmt ->
                stmt.setString(1, target)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setString(3, from.trim())
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 删除书源分组：**只解绑、不删书源**（书源退化为未分组）。
     *
     * 与书架分组删除语义对齐（见 [deleteBookGroup]）：分组消失绝不能让书源跟着消失，
     * 否则一次误点会静默丢掉整组书源。
     *
     * @return 受影响的书源数（0 表示分组不存在）。
     */
    fun clearSourceGroup(name: String): Int = write { db ->
        db.prepareStatement("update source set source_group = null, updated_at = ? where source_group = ? collate nocase").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    /**
     * 取出用于一次搜索的书源。
     *
     * @param sourceIds 只搜这些书源（null / 空表示不限）。
     * @param group     只搜这个书源分组；[SourceGroupFilter.UNGROUPED] 表示「未分组」。
     *                  空白表示不限。与 [sourceIds] 同时给出时按 AND 取交集。
     */
    fun listSearchSourceRecords(sourceIds: List<String>?, group: String? = null): List<SourceRecord> = connect { db ->
        val ids = sourceIds?.distinct()?.takeIf { it.isNotEmpty() }
        val conditions = mutableListOf("enabled = 1")
        val params = mutableListOf<String>()
        if (ids != null) {
            conditions += "id in (${ids.joinToString(",") { "?" }})"
            params += ids
        }
        val groupName = group?.trim()?.takeIf { it.isNotBlank() }
        if (groupName != null) {
            if (groupName == SourceGroupFilter.UNGROUPED) {
                conditions += "(source_group is null or trim(source_group) = '')"
            } else {
                conditions += "source_group = ? collate nocase"
                params += groupName
            }
        }
        val sql = "select id, payload, version, updated_at from source where ${conditions.joinToString(" and ")} order by name collate nocase"
        db.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(SourceRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))) } }
        }
    }

    /**
     * 解析新的搜索范围协议：分组与单个书源按并集搜索，结果按书源 id 去重。
     *
     * 旧版 [listSearchSourceRecords] 保留其「sourceIds 与 group 取交集」语义，供旧客户端
     * 与既有调用方继续使用；新 HTTP/WS 搜索统一调用本方法。按 500 个 id 分块查询，避免
     * SQLite 参数数量限制阻断大量单独书源的组合搜索。
     */
    fun listSearchSourceRecordsForScope(
        sourceIds: List<String>?,
        groups: List<String> = emptyList(),
        legacyGroup: String? = null,
    ): List<SourceRecord> = connect { db ->
        val ids = sourceIds?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val groupNames = (groups + listOfNotNull(legacyGroup))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val result = linkedMapOf<String, SourceRecord>()

        fun read(sql: String, params: List<String>) {
            db.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getString(1)
                        result.putIfAbsent(id, SourceRecord(id, rs.getString(2), rs.getLong(3), rs.getLong(4)))
                    }
                }
            }
        }

        if (groupNames.isNotEmpty()) {
            val groupConditions = mutableListOf<String>()
            val params = mutableListOf<String>()
            groupNames.forEach { group ->
                if (group == SourceGroupFilter.UNGROUPED) {
                    groupConditions += "(source_group is null or trim(source_group) = '')"
                } else {
                    groupConditions += "source_group = ? collate nocase"
                    params += group
                }
            }
            read(
                "select id, payload, version, updated_at from source where enabled = 1 and (${groupConditions.joinToString(" or ")}) order by name collate nocase",
                params,
            )
        }
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            read(
                "select id, payload, version, updated_at from source where enabled = 1 and id in ($placeholders) order by name collate nocase",
                chunk,
            )
        }
        if (groupNames.isEmpty() && ids.isEmpty()) {
            read(
                "select id, payload, version, updated_at from source where enabled = 1 order by name collate nocase",
                emptyList(),
            )
        }
        result.values.toList()
    }

    fun saveSource(parsed: ParsedSource, expectedVersion: Long?): SourceRecord = write { db ->
        db.autoCommit = false
        try {
            val current = db.prepareStatement("select version from source where id = ?").use { it.setString(1, parsed.id); it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null } }
            if (expectedVersion != null && current != expectedVersion) throw VersionConflict()
            val version = (current ?: 0) + 1; val now = System.currentTimeMillis()
            db.prepareStatement("""insert into source(id,name,source_url,source_group,enabled,is_js,payload,version,updated_at,has_login)
                values(?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set name=excluded.name,source_url=excluded.source_url,source_group=excluded.source_group,enabled=excluded.enabled,is_js=excluded.is_js,payload=excluded.payload,version=excluded.version,updated_at=excluded.updated_at,has_login=excluded.has_login""").use {
                it.setString(1, parsed.id); it.setString(2, parsed.name); it.setString(3, parsed.url); it.setString(4, parsed.group); it.setInt(5, if (parsed.enabled) 1 else 0); it.setInt(6, if (parsed.isJs) 1 else 0); it.setString(7, parsed.json); it.setLong(8, version); it.setLong(9, now); it.setInt(10, if (parsed.hasLogin) 1 else 0); it.executeUpdate()
            }
            db.commit(); SourceRecord(parsed.id, parsed.json, version, now)
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }

    fun deleteSource(id: String): Boolean = write { db -> db.prepareStatement("delete from source where id = ?").use { it.setString(1, id); it.executeUpdate() == 1 } }

    fun batchUpdateSources(ids: List<String>, action: String, group: String? = null): Int {
        val distinctIds = ids.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var affectedTotal = 0
        write { db ->
            db.autoCommit = false
            try {
                distinctIds.chunked(200).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    when (action) {
                        "enable" -> {
                            val sql = "update source set enabled = 1, updated_at = ? where id in ($placeholders)"
                            db.prepareStatement(sql).use { stmt ->
                                stmt.setLong(1, now)
                                chunk.forEachIndexed { i, id -> stmt.setString(i + 2, id) }
                                affectedTotal += stmt.executeUpdate()
                            }
                        }
                        "disable" -> {
                            val sql = "update source set enabled = 0, updated_at = ? where id in ($placeholders)"
                            db.prepareStatement(sql).use { stmt ->
                                stmt.setLong(1, now)
                                chunk.forEachIndexed { i, id -> stmt.setString(i + 2, id) }
                                affectedTotal += stmt.executeUpdate()
                            }
                        }
                        "set_group" -> {
                            val targetGroup = group?.trim()?.takeIf { it.isNotBlank() }
                            require(targetGroup == null || !targetGroup.equals(SourceGroupFilter.UNGROUPED, ignoreCase = true)) {
                                "不能使用系统保留字「$targetGroup」作为分组名称"
                            }
                            val sql = "update source set source_group = ?, updated_at = ? where id in ($placeholders)"
                            db.prepareStatement(sql).use { stmt ->
                                stmt.setString(1, targetGroup)
                                stmt.setLong(2, now)
                                chunk.forEachIndexed { i, id -> stmt.setString(i + 3, id) }
                                affectedTotal += stmt.executeUpdate()
                            }
                        }
                        "delete" -> {
                            val delSql = "delete from source where id in ($placeholders)"
                            db.prepareStatement(delSql).use { stmt ->
                                chunk.forEachIndexed { i, id -> stmt.setString(i + 1, id) }
                                affectedTotal += stmt.executeUpdate()
                            }
                            val delLoginSql = "delete from source_login_state where source_id in ($placeholders)"
                            db.prepareStatement(delLoginSql).use { stmt ->
                                chunk.forEachIndexed { i, id -> stmt.setString(i + 1, id) }
                                stmt.executeUpdate()
                            }
                        }
                        else -> throw IllegalArgumentException("不支持的批量操作: $action")
                    }
                }
                db.commit()
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
        return affectedTotal
    }
    fun saveProgress(progress: ReadingProgress): ReadingProgress = write { db ->
        val now = System.currentTimeMillis()
        // `position_ms` 用 `coalesce(excluded, ...)` 而不是直接覆盖：
        // 小说/漫画的写入路径从不带这个字段（null），绝不能因为一次文本阅读把音频的毫秒进度抹掉。
        db.prepareStatement("""insert into reading_progress(source_id,book_url,chapter_url,chapter_index,scroll_position,position_ms,updated_at) values(?,?,?,?,?,?,?)
            on conflict(source_id,book_url) do update set chapter_url=excluded.chapter_url,chapter_index=excluded.chapter_index,scroll_position=excluded.scroll_position,position_ms=coalesce(excluded.position_ms,reading_progress.position_ms),updated_at=excluded.updated_at""").use {
            it.setString(1, progress.sourceId); it.setString(2, progress.bookUrl); it.setString(3, progress.chapterUrl); it.setInt(4, progress.chapterIndex); it.setDouble(5, progress.scrollPosition)
            progress.positionMs?.let { value -> it.setLong(6, value) } ?: it.setNull(6, java.sql.Types.INTEGER)
            it.setLong(7, now); it.executeUpdate()
        }
        db.prepareStatement("update book_shelf set last_read_at=? where source_id=? and book_url=?").use {
            it.setLong(1, now); it.setString(2, progress.sourceId); it.setString(3, progress.bookUrl); it.executeUpdate()
        }
        progress.copy(updatedAt = now)
    }
    fun getProgress(sourceId: String, bookUrl: String): ReadingProgress? = connect { db -> db.prepareStatement("select source_id,book_url,chapter_url,chapter_index,scroll_position,updated_at,position_ms from reading_progress where source_id=? and book_url=?").use {
        it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) ReadingProgress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getLong(6), positionMs = rs.readPositionMs(7)) else null }
    } }

    /** 读取服务端设置（前端设置存 localStorage，但**服务端自己用的**配置必须可持久化）。 */
    fun getSetting(key: String): String? = connect { db ->
        db.prepareStatement("select value from app_setting where key=?").use {
            it.setString(1, key); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun setSetting(key: String, value: String) = write { db ->
        db.prepareStatement("""insert into app_setting(key,value,updated_at) values(?,?,?)
            on conflict(key) do update set value=excluded.value,updated_at=excluded.updated_at""").use {
            it.setString(1, key); it.setString(2, value); it.setLong(3, System.currentTimeMillis()); it.executeUpdate()
        }
    }
    fun saveBookshelf(request: BookshelfWriteRequest, cover: CachedCover?): BookshelfItem = write { db ->
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            cover?.let { value -> db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use { it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate() } }
            val altJson = request.alternateSources?.let { Json.encodeToString(it) }
            val cleanGroup = request.groupName?.trim()?.takeIf { it.isNotEmpty() }
            // 书名入库前统一清洗：去掉换行（目录页规则常把「最新章节标题」带进书名，
            // 实测会出现 `"书名\n第八十章 …"` 这种脏数据，见 SESSION-027）。
            val cleanName = sanitizeBookName(request.name).ifBlank { request.name.trim() }
            // cover_url 与 cover_key 的「缺省」语义必须对齐成**保留**。
            //
            // 原先 cover_url 是 `excluded.cover_url` 无条件覆盖，而 cover_key 有 coalesce 保护 ——
            // 这个不对称会造成不可逆的数据损失：前端每次开书都会调一次「加入书架」
            // （main.tsx 的 openReader），而它传的 coverUrl 对「只靠外链、没有本地副本」的书是 undefined
            // ⇒ cover_url 被抹成 NULL ⇒ 该书的封面引用彻底消失，返回书架就再也看不到封面。
            // 实测（2026-10-03）已因此弄丢 2 本书的封面。缺省一律视为「保留」。
            //
            // cover_key 反过来改成「已有副本优先」：重复加入书架（例如再次从书源页打开）不该顶掉
            // 已经物化好的封面，手动选好的封面同理。
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources,group_name,book_kind) values(?,?,?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),cover_key=coalesce(book_shelf.cover_key,excluded.cover_key),last_read_at=excluded.last_read_at,alternate_sources=coalesce(excluded.alternate_sources,book_shelf.alternate_sources),group_name=coalesce(excluded.group_name,book_shelf.group_name),book_kind=coalesce(excluded.book_kind,book_shelf.book_kind)""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, cleanName); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl?.trim()?.takeIf { u -> u.isNotEmpty() && !isSelfCoverReference(u, cover?.key) }); it.setString(7, cover?.key); it.setLong(8, now); it.setString(9, altJson); it.setString(10, cleanGroup); it.setString(11, request.bookKind); it.executeUpdate()
            }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!!
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    /** 静默回填书架类型（用于解析器在后端识别到漫画时的幂等修正）。 */
    fun setBookKindSilently(sourceId: String, bookUrl: String, kind: String = "comic") = write { db ->
        db.prepareStatement("update book_shelf set book_kind=? where source_id=? and book_url=?").use {
            it.setString(1, kind); it.setString(2, sourceId); it.setString(3, bookUrl); it.executeUpdate()
        }
    }

    /**
     * 读书架上的书籍类型（`comic` / `audio` / `local` / `novel`）。
     *
     * ⚠️ **不在书架上不是错误**：调用方要的是"这本书是不是漫画"，
     * 不在书架上（例如从书源页直接打开）一律按 `novel` 处理，与 `BookshelfItem.bookKind` 的默认值同源。
     */
    fun getBookKind(sourceId: String, bookUrl: String): String = rawBookKind(sourceId, bookUrl) ?: "novel"

    /**
     * 原始 `book_kind` 列（**不做默认值补齐**）：`null` / 空串表示"未分类"。
     *
     * `getBookKind` 会把未分类补成 `novel`，于是**分不清"没分类"与"明确是小说"**。
     * 判断"导入到底写没写这一列"（即"已经一样就跳过"是否真的跳过）必须看原始值。
     */
    fun rawBookKind(sourceId: String, bookUrl: String): String? = connect { db ->
        db.prepareStatement("select book_kind from book_shelf where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.trim()?.takeIf { value -> value.isNotEmpty() } else null }
        }
    }

    /**
     * 这本书是否还在书架上。
     *
     * GC 的顺位 2 用它判「弃坑且已移出书架」；**查不到时调用方当作"在架"**（保守：不删比误删安全）。
     */
    fun shelfHasBook(sourceId: String, bookUrl: String): Boolean = connect { db ->
        db.prepareStatement("select 1 from book_shelf where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> rs.next() }
        }
    }

    /**
     * 全部阅读锚点（GC 用：判断"当前读到哪一章"）。
     *
     * 一次性取回而不是逐本书查：GC 需要给**每本有缓存的书**算保护窗口，
     * 逐本 `getProgress` 是 N+1 次连接，在一堆书命中 GC 时会把读路径拖慢。
     */
    fun listReadingAnchors(): List<ReadingProgress> = connect { db ->
        db.prepareStatement("select source_id,book_url,chapter_url,chapter_index,scroll_position,updated_at,position_ms from reading_progress").use {
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ReadingProgress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getLong(6), positionMs = rs.readPositionMs(7)))
                    }
                }
            }
        }
    }

    /**
     * 读可空的 `position_ms`。
     *
     * ⚠️ AGENTS.md 铁律：**严禁用可空列做存在性 Elvis 判断**。这里读的是"字段可能为 NULL"，
     * 因此必须走 `getObject` 判 null，而不是 `getLong` 得到 0 再当成"有值"——
     * 0 毫秒是**合法进度**（章首），与"没有毫秒进度"是两件事。
     */
    private fun java.sql.ResultSet.readPositionMs(column: Int): Long? =
        (getObject(column) as? Number)?.toLong()

    // ------------------------------------------------------------------
    // 漫画链路 · 章节清单（L2）
    // ------------------------------------------------------------------

    fun getMangaChapter(sourceId: String, bookUrl: String, chapterUrl: String): MangaChapterRecord? = connect { db ->
        db.prepareStatement(MANGA_CHAPTER_SELECT + " where source_id=? and book_url=? and chapter_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.toMangaChapter() else null }
        }
    }

    fun listMangaChaptersForBook(sourceId: String, bookUrl: String): List<MangaChapterRecord> = connect { db ->
        db.prepareStatement(MANGA_CHAPTER_SELECT + " where source_id=? and book_url=? order by chapter_index asc").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMangaChapter()) } }
        }
    }

    /**
     * 写入/覆盖某章的清单。
     *
     * `created_at` **不参与 update**：重解析清单不应让「创建时间」漂移（它同时被 GC 当作
     * 「这本书多久没碰过」的兜底信号）。
     */
    fun saveMangaChapter(record: MangaChapterRecord) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """insert into manga_chapter_cache(source_id,book_url,chapter_url,chapter_index,pinned,images_json,total,ready,skipped,state,last_error,created_at,updated_at)
               values(?,?,?,?,?,?,?,?,?,?,?,?,?)
               on conflict(source_id,book_url,chapter_url) do update set
                 chapter_index=excluded.chapter_index, pinned=excluded.pinned, images_json=excluded.images_json,
                 total=excluded.total, ready=excluded.ready, skipped=excluded.skipped, state=excluded.state,
                 last_error=excluded.last_error, updated_at=excluded.updated_at""",
        ).use {
            it.setString(1, record.sourceId); it.setString(2, record.bookUrl); it.setString(3, record.chapterUrl)
            it.setInt(4, record.chapterIndex); it.setInt(5, if (record.pinned) 1 else 0)
            it.setString(6, record.imagesJson); it.setInt(7, record.total); it.setInt(8, record.ready)
            it.setInt(9, record.skipped); it.setString(10, record.state); it.setString(11, record.lastError)
            it.setLong(12, if (record.createdAt > 0) record.createdAt else now); it.setLong(13, now)
            it.executeUpdate()
        }
    }

    /** 只更新就绪度与状态（每张图落盘后调用；不动 `images_json`）。 */
    fun updateMangaChapterProgress(
        sourceId: String,
        bookUrl: String,
        chapterUrl: String,
        ready: Int,
        skipped: Int,
        state: String,
        lastError: String? = null,
    ) = write { db ->
        db.prepareStatement(
            "update manga_chapter_cache set ready=?, skipped=?, state=?, last_error=?, updated_at=? where source_id=? and book_url=? and chapter_url=?",
        ).use {
            it.setInt(1, ready); it.setInt(2, skipped); it.setString(3, state); it.setString(4, lastError)
            it.setLong(5, System.currentTimeMillis())
            it.setString(6, sourceId); it.setString(7, bookUrl); it.setString(8, chapterUrl)
            it.executeUpdate()
        }
    }

    /** 目录绿点 `●` 的唯一口径：**整章全部图片都落盘**才返回该章。 */
    fun listReadyMangaChapterUrls(sourceId: String, bookUrl: String): List<String> = connect { db ->
        db.prepareStatement("select chapter_url from manga_chapter_cache where source_id=? and book_url=? and state='ready' order by chapter_index asc").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun countMangaChapters(sourceId: String, bookUrl: String): Int = connect { db ->
        db.prepareStatement("select count(*) from manga_chapter_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /**
     * 一次查全架：每本书"整章图片全部落盘"的章数（key = "sourceId\u0000bookUrl"）。
     *
     * 书架列表要按**媒体口径**显示漫画/有声的缓存进度（过去只读 book_cache_status 这张
     * 正文缓存表 ⇒ 漫画/有声永远显示 0 章 / 失败，与目录抽屉里绿点在涨自相矛盾）。
     * 逐本查会变成 N 次 SQL（书架近 400 本），所以这里按书聚合一次取完。
     */
    fun readyMangaChapterCountsByBook(): Map<String, Int> = connect { db ->
        db.prepareStatement(
            "select source_id, book_url, count(*) from manga_chapter_cache where state='ready' group by source_id, book_url",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put("${rs.getString(1)}\u0000${rs.getString(2)}", rs.getInt(3)) }
            }
        }
    }

    /** 同上，有声：整章所有音频分段都 ready 才算一章（与 [readyAudioChapters] 同口径）。 */
    fun readyAudioChapterCountsByBook(): Map<String, Int> = connect { db ->
        db.prepareStatement(
            """select source_id, book_url, count(*) from (
                   select source_id, book_url, chapter_url from audio_cache
                    group by source_id, book_url, chapter_url
                   having count(*) > 0 and sum(case when state='ready' then 1 else 0 end) = count(*)
               ) ready_chapters group by source_id, book_url""",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put("${rs.getString(1)}\u0000${rs.getString(2)}", rs.getInt(3)) }
            }
        }
    }

    fun countReadyMangaChapters(sourceId: String, bookUrl: String): Int = connect { db ->
        db.prepareStatement("select count(*) from manga_chapter_cache where source_id=? and book_url=? and state='ready'").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** 手动「整本离线（pinned）」开关：GC 免死金牌。 */
    fun setMangaBookPinned(sourceId: String, bookUrl: String, pinned: Boolean) = write { db ->
        db.prepareStatement("update manga_chapter_cache set pinned=?, updated_at=? where source_id=? and book_url=?").use {
            it.setInt(1, if (pinned) 1 else 0); it.setLong(2, System.currentTimeMillis())
            it.setString(3, sourceId); it.setString(4, bookUrl); it.executeUpdate()
        }
    }

    fun deleteMangaChapters(sourceId: String, bookUrl: String): Int = write { db ->
        db.prepareStatement("delete from manga_chapter_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // 漫画链路 · 图片字节索引（L1）
    // ------------------------------------------------------------------

    fun findMangaImageByUrlHash(urlHash: String): MangaImageRecord? = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT + " where url_hash=?").use {
            it.setString(1, urlHash)
            it.executeQuery().use { rs -> if (rs.next()) rs.toMangaImage() else null }
        }
    }

    /**
     * 记录一次成功落盘（**先盘后表**：文件已经在磁盘上才写这行），并把该行置为 `ready`。
     *
     * `created_at` 不参与 update：同一 URL 若字节变了（上游换图），新 key 会覆盖旧值，
     * 但创建时间保持首次，避免 GC 把"老图"当新图保护。
     */
    fun recordMangaImage(record: MangaImageRecord) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """insert into manga_image_cache(url_hash,cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,content_type,byte_size,width,height,state,last_error,created_at,last_access_at,file_path)
               values(?,?,?,?,?,?,?,?,?,?,?,?,'ready',null,?,?,?)
               on conflict(url_hash) do update set
                 cache_key=excluded.cache_key, source_id=excluded.source_id, book_url=excluded.book_url,
                 chapter_url=excluded.chapter_url, chapter_index=excluded.chapter_index,
                 segment_index=excluded.segment_index, upstream_url=excluded.upstream_url,
                 content_type=excluded.content_type, byte_size=excluded.byte_size,
                 width=excluded.width, height=excluded.height, state='ready', last_error=null,
                 last_access_at=excluded.last_access_at, file_path=coalesce(nullif(excluded.file_path,''), manga_image_cache.file_path)""",
        ).use {
            it.setString(1, record.urlHash); it.setString(2, record.cacheKey); it.setString(3, record.sourceId)
            it.setString(4, record.bookUrl); it.setString(5, record.chapterUrl); it.setInt(6, record.chapterIndex)
            it.setInt(7, record.segmentIndex); it.setString(8, record.upstreamUrl); it.setString(9, record.contentType)
            it.setLong(10, record.byteSize)
            if (record.width != null) it.setInt(11, record.width) else it.setNull(11, java.sql.Types.INTEGER)
            if (record.height != null) it.setInt(12, record.height) else it.setNull(12, java.sql.Types.INTEGER)
            it.setLong(13, if (record.createdAt > 0) record.createdAt else now)
            it.setLong(14, if (record.lastAccessAt > 0) record.lastAccessAt else now)
            // 空路径只是"不知道"，不是"没有文件"：绝不能把已有行的可用路径抹成空
            it.setString(15, record.filePath)
            it.executeUpdate()
        }
    }

    /**
     * 清单阶段的**登记**（幂等批量）：把「这一章有哪些图、分别是什么 key」先落库，
     * 但**不动 `state`** —— 已经 ready 的行不能被重新登记打回 pending。
     *
     * 为什么必须登记：对外 key 是 URL 派生的哈希、**不可逆**，服务端必须留着 `upstream_url`
     * 才知道按需去哪个地址抓。
     */
    fun registerMangaImages(records: List<MangaImageRecord>): Int {
        if (records.isEmpty()) return 0
        return write { db ->
            var affected = 0
            db.autoCommit = false
            try {
                db.prepareStatement(
                    """insert into manga_image_cache(url_hash,cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,content_type,byte_size,state,created_at,last_access_at)
                       values(?,?,?,?,?,?,?,?,'',0,'pending',?,?)
                       on conflict(url_hash) do update set
                         source_id=excluded.source_id, book_url=excluded.book_url,
                         chapter_url=excluded.chapter_url, chapter_index=excluded.chapter_index,
                         segment_index=excluded.segment_index, upstream_url=excluded.upstream_url""",
                ).use { st ->
                    val now = System.currentTimeMillis()
                    records.forEach { r ->
                        st.setString(1, r.urlHash); st.setString(2, r.cacheKey); st.setString(3, r.sourceId)
                        st.setString(4, r.bookUrl); st.setString(5, r.chapterUrl); st.setInt(6, r.chapterIndex)
                        st.setInt(7, r.segmentIndex); st.setString(8, r.upstreamUrl)
                        st.setLong(9, now); st.setLong(10, now)
                        st.addBatch()
                    }
                    affected = st.executeBatch().sum()
                }
                db.commit()
                affected
            } catch (error: Throwable) {
                db.rollback(); throw error
            } finally {
                db.autoCommit = true
            }
        }
    }

    /** 某一章的全部图片行（按章节内序号排序）—— 清单接口的取数口径。 */
    fun listMangaImagesForChapter(sourceId: String, bookUrl: String, chapterUrl: String): List<MangaImageRecord> = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT + " where source_id=? and book_url=? and chapter_url=? order by segment_index asc").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMangaImage()) } }
        }
    }

    fun markMangaImageFailed(urlHash: String, error: String?) = write { db ->
        db.prepareStatement("update manga_image_cache set state='failed', last_error=? where url_hash=?").use {
            it.setString(1, error?.take(500)); it.setString(2, urlHash); it.executeUpdate()
        }
    }

    fun touchMangaImage(urlHash: String, at: Long = System.currentTimeMillis()) = write { db ->
        db.prepareStatement("update manga_image_cache set last_access_at=? where url_hash=?").use {
            it.setLong(1, at); it.setString(2, urlHash); it.executeUpdate()
        }
    }

    /** 引用计数：同一份字节可能被多个上游 URL 引用 ⇒ 删文件前必须先问它。 */
    fun countMangaImageRefs(cacheKey: String): Int = connect { db ->
        db.prepareStatement("select count(*) from manga_image_cache where cache_key=?").use {
            it.setString(1, cacheKey)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /**
     * 路径引用计数：**删物理文件前的判据**。
     *
     * 内容寻址时代问的是 `cache_key`（同一份字节被多个 URL 共用）；现在每段落自己的文件，
     * 判据换成 `file_path` —— 万一两行指向同一个文件（历史数据/极端重名），也不能误删。
     */
    fun countMangaImagePaths(filePath: String): Int = connect { db ->
        db.prepareStatement("select count(*) from manga_image_cache where file_path=?").use {
            it.setString(1, filePath)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** 无中文定位命中的**回填**：把 `file_path` 这条快路径修好（下次直出少一次扫盘）。 */
    fun updateMangaImageFilePath(urlHash: String, filePath: String) = write { db ->
        db.prepareStatement("update manga_image_cache set file_path=? where url_hash=?").use {
            it.setString(1, filePath); it.setString(2, urlHash); it.executeUpdate()
        }
    }

    /**
     * 取「任一引用该字节的行」（`fileFor(cacheKey)` 口径）。
     *
     * 返回行而不是路径：快路径失效时调用方还要拿它的 `sourceId/bookUrl/chapterIndex/segmentIndex`
     * 退到无中文定位，不会因为 `file_path` 过期就判定"文件不存在"。
     */
    fun findMangaImageByCacheKey(cacheKey: String): MangaImageRecord? = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT + " where cache_key=? limit 1").use {
            it.setString(1, cacheKey)
            it.executeQuery().use { rs -> if (rs.next()) rs.toMangaImage() else null }
        }
    }

    /**
     * 书名（缓存目录用）。
     *
     * 书**不在书架**上也要能落盘 ⇒ 返回 null，调用方用短哈希兜底，绝不因此失败。
     */
    fun cacheBookName(sourceId: String, bookUrl: String): String? = connect { db ->
        db.prepareStatement("select name from book_shelf where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * 章节标题（缓存目录用）：先按 `tocUrl = bookUrl` / `bookUrl + "/toc"` 两种形态查 TOC 缓存，
     * 按章节地址匹配、退而按序号匹配；都拿不到再退到 `getChapterTitle`（like 兜底）。
     *
     * 第 4 条回退是**聚合源的必需品**：大灰狼这类源的目录缓存在**书架行的 `toc_url`** 下
     * （`data:;base64,…{"type":"qingtian2"}`），而 `bookUrl` 是另一个 `data:` 载荷
     * —— 少了这一条，整本书的章节目录就全退化成 `未命名 (哈希)`（实测）。
     *
     * 一律允许返回 null —— **标题只是给人看的装饰**，取不到就只用 8 位章节号。
     */
    fun cacheChapterTitle(sourceId: String, bookUrl: String, chapterUrl: String, chapterIndex: Int): String? {
        val chapters = getTocCache(sourceId, bookUrl)
            ?: getTocCache(sourceId, "$bookUrl/toc")
            ?: getShelfBookByUrl(bookUrl)?.tocUrl?.takeIf { it.isNotBlank() && it != bookUrl }?.let { getTocCache(sourceId, it) }
        val title = chapters?.let { list ->
            list.firstOrNull { it.url == chapterUrl }?.title
                ?: list.firstOrNull { it.index == chapterIndex }?.title
        }
        return title?.takeIf { it.isNotBlank() } ?: getChapterTitle(sourceId, bookUrl, chapterUrl)
    }

    fun sumMangaImageBytes(): Long = connect { db ->
        db.prepareStatement("select coalesce(sum(byte_size),0) from manga_image_cache").use {
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun countMangaImages(): Int = connect { db ->
        db.prepareStatement("select count(*) from manga_image_cache").use {
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** GC 候选（按最后访问时间升序）。滑窗分档的**筛选**在 `ImageCacheGc` 里做（需读进度锚点）。 */
    fun listMangaImagesByLastAccess(limit: Int): List<MangaImageRecord> = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT + " order by last_access_at asc limit ?").use {
            it.setInt(1, limit)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMangaImage()) } }
        }
    }

    /**
     * 全部「免死金牌」书（`pinned = 1`）。
     *
     * 返回 `(sourceId, bookUrl)` 集合而不是拼接字符串：拼接需要约定一个分隔符，
     * 而书源 ID 允许任意非空字符串（AGENTS.md 记过：中文标识都会有），分隔符早晚撞上。
     */
    fun listPinnedMangaBooks(): Set<Pair<String, String>> = connect { db ->
        db.prepareStatement("select distinct source_id, book_url from manga_chapter_cache where pinned=1").use {
            it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }

    /**
     * 批量删除索引行（**GC 专用**）。
     *
     * 一条事务删一批：GC 一轮可能淘汰几百行，逐行 `deleteMangaImageRow` 就是几百次事务提交
     * （GC 与阅读路径抢同一个写连接 ⇒ 前台接口卡顿）。
     */
    fun deleteMangaImageRows(urlHashes: List<String>): Int {
        if (urlHashes.isEmpty()) return 0
        return write { db ->
            db.autoCommit = false
            try {
                var removed = 0
                db.prepareStatement("delete from manga_image_cache where url_hash=?").use { statement ->
                    urlHashes.forEach { hash -> statement.setString(1, hash); removed += statement.executeUpdate() }
                }
                db.commit()
                removed
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun deleteMangaImageRow(urlHash: String) = write { db ->
        db.prepareStatement("delete from manga_image_cache where url_hash=?").use {
            it.setString(1, urlHash); it.executeUpdate()
        }
    }

    fun deleteMangaImageRowsByBook(sourceId: String, bookUrl: String): Int = write { db ->
        db.prepareStatement("delete from manga_image_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate()
        }
    }

    fun listMangaImagesByBook(sourceId: String, bookUrl: String): List<MangaImageRecord> = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT + " where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMangaImage()) } }
        }
    }

    fun listAllMangaImages(): List<MangaImageRecord> = connect { db ->
        db.prepareStatement(MANGA_IMAGE_SELECT).use {
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMangaImage()) } }
        }
    }

    /** 清空全部图片索引（`?all=true` 的清理入口）。返回受影响行数。 */
    fun deleteAllMangaImageRows(): Int = write { db ->
        db.createStatement().use { it.executeUpdate("delete from manga_image_cache") }
    }

    fun mangaCacheStatsByBook(): List<MangaCacheBookStat> = connect { db ->
        db.prepareStatement(
            """select i.source_id, i.book_url, max(s.name) as name, count(*) as image_count, coalesce(sum(i.byte_size),0) as total_bytes,
                      coalesce(max(c.chapter_count),0), coalesce(max(c.ready_count),0), coalesce(max(c.pinned),0)
               from manga_image_cache i
               left join book_shelf s on s.source_id = i.source_id and s.book_url = i.book_url
               left join (
                 select source_id, book_url, count(*) as chapter_count,
                        sum(case when state='ready' then 1 else 0 end) as ready_count,
                        max(pinned) as pinned
                 from manga_chapter_cache group by source_id, book_url
               ) c on c.source_id = i.source_id and c.book_url = i.book_url
               group by i.source_id, i.book_url
               order by total_bytes desc""",
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        MangaCacheBookStat(
                            sourceId = rs.getString(1), bookUrl = rs.getString(2), name = rs.getString(3),
                            imageCount = rs.getInt(4), totalBytes = rs.getLong(5),
                            chapterCount = rs.getInt(6), readyChapterCount = rs.getInt(7),
                            pinned = rs.getInt(8) != 0,
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 有声书链路 · 音频字节索引（L1）
    //
    // 与漫画的**唯一结构差异**：音频是 URL 寻址（一个 URL 一份字节，不做内容寻址去重），
    // 因此没有 `cache_key`（内容哈希）这一层，key 直接就是 URL 派生的哈希
    // —— 前端于是能在**抓取之前**就拼出 `GET /api/audio/{key}`（G-2 裁决）。
    // ------------------------------------------------------------------

    fun findAudioByKey(cacheKey: String): AudioCacheRecord? = connect { db ->
        db.prepareStatement(AUDIO_SELECT + " where cache_key=?").use {
            it.setString(1, cacheKey)
            it.executeQuery().use { rs -> if (rs.next()) rs.toAudioCache() else null }
        }
    }

    /**
     * 清单阶段的**登记**（幂等批量）：先落 `(key → 上游地址 + 该段专属头)`，但**不动 `state`**
     * —— 已经 `ready` 的行不能被重新登记打回 `pending`。
     *
     * 同时**清掉本章已不在清单里的旧 key**：书源换了某段地址时，旧行会永远留在表里占盘、
     * 还会把"整章就绪度"算成一个永远凑不齐的分数（绿点永远不亮）。
     */
    fun registerAudioTracks(records: List<AudioCacheRecord>, keepKeys: List<String>): Int {
        if (records.isEmpty()) return 0
        val head = records.first()
        return write { db ->
            var affected = 0
            db.autoCommit = false
            try {
                val now = System.currentTimeMillis()
                db.prepareStatement(
                    """insert into audio_cache(cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,headers_json,content_type,byte_size,duration_ms,state,pinned,listened,created_at,last_access_at)
                       values(?,?,?,?,?,?,?,?,'',0,0,'pending',0,0,?,?)
                       on conflict(cache_key) do update set
                         source_id=excluded.source_id, book_url=excluded.book_url, chapter_url=excluded.chapter_url,
                         chapter_index=excluded.chapter_index, segment_index=excluded.segment_index,
                         upstream_url=excluded.upstream_url, headers_json=excluded.headers_json""",
                ).use { st ->
                    records.forEach { r ->
                        st.setString(1, r.cacheKey); st.setString(2, r.sourceId); st.setString(3, r.bookUrl)
                        st.setString(4, r.chapterUrl); st.setInt(5, r.chapterIndex); st.setInt(6, r.segmentIndex)
                        st.setString(7, r.upstreamUrl); st.setString(8, r.headersJson)
                        st.setLong(9, now); st.setLong(10, now)
                        st.addBatch()
                    }
                    affected = st.executeBatch().sum()
                }
                if (keepKeys.isNotEmpty()) {
                    val placeholders = keepKeys.joinToString(",") { "?" }
                    db.prepareStatement(
                        "delete from audio_cache where source_id=? and book_url=? and chapter_url=? and cache_key not in ($placeholders)",
                    ).use { st ->
                        st.setString(1, head.sourceId); st.setString(2, head.bookUrl); st.setString(3, head.chapterUrl)
                        keepKeys.forEachIndexed { index, key -> st.setString(4 + index, key) }
                        st.executeUpdate()
                    }
                }
                db.commit()
                affected
            } catch (error: Throwable) {
                db.rollback(); throw error
            } finally {
                db.autoCommit = true
            }
        }
    }

    /** 某一章的全部分段行（按段序号排序）—— 清单接口的取数口径。 */
    fun listAudioForChapter(sourceId: String, bookUrl: String, chapterUrl: String): List<AudioCacheRecord> = connect { db ->
        db.prepareStatement(AUDIO_SELECT + " where source_id=? and book_url=? and chapter_url=? order by segment_index asc").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAudioCache()) } }
        }
    }

    /**
     * 记录一次成功落盘（**先盘后表**：文件已经在磁盘上才写这行）。
     *
     * `pinned` / `listened` **不参与 update**：重新下载一段已 pin 的音频不该把免死金牌弄丢。
     */
    fun recordAudioTrack(record: AudioCacheRecord) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """insert into audio_cache(cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,headers_json,content_type,byte_size,duration_ms,state,last_error,pinned,listened,created_at,last_access_at,file_path)
               values(?,?,?,?,?,?,?,?,?,?,?,'ready',null,?,?,?,?,?)
               on conflict(cache_key) do update set
                 source_id=excluded.source_id, book_url=excluded.book_url, chapter_url=excluded.chapter_url,
                 chapter_index=excluded.chapter_index, segment_index=excluded.segment_index,
                 upstream_url=excluded.upstream_url, headers_json=excluded.headers_json,
                 content_type=excluded.content_type, byte_size=excluded.byte_size,
                 duration_ms=excluded.duration_ms, state='ready', last_error=null,
                 last_access_at=excluded.last_access_at,
                 file_path=coalesce(nullif(excluded.file_path,''), audio_cache.file_path)""",
        ).use {
            it.setString(1, record.cacheKey); it.setString(2, record.sourceId); it.setString(3, record.bookUrl)
            it.setString(4, record.chapterUrl); it.setInt(5, record.chapterIndex); it.setInt(6, record.segmentIndex)
            it.setString(7, record.upstreamUrl); it.setString(8, record.headersJson); it.setString(9, record.contentType)
            it.setLong(10, record.byteSize); it.setLong(11, record.durationMs)
            it.setInt(12, if (record.pinned) 1 else 0); it.setInt(13, if (record.listened) 1 else 0)
            it.setLong(14, if (record.createdAt > 0) record.createdAt else now)
            it.setLong(15, if (record.lastAccessAt > 0) record.lastAccessAt else now)
            // 空路径只是"不知道"，不是"没有文件"：绝不能把已有行的可用路径抹成空
            it.setString(16, record.filePath)
            it.executeUpdate()
        }
    }

    fun markAudioFailed(cacheKey: String, error: String?) = write { db ->
        db.prepareStatement("update audio_cache set state='failed', last_error=? where cache_key=?").use {
            it.setString(1, error?.take(500)); it.setString(2, cacheKey); it.executeUpdate()
        }
    }

    fun touchAudio(cacheKey: String, at: Long = System.currentTimeMillis()) = write { db ->
        db.prepareStatement("update audio_cache set last_access_at=? where cache_key=?").use {
            it.setLong(1, at); it.setString(2, cacheKey); it.executeUpdate()
        }
    }

    /** 前端 `loadedmetadata` 回传的时长。**只在拿到正数时写**：0 是"未知"，不该覆盖已知值。 */
    fun updateAudioDuration(cacheKey: String, durationMs: Long): Int = write { db ->
        db.prepareStatement("update audio_cache set duration_ms=? where cache_key=? and ?>0").use {
            it.setLong(1, durationMs); it.setString(2, cacheKey); it.setLong(3, durationMs); it.executeUpdate()
        }
    }

    /** 标记"这段听完了"（GC 第 1 顺位的前提条件）。 */
    fun markAudioListened(cacheKey: String, listened: Boolean) = write { db ->
        db.prepareStatement("update audio_cache set listened=? where cache_key=?").use {
            it.setInt(1, if (listened) 1 else 0); it.setString(2, cacheKey); it.executeUpdate()
        }
    }

    fun sumAudioBytes(): Long = connect { db ->
        db.prepareStatement("select coalesce(sum(byte_size),0) from audio_cache").use {
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun countAudioTracks(): Int = connect { db ->
        db.prepareStatement("select count(*) from audio_cache").use {
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** GC 候选（按最后访问时间升序）。滑窗分档的**筛选**在 `AudioCacheGc` 里做（需读进度锚点）。 */
    fun listAudioByLastAccess(limit: Int): List<AudioCacheRecord> = connect { db ->
        db.prepareStatement(AUDIO_SELECT + " order by last_access_at asc limit ?").use {
            it.setInt(1, limit)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAudioCache()) } }
        }
    }

    /** 全部「免死金牌」书（`pinned = 1`）。返回 Pair 而非拼接串：书源 ID 允许任意非空字符串。 */
    fun listPinnedAudioBooks(): Set<Pair<String, String>> = connect { db ->
        db.prepareStatement("select distinct source_id, book_url from audio_cache where pinned=1").use {
            it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }

    /** 打/摘免死金牌（整本粒度，落在该书的**所有**行上）。 */
    fun setAudioBookPinned(sourceId: String, bookUrl: String, pinned: Boolean): Int = write { db ->
        db.prepareStatement("update audio_cache set pinned=? where source_id=? and book_url=?").use {
            it.setInt(1, if (pinned) 1 else 0); it.setString(2, sourceId); it.setString(3, bookUrl); it.executeUpdate()
        }
    }

    /** 批量删除索引行（**GC 专用**）：一条事务删一批，避免与前台阅读路径抢写连接。 */
    fun deleteAudioRows(cacheKeys: List<String>): Int {
        if (cacheKeys.isEmpty()) return 0
        return write { db ->
            db.autoCommit = false
            try {
                var removed = 0
                db.prepareStatement("delete from audio_cache where cache_key=?").use { statement ->
                    cacheKeys.forEach { key -> statement.setString(1, key); removed += statement.executeUpdate() }
                }
                db.commit()
                removed
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
    }

    fun deleteAudioRow(cacheKey: String) = write { db ->
        db.prepareStatement("delete from audio_cache where cache_key=?").use {
            it.setString(1, cacheKey); it.executeUpdate()
        }
    }

    /** 路径引用计数：删物理文件前的判据（同 `countMangaImagePaths`）。 */
    fun countAudioPaths(filePath: String): Int = connect { db ->
        db.prepareStatement("select count(*) from audio_cache where file_path=?").use {
            it.setString(1, filePath)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** 无中文定位命中的**回填**（同 `updateMangaImageFilePath`）。 */
    fun updateAudioFilePath(cacheKey: String, filePath: String) = write { db ->
        db.prepareStatement("update audio_cache set file_path=? where cache_key=?").use {
            it.setString(1, filePath); it.setString(2, cacheKey); it.executeUpdate()
        }
    }

    fun deleteAudioRowsByBook(sourceId: String, bookUrl: String): Int = write { db ->
        db.prepareStatement("delete from audio_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate()
        }
    }

    fun listAudioByBook(sourceId: String, bookUrl: String): List<AudioCacheRecord> = connect { db ->
        db.prepareStatement(AUDIO_SELECT + " where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAudioCache()) } }
        }
    }

    fun listAllAudio(): List<AudioCacheRecord> = connect { db ->
        db.prepareStatement(AUDIO_SELECT).use {
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAudioCache()) } }
        }
    }

    fun deleteAllAudioRows(): Int = write { db ->
        db.createStatement().use { it.executeUpdate("delete from audio_cache") }
    }

    /**
     * 已**整章就绪**的章节 URL（目录绿点 `●` 的唯一口径）。
     *
     * 与漫画的差别：音频**没有 L2 章节表**——"整章就绪"就是"这一章的每一段都 ready"，
     * 一条 `having` 就能算出来，多建一张表只会多一处需要同步的状态。
     */
    fun readyAudioChapters(sourceId: String, bookUrl: String): List<String> = connect { db ->
        db.prepareStatement(
            """select chapter_url from audio_cache where source_id=? and book_url=?
               group by chapter_url
               having count(*) > 0 and sum(case when state='ready' then 1 else 0 end) = count(*)
               order by min(chapter_index) asc""",
        ).use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun audioCacheStatsByBook(): List<AudioCacheBookStat> = connect { db ->
        db.prepareStatement(
            """select a.source_id, a.book_url, max(s.name) as name, count(*) as track_count, coalesce(sum(a.byte_size),0) as total_bytes,
                      coalesce(max(c.chapter_count),0), coalesce(max(c.ready_count),0), coalesce(max(c.pinned),0)
               from audio_cache a
               left join book_shelf s on s.source_id = a.source_id and s.book_url = a.book_url
               left join (
                 select source_id, book_url, count(*) as chapter_count,
                        sum(case when ready_all = 1 then 1 else 0 end) as ready_count,
                        max(pinned) as pinned
                 from (
                   select source_id, book_url, chapter_url,
                          min(case when state='ready' then 1 else 0 end) as ready_all,
                          max(pinned) as pinned
                   from audio_cache group by source_id, book_url, chapter_url
                 ) chapter_state
                 group by source_id, book_url
               ) c on c.source_id = a.source_id and c.book_url = a.book_url
               group by a.source_id, a.book_url
               order by total_bytes desc""",
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        AudioCacheBookStat(
                            sourceId = rs.getString(1), bookUrl = rs.getString(2), name = rs.getString(3),
                            trackCount = rs.getInt(4), totalBytes = rs.getLong(5),
                            chapterCount = rs.getInt(6), readyChapterCount = rs.getInt(7),
                            pinned = rs.getInt(8) != 0,
                        ),
                    )
                }
            }
        }
    }

    private fun java.sql.ResultSet.toAudioCache(): AudioCacheRecord = AudioCacheRecord(
        cacheKey = getString(1), sourceId = getString(2), bookUrl = getString(3), chapterUrl = getString(4),
        chapterIndex = getInt(5), segmentIndex = getInt(6), upstreamUrl = getString(7), headersJson = getString(8),
        contentType = getString(9), byteSize = getLong(10), durationMs = getLong(11), state = getString(12),
        lastError = getString(13), pinned = getInt(14) != 0, listened = getInt(15) != 0,
        createdAt = getLong(16), lastAccessAt = getLong(17), filePath = getString(18).orEmpty(),
    )

    // ------------------------------------------------------------------
    // 小说正文插图链路 · 插图字节索引（`images/`）
    //
    // 为什么**不**复用 `manga_image_cache`：漫画的序号来自"整章清单"，
    // 插图的序号来自"正文里的出现顺序"，两套语义混表会让任何一方的口径变更污染另一方
    // （见 ADR-033）。这里只有一张表：URL 寻址 + 落盘路径 + 序号。
    // ------------------------------------------------------------------

    fun findIllustrationByUrlHash(urlHash: String): IllustrationRecord? = connect { db ->
        db.prepareStatement(ILLUSTRATION_SELECT + " where url_hash=?").use {
            it.setString(1, urlHash)
            it.executeQuery().use { rs -> if (rs.next()) rs.toIllustration() else null }
        }
    }

    /**
     * 按正文顺序**登记**一章的插图（幂等批量）。
     *
     * `on conflict do nothing` 是刻意的：序号一旦分配就**永不改写**，否则"正文顺序变了"
     * 会让老文件的序号与文件名错位（读取只认哈希 + 序号 ⇒ 可能拿到另一张图）。
     */
    fun insertIllustrationRows(records: List<IllustrationRecord>): Int {
        if (records.isEmpty()) return 0
        return write { db ->
            var affected = 0
            db.autoCommit = false
            try {
                db.prepareStatement(
                    """insert into illustration_cache(url_hash,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,content_type,byte_size,cache_key,state,created_at,last_access_at)
                       values(?,?,?,?,?,?,?,'',0,'','pending',?,?)
                       on conflict(url_hash) do nothing""",
                ).use { st ->
                    val now = System.currentTimeMillis()
                    records.forEach { r ->
                        st.setString(1, r.urlHash); st.setString(2, r.sourceId); st.setString(3, r.bookUrl)
                        st.setString(4, r.chapterUrl); st.setInt(5, r.chapterIndex); st.setInt(6, r.segmentIndex)
                        st.setString(7, r.upstreamUrl)
                        st.setLong(8, if (r.createdAt > 0) r.createdAt else now)
                        st.setLong(9, if (r.lastAccessAt > 0) r.lastAccessAt else now)
                        st.addBatch()
                    }
                    affected = st.executeBatch().sum()
                }
                db.commit()
                affected
            } catch (error: Throwable) {
                db.rollback()
                throw error
            } finally {
                db.autoCommit = true
            }
        }
    }

    /** 记录一次成功落盘（**先盘后表**：文件已经写好才把行置 `ready`）。 */
    fun recordIllustration(record: IllustrationRecord) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """insert into illustration_cache(url_hash,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,content_type,byte_size,cache_key,state,created_at,last_access_at,file_path)
               values(?,?,?,?,?,?,?,?,?,?,'ready',?,?,?)
               on conflict(url_hash) do update set
                 content_type=excluded.content_type, byte_size=excluded.byte_size, cache_key=excluded.cache_key,
                 state='ready', last_access_at=excluded.last_access_at,
                 file_path=coalesce(nullif(excluded.file_path,''), illustration_cache.file_path)""",
        ).use {
            it.setString(1, record.urlHash); it.setString(2, record.sourceId); it.setString(3, record.bookUrl)
            it.setString(4, record.chapterUrl); it.setInt(5, record.chapterIndex); it.setInt(6, record.segmentIndex)
            it.setString(7, record.upstreamUrl); it.setString(8, record.contentType); it.setLong(9, record.byteSize)
            it.setString(10, record.cacheKey)
            it.setLong(11, if (record.createdAt > 0) record.createdAt else now)
            it.setLong(12, if (record.lastAccessAt > 0) record.lastAccessAt else now)
            // 空路径只是"不知道"，不是"没有文件"：绝不能把已有行的可用路径抹成空
            it.setString(13, record.filePath)
            it.executeUpdate()
        }
    }

    fun listIllustrationsForChapter(sourceId: String, bookUrl: String, chapterUrl: String): List<IllustrationRecord> = connect { db ->
        db.prepareStatement(
            ILLUSTRATION_SELECT + " where source_id=? and book_url=? and chapter_url=? order by segment_index asc",
        ).use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toIllustration()) } }
        }
    }

    fun listIllustrationsByBook(sourceId: String, bookUrl: String): List<IllustrationRecord> = connect { db ->
        db.prepareStatement(ILLUSTRATION_SELECT + " where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toIllustration()) } }
        }
    }

    fun deleteIllustrationRow(urlHash: String) = write { db ->
        db.prepareStatement("delete from illustration_cache where url_hash=?").use {
            it.setString(1, urlHash); it.executeUpdate()
        }
    }

    fun deleteIllustrationRowsByBook(sourceId: String, bookUrl: String): Int = write { db ->
        db.prepareStatement("delete from illustration_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate()
        }
    }

    fun updateIllustrationFilePath(urlHash: String, filePath: String) = write { db ->
        db.prepareStatement("update illustration_cache set file_path=? where url_hash=?").use {
            it.setString(1, filePath); it.setString(2, urlHash); it.executeUpdate()
        }
    }

    fun countIllustrationPaths(filePath: String): Int = connect { db ->
        db.prepareStatement("select count(*) from illustration_cache where file_path=?").use {
            it.setString(1, filePath)
            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun java.sql.ResultSet.toIllustration(): IllustrationRecord = IllustrationRecord(
        urlHash = getString(1), sourceId = getString(2), bookUrl = getString(3), chapterUrl = getString(4),
        upstreamUrl = getString(5), chapterIndex = getInt(6), segmentIndex = getInt(7),
        contentType = getString(8), byteSize = getLong(9), cacheKey = getString(10),
        state = getString(11), createdAt = getLong(12), lastAccessAt = getLong(13),
        filePath = getString(14).orEmpty(),
    )

    private fun java.sql.ResultSet.toMangaChapter(): MangaChapterRecord = MangaChapterRecord(
        sourceId = getString(1), bookUrl = getString(2), chapterUrl = getString(3),
        chapterIndex = getInt(4), pinned = getInt(5) != 0, imagesJson = getString(6),
        total = getInt(7), ready = getInt(8), skipped = getInt(9), state = getString(10),
        lastError = getString(11), createdAt = getLong(12), updatedAt = getLong(13),
    )

    private fun java.sql.ResultSet.toMangaImage(): MangaImageRecord = MangaImageRecord(
        urlHash = getString(1), cacheKey = getString(2), sourceId = getString(3), bookUrl = getString(4),
        chapterUrl = getString(5), chapterIndex = getInt(6), segmentIndex = getInt(7), upstreamUrl = getString(8),
        contentType = getString(9), byteSize = getLong(10), width = getObject(11) as? Int, height = getObject(12) as? Int,
        state = getString(13), lastError = getString(14), createdAt = getLong(15), lastAccessAt = getLong(16),
        filePath = getString(17).orEmpty(),
    )
    /**
     * 登记一张封面副本（`file_path` 可选）。
     *
     * [filePath] 缺省/空 ⇒ **保留已有值**（`coalesce(nullif(...))`，与 `manga_image_cache` 同一手法）：
     * 既有调用点（上传接口等）只关心 key 与类型，不该顺手把快路径擦掉。
     */
    fun recordCoverCache(coverKey: String, contentType: String = "image/*", filePath: String? = null) = write { db ->
        db.prepareStatement("insert into cover_cache(cache_key,content_type,file_path) values(?,?,?) on conflict(cache_key) do update set content_type=excluded.content_type, file_path=coalesce(nullif(excluded.file_path,''), cover_cache.file_path)").use {
            it.setString(1, coverKey)
            it.setString(2, contentType)
            it.setString(3, filePath.orEmpty())
            it.executeUpdate()
        }
    }

    /** 某张封面记下的相对路径（快路径）。没有记录或列为空 ⇒ null。 */
    fun coverFilePath(coverKey: String): String? = connect { db ->
        db.prepareStatement("select file_path from cover_cache where cache_key=?").use {
            it.setString(1, coverKey)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.takeIf { path -> path.isNotBlank() } else null }
        }
    }

    /** 无中文定位命中后的**回填**：把快路径修好（下次直出少一次扫盘）。 */
    fun updateCoverFilePath(coverKey: String, filePath: String) = write { db ->
        db.prepareStatement("update cover_cache set file_path=? where cache_key=?").use {
            it.setString(1, filePath)
            it.setString(2, coverKey)
            it.executeUpdate()
        }
    }

    /** 清掉快路径（封面文件被删除时调用，免得留一个指向空气的路径）。 */
    fun clearCoverFilePath(coverKey: String) = write { db ->
        db.prepareStatement("update cover_cache set file_path='' where cache_key=?").use {
            it.setString(1, coverKey)
            it.executeUpdate()
        }
    }

    /**
     * 某个相对路径当前**被哪些 key 认领**（`cover_cache.file_path = ?`）。
     *
     * 用途只有一个：封面按「书哈希」兜底定位时，同一本书可能有两张封面（换过图的常见形态），
     * 而 `书名 (书哈希).扩展名` 这个命名**天生容不下两版**。于是：
     * - **读**：候选文件若已被别的 key 认领 ⇒ 跳过（宁可判"没有"、回源重下，也绝不返回另一版字节）；
     * - **写**：覆盖同名文件前先把别的 key 的快路径清掉（那条路径装的不再是它的字节了）。
     */
    fun coverFilePathHolders(filePath: String): List<String> = connect { db ->
        db.prepareStatement("select cache_key from cover_cache where file_path=?").use {
            it.setString(1, filePath)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** 全部封面索引行（布局迁移用：key + 类型 + 已记的相对路径）。 */
    fun listCoverCache(): List<CoverCacheRow> = connect { db ->
        db.prepareStatement("select cache_key,content_type,file_path from cover_cache").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(CoverCacheRow(rs.getString(1), rs.getString(2).orEmpty(), rs.getString(3).orEmpty()))
                }
            }
        }
    }

    /**
     * 引用某张封面的书架条目（按最近阅读倒序）。
     *
     * 这是封面读取的**兜底依据**：路径失效时用它的 `sourceId`/`bookUrl` 算出书哈希，
     * 再去封面根里按 `(书哈希)` 无中文扫描 —— 中文书名被改名也不会失联。
     */
    fun coverOwners(coverKey: String): List<CoverOwner> = connect { db ->
        db.prepareStatement("select name, source_id, book_url from book_shelf where cover_key=? order by last_read_at desc").use {
            it.setString(1, coverKey)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(CoverOwner(rs.getString(1).orEmpty(), rs.getString(2), rs.getString(3)))
                }
            }
        }
    }

    /**
     * 回写本地封面副本（`cover_key`），可选**同时**更新外链 `cover_url`。
     *
     * [coverUrl] 只在非空时一起写：默认 null ⇒ 与改动前**逐字一致**的行为
     * （既有调用点传的都是 4 个参数，语义不变）。
     * 深度回源补抓需要它——回源拿到的是原本那份**已过期签名地址的替代品**，
     * 只更新 `cover_key` 的话外链兜底依然是死的。
     */
    fun updateBookshelfCover(sourceId: String, bookUrl: String, coverKey: String, contentType: String = "image/*", coverUrl: String? = null): Boolean = write { db ->
        val exists = db.prepareStatement("select 1 from book_shelf where source_id=? and book_url=?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, bookUrl)
            stmt.executeQuery().use { rs -> rs.next() }
        }
        if (!exists) return@write false

        db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use {
            it.setString(1, coverKey)
            it.setString(2, contentType)
            it.executeUpdate()
        }
        if (coverUrl.isNullOrBlank()) {
            db.prepareStatement("update book_shelf set cover_key=? where source_id=? and book_url=?").use {
                it.setString(1, coverKey)
                it.setString(2, sourceId)
                it.setString(3, bookUrl)
                it.executeUpdate() > 0
            }
        } else {
            db.prepareStatement("update book_shelf set cover_key=?, cover_url=? where source_id=? and book_url=?").use {
                it.setString(1, coverKey)
                it.setString(2, coverUrl)
                it.setString(3, sourceId)
                it.setString(4, bookUrl)
                it.executeUpdate() > 0
            }
        }
    }
    fun listBookshelf(): List<BookshelfItem> = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable,p.position_ms from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url order by s.last_read_at desc""").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toShelf()) } } } }
    fun getShelfBookByUrl(bookUrl: String): BookshelfItem? = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable,p.position_ms from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.book_url=?""").use { it.setString(1, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } } }
    fun setBookshelfCompleted(sourceId: String, bookUrl: String, completed: Boolean): BookshelfItem? = write { db ->
        db.prepareStatement("update book_shelf set completed=? where source_id=? and book_url=?").use {
            it.setInt(1, if (completed) 1 else 0); it.setString(2, sourceId); it.setString(3, bookUrl); it.executeUpdate()
        }
        getBookshelf(db, sourceId, bookUrl)
    }
    fun updateBookshelfInfo(request: BookshelfInfoUpdateRequest, cover: CachedCover?): BookshelfItem? = write { db ->
        db.autoCommit = false
        try {
            var found = false
            var oldCover: String? = null
            var oldGroup: String? = null
            var oldAlts: String? = null
            var oldCoverUrl: String? = null
            // 必须把 cover_url 一起查出来 —— 「表里有」不等于「读得到」，
            // 缺了它就无从判断「字段缺省时该保留什么」（同类教训见备份导入封面那条）。
            db.prepareStatement("select cover_key, cover_url, group_name, alternate_sources from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        found = true
                        oldCover = rs.getString(1)
                        oldCoverUrl = rs.getString(2)
                        oldGroup = rs.getString(3)
                        oldAlts = rs.getString(4)
                    }
                }
            }
            if (!found) return@write null

            cover?.let { value ->
                db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use {
                    it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate()
                }
            }

            val newCoverKey = cover?.key
                ?: request.coverKey?.takeIf { it.isNotBlank() }
                ?: (if ((request.coverUrl != null && request.coverUrl.isBlank()) || (request.coverKey != null && request.coverKey.isBlank())) null else oldCover)
            val newGroup = if (request.groupName != null) request.groupName.trim().takeIf { it.isNotEmpty() } else oldGroup
            val newAlts = if (request.alternateSources != null) Json.encodeToString(request.alternateSources) else oldAlts
            // 拒绝把本服务自己的封面接口地址回写成 coverUrl。
            //
            // 实测（SESSION-027）：编辑弹窗在没有外部 URL 时会回退调用 api.cover(coverKey)，
            // 于是把 `/api/covers/<自己的 key>` 当成"外部封面地址"存了回来，形成自引用。
            // 危害：一旦 coverKey 被清空，前端回退到 coverUrl 就指向自身，形成无意义的循环。
            //
            // cover_url 的三种情形必须区分开，不能再拿「缺省」兼职「清空」：
            //   ① 字段缺失（null）⇒ **保留**原值 —— 编辑弹窗只改书名/作者时不该顺手抹掉封面地址。
            //      原实现直接写 null，实测「不改封面点保存」会把 cover_url 抹成 NULL（不可逆）。
            //   ② 显式空串（""）  ⇒ 清除（编辑弹窗的「清除封面」按钮走这条）。
            //   ③ 有值            ⇒ 采用该值。
            // 传入自引用时退回原值而不是写 null —— 写 null 同样会毁掉外链兜底。
            val fallbackCoverUrl = oldCoverUrl?.takeIf { url -> !isSelfCoverReference(url, newCoverKey) }
            val newCoverUrl = when {
                request.coverUrl == null -> fallbackCoverUrl
                request.coverUrl.isBlank() -> null
                else -> request.coverUrl.takeIf { url -> !isSelfCoverReference(url, newCoverKey) } ?: fallbackCoverUrl
            }

            db.prepareStatement("update book_shelf set name=?, author=?, cover_url=?, cover_key=?, group_name=?, alternate_sources=? where source_id=? and book_url=?").use {
                it.setString(1, sanitizeBookName(request.name).ifBlank { request.name.trim() })
                it.setString(2, request.author)
                it.setString(3, newCoverUrl)
                it.setString(4, newCoverKey)
                it.setString(5, newGroup)
                it.setString(6, newAlts)
                it.setString(7, request.sourceId)
                it.setString(8, request.bookUrl)
                it.executeUpdate()
            }

            val orphan = oldCover.takeIf { value ->
                value != newCoverKey && db.prepareStatement("select 1 from book_shelf where cover_key=?").use {
                    it.setString(1, value); !it.executeQuery().next()
                }
            }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }

            // 书籍变量：只在**显式给了 custom 值**时才动（null = 不动），
            // 且做的是**合并**（其余键原样保留）—— 对齐 Legado 的 `putCustomVariable()`。
            // 这样「只改书名点保存」绝不会顺手清掉听书音色。
            request.customVariable?.let { value ->
                val merged = parseBookVariables(
                    db.prepareStatement("select variable from book_shelf where source_id=? and book_url=?").use {
                        it.setString(1, request.sourceId); it.setString(2, request.bookUrl)
                        it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    },
                ).toMutableMap()
                merged[CUSTOM_VARIABLE_KEY] = value
                db.prepareStatement("update book_shelf set variable=? where source_id=? and book_url=?").use {
                    it.setString(1, Json.encodeToString(merged.toMap()))
                    it.setString(2, request.sourceId); it.setString(3, request.bookUrl)
                    it.executeUpdate()
                }
            }

            db.commit()
            getBookshelf(db, request.sourceId, request.bookUrl)
        } catch (error: Throwable) {
            db.rollback(); throw error
        } finally {
            db.autoCommit = true
        }
    }
    fun removeBookshelf(sourceId: String, bookUrl: String): String? = write { db ->
        db.autoCommit = false
        try {
            var name: String? = null
            var author: String? = null
            val key = db.prepareStatement("select cover_key, name, author from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, sourceId); it.setString(2, bookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        name = rs.getString(2)
                        author = rs.getString(3)
                        rs.getString(1)
                    } else null
                }
            }
            db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_content_cache where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_cache_status where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_toc_cache where source_id=? and (toc_url=? or toc_url like ?)").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, "%$bookUrl%"); it.executeUpdate() }
            // 章节检索索引跟着书架行一起走（否则「目录搜索」会命中已经不在书架上的书）。
            db.prepareStatement("delete from book_chapter_index where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            /*
             * 封面文件的回收判定（用户要求：「只要书架或者书签任何一个或两者存在，那书籍的封面文件就保留」）。
             *
             * 只有同时满足下面三条才算孤儿、才允许删文件：
             * ① 没有别的书架行还在用这个 key；② 这本书已经没有任何书签；③ 封面登记表里也没人引用这个 key。
             *
             * ⚠️ 书签**不跟着移出书架一起删**（removeBookshelf 不碰 bookmark 表）：用户要求
             * 「在书架删除书时不删除书签」——书签是独立的阅读记录，靠「书签」页继续管理。
             *
             * ⚠️ ②成立（还有书签）时，必须先把封面 key **转存进 book_cover** 再删书架行：
             * 否则这本书唯一的封面引用就随书架行一起消失了 —— 书签页会看不到封面，
             * 而 purgeIfUnused 又只认 book_cover，导致文件永远删不掉（审查复现过）。
             */
            val bookName = name
            val normalizedAuthor = author?.trim().orEmpty()
            val hasMarks = bookName != null && db.prepareStatement(
                "select 1 from bookmark where book_name=? and coalesce(book_author,'')=? limit 1"
            ).use { it.setString(1, bookName); it.setString(2, normalizedAuthor); it.executeQuery().next() }
            if (key != null && hasMarks && bookName != null) {
                // 已有登记（比如书签页上传过封面）就不覆盖，保留用户显式设置的优先
                db.prepareStatement(
                    """
                    insert into book_cover(book_name,book_author,cover_key,missing_attempts,last_attempt_at,updated_at)
                    values(?,?,?,0,?,?)
                    on conflict(book_name,book_author) do update set
                      cover_key = coalesce(book_cover.cover_key, excluded.cover_key),
                      updated_at = excluded.updated_at
                    """.trimIndent()
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    stmt.setString(1, bookName)
                    stmt.setString(2, normalizedAuthor)
                    stmt.setString(3, key)
                    stmt.setLong(4, now)
                    stmt.setLong(5, now)
                    stmt.executeUpdate()
                }
            }
            val orphan = key?.takeIf { value ->
                val usedByShelf = db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); it.executeQuery().next() }
                if (usedByShelf) return@takeIf false
                if (hasMarks) return@takeIf false
                val usedByRegister = db.prepareStatement("select 1 from book_cover where cover_key=?").use { it.setString(1, value); it.executeQuery().next() }
                !usedByRegister
            }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    fun switchBookshelf(oldSourceId: String, oldBookUrl: String, request: BookshelfWriteRequest, cover: CachedCover?, alternateSources: List<SearchResult>? = null): Pair<BookshelfItem, String?> = write { db ->
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            var oldCover: String? = null
            var oldGroup: String? = null
            db.prepareStatement("select cover_key, group_name from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, oldSourceId); it.setString(2, oldBookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        oldCover = rs.getString(1)
                        oldGroup = rs.getString(2)
                    }
                }
            }
            val existingAlts = db.prepareStatement("select alternate_sources from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, oldSourceId); it.setString(2, oldBookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1)?.let { raw -> runCatching { Json.decodeFromString<List<SearchResult>>(raw) }.getOrNull() } else null
                }
            } ?: emptyList()
            val oldSourceResult = SearchResult(sourceId = oldSourceId, name = request.name, author = request.author, bookUrl = oldBookUrl)
            val incomingAlts = alternateSources ?: request.alternateSources ?: emptyList()
            val combinedAlts = (listOf(oldSourceResult) + existingAlts + incomingAlts)
                .filter { it.sourceId != request.sourceId || it.bookUrl != request.bookUrl }
                .distinctBy { "${it.sourceId}\u0000${it.bookUrl}" }
            val altJson = Json.encodeToString(combinedAlts)
            val cleanGroup = request.groupName?.trim()?.takeIf { it.isNotEmpty() } ?: oldGroup

            db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_content_cache where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_cache_status where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_toc_cache where source_id=? and (toc_url=? or toc_url like ?)").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.setString(3, "%$oldBookUrl%"); it.executeUpdate() }
            db.prepareStatement("delete from book_chapter_index where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            cover?.let { value -> db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use { it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate() } }
            val newCoverKey = cover?.key ?: oldCover
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources,group_name,book_kind) values(?,?,?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at,alternate_sources=excluded.alternate_sources,group_name=coalesce(excluded.group_name,book_shelf.group_name),book_kind=coalesce(excluded.book_kind,book_shelf.book_kind)""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, request.name); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl); it.setString(7, newCoverKey); it.setLong(8, now); it.setString(9, altJson); it.setString(10, cleanGroup); it.setString(11, request.bookKind); it.executeUpdate()
            }
            val orphan = oldCover?.takeIf { value -> value != newCoverKey && db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!! to orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }

    fun listBookGroups(): List<BookGroup> = connect { db ->
        db.prepareStatement("""
            select g.id, g.name, g.sort_order, count(s.book_url) as book_count, g.is_private
            from book_group g
            left join book_shelf s on s.group_name = g.name collate nocase
            group by g.id, g.name, g.sort_order, g.is_private
            order by g.sort_order asc, g.id asc
        """).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            BookGroup(
                                id = rs.getLong(1),
                                name = rs.getString(2),
                                sortOrder = rs.getInt(3),
                                bookCount = rs.getInt(4),
                                isPrivate = rs.getInt(5) == 1,
                            )
                        )
                    }
                }
            }
        }
    }

    fun getBookGroup(name: String): BookGroup? = connect { db ->
        db.prepareStatement("""
            select g.id, g.name, g.sort_order, count(s.book_url) as book_count, g.is_private
            from book_group g
            left join book_shelf s on s.group_name = g.name collate nocase
            where g.name = ? collate nocase
            group by g.id, g.name, g.sort_order, g.is_private
        """).use { stmt ->
            stmt.setString(1, name.trim())
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    BookGroup(
                        id = rs.getLong(1),
                        name = rs.getString(2),
                        sortOrder = rs.getInt(3),
                        bookCount = rs.getInt(4),
                        isPrivate = rs.getInt(5) == 1,
                    )
                } else null
            }
        }
    }

    fun createBookGroup(name: String, isPrivate: Boolean = false): BookGroup = write { db ->
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名称不能为空" }
        val exists = db.prepareStatement("select count(*) from book_group where name=? collate nocase").use { stmt ->
            stmt.setString(1, trimmed)
            stmt.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        require(!exists) { "分组「$trimmed」已存在" }
        val maxOrder = db.prepareStatement("select coalesce(max(sort_order), 0) from book_group").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val now = System.currentTimeMillis()
        try {
            db.prepareStatement("insert into book_group(name, sort_order, is_private, created_at) values(?, ?, ?, ?)").use { stmt ->
                stmt.setString(1, trimmed)
                stmt.setInt(2, maxOrder + 1)
                stmt.setInt(3, if (isPrivate) 1 else 0)
                stmt.setLong(4, now)
                stmt.executeUpdate()
            }
        } catch (error: java.sql.SQLException) {
            // 上面的 count 查重与 insert 之间有竞态窗口；PG 的 `unique(lower(name))`（对齐 SQLite 的
            // `unique collate nocase`）会把并发/大小写变体挡在数据库层 ⇒ 这里翻成与查重一致的用户提示，
            // 别把 SQL 异常漏成 500。
            if (isUniqueViolation(error)) throw IllegalArgumentException("分组「$trimmed」已存在")
            throw error
        }
        db.prepareStatement("select id, name, sort_order, is_private from book_group where name=? collate nocase").use { stmt ->
            stmt.setString(1, trimmed)
            stmt.executeQuery().use { rs ->
                if (rs.next()) BookGroup(rs.getLong(1), rs.getString(2), rs.getInt(3), 0, rs.getInt(4) == 1)
                else throw IllegalStateException("创建分组失败")
            }
        }
    }

    fun updateBookGroupPrivacy(name: String, isPrivate: Boolean): BookGroup = write { db ->
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "分组名称不能为空" }
        val updated = db.prepareStatement("update book_group set is_private=? where name=? collate nocase").use { stmt ->
            stmt.setInt(1, if (isPrivate) 1 else 0)
            stmt.setString(2, trimmed)
            stmt.executeUpdate() > 0
        }
        if (!updated) throw NoSuchElementException("原分组不存在")
        getBookGroup(trimmed) ?: throw IllegalStateException("更新分组私密状态失败")
    }

    fun renameBookGroup(oldName: String, newName: String): BookGroup = write { db ->
        val oldTrimmed = oldName.trim()
        val newTrimmed = newName.trim()
        require(oldTrimmed.isNotBlank()) { "原分组名称不能为空" }
        require(newTrimmed.isNotBlank()) { "新分组名称不能为空" }
        if (oldTrimmed.equals(newTrimmed, ignoreCase = true)) {
            val group = getBookGroup(newTrimmed) ?: throw NoSuchElementException("原分组不存在")
            return@write group
        }
        val exists = db.prepareStatement("select count(*) from book_group where name=? collate nocase").use { stmt ->
            stmt.setString(1, newTrimmed)
            stmt.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        require(!exists) { "分组「$newTrimmed」已存在" }
        db.autoCommit = false
        try {
            db.prepareStatement("update book_group set name=? where name=? collate nocase").use { stmt ->
                stmt.setString(1, newTrimmed)
                stmt.setString(2, oldTrimmed)
                val count = stmt.executeUpdate()
                if (count == 0) throw NoSuchElementException("原分组不存在")
            }
            db.prepareStatement("update book_shelf set group_name=? where group_name=? collate nocase").use { stmt ->
                stmt.setString(1, newTrimmed)
                stmt.setString(2, oldTrimmed)
                stmt.executeUpdate()
            }
            db.commit()
            getBookGroup(newTrimmed) ?: throw IllegalStateException("更新分组失败")
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun deleteBookGroup(name: String): Boolean = write { db ->
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@write false
        db.autoCommit = false
        try {
            db.prepareStatement("update book_shelf set group_name=null where group_name=? collate nocase").use { stmt ->
                stmt.setString(1, trimmed)
                stmt.executeUpdate()
            }
            val deleted = db.prepareStatement("delete from book_group where name=? collate nocase").use { stmt ->
                stmt.setString(1, trimmed)
                stmt.executeUpdate() > 0
            }
            db.commit()
            deleted
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun updateBookGroupsOrder(groupNames: List<String>): List<BookGroup> = write { db ->
        db.autoCommit = false
        try {
            groupNames.forEachIndexed { index, groupName ->
                db.prepareStatement("update book_group set sort_order=? where name=? collate nocase").use { stmt ->
                    stmt.setInt(1, index + 1)
                    stmt.setString(2, groupName.trim())
                    stmt.executeUpdate()
                }
            }
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        listBookGroups()
    }

    fun updateBookGroup(sourceId: String, bookUrl: String, groupName: String?): BookshelfItem? = write { db ->
        val cleanGroup = groupName?.trim()?.takeIf { it.isNotEmpty() }
        db.prepareStatement("update book_shelf set group_name=? where source_id=? and book_url=?").use { stmt ->
            stmt.setString(1, cleanGroup)
            stmt.setString(2, sourceId)
            stmt.setString(3, bookUrl)
            stmt.executeUpdate()
        }
        getBookshelf(db, sourceId, bookUrl)
    }

    fun batchBookshelfOperation(request: BookshelfBatchRequest, onRemoveCover: (String) -> Unit = {}): Int = write { db ->
        if (request.items.isEmpty()) return@write 0
        db.autoCommit = false
        try {
            var affected = 0
            when (request.action) {
                "move_group" -> {
                    val target = request.targetGroup?.trim()?.takeIf { it.isNotEmpty() }
                    db.prepareStatement("update book_shelf set group_name=? where source_id=? and book_url=?").use { stmt ->
                        for (item in request.items) {
                            stmt.setString(1, target)
                            stmt.setString(2, item.sourceId)
                            stmt.setString(3, item.bookUrl)
                            stmt.addBatch()
                        }
                        affected = stmt.executeBatch().count { it > 0 || it == java.sql.Statement.SUCCESS_NO_INFO }
                    }
                }
                "mark_completed" -> {
                    val comp = if (request.completed == true) 1 else 0
                    db.prepareStatement("update book_shelf set completed=? where source_id=? and book_url=?").use { stmt ->
                        for (item in request.items) {
                            stmt.setInt(1, comp)
                            stmt.setString(2, item.sourceId)
                            stmt.setString(3, item.bookUrl)
                            stmt.addBatch()
                        }
                        affected = stmt.executeBatch().count { it > 0 || it == java.sql.Statement.SUCCESS_NO_INFO }
                    }
                }
                "delete" -> {
                    val orphanCovers = mutableListOf<String>()
                    for (item in request.items) {
                        val key = db.prepareStatement("select cover_key from book_shelf where source_id=? and book_url=?").use {
                            it.setString(1, item.sourceId); it.setString(2, item.bookUrl)
                            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                        }
                        db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, item.sourceId); it.setString(2, item.bookUrl); it.executeUpdate() }
                        db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, item.sourceId); it.setString(2, item.bookUrl); it.executeUpdate() }
                        db.prepareStatement("delete from book_content_cache where source_id=? and book_url=?").use { it.setString(1, item.sourceId); it.setString(2, item.bookUrl); it.executeUpdate() }
                        db.prepareStatement("delete from book_cache_status where source_id=? and book_url=?").use { it.setString(1, item.sourceId); it.setString(2, item.bookUrl); it.executeUpdate() }
                        db.prepareStatement("delete from book_chapter_index where source_id=? and book_url=?").use { it.setString(1, item.sourceId); it.setString(2, item.bookUrl); it.executeUpdate() }
                        val orphan = key?.takeIf { value -> db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
                        orphan?.let { value ->
                            db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() }
                            orphanCovers.add(value)
                        }
                        affected++
                    }
                    orphanCovers.forEach(onRemoveCover)
                }
            }
            db.commit()
            affected
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }
    fun coverContentType(key: String): String? = connect { db -> db.prepareStatement("select content_type from cover_cache where cache_key=?").use { it.setString(1, key); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } } }

    fun getTocCache(sourceId: String, tocUrl: String): List<Chapter>? = connect { db ->
        db.prepareStatement("select chapters_json from book_toc_cache where source_id = ? and toc_url = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, tocUrl)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    runCatching { Json.decodeFromString<List<Chapter>>(rs.getString(1)) }.getOrNull()
                } else null
            }
        }
    }

    /**
     * 从目录缓存里找出某章标题。
     *
     * 进度文件（`bookProgress` 下的 JSON）需要写 `durChapterTitle`，而前端只传章节 URL/index，
     * 因此服务端需要回查标题。查不到返回 null（调用方应放弃写文件，而不是写个空标题）。
     *
     * 注意 toc_url 可能有多个变体（如带/不带查询串），所以用 like 兜底匹配。
     */
    fun getChapterTitle(sourceId: String, bookUrl: String, chapterUrl: String): String? {
        val sql = "select chapters_json from book_toc_cache where source_id = ? and (toc_url = ? or toc_url like ?) order by updated_at desc limit 5"
        return connect { db ->
            db.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sourceId); stmt.setString(2, bookUrl); stmt.setString(3, "%$bookUrl%")
                stmt.executeQuery().use { rs ->
                    var found: String? = null
                    while (found == null && rs.next()) {
                        val chapters = runCatching { Json.decodeFromString<List<Chapter>>(rs.getString(1)) }.getOrNull() ?: continue
                        found = chapters.firstOrNull { it.url == chapterUrl }?.title
                    }
                    found
                }
            }
        }
    }

    fun saveTocCache(sourceId: String, tocUrl: String, chapters: List<Chapter>) = write { db ->
        db.prepareStatement("""
            insert into book_toc_cache(source_id, toc_url, chapters_json, updated_at)
            values(?, ?, ?, ?)
            on conflict(source_id, toc_url) do update set
                chapters_json = excluded.chapters_json, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, tocUrl)
            stmt.setString(3, Json.encodeToString(chapters))
            stmt.setLong(4, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        // 目录缓存落库的同时把章名摊平进检索索引（全局搜索的「目录搜索」靠它）。
        // `tocUrl` 可能是 `/toc` 变体 ⇒ 用书架行反查真正的 `book_url` 再落索引；
        // 反查不到（书不在书架）就**跳过**：目录搜索本来就只针对书架里的书。
        val shelfBookUrl = resolveShelfBookUrl(db, sourceId, tocUrl)
        if (shelfBookUrl != null) replaceChapterIndex(db, sourceId, shelfBookUrl, chapters)
    }

    /**
     * 由 `book_toc_cache.toc_url` 反查书架上的 `book_url`。
     *
     * 只做**精确匹配**（`book_url` 或 `toc_url` 相等）：`like %…%` 在几千本书上是
     * 全表扫描且可能匹配到别的书，宁可少索引几本，也不能把章名挂到错的书上。
     */
    private fun resolveShelfBookUrl(db: Connection, sourceId: String, tocUrl: String): String? =
        db.prepareStatement("select book_url from book_shelf where source_id = ? and (book_url = ? or toc_url = ?) limit 1").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, tocUrl)
            stmt.setString(3, tocUrl)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /**
     * 用一本书的完整目录**整体替换**它的章节索引（先删后插，语义与 `book_toc_cache` 一致）。
     *
     * 为什么是「替换」而不是「增量」：目录会被刷新/换源重排，章数变少时旧行必须消失，
     * 否则目录搜索会命中已经不存在的章。
     *
     * ⚠️ `on conflict … do update` 不能省：真实目录里同一个 `chapter_url` 出现两次是常态
     * （书源的"最新章节"区会重复列一遍），没有它整批 insert 会因主键冲突全部失败。
     */
    private fun replaceChapterIndex(db: Connection, sourceId: String, bookUrl: String, chapters: List<Chapter>) {
        val ownTransaction = db.autoCommit
        if (ownTransaction) db.autoCommit = false
        try {
            db.prepareStatement("delete from book_chapter_index where source_id = ? and book_url = ?").use { stmt ->
                stmt.setString(1, sourceId)
                stmt.setString(2, bookUrl)
                stmt.executeUpdate()
            }
            if (chapters.isNotEmpty()) {
                db.prepareStatement("""
                    insert into book_chapter_index(source_id, book_url, chapter_url, chapter_index, title)
                    values(?, ?, ?, ?, ?)
                    on conflict(source_id, book_url, chapter_url) do update set
                        chapter_index = excluded.chapter_index, title = excluded.title
                """.trimIndent()).use { stmt ->
                    chapters.forEachIndexed { position, chapter ->
                        stmt.setString(1, sourceId)
                        stmt.setString(2, bookUrl)
                        stmt.setString(3, chapter.url)
                        // 存**目录里的位置**（数组下标）而不是 `Chapter.index`：
                        // 前端 `changeChapter(index)` / `matchBestChapter` 用的都是数组下标，
                        // 而 `Chapter.index` 是书源给的、可能从 1 开始或干脆乱序。
                        stmt.setInt(4, position)
                        stmt.setString(5, chapter.title)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
            }
            if (ownTransaction) db.commit()
        } catch (error: Throwable) {
            if (ownTransaction) runCatching { db.rollback() }
            throw error
        } finally {
            if (ownTransaction) db.autoCommit = true
        }
    }

    // ---------------------------------------------------------------------
    // 全局搜索（书架 / 目录 / 正文）
    //
    // 三条查询都**只用 SQLite 与 PostgreSQL 都支持**的语法：`lower()` 做大小写不敏感、
    // `coalesce()` 兜空、`limit ?` 传参。没有 rowid / insert or / pragma / strftime
    // （`order by rowid` 让 PG 秒 500 的教训见 AGENTS.md）。
    // ---------------------------------------------------------------------

    /**
     * 书架搜索：书名或作者命中。
     *
     * 排序用 `last_read_at desc`，与书架列表同源 —— 用户最近读的书排前面最符合直觉。
     */
    fun searchShelf(query: String, limit: Int): List<SearchHit> = connect { db ->
        val like = "%$query%"
        db.prepareStatement(
            """
            select source_id, book_url, name, author, cover_key
            from book_shelf
            where lower(name) like lower(?) or lower(coalesce(author, '')) like lower(?)
            order by last_read_at desc
            limit ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setString(1, like)
            stmt.setString(2, like)
            stmt.setInt(3, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SearchHit(
                                kind = "shelf",
                                sourceId = rs.getString(1),
                                bookUrl = rs.getString(2),
                                bookName = rs.getString(3),
                                bookAuthor = rs.getString(4),
                                coverKey = rs.getString(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 目录搜索：在**已抓到目录**的书里按章名找。
     *
     * join `book_shelf` 是为了带出书名与封面（章名本身对用户没意义）。
     * 传了 `sourceId` + `bookUrl` 时收窄到单本书（阅读器里的「只搜当前书」）。
     */
    fun searchToc(query: String, limit: Int, sourceId: String? = null, bookUrl: String? = null): List<SearchHit> = connect { db ->
        val like = "%$query%"
        val where = StringBuilder("lower(c.title) like lower(?)")
        val params = mutableListOf<String>(like)
        if (!sourceId.isNullOrBlank()) { where.append(" and c.source_id = ?"); params.add(sourceId) }
        if (!bookUrl.isNullOrBlank()) { where.append(" and c.book_url = ?"); params.add(bookUrl) }
        val sql = """
            select c.source_id, c.book_url, c.chapter_url, c.chapter_index, c.title,
                   s.name, s.author, s.cover_key
            from book_chapter_index c
            left join book_shelf s on s.source_id = c.source_id and s.book_url = c.book_url
            where $where
            order by s.last_read_at desc, c.chapter_index asc
            limit ?
        """.trimIndent()
        db.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, value -> stmt.setString(index + 1, value) }
            stmt.setInt(params.size + 1, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            SearchHit(
                                kind = "toc",
                                sourceId = rs.getString(1),
                                bookUrl = rs.getString(2),
                                chapterUrl = rs.getString(3),
                                chapterIndex = rs.getInt(4),
                                chapterTitle = rs.getString(5),
                                bookName = rs.getString(6).orEmpty(),
                                bookAuthor = rs.getString(7),
                                coverKey = rs.getString(8),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 正文搜索：在**已缓存正文**里找，服务端生成 snippet。
     *
     * `lower(content) like lower(?)` 在大表上是顺序扫描，但正文缓存本来就是"读过的书"的
     * 子集，配合 `limit` 足够；真正的性能上限由前端 debounce（300ms）挡在前面。
     */
    fun searchContent(query: String, limit: Int, sourceId: String? = null, bookUrl: String? = null): List<SearchHit> = connect { db ->
        val like = "%$query%"
        val where = StringBuilder("lower(cc.content) like lower(?)")
        val params = mutableListOf<String>(like)
        if (!sourceId.isNullOrBlank()) { where.append(" and cc.source_id = ?"); params.add(sourceId) }
        if (!bookUrl.isNullOrBlank()) { where.append(" and cc.book_url = ?"); params.add(bookUrl) }
        val sql = """
            select cc.source_id, cc.book_url, cc.chapter_url, cc.title, cc.content,
                   s.name, s.author, s.cover_key, c.chapter_index
            from book_content_cache cc
            left join book_shelf s on s.source_id = cc.source_id and s.book_url = cc.book_url
            left join book_chapter_index c
                   on c.source_id = cc.source_id and c.book_url = cc.book_url and c.chapter_url = cc.chapter_url
            where $where
            -- 正文命中必须有稳定顺序：按书的最近阅读时间、再按章节序号。
            -- 否则 PG 会按物理堆顺序返回，阅读器里第 80 章可能排在第一章前面。
            order by coalesce(s.last_read_at, 0) desc,
                     coalesce(c.chapter_index, 2147483647) asc,
                     cc.chapter_url asc
            limit ?
        """.trimIndent()
        db.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, value -> stmt.setString(index + 1, value) }
            stmt.setInt(params.size + 1, limit)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val content = rs.getString(5).orEmpty()
                        val chapterIndex = rs.getInt(9).let { if (rs.wasNull()) null else it }
                        add(
                            SearchHit(
                                kind = "content",
                                sourceId = rs.getString(1),
                                bookUrl = rs.getString(2),
                                chapterUrl = rs.getString(3),
                                chapterTitle = rs.getString(4),
                                snippet = buildSearchSnippet(content, query),
                                bookName = rs.getString(6).orEmpty(),
                                bookAuthor = rs.getString(7),
                                coverKey = rs.getString(8),
                                chapterIndex = chapterIndex,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 启动时**一次性回填**章节索引：扫「在书架里、但 `book_chapter_index` 一行都没有」的书。
     *
     * 为什么要它：「目录搜索」是本次新增的能力，存量库里 `book_toc_cache` 已经有几千本书的目录，
     * 不回填的话老用户搜不到任何东西（新数据不再出错 ≠ 老数据自动好了）。
     *
     * 幂等：只挑完全没进索引的书；每轮限 [limit] 本（默认 50），失败的书静默跳过并记日志
     * （单本书的坏 JSON 不该拖垮启动）。返回本轮实际写入索引的书数。
     */
    fun backfillChapterIndex(limit: Int = CHAPTER_INDEX_BACKFILL_LIMIT): Int = write { db ->
        backfillChapterIndex(db, limit)
    }

    private fun backfillChapterIndex(db: Connection, limit: Int): Int {
        // 三元组：source_id / book_url / **书架存的 toc_url**。
        // 必须带上 toc_url：聚合源的 book_url 与 toc_url 只在尾部类型字段不同
        //（如 `{"type":"qingtian"}` vs `{"type":"qingtian2"}`），只按 book_url 反查会静默跳过，
        // 表现为「这本书目录搜不到」。
        val targets = mutableListOf<Triple<String, String, String?>>()
        db.prepareStatement(
            """
            select s.source_id, s.book_url, s.toc_url
            from book_shelf s
            where not exists (
                select 1 from book_chapter_index c
                where c.source_id = s.source_id and c.book_url = s.book_url
            )
            order by s.last_read_at desc
            limit ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) targets.add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
        }
        var indexed = 0
        for ((sourceId, bookUrl, shelfTocUrl) in targets) {
            val chapters = runCatching { cachedTocChapters(db, sourceId, bookUrl, shelfTocUrl) }.getOrNull().orEmpty()
            if (chapters.isEmpty()) continue
            val ok = runCatching {
                replaceChapterIndex(db, sourceId, bookUrl, chapters)
            }.isSuccess
            if (ok) {
                indexed++
            } else {
                println("[db] 章节索引回填跳过（写入失败）：$sourceId / $bookUrl")
            }
        }
        if (indexed > 0) println("[db] 章节索引回填：$indexed 本书")
        return indexed
    }

    /**
     * 取一本书的目录缓存（回填用）。
     *
     * `toc_url` 有多个变体（书源详情给的 tocUrl、书架存的 tocUrl、以及 `like %bookUrl%` 命中的），
     * 与 [getChapterTitle] 同一套兜底口径：按 `updated_at desc` 取最新几条，第一条能解析的胜出。
     */
    private fun cachedTocChapters(db: Connection, sourceId: String, bookUrl: String, tocUrl: String? = null): List<Chapter> {
        // 命中优先级：书架 toc_url 精确 → book_url 精确 → 双向包含（`? like '%' || toc_url || '%'`）。
        // 聚合源的 book_url / toc_url 只在尾部类型字段上不同，只按 book_url 反查会漏（实测「目录搜不到」）。
        val sql = """
            select chapters_json from book_toc_cache
            where source_id = ? and (
                toc_url = ? or toc_url = ? or toc_url like ? or ? like '%' || toc_url || '%'
            )
            order by updated_at desc limit 8
        """.trimIndent()
        db.prepareStatement(sql).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, tocUrl ?: "")
            stmt.setString(3, bookUrl)
            stmt.setString(4, "%$bookUrl%")
            stmt.setString(5, bookUrl)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val chapters = runCatching { Json.decodeFromString<List<Chapter>>(rs.getString(1)) }.getOrNull()
                    if (!chapters.isNullOrEmpty()) return chapters
                }
            }
        }
        return emptyList()
    }

    fun importLocalBook(
        bookId: String,
        parsed: ParsedBook,
        coverKey: String?,
    ): BookshelfItem = write { db ->
        db.autoCommit = false
        try {
            val sourceId = LocalBookParser.LOC_BOOK_SOURCE_ID
            val bookUrl = "local://$bookId"
            val tocUrl = "local://$bookId/toc"
            val now = System.currentTimeMillis()

            // 1. Insert/update book_shelf
            // `book_kind` 显式写 `local`（issue #66）：本地书自成一类，四类角标（本地/漫画/听书/小说）互斥。
            // 本地书**定义上**就是 local，因此 on conflict 直接覆盖（不像在线书那样 coalesce 保留旧值），
            // 这样历史被误写成 `novel` 的本地书在重新导入时也会被纠正。
            db.prepareStatement("""
                insert into book_shelf(source_id, book_url, name, author, toc_url, cover_url, cover_key, last_read_at, completed, alternate_sources, book_kind)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(source_id, book_url) do update set
                    name = excluded.name, author = excluded.author, toc_url = excluded.toc_url,
                    cover_key = coalesce(excluded.cover_key, book_shelf.cover_key),
                    last_read_at = excluded.last_read_at,
                    book_kind = excluded.book_kind
            """.trimIndent()).use { stmt ->
                stmt.setString(1, sourceId)
                stmt.setString(2, bookUrl)
                stmt.setString(3, parsed.title)
                stmt.setString(4, parsed.author)
                stmt.setString(5, tocUrl)
                stmt.setString(6, null)
                stmt.setString(7, coverKey)
                stmt.setLong(8, now)
                stmt.setInt(9, 0)
                stmt.setString(10, "[]")
                stmt.setString(11, BookKind.LOCAL.wire)
                stmt.executeUpdate()
            }

            // 2. Insert cover into cover_cache if present
            if (coverKey != null && parsed.coverContentType != null) {
                db.prepareStatement("insert into cover_cache(cache_key, content_type) values(?, ?) on conflict(cache_key) do update set content_type=excluded.content_type").use {
                    it.setString(1, coverKey)
                    it.setString(2, parsed.coverContentType)
                    it.executeUpdate()
                }
            }

            // 3~5. 目录 + 正文 + 缓存状态
            writeLocalBookCaches(db, bookId, parsed, now)

            db.commit()
            getBookshelf(db, sourceId, bookUrl)!!
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    /**
     * 写入本地书的目录 + 正文 + 缓存状态。
     *
     * 「首次导入」([importLocalBook]) 与「存量重新解析」([replaceLocalBookCache]) 共用这一段落盘路径，
     * 避免两条代码路径各自演化出不一致的缓存形状（目录键、章节 URL 规则、双副本都需要完全一致）。
     *
     * 调用方负责事务：两处调用点都已在 `autoCommit = false` 下执行，成功各自 `commit()`、失败 `rollback()`。
     *
     * @return 写入的章节数
     */
    private fun writeLocalBookCaches(db: Connection, bookId: String, parsed: ParsedBook, now: Long): Int {
        val sourceId = LocalBookParser.LOC_BOOK_SOURCE_ID
        val bookUrl = "local://$bookId"
        val tocUrl = "local://$bookId/toc"

        // 1. 目录缓存：`tocUrl` 与 `bookUrl` 两个键都要写。
        //    前者是标准目录接口用的键，后者是 `getCachedChaptersFallback` 的兜底查询键。
        val chapters = parsed.chapters.mapIndexed { idx, ch ->
            Chapter(
                index = idx,
                title = ch.title,
                url = "local://$bookId/chapter_${idx + 1}"
            )
        }
        db.prepareStatement("""
            insert into book_toc_cache(source_id, toc_url, chapters_json, updated_at)
            values(?, ?, ?, ?)
            on conflict(source_id, toc_url) do update set
                chapters_json = excluded.chapters_json, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, tocUrl)
            stmt.setString(3, Json.encodeToString(chapters))
            stmt.setLong(4, now)
            stmt.executeUpdate()
        }
        db.prepareStatement("""
            insert into book_toc_cache(source_id, toc_url, chapters_json, updated_at)
            values(?, ?, ?, ?)
            on conflict(source_id, toc_url) do update set
                chapters_json = excluded.chapters_json, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, bookUrl)
            stmt.setString(3, Json.encodeToString(chapters))
            stmt.setLong(4, now)
            stmt.executeUpdate()
        }
        // 目录摊平进检索索引（本地书同样要能被「目录搜索」找到）。
        // 两个 toc 键（`tocUrl` / `bookUrl`）指向同一份目录 ⇒ 只需要索引一次。
        replaceChapterIndex(db, sourceId, bookUrl, chapters)

        // 2. 正文缓存：先清后写，保证重新解析后不残留旧章节行（章节数变少时尤其重要）。
        db.prepareStatement("delete from book_content_cache where source_id = ? and book_url = ?").use {
            it.setString(1, sourceId)
            it.setString(2, bookUrl)
            it.executeUpdate()
        }
        db.prepareStatement("""
            insert into book_content_cache(source_id, book_url, chapter_url, title, content, cached_at, raw_title, raw_content)
            values(?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            chapters.forEachIndexed { idx, ch ->
                val contentText = parsed.chapters.getOrNull(idx)?.content ?: ""
                stmt.setString(1, sourceId)
                stmt.setString(2, bookUrl)
                stmt.setString(3, ch.url)
                stmt.setString(4, ch.title)
                stmt.setString(5, contentText)
                stmt.setLong(6, now)
                stmt.setString(7, ch.title)
                stmt.setString(8, contentText)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }

        // 3. 缓存状态置为 ready（本地书不存在「待抓取」中间态）
        db.prepareStatement("""
            insert into book_cache_status(source_id, book_url, total_chapters, cached_chapters, state, last_error, updated_at)
            values(?, ?, ?, ?, 'ready', null, ?)
            on conflict(source_id, book_url) do update set
                total_chapters = excluded.total_chapters, cached_chapters = excluded.cached_chapters,
                state = 'ready', last_error = null, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, bookUrl)
            stmt.setInt(3, chapters.size)
            stmt.setInt(4, chapters.size)
            stmt.setLong(5, now)
            stmt.executeUpdate()
        }

        return chapters.size
    }

    /**
     * 用重新解析的结果**覆盖**已有本地书的目录与正文缓存（只动缓存，不碰书架行）。
     *
     * 存在理由：本地书（尤其 EPUB）的目录与正文是在**导入那一刻**解析落库的，
     * 因此解析器的缺陷修好之后，**老数据不会自动变好**——正文段落边界在导入时已被压成单行、
     * 信息不可逆丢失，替换净化规则也救不回来，只能重新解析。
     * 原始文件一直保留在 `local_books` 目录（`<bookId>.<ext>`），所以可以直接就地重解析，
     * 用户既不用重新上传，`local://<bookId>` 也不用变 ⇒ 书架元数据、分组、封面、
     * 阅读进度与书签全部延续。
     *
     * 幂等：同一本书反复调用结果一致（先清后写，章节 URL 由序号推导）。
     *
     * @return 写入的章节数；书籍不在书架中时返回 0（拒绝为孤儿文件凭空造缓存）
     */
    fun replaceLocalBookCache(bookId: String, parsed: ParsedBook): Int = write { db ->
        val sourceId = LocalBookParser.LOC_BOOK_SOURCE_ID
        val bookUrl = "local://$bookId"
        db.autoCommit = false
        try {
            if (getBookshelf(db, sourceId, bookUrl) == null) {
                db.rollback()
                0
            } else {
                val count = writeLocalBookCaches(db, bookId, parsed, System.currentTimeMillis())
                db.commit()
                count
            }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun getCachedChaptersFallback(sourceId: String, bookUrl: String): List<Chapter> = connect { db ->
        db.prepareStatement("""
            select chapter_url, title from book_content_cache
            where source_id = ? and (book_url = ? or chapter_url like ?)
            -- 不能用 rowid（SQLite 专有）：PG 没有该列，之前会让 /books/chapters 直接 500。
            -- cached_at + chapter_url 是跨引擎可用的稳定顺序（缓存行按下载顺序写入）。
            order by cached_at asc, chapter_url asc
        """.trimIndent()).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, bookUrl)
            stmt.setString(3, "%$bookUrl%")
            stmt.executeQuery().use { rs ->
                buildList {
                    var index = 0
                    while (rs.next()) {
                        val url = rs.getString(1)
                        val title = rs.getString(2)?.ifBlank { null } ?: "第 ${index + 1} 章"
                        add(Chapter(index++, title, url))
                    }
                }
            }
        }
    }

    fun cachedContent(sourceId: String, bookUrl: String, chapterUrl: String): ChapterContent? = connect { db ->
        db.prepareStatement("select title, content, raw_title, raw_content from book_content_cache where source_id=? and book_url=? and chapter_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl)
            it.executeQuery().use { rs ->
                if (rs.next()) {
                    val title = rs.getString(1)
                    val content = rs.getString(2)
                    val rawTitle = rs.getString(3) ?: title
                    val rawContent = rs.getString(4) ?: content
                    ChapterContent(title, content, rawTitle, rawContent)
                } else null
            }
        }
    }
    fun cachedChapterUrls(sourceId: String, bookUrl: String): Set<String> = connect { db ->
        db.prepareStatement("select chapter_url from book_content_cache where source_id = ? and book_url = ?").use { statement ->
            statement.setString(1, sourceId)
            statement.setString(2, bookUrl)
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString(1))
                }
            }
        }
    }
    /**
     * 删除一本书的正文缓存（仅"服务端缓存"范围会走到这里）。
     *
     * **本地书恒不删正文**（issue #66 的数据层守卫）：本地书的正文是用户上传的原文件解析后落库的，
     * 线上没有"下次阅读会重新下载"这一步 —— 删了只能靠「重新解析本地文件」从留存的原文件重建。
     * 前端已经不给本地书任何缓存/清缓存入口，这道守卫是**防 UI 绕过**（直接打 HTTP 也一样安全），
     * 因此放在数据层而不是路由层。
     *
     * @return 实际删除的正文行数；本地书恒为 0
     */
    fun clearBookCacheContent(sourceId: String, bookUrl: String): Int = write { db ->
        if (BookKind.isLocalRef(sourceId, bookUrl)) return@write 0
        val deleted = db.prepareStatement("delete from book_content_cache where source_id = ? and book_url = ?").use { statement ->
            statement.setString(1, sourceId)
            statement.setString(2, bookUrl)
            statement.executeUpdate()
        }
        db.prepareStatement("update book_cache_status set cached_chapters = 0, state = 'idle', last_error = null, updated_at = ? where source_id = ? and book_url = ?").use { statement ->
            statement.setLong(1, System.currentTimeMillis())
            statement.setString(2, sourceId)
            statement.setString(3, bookUrl)
            statement.executeUpdate()
        }
        deleted
    }
    fun updateBookCacheProgress(sourceId: String, bookUrl: String, cachedCount: Int) = write { db ->
        db.prepareStatement("update book_cache_status set cached_chapters = ?, updated_at = ? where source_id = ? and book_url = ?").use { statement ->
            statement.setInt(1, cachedCount)
            statement.setLong(2, System.currentTimeMillis())
            statement.setString(3, sourceId)
            statement.setString(4, bookUrl)
            statement.executeUpdate()
        }
    }
    fun cacheBookContent(sourceId: String, bookUrl: String, chapterUrl: String, content: ChapterContent): Unit = write { db ->
        db.prepareStatement("""
            insert into book_content_cache(source_id, book_url, chapter_url, title, content, cached_at, raw_title, raw_content)
            values(?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(source_id, book_url, chapter_url) do update set
                title = excluded.title,
                content = excluded.content,
                cached_at = excluded.cached_at,
                raw_title = coalesce(excluded.raw_title, book_content_cache.raw_title),
                raw_content = coalesce(excluded.raw_content, book_content_cache.raw_content)
        """.trimIndent()).use { statement ->
            statement.setString(1, sourceId)
            statement.setString(2, bookUrl)
            statement.setString(3, chapterUrl)
            statement.setString(4, content.title)
            statement.setString(5, content.content)
            statement.setLong(6, System.currentTimeMillis())
            statement.setString(7, content.rawTitle ?: content.title)
            statement.setString(8, content.rawContent ?: content.content)
            statement.executeUpdate()
        }
    }
    fun recleanBookCache(sourceId: String, bookUrl: String, jsSandbox: JsSandbox? = null): BookRecleanResponse {
        val shelfItem = listBookshelf().firstOrNull { it.sourceId == sourceId && it.bookUrl == bookUrl }
        val bookName = shelfItem?.name
        val sourceRecord = if (sourceId != LocalBookParser.LOC_BOOK_SOURCE_ID) getSource(sourceId) else null
        val parsedSource = sourceRecord?.let { runCatching { SourceCodec.parse(it.json) }.getOrNull() }
        val sourceUrl = if (sourceId == LocalBookParser.LOC_BOOK_SOURCE_ID) "local://" else parsedSource?.url ?: sourceId
        val sourceName = parsedSource?.name

        val rules = getEnabledReplaceRulesForScope(bookName, sourceUrl, sourceName)

        data class ChapterRaw(val chapterUrl: String, val rawTitle: String?, val rawContent: String)
        val cachedList = connect { db ->
            db.prepareStatement("select chapter_url, coalesce(raw_title, title), coalesce(raw_content, content) from book_content_cache where source_id = ? and book_url = ?").use { stmt ->
                stmt.setString(1, sourceId)
                stmt.setString(2, bookUrl)
                stmt.executeQuery().use { rs ->
                    val list = mutableListOf<ChapterRaw>()
                    while (rs.next()) {
                        list.add(ChapterRaw(rs.getString(1), rs.getString(2), rs.getString(3) ?: ""))
                    }
                    list
                }
            }
        }

        if (cachedList.isEmpty()) {
            return BookRecleanResponse(sourceId, bookUrl, 0, 0)
        }

        val total = cachedList.size
        var recleaned = 0
        write { db ->
            db.prepareStatement("""
                update book_content_cache
                set title = ?, content = ?, raw_title = ?, raw_content = ?
                where source_id = ? and book_url = ? and chapter_url = ?
            """.trimIndent()).use { stmt ->
                for (item in cachedList) {
                    val rawTitle = item.rawTitle
                    val rawContent = item.rawContent
                    val cleanedContent = if (rules.isNotEmpty()) {
                        ContentProcessor.processContent(rawContent, rules, jsSandbox, bookName, rawTitle)
                    } else rawContent
                    val cleanedTitle = if (rules.isNotEmpty() && rawTitle != null) {
                        ContentProcessor.processTitle(rawTitle, rules, jsSandbox, bookName)
                    } else rawTitle

                    stmt.setString(1, cleanedTitle)
                    stmt.setString(2, cleanedContent)
                    stmt.setString(3, rawTitle)
                    stmt.setString(4, rawContent)
                    stmt.setString(5, sourceId)
                    stmt.setString(6, bookUrl)
                    stmt.setString(7, item.chapterUrl)
                    stmt.addBatch()
                    recleaned++
                }
                stmt.executeBatch()
            }
        }

        return BookRecleanResponse(sourceId, bookUrl, recleaned, total)
    }
    fun beginBookCache(sourceId: String, bookUrl: String, total: Int) = write { db -> db.prepareStatement("insert into book_cache_status(source_id,book_url,total_chapters,cached_chapters,state,last_error,updated_at) values(?,?,?,?,?,?,?) on conflict(source_id,book_url) do update set total_chapters=excluded.total_chapters,cached_chapters=excluded.cached_chapters,state=excluded.state,last_error=null,updated_at=excluded.updated_at").use {
        val count = db.prepareStatement("select count(*) from book_content_cache where source_id=? and book_url=?").use { query -> query.setString(1, sourceId); query.setString(2, bookUrl); query.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        it.setString(1, sourceId); it.setString(2, bookUrl); it.setInt(3, total); it.setInt(4, count); it.setString(5, "caching"); it.setString(6, null); it.setLong(7, System.currentTimeMillis()); it.executeUpdate()
    } }
    fun finishBookCache(sourceId: String, bookUrl: String, error: String? = null) = write { db ->
        val state = if (error == null) "ready" else "failed"
        val cachedCount = db.prepareStatement("select count(*) from book_content_cache where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        val existingTotal = db.prepareStatement("select total_chapters from book_cache_status where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        val total = if (existingTotal > 0) existingTotal else cachedCount
        db.prepareStatement("insert into book_cache_status(source_id,book_url,total_chapters,cached_chapters,state,last_error,updated_at) values(?,?,?,?,?,?,?) on conflict(source_id,book_url) do update set total_chapters=excluded.total_chapters,cached_chapters=excluded.cached_chapters,state=excluded.state,last_error=excluded.last_error,updated_at=excluded.updated_at").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.setInt(3, total); it.setInt(4, cachedCount); it.setString(5, state); it.setString(6, error?.take(500)); it.setLong(7, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    /**
     * 保证本地导入书籍的缓存状态为 ready。
     *
     * 本地图书在导入时已由 [LocalBookParser] 解析完毕并全部入库（写入 [book_content_cache]），
     * 严禁被网络爬虫队列覆盖为 failed。
     */
    fun ensureLocalBookStatusReady(sourceId: String, bookUrl: String) = write { db ->
        val cachedCount = db.prepareStatement("select count(*) from book_content_cache where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        val existingTotal = db.prepareStatement("select total_chapters from book_cache_status where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val total = if (existingTotal > 0) existingTotal else if (cachedCount > 0) cachedCount else 1
        db.prepareStatement("""
            insert into book_cache_status(source_id, book_url, total_chapters, cached_chapters, state, last_error, updated_at)
            values(?, ?, ?, ?, 'ready', null, ?)
            on conflict(source_id, book_url) do update set
                total_chapters = case when excluded.cached_chapters > 0 then excluded.cached_chapters else book_cache_status.total_chapters end,
                cached_chapters = case when excluded.cached_chapters > 0 then excluded.cached_chapters else book_cache_status.cached_chapters end,
                state = 'ready',
                last_error = null,
                updated_at = excluded.updated_at
        """.trimIndent()).use {
            it.setString(1, sourceId)
            it.setString(2, bookUrl)
            it.setInt(3, total)
            it.setInt(4, cachedCount)
            it.setLong(5, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    private fun healLocalBookCacheStatus(db: Connection) {
        // 自愈被历史网络缓存误判为失败的本地图书
        db.prepareStatement("""
            update book_cache_status
            set state = 'ready', last_error = null
            where (source_id = 'loc_book' or book_url like 'local://%')
              and state = 'failed'
        """.trimIndent()).use { it.executeUpdate() }
    }

    /**
     * 启动时需要**续做**的缓存任务。
     *
     * 只返回上次未跑完的书（`state` 为 `pending`/`caching`），而不是整张书架。
     *
     * 旧实现是 `select ... from book_shelf`（**无任何过滤**），导致每次重启都把
     * 整架书重新下载一遍：实测 435 本的库启动即打满线程池，`Application started`
     * 之后数分钟出不来的都是这台机器；数据库还会从 13MB 膨胀到 256MB。
     * 其中还包括 Android 的 `content://` 本地书——这类路径在服务端**必然失败**，
     * 纯粹是每次启动重复刷屏 + 白白占用连接。
     *
     * 另外跳过非网络来源的 `book_url`：`content://`（Android 本地文件）、
     * `local://`（本项目本地导入）都不是服务端可抓取的地址。
     */
    /**
     * 自动缓存（**小说**）的候选书：**包含从未缓存过的书**。
     *
     * 与 [cacheRequests] 的差别只在 `state` 口径——那个是「启动续做」语义，只认 `pending/caching`；
     * 自动缓存的目标是「书架里还没缓存完的书」，所以把 **无状态行 / idle / failed** 也算进来，
     * 只排除 `ready`（已完成）与 `caching`（正在跑，避免重复入队）。
     *
     * 排序沿用「最近读的优先」，`limit` 由调用方按每轮本数上限给（多取一些只为把候选数打进日志）。
     */
    fun autoCacheNovelCandidates(limit: Int): List<CachedBookRequest> = connect { db ->
        db.prepareStatement(
            """
            select s.source_id, s.book_url, s.toc_url
            from book_shelf s
            left join book_cache_status c on c.source_id = s.source_id and c.book_url = s.book_url
            where coalesce(s.book_kind, 'novel') = 'novel'
              -- 只排除 ready；caching 是「上次中断」，自动缓存应当续做。
              -- 正在跑的书由 BookCacheService.enqueue 自己按 jobs 去重，不靠 DB 状态判断。
              and coalesce(c.state, 'idle') <> 'ready'
              and s.book_url not like 'content://%'
              and s.book_url not like 'local://%'
            order by s.last_read_at desc
            limit ?
            """.trimIndent(),
        ).use { query ->
            query.setInt(1, limit)
            query.executeQuery().use { rs ->
                buildList { while (rs.next()) add(CachedBookRequest(rs.getString(1), rs.getString(2), rs.getString(3))) }
            }
        }
    }
    fun cacheRequests(): List<CachedBookRequest> = connect { db ->
        db.prepareStatement(
            """
            select s.source_id, s.book_url, s.toc_url
            from book_shelf s
            join book_cache_status c on c.source_id = s.source_id and c.book_url = s.book_url
            where c.state in ('pending', 'caching')
              and s.book_url not like 'content://%'
              and s.book_url not like 'local://%'
              -- 漫画/有声书不续做**文本**缓存：那是给文字阅读器用的（拉章节页抽正文），
              -- 对图片/音频书只会 100% 失败并污染缓存状态（实测：一本漫画 10 章全失败）
              and coalesce(s.book_kind, 'novel') = 'novel'
            """.trimIndent()
        ).use { query ->
            query.executeQuery().use { rs ->
                buildList { while (rs.next()) add(CachedBookRequest(rs.getString(1), rs.getString(2), rs.getString(3))) }
            }
        }
    }

    /**
     * 批量导入书源。
     *
     * @param applyGroups **是否采用书源自带的 `bookSourceGroup`**：
     *   - `true`（仅**备份导入**用）：分组是手机端整理好的成果，随备份一起带进来；
     *   - `false`（**导入书源 JSON / 订阅更新**用的默认语义）：单独导入一个书源文件时**不自动分组**，
     *     新书源一律落在「未分组」，分组只由用户在「分组管理」里手动整理。
     *
     *   `applyGroups = false` 时还有一条**不可省的守卫**：upsert 的 `do update` **不碰 `source_group`**，
     *   否则一次普通的书源文件导入会把用户手工分好的组全部清空（新行才写 null）。
     */
    fun importSources(rawSources: List<String>, applyGroups: Boolean = false): ImportResponse {
        val errors = mutableListOf<String>()
        val unique = linkedMapOf<String, ParsedSource>()
        rawSources.forEachIndexed { index, raw ->
            try {
                val parsed = SourceCodec.parse(raw, keepGroup = applyGroups)
                unique[parsed.id] = parsed
            } catch (error: IllegalArgumentException) {
                errors += "第 ${index + 1} 项：${error.message}"
            }
        }
        var imported = 0
        var updated = 0
        // 分组是书源自带的元数据，落库时与书源同批写入 `source_group`；
        // 这里顺手统计「本批带来了几个不同的分组」，供导入提示如实回报（未分组不计）。
        val sourceGroups = if (!applyGroups) 0 else unique.values
            .mapNotNull { it.group?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinctBy { it.lowercase() }
            .size
        // `applyGroups = false` 时 **DO UPDATE 里不能出现 source_group**：不覆盖已有分组，
        // 否则一次普通的书源文件导入会把用户手工分好的组全部清空。
        val groupOnConflict = if (applyGroups) "source_group=excluded.source_group," else ""
        write { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("select version from source where id = ?").use { current ->
                    db.prepareStatement(
                        "insert into source(id,name,source_url,source_group,enabled,is_js,payload,version,updated_at,has_login) " +
                            "values(?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set " +
                            "name=excluded.name,source_url=excluded.source_url,${groupOnConflict}enabled=excluded.enabled," +
                            "is_js=excluded.is_js,payload=excluded.payload,version=excluded.version," +
                            "updated_at=excluded.updated_at,has_login=excluded.has_login",
                    ).use { save ->
                        unique.values.forEach { parsed ->
                            current.setString(1, parsed.id)
                            val version = current.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                            if (version == null) imported++ else updated++
                            save.setString(1, parsed.id); save.setString(2, parsed.name); save.setString(3, parsed.url); save.setString(4, parsed.group)
                            save.setInt(5, if (parsed.enabled) 1 else 0); save.setInt(6, if (parsed.isJs) 1 else 0); save.setString(7, parsed.json)
                            save.setLong(8, (version ?: 0) + 1); save.setLong(9, System.currentTimeMillis()); save.setInt(10, if (parsed.hasLogin) 1 else 0); save.addBatch()
                        }
                        save.executeBatch()
                    }
                }
                db.commit()
            } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
        }
        return ImportResponse(imported, updated, rawSources.size - unique.size, errors, sourceGroups)
    }

    /**
     * 备份导入：批量写入书架条目，并写入阅读进度（仅当备份时间不早于库中进度时覆盖，避免进度回退）。
     * 备份包只带章节序号与字符偏移，因此 [BackupShelfEntry.chapterIndex] 之外的定位信息不落库。
     */
    fun importLibrary(entries: List<BackupShelfEntry>): LibraryImportResult {
        if (entries.isEmpty()) return LibraryImportResult(0, 0, 0)
        val now = System.currentTimeMillis()
        var imported = 0
        var updated = 0
        var progressApplied = 0
        write { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("select 1 from book_shelf where source_id = ? and book_url = ?").use { existing ->
                    db.prepareStatement("""
                        insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,completed,alternate_sources,book_kind,variable)
                        values(?,?,?,?,?,?,null,?,?,null,?,?)
                        on conflict(source_id,book_url) do update set
                          name=excluded.name, author=excluded.author, toc_url=excluded.toc_url,
                          cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),
                          last_read_at=excluded.last_read_at, completed=book_shelf.completed,
                          book_kind=coalesce(excluded.book_kind,book_shelf.book_kind),
                          -- 书籍变量：备份里没有就**保留本机已有的**（用户可能刚在这台机器上改过音色）
                          variable=coalesce(excluded.variable,book_shelf.variable)
                    """.trimIndent()).use { save ->
                        entries.forEach { entry ->
                            existing.setString(1, entry.sourceId); existing.setString(2, entry.bookUrl)
                            if (existing.executeQuery().use { it.next() }) updated++ else imported++
                            save.setString(1, entry.sourceId); save.setString(2, entry.bookUrl); save.setString(3, entry.name)
                            save.setString(4, entry.author); save.setString(5, entry.tocUrl); save.setString(6, entry.coverUrl)
                            save.setLong(7, if (entry.readAt > 0) entry.readAt else now)
                            save.setInt(8, if (entry.completed) 1 else 0)
                            save.setString(9, entry.bookKind)
                            save.setString(10, entry.variable?.takeIf { it.isNotBlank() })
                            save.addBatch()
                        }
                        save.executeBatch()
                    }
                }
                val progressed = entries.filter { it.chapterIndex > 0 }
                if (progressed.isNotEmpty()) {
                    // `position_ms` 只对**有声书**写：`durChapterPos` 对音频就是毫秒（同语义直传），
                    // 而对文本/漫画它是字符偏移量，塞进这里毫无意义（AGENTS.md 明文禁止混用）。
                    db.prepareStatement("""
                        insert into reading_progress(source_id,book_url,chapter_url,chapter_index,scroll_position,position_ms,updated_at)
                        values(?,?,?,?,0,?,?)
                        on conflict(source_id,book_url) do update set
                          chapter_url=excluded.chapter_url, chapter_index=excluded.chapter_index,
                          position_ms=coalesce(excluded.position_ms, reading_progress.position_ms),
                          updated_at=excluded.updated_at
                        where excluded.updated_at >= reading_progress.updated_at
                    """.trimIndent()).use { stmt ->
                        progressed.forEach { entry ->
                            stmt.setString(1, entry.sourceId); stmt.setString(2, entry.bookUrl); stmt.setString(3, "")
                            stmt.setInt(4, entry.chapterIndex)
                            if (entry.bookKind == "audio") stmt.setLong(5, entry.chapterPos.toLong())
                            else stmt.setNull(5, java.sql.Types.INTEGER)
                            stmt.setLong(6, if (entry.readAt > 0) entry.readAt else now)
                            stmt.addBatch()
                        }
                        progressApplied = stmt.executeBatch().sum()
                    }
                }
                db.commit()
            } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
        }
        return LibraryImportResult(imported, updated, progressApplied)
    }

    /**
     * 导入备份包里的**分组**，并把书架上已有的 `group_name` 对齐到这些分组。
     *
     * ## 为什么需要「对齐」这一步
     *
     * 备份里书籍的分组是**数字 id**（`bookshelf.json` 的 `group`），而本服务的
     * `book_group` 与 `book_shelf.group_name` 是**按名字**关联的
     * （见 [listBookGroups] 的 `s.group_name = g.name collate nocase`）。
     * 因此这里要做两件事：① 建出分组；② 把已导入书籍的 `group_name` 填上对应的名字。
     *
     * ## 设计取舍
     *
     * - **只导入「有书的分组」**：Legado 内置的智能分组（`groupId` 为负：在读/未读/已读/
     *   小说/漫画/全部/本地/音频…）是按条件动态筛选的虚拟分组，实测在真实备份里都是空的，
     *   导入后只会变成一堆点不动的空分组。调用方据此过滤。
     * - **不覆盖已有分组**：`insert ... on conflict(name) do update` 只更新排序，不动已有的书。
     * - **`group_name` 用 coalesce 语义**：只在本服务该书的 `group_name` 为空时才写入，
     *   避免一次备份导入把用户在服务端手工改过的分组冲掉。
     *
     * @param groups    要导入的分组（调用方已过滤掉空的内置分组）
     * @param shelf     已导入的书架条目（其 [BackupShelfEntry.groupName] 已由 groupId 解析好）
     * @return 新建的分组数与被赋予分组的书籍数
     */
    fun importBookGroups(
        groups: List<BackupGroupEntry>,
        shelf: List<BackupShelfEntry>,
    ): GroupImportResult = write { db ->
        db.autoCommit = false
        var created = 0
        var assigned = 0
        try {
            // ① 建分组（按名字去重；已存在则只更新排序）
            //
            // ⚠️ 先按「大小写不敏感」读出现有分组再决定插不插：PG 侧 `book_group` 有
            // `unique(lower(name))` 表达式唯一索引（对齐 SQLite 的 `unique collate nocase`），
            // 而下面的 `on conflict(name)` 只能命中**区分大小写**的普通唯一约束 ⇒
            // 备份里的 `news` 撞上库里的 `News` 会抛唯一冲突、整包导入失败。
            // 所以在内存里把「大小写不同但同一个分组」映射到库里既有名字：既不新建，也不改用户分组名。
            val existingGroups = LinkedHashMap<String, String>()
            db.prepareStatement("select name from book_group").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) rs.getString(1)?.let { existingGroups[it.lowercase()] = it }
                }
            }
            val nameOverrides = HashMap<String, String>()
            val toInsert = groups
                .distinctBy { it.groupName.lowercase() }
                .filter { group ->
                    val existing = existingGroups[group.groupName.lowercase()]
                    if (existing != null) {
                        if (existing != group.groupName) nameOverrides[group.groupName] = existing
                        false
                    } else {
                        true
                    }
                }
            if (toInsert.isNotEmpty()) {
                db.prepareStatement(
                    "insert into book_group(name, sort_order, is_private, created_at) values(?,?,?,?) " +
                        "on conflict(name) do update set sort_order=excluded.sort_order, is_private=excluded.is_private"
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    toInsert.forEach { group ->
                        stmt.setString(1, group.groupName)
                        stmt.setInt(2, group.order)
                        stmt.setInt(3, if (group.isPrivate) 1 else 0)
                        stmt.setLong(4, now)
                        stmt.addBatch()
                    }
                    created = stmt.executeBatch().count { it > 0 }
                }
            }

            // ② 把书籍的 group_name 补上（只填空值，不动已存在的分组）
            val withGroup = shelf.filter { !it.groupName.isNullOrBlank() }
            if (withGroup.isNotEmpty()) {
                db.prepareStatement(
                    "update book_shelf set group_name=? where source_id=? and book_url=? " +
                        "and (group_name is null or group_name='')"
                ).use { stmt ->
                    withGroup.forEach { entry ->
                        // 名字归一化到库里既有写法（大小写不同视为同一分组）
                        stmt.setString(1, nameOverrides[entry.groupName] ?: entry.groupName)
                        stmt.setString(2, entry.sourceId)
                        stmt.setString(3, entry.bookUrl)
                        stmt.addBatch()
                    }
                    assigned = stmt.executeBatch().sum()
                }
            }
            db.commit()
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
        GroupImportResult(created = created, assigned = assigned)
    }

    /**
     * 导入备份包里的**书签/阅读记录**（`bookmark.json`）。
     *
     * ## 只导入「书还在书架上」的书签
     *
     * 实测真实备份 47 条书签里，**29 条挂在被过滤掉的书上**（本地图书/音频）——
     * 那些书不在书架上，书签也就没有展示位置。调用方据此过滤后传入。
     *
     * ## 幂等
     *
     * `bookmark` 表上对 `(book_name, book_author, chapter_index, chapter_pos)` 建了唯一键，
     * 重复导入同一备份不会产生重复书签。
     *
     * @return 本次新增的书签数
     */
    fun importBookmarks(bookmarks: List<BackupBookmarkEntry>): Int {
        if (bookmarks.isEmpty()) return 0
        return write { db ->
            db.autoCommit = false
            var inserted = 0
            try {
                db.prepareStatement(
                    """
                    insert into bookmark(book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at)
                    values(?,?,?,?,?,?,?,?)
                    on conflict(book_name,book_author,chapter_index,chapter_pos) do update set
                      chapter_name=excluded.chapter_name, book_text=excluded.book_text,
                      content=excluded.content, created_at=excluded.created_at
                    """.trimIndent()
                ).use { stmt ->
                    bookmarks.forEach { mark ->
                        stmt.setString(1, mark.bookName)
                        stmt.setString(2, mark.bookAuthor)
                        stmt.setInt(3, mark.chapterIndex)
                        stmt.setString(4, mark.chapterName)
                        stmt.setInt(5, mark.chapterPos)
                        stmt.setString(6, mark.bookText)
                        stmt.setString(7, mark.content)
                        stmt.setLong(8, if (mark.time > 0) mark.time else System.currentTimeMillis())
                        stmt.addBatch()
                    }
                    inserted = stmt.executeBatch().count { it > 0 }
                }
                db.commit()
            } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
            inserted
        }
    }

    /**
     * 某本书的书签数量。
     *
     * 用于验证导入幂等——**不能拿 upsert 的 `changes()` 判断"是否新增"**：
     * SQLite 在 `do update` 时同样报告 1 行受影响，只有查实际行数才准。
     */
    fun countBookmarks(bookName: String, bookAuthor: String?): Int = connect { db ->
        db.prepareStatement(
            "select count(*) from bookmark where book_name=? and coalesce(book_author,'')=coalesce(?,'')"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, bookAuthor)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** 全部书签（导出备份用）。按书籍、章节与位置排序，保证输出稳定。 */
    fun listAllBookmarks(): List<Bookmark> = connect { db ->
        db.prepareStatement(
            "select id,book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at " +
                "from bookmark order by book_name asc, chapter_index asc, chapter_pos asc"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Bookmark(
                            id = rs.getLong(1),
                            bookName = rs.getString(2),
                            bookAuthor = rs.getString(3),
                            chapterIndex = rs.getInt(4),
                            chapterName = rs.getString(5),
                            chapterPos = rs.getInt(6),
                            bookText = rs.getString(7),
                            content = rs.getString(8),
                            createdAt = rs.getLong(9),
                        )
                    )
                }
            }
        }
    }

    /** 某本书的全部书签，按章节与位置排序。 */
    fun listBookmarks(bookName: String, bookAuthor: String?): List<Bookmark> = connect { db ->
        db.prepareStatement(
            "select id,book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at " +
                "from bookmark where book_name=? and coalesce(book_author,'')=coalesce(?,'') " +
                "order by chapter_index asc, chapter_pos asc"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, bookAuthor)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Bookmark(
                            id = rs.getLong(1),
                            bookName = rs.getString(2),
                            bookAuthor = rs.getString(3),
                            chapterIndex = rs.getInt(4),
                            chapterName = rs.getString(5),
                            chapterPos = rs.getInt(6),
                            bookText = rs.getString(7),
                            content = rs.getString(8),
                            createdAt = rs.getLong(9),
                        )
                    )
                }
            }
        }
    }

    /**
     * 新增 / 就地更新一条书签（阅读器里点「添加书签」）。
     *
     * 同一位置重复点击**不产生重复行**：先按 `(书名, coalesce(作者), 章序号, 章内偏移)` 更新，
     * 没更新到行才插入。`created_at` 只在首次插入时写入（书签的时间是"什么时候加的"）。
     *
     * ⚠️ 为什么不用 `insert ... on conflict(book_name, book_author, ...) do update` 一把梭：
     * SQLite 的 UNIQUE 索引把 **NULL 视为互不相等**，而本地书/无名作者的书签 `book_author` 是 NULL
     * ⇒ upsert 在「重复点同一位置」时**不会命中冲突**，会一路插出重复行（实测：连点 7 次得到 7 条）。
     * 先查再写用 `coalesce(book_author,'')` 归一化，NULL 与空串都能命中。
     *
     * 返回值一定是**库里那一行**（带 id），前端据此直接更新列表，无需再查一次。
     */
    fun saveBookmark(request: BookmarkSaveRequest): Bookmark = write { db ->
        val updated = db.prepareStatement(
            """
            update bookmark set chapter_name=coalesce(?,chapter_name), book_text=coalesce(?,book_text),
              content=coalesce(?,content)
            where book_name=? and coalesce(book_author,'')=coalesce(?,'') and chapter_index=? and chapter_pos=?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, request.chapterName)
            stmt.setString(2, request.bookText)
            stmt.setString(3, request.content)
            stmt.setString(4, request.bookName)
            stmt.setString(5, request.bookAuthor)
            stmt.setInt(6, request.chapterIndex)
            stmt.setInt(7, request.chapterPos)
            stmt.executeUpdate()
        }
        if (updated == 0) {
            db.prepareStatement(
                "insert into bookmark(book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at) values(?,?,?,?,?,?,?,?)"
            ).use { stmt ->
                stmt.setString(1, request.bookName)
                stmt.setString(2, request.bookAuthor)
                stmt.setInt(3, request.chapterIndex)
                stmt.setString(4, request.chapterName)
                stmt.setInt(5, request.chapterPos)
                stmt.setString(6, request.bookText)
                stmt.setString(7, request.content)
                stmt.setLong(8, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
        selectBookmark(db, request.bookName, request.bookAuthor, request.chapterIndex, request.chapterPos)
            ?: error("书签写入后读不回来：${request.bookName} / ${request.chapterIndex} / ${request.chapterPos}")
    }

    private fun selectBookmark(db: Connection, bookName: String, bookAuthor: String?, chapterIndex: Int, chapterPos: Int): Bookmark? =
        db.prepareStatement(
            "select id,book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at " +
                "from bookmark where book_name=? and coalesce(book_author,'')=coalesce(?,'') and chapter_index=? and chapter_pos=?"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, bookAuthor)
            stmt.setInt(3, chapterIndex)
            stmt.setInt(4, chapterPos)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.toBookmark() else null }
        }

    /**
     * 编辑书签（只改原文摘要与备注，位置锚点不动）。
     *
     * `null` 表示「这一项不改」，因此备注可以被显式清空成空串、但不会被"没传"误清。
     */
    fun updateBookmark(id: Long, request: BookmarkUpdateRequest): Bookmark? = write { db ->
        db.prepareStatement(
            "update bookmark set book_text=coalesce(?,book_text), content=coalesce(?,content) where id=?"
        ).use { stmt ->
            stmt.setString(1, request.bookText)
            stmt.setString(2, request.content)
            stmt.setLong(3, id)
            stmt.executeUpdate()
        }
        db.prepareStatement(
            "select id,book_name,book_author,chapter_index,chapter_name,chapter_pos,book_text,content,created_at from bookmark where id=?"
        ).use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.toBookmark() else null }
        }
    }

    /** 删除一条书签。返回是否真的删掉了（前端据此提示"已被删除"）。 */
    fun deleteBookmark(id: Long): Boolean = write { db ->
        db.prepareStatement("delete from bookmark where id=?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeUpdate() > 0
        }
    }

    /**
     * 「书签」页左栏：按（书名 + 作者）聚合出**所有有书签的书**。
     *
     * 刻意**不要求书在书架上**——用户要求「一股脑全存到表里，不论有没有这本书，不论是本地的还是什么的」。
     * 封面只是**锦上添花**：用 LEFT JOIN 从书架取（同一本书可能有多个书源的记录，任取一张），
     * 书不在书架时 `cover_key`/`cover_url` 为 null，前端显示文字占位。
     *
     * 排序按「最新一条书签的时间」倒序：刚加过书签的书排在最前面，符合"继续看"的直觉。
     */
    fun listBookmarkBooks(): List<BookmarkBook> = connect { db ->
        db.prepareStatement(
            """
            select b.book_name, max(b.book_author) as book_author, count(distinct b.id) as cnt, max(b.created_at) as last_at,
                   max(s.cover_key) as cover_key, max(s.cover_url) as cover_url,
                   max(c.cover_key) as local_cover_key,
                   max(coalesce(c.missing_attempts, 0)) as cover_attempts
            from bookmark b
            left join book_shelf s
              on s.name = b.book_name and coalesce(s.author,'') = coalesce(b.book_author,'')
            left join book_cover c
              on c.book_name = b.book_name and c.book_author = coalesce(b.book_author,'')
            group by b.book_name, coalesce(b.book_author,'')
            order by last_at desc, b.book_name asc
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        // 封面优先取书架那份（书在架上时它就是权威），没有才用 book_cover 兜底
                        val shelfCover = rs.getString(5)
                        val attempts = rs.getInt(8)
                        add(
                            BookmarkBook(
                                bookName = rs.getString(1),
                                bookAuthor = rs.getString(2),
                                count = rs.getInt(3),
                                lastCreatedAt = rs.getLong(4),
                                coverKey = shelfCover ?: rs.getString(7),
                                coverUrl = rs.getString(6),
                                coverMissing = shelfCover == null && attempts >= COVER_MISSING_LIMIT,
                                coverAttempts = attempts,
                            )
                        )
                    }
                }
            }
        }
    }

    /** 批量删除书签（按 id）。返回**真实删除行数**（`executeBatch` 每次返回该语句影响的行数，必须求和）。 */
    fun deleteBookmarks(ids: List<Long>): Int {        if (ids.isEmpty()) return 0
        return write { db ->
            db.prepareStatement("delete from bookmark where id=?").use { stmt ->
                ids.forEach { id ->
                    stmt.setLong(1, id)
                    stmt.addBatch()
                }
                stmt.executeBatch().sum()
            }
        }
    }

    /**
     * 批量删除「整本书的书签」。返回**真实删除行数**。
     *
     * 用 `coalesce(book_author,'')` 比较，NULL 与空串都能命中（书签表里无作者的书占多数）。
     */
    fun deleteBookmarksByBooks(books: List<BookmarkBookKey>): Int {
        if (books.isEmpty()) return 0
        return write { db ->
            db.prepareStatement(
                "delete from bookmark where book_name=? and coalesce(book_author,'')=coalesce(?,'')"
            ).use { stmt ->
                books.forEach { book ->
                    stmt.setString(1, book.bookName)
                    stmt.setString(2, book.bookAuthor)
                    stmt.addBatch()
                }
                stmt.executeBatch().sum()
            }
        }
    }

    private fun ResultSet.toBookmark() = Bookmark(
        id = getLong(1),
        bookName = getString(2),
        bookAuthor = getString(3),
        chapterIndex = getInt(4),
        chapterName = getString(5),
        chapterPos = getInt(6),
        bookText = getString(7),
        content = getString(8),
        createdAt = getLong(9),
    )

    // ------------------------------------------------------------------
    // 书籍封面登记（book_cover）：书签里有、书架里没有的书也要能显示 / 管理封面
    // ------------------------------------------------------------------

    /** 封面登记的一行；[exhausted] = 已抓满 [COVER_MISSING_LIMIT] 次仍没找到 ⇒ 停止自动重试。 */
    data class BookCoverRow(
        val bookName: String,
        val bookAuthor: String,
        val coverKey: String? = null,
        val missingAttempts: Int = 0,
        val lastAttemptAt: Long = 0L,
    ) {
        val exhausted: Boolean get() = coverKey == null && missingAttempts >= COVER_MISSING_LIMIT
    }

    fun getBookCover(bookName: String, bookAuthor: String?): BookCoverRow? = connect { db ->
        val author = normalizeBookAuthor(bookAuthor)
        db.prepareStatement(
            "select cover_key, missing_attempts, last_attempt_at from book_cover where book_name=? and book_author=?"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, author)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null
                else BookCoverRow(bookName, author, rs.getString(1), rs.getInt(2), rs.getLong(3))
            }
        }
    }

    /** 写入/替换封面（上传或抓取成功），并把"失败次数"清零。 */
    fun saveBookCover(bookName: String, bookAuthor: String?, coverKey: String): BookCoverRow = write { db ->
        val author = normalizeBookAuthor(bookAuthor)
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """
            insert into book_cover(book_name,book_author,cover_key,missing_attempts,last_attempt_at,updated_at)
            values(?,?,?,0,?,?)
            on conflict(book_name,book_author) do update set
              cover_key=excluded.cover_key, missing_attempts=0,
              last_attempt_at=excluded.last_attempt_at, updated_at=excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, author)
            stmt.setString(3, coverKey)
            stmt.setLong(4, now)
            stmt.setLong(5, now)
            stmt.executeUpdate()
        }
        BookCoverRow(bookName, author, coverKey, 0, now)
    }

    /**
     * 记一次「没抓到封面」，返回累计次数。
     *
     * 已有本地封面的行**不加计数**（封面还在就不算"没找到"）。
     */
    fun recordCoverMiss(bookName: String, bookAuthor: String?): Int = write { db ->
        val author = normalizeBookAuthor(bookAuthor)
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """
            insert into book_cover(book_name,book_author,cover_key,missing_attempts,last_attempt_at,updated_at)
            values(?,?,null,1,?,?)
            on conflict(book_name,book_author) do update set
              missing_attempts = case when book_cover.cover_key is null
                                      then book_cover.missing_attempts + 1
                                      else book_cover.missing_attempts end,
              last_attempt_at = excluded.last_attempt_at, updated_at = excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, author)
            stmt.setLong(3, now)
            stmt.setLong(4, now)
            stmt.executeUpdate()
        }
        db.prepareStatement("select missing_attempts from book_cover where book_name=? and book_author=?").use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, author)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** 清空某本书的封面登记（删除封面时用）。 */
    fun clearBookCover(bookName: String, bookAuthor: String?): Boolean = write { db ->
        db.prepareStatement("delete from book_cover where book_name=? and book_author=?").use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, normalizeBookAuthor(bookAuthor))
            stmt.executeUpdate() > 0
        }
    }

    /**
     * 这些书签 id 各属于哪本书（删书签后据此判断封面要不要顺手删掉）。
     *
     * 返回去重后的 (书名, 作者) 列表。
     */
    fun bookKeysOfBookmarks(ids: List<Long>): List<BookmarkBookKey> {
        if (ids.isEmpty()) return emptyList()
        return connect { db ->
            db.prepareStatement(
                "select distinct book_name, coalesce(book_author,'') from bookmark where id=?"
            ).use { stmt ->
                val seen = LinkedHashMap<String, BookmarkBookKey>()
                ids.forEach { id ->
                    stmt.setLong(1, id)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val name = rs.getString(1)
                            val author = rs.getString(2)
                            seen["$name\u0000$author"] = BookmarkBookKey(name, author.ifBlank { null })
                        }
                    }
                }
                seen.values.toList()
            }
        }
    }

    /** 单个书签所在的 (书名, 作者)；不存在返回 null。 */
    fun bookKeyOfBookmark(id: Long): BookmarkBookKey? = connect { db ->
        db.prepareStatement("select book_name, coalesce(book_author,'') from bookmark where id=?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) BookmarkBookKey(rs.getString(1), rs.getString(2).ifBlank { null }) else null
            }
        }
    }

    /** 这本书还有没有书签（决定封面文件能否删）。 */
    fun hasBookmarksFor(bookName: String, bookAuthor: String?): Boolean = connect { db ->
        db.prepareStatement(
            "select 1 from bookmark where book_name=? and coalesce(book_author,'')=? limit 1"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, normalizeBookAuthor(bookAuthor))
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    /** 这本书还在不在书架（同一次清理里判断封面要不要保留）。 */
    fun hasShelfBookFor(bookName: String, bookAuthor: String?): Boolean = connect { db ->
        db.prepareStatement(
            "select 1 from book_shelf where name=? and coalesce(author,'')=? limit 1"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, normalizeBookAuthor(bookAuthor))
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    /** 同一个封面 key 还有没有别的书在用（书架行或封面登记）。 */
    fun isCoverKeyReferenced(coverKey: String): Boolean = connect { db ->
        db.prepareStatement("select 1 from book_shelf where cover_key=? limit 1").use { stmt ->
            stmt.setString(1, coverKey)
            if (stmt.executeQuery().use { it.next() }) return@connect true
        }
        db.prepareStatement("select 1 from book_cover where cover_key=? limit 1").use { stmt ->
            stmt.setString(1, coverKey)
            if (stmt.executeQuery().use { it.next() }) return@connect true
        }
        false
    }

    /** 把一本书的本地封面副本同步到它所有书架行（书签页上传后书架也要显示）。 */
    fun attachCoverToShelfRows(bookName: String, bookAuthor: String?, coverKey: String): Int = write { db ->
        db.prepareStatement(
            "update book_shelf set cover_key=? where name=? and coalesce(author,'')=?"
        ).use { stmt ->
            stmt.setString(1, coverKey)
            stmt.setString(2, bookName)
            stmt.setString(3, normalizeBookAuthor(bookAuthor))
            stmt.executeUpdate()
        }
    }

    /** 这本书在书架上所有行用到的封面 key（去重，忽略空值）——换/删封面时用来回收旧图。 */
    fun shelfCoverKeys(bookName: String, bookAuthor: String?): List<String> = connect { db ->
        db.prepareStatement(
            "select distinct cover_key from book_shelf where name=? and coalesce(author,'')=? and cover_key is not null"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, normalizeBookAuthor(bookAuthor))
            stmt.executeQuery().use { rs ->
                buildList { while (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() }?.let { add(it) } }
            }
        }
    }

    /** 移除某本书在书架行上的封面引用（书签页「移除封面」用）。 */
    fun clearCoverOnShelfRows(bookName: String, bookAuthor: String?): Int = write { db ->
        db.prepareStatement(
            "update book_shelf set cover_key=null where name=? and coalesce(author,'')=?"
        ).use { stmt ->
            stmt.setString(1, bookName)
            stmt.setString(2, normalizeBookAuthor(bookAuthor))
            stmt.executeUpdate()
        }
    }

    private fun normalizeBookAuthor(author: String?): String = author?.trim().orEmpty()


    fun listSubscriptions(enabledOnly: Boolean = false): List<SourceSubscription> = connect { db ->        val sql = "select * from source_subscription" + (if (enabledOnly) " where enabled=1" else "") + " order by id"
        db.prepareStatement(sql).use { statement -> statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSubscription()) } } }
    }

    fun saveSubscription(request: SubscriptionWriteRequest): SourceSubscription = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""insert into source_subscription(url,enabled,name,type,auto_update,update_interval,created_at,updated_at) values(?,?,?,?,?,?,?,?)
            on conflict(url) do update set enabled=excluded.enabled,name=excluded.name,type=excluded.type,
            auto_update=excluded.auto_update,update_interval=excluded.update_interval,updated_at=excluded.updated_at""").use {
            it.setString(1, request.url); it.setInt(2, if (request.enabled) 1 else 0); it.setString(3, request.name.trim())
            it.setInt(4, request.type); it.setInt(5, if (request.autoUpdate) 1 else 0); it.setInt(6, request.updateInterval)
            it.setLong(7, now); it.setLong(8, now); it.executeUpdate()
        }
        db.prepareStatement("select * from source_subscription where url=?").use { it.setString(1, request.url); it.executeQuery().use { rs -> rs.next(); rs.toSubscription() } }
    }

    fun getSubscription(id: Long): SourceSubscription? = connect { db -> db.prepareStatement("select * from source_subscription where id=?").use { it.setLong(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.toSubscription() else null } } }
    fun deleteSubscription(id: Long): Boolean = write { db -> db.prepareStatement("delete from source_subscription where id=?").use { it.setLong(1, id); it.executeUpdate() == 1 } }
    fun recordSubscriptionSuccess(id: Long, response: ImportResponse, contentHash: String) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("update source_subscription set last_success_at=?,last_attempt_at=?,last_error=null,last_imported=?,content_hash=?,updated_at=? where id=?").use {
            it.setLong(1, now); it.setLong(2, now); it.setInt(3, response.imported + response.updated); it.setString(4, contentHash); it.setLong(5, now); it.setLong(6, id); it.executeUpdate()
        }
    }
    fun recordSubscriptionFailure(id: Long, message: String) = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("update source_subscription set last_attempt_at=?,last_error=?,updated_at=? where id=?").use { it.setLong(1, now); it.setString(2, message.take(500)); it.setLong(3, now); it.setLong(4, id); it.executeUpdate() }
    }
    fun exportSources(ids: List<String>?): List<String> = connect { db ->
        val sql = if (ids.isNullOrEmpty()) {
            "select payload, source_group from source order by name collate nocase"
        } else {
            "select payload, source_group from source where id in (${ids.joinToString(",") { "?" }}) order by name collate nocase"
        }
        db.prepareStatement(sql).use { statement ->
            ids?.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(SourceCodec.withGroup(rs.getString(1), rs.getString(2)))
                    }
                }
            }
        }
    }

    fun getSourceLoginState(sourceId: String): SourceLoginStateRecord? = connect { db ->
        db.prepareStatement("select source_id, login_info, login_header, source_variable, source_kv, cookie_jar, updated_at from source_login_state where source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val loginInfo = rs.getString(2)?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                    val loginHeader = rs.getString(3)
                    val sourceVariable = rs.getString(4)
                    val sourceKv = rs.getString(5)?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                    val cookieJar = rs.getString(6)?.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                    val updatedAt = rs.getLong(7)
                    SourceLoginStateRecord(
                        sourceId = rs.getString(1),
                        loginInfo = loginInfo,
                        loginHeader = loginHeader,
                        sourceVariable = sourceVariable,
                        sourceKv = sourceKv,
                        cookieJar = cookieJar,
                        updatedAt = updatedAt,
                    )
                } else null
            }
        }
    }

    fun saveSourceLoginInfo(sourceId: String, loginInfo: Map<String, String>): Boolean = write { db ->
        val now = System.currentTimeMillis()
        val infoJson = Json.encodeToString(loginInfo)
        db.prepareStatement("""
            insert into source_login_state(source_id, login_info, updated_at) values(?, ?, ?)
            on conflict(source_id) do update set login_info=excluded.login_info, updated_at=excluded.updated_at
        """).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, infoJson)
            stmt.setLong(3, now)
            stmt.executeUpdate() > 0
        }
    }

    fun saveSourceLoginHeader(sourceId: String, header: String?): Boolean = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""
            insert into source_login_state(source_id, login_header, updated_at) values(?, ?, ?)
            on conflict(source_id) do update set login_header=excluded.login_header, updated_at=excluded.updated_at
        """).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, header)
            stmt.setLong(3, now)
            stmt.executeUpdate() > 0
        }
    }

    fun removeSourceLoginHeader(sourceId: String): Boolean = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""
            update source_login_state set login_header = null, updated_at = ? where source_id = ?
        """).use { stmt ->
            stmt.setLong(1, now)
            stmt.setString(2, sourceId)
            stmt.executeUpdate() > 0
        }
    }

    fun removeSourceLoginInfo(sourceId: String): Boolean = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""
            update source_login_state set login_info = null, updated_at = ? where source_id = ?
        """).use { stmt ->
            stmt.setLong(1, now)
            stmt.setString(2, sourceId)
            stmt.executeUpdate() > 0
        }
    }

    fun saveSourceVariable(sourceId: String, variable: String?): Boolean = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""
            insert into source_login_state(source_id, source_variable, updated_at) values(?, ?, ?)
            on conflict(source_id) do update set source_variable=excluded.source_variable, updated_at=excluded.updated_at
        """).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, variable)
            stmt.setLong(3, now)
            stmt.executeUpdate() > 0
        }
    }

    fun getSourceVariable(sourceId: String): String? = connect { db ->
        db.prepareStatement("select source_variable from source_login_state where source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun saveSourceKv(sourceId: String, key: String, value: String?): Boolean = write { db ->
        val now = System.currentTimeMillis()
        val state = getSourceLoginState(sourceId)
        val currentKv = state?.sourceKv?.toMutableMap() ?: mutableMapOf()
        if (value != null) {
            currentKv[key] = value
        } else {
            currentKv.remove(key)
        }
        val kvJson = Json.encodeToString(currentKv)
        db.prepareStatement("""
            insert into source_login_state(source_id, source_kv, updated_at) values(?, ?, ?)
            on conflict(source_id) do update set source_kv=excluded.source_kv, updated_at=excluded.updated_at
        """).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, kvJson)
            stmt.setLong(3, now)
            stmt.executeUpdate() > 0
        }
    }

    fun getSourceKv(sourceId: String, key: String): String? = connect { db ->
        db.prepareStatement("select source_kv from source_login_state where source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val kvJson = rs.getString(1) ?: return@use null
                    runCatching { Json.decodeFromString<Map<String, String>>(kvJson)[key] }.getOrNull()
                } else null
            }
        }
    }

    fun saveSourceCookieJar(sourceId: String, cookies: Map<String, String>): Boolean = write { db ->
        val now = System.currentTimeMillis()
        val jarJson = Json.encodeToString(cookies)
        db.prepareStatement("""
            insert into source_login_state(source_id, cookie_jar, updated_at) values(?, ?, ?)
            on conflict(source_id) do update set cookie_jar=excluded.cookie_jar, updated_at=excluded.updated_at
        """).use { stmt ->
            stmt.setString(1, sourceId)
            stmt.setString(2, jarJson)
            stmt.setLong(3, now)
            stmt.executeUpdate() > 0
        }
    }

    fun getSourceCookieJar(sourceId: String): Map<String, String> = connect { db ->
        db.prepareStatement("select cookie_jar from source_login_state where source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val jarJson = rs.getString(1) ?: return@use emptyMap()
                    runCatching { Json.decodeFromString<Map<String, String>>(jarJson) }.getOrDefault(emptyMap())
                } else emptyMap()
            }
        }
    }

    fun getSourceCookie(sourceId: String, url: String): String? {
        val jar = getSourceCookieJar(sourceId)
        if (jar.isEmpty()) return null
        if (jar.containsKey(url)) return jar[url]
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        // 精确域名优先，再逐级回退到父域（a.b.com -> b.com），与浏览器 Cookie 语义一致
        val ordered = mutableListOf<String>()
        jar[host]?.let { ordered += it }
        val parts = host.split('.')
        for (index in 1..parts.size - 2) {
            jar[parts.subList(index, parts.size).joinToString(".")]?.let { ordered += it }
        }
        if (ordered.isEmpty()) return null
        val merged = linkedMapOf<String, String>()
        ordered.reversed().forEach { merged.putAll(parseCookieString(it)) }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }
    }

    /**
     * 从 Set-Cookie 响应头中提取第一个 name=value 对。
     * 整行入库会把 Path / Expires / HttpOnly 等属性误当成 Cookie 一并发送。
     */
    fun extractCookiePair(setCookieHeader: String): String? {
        val first = setCookieHeader.substringBefore(';').trim()
        val separator = first.indexOf('=')
        if (separator <= 0) return null
        if (first.substring(0, separator).isBlank()) return null
        return first
    }

    fun setSourceCookieFromSetCookie(sourceId: String, url: String, setCookieHeader: String): Boolean {
        val pair = extractCookiePair(setCookieHeader) ?: return false
        return setSourceCookie(sourceId, url, pair)
    }

    fun setSourceCookie(sourceId: String, url: String, cookie: String): Boolean {
        val jar = getSourceCookieJar(sourceId).toMutableMap()
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: url
        val existing = jar[host] ?: jar[url]
        val incomingMap = parseCookieString(cookie)
        if (incomingMap.isEmpty()) return false
        val finalMap = if (!existing.isNullOrBlank()) {
            val map = parseCookieString(existing).toMutableMap()
            map.putAll(incomingMap)
            map
        } else {
            incomingMap
        }
        jar[host] = finalMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return saveSourceCookieJar(sourceId, jar)
    }

    fun removeSourceCookie(sourceId: String, url: String): Boolean {
        val jar = getSourceCookieJar(sourceId).toMutableMap()
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        jar.remove(host)
        jar.remove(url)
        val keysToRemove = jar.keys.filter { it.contains(host) || host.contains(it) }
        keysToRemove.forEach { jar.remove(it) }
        return saveSourceCookieJar(sourceId, jar)
    }

    fun clearSourceLoginState(sourceId: String): Boolean = write { db ->
        db.prepareStatement("delete from source_login_state where source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeUpdate() > 0
        }
    }

    fun parseCookieString(cookieStr: String): Map<String, String> {
        val trimmed = cookieStr.trim()
        if (trimmed.isEmpty()) return emptyMap()

        // 1. JSON Array (e.g. Cookie-Editor / EditThisCookie export format)
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val fromArray = runCatching {
                val array = Json.parseToJsonElement(trimmed).jsonArray
                val map = linkedMapOf<String, String>()
                for (item in array) {
                    val obj = item.jsonObject
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                    val value = (obj["value"] as? JsonPrimitive)?.contentOrNull
                    if (!name.isNullOrBlank() && value != null) {
                        map[name] = value
                    }
                }
                map
            }.getOrNull()
            if (!fromArray.isNullOrEmpty()) return fromArray
        }

        // 2. JSON Object (e.g. {"Cookie": "..."} or {"token": "..."})
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val fromObj = runCatching {
                val obj = Json.parseToJsonElement(trimmed).jsonObject
                if (obj.containsKey("Cookie") || obj.containsKey("cookie")) {
                    val c = (obj["Cookie"] ?: obj["cookie"])?.jsonPrimitive?.contentOrNull
                    if (!c.isNullOrBlank()) return parseCookieString(c)
                }
                val map = linkedMapOf<String, String>()
                for ((k, v) in obj) {
                    val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                    if (k.isNotBlank()) map[k] = value
                }
                map
            }.getOrNull()
            if (!fromObj.isNullOrEmpty()) return fromObj
        }

        // 3. Netscape format lines (tab separated)
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isNotEmpty() && lines.any { it.contains("\t") }) {
            val map = linkedMapOf<String, String>()
            for (line in lines) {
                val parts = line.split("\t")
                if (parts.size >= 7) {
                    val name = parts[5].trim()
                    val value = parts[6].trim()
                    if (name.isNotEmpty()) map[name] = value
                }
            }
            if (map.isNotEmpty()) return map
        }

        // 4. Standard key=value; key2=value2 format
        val result = linkedMapOf<String, String>()
        trimmed.split(';').forEach { part ->
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq > 0) {
                val key = p.substring(0, eq).trim()
                val value = p.substring(eq + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun java.sql.ResultSet.toSummary() = SourceSummary(
        id = getString(1),
        name = getString(2),
        url = getString(3),
        group = getString(4),
        enabled = getInt(5) == 1,
        isJsSource = getInt(6) == 1,
        hasLogin = getInt(9) == 1,
        updatedAt = getLong(7),
        version = getLong(8),
    )
    private fun java.sql.ResultSet.toSubscription() = SourceSubscription(
        getLong("id"), getString("url"), getInt("enabled") == 1, getLong("created_at"), getLong("updated_at"),
        getLong("last_success_at").takeIf { !wasNull() }, getLong("last_attempt_at").takeIf { !wasNull() },
        getString("last_error"), getInt("last_imported"), getString("content_hash"),
        name = getString("name").orEmpty(), type = getInt("type"),
        autoUpdate = getInt("auto_update") == 1, updateInterval = getInt("update_interval"),
    )

    // ------------------------------------------------------------------ 订阅源（RssSource）

    /** 导入订阅源：按 `sourceUrl` upsert；逐条试解析，不合格的进 `errors`、不中断整批。 */
    fun importRssSources(rawSources: List<String>): ImportResponse {
        val errors = mutableListOf<String>()
        val unique = linkedMapOf<String, ParsedRssSource>()
        rawSources.forEachIndexed { index, raw ->
            try {
                val parsed = RssSourceCodec.parse(raw)
                unique[parsed.id] = parsed
            } catch (error: IllegalArgumentException) {
                errors += "第 ${index + 1} 项：${error.message}"
            }
        }
        var imported = 0
        var updated = 0
        write { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("select 1 from rss_source where id = ?").use { exists ->
                    db.prepareStatement(
                        "insert into rss_source(id,name,icon,source_group,enabled,single_url,sort_url,custom_order,payload,updated_at) " +
                            "values(?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set name=excluded.name,icon=excluded.icon," +
                            "source_group=excluded.source_group,enabled=excluded.enabled,single_url=excluded.single_url," +
                            "sort_url=excluded.sort_url,custom_order=excluded.custom_order,payload=excluded.payload,updated_at=excluded.updated_at",
                    ).use { save ->
                        unique.values.forEach { parsed ->
                            exists.setString(1, parsed.id)
                            if (exists.executeQuery().use { it.next() }) updated++ else imported++
                            save.setString(1, parsed.id); save.setString(2, parsed.name); save.setString(3, parsed.icon); save.setString(4, parsed.group)
                            save.setInt(5, if (parsed.enabled) 1 else 0); save.setInt(6, if (parsed.singleUrl) 1 else 0); save.setString(7, parsed.sortUrl)
                            save.setInt(8, parsed.customOrder); save.setString(9, parsed.json); save.setLong(10, System.currentTimeMillis()); save.addBatch()
                        }
                        save.executeBatch()
                    }
                }
                db.commit()
            } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
        }
        return ImportResponse(imported, updated, rawSources.size - unique.size, errors)
    }

    fun listRssSources(): List<RssSourceSummary> = connect { db ->
        db.prepareStatement("select id,name,icon,source_group,enabled,single_url,sort_url,custom_order,updated_at from rss_source order by custom_order, name").use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString("id")
                        add(
                            RssSourceSummary(
                                id = id, name = rs.getString("name"), icon = rs.getString("icon"), group = rs.getString("source_group"),
                                enabled = rs.getInt("enabled") == 1, singleUrl = rs.getInt("single_url") == 1,
                                openUrl = RssSourceCodec.openUrl(id, rs.getString("sort_url")),
                                customOrder = rs.getInt("custom_order"), updatedAt = rs.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 订阅源原始 JSON；不存在返回 null。 */
    fun getRssSourcePayload(id: String): String? = connect { db ->
        db.prepareStatement("select payload from rss_source where id = ?").use { it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    fun exportRssSources(): List<String> = connect { db ->
        db.prepareStatement("select payload from rss_source order by custom_order, name").use { it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    fun setRssSourceEnabled(id: String, enabled: Boolean): Boolean = write { db ->
        db.prepareStatement("update rss_source set enabled=?, updated_at=? where id=?").use {
            it.setInt(1, if (enabled) 1 else 0); it.setLong(2, System.currentTimeMillis()); it.setString(3, id); it.executeUpdate() == 1
        }
    }

    fun deleteRssSource(id: String): Boolean = write { db ->
        db.prepareStatement("delete from rss_source where id=?").use { it.setString(1, id); it.executeUpdate() == 1 }
    }
    private fun java.sql.ResultSet.toShelf(): BookshelfItem {
        val altJson = getString(15)
        val altSources = if (!altJson.isNullOrBlank()) {
            runCatching { Json.decodeFromString<List<SearchResult>>(altJson) }.getOrDefault(emptyList())
        } else emptyList()
        val sourceId = getString(1)
        val bookUrl = getString(2)
        val rawState = getString(12)
        val rawError = getString(13)
        val isLocal = sourceId == LocalBookParser.LOC_BOOK_SOURCE_ID || bookUrl.startsWith("local://")
        val state = if (isLocal && rawState == "failed") "ready" else rawState
        val error = if (isLocal && rawState == "failed") null else rawError
        return BookshelfItem(
            sourceId = sourceId,
            bookUrl = bookUrl,
            name = getString(3),
            author = getString(4),
            tocUrl = getString(5),
            coverKey = getString(6),
            chapterIndex = getObject(7) as? Int,
            scrollPosition = getObject(8) as? Double,
            lastReadAt = getLong(9),
            cachedChapters = getInt(10),
            totalChapters = getInt(11),
            cacheState = state,
            cacheError = error,
            completed = getInt(14) != 0,
            alternateSources = altSources,
            groupName = runCatching { getString(16) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = runCatching { getString(17) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            // 本地书（issue #66）：列若为空（极老的数据、或迁移中途）仍按身份兜底成 `local`，
            // 保证前端四类角标与备份导出 `type=264` 不会因为"列没写"而退化成小说。
            bookKind = runCatching { getString(18) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (isLocal) BookKind.LOCAL.wire else BookKind.NOVEL.wire,
            // 有声书的毫秒位置（文本/漫画恒为 null，语义不可混用）；导出行回、进度同步都用它
            positionMs = runCatching { (getObject(20) as? Number)?.toLong() }.getOrNull(),
            variable = runCatching { getString(19) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------
    // 书籍变量（Legado `Book.variable` / `book.getVariable()`）
    //
    // 存储形态**逐字对齐开源阅读**（`legado-with-MD3` 的 `BaseBook`）：
    //   `variable` 列 = `GSON.toJson(variableMap)` ⇒ 一个 JSON 对象字符串，键值都是字符串。
    // 读取语义也对齐 `RuleDataInterface.getVariable()`：**键不存在返回空串**（不是 "null"）。
    // ------------------------------------------------------------------

    /** 原始 JSON（前端编辑弹窗与备份导出用）。行不存在 / 从未设置过 ⇒ null。 */
    fun getBookVariable(sourceId: String, bookUrl: String): String? = connect { db ->
        db.prepareStatement("select variable from book_shelf where source_id=? and book_url=?").use {
            it.setString(1, sourceId); it.setString(2, bookUrl)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.trim()?.takeIf { v -> v.isNotEmpty() } else null }
        }
    }

    /** 解析后的键值表（`book.getVariable(key)` 的取值来源）。**任何脏 JSON 都退化为空表**，绝不抛。 */
    fun getBookVariables(sourceId: String, bookUrl: String): Map<String, String> =
        parseBookVariables(getBookVariable(sourceId, bookUrl))

    /**
     * 写入整张变量表。
     *
     * ⚠️ 空表写 `null` 而不是 `"{}"`：与 Legado 的"从未设置过"保持同一种形态，
     * 也让 `book.variable` 在"确实没有变量"时保持假值（书源里 `if (!book.variable)` 这类判空才成立）。
     */
    fun saveBookVariables(sourceId: String, bookUrl: String, variables: Map<String, String>): Boolean = write { db ->
        val payload = if (variables.isEmpty()) null else Json.encodeToString(variables)
        db.prepareStatement("update book_shelf set variable=? where source_id=? and book_url=?").use {
            it.setString(1, payload); it.setString(2, sourceId); it.setString(3, bookUrl)
            it.executeUpdate() > 0
        }
    }

    /**
     * 写入**原始 JSON 字符串**（不做解析）。
     *
     * 用于两类场景：备份导入要原样搬运手机端的 `variable`；以及测试/排障要构造脏数据。
     * 常规写入请走 [saveBookVariables]（它会做空表归一化）。
     */
    fun saveBookVariable(sourceId: String, bookUrl: String, raw: String?): Boolean = write { db ->
        db.prepareStatement("update book_shelf set variable=? where source_id=? and book_url=?").use {
            it.setString(1, raw?.trim()?.takeIf { value -> value.isNotEmpty() })
            it.setString(2, sourceId); it.setString(3, bookUrl)
            it.executeUpdate() > 0
        }
    }

    /** `putCustomVariable(value)`：只为 `custom` 一个键做合并，其余键**原样保留**（对齐 Legado）。 */
    fun saveCustomVariable(sourceId: String, bookUrl: String, value: String?): Boolean {
        if (value == null) return false
        val merged = parseBookVariables(getBookVariable(sourceId, bookUrl)).toMutableMap()
        merged[CUSTOM_VARIABLE_KEY] = value
        return saveBookVariables(sourceId, bookUrl, merged)
    }

    /** 容错解析：非法 JSON / 非字符串值一律丢弃（书源写坏了不能把书架读崩）。 */
    private fun parseBookVariables(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching emptyMap()
            obj.mapNotNull { (key, element) ->
                val text = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                key to text
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun java.sql.ResultSet.toReplaceRule(): ReplaceRule = ReplaceRule(
        id = getString(1),
        name = getString(2),
        group = getString(3),
        pattern = getString(4),
        replacement = getString(5) ?: "",
        isRegex = getInt(6) != 0,
        scope = getString(7),
        excludeScope = getString(8),
        scopeTitle = getInt(9) != 0,
        scopeContent = getInt(10) != 0,
        isEnabled = getInt(11) != 0,
        order = getInt(12),
        timeoutMillisecond = getLong(13),
        createdAt = getLong(14),
        updatedAt = getLong(15),
    )

    fun listReplaceRules(query: String? = null, group: String? = null, scope: String? = null): List<ReplaceRule> = connect { db ->
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (!query.isNullOrBlank()) {
            conditions.add("(name like ? or pattern like ? or replacement like ?)")
            val q = "%${query.trim()}%"
            params.add(q); params.add(q); params.add(q)
        }
        if (!group.isNullOrBlank()) {
            conditions.add("group_name = ?")
            params.add(group.trim())
        }
        if (!scope.isNullOrBlank()) {
            conditions.add("scope like ?")
            params.add("%${scope.trim()}%")
        }
        val whereClause = if (conditions.isEmpty()) "" else "where " + conditions.joinToString(" and ")
        val sql = "select id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at from replace_rule $whereClause order by sort_order asc, created_at asc"
        db.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ReplaceRule>()
                while (rs.next()) {
                    list.add(rs.toReplaceRule())
                }
                list
            }
        }
    }

    fun getReplaceRule(id: String): ReplaceRule? = connect { db ->
        db.prepareStatement("select id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at from replace_rule where id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.toReplaceRule() else null }
        }
    }

    fun saveReplaceRule(rule: ReplaceRule): ReplaceRule = write { db ->
        val now = System.currentTimeMillis()
        val ruleId = rule.id.ifBlank { System.currentTimeMillis().toString() + "_" + java.util.UUID.randomUUID().toString().take(6) }
        val existing = db.prepareStatement("select created_at from replace_rule where id = ?").use { stmt ->
            stmt.setString(1, ruleId)
            stmt.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }
        val createdAt = existing ?: (if (rule.createdAt > 0) rule.createdAt else now)
        db.prepareStatement("""
            insert into replace_rule(id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at)
            values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(id) do update set
              name = excluded.name,
              group_name = excluded.group_name,
              pattern = excluded.pattern,
              replacement = excluded.replacement,
              is_regex = excluded.is_regex,
              scope = excluded.scope,
              exclude_scope = excluded.exclude_scope,
              scope_title = excluded.scope_title,
              scope_content = excluded.scope_content,
              is_enabled = excluded.is_enabled,
              sort_order = excluded.sort_order,
              timeout_ms = excluded.timeout_ms,
              updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, ruleId)
            stmt.setString(2, rule.name.ifBlank { rule.pattern })
            stmt.setString(3, rule.group)
            stmt.setString(4, rule.pattern)
            stmt.setString(5, rule.replacement)
            stmt.setInt(6, if (rule.isRegex) 1 else 0)
            stmt.setString(7, rule.scope)
            stmt.setString(8, rule.excludeScope)
            stmt.setInt(9, if (rule.scopeTitle) 1 else 0)
            stmt.setInt(10, if (rule.scopeContent) 1 else 0)
            stmt.setInt(11, if (rule.isEnabled) 1 else 0)
            stmt.setInt(12, rule.order)
            stmt.setLong(13, if (rule.timeoutMillisecond > 0) rule.timeoutMillisecond else 3000L)
            stmt.setLong(14, createdAt)
            stmt.setLong(15, now)
            stmt.executeUpdate()
        }
        rule.copy(id = ruleId, createdAt = createdAt, updatedAt = now)
    }

    fun deleteReplaceRule(id: String): Boolean = write { db ->
        db.prepareStatement("delete from replace_rule where id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate() > 0
        }
    }

    fun deleteReplaceRules(ids: List<String>): Int = write { db ->
        if (ids.isEmpty()) return@write 0
        val placeholders = ids.joinToString(",") { "?" }
        db.prepareStatement("delete from replace_rule where id in ($placeholders)").use { stmt ->
            ids.forEachIndexed { idx, id -> stmt.setString(idx + 1, id) }
            stmt.executeUpdate()
        }
    }

    fun toggleReplaceRules(ids: List<String>, enabled: Boolean): Int = write { db ->
        if (ids.isEmpty()) return@write 0
        val placeholders = ids.joinToString(",") { "?" }
        val now = System.currentTimeMillis()
        db.prepareStatement("update replace_rule set is_enabled = ?, updated_at = ? where id in ($placeholders)").use { stmt ->
            stmt.setInt(1, if (enabled) 1 else 0)
            stmt.setLong(2, now)
            ids.forEachIndexed { idx, id -> stmt.setString(idx + 3, id) }
            stmt.executeUpdate()
        }
    }

    fun batchSetReplaceRulesGroup(ids: List<String>, group: String?): Int = write { db ->
        if (ids.isEmpty()) return@write 0
        val placeholders = ids.joinToString(",") { "?" }
        val targetGroup = group?.trim()?.takeIf { it.isNotBlank() && !it.equals(SourceGroupFilter.UNGROUPED, ignoreCase = true) }
        val now = System.currentTimeMillis()
        db.prepareStatement("update replace_rule set group_name = ?, updated_at = ? where id in ($placeholders)").use { stmt ->
            stmt.setString(1, targetGroup)
            stmt.setLong(2, now)
            ids.forEachIndexed { idx, id -> stmt.setString(idx + 3, id) }
            stmt.executeUpdate()
        }
    }

    fun listReplaceRuleGroups(): List<ReplaceRuleGroupSummary> = connect { db ->
        val sql = """
            select trim(group_name) as gname,
                   count(*) as total,
                   sum(case when is_enabled = 1 then 1 else 0 end) as enabled
            from replace_rule
            where group_name is not null and trim(group_name) != ''
            group by trim(group_name) collate nocase
            order by trim(group_name) collate nocase asc
        """.trimIndent()
        db.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ReplaceRuleGroupSummary>()
                while (rs.next()) {
                    list.add(
                        ReplaceRuleGroupSummary(
                            name = rs.getString(1),
                            ruleCount = rs.getInt(2),
                            enabledCount = rs.getInt(3),
                        )
                    )
                }
                list
            }
        }
    }

    fun renameReplaceRuleGroup(from: String, to: String): Int {
        val target = to.trim()
        require(!target.equals(SourceGroupFilter.UNGROUPED, ignoreCase = true)) {
            "不能使用系统保留字「$target」作为分组名称"
        }
        return write { db ->
            db.prepareStatement("update replace_rule set group_name = ?, updated_at = ? where group_name = ? collate nocase").use { stmt ->
                stmt.setString(1, target)
                stmt.setLong(2, System.currentTimeMillis())
                stmt.setString(3, from.trim())
                stmt.executeUpdate()
            }
        }
    }

    fun clearReplaceRuleGroup(name: String): Int = write { db ->
        db.prepareStatement("update replace_rule set group_name = null, updated_at = ? where group_name = ? collate nocase").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, name.trim())
            stmt.executeUpdate()
        }
    }

    fun getEnabledReplaceRules(): List<ReplaceRule> = connect { db ->
        db.prepareStatement("select id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at from replace_rule where is_enabled = 1 order by sort_order asc, created_at asc").use { stmt ->
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ReplaceRule>()
                while (rs.next()) list.add(rs.toReplaceRule())
                list
            }
        }
    }

    fun getEnabledReplaceRulesForScope(bookName: String?, sourceUrl: String?, sourceName: String? = null): List<ReplaceRule> {
        val allEnabled = getEnabledReplaceRules()
        return allEnabled.filter { ContentProcessor.matchesScope(it, bookName, sourceUrl, sourceName) }
    }

    fun importReplaceRules(rules: List<ReplaceRule>): ReplaceRuleImportResponse = write { db ->
        val now = System.currentTimeMillis()
        var imported = 0
        var updated = 0
        var skipped = 0

        for (rule in rules) {
            if (rule.pattern.isBlank()) {
                skipped++
                continue
            }
            val ruleId = rule.id.ifBlank { System.currentTimeMillis().toString() + "_" + java.util.UUID.randomUUID().toString().take(6) }
            val existing = db.prepareStatement("select 1 from replace_rule where id = ?").use { stmt ->
                stmt.setString(1, ruleId)
                stmt.executeQuery().use { it.next() }
            }
            db.prepareStatement("""
                insert into replace_rule(id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                  name = excluded.name,
                  group_name = excluded.group_name,
                  pattern = excluded.pattern,
                  replacement = excluded.replacement,
                  is_regex = excluded.is_regex,
                  scope = excluded.scope,
                  exclude_scope = excluded.exclude_scope,
                  scope_title = excluded.scope_title,
                  scope_content = excluded.scope_content,
                  is_enabled = excluded.is_enabled,
                  sort_order = excluded.sort_order,
                  timeout_ms = excluded.timeout_ms,
                  updated_at = excluded.updated_at
            """.trimIndent()).use { stmt ->
                stmt.setString(1, ruleId)
                stmt.setString(2, rule.name.ifBlank { rule.pattern })
                stmt.setString(3, rule.group)
                stmt.setString(4, rule.pattern)
                stmt.setString(5, rule.replacement)
                stmt.setInt(6, if (rule.isRegex) 1 else 0)
                stmt.setString(7, rule.scope)
                stmt.setString(8, rule.excludeScope)
                stmt.setInt(9, if (rule.scopeTitle) 1 else 0)
                stmt.setInt(10, if (rule.scopeContent) 1 else 0)
                stmt.setInt(11, if (rule.isEnabled) 1 else 0)
                stmt.setInt(12, rule.order)
                stmt.setLong(13, if (rule.timeoutMillisecond > 0) rule.timeoutMillisecond else 3000L)
                stmt.setLong(14, if (rule.createdAt > 0) rule.createdAt else now)
                stmt.setLong(15, now)
                stmt.executeUpdate()
            }
            if (existing) updated++ else imported++
        }
        ReplaceRuleImportResponse(imported, updated, skipped, rules.size)
    }

    fun exportReplaceRules(ids: List<String>? = null): List<ReplaceRule> = connect { db ->
        val sql = if (ids.isNullOrEmpty()) {
            "select id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at from replace_rule order by sort_order asc, created_at asc"
        } else {
            val placeholders = ids.joinToString(",") { "?" }
            "select id, name, group_name, pattern, replacement, is_regex, scope, exclude_scope, scope_title, scope_content, is_enabled, sort_order, timeout_ms, created_at, updated_at from replace_rule where id in ($placeholders) order by sort_order asc, created_at asc"
        }
        db.prepareStatement(sql).use { stmt ->
            ids?.forEachIndexed { idx, id -> stmt.setString(idx + 1, id) }
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<ReplaceRule>()
                while (rs.next()) list.add(rs.toReplaceRule())
                list
            }
        }
    }

    private fun getBookshelf(db: Connection, sourceId: String, bookUrl: String): BookshelfItem? = db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable,p.position_ms from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.source_id=? and s.book_url=?""").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } }

    /**
     * 判断 `coverUrl` 是否为本服务自己的封面接口地址（自引用）。
     *
     * 形如 `/api/covers/<key>` 或带origin的完整地址都算。允许与当前 `coverKey` 不同：
     * 只要是本服务的封面接口，就不该被当作"外部封面地址"存进 `cover_url`
     * （它本来就由 `cover_key` 表达，重复存放只会产生循环回退）。
     */
    private fun isSelfCoverReference(url: String, coverKey: String?): Boolean {
        val trimmed = url.trim()
        if (!trimmed.contains("/api/covers/")) return false
        val path = runCatching { URI(trimmed).path }.getOrNull() ?: trimmed
        if (!path.startsWith("/api/covers/")) return false
        val key = path.removePrefix("/api/covers/").trim('/')
        // 本服务封面 key 是 64 位 hex；与自身 key 相同、或本身就指向本服务封面接口，均视为自引用
        return coverKey == null || key == coverKey || key.matches(Regex("[0-9a-f]{64}"))
    }
    private fun migrateBookGroup(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(book_group)").use { result ->
                buildSet { while (result.next()) add(result.getString("name")) }
            }
        }
        if ("is_private" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table book_group add column is_private integer not null default 0") }
        }
    }
    private fun migrateReadingProgress(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(reading_progress)").use { result ->
                buildSet { while (result.next()) add(result.getString("name")) }
            }
        }
        if ("scroll_position" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table reading_progress add column scroll_position real not null default 0") }
        }
        // 有声书的毫秒进度（G-1 裁决选 (a)：新增列，不动既有 0..1 校验与文本/漫画语义）。
        // 幂等：老库补列，新库由上方 create table + 本句都安全。
        if ("position_ms" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table reading_progress add column position_ms integer") }
        }
    }
    private fun migrateBookshelf(db: Connection) {
        val columns = db.createStatement().use { statement -> statement.executeQuery("pragma table_info(book_shelf)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } } }
        if ("completed" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column completed integer not null default 0") }
        if ("alternate_sources" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column alternate_sources text") }
        if ("group_name" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column group_name text") }
        if ("book_kind" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column book_kind text") }
        // 书籍变量（Legado `Book.variable`）：JSON 对象字符串，供书源 JS 的 `book.getVariable(...)` 读取。
        // 幂等：老库补列，新库由 create table 之外的这条也安全（列已存在则跳过）。
        if ("variable" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column variable text") }
        backfillBookKind(db)
    }

    /**
     * 一次性回填书架 `book_kind`：**本地书身份优先 → 书自己的 `tab` → 书源声明兜底**
     * （见 [BookKind.isLocalRef] / [BookKind.resolveOrNull]）。
     *
     * 覆盖三类历史数据：老书架行 `book_kind` 为空；备份导入时"书自己没说类型"但书源声明了类型的行；
     * 以及**本地导入书**（可能是空、也可能是早年按小说写成的 `novel`）—— issue #66 起本地书自成一类，
     * 必须落库成 `local`，否则前端只能靠 `sourceId` 猜、备份导出的 `type` 位也写不对。
     *
     * 「新数据不再出错」≠「老数据自动好了」（AGENTS.md）：此函数幂等，只动上面这三类；
     * 只做一次 `select` + 少量 `update`，**不解析任何书**（启动路径不许重 I/O）。
     */
    private fun backfillBookKind(db: Connection) {
        val resolved = mutableListOf<Triple<String, String, String>>()
        // 也覆盖**已被写成 'novel' 的历史行**：老逻辑只认源声明，把聚合源里的漫画写成了 novel
        // （真实现场：用户书架上 15 本漫画全被写成 novel）。「新数据不再出错」≠「老数据自动好了」，
        // 所以这里对 `novel` 也做一次幂等纠正 —— 判据是**书自己的 tab**，不是猜。
        db.prepareStatement(
            """select s.source_id, s.book_url, s.book_kind, src.payload from book_shelf s
               left join source src on src.id = s.source_id
               where s.book_kind is null or s.book_kind = '' or s.book_kind = 'novel'""",
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val sourceId = rs.getString(1)
                    val bookUrl = rs.getString(2)
                    val current = rs.getString(3)?.trim()?.lowercase().orEmpty()
                    // 本地书是**身份**而非"书的说法"：`loc_book` / `local://` 一律 `local`，
                    // 绝不让它掉进下面的 tab/源声明推导（那是给在线书用的）。
                    val kind = if (BookKind.isLocalRef(sourceId, bookUrl)) {
                        BookKind.LOCAL.wire
                    } else {
                        // 书自己的 tab 优先（聚合源不声明类型，只能靠这个）；都没有 ⇒ 不写
                        BookKind.resolveOrNull(null, bookUrl, rs.getString(4)) ?: continue
                    }
                    if (kind == current) continue // 已经是这个值 ⇒ 跳过，不做无谓写库
                    resolved.add(Triple(sourceId, bookUrl, kind))
                }
            }
        }
        for ((sourceId, bookUrl, kind) in resolved) {
            db.prepareStatement("update book_shelf set book_kind=? where source_id=? and book_url=?").use { u ->
                u.setString(1, kind); u.setString(2, sourceId); u.setString(3, bookUrl); u.executeUpdate()
            }
        }
    }

    /**
     * 立刻跑一次「书籍类型回填」（备份导入后调用）。
     *
     * ## 为什么需要它（而不是等下次启动）
     * 备份导入只按**书自己的 `tab`** 定类型；书自己没说、但书源确实声明了类型的书，
     * 要等启动期回填才会被修正 ⇒ 导入完立刻点开那本漫画会先落到文字阅读器。
     * 因此导入流程结束时显式调一次（与启动期回填同一个实现，幂等）。
     */
    fun backfillBookKindFromSources() = write { db -> backfillBookKind(db) }
    /**
     * 订阅条目对齐开源阅读 `RuleSub`：名称 / 类型（0 书源、1 订阅源、2 替换规则）/ 自动更新 / 间隔小时。
     * 列默认值取「每 6 小时自动更新的书源订阅」，即加列之前所有订阅的实际行为。
     */
    private fun migrateSubscriptionTable(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(source_subscription)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
        fun add(name: String, ddl: String) {
            if (name !in columns) db.createStatement().use { it.executeUpdate("alter table source_subscription add column $name $ddl") }
        }
        add("name", "text not null default ''")
        add("type", "integer not null default 0")
        add("auto_update", "integer not null default 1")
        add("update_interval", "integer not null default 6")
    }

    private fun migrateSourceTable(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(source)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }
        if ("has_login" !in columns) {
            db.createStatement().use {
                it.executeUpdate("alter table source add column has_login integer not null default 0")
            }
        }
        db.createStatement().use {
            it.executeUpdate("update source set has_login = 1 where (payload like '%\"loginUi\"%' or payload like '%\"loginUrl\"%' or payload like '%\"loginCheckJs\"%') and (has_login is null or has_login = 0)")
        }
    }
    private fun migrateBookContentCache(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(book_content_cache)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }
        if ("raw_title" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table book_content_cache add column raw_title text") }
        }
        if ("raw_content" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table book_content_cache add column raw_content text") }
        }
    }

    /**
     * 漫画图片索引的增量迁移（幂等）。
     *
     * 为什么需要它：`manga_image_cache` 在实现中途调整过形状 —— 从「只记已落盘的字节」
     * 改成「**清单阶段先登记 pending 行**（因为对外 key 是 URL 派生的哈希、不可逆，
     * 服务端必须留着 URL 才知道去哪抓），按需再填充字节」。开发期已经建过表的库必须能平滑加上新列。
     */
    private fun migrateMangaImageCache(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(manga_image_cache)").use { rs ->
                buildSet { while (rs.next()) add(rs.getString("name")) }
            }
        }
        if (columns.isEmpty()) return
        if ("chapter_index" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table manga_image_cache add column chapter_index integer not null default 0") }
        }
        if ("segment_index" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table manga_image_cache add column segment_index integer not null default 0") }
        }
        if ("state" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table manga_image_cache add column state text not null default 'pending'") }
        }
        if ("last_error" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table manga_image_cache add column last_error text") }
        }
    }

    /**
     * 缓存落盘相对路径（`file_path`）的增量迁移（幂等）。
     *
     * 缓存目录从「散列两级目录」改成「书/章可读目录」后，行必须记住自己落在哪 ——
     * 但**它只是快路径缓存**：读取定位还有第二级（无中文的哈希+序号扫描，见
     * `AtomicFileStore.findByNameFree`），所以即使这一列为空也不会读不到文件。
     */
    private fun migrateCacheFilePaths(db: Connection) {
        // `cover_cache` 同列同理：封面文件名从 `covers/<key>` 改成 `<书名 (书哈希).扩展名>` 后，
        // 行必须记住自己落在哪；读取侧同样有兜底（哈希段无中文扫描 + 旧形态 `covers/<key>`）。
        listOf("manga_image_cache", "audio_cache", "cover_cache").forEach { table ->
            val columns = db.createStatement().use { statement ->
                statement.executeQuery("pragma table_info($table)").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("name")) }
                }
            }
            if (columns.isEmpty()) return@forEach
            if ("file_path" !in columns) {
                db.createStatement().use { it.executeUpdate("alter table $table add column file_path text not null default ''") }
            }
        }
    }
    fun listHttpTts(query: String? = null): List<HttpTts> = connect { db ->
        val sql = if (!query.isNullOrBlank()) {
            "select id, name, url, header, content_type, concurrent_rate, login_url, login_check_js, login_ui, js_lib, enabled_cookie_jar, last_update_time from http_tts where name like ? or url like ? order by id asc"
        } else {
            "select id, name, url, header, content_type, concurrent_rate, login_url, login_check_js, login_ui, js_lib, enabled_cookie_jar, last_update_time from http_tts order by id asc"
        }
        db.prepareStatement(sql).use { stmt ->
            if (!query.isNullOrBlank()) {
                val q = "%${query.trim()}%"
                stmt.setString(1, q)
                stmt.setString(2, q)
            }
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<HttpTts>()
                while (rs.next()) {
                    list.add(rs.toHttpTts())
                }
                list
            }
        }
    }

    fun getHttpTts(id: Long): HttpTts? = connect { db ->
        db.prepareStatement("select id, name, url, header, content_type, concurrent_rate, login_url, login_check_js, login_ui, js_lib, enabled_cookie_jar, last_update_time from http_tts where id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.toHttpTts() else null }
        }
    }

    fun saveHttpTts(tts: HttpTts): HttpTts = write { db ->
        val now = System.currentTimeMillis()
        val id = if (tts.id != 0L) tts.id else now
        val lastUpdate = if (tts.lastUpdateTime > 0L) tts.lastUpdateTime else now
        db.prepareStatement("""
            insert into http_tts(id, name, url, header, content_type, concurrent_rate, login_url, login_check_js, login_ui, js_lib, enabled_cookie_jar, last_update_time)
            values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict(id) do update set
              name = excluded.name,
              url = excluded.url,
              header = excluded.header,
              content_type = excluded.content_type,
              concurrent_rate = excluded.concurrent_rate,
              login_url = excluded.login_url,
              login_check_js = excluded.login_check_js,
              login_ui = excluded.login_ui,
              js_lib = excluded.js_lib,
              enabled_cookie_jar = excluded.enabled_cookie_jar,
              last_update_time = excluded.last_update_time
        """.trimIndent()).use { stmt ->
            stmt.setLong(1, id)
            stmt.setString(2, tts.name.ifBlank { "未命名" })
            stmt.setString(3, tts.url)
            stmt.setString(4, tts.header)
            stmt.setString(5, tts.contentType)
            stmt.setString(6, tts.concurrentRate)
            stmt.setString(7, tts.loginUrl)
            stmt.setString(8, tts.loginCheckJs)
            stmt.setString(9, tts.loginUi)
            stmt.setString(10, tts.jsLib)
            stmt.setInt(11, if (tts.enabledCookieJar) 1 else 0)
            stmt.setLong(12, lastUpdate)
            stmt.executeUpdate()
        }
        getHttpTts(id) ?: tts.copy(id = id, lastUpdateTime = lastUpdate)
    }

    fun deleteHttpTts(id: Long): Boolean = write { db ->
        db.prepareStatement("delete from http_tts where id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeUpdate() > 0
        }
    }

    fun importHttpTts(list: List<HttpTts>): HttpTtsImportResponse {
        var imported = 0
        var failed = 0
        for (item in list) {
            try {
                if (item.url.isBlank()) {
                    failed++
                    continue
                }
                saveHttpTts(item)
                imported++
            } catch (_: Throwable) {
                failed++
            }
        }
        return HttpTtsImportResponse(total = list.size, imported = imported, failed = failed)
    }

    private fun secret(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun passwordHash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(password, salt, PBKDF2_ITERATIONS)
        return "pbkdf2-sha256$${PBKDF2_ITERATIONS}$${Base64.getEncoder().encodeToString(salt)}$${Base64.getEncoder().encodeToString(digest)}"
    }
    private fun verifyPassword(stored: String, password: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return runCatching { MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[2]), iterations), Base64.getDecoder().decode(parts[3])) }.getOrDefault(false)
    }
    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    companion object {
        /**
         * 书籍变量里承载"自定义值"的键名。
         *
         * **对齐开源阅读**（`legado-with-MD3` 的 `BaseBook.putCustomVariable()`/`getCustomVariable()`）：
         * 那里把它做成一等公民，编辑 UI 也只编辑它。实测最常见的用途就是听书音色/朗读者
         * （`🍅大灰狼聚合` 源：`book.getVariable('custom')` 取音色编号）。
         */
        const val CUSTOM_VARIABLE_KEY = "custom"

        private const val SESSION_TTL = 30L * 24 * 60 * 60 * 1000
        /** 漫画章节清单的列顺序必须与 `toMangaChapter()` 的取值下标逐一对齐。 */
        private const val MANGA_CHAPTER_SELECT =
            "select source_id,book_url,chapter_url,chapter_index,pinned,images_json,total,ready,skipped,state,last_error,created_at,updated_at from manga_chapter_cache"
        /** 漫画图片索引的列顺序必须与 `toMangaImage()` 的取值下标逐一对齐。 */
        private const val MANGA_IMAGE_SELECT =
            "select url_hash,cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,content_type,byte_size,width,height,state,last_error,created_at,last_access_at,file_path from manga_image_cache"
        /** 音频索引的列顺序必须与 `toAudioCache()` 的取值下标逐一对齐。 */
        private const val AUDIO_SELECT =
            "select cache_key,source_id,book_url,chapter_url,chapter_index,segment_index,upstream_url,headers_json,content_type,byte_size,duration_ms,state,last_error,pinned,listened,created_at,last_access_at,file_path from audio_cache"
        /** 插图索引的列顺序必须与 `toIllustration()` 的取值下标逐一对齐。 */
        private const val ILLUSTRATION_SELECT =
            "select url_hash,source_id,book_url,chapter_url,upstream_url,chapter_index,segment_index,content_type,byte_size,cache_key,state,created_at,last_access_at,file_path from illustration_cache"
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256

        /**
         * 章节索引启动回填的单轮上限。
         *
         * 限量是为了守住「启动路径不许重 I/O」：50 本书的 `chapters_json` 解析在毫秒级，
         * 而整架书（实测 400+ 本）一次全解析会让 `Application started` 明显推迟。
         * 剩下的由后续每次启动继续补（幂等：已索引的书不会再被挑中）。
         */
        private const val CHAPTER_INDEX_BACKFILL_LIMIT = 50
    }
}

class VersionConflict : RuntimeException()

/** 正文搜索 snippet 的前后文半径（命中位置前后各 40 字符）。 */
private const val SNIPPET_RADIUS = 40

/** 正文搜索 snippet 的总长度上限（含首尾省略号）。 */
private const val SNIPPET_MAX_LENGTH = 120

/** snippet 需要压掉的空白：正文按段落换行，弹窗里一行就够。 */
private val SNIPPET_WHITESPACE_RE = Regex("""[\r\n\t\u00a0\u3000 ]+""")

/**
 * 由命中位置生成正文摘要（服务端做，前端只管高亮）。
 *
 * 规则（用户要求）：命中位置**前后各 40 字符**、压掉换行、最多 **120 字符**。
 * 找不到命中位置（理论上不该发生，SQL 已经筛过）时退化为「正文开头 120 字符」，
 * 只影响展示，绝不能让整条搜索结果消失。
 *
 * ⚠️ 大小写折叠后**下标可能对不上原串**（极少数字符 lowercase 会变长），
 * 所以起止下标一律 `coerceIn` 到安全范围，宁可摘要偏一点也不能抛
 * `StringIndexOutOfBoundsException` 把整个搜索请求打成 500。
 */
internal fun buildSearchSnippet(
    content: String,
    query: String,
    radius: Int = SNIPPET_RADIUS,
    maxLength: Int = SNIPPET_MAX_LENGTH,
): String {
    if (content.isEmpty()) return ""
    val flat = SNIPPET_WHITESPACE_RE.replace(content, " ").trim()
    if (flat.isEmpty()) return ""
    val needle = SNIPPET_WHITESPACE_RE.replace(query.trim(), " ")
    val hit = if (needle.isEmpty()) -1 else flat.lowercase().indexOf(needle.lowercase())
    if (hit < 0) return flat.take(maxLength)
    val start = (hit - radius).coerceIn(0, flat.length)
    val end = (hit + needle.length + radius).coerceIn(start, flat.length)
    val body = flat.substring(start, end).trim().ifEmpty { flat.take(maxLength) }
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return (prefix + body + suffix).take(maxLength)
}

private fun ResultSet.toHttpTts(): HttpTts = HttpTts(
    id = getLong("id"),
    name = getString("name"),
    url = getString("url"),
    header = getString("header"),
    contentType = getString("content_type"),
    concurrentRate = getString("concurrent_rate"),
    loginUrl = getString("login_url"),
    loginCheckJs = getString("login_check_js"),
    loginUi = getString("login_ui"),
    jsLib = getString("js_lib"),
    enabledCookieJar = getInt("enabled_cookie_jar") == 1,
    lastUpdateTime = getLong("last_update_time"),
)
