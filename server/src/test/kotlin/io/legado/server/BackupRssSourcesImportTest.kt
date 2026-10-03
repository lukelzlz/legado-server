package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导入：`rssSources.json`（RSS 订阅源）必须真的进库，且**规则原样保留**。
 *
 * 参照物是工作区里那份**真实备份**的 `rssSources.json`
 * （`backup2026-09-30-PEPM00.zip` 抽出，8 条 / 31 字段并集，作为测试资源逐字保存）。
 * 与 [BackupHttpTtsImportTest] 同一套路：真实同形数据 → 走完整导入 → 逐字段断言。
 *
 * 为什么必须用真实数据而不是手搓样本：真实数据里有几个**反直觉**的形态，
 * 手写样本几乎必然写成「理想形态」而漏掉它们：
 * - `sourceUrl` 是**非 URL 的自定义中文串**（`https://www.baidu.com/大灰狼番茄书荒广场`）、
 *   乃至 `snssdk1128://user/profile/…` 这种自定义 scheme；
 * - `redirectPolicy` 是**字符串枚举**（`"ASK_CROSS_ORIGIN"`），不是整数；
 * - 8 条里**只有 1 条**有规则（其余 7 条 `ruleArticles` 等 5 个规则字段全空）——
 *   它们在手机版里是「打开网页」用的，没有文章列表能力。
 *
 * ⚠️ 本用例**不联网**：只验证「备份里的规则被原样落库」，
 * 不验证「这些规则能被求值出文章」。后者由 `RssRuleRunnerTest` 用录制响应负责。
 */
class BackupRssSourcesImportTest {

    private val shelfJson = """
        [{"name":"测试书","origin":"大灰狼融合VIP5.0","bookUrl":"data:;base64,aa","tocUrl":"data:;base64,aa"}]
    """.trimIndent()

    /** 与真实备份**逐字一致**的 `rssSources.json`（8 条）。 */
    private fun realRssSourcesJson(): String =
        checkNotNull(javaClass.getResourceAsStream("/backup/rssSources.json")) {
            "测试资源 /backup/rssSources.json 缺失"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun zipWith(shelf: String, rss: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 条目名用**真实备份的大小写**（rssSources.json），与 readSection 的 lowercase 口径对齐
            zip.putNextEntry(ZipEntry("bookshelf.json")); zip.write(shelf.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("rssSources.json")); zip.write(rss.toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `真实备份的 8 个订阅源必须全部进库并逐字段保真`() {
        val dbPath = Files.createTempFile("legado-rss-import", ".sqlite").toString()
        val zipPath = Files.createTempFile("legado-rss-import", ".zip")
        try {
            Files.write(zipPath, zipWith(shelfJson, realRssSourcesJson()))
            val database = Database(dbPath)
            database.initialize("password-for-test")
            val summary = BackupImporter(database).import(Path.of(zipPath.toString()))

            // 8 条全部导入，且都是「新增」而不是「更新」
            assertEquals(8, summary.rssSources)
            assertEquals(0, summary.rssSourcesUpdated)

            val sources = database.listRssSources()
            assertEquals(8, sources.size)

            // ---- 第 1 条：唯一有规则的那个源（大灰狼书荒广场）----
            val dagou = sources.firstOrNull { it.sourceName == "大灰狼书荒广场" }
                ?: error("未导入「大灰狼书荒广场」；实际导入：" + sources.map { it.sourceName })
            // sourceUrl 是**允许非 URL 的中文自定义串**，严禁被 URL 校验挡掉
            assertEquals("https://www.baidu.com/大灰狼番茄书荒广场", dagou.sourceUrl)
            assertEquals(-101052, dagou.customOrder)
            assertEquals(0, dagou.type)
            assertTrue("enabled 应为 true", dagou.enabled)
            // 5 条规则逐字保真（含 <js> 与模板字符串）
            assertTrue("ruleArticles 应以 <js> 开头", dagou.ruleArticles!!.startsWith("<js>"))
            assertTrue("ruleArticles 应含 java.ajax(java.log(...)) 惯用法", dagou.ruleArticles!!.contains("java.ajax(java.log("))
            assertTrue("ruleArticles 应保留末段取值路径", dagou.ruleArticles!!.contains("$.data.cell_view.topic_data[*]"))
            assertEquals("$.topic_desc.topic_title", dagou.ruleTitle)
            assertEquals("$.topic_desc.topic_cover", dagou.ruleImage)
            assertTrue("ruleLink 应保留 {{$.…}} 模板", dagou.ruleLink!!.contains("{{$.topic_desc.topic_id}}"))
            // 混合形态：JsonPath + 后置 @js:（这条锁死「路径@js:」不得被拆坏）
            assertTrue("rulePubDate 应同时含路径与 @js:", dagou.rulePubDate!!.contains("@js:"))
            assertTrue("rulePubDate 应含 JsonPath 前缀", dagou.rulePubDate!!.startsWith("$.topic_desc.topic_content"))
            // jsLib / sortUrl / loginUrl 都是大段多行文本，必须原样保留
            assertNotNull("jsLib 不应为空", dagou.jsLib)
            assertTrue("jsLib 应含 base_url 定义", dagou.jsLib!!.contains("base_url"))
            assertTrue("sortUrl 应含多行分类", dagou.sortUrl!!.contains("番茄::番茄"))
            assertTrue("sortUrl 应有多行", dagou.sortUrl!!.contains("\n"))
            assertNotNull("loginUrl 不应为空", dagou.loginUrl)
            // redirectPolicy 是**字符串**枚举
            assertEquals("ASK_CROSS_ORIGIN", dagou.redirectPolicy)
            // 这一条真实数据**没有** sourceGroup（另外 4 条有），字段缺失应为 null 而不是空串
            assertNull("大灰狼源无分组，应为 null", dagou.sourceGroup)

            // ---- 其余 7 条：无规则的「网页链接」，规则字段应全空但不该丢 ----
            val ruleless = sources.filter { it.sourceName != "大灰狼书荒广场" }
            assertEquals(7, ruleless.size)
            assertTrue(
                "手机端无规则的源其 5 个规则字段应全部为空",
                ruleless.all { it.ruleArticles.isNullOrBlank() && it.ruleLink.isNullOrBlank() && it.ruleTitle.isNullOrBlank() && it.ruleImage.isNullOrBlank() && it.rulePubDate.isNullOrBlank() },
            )
            // 逐条确认 7 个非 URL 的 sourceUrl 都被接受（自定义 scheme / 中文串 / 含 @js: 的串）
            val urls = ruleless.map { it.sourceUrl }.toSet()
            assertTrue("自定义 scheme 必须被接受", urls.contains("snssdk1128://user/profile/562564899806367"))
            assertTrue("含 @js: 的 sourceUrl 必须被原样保留", urls.any { it.startsWith("http@js:") })
            assertTrue("纯 http 链接也必须被接受", urls.contains("https://www.yuque.com/legado"))

            // 运行态：导入**不该**把源标成「已成功刷新」
            assertNull("导入不应伪造成功时间", dagou.lastSuccessAt)
            assertNull("导入不应伪造错误", dagou.lastError)
            assertEquals("导入阶段不应有文章", 0, dagou.articleCount)

            database.close()
        } finally {
            runCatching { Files.deleteIfExists(Path.of(zipPath.toString())) }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }

    /**
     * 重复导入同一备份必须**幂等**：第二次全部记为「更新」，且总数不变。
     *
     * ⚠️ 返回值不能靠 `changes()` 判定新增/更新（`on conflict do update` 在 update 分支
     * 同样报 1 行受影响，见 AGENTS.md），本用例顺带锁死这一点。
     */
    @Test
    fun `重复导入同一份 rssSources 必须幂等`() {
        val dbPath = Files.createTempFile("legado-rss-idem", ".sqlite").toString()
        val zipPath = Files.createTempFile("legado-rss-idem", ".zip")
        try {
            Files.write(zipPath, zipWith(shelfJson, realRssSourcesJson()))
            val database = Database(dbPath)
            database.initialize("password-for-test")
            val resolved = Path.of(zipPath.toString())

            val first = BackupImporter(database).import(resolved)
            assertEquals(8, first.rssSources)
            assertEquals(0, first.rssSourcesUpdated)

            val second = BackupImporter(database).import(resolved)
            assertEquals("第二次不该再算新增", 0, second.rssSources)
            assertEquals("第二次应全部算更新", 8, second.rssSourcesUpdated)
            assertEquals("总数不能翻倍", 8, database.listRssSources().size)

            database.close()
        } finally {
            runCatching { Files.deleteIfExists(Path.of(zipPath.toString())) }
            runCatching { Files.deleteIfExists(Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(Path.of("$dbPath-shm")) }
        }
    }
}
