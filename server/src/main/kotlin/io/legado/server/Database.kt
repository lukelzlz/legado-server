package io.legado.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Database(private val path: String) {
    private val random = SecureRandom()
    private val writeLock = ReentrantLock()

    init { Files.createDirectories(Path.of(path).toAbsolutePath().parent) }

    private fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use {
                it.execute("pragma busy_timeout = 5000")
                it.execute("pragma foreign_keys = on")
            }
            block(db)
        }

    private fun <T> write(block: (Connection) -> T): T = writeLock.withLock { connect(block) }

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
                  last_read_at integer not null, primary key (source_id, book_url)
                );
                create index if not exists book_shelf_last_read_idx on book_shelf(last_read_at desc);
                create table if not exists source_subscription (
                  id integer primary key autoincrement, url text not null unique,
                  enabled integer not null, created_at integer not null, updated_at integer not null,
                  last_success_at integer, last_attempt_at integer, last_error text,
                  last_imported integer not null default 0, content_hash text
                );
                create index if not exists source_subscription_enabled_idx on source_subscription(enabled);
            """.trimIndent())
        }
        migrateReadingProgress(db)
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

    fun deleteSession(session: UserSession) = write { db -> db.prepareStatement("delete from session where id = ?").use { it.setString(1, session.id); it.executeUpdate() } }

    fun listSources(query: String?): List<SourceSummary> = connect { db ->
        val sql = if (query.isNullOrBlank()) "select * from source order by name collate nocase" else "select * from source where name like ? or source_url like ? order by name collate nocase"
        db.prepareStatement(sql).use { statement ->
            if (!query.isNullOrBlank()) { statement.setString(1, "%$query%"); statement.setString(2, "%$query%") }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSummary()) } }
        }
    }

    fun getSource(id: String): SourceRecord? = connect { db -> db.prepareStatement("select id, payload, version, updated_at from source where id = ?").use {
        it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) SourceRecord(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) else null }
    } }

    fun saveSource(parsed: ParsedSource, expectedVersion: Long?): SourceRecord = write { db ->
        db.autoCommit = false
        try {
            val current = db.prepareStatement("select version from source where id = ?").use { it.setString(1, parsed.id); it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null } }
            if (expectedVersion != null && current != expectedVersion) throw VersionConflict()
            val version = (current ?: 0) + 1; val now = System.currentTimeMillis()
            db.prepareStatement("""insert into source(id,name,source_url,source_group,enabled,is_js,payload,version,updated_at)
                values(?,?,?,?,?,?,?,?,?) on conflict(id) do update set name=excluded.name,source_url=excluded.source_url,source_group=excluded.source_group,enabled=excluded.enabled,is_js=excluded.is_js,payload=excluded.payload,version=excluded.version,updated_at=excluded.updated_at""").use {
                it.setString(1, parsed.id); it.setString(2, parsed.name); it.setString(3, parsed.url); it.setString(4, parsed.group); it.setInt(5, if (parsed.enabled) 1 else 0); it.setInt(6, if (parsed.isJs) 1 else 0); it.setString(7, parsed.json); it.setLong(8, version); it.setLong(9, now); it.executeUpdate()
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
            db.prepareStatement("""insert into book_shelf(source_id,book_url,name,author,toc_url,cover_url,cover_key,last_read_at) values(?,?,?,?,?,?,?,?)
                on conflict(source_id,book_url) do update set name=excluded.name,author=excluded.author,toc_url=excluded.toc_url,cover_url=excluded.cover_url,cover_key=coalesce(excluded.cover_key,book_shelf.cover_key),last_read_at=excluded.last_read_at""").use {
                it.setString(1, request.sourceId); it.setString(2, request.bookUrl); it.setString(3, request.name); it.setString(4, request.author); it.setString(5, request.tocUrl); it.setString(6, request.coverUrl); it.setString(7, cover?.key); it.setLong(8, now); it.executeUpdate()
            }
            db.commit(); getBookshelf(db, request.sourceId, request.bookUrl)!!
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    fun listBookshelf(): List<BookshelfItem> = connect { db -> db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url order by s.last_read_at desc""").use { query -> query.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toShelf()) } } } }
    fun removeBookshelf(sourceId: String, bookUrl: String): String? = write { db ->
        db.autoCommit = false
        try {
            val key = db.prepareStatement("select cover_key from book_shelf where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
            db.prepareStatement("delete from book_shelf where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            db.prepareStatement("delete from reading_progress where source_id=? and book_url=?").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeUpdate() }
            val orphan = key?.takeIf { value -> db.prepareStatement("select 1 from book_shelf where cover_key=?").use { it.setString(1, value); !it.executeQuery().next() } }
            orphan?.let { value -> db.prepareStatement("delete from cover_cache where cache_key=?").use { it.setString(1, value); it.executeUpdate() } }
            db.commit(); orphan
        } catch (error: Throwable) { db.rollback(); throw error } finally { db.autoCommit = true }
    }
    fun coverContentType(key: String): String? = connect { db -> db.prepareStatement("select content_type from cover_cache where cache_key=?").use { it.setString(1, key); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } } }

    fun importSources(rawSources: List<String>): ImportResponse {
        val errors = mutableListOf<String>()
        val unique = linkedMapOf<String, ParsedSource>()
        rawSources.forEachIndexed { index, raw ->
            try {
                val parsed = SourceCodec.parse(raw)
                unique[parsed.url] = parsed
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
                    db.prepareStatement("""insert into source(id,name,source_url,source_group,enabled,is_js,payload,version,updated_at)
                        values(?,?,?,?,?,?,?,?,?) on conflict(id) do update set name=excluded.name,source_url=excluded.source_url,source_group=excluded.source_group,enabled=excluded.enabled,is_js=excluded.is_js,payload=excluded.payload,version=excluded.version,updated_at=excluded.updated_at""").use { save ->
                        unique.values.forEach { parsed ->
                            current.setString(1, parsed.id)
                            val version = current.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                            if (version == null) imported++ else updated++
                            save.setString(1, parsed.id); save.setString(2, parsed.name); save.setString(3, parsed.url); save.setString(4, parsed.group)
                            save.setInt(5, if (parsed.enabled) 1 else 0); save.setInt(6, if (parsed.isJs) 1 else 0); save.setString(7, parsed.json)
                            save.setLong(8, (version ?: 0) + 1); save.setLong(9, System.currentTimeMillis()); save.addBatch()
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

    private fun java.sql.ResultSet.toSummary() = SourceSummary(getString("id"), getString("name"), getString("source_url"), getString("source_group"), getInt("enabled") == 1, getInt("is_js") == 1, getLong("updated_at"), getLong("version"))
    private fun java.sql.ResultSet.toSubscription() = SourceSubscription(getLong("id"), getString("url"), getInt("enabled") == 1, getLong("created_at"), getLong("updated_at"), getLong("last_success_at").takeIf { !wasNull() }, getLong("last_attempt_at").takeIf { !wasNull() }, getString("last_error"), getInt("last_imported"), getString("content_hash"))
    private fun java.sql.ResultSet.toShelf() = BookshelfItem(getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), getObject(7) as? Int, getObject(8) as? Double, getLong(9))
    private fun getBookshelf(db: Connection, sourceId: String, bookUrl: String): BookshelfItem? = db.prepareStatement("""select s.source_id,s.book_url,s.name,s.author,s.toc_url,s.cover_key,p.chapter_index,p.scroll_position,s.last_read_at from book_shelf s left join reading_progress p on p.source_id=s.source_id and p.book_url=s.book_url where s.source_id=? and s.book_url=?""").use { it.setString(1, sourceId); it.setString(2, bookUrl); it.executeQuery().use { rs -> if (rs.next()) rs.toShelf() else null } }
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
