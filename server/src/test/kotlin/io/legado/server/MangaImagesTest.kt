package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 方案 E · P0 解析打通：`RuleRunner.mangaImages()` 与 `BookKind` 判定。
 *
 * 这些用例锁定的都是**静默失败**类的风险（清单空、顺序乱、相对路径没补全、
 * `data-src` 懒加载漏图）——它们不会报错，只会让用户看到空白或错图。
 */
class MangaImagesTest {

    // ------------------------------------------------------------------
    // 1. splitImageUrls：三种规则返回形态
    // ------------------------------------------------------------------

    @Test
    fun `extracts images from html fragment preferring lazy-load attributes`() {
        val raw = """
            <div class="comic">
              <img src="/img/1.jpg">
              <img data-src="/img/2.jpg" src="data:image/gif;base64,R0lGOD">
              <img data-original="https://cdn.example.com/3.jpg">
            </div>
        """.trimIndent()

        val urls = RuleRunner { "" }.splitImageUrls(raw)

        assertEquals("必须保序提取全部 3 张（含 data-src / data-original 懒加载）", 3, urls.size)
        assertEquals("/img/1.jpg", urls[0])
        assertEquals("data-src 必须优先于占位 src", "/img/2.jpg", urls[1])
        assertEquals("data-original 也要认", "https://cdn.example.com/3.jpg", urls[2])
    }

    @Test
    fun `splits newline separated multi-value rule result`() {
        val raw = "/img/a.jpg\n/img/b.jpg\n\n  /img/c.jpg  "

        val urls = RuleRunner { "" }.splitImageUrls(raw)

        assertEquals(listOf("/img/a.jpg", "/img/b.jpg", "/img/c.jpg"), urls)
    }

    @Test
    fun `splits json array string`() {
        val raw = """["/img/a.jpg","https://cdn.example.com/b.jpg"]"""

        val urls = RuleRunner { "" }.splitImageUrls(raw)

        assertEquals(listOf("/img/a.jpg", "https://cdn.example.com/b.jpg"), urls)
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(RuleRunner { "" }.splitImageUrls("").isEmpty())
        assertTrue(RuleRunner { "" }.splitImageUrls("   \n  ").isEmpty())
        assertTrue(RuleRunner { "" }.splitImageUrls(null).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. mangaImages：端到端（注入响应，不触网）
    // ------------------------------------------------------------------

    private val sourceUrl = "https://manga.example.com"

    private fun comicSource(contentRule: String) = """
        {
          "bookSourceName": "测试漫画",
          "bookSourceUrl": "$sourceUrl",
          "bookSourceType": 2,
          "ruleContent": { "content": "$contentRule" }
        }
    """.trimIndent()

    private val chapterUrl = "$sourceUrl/chapter/12"

    @Test
    fun `mangaImages resolves relative urls against chapter url and keeps order`() {
        val html = """
            <html><body>
              <div class="comic">
                <img src="/img/1.jpg">
                <img src="//cdn2.example.com/2.jpg">
                <img src="https://cdn3.example.com/3.jpg">
              </div>
            </body></html>
        """.trimIndent()
        val runner = RuleRunner(responseFetcher = { html })

        val images = runner.mangaImages(comicSource("@css:.comic@html"), chapterUrl)

        assertEquals(3, images.size)
        assertEquals("$sourceUrl/img/1.jpg", images[0])
        assertEquals("协议相对地址必须补全为 https", "https://cdn2.example.com/2.jpg", images[1])
        assertEquals("https://cdn3.example.com/3.jpg", images[2])
    }

    @Test
    fun `mangaImages works with multi-value css rule joined by newline`() {
        val html = """
            <html><body>
              <div class="comic"><img src="/img/1.jpg"></div>
              <div class="comic"><img src="/img/2.jpg"></div>
            </body></html>
        """.trimIndent()
        val runner = RuleRunner(responseFetcher = { html })

        val images = runner.mangaImages(comicSource("@css:.comic img@src"), chapterUrl)

        assertEquals(listOf("$sourceUrl/img/1.jpg", "$sourceUrl/img/2.jpg"), images)
    }

    @Test
    fun `mangaImages deduplicates while preserving first occurrence`() {
        val html = """
            <html><body><div class="comic">
              <img src="/img/1.jpg"><img src="/img/2.jpg"><img src="/img/1.jpg">
            </div></body></html>
        """.trimIndent()
        val runner = RuleRunner(responseFetcher = { html })

        val images = runner.mangaImages(comicSource("@css:.comic@html"), chapterUrl)

        assertEquals("重复图片只保留首次位置", listOf("$sourceUrl/img/1.jpg", "$sourceUrl/img/2.jpg"), images)
    }

    @Test
    fun `mangaImages returns empty list when rule yields nothing`() {
        val runner = RuleRunner(responseFetcher = { "<html><body><p>没有图片</p></body></html>" })

        val images = runner.mangaImages(comicSource("@css:.comic@html"), chapterUrl)

        // 刻意**不抛异常**：让路由返回 total=0，由前端给出明确提示
        // （抛 500 会把「规则没配好」伪装成「服务器故障」）。
        assertTrue("空清单必须是空列表而不是异常", images.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. BookKind 判定
    // ------------------------------------------------------------------

    @Test
    fun `detects comic from numeric and string bookSourceType`() {
        assertEquals(BookKind.COMIC, BookKind.fromSourcePayload("""{"bookSourceType":2}"""))
        assertEquals(BookKind.COMIC, BookKind.fromSourcePayload("""{"bookSourceType":"2"}"""))
    }

    @Test
    fun `treats other or missing bookSourceType as novel`() {
        assertEquals(BookKind.NOVEL, BookKind.fromSourcePayload("""{"bookSourceType":0}"""))
        // 1 = 音频/听书（有声书支持后它不再等于小说了）
        assertEquals(BookKind.AUDIO, BookKind.fromSourcePayload("""{"bookSourceType":1}"""))
        assertEquals(BookKind.AUDIO, BookKind.fromSourcePayload("""{"bookSourceType":"1"}"""))
        assertEquals(BookKind.NOVEL, BookKind.fromSourcePayload("""{"bookSourceName":"没有类型字段"}"""))
        assertEquals(BookKind.NOVEL, BookKind.fromSourcePayload("不是 JSON"))
        assertEquals(BookKind.NOVEL, BookKind.fromSourcePayload(null))
    }

    @Test
    fun `detects comic from legado backup tab`() {
        assertEquals(BookKind.COMIC, BookKind.fromTab("漫画"))
        assertEquals(BookKind.NOVEL, BookKind.fromTab("小说"))
        // 听书 ⇒ 音频（有声书 G0 起成为一等类型）
        assertEquals(BookKind.AUDIO, BookKind.fromTab("听书"))
        assertEquals(BookKind.NOVEL, BookKind.fromTab(null))
    }

    // ------------------------------------------------------------------
    // 4. 书架列持久化（book_kind）
    // ------------------------------------------------------------------

    @Test
    fun `shelf persists and returns book kind`() {
        val path = Files.createTempFile("legado-manga-kind", ".sqlite")
        val database = Database(path.toString())
        try {
            database.initialize("password-for-test")

            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://manga.example.com",
                    bookUrl = "$sourceUrl/book/1",
                    name = "测试漫画",
                    tocUrl = "$sourceUrl/book/1",
                    bookKind = "comic",
                ),
                cover = null,
            )
            database.saveBookshelf(
                BookshelfWriteRequest(
                    sourceId = "https://novel.example.com",
                    bookUrl = "https://novel.example.com/book/2",
                    name = "普通小说",
                    tocUrl = "https://novel.example.com/book/2",
                ),
                cover = null,
            )

            val shelf = database.listBookshelf().associateBy { it.bookUrl }
            assertEquals("comic", shelf["$sourceUrl/book/1"]?.bookKind)
            assertEquals("缺省必须是 novel（绝不猜成漫画）", "novel", shelf["https://novel.example.com/book/2"]?.bookKind)

            // 解析器识别到漫画时的幂等修正
            database.setBookKindSilently("https://novel.example.com", "https://novel.example.com/book/2")
            assertEquals("comic", database.getShelfBookByUrl("https://novel.example.com/book/2")?.bookKind)
        } finally {
            database.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(Path.of("$path-wal"))
            Files.deleteIfExists(Path.of("$path-shm"))
        }
    }
}
