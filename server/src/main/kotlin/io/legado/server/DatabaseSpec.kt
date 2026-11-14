package io.legado.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * `database.type: mysql` 已移除后的统一提示。
 *
 * 用户拍板「彻底移除 MySQL，只保留 SQLite + PostgreSQL」。旧配置里若还写着 `mysql`，
 * 必须**明确报错**、绝不静默回落 sqlite —— 静默回落会让用户以为自己在写远端的 MySQL，
 * 实际却在本地建了一个新的空 sqlite，数据「凭空消失」且毫无提示。
 */
internal const val MYSQL_REMOVED_MESSAGE =
    "mysql 支持已移除，请改用 sqlite 或 postgres（database.type: mysql 不再支持）"

/** 配置里写了已移除的数据库类型（目前只会是 `mysql`）。 */
class UnsupportedDatabaseTypeException(message: String) : IllegalArgumentException(message)

/**
 * 数据层引擎（SQL 方言的选择器）。
 *
 * 两种引擎的差异只允许收敛在两处：
 * 1. **DDL**（建表/建索引）——见 [DatabaseSchema]；
 * 2. **SQL 翻译**（`on conflict` / `collate nocase` / `pragma` / `real`）——见 [SqlTranslator]。
 *
 * 业务代码（`Database.kt` 里 280+ 处 `prepareStatement`）**一行都不用改**：
 * 翻译层包在 `Connection`/`Statement` 上，调用点看到的仍是 SQLite 写法。
 */
enum class DatabaseEngine(val wire: String) {
    SQLITE("sqlite"),
    POSTGRES("postgres"),
    ;

    val isExternal: Boolean get() = this != SQLITE

    /**
     * JDBC 驱动类名。
     *
     * **必须显式 `Class.forName` 注册**：fatJar 用 `duplicatesStrategy = EXCLUDE` 打包，
     * 而 sqlite/postgres 两个驱动都带 `META-INF/services/java.sql.Driver`，只会活下来一个
     * ⇒ 依赖 ServiceLoader 自动发现的写法在 fatJar 里必然「找不到合适驱动」。
     */
    val driverClass: String
        get() = when (this) {
            SQLITE -> "org.sqlite.JDBC"
            POSTGRES -> "org.postgresql.Driver"
        }

    companion object {
        /** 归一化：只认 sqlite/postgres（`postgresql` 归一到 postgres），其余按 sqlite。 */
        fun of(raw: String?): DatabaseEngine = when (raw?.trim()?.lowercase()) {
            "postgres", "postgresql" -> POSTGRES
            "mysql" -> throw UnsupportedDatabaseTypeException(MYSQL_REMOVED_MESSAGE)
            else -> SQLITE
        }
    }
}

/**
 * 数据层连接规格 —— [Database] 的唯一构造入参。
 *
 * 保留 `Database(path: String)` 这个便捷构造给 30+ 个既有测试用，所以这里是**附加**入口：
 * - [engine] = sqlite ⇒ 用 [path] 打开本地文件（其余字段忽略）；
 * - [engine] = postgres ⇒ 用 [jdbcUrl] + [user]/[password] 连接，启动时还会
 *   自动建库（若不存在）并自动建表。
 */
data class DatabaseSpec(
    val engine: DatabaseEngine = DatabaseEngine.SQLITE,
    /** SQLite 数据文件路径（engine = sqlite 时生效）。 */
    val path: String = "",
    /** 外部库 JDBC 连接串（engine = postgres 时生效）。 */
    val jdbcUrl: String? = null,
    val user: String? = null,
    val password: String? = null,
) {
    val isExternal: Boolean get() = engine.isExternal

    /** 目标库名（从 JDBC 串的最后一段路径取）。 */
    val databaseName: String get() = parseExternalUrl()?.database.orEmpty()

    private fun parseExternalUrl(): ExternalUrl? = jdbcUrl?.let { runCatching { ExternalUrl.parse(it) }.getOrNull() }

    /** 打开一条**真实**外部库连接（sqlite 由 [Database] 自己用 SQLiteConfig 开）。 */
    fun openExternalConnection(): Connection {
        check(isExternal) { "openExternalConnection 只用于 postgres" }
        val url = jdbcUrl?.takeIf { it.isNotBlank() } ?: error("database.type=${engine.wire} 但缺少 JDBC 连接串")
        ensureDriver()
        val props = Properties().apply {
            user?.let { put("user", it) }
            password?.let { put("password", it) }
        }
        return DriverManager.getConnection(url, props)
    }

    private fun ensureDriver() {
        runCatching { Class.forName(engine.driverClass) }
            .onFailure { error ->
                throw IllegalStateException(
                    "找不到 ${engine.wire} 的 JDBC 驱动（${engine.driverClass}）：${error.message}",
                    error,
                )
            }
    }

    /**
     * 自动建库（若不存在）。
     *
     * **PostgreSQL**：`create database` **不支持 `if not exists`**（没有这个语法），只能连到
     * `postgres` 库执行，并吞掉「已存在」（SQLState `42P04`）。
     *
     * 顺序是「**先试直连目标库**，连不上才建」：这样即使用户没有建库权限、但库已经由 DBA 建好，
     * 也不会因为多跑一次 `create database` 而启动失败。
     */
    fun ensureDatabase(log: (String) -> Unit = { println(it) }) {
        if (!isExternal) return
        val parts = parseExternalUrl() ?: throw IllegalStateException("非法的 JDBC 连接串：$jdbcUrl")
        val name = parts.database
        if (name.isBlank()) return
        if (canConnectTarget()) {
            log("[db] 已连接目标库：$name（${engine.wire}）")
            return
        }
        // 库名会拼进 DDL，必须白名单校验（JDBC 串来自用户配置文件）
        require(name.matches(Regex("[A-Za-z0-9_\\-$]+"))) { "非法的数据库名：$name" }
        val bootstrapUrl = when (engine) {
            DatabaseEngine.POSTGRES -> parts.serverUrl("postgres")
            DatabaseEngine.SQLITE -> return
        }
        log("[db] 目标库 $name 不存在，尝试自动建库：$bootstrapUrl")
        ensureDriver()
        val props = Properties().apply {
            user?.let { put("user", it) }
            password?.let { put("password", it) }
        }
        try {
            DriverManager.getConnection(bootstrapUrl, props).use { connection ->
                connection.createStatement().use { statement ->
                    try {
                        statement.executeUpdate("create database \"$name\"")
                    } catch (dup: SQLException) {
                        if (!isDuplicateDatabase(dup)) throw dup
                    }
                }
            }
        } catch (error: SQLException) {
            log("[db] 自动建库失败（库=$name）：${error.message}")
            throw IllegalStateException(
                "无法自动创建数据库 `$name`（${engine.wire}）：${error.message}。" +
                    "请确认账号有建库权限，或手工建库后重启。",
                error,
            )
        }
        log("[db] 已自动创建数据库：$name（${engine.wire}）")
    }

    private fun canConnectTarget(): Boolean = runCatching {
        openExternalConnection().use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("select 1").use { it.next() } }
        }
        true
    }.getOrDefault(false)

    private fun isDuplicateDatabase(error: SQLException): Boolean =
        error.sqlState == "42P04" || (error.message?.contains("already exists", ignoreCase = true) == true)

    companion object {
        fun sqlite(path: String): DatabaseSpec = DatabaseSpec(engine = DatabaseEngine.SQLITE, path = path)
    }
}

/**
 * 外部库 JDBC 连接串的拆解结果。
 *
 * `jdbc:postgresql://host:5432/legado?ssl=false` ⇒
 * [scheme] = `jdbc:postgresql://`、[authority] = `host:5432`、[database] = `legado`、
 * [query] = `?ssl=false`。
 */
internal data class ExternalUrl(
    val scheme: String,
    val authority: String,
    val database: String,
    val query: String,
) {
    /** 服务器级连接串（PG 建库前连 `postgres` 库）。 */
    fun serverUrl(database: String): String = "$scheme$authority/$database$query"

    companion object {
        fun parse(url: String): ExternalUrl {
            val mark = url.indexOf("://")
            require(mark > 0) { "非法的 JDBC 连接串：$url" }
            val scheme = url.substring(0, mark + 3)
            val rest = url.substring(mark + 3)
            val slash = rest.indexOf('/')
            val head = if (slash < 0) rest else rest.substring(0, slash)
            val tail = if (slash < 0) "" else rest.substring(slash + 1)
            // `jdbc:postgresql://host:5432?x=1` 这种没有库名、只带参数的形态：authority 到 '?' 为止
            val qInHead = head.indexOf('?')
            val authority = if (qInHead < 0) head else head.substring(0, qInHead)
            val headQuery = if (qInHead < 0) "" else head.substring(qInHead)
            val q = tail.indexOf('?')
            val database = (if (q < 0) tail else tail.substring(0, q)).trim()
            val query = headQuery + if (q < 0) "" else tail.substring(q)
            return ExternalUrl(scheme, authority, database, query)
        }
    }
}
