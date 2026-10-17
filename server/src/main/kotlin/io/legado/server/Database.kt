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
                  cache_key text primary key, content_type text not null,
                  file_path text not null default ''
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
                create table if not exists http_tts (
                  id integer primary key,
                  name text not null,
                  url text not null,
                  header text,
                  content_type text,
                  concurrent_rate text,
                  login_url text,
                  login_check_js text,
                  login_ui text,
                  js_lib text,
                  enabled_cookie_jar integer not null default 0,
                  last_update_time integer not null
                );
                create index if not exists idx_http_tts_name on http_tts(name);
                -- ------------------------------------------------------------------
                -- 漫画链路（方案 E）：
                --   manga_chapter_cache = 章节图片清单 + 整章就绪度（L2 视图层）
                --   manga_image_cache   = 图片字节索引（L1 唯一副本 + GC 依据）
                -- 详见 docs/proposals/PROPOSAL-028-* 与 docs/decisions/ADR-028-*
                -- ------------------------------------------------------------------
                create table if not exists manga_chapter_cache (
                  source_id     text not null,
                  book_url      text not null,
                  chapter_url   text not null,
                  chapter_index integer not null default 0,
                  pinned        integer not null default 0,
                  images_json   text not null,
                  total         integer not null default 0,
                  ready         integer not null default 0,
                  skipped       integer not null default 0,
                  state         text not null default 'pending',
                  last_error    text,
                  created_at    integer not null,
                  updated_at    integer not null,
                  primary key (source_id, book_url, chapter_url)
                );
                create index if not exists idx_manga_chapter_state on manga_chapter_cache(source_id, book_url, state);
                create index if not exists idx_manga_chapter_pinned on manga_chapter_cache(pinned);
                create table if not exists manga_image_cache (
                  url_hash       text primary key,
                  cache_key      text not null default '',
                  source_id      text not null,
                  book_url       text not null,
                  chapter_url    text not null,
                  chapter_index  integer not null default 0,
                  segment_index  integer not null default 0,
                  upstream_url   text not null,
                  content_type   text not null default '',
                  byte_size      integer not null default 0,
                  width          integer,
                  height         integer,
                  state          text not null default 'pending',
                  last_error     text,
                  created_at     integer not null,
                  last_access_at integer not null,
                  file_path      text not null default ''
                );
                create index if not exists idx_manga_image_key on manga_image_cache(cache_key);
                create index if not exists idx_manga_image_book on manga_image_cache(source_id, book_url);
                create index if not exists idx_manga_image_chapter on manga_image_cache(source_id, book_url, chapter_url, segment_index);
                create index if not exists idx_manga_image_access on manga_image_cache(last_access_at asc);
                -- ------------------------------------------------------------------
                -- 有声书链路（方案 G2）：
                --   audio_cache = 音频字节索引（**URL 寻址**：key 可预测 ⇒ 前端直接拼 src，
                --                 不做内容寻址去重 —— 见 ADR-029 的 G-2 裁决）
                -- 没有"音频章节清单表"：整章就绪度由本表按 chapter_url 聚合即得（少一张表少一处漂移）。
                -- 详见 docs/proposals/PROPOSAL-029-* 与 docs/decisions/ADR-029-*
                -- ------------------------------------------------------------------
                create table if not exists audio_cache (
                  cache_key      text primary key,
                  source_id      text not null,
                  book_url       text not null,
                  chapter_url    text not null,
                  chapter_index  integer not null default 0,
                  segment_index  integer not null default 0,
                  upstream_url   text not null,
                  headers_json   text,
                  content_type   text not null default '',
                  byte_size      integer not null default 0,
                  duration_ms    integer not null default 0,
                  state          text not null default 'pending',
                  last_error     text,
                  pinned         integer not null default 0,
                  listened       integer not null default 0,
                  created_at     integer not null,
                  last_access_at integer not null,
                  file_path      text not null default ''
                );
                -- 刻意**不是**唯一索引：书源换了某一段的地址时，新 key 会挂在同一个 segment_index 上，
                -- 唯一索引会让那次 upsert 直接抛异常（一次书源变更 ⇒ 整章打不开）。
                -- 排序/拼装只需要索引能加速，不需要唯一性。
                create index if not exists idx_audio_cache_segment on audio_cache(source_id, book_url, chapter_url, segment_index);
                create index if not exists idx_audio_cache_book on audio_cache(source_id, book_url);
                create index if not exists idx_audio_cache_state on audio_cache(state);
                create index if not exists idx_audio_cache_access on audio_cache(last_access_at asc);
                create index if not exists idx_audio_cache_pinned on audio_cache(pinned);
            """.trimIndent())
        }
        migrateReadingProgress(db)
        migrateBookshelf(db)
        migrateSourceTable(db)
        migrateBookContentCache(db)
        migrateMangaImageCache(db)
        migrateCacheFilePaths(db)
        healLocalBookCacheStatus(db)
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
     * 读书架上的书籍类型（`comic` / `novel`）。
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
               ) group by source_id, book_url""",
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
     * 一律允许返回 null —— **标题只是给人看的装饰**，取不到就只用 8 位章节号。
     */
    fun cacheChapterTitle(sourceId: String, bookUrl: String, chapterUrl: String, chapterIndex: Int): String? {
        val chapters = getTocCache(sourceId, bookUrl) ?: getTocCache(sourceId, "$bookUrl/toc")
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
            """select i.source_id, i.book_url, s.name, count(*) as image_count, coalesce(sum(i.byte_size),0) as total_bytes,
                      coalesce(c.chapter_count,0), coalesce(c.ready_count,0), coalesce(c.pinned,0)
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
            """select a.source_id, a.book_url, s.name, count(*) as track_count, coalesce(sum(a.byte_size),0) as total_bytes,
                      coalesce(c.chapter_count,0), coalesce(c.ready_count,0), coalesce(c.pinned,0)
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
                 )
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
    fun listBookshelf(): List<BookshelfItem> = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url order by s.last_read_at desc""").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toShelf()) } } } }
    fun getShelfBookByUrl(bookUrl: String): BookshelfItem? = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.book_url=?""").use { it.setString(1, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } } }
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
            bookKind = runCatching { getString(18) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "novel",
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

    private fun getBookshelf(db: Connection, sourceId: String, bookUrl: String): BookshelfItem? = db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources,s.group_name,s.cover_url,s.book_kind,s.variable from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.source_id=? and s.book_url=?""").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } }

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
     * 一次性回填书架 `book_kind`：**书自己的 `tab` 优先，书源声明兜底**（见 [BookKind.resolveOrNull]）。
     *
     * 覆盖两类历史数据：老书架行 `book_kind` 为空；以及备份导入时"书自己没说类型"但书源声明了类型的行。
     * 「新数据不再出错」≠「老数据自动好了」（AGENTS.md）：此函数幂等，只动 null/空值。
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
                    // 书自己的 tab 优先（聚合源不声明类型，只能靠这个）；都没有 ⇒ 不写
                    val kind = BookKind.resolveOrNull(null, bookUrl, rs.getString(4)) ?: continue
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
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}

class VersionConflict : RuntimeException()

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

