package io.legado.server

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
                create table if not exists book_shelf (
                  source_id text not null, book_url text not null, name text not null,
                  author text, toc_url text not null, cover_url text, cover_key text,
                  last_read_at integer not null, completed integer not null default 0,
                  alternate_sources text,
                  primary key (source_id, book_url)
                );
                create index if not exists book_shelf_last_read_idx on book_shelf(last_read_at desc);
                create table if not exists book_content_cache (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  title text, content text not null, cached_at integer not null,
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
            """.trimIndent())
        }
        migrateReadingProgress(db)
        migrateBookshelf(db)
        migrateSourceTable(db)
        val userExists = db.prepareStatement("select 1 from app_user where id = 1").use { it.executeQuery().next() }
        if (!userExists) {
            require(!initialPassword.isNullOrBlank()) { "首次启动必须提供 ADMIN_PASSWORD" }
            db.prepareStatement("insert into app_user(id, password_hash) values(1, ?)").use {
                it.setString(1, passwordHash(initialPassword))
                it.executeUpdate()
            }
        }
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

    fun getSource(id: String): SourceRecord? = connect { db -> db.prepareStatement("select id, payload, version, updated_at from source where id = ?").use {
        it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) SourceRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) else null }
    } }

    fun listSearchSourceRecords(sourceIds: List<String>?): List<SourceRecord> = connect { db ->
        val ids = sourceIds?.distinct()?.takeIf { it.isNotEmpty() }
        val sql = if (ids == null) {
            "select id, payload, version, updated_at from source where enabled = 1 order by name collate nocase"
        } else {
            "select id, payload, version, updated_at from source where enabled = 1 and id in (${ids.joinToString(",") { "?" }}) order by name collate nocase"
        }
        db.prepareStatement(sql).use { statement ->
            ids?.forEachIndexed { index, id -> statement.setString(index + 1, id) }
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
    fun saveBookshelf(request: BookshelfWriteRequest, cover: CachedCover?): BookshelfItem = write { db ->
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            cover?.let { value -> db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use { it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate() } }
            val altJson = request.alternateSources?.let { Json.encodeToString(it) }
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources) values(?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=excluded.cover_url,cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at,alternate_sources=coalesce(excluded.alternate_sources,book_shelf.alternate_sources)""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, request.name); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl); it.setString(7, cover?.key); it.setLong(8, now); it.setString(9, altJson); it.executeUpdate()
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
    fun listBookshelf(): List<BookshelfItem> = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url order by s.last_read_at desc""").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toShelf()) } } } }
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
            db.prepareStatement("select cover_key from book_shelf where source_id=? and book_url=?").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl)
                it.executeQuery().use { rs ->
                    if (rs.next()) {
                        found = true
                        oldCover = rs.getString(1)
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

            db.prepareStatement("update book_shelf set name=?, author=?, cover_url=?, cover_key=? where source_id=? and book_url=?").use {
                it.setString(1, request.name)
                it.setString(2, request.author)
                it.setString(3, request.coverUrl)
                it.setString(4, newCoverKey)
                it.setString(5, request.sourceId)
                it.setString(6, request.bookUrl)
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
            val orphan = key?.takeIf { value -> db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    fun switchBookshelf(oldSourceId: String, oldBookUrl: String, request: BookshelfWriteRequest, cover: CachedCover?, alternateSources: List<SearchResult>? = null): Pair<BookshelfItem, String?> = write { db ->
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            val oldCover = db.prepareStatement("select cover_key from book_shelf where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
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

            db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_content_cache where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            db.prepareStatement("delete from book_cache_status where source_id=? and book_url=?").use { it.setString(1, oldSourceId); it.setString(2, oldBookUrl); it.executeUpdate() }
            cover?.let { value -> db.prepareStatement("insert into cover_cache(cache_key,content_type) values(?,?) on conflict(cache_key) do update set content_type=excluded.content_type").use { it.setString(1, value.key); it.setString(2, value.contentType); it.executeUpdate() } }
            val newCoverKey = cover?.key ?: oldCover
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at,alternate_sources) values(?,?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=coalesce(excluded.cover_url,book_shelf.cover_url),cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at,alternate_sources=excluded.alternate_sources""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, request.name); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl); it.setString(7, newCoverKey); it.setLong(8, now); it.setString(9, altJson); it.executeUpdate()
            }
            val orphan = oldCover?.takeIf { value -> value != newCoverKey && db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!! to orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
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

    fun cachedContent(sourceId: String, bookUrl: String, chapterUrl: String): ChapterContent? = connect { db -> db.prepareStatement("select title,content from book_content_cache where source_id=? and book_url=? and chapter_url=?").use {
        it.setString(1, sourceId); it.setString(2, bookUrl); it.setString(3, chapterUrl); it.executeQuery().use { rs -> if (rs.next()) ChapterContent(rs.getString(1), rs.getString(2)) else null }
    } }
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
            insert into book_content_cache(source_id, book_url, chapter_url, title, content, cached_at)
            values(?, ?, ?, ?, ?, ?)
            on conflict(source_id, book_url, chapter_url) do update set
                title = excluded.title, content = excluded.content, cached_at = excluded.cached_at
        """.trimIndent()).use { statement ->
            statement.setString(1, sourceId)
            statement.setString(2, bookUrl)
            statement.setString(3, chapterUrl)
            statement.setString(4, content.title)
            statement.setString(5, content.content)
            statement.setLong(6, System.currentTimeMillis())
            statement.executeUpdate()
        }
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

    fun cacheRequests(): List<CachedBookRequest> = connect { db -> db.prepareStatement("select source_id,book_url,toc_url from book_shelf").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(CachedBookRequest(rs.getString(1), rs.getString(2), rs.getString(3))) } } } }

    fun importSources(rawSources: List<String>): ImportResponse {
        val errors = mutableListOf<String>()
        val unique = linkedMapOf<String, ParsedSource>()
        rawSources.forEachIndexed { index, raw ->
            try {
                val parsed = SourceCodec.parse(raw)
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
                db.prepareStatement("select version from source where id = ?").use { current ->
                    db.prepareStatement("""insert into source(id,name,source_url,source_group,enabled,is_js,payload,version,updated_at,has_login)
                        values(?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set name=excluded.name,source_url=excluded.source_url,source_group=excluded.source_group,enabled=excluded.enabled,is_js=excluded.is_js,payload=excluded.payload,version=excluded.version,updated_at=excluded.updated_at,has_login=excluded.has_login""").use { save ->
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
        return ImportResponse(imported, updated, rawSources.size - unique.size, errors)
    }

    fun listSubscriptions(enabledOnly: Boolean = false): List<SourceSubscription> = connect { db ->
        val sql = "select * from source_subscription" + (if (enabledOnly) " where enabled=1" else "") + " order by id"
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
        val sql = if (ids.isNullOrEmpty()) "select payload from source order by name collate nocase" else "select payload from source where id in (${ids.joinToString(",") { "?" }}) order by name collate nocase"
        db.prepareStatement(sql).use { statement -> ids?.forEachIndexed { index, id -> statement.setString(index + 1, id) }; statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
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
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        if (jar.containsKey(host)) return jar[host]
        return jar.entries.firstOrNull { (k, _) -> host.contains(k) || k.contains(host) || url.contains(k) }?.value
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
        )
    }
    private fun getBookshelf(db: Connection, sourceId: String, bookUrl: String): BookshelfItem? = db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at,coalesce(c.cached_chapters,0),coalesce(c.total_chapters,0),coalesce(c.state,'idle'),c.last_error,s.completed,s.alternate_sources from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url left join book_cache_status c on c.source_id=s.source_id and c.book_url=s.book_url where s.source_id=? and s.book_url=?""").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } }
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

