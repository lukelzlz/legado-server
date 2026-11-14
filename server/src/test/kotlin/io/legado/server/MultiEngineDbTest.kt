package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多引擎（MySQL / PostgreSQL）数据层的**纯单元**约定。
 *
 * 这里锁的是「翻译层与 DDL 生成」的正确性——它们都是纯字符串/纯结构逻辑，
 * 不需要真库就能测。真库联调（自动建库 + 22 张表 + 写入读回）见
 * `MySQL 真机联调` 那一条（`LEGADO_TEST_MYSQL_URL` 未设置时自动跳过，不污染 CI）。
 */
class MultiEngineDbTest {

    private fun mysql(sql: String): String =
        requireNotNull(SqlTranslator.translate(DatabaseEngine.MYSQL, sql)) { "mysql 不应跳过：$sql" }

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
    fun `mysql：on conflict do update 改成 on duplicate key update 加行别名`() {
        val sql = mysql("insert into source(id,name) values(?,?) on conflict(id) do update set name=excluded.name")
        assertEquals("insert into source(id,name) values(?,?) as new on duplicate key update name=new.name", sql)
    }

    @Test
    fun `mysql：do update 里的旧值引用必须保留表名前缀（行别名下裸列名会歧义）`() {
        val sql = mysql(
            "insert into cover_cache(cache_key,content_type,file_path) values(?,?,?) " +
                "on conflict(cache_key) do update set content_type=excluded.content_type, " +
                "file_path=coalesce(nullif(excluded.file_path,''), cover_cache.file_path)",
        )
        assertEquals(
            "insert into cover_cache(cache_key,content_type,file_path) values(?,?,?) as new " +
                "on duplicate key update content_type=new.content_type, " +
                "file_path=coalesce(nullif(new.file_path,''), cover_cache.file_path)",
            sql,
        )
        // 裸列名的旧值引用要自动补上表名前缀（MySQL 8.0.46 实测：不补会报
        // `Column 'x' in field list is ambiguous`）
        val bare = mysql(
            "insert into book_cache_status(source_id,book_url,state,last_error) values(?,?,?,?) " +
                "on conflict(source_id,book_url) do update set state=excluded.state, " +
                "last_error=coalesce(excluded.last_error, last_error)",
        )
        assertTrue(bare, bare.contains("last_error=coalesce(new.last_error, book_cache_status.last_error)"))
    }

    @Test
    fun `mysql：on conflict do nothing 改成 insert ignore into`() {
        val sql = mysql("insert into illustration_cache(url_hash) values(?) on conflict(url_hash) do nothing")
        assertEquals("insert ignore into illustration_cache(url_hash) values(?)", sql)
    }

    @Test
    fun `postgres 保留 excluded 与 on conflict 原样（PG 原生支持）`() {
        val sql = "insert into source(id,name) values(?,?) on conflict(id) do update set name=excluded.name"
        assertEquals(sql, postgres(sql))
    }

    // --------------------------------------------------------- collate nocase

    @Test
    fun `mysql：collate nocase 直接删除（默认排序规则就是大小写不敏感）`() {
        assertEquals("select id from source where name = ?", mysql("select id from source where name = ? collate nocase"))
        assertEquals("select id from source order by name", mysql("select id from source order by name collate nocase"))
        assertEquals(
            "select 1 from book_group where name=?",
            mysql("select 1 from book_group where name=? collate nocase"),
        )
    }

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
        val mysqlSql = mysql("pragma table_info(book_shelf)")
        assertTrue(mysqlSql, mysqlSql.contains("information_schema.columns"))
        assertTrue(mysqlSql, mysqlSql.contains("column_name as name"))
        assertTrue(mysqlSql, mysqlSql.contains("table_name = 'book_shelf'"))

        val pgSql = postgres("pragma table_info(book_shelf)")
        assertTrue(pgSql, pgSql.contains("information_schema.columns"))
        assertTrue(pgSql, pgSql.contains("current_schema()"))
    }

    @Test
    fun `sqlite 专属 pragma 在外部库上跳过执行（返回 null）`() {
        assertNull(SqlTranslator.translate(DatabaseEngine.MYSQL, "pragma journal_mode = wal"))
        assertNull(SqlTranslator.translate(DatabaseEngine.POSTGRES, "pragma journal_mode = wal"))
    }

    // ------------------------------------------------------------ 其它方言点

    @Test
    fun `json_extract 翻译`() {
        val mysqlSql = mysql("select id from source where json_extract(payload, '$.exploreUrl') is not null")
        assertTrue(mysqlSql, mysqlSql.contains("nullif(json_unquote(json_extract(payload, '$.exploreUrl')), 'null')"))

        val pgSql = postgres("select id from source where json_extract(payload, '$.exploreUrl') is not null")
        assertTrue(pgSql, pgSql.contains("#>> '{exploreUrl}'"))
    }

    @Test
    fun `real 列类型按方言映射（alter 迁移里真的会跑到）`() {
        val alter = "alter table reading_progress add column scroll_position real not null default 0"
        assertTrue(mysql(alter), mysql(alter).contains("scroll_position double not null default 0"))
        assertTrue(postgres(alter), postgres(alter).contains("scroll_position double precision not null default 0"))
    }

    @Test
    fun `mysql 的 text 列不能带默认值：alter 时改 varchar`() {
        val alter = "alter table manga_image_cache add column file_path text not null default ''"
        assertTrue(mysql(alter), mysql(alter).contains("file_path varchar(512) not null default ''"))
        // 没有默认值的 text 可以是 longtext（正文载荷）
        val alter2 = "alter table book_shelf add column alternate_sources text"
        assertTrue(mysql(alter2), mysql(alter2).contains("alternate_sources longtext"))
        // PG 的 text 可以直接带默认值
        assertTrue(postgres(alter), postgres(alter).contains("file_path text not null default ''"))
    }

    @Test
    fun `mysql 保留字 key：app_setting 相关语句才加反引号`() {
        val insert = mysql(
            "insert into app_setting(key,value,updated_at) values(?,?,?) " +
                "on conflict(key) do update set value=excluded.value,updated_at=excluded.updated_at",
        )
        assertEquals(
            "insert into app_setting(`key`,value,updated_at) values(?,?,?) as new " +
                "on duplicate key update value=new.value,updated_at=new.updated_at",
            insert,
        )
        assertEquals("select value from app_setting where `key`=?", mysql("select value from app_setting where key=?"))
        // 其它表的 `key` 字段（目前没有）不该被改动；`primary key` 更不能被套反引号
        assertEquals(
            "create table if not exists t (id bigint primary key)",
            mysql("create table if not exists t (id bigint primary key)"),
        )
    }

    // -------------------------------------------------------------------- DDL

    private fun tables(engine: DatabaseEngine) =
        DatabaseSchema.statements(engine).filter { it.startsWith("create table", ignoreCase = true) }

    private fun indexes(engine: DatabaseEngine) =
        DatabaseSchema.statements(engine).filter { it.startsWith("create index", ignoreCase = true) }

    @Test
    fun `DDL：22 张表都生成出来，且三家表名一致`() {
        val expected = listOf(
            "app_user", "session", "source", "reading_progress", "cover_cache", "app_setting", "book_group",
            "bookmark", "book_cover", "book_shelf", "book_content_cache", "book_cache_status",
            "source_subscription", "rss_source", "book_toc_cache", "source_login_state", "replace_rule",
            "http_tts", "manga_chapter_cache", "manga_image_cache", "audio_cache", "illustration_cache",
        )
        assertEquals(22, expected.size)
        listOf(DatabaseEngine.MYSQL, DatabaseEngine.POSTGRES).forEach { engine ->
            val names = tables(engine).map { statement ->
                Regex("""(?is)^create\s+table\s+if\s+not\s+exists\s+([\w`"]+)""").find(statement)!!.groupValues[1].trim('`', '"')
            }
            assertEquals("$engine 表数量", 22, names.size)
            assertEquals("$engine 表集合", expected.toSet(), names.toSet())
            assertTrue("$engine 索引数量异常：${indexes(engine).size}", indexes(engine).size >= 20)
        }
    }

    @Test
    fun `DDL：MySQL 的 text 不能有默认值、索引列必须是 varchar、自增改 auto_increment`() {
        val statements = DatabaseSchema.statements(DatabaseEngine.MYSQL)
        val text = statements.joinToString("\n")
        assertFalse("MySQL 不允许 text/longtext 带默认值：$text", Regex("""(?i)\b(text|longtext|mediumtext)\b[^,)]*default""").containsMatchIn(text))
        assertFalse("MySQL 用 auto_increment", text.contains("autoincrement", ignoreCase = true))
        assertFalse(text.contains("collate nocase", ignoreCase = true))
        // 索引列一律 varchar(255)：utf8mb4 下 255*4=1020 字节，三列联合主键 3060 ≤ 3072
        assertTrue(text, text.contains("create table if not exists book_content_cache (\nsource_id varchar(255) not null"))
        assertTrue(text, text.contains("content longtext not null"))
        assertTrue(text, text.contains("create table if not exists book_group (\nid bigint primary key auto_increment"))
        // 每个表都要显式 utf8mb4（collate nocase 翻译成「删掉」的前提就是它）
        tables(DatabaseEngine.MYSQL).forEach { statement ->
            assertTrue(statement, statement.contains("charset=utf8mb4 collate=utf8mb4_general_ci"))
        }
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
        val mysqlUrl = ExternalUrl.parse(
            "jdbc:mysql://192.168.1.68:3306/legado?useUnicode=true&characterEncoding=utf8&useSSL=false",
        )
        assertEquals("legado", mysqlUrl.database)
        assertEquals(
            "jdbc:mysql://192.168.1.68:3306/?useUnicode=true&characterEncoding=utf8&useSSL=false",
            mysqlUrl.serverUrl(null),
        )

        val pgUrl = ExternalUrl.parse("jdbc:postgresql://192.168.1.68:5432/legado")
        assertEquals("legado", pgUrl.database)
        assertEquals("jdbc:postgresql://192.168.1.68:5432/postgres", pgUrl.serverUrl("postgres"))

        // 没有库名、只有参数的形态
        val bare = ExternalUrl.parse("jdbc:mysql://h:3306?x=1")
        assertEquals("", bare.database)
        assertTrue(bare.serverUrl(null).startsWith("jdbc:mysql://h:3306/"))
    }

    @Test
    fun `ServerConfig 能推导出三种引擎的连接规格`() {
        val sqlite = ServerConfig.fromSources(mapOf("data.database" to "/tmp/x.sqlite"), emptyMap())
        assertEquals(DatabaseEngine.SQLITE, sqlite.databaseSpec().engine)
        assertFalse(sqlite.databaseSpec().isExternal)

        val mysql = ServerConfig.fromSources(
            mapOf(
                "database.type" to "mysql",
                "database.mysql.host" to "192.168.1.68",
                "database.mysql.database" to "legado",
                "database.mysql.user" to "root",
                "database.mysql.password" to "pw",
            ),
            emptyMap(),
        )
        val spec = mysql.databaseSpec()
        assertEquals(DatabaseEngine.MYSQL, spec.engine)
        assertEquals("legado", spec.databaseName)
        assertEquals("root", spec.user)
        assertEquals("pw", spec.password)
    }

    // ------------------------------------------------------- 真机联调（可选）

    /**
     * MySQL 真机联调：**只有显式设置 `LEGADO_TEST_MYSQL_URL` 才会跑**。
     *
     * 覆盖「自动建库 + 22 张表 + 一次真实 upsert + 读回」——
     * 这些是纯单测覆盖不到、只能靠真库证明的部分（CI 没有 MySQL，故默认跳过）。
     */
    @Test
    fun `MySQL 真机：自动建库建表后能正常读写`() {
        val url = System.getenv("LEGADO_TEST_MYSQL_URL")?.takeIf { it.isNotBlank() } ?: return
        val user = System.getenv("LEGADO_TEST_MYSQL_USER") ?: "root"
        val password = System.getenv("LEGADO_TEST_MYSQL_PASSWORD") ?: ""
        val spec = DatabaseSpec(
            engine = DatabaseEngine.MYSQL,
            jdbcUrl = url,
            user = user,
            password = password,
        )
        val database = Database(spec)
        try {
            database.initialize("mysql-smoke-pass-123")
            assertTrue(database.hasPassword())
            assertTrue(database.verifyPassword("mysql-smoke-pass-123"))

            // 写 → 读（走 on conflict / collate nocase / json_extract 这些真实翻译点）
            database.setSetting("cache_layout", "v3")
            assertEquals("v3", database.getSetting("cache_layout"))
            database.setSetting("cache_layout", "v4")
            assertEquals("v4", database.getSetting("cache_layout"))

            val name = "mysql-test-${System.currentTimeMillis()}"
            val group = database.createBookGroup(name)
            assertEquals(
                "group lookup 必须大小写不敏感（collate nocase 的语义）",
                group.id,
                database.getBookGroup(name.uppercase())?.id,
            )
        } finally {
            database.close()
        }
        // 幂等重启：同一份库再初始化一次不能报「表/索引已存在」
        val again = Database(spec)
        try {
            again.initialize(null)
            assertEquals("v4", again.getSetting("cache_layout"))
        } finally {
            again.close()
        }
    }
}
