package io.legado.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * 数据层引擎（SQL 方言的选择器）。
 *
 * 三种引擎的差异只允许收敛在两处：
 * 1. **DDL**（建表/建索引）——见 [DatabaseSchema]；
 * 2. **SQL 翻译**（`on conflict` / `collate nocase` / `pragma` / `real`）——见 [SqlTranslator]。
 *
 * 业务代码（`Database.kt` 里 280+ 处 `prepareStatement`）**一行都不用改**：
 * 翻译层包在 `Connection`/`Statement` 上，调用点看到的仍是 SQLite 写法。
 */
enum class DatabaseEngine(val wire: String) {
    SQLITE("sqlite"),
    MYSQL("mysql"),
    POSTGRES("postgres"),
    ;

    val isExternal: Boolean get() = this != SQLITE

    /**
     * JDBC 驱动类名。
     *
     * **必须显式 `Class.forName` 注册**：fatJar 用 `duplicatesStrategy = EXCLUDE` 打三个驱动，
     * 而三者都带 `META-INF/services/java.sql.Driver`，只会活下来一个
     * ⇒ 依赖 ServiceLoader 自动发现的写法在 fatJar 里必然「找不到合适驱动」。
     */
    val driverClass: String
        get() = when (this) {
            SQLITE -> "org.sqlite.JDBC"
            MYSQL -> "com.mysql.cj.jdbc.Driver"
            POSTGRES -> "org.postgresql.Driver"
        }

    companion object {
        /** 归一化：只认 sqlite/mysql/postgres（`postgresql` 归一到 postgres），其余按 sqlite。 */
        fun of(raw: String?): DatabaseEngine = when (raw?.trim()?.lowercase()) {
            "mysql" -> MYSQL
            "postgres", "postgresql" -> POSTGRES
            else -> SQLITE
        }
    }
}

/**
 * 数据层连接规格 —— [Database] 的唯一构造入参。
 *
 * 保留 `Database(path: String)` 这个便捷构造给 30+ 个既有测试用，所以这里是**附加**入口：
 * - [engine] = sqlite ⇒ 用 [path] 打开本地文件（其余字段忽略）；
 * - [engine] = mysql/postgres ⇒ 用 [jdbcUrl] + [user]/[password] 连接，启动时还会
 *   自动建库（若不存在）并自动建表。
 */
data class DatabaseSpec(
    val engine: DatabaseEngine = DatabaseEngine.SQLITE,
    /** SQLite 数据文件路径（engine = sqlite 时生效）。 */
    val path: String = "",
    /** 外部库 JDBC 连接串（engine ≠ sqlite 时生效）。 */
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
        check(isExternal) { "openExternalConnection 只用于 mysql/postgres" }
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
     * 真实踩过的坑（两家都不一样）：
     * - **MySQL**：先连到**不带库名**的 `jdbc:mysql://host:port/`，再 `create database if not exists`；
     * - **PostgreSQL**：`create database` **不支持 `if not exists`**（没有这个语法），只能连到
     *   `postgres` 库执行，并吞掉「已存在」（SQLState `42P04`）。
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
            DatabaseEngine.MYSQL -> parts.serverUrl(null)
            DatabaseEngine.POSTGRES -> parts.serverUrl("postgres")
            else -> return
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
                    when (engine) {
                        DatabaseEngine.MYSQL ->
                            // utf8mb4 + general_ci：`collate nocase` 的大小写不敏感语义依赖它
                            // （MySQL 的默认排序规则就是 ci，所以 `collate nocase` 翻译成「删掉」即可）。
                            statement.executeUpdate(
                                "create database if not exists `$name` character set utf8mb4 collate utf8mb4_general_ci",
                            )
                        DatabaseEngine.POSTGRES ->
                            try {
                                statement.executeUpdate("create database \"$name\"")
                            } catch (dup: SQLException) {
                                if (!isDuplicateDatabase(dup)) throw dup
                            }
                        else -> Unit
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
        log("[db] 已自动创建数据库：$name（${engine.wire}，utf8mb4）")
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
 * `jdbc:mysql://host:3306/legado?useUnicode=true` ⇒
 * [scheme] = `jdbc:mysql://`、[authority] = `host:3306`、[database] = `legado`、
 * [query] = `?useUnicode=true`。
 */
internal data class ExternalUrl(
    val scheme: String,
    val authority: String,
    val database: String,
    val query: String,
) {
    /** 服务器级连接串：[database] 为 null 时表示「不带库名」（MySQL 建库前的形态）。 */
    fun serverUrl(database: String?): String =
        if (database == null) "$scheme$authority/$query" else "$scheme$authority/$database$query"

    companion object {
        fun parse(url: String): ExternalUrl {
            val mark = url.indexOf("://")
            require(mark > 0) { "非法的 JDBC 连接串：$url" }
            val scheme = url.substring(0, mark + 3)
            val rest = url.substring(mark + 3)
            val slash = rest.indexOf('/')
            val head = if (slash < 0) rest else rest.substring(0, slash)
            val tail = if (slash < 0) "" else rest.substring(slash + 1)
            // `jdbc:mysql://host:3306?x=1` 这种没有库名、只带参数的形态：authority 到 '?' 为止
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
