package io.legado.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `config/config.yaml` 的约定（用户要求：配置改成 jar 同目录下的 config 文件夹里的 yaml，
 * 首次启动先写一份带注释的默认模板）。
 *
 * 这里锁三件用户能感知的事：
 * 1. **首次启动自动生成**默认配置，且**绝不覆盖**用户改过的内容；
 * 2. 配置文件能真正生效（host/port/密码/配额/开关），**环境变量优先**；
 * 3. YAML 写坏了也**不让服务起不来**（回落环境变量/默认值）。
 */
class ConfigFileTest {

    private lateinit var baseDir: Path

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("legado-config")
    }

    @After
    fun tearDown() {
        runCatching { baseDir.toFile().deleteRecursively() }
    }

    private fun writeConfig(text: String) {
        val file = ConfigFile.fileOf(baseDir)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `首次启动生成带注释的默认配置，且再启动不覆盖用户改动`() {
        val (file, generated) = ConfigFile.ensureDefault(baseDir)
        assertTrue("第一次必须生成文件", generated)
        assertTrue(Files.exists(file))
        // 相对 jar 目录：config/config.yaml
        assertEquals(baseDir.resolve("config").resolve("config.yaml"), file)

        val content = Files.readString(file)
        assertTrue("默认配置必须带注释", content.contains("#"))
        assertTrue("必须解释优先级", content.contains("环境变量"))
        assertTrue("必须包含端口项", content.contains("port: 8080"))
        assertTrue("必须包含配置文件用于明文 HTTP 的提示", content.contains("secure_cookies"))
        // MySQL 支持已移除：默认模板里不能再出现 mysql 段（否则用户会被引导去配一个不支持的引擎）
        assertFalse("默认模板不能再出现 mysql", content.contains("mysql", ignoreCase = true))
        assertTrue("默认模板要保留 postgres 段", content.contains("postgres:"))

        // 用户改过之后再次 ensure：内容原封不动
        Files.writeString(file, "server:\n  port: 9999\n")
        val (_, regenerated) = ConfigFile.ensureDefault(baseDir)
        assertFalse("已有文件不能覆盖", regenerated)
        assertTrue(Files.readString(file).contains("9999"))
    }

    @Test
    fun `配置文件生效：host 端口 密码 配额 开关都能读出来`() {
        writeConfig(
            """
            server:
              host: 127.0.0.1
              port: 9000
            data:
              dir: ./cfg-data
            auth:
              password: "secret-123"
              secure_cookies: false
            cache:
              manga:
                max_gb: 2.5
                max_global_fetches: 9
                prefetch: false
              audio:
                max_gb: 3
                max_global_fetches: 2
                gc: false
            cover:
              self_heal: false
            """.trimIndent(),
        )

        val config = readyConfig(baseDir, emptyMap())

        assertEquals("127.0.0.1", config.host)
        assertEquals(9000, config.port)
        assertEquals("secret-123", config.initialAdminPassword)
        assertFalse("明文 HTTP 部署必须能把 Secure Cookie 关掉", config.secureCookies)
        assertEquals((2.5 * 1024 * 1024 * 1024).toLong(), config.imageCacheQuotaBytes)
        assertEquals(9, config.maxGlobalImageFetches)
        assertFalse(config.mangaPrefetchEnabled)
        assertEquals((3.0 * 1024 * 1024 * 1024).toLong(), config.audioCacheQuotaBytes)
        assertEquals(2, config.maxGlobalAudioFetches)
        assertFalse(config.audioGcEnabled)
        assertFalse(config.coverSelfHealEnabled)
        assertTrue("相对 data.dir 要解析成绝对路径", config.databasePath.endsWith("legado.sqlite"))
        assertTrue(config.coverCacheDirectory.isAbsolute)
    }

    @Test
    fun `环境变量优先于配置文件，且空串不算配置`() {
        writeConfig(
            """
            server:
              host: 127.0.0.1
              port: 9000
            auth:
              password: "from-file"
            """.trimIndent(),
        )

        val config = readyConfig(
            baseDir,
            mapOf("LEGADO_PORT" to "8123", "ADMIN_PASSWORD" to "", "LEGADO_HOST" to "0.0.0.0"),
        )

        assertEquals("环境变量必须覆盖文件", 8123, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals("空的环境变量不覆盖文件值", "from-file", config.initialAdminPassword)
    }

    @Test
    fun `YAML 写坏时回落到环境变量和默认值，不抛异常`() {
        writeConfig("server:\n  port: [unclosed\n")

        val config = readyConfig(baseDir, mapOf("LEGADO_PORT" to "7777"))

        assertEquals(7777, config.port)
        assertEquals("0.0.0.0", config.host)
    }

    @Test
    fun `没有配置文件时用内置默认值（端口 8080，Secure Cookie 默认开）`() {
        val config = readyConfig(baseDir, emptyMap())
        assertEquals(8080, config.port)
        assertTrue(config.secureCookies)
        assertNotNull(config.databasePath)
        assertNull("没有 ADMIN_PASSWORD 时不该有初始密码", config.initialAdminPassword)
    }

    @Test
    fun `扁平化规则：嵌套键用点连接，空值不进结果`() {
        writeConfig(
            """
            server:
              port: 8080
            auth:
              password:
            """.trimIndent(),
        )
        val values = ConfigFile.readValues(ConfigFile.fileOf(baseDir))
        assertNotNull(values)
        assertEquals("8080", values!!["server.port"])
        assertFalse("留空的项等于没配", values.containsKey("auth.password"))
    }

    /** load() 返回 LoadResult；这里直接拿「已就绪」的配置。 */
    private fun readyConfig(baseDir: Path, env: Map<String, String>): ServerConfig {
        // 先确保配置文件存在（否则会走「首启只生成配置」分支）
        ConfigFile.ensureDefault(baseDir, emptyMap())
        return when (val loaded = ServerConfig.load(baseDir, env = env, log = {})) {
            is ServerConfig.LoadResult.Ready -> loaded.config
            is ServerConfig.LoadResult.FirstRun -> error("配置文件已存在，不应再走首启分支")
        }
    }

    @Test
    fun `首次启动只生成配置文件、不启动（返回 FirstRun）；第二次启动才 Ready`() {
        val first = ServerConfig.load(baseDir, env = emptyMap(), log = {})
        assertTrue("首次启动必须返回 FirstRun", first is ServerConfig.LoadResult.FirstRun)
        val file = (first as ServerConfig.LoadResult.FirstRun).file
        assertTrue("配置文件必须已生成", Files.exists(file))

        val second = ServerConfig.load(baseDir, env = emptyMap(), log = {})
        assertTrue("第二次启动必须返回 Ready", second is ServerConfig.LoadResult.Ready)
    }

    fun `桌面首次启动：生成配置后停下（FirstRun），模板里带上环境变量的实际值`() {
        // 没有任何「关键环境变量」⇒ 视为桌面/裸 jar 场景：生成模板后停一次
        val first = ServerConfig.load(baseDir, env = emptyMap(), log = {})
        val file = (first as ServerConfig.LoadResult.FirstRun).file
        val content = Files.readString(file)
        assertTrue("模板必须写实际生效端口", content.contains("port: "))
        assertTrue("模板必须写数据目录", content.contains("data:"))
    }

    @Test
    fun `容器部署（有环境变量）：首次启动也要直接 Ready，且配置落在数据卷上`() {
        // Docker/K8s 里「起一次就退出」会被判成崩溃重启 ⇒ 有环境变量就不再等人工改配置
        val dataDir = baseDir.resolve("env-data")
        val env = mapOf("LEGADO_PORT" to "8231", "LEGADO_DATA_DIR" to dataDir.toString())
        val loaded = ServerConfig.load(baseDir, env = env, log = {})
        assertTrue("环境变量部署必须直接 Ready：$loaded", loaded is ServerConfig.LoadResult.Ready)
        val file = dataDir.resolve("config").resolve("config.yaml")
        assertTrue("配置文件必须生成在数据卷上：$file", Files.exists(file))
        val content = Files.readString(file)
        assertTrue("端口要写实际生效值", content.contains("port: 8231"))
        assertTrue("数据目录要写实际生效值", content.contains("env-data"))
    }

    @Test
    fun `LEGADO_CONFIG_FILE 显式指定配置文件路径时按它读写`() {
        val custom = baseDir.resolve("custom").resolve("my.yaml")
        val env = mapOf("LEGADO_CONFIG_FILE" to custom.toString(), "ADMIN_PASSWORD" to "pw-123456")
        val loaded = ServerConfig.load(baseDir, env = env, log = {})
        assertTrue("显式配置文件 + 环境变量 ⇒ 直接 Ready：$loaded", loaded is ServerConfig.LoadResult.Ready)
        assertTrue("配置文件必须落在 LEGADO_CONFIG_FILE 指定处：$custom", Files.exists(custom))
    }

    @Test
    fun `数据库类型：默认 sqlite；postgresql 归一化；mysql 已移除必须明确报错`() {
        writeConfig("database:\n  type: sqlite\n")
        val sqlite = readyConfig(baseDir, emptyMap())
        assertEquals("sqlite", sqlite.databaseType)
        assertFalse("sqlite 不是外部库", sqlite.isExternalDatabase)
        assertNull(sqlite.databaseJdbcUrl)

        // 用户拍板移除 MySQL：旧配置里写着 mysql 必须**明确报错**，不能静默回落 sqlite
        writeConfig("database:\n  type: mysql\n")
        val removed = runCatching { readyConfig(baseDir, emptyMap()) }.exceptionOrNull()
        assertTrue("mysql 已移除：必须抛明确错误而不是静默回落：$removed", removed is UnsupportedDatabaseTypeException)
        assertTrue(
            "错误提示要给出替代方案：${removed?.message}",
            removed!!.message!!.contains("mysql 支持已移除") && removed.message!!.contains("postgres"),
        )

        writeConfig("database:\n  type: postgresql\n")
        val pg = readyConfig(baseDir, emptyMap())
        assertEquals("postgresql 要归一化成 postgres", "postgres", pg.databaseType)
        assertTrue("默认连接串：${pg.databaseJdbcUrl}", pg.databaseJdbcUrl!!.startsWith("jdbc:postgresql://"))
    }
@Test
    fun `sqlite 文件路径：database_sqlite_file 生效，旧键 data_database 仍兼容`() {
        writeConfig(
            """
            database:
              type: sqlite
              sqlite:
                file: /tmp/new-key.sqlite
            """.trimIndent(),
        )
        val fresh = readyConfig(baseDir, emptyMap())
        assertEquals("/tmp/new-key.sqlite", fresh.databasePath)

        writeConfig(
            """
            data:
              database: /tmp/legacy-key.sqlite
            """.trimIndent(),
        )
        val legacy = readyConfig(baseDir, emptyMap())
        assertEquals("/tmp/legacy-key.sqlite", legacy.databasePath)
    }}


