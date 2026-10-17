package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 封面自愈**绝不阻塞启动**（仓库里最贵的一次教训，见 AGENTS.md `[启动/缓存]`）。
 *
 * 历史事故：418 本书的启动期续做把服务打到「日志显示 Application started，但接口持续超时 4 分钟」。
 * 教训的通用形态是：**后台保洁类工作必须延迟启动 + 分批投放，且任务数量无界时限流要加在全局**。
 *
 * 因此这里构造一个「整架书都没封面」的极端库（300 本），断言：
 * 1. 启动后 `/healthz` 立刻可用（秒级，而不是被 300 本封面拖成分钟级）；
 * 2. 启动瞬间**一张封面都没抓**（worker 延迟 [ServerConfig.COVER_SELF_HEAL_START_DELAY_MS] 才开工）；
 * 3. 这个延迟常量本身足够大（>= 1 分钟），保证它不是「碰巧很快」。
 */
class CoverSelfHealStartupTest {

    private val password = "test-password-1234"
    private val sourceId = "https://cover.example.com"
    private val bookCount = 300

    @Test
    fun `整架书都缺封面也不阻塞启动`() {
        val dbPath = Files.createTempFile("legado-selfheal-startup", ".sqlite").toString()
        val coverDir = Files.createTempDirectory("legado-selfheal-startup-covers")
        try {
            // 预置 300 本「只有外链、没有本地封面」的书（自愈最想抓的形态），然后关库再起服务。
            Database(dbPath).use { database ->
                database.initialize(password)
                repeat(bookCount) { i ->
                    database.saveBookshelf(
                        BookshelfWriteRequest(
                            sourceId = sourceId,
                            bookUrl = "$sourceId/book/$i",
                            name = "书$i",
                            author = "作者",
                            tocUrl = "$sourceId/book/$i/toc",
                            coverUrl = "https://img.example.com/$i.png",
                        ),
                        null,
                    )
                }
            }

            testApplication {
                application {
                    legadoApplication(
                        ServerConfig(
                            host = "0.0.0.0", port = 8080, databasePath = dbPath,
                            coverCacheDirectory = coverDir, webDavDirectory = coverDir.resolve("webdav"),
                            initialAdminPassword = password, secureCookies = false,
                        ),
                    )
                }
                val client = createClient {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    install(HttpCookies)
                }

                // ① 就绪必须立刻可用：被 300 本封面拖住的话这里会是分钟级
                val startedAt = System.currentTimeMillis()
                val health = client.get("/healthz")
                val elapsedMs = System.currentTimeMillis() - startedAt
                assertEquals(HttpStatusCode.OK, health.status)
                assertTrue("启动后 /healthz 必须秒级可用，实际 ${elapsedMs}ms", elapsedMs < 10_000)

                // ② 登录并确认 300 本书确实在架、且确实都还没有本地封面
                val login = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(password))
                }
                assertEquals(HttpStatusCode.OK, login.status)
                val shelf: List<BookshelfItem> = client.get("/api/bookshelf").body()
                assertEquals("数据必须真的在架（否则这个用例什么也没证明）", bookCount, shelf.size)
                assertEquals("整架书都缺本地封面", bookCount, shelf.count { it.coverKey.isNullOrBlank() })

                // ③ worker 延迟启动 ⇒ 启动瞬间一张封面都没抓（既没下载也没落盘）
                val files = Files.list(coverDir).use { stream -> stream.filter { Files.isRegularFile(it) }.count() }
                assertEquals("启动阶段绝不允许抓封面（worker 必须延迟启动）", 0L, files)
            }
        } finally {
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
            runCatching { coverDir.toFile().deleteRecursively() }
        }
    }

    /** 延迟常量本身必须够大：否则「碰巧很快」会让上一条用例变成假绿。 */
    @Test
    fun `自愈首轮延迟与巡检间隔是"低优先级保洁"的量级`() {
        assertTrue(
            "首轮延迟必须 >= 1 分钟（实际 ${ServerConfig.COVER_SELF_HEAL_START_DELAY_MS}ms）",
            ServerConfig.COVER_SELF_HEAL_START_DELAY_MS >= 60_000L,
        )
        assertTrue(
            "巡检间隔必须 >= 1 分钟（实际 ${ServerConfig.COVER_SELF_HEAL_INTERVAL_MS}ms）",
            ServerConfig.COVER_SELF_HEAL_INTERVAL_MS >= 60_000L,
        )
    }
}
