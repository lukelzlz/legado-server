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
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.concurrent.withLock

class Database(private val path: String) : Closeable, AutoCloseable {
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

    init { Files.createDirectories(Path.of(path).toAbsolutePath().parent) }

    private fun createConnection(): Connection = sqliteConfig.createConnection("jdbc:sqlite:$path")

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

    fun initialize(initialPassword: String?) = write { db ->
        db.createStatement().use { statement ->
            statement.execute("pragma journal_mode = wal")
            statement.executeUpdate("""
                create table if not exists app_user (
                  id integer primary key check (id = 1), password_hash text not null
                );
                create table if not exists session (
                  id text primary key, csrf_token text not null, expires_at integer not null
                );
                create table if not exists source (
                  id text primary key, name text not null, source_url text not null,
                  source_group text, enabled integer not null, is_js integer not null,
                  payload text not null, version integer not null, updated_at integer not null
                );
                create index if not exists source_name_idx on source(name);
                create index if not exists idx_source_name_nocase on source(name collate nocase);
                create table if not exists reading_progress (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  chapter_index integer not null, updated_at integer not null,
                  primary key (source_id, book_url)
                );
                create table if not exists cover_cache (
                  cache_key text primary key, content_type text not null
                );
                create table if not exists app_setting (
                  key text primary key, value text not null, updated_at integer not null
                );
                create table if not exists book_group (
                  id integer primary key autoincrement,
                  name text not null unique collate nocase,
                  sort_order integer not null default 0,
                  created_at integer not null
                );
                create index if not exists idx_book_group_sort on book_group(sort_order asc, id asc);
                create table if not exists bookmark (
                  id integer primary key autoincrement,
                  book_name text not null,
                  book_author text,
                  chapter_index integer not null default 0,
                  chapter_name text,
                  chapter_pos integer not null default 0,
                  book_text text,
                  content text,
                  created_at integer not null,
                  unique(book_name, book_author, chapter_index, chapter_pos)
                );
                create index if not exists idx_bookmark_book on bookmark(book_name, book_author);
                create table if not exists book_shelf (
                  source_id text not null, book_url text not null, name text not null,
                  author text, toc_url text not null, cover_url text, cover_key text,
                  last_read_at integer not null, completed integer not null default 0,
                  alternate_sources text, group_name text,
                  primary key (source_id, book_url)
                );
                create index if not exists book_shelf_last_read_idx on book_shelf(last_read_at desc);
                create table if not exists book_content_cache (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  title text, content text not null, cached_at integer not null,
                  raw_title text, raw_content text,
                  primary key (source_id, book_url, chapter_url)
                );
                create index if not exists book_content_cache_book_idx on book_content_cache(source_id, book_url);
                create table if not exists book_cache_status (
                  source_id text not null, book_url text not null, total_chapters integer not null default 0,
                  cached_chapters integer not null default 0, state text not null default 'idle',
                  last_error text, updated_at integer not null,
                  primary key (source_id, book_url)
                );
                create table if not exists source_subscription (
                  id integer primary key autoincrement, url text not null unique,
                  enabled integer not null, created_at integer not null, updated_at integer not null,
                  last_success_at integer, last_attempt_at integer, last_error text,
                  last_imported integer not null default 0, content_hash text
                );
                create index if not exists source_subscription_enabled_idx on source_subscription(enabled);
                create table if not exists book_toc_cache (
                  source_id text not null, toc_url text not null, chapters_json text not null, updated_at integer not null,
                  primary key (source_id, toc_url)
                );
                create table if not exists source_login_state (
                  source_id text primary key,
                  login_info text,
                  login_header text,
                  source_variable text,
                  source_kv text,
                  cookie_jar text,
                  updated_at integer not null
                );
                create table if not exists replace_rule (
                  id text primary key,
                  name text not null,
                  group_name text,
                  pattern text not null,
                  replacement text not null default '',
                  is_regex integer not null default 1,
                  scope text,
                  exclude_scope text,
                  scope_title integer not null default 0,
                  scope_content integer not null default 1,
                  is_enabled integer not null default 1,
                  sort_order integer not null default 0,
                  timeout_ms integer not null default 3000,
                  created_at integer not null,
                  updated_at integer not null
                );
                create index if not exists idx_replace_rule_enabled on replace_rule(is_enabled, sort_order asc);
                create index if not exists idx_replace_rule_group on replace_rule(group_name);
            """.trimIndent())
        }
        migrateReadingProgress(db)
        migrateBookshelf(db)
        migrateSourceTable(db)
        migrateBookContentCache(db)
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
            select trim(source_group) as group_name,
                   count(*) as source_count,
                   sum(case when enabled = 1 then 1 else 0 end) as enabled_count
            from source
            where source_group is not null and trim(source_group) <> ''
            group by trim(source_group) collate nocase
            order by group_name collate nocase
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
        db.prepareStatement("""insert into reading_progress(source_id,book_url,chapter_url,chapter_index,scroll_position,updated_at) values(?,?,?,?,?,?)
            on conflict(source_id,book_url) do update set chapter_url=excluded.chapter_url,chapter_index=excluded.chapter_index,scroll_position=excluded.scroll_position,updated_at=excluded.updated_at""").use {
            it.setString(1, progress.sourceId); it.setString(2, progress.bookUrl); it.setString(3, progress.chapterUrl); it.setInt(4, progress.chapterIndex); it.setDouble(5, progress.scrollPosition); it.setLong(6, now); it.executeUpdate()
        }
        db.prepareStatement("update book_shelf set last_read_at=? where source_id=? and book_url=?").use {
            it.setLong(1, now); it.setString(2, progress.sourceId); it.setString(3, progress.bookUrl); it.executeUpdate()
        }
        progress.copy(updatedAt = now)
    }
    fun getProgress(sourceId: String, bookUrl: String): ReadingProgress? = connect { db -> db.prepareStatement("select source_id,book_url,chapter_url,chapter_index,scroll_position,updated_at from reading_progress where source_id=? and book_url=?").use {
        it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) ReadingProgress(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getLong(6)) else null }
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
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources,group_name) values(?,?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=excluded.cover_url,cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at,alternate_sources=coalesce(excluded.alternate_sources,book_shelf.alternate_sources),group_name=coalesce(excluded.group_name,book_shelf.group_name)""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, cleanName); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl?.takeIf { u -> !isSelfCoverReference(u, cover?.key) }); it.setString(7, cover?.key); it.setLong(8, now); it.setString(9, altJson); it.setString(10, cleanGroup); it.executeUpdate()
            }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!!
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    fun updateBookshelfCover(sourceId: String, bookUrl: String, coverKey: String, contentType: String = "image/*"): Boolean = write { db ->
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
        db.prepareStatement("update book_shelf set cover_key=? where source_id=? and book_url=?").use {
            it.setString(1, coverKey)
            it.setString(2, sourceId)
            it.setString(3, bookUrl)
            it.executeUpdate() > 0
        }
    }
    fun listBookshelf(): List<BookshelfItem> = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url order by s.last_read_at desc""").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toShelf()) } } } }
    fun getShelfBookByUrl(bookUrl: String): BookshelfItem? = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.book_url=?""").use { it.setString(1, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } } }
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
            db.prepareStatement("select cover_key, group_name, alternate_sources from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        found = true
                        oldCover = rs.getString(1)
                        oldGroup = rs.getString(2)
                        oldAlts = rs.getString(3)
                    }
                }
            }
            if (!found) return@write null

            cover?.let { value ->
                db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use {
                    it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate()
                }
            }

            val newCoverKey = cover?.key ?: (if (request.coverUrl != null && request.coverUrl.isBlank()) null else oldCover)
            val newGroup = if (request.groupName != null) request.groupName.trim().takeIf { it.isNotEmpty() } else oldGroup
            val newAlts = if (request.alternateSources != null) Json.encodeToString(request.alternateSources) else oldAlts
            // 拒绝把本服务自己的封面接口地址回写成 coverUrl。
            //
            // 实测（SESSION-027）：编辑弹窗在没有外部 URL 时会回退调用 api.cover(coverKey)，
            // 于是把 `/api/covers/<自己的 key>` 当成"外部封面地址"存了回来，形成自引用。
            // 危害：一旦 coverKey 被清空，前端回退到 coverUrl 就指向自身，形成无意义的循环。
            // 这里以 coverKey 为准，剥离该自引用（保留真实外部 URL）。
            val sanitizedCoverUrl = request.coverUrl?.takeIf { !isSelfCoverReference(it, newCoverKey) }

            db.prepareStatement("update book_shelf set name=?, author=?, cover_url=?, cover_key=?, group_name=?, alternate_sources=? where source_id=? and book_url=?").use {
                it.setString(1, sanitizeBookName(request.name).ifBlank { request.name.trim() })
                it.setString(2, request.author)
                it.setString(3, sanitizedCoverUrl)
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
            val key = db.prepareStatement("select cover_key from book_shelf where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
            db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_content_cache where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_cache_status where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_toc_cache where source_id=? and (toc_url=? or toc_url like ?)").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, "%$bookUrl%"); it.executeUpdate() }
            val orphan = key?.takeIf { value -> db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
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
            cover?.let { value -> db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use { it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate() } }
            val newCoverKey = cover?.key ?: oldCover
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources,group_name) values(?,?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at,alternate_sources=excluded.alternate_sources,group_name=coalesce(excluded.group_name,book_shelf.group_name)""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, request.name); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl); it.setString(7, newCoverKey); it.setLong(8, now); it.setString(9, altJson); it.setString(10, cleanGroup); it.executeUpdate()
            }
            val orphan = oldCover?.takeIf { value -> value != newCoverKey && db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!! to orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }

    fun listBookGroups(): List<BookGroup> = connect { db ->
        db.prepareStatement("""
            select g.id, g.name, g.sort_order, count(s.book_url) as book_count
            from book_group g
            left join book_shelf s on s.group_name = g.name collate nocase
            group by g.id, g.name, g.sort_order
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
                                bookCount = rs.getInt(4)
                            )
                        )
                    }
                }
            }
        }
    }

    fun getBookGroup(name: String): BookGroup? = connect { db ->
        db.prepareStatement("""
            select g.id, g.name, g.sort_order, count(s.book_url) as book_count
            from book_group g
            left join book_shelf s on s.group_name = g.name collate nocase
            where g.name = ? collate nocase
            group by g.id, g.name, g.sort_order
        """).use { stmt ->
            stmt.setString(1, name.trim())
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    BookGroup(
                        id = rs.getLong(1),
                        name = rs.getString(2),
                        sortOrder = rs.getInt(3),
                        bookCount = rs.getInt(4)
                    )
                } else null
            }
        }
    }

    fun createBookGroup(name: String): BookGroup = write { db ->
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
        db.prepareStatement("insert into book_group(name, sort_order, created_at) values(?, ?, ?)").use { stmt ->
            stmt.setString(1, trimmed)
            stmt.setInt(2, maxOrder + 1)
            stmt.setLong(3, now)
            stmt.executeUpdate()
        }
        db.prepareStatement("select id, name, sort_order from book_group where name=? collate nocase").use { stmt ->
            stmt.setString(1, trimmed)
            stmt.executeQuery().use { rs ->
                if (rs.next()) BookGroup(rs.getLong(1), rs.getString(2), rs.getInt(3), 0)
                else throw IllegalStateException("创建分组失败")
            }
        }
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
            db.prepareStatement("""
                insert into book_shelf(source_id, book_url, name, author, toc_url, cover_url, cover_key, last_read_at, completed, alternate_sources)
                values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(source_id, book_url) do update set
                    name = excluded.name, author = excluded.author, toc_url = excluded.toc_url,
                    cover_key = coalesce(excluded.cover_key, book_shelf.cover_key),
                    last_read_at = excluded.last_read_at
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

            // 3. Insert TOC cache
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

            // 4. Batch insert chapters into book_content_cache
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

            // 5. Update book_cache_status to ready
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

            db.commit()
            getBookshelf(db, sourceId, bookUrl)!!
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
            order by rowid asc
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
    fun clearBookCacheContent(sourceId: String, bookUrl: String): Int = write { db ->
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
    fun cacheRequests(): List<CachedBookRequest> = connect { db ->
        db.prepareStatement(
            """
            select s.source_id, s.book_url, s.toc_url
            from book_shelf s
            join book_cache_status c on c.source_id = s.source_id and c.book_url = s.book_url
            where c.state in ('pending', 'caching')
              and s.book_url not like 'content://%'
              and s.book_url not like 'local://%'
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
                        insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,completed,alternate_sources)
                        values(?,?,?,?,?,?,null,?,?,null)
                        on conflict(source_id,book_url) do update set
                          name=excluded.name, author=excluded.author, toc_url=excluded.toc_url,
                          cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),
                          last_read_at=excluded.last_read_at, completed=excluded.completed
                    """.trimIndent()).use { save ->
                        entries.forEach { entry ->
                            existing.setString(1, entry.sourceId); existing.setString(2, entry.bookUrl)
                            if (existing.executeQuery().use { it.next() }) updated++ else imported++
                            save.setString(1, entry.sourceId); save.setString(2, entry.bookUrl); save.setString(3, entry.name)
                            save.setString(4, entry.author); save.setString(5, entry.tocUrl); save.setString(6, entry.coverUrl)
                            save.setLong(7, if (entry.readAt > 0) entry.readAt else now)
                            save.setInt(8, if (entry.completed) 1 else 0)
                            save.addBatch()
                        }
                        save.executeBatch()
                    }
                }
                val progressed = entries.filter { it.chapterIndex > 0 }
                if (progressed.isNotEmpty()) {
                    db.prepareStatement("""
                        insert into reading_progress(source_id,book_url,chapter_url,chapter_index,scroll_position,updated_at)
                        values(?,?,?,?,0,?)
                        on conflict(source_id,book_url) do update set
                          chapter_url=excluded.chapter_url, chapter_index=excluded.chapter_index, updated_at=excluded.updated_at
                        where excluded.updated_at >= reading_progress.updated_at
                    """.trimIndent()).use { stmt ->
                        progressed.forEach { entry ->
                            stmt.setString(1, entry.sourceId); stmt.setString(2, entry.bookUrl); stmt.setString(3, "")
                            stmt.setInt(4, entry.chapterIndex); stmt.setLong(5, if (entry.readAt > 0) entry.readAt else now)
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
            if (groups.isNotEmpty()) {
                db.prepareStatement(
                    "insert into book_group(name, sort_order, created_at) values(?,?,?) " +
                        "on conflict(name) do update set sort_order=excluded.sort_order"
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    groups.distinctBy { it.groupName.lowercase() }.forEach { group ->
                        stmt.setString(1, group.groupName)
                        stmt.setInt(2, group.order)
                        stmt.setLong(3, now)
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
                        stmt.setString(1, entry.groupName)
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

    fun listSubscriptions(enabledOnly: Boolean = false): List<SourceSubscription> = connect { db ->        val sql = "select * from source_subscription" + (if (enabledOnly) " where enabled=1" else "") + " order by id"
        db.prepareStatement(sql).use { statement -> statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSubscription()) } } }
    }

    fun saveSubscription(request: SubscriptionWriteRequest): SourceSubscription = write { db ->
        val now = System.currentTimeMillis()
        db.prepareStatement("""insert into source_subscription(url,enabled,created_at,updated_at) values(?,?,?,?)
            on conflict(url) do update set enabled=excluded.enabled,updated_at=excluded.updated_at""").use {
            it.setString(1, request.url); it.setInt(2, if (request.enabled) 1 else 0); it.setLong(3, now); it.setLong(4, now); it.executeUpdate()
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
    private fun java.sql.ResultSet.toSubscription() = SourceSubscription(getLong("id"), getString("url"), getInt("enabled") == 1, getLong("created_at"), getLong("updated_at"), getLong("last_success_at").takeIf { !wasNull() }, getLong("last_attempt_at").takeIf { !wasNull() }, getString("last_error"), getInt("last_imported"), getString("content_hash"))
    private fun java.sql.ResultSet.toShelf(): BookshelfItem {
        val altJson = getString(15)
        val altSources = if (!altJson.isNullOrBlank()) {
            runCatching { Json.decodeFromString<List<SearchResult>>(altJson) }.getOrDefault(emptyList())
        } else emptyList()
        return BookshelfItem(
            sourceId = getString(1),
            bookUrl = getString(2),
            name = getString(3),
            author = getString(4),
            tocUrl = getString(5),
            coverKey = getString(6),
            chapterIndex = getObject(7) as? Int,
            scrollPosition = getObject(8) as? Double,
            lastReadAt = getLong(9),
            cachedChapters = getInt(10),
            totalChapters = getInt(11),
            cacheState = getString(12),
            cacheError = getString(13),
            completed = getInt(14) != 0,
            alternateSources = altSources,
            groupName = runCatching { getString(16) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = runCatching { getString(17) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        )
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

    private fun getBookshelf(db: Connection, sourceId: String, bookUrl: String): BookshelfItem? = db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.source_id=? and s.book_url=?""").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } }

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
    private fun migrateReadingProgress(db: Connection) {
        val columns = db.createStatement().use { statement ->
            statement.executeQuery("pragma table_info(reading_progress)").use { result ->
                buildSet { while (result.next()) add(result.getString("name")) }
            }
        }
        if ("scroll_position" !in columns) {
            db.createStatement().use { it.executeUpdate("alter table reading_progress add column scroll_position real not null default 0") }
        }
    }
    private fun migrateBookshelf(db: Connection) {
        val columns = db.createStatement().use { statement -> statement.executeQuery("pragma table_info(book_shelf)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } } }
        if ("completed" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column completed integer not null default 0") }
        if ("alternate_sources" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column alternate_sources text") }
        if ("group_name" !in columns) db.createStatement().use { it.executeUpdate("alter table book_shelf add column group_name text") }
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
        private const val SESSION_TTL = 30L * 24 * 60 * 60 * 1000
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}

class VersionConflict : RuntimeException()

