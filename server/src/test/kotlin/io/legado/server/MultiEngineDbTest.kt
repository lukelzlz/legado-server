package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双引擎（SQLite / PostgreSQL）数据层的**纯单元**约定。
 *
 * 这里锁的是「翻译层与 DDL 生成」的正确性——它们都是纯字符串/纯结构逻辑，
 * 不需要真库就能测。真库联调（自动建库 + 22 张表 + 冒烟）见
 * `docs/sessions/SESSION-059-multi-database-engine.md` 的 PG 证据。
 */
class MultiEngineDbTest {

    private fun postgres(sql: String): String =
        requireNotNull(SqlTranslator.translate(DatabaseEngine.POSTGRES, sql)) { "postgres 不应跳过：$sql" }

    // ------------------------------------------------------------------ sqlite

    @Test
    fun `sqlite 下翻译层是恒等变换（零回归的前提）`() {
        val samples = listOf(
            "insert into source(id) values(?) on conflict(id) do update set name=excluded.name",
            "select id from source order by name collate nocase",
            "pragma table_info(book_shelf)",
            "alter table reading_progress add column scroll_position real not null default 0",
        )
        samples.forEach { sql ->
            assertEquals(sql, SqlTranslator.translate(DatabaseEngine.SQLITE, sql))
        }
    }

    // ------------------------------------------------------------- upsert 翻译

    @Test
    fun `postgres 保留 excluded 与 on conflict 原样（PG 原生支持）`() {
        val sql = "insert into source(id,name) values(?,?) on conflict(id) do update set name=excluded.name"
        assertEquals(sql, postgres(sql))
        assertEquals(
            "insert into illustration_cache(url_hash) values(?) on conflict(url_hash) do nothing",
            postgres("insert into illustration_cache(url_hash) values(?) on conflict(url_hash) do nothing"),
        )
    }

    // --------------------------------------------------------- collate nocase

    @Test
    fun `postgres：比较两侧都包 lower`() {
        assertEquals(
            "select id from source where lower(name) = lower(?)",
            postgres("select id from source where name = ? collate nocase"),
        )
        assertEquals(
            "select * from book_shelf s left join book_group g on lower(s.group_name) = lower(g.name)",
            postgres("select * from book_shelf s left join book_group g on s.group_name = g.name collate nocase"),
        )
        assertEquals(
            "select id from source where lower(name) like lower(?)",
            postgres("select id from source where name like ? collate nocase"),
        )
    }

    @Test
    fun `postgres：order by 包 lower，group by 只删不加`() {
        assertEquals(
            "select id from source order by lower(name)",
            postgres("select id from source order by name collate nocase"),
        )
        val grouped = postgres(
            "select trim(source_group) as group_name, count(*) from source " +
                "group by trim(source_group) collate nocase order by group_name collate nocase",
        )
        // group by 的表达式必须与 select 列表里的完全一致，否则 PG 直接报
        // 「column must appear in the GROUP BY clause」
        assertEquals(
            "select trim(source_group) as group_name, count(*) from source " +
                "group by trim(source_group) order by lower(group_name)",
            grouped,
        )
    }

    // ------------------------------------------------------------------ pragma

    @Test
    fun `pragma table_info 翻译成 information_schema 且列名仍是 name`() {
        val pgSql = postgres("pragma table_info(book_shelf)")
        assertTrue(pgSql, pgSql.contains("information_schema.columns"))
        assertTrue(pgSql, pgSql.contains("current_schema()"))
        assertTrue(pgSql, pgSql.contains("column_name as name"))
        assertTrue(pgSql, pgSql.contains("table_name = 'book_shelf'"))
    }

    @Test
    fun `sqlite 专属 pragma 在外部库上跳过执行（返回 null）`() {
        assertNull(SqlTranslator.translate(DatabaseEngine.POSTGRES, "pragma journal_mode = wal"))
    }

    // ------------------------------------------------------------ 其它方言点

    @Test
    fun `json_extract 翻译`() {
        val pgSql = postgres("select id from source where json_extract(payload, '$.exploreUrl') is not null")
        assertTrue(pgSql, pgSql.contains("#>> '{exploreUrl}'"))
    }

    @Test
    fun `real 列类型按方言映射（alter 迁移里真的会跑到）`() {
        val alter = "alter table reading_progress add column scroll_position real not null default 0"
        assertTrue(postgres(alter), postgres(alter).contains("scroll_position double precision not null default 0"))
        // PG 的 text 可以直接带默认值，不需要任何改写
        val text = "alter table manga_image_cache add column file_path text not null default ''"
        assertTrue(postgres(text), postgres(text).contains("file_path text not null default ''"))
    }

    // -------------------------------------------------------------------- DDL

    private fun tables(engine: DatabaseEngine) =
        DatabaseSchema.statements(engine).filter { it.startsWith("create table", ignoreCase = true) }

    private fun indexes(engine: DatabaseEngine) =
        DatabaseSchema.statements(engine).filter { it.startsWith("create index", ignoreCase = true) }

    @Test
    fun `DDL：23 张表都生成出来，且表名与 SQLite 一致`() {
        val expected = listOf(
            "app_user", "session", "source", "reading_progress", "cover_cache", "app_setting", "book_group",
            "bookmark", "book_cover", "book_shelf", "book_content_cache", "book_cache_status",
            "source_subscription", "rss_source", "book_toc_cache", "book_chapter_index", "source_login_state",
            "replace_rule", "http_tts", "manga_chapter_cache", "manga_image_cache", "audio_cache",
            "illustration_cache",
        )
        assertEquals(23, expected.size)
        val engine = DatabaseEngine.POSTGRES
        val names = tables(engine).map { statement ->
            Regex("""(?is)^create\s+table\s+if\s+not\s+exists\s+([\w`"]+)""").find(statement)!!.groupValues[1].trim('`', '"')
        }
        assertEquals("$engine 表数量", 23, names.size)
        assertEquals("$engine 表集合", expected.toSet(), names.toSet())
        assertTrue("$engine 索引数量异常：${indexes(engine).size}", indexes(engine).size >= 20)
    }

    @Test
    fun `DDL：PG 用 bigserial、bigint、create index if not exists`() {
        val statements = DatabaseSchema.statements(DatabaseEngine.POSTGRES)
        val text = statements.joinToString("\n")
        // 自增主键：SQLite 的 autoincrement ⇒ PG 的 bigserial
        assertTrue(text, text.contains("id bigserial primary key"))
        // 非自增主键仍然只是 bigint（http_tts.id 由业务侧生成）
        assertTrue(text, text.contains("create table if not exists http_tts (\nid bigint primary key"))
        // SQLite 的 integer 是 64 位：毫秒时间戳必须是 bigint
        assertFalse("PG 不能用 32 位 int 存毫秒时间戳", Regex("""(?i)\b\w+ integer\b""").containsMatchIn(text))
        assertFalse("PG 没有 autoincrement", text.contains("autoincrement", ignoreCase = true))
        assertFalse(text.contains("collate nocase", ignoreCase = true))
        // 大小写不敏感索引必须建立在 lower(...) 上，否则查询侧的 lower() 永远用不上索引
        assertTrue(text, text.contains("create index if not exists idx_source_name_nocase on source (lower(name))"))
        indexes(DatabaseEngine.POSTGRES).forEach { statement ->
            assertTrue(statement, statement.startsWith("create index if not exists", ignoreCase = true))
        }
    }

    // --------------------------------------------------------------- 连接串

    @Test
    fun `JDBC 连接串拆解与建库用的服务器串`() {
        val pgUrl = ExternalUrl.parse("jdbc:postgresql://192.168.1.68:5432/legado")
        assertEquals("legado", pgUrl.database)
        assertEquals("jdbc:postgresql://192.168.1.68:5432/postgres", pgUrl.serverUrl("postgres"))

        // 没有库名、只有参数的形态（建库前只连服务器）
        val bare = ExternalUrl.parse("jdbc:postgresql://h:5432?x=1")
        assertEquals("", bare.database)
        assertEquals("jdbc:postgresql://h:5432/postgres?x=1", bare.serverUrl("postgres"))
    }

    @Test
    fun `ServerConfig 能推导出两种引擎的连接规格`() {
        val sqlite = ServerConfig.fromSources(mapOf("data.database" to "/tmp/x.sqlite"), emptyMap())
        assertEquals(DatabaseEngine.SQLITE, sqlite.databaseSpec().engine)
        assertFalse(sqlite.databaseSpec().isExternal)

        val pg = ServerConfig.fromSources(
            mapOf(
                "database.type" to "postgres",
                "database.postgres.host" to "192.168.1.68",
                "database.postgres.database" to "legado",
                "database.postgres.user" to "wfanan",
                "database.postgres.password" to "pw",
            ),
            emptyMap(),
        )
        val spec = pg.databaseSpec()
        assertEquals(DatabaseEngine.POSTGRES, spec.engine)
        assertEquals("legado", spec.databaseName)
        assertEquals("wfanan", spec.user)
        assertEquals("pw", spec.password)
    }

    // ------------------------------------------------------- MySQL 已移除

    @Test
    fun `mysql 已移除：配置写 mysql 必须明确报错，绝不静默回落 sqlite`() {
        val fromConfig = runCatching {
            ServerConfig.fromSources(mapOf("database.type" to "mysql"), emptyMap())
        }.exceptionOrNull()
        assertTrue("必须是明确的配置错误，而不是静默回落：$fromConfig", fromConfig is UnsupportedDatabaseTypeException)
        assertTrue("错误提示要说清改用哪个：${fromConfig?.message}", fromConfig!!.message!!.contains("mysql 支持已移除"))

        val fromEnv = runCatching {
            ServerConfig.fromSources(emptyMap(), mapOf("LEGADO_DB_TYPE" to "MySQL"))
        }.exceptionOrNull()
        assertTrue("环境变量同样要拦：$fromEnv", fromEnv is UnsupportedDatabaseTypeException)

        val fromEngine = runCatching { DatabaseEngine.of("mysql") }.exceptionOrNull()
        assertTrue("引擎解析同样要拦：$fromEngine", fromEngine is UnsupportedDatabaseTypeException)
    }
    // ------------------------------------------- book_group 大小写：以 SQLite 为准
    @Test
    fun `DDL：PG 追加 lower(name) 唯一索引，同时保留列级 unique（on conflict 需要它）`() {
        val statements = DatabaseSchema.statements(DatabaseEngine.POSTGRES)
        assertTrue(
            "PG 必须有 lower(name) 表达式唯一索引（对齐 SQLite 的 unique collate nocase）：$statements",
            statements.any { it.contains("create unique index if not exists book_group_name_ci on book_group (lower(name))") },
        )
        val bookGroup = statements.first { it.startsWith("create table if not exists book_group", ignoreCase = true) }
        assertTrue(
            "列级 unique(name) 必须保留，否则 importBookGroups 的 on conflict(name) 在 PG 上无约束可匹配：$bookGroup",
            bookGroup.contains("unique", ignoreCase = true),
        )
        assertFalse("外部库 DDL 不能残留 collate nocase：$bookGroup", bookGroup.contains("collate nocase", ignoreCase = true))
    }

    @Test
    fun `SQLite：分组名大小写不同视为同一个分组（建第二个要报「已存在」）`() {
        val dir = java.nio.file.Files.createTempDirectory("legado-group-ci")
        val db = Database(dir.resolve("x.sqlite").toString())
        try {
            db.initialize("pw")
            db.createBookGroup("News")
            val error = runCatching { db.createBookGroup("news") }.exceptionOrNull()
            assertTrue("必须是友好的已存在提示，而不是 SQL 异常：$error", error is IllegalArgumentException)
            assertTrue("提示语要带分组名：${error?.message}", error!!.message!!.contains("已存在"))
            assertEquals("只应存在一个分组", 1, db.listBookGroups().size)
        } finally {
            db.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }}
