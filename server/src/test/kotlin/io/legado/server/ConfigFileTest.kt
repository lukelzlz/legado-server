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

        val config = ServerConfig.load(baseDir, env = emptyMap(), log = {})

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

        val config = ServerConfig.load(
            baseDir,
            env = mapOf("LEGADO_PORT" to "8123", "ADMIN_PASSWORD" to "", "LEGADO_HOST" to "0.0.0.0"),
            log = {},
        )

        assertEquals("环境变量必须覆盖文件", 8123, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals("空的环境变量不覆盖文件值", "from-file", config.initialAdminPassword)
    }

    @Test
    fun `YAML 写坏时回落到环境变量和默认值，不抛异常`() {
        writeConfig("server:\n  port: [unclosed\n")

        val config = ServerConfig.load(baseDir, env = mapOf("LEGADO_PORT" to "7777"), log = {})

        assertEquals(7777, config.port)
        assertEquals("0.0.0.0", config.host)
    }

    @Test
    fun `没有配置文件时用内置默认值（端口 8080，Secure Cookie 默认开）`() {
        val config = ServerConfig.load(baseDir, env = emptyMap(), log = {})
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
}
