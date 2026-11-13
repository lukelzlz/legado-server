package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 订阅源（RssSource）入库、一键导入分发与规则订阅（对齐开源阅读；起因 Issue #88）。
 */
class RssSourceTest {
    /** Issue #88 报告者提供的真实订阅文件（仅截短了内嵌的 base64 图标）。 */
    private val realRss = javaClass.getResource("/issue88-rss-subscription.json")!!.readText()
    private val bookSources =
        """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"源A"},{"bookSourceUrl":"https://b.example.com","bookSourceName":"源B"}]"""
    private val replaceRules = """[{"id":1,"name":"去广告","pattern":"广告","replacement":"","isRegex":false}]"""
    private val httpTts = """[{"id":7,"name":"朗读A","url":"https://tts.example.com/?t={{speakText}}"}]"""

    private fun withDb(block: suspend (Database) -> Unit) {
        val dir = Files.createTempDirectory("legado-rss")
        val db = Database(dir.resolve("db.sqlite").toString())
        db.initialize("test-password-1234")
        try { runBlocking { block(db) } } finally { db.close() }
    }

    private fun web(vararg pages: Pair<String, String>): (String) -> String {
        val map = mapOf(*pages)
        return { url -> map[url] ?: throw IllegalArgumentException("HTTP 404 $url") }
    }

    // ---------------------------------------------------------------- 类型判别

    @Test
    fun `kind detection follows legado jsonImportType`() {
        assertEquals("rssSource", RssSourceCodec.detectKind(realRss))
        assertEquals("bookSource", RssSourceCodec.detectKind(bookSources))
        assertEquals("replaceRule", RssSourceCodec.detectKind(replaceRules))
        assertEquals("httpTts", RssSourceCodec.detectKind(httpTts))
        // 同时带两个主键时书源优先（判定顺序）
        assertEquals("bookSource", RssSourceCodec.detectKind("""{"bookSourceUrl":"https://a","sourceUrl":"https://b"}"""))
        // 高亮规则带 pattern 但不是替换规则
        assertNull(RssSourceCodec.detectKind("""[{"pattern":"x","style":"s","uuid":"u"}]"""))
        assertNull(RssSourceCodec.detectKind("<html>404</html>"))
    }

    // ---------------------------------------------------------------- 入口地址

    @Test
    fun `open url follows legado getSingleUrl`() {
        val home = "http://feed.example.com/"
        assertEquals("http://feed.example.com/gx.html", RssSourceCodec.openUrl(home, "首页::http://feed.example.com/gx.html"))
        assertEquals("http://feed.example.com/a", RssSourceCodec.openUrl(home, "A::http://feed.example.com/a\nB::http://feed.example.com/b"))
        assertEquals("http://feed.example.com/a", RssSourceCodec.openUrl(home, "A::http://feed.example.com/a&&B::http://feed.example.com/b"))
        assertEquals("http://feed.example.com/plain", RssSourceCodec.openUrl(home, "http://feed.example.com/plain"))
        assertEquals(home, RssSourceCodec.openUrl(home, null))
        // 脚本型 sortUrl 未实现 ⇒ 退回 sourceUrl
        assertEquals(home, RssSourceCodec.openUrl(home, "@js:'x'"))
        assertEquals(home, RssSourceCodec.openUrl(home, "<js>'x'</js>"))
        // sortUrl 给的不是 http(s) 地址 ⇒ 退回 sourceUrl；两者都不是则无法打开
        assertEquals(home, RssSourceCodec.openUrl(home, "首页::/relative"))
        assertNull(RssSourceCodec.openUrl("自定义标识", null))
    }

    // ---------------------------------------------------------------- 入库

    @Test
    fun `real issue 88 file is stored as an rss source and never as a book source`() = withDb { db ->
        val result = db.importRssSources(SourceCodec.parseSourceList(realRss))
        assertEquals(1, result.imported)

        val saved = db.listRssSources().single()
        assertEquals("http://yuedu.miaogongzi.net/gx.html", saved.id)
        assertEquals("喵公子书源管理", saved.name)
        assertEquals("书源", saved.group)
        assertTrue(saved.singleUrl)
        assertEquals("http://yuedu.miaogongzi.net/gx.html", saved.openUrl)
        assertTrue(saved.icon!!.startsWith("data:image/png;base64,"))

        // 走书源导入则逐条被拒，书源表保持为空
        val asBookSource = db.importSources(SourceCodec.parseSourceList(realRss))
        assertEquals(0, asBookSource.imported)
        assertTrue(asBookSource.errors.single().contains("订阅源"))
        assertTrue(db.listSources(null).isEmpty())
    }

    @Test
    fun `rss source import is an upsert and supports toggle export and delete`() = withDb { db ->
        val id = "http://yuedu.miaogongzi.net/gx.html"
        db.importRssSources(SourceCodec.parseSourceList(realRss))
        val again = db.importRssSources(SourceCodec.parseSourceList(realRss))
        assertEquals(0, again.imported)
        assertEquals(1, again.updated)
        assertEquals(1, db.listRssSources().size)

        assertTrue(db.setRssSourceEnabled(id, false))
        assertFalse(db.listRssSources().single().enabled)
        assertFalse(db.setRssSourceEnabled("不存在", true))

        // 导出原样回放，可再次导入
        assertEquals("rssSource", RssSourceCodec.detectKind("[${db.exportRssSources().single()}]"))
        assertNotNull(db.getRssSourcePayload(id))

        assertTrue(db.deleteRssSource(id))
        assertTrue(db.listRssSources().isEmpty())
        assertNull(db.getRssSourcePayload(id))
    }

    @Test
    fun `book source is rejected by the rss importer`() = withDb { db ->
        val result = db.importRssSources(SourceCodec.parseSourceList(bookSources))
        assertEquals(0, result.imported)
        assertEquals(2, result.errors.size)
        assertTrue(db.listRssSources().isEmpty())
    }

    // ---------------------------------------------------------------- 一键导入

    @Test
    fun `online import dispatches by kind and auto detects when kind is absent`() = withDb { db ->
        val import = OnlineImport(
            db,
            web("u://rss" to realRss, "u://rules" to replaceRules, "u://tts" to httpTts, "u://books" to bookSources),
        )

        val dry = import.run("u://rss", null, dryRun = true)
        assertEquals("rssSource", dry.kind)
        assertEquals(1, dry.total)
        assertEquals(listOf("喵公子书源管理"), dry.names)
        assertTrue("dryRun 不得落库", db.listRssSources().isEmpty())

        assertEquals(1, import.run("u://rss", "auto", dryRun = false).imported)
        assertEquals(1, db.listRssSources().size)

        assertEquals("replaceRule", import.run("u://rules", null, dryRun = false).kind)
        assertEquals(1, db.listReplaceRules().size)

        assertEquals("httpTts", import.run("u://tts", null, dryRun = false).kind)
        assertEquals("朗读A", db.listHttpTts(null).single().name)

        // 书源只判别、不在这里落库（要走预览票据）
        assertEquals("bookSource", import.run("u://books", null, dryRun = true).kind)
        val error = assertThrows(NetworkImportException::class.java) { runBlocking { import.run("u://books", null, dryRun = false) } }
        assertEquals("import_use_preview", error.code)
        assertTrue(db.listSources(null).isEmpty())
    }

    @Test
    fun `online import reports unknown content instead of guessing`() = withDb { db ->
        val import = OnlineImport(db, web("u://html" to "<html>404</html>", "u://odd" to """[{"foo":1}]"""))
        listOf("u://html", "u://odd").forEach { url ->
            val error = assertThrows(NetworkImportException::class.java) { runBlocking { import.run(url, null, dryRun = true) } }
            assertEquals("import_kind_unknown", error.code)
        }
    }

    // ---------------------------------------------------------------- 规则订阅

    @Test
    fun `subscription of each type updates the matching store`() = withDb { db ->
        val service = SubscriptionService(db, fetcher = web("u://rss" to realRss, "u://rules" to replaceRules, "u://books" to bookSources)) {}
        val books = db.saveSubscription(SubscriptionWriteRequest("u://books", name = "书源集合", type = 0))
        val rss = db.saveSubscription(SubscriptionWriteRequest("u://rss", name = "喵公子", type = 1))
        val rules = db.saveSubscription(SubscriptionWriteRequest("u://rules", type = 2))

        assertEquals(2, service.updateOne(books.id).imported)
        assertEquals(1, service.updateOne(rss.id).imported)
        assertEquals(1, service.updateOne(rules.id).imported)

        assertEquals(2, db.listSources(null).size)
        assertEquals(1, db.listRssSources().size)
        assertEquals(1, db.listReplaceRules().size)
        assertEquals("喵公子", db.getSubscription(rss.id)!!.name)
        assertEquals(1, db.getSubscription(rss.id)!!.type)
    }

    /** Issue #88 的原始场景：把订阅源地址当书源订阅。必须报失败并说明原因，不能「成功 0 条」或落一条空书源。 */
    @Test
    fun `rss url subscribed as book source fails loudly`() = withDb { db ->
        val service = SubscriptionService(db, fetcher = web("u://rss" to realRss)) {}
        val sub = db.saveSubscription(SubscriptionWriteRequest("u://rss", type = 0))

        val error = assertThrows(IllegalArgumentException::class.java) { runBlocking { service.updateOne(sub.id) } }

        assertTrue(error.message!!.contains("订阅源"))
        assertTrue(db.listSources(null).isEmpty())
        assertTrue(db.getSubscription(sub.id)!!.lastError!!.contains("订阅源"))
    }

    @Test
    fun `only subscriptions with auto update that reached their interval are due`() = withDb { db ->
        val hour = 60 * 60 * 1000L
        fun sub(auto: Boolean, interval: Int, lastAttempt: Long?) =
            SourceSubscription(1, "u", true, 0, 0, lastAttemptAt = lastAttempt, autoUpdate = auto, updateInterval = interval)
        val now = 100 * hour
        assertTrue(SubscriptionService.isDue(sub(true, 24, null), now))
        assertTrue(SubscriptionService.isDue(sub(true, 24, now - 24 * hour), now))
        assertFalse(SubscriptionService.isDue(sub(true, 24, now - 23 * hour), now))
        assertFalse(SubscriptionService.isDue(sub(false, 24, null), now))
        assertFalse(SubscriptionService.isDue(sub(true, 0, null), now))

        // 定时器只碰到期的；手动「全部更新」不看自动更新开关
        val service = SubscriptionService(db, fetcher = web("u://a" to bookSources, "u://b" to realRss)) {}
        db.saveSubscription(SubscriptionWriteRequest("u://a", type = 0, autoUpdate = true, updateInterval = 1))
        db.saveSubscription(SubscriptionWriteRequest("u://b", type = 1))
        assertEquals(1, service.updateDue().size)
        assertEquals(2, db.listSources(null).size)
        assertTrue(db.listRssSources().isEmpty())
        assertEquals(0, service.updateDue().size)
        assertEquals(2, service.updateAll().size)
        assertEquals(1, db.listRssSources().size)
    }
}
