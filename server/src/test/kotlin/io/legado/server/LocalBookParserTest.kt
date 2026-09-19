package io.legado.server

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalBookParserTest {

    @Test
    fun `extractTitleAndAuthorFromFilename extracts correctly`() {
        val (t1, a1) = LocalBookParser.extractTitleAndAuthorFromFilename("《大奉打更人》作者：卖报小郎君.txt")
        assertEquals("大奉打更人", t1)
        assertEquals("卖报小郎君", a1)

        val (t2, a2) = LocalBookParser.extractTitleAndAuthorFromFilename("凡人修仙传_忘语.txt")
        assertEquals("凡人修仙传", t2)
        assertEquals("忘语", a2)

        val (t3, a3) = LocalBookParser.extractTitleAndAuthorFromFilename("诡秘之主(爱潜水的乌贼).txt")
        assertEquals("诡秘之主", t3)
        assertEquals("爱潜水的乌贼", a3)

        val (t4, a4) = LocalBookParser.extractTitleAndAuthorFromFilename("三体.epub")
        assertEquals("三体", t4)
        assertNull(a4)
    }

    @Test
    fun `parseTxt with UTF-8 and standard chapters`() {
        val content = """
            这是书本的前言与世界观简介。
            第二行前言。
            
            第一章 穿越异界
            主角苏醒了，发现自己来到了一个全新的修仙世界。
            灵气十分充裕。
            
            第二章 初入宗门
            来到青云门，开始测试灵根。
            竟然是天灵根！
            
            尾声
            最终登顶仙界。
        """.trimIndent()

        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val parsed = LocalBookParser.parseTxt("《修仙传奇》作者：青云.txt", bytes)

        assertEquals("修仙传奇", parsed.title)
        assertEquals("青云", parsed.author)
        assertEquals(4, parsed.chapters.size)
        assertEquals("前言", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].content.contains("这是书本的前言"))
        assertEquals("第一章 穿越异界", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].content.contains("主角苏醒了"))
        assertEquals("第二章 初入宗门", parsed.chapters[2].title)
        assertEquals("尾声", parsed.chapters[3].title)
        assertNotNull(parsed.coverBytes)
        assertEquals("image/svg+xml", parsed.coverContentType)
    }

    @Test
    fun `parseTxt with GB18030 encoding`() {
        val content = """
            Chapter 1 The Beginning
            In a hole in the ground there lived a hobbit.
            
            Chapter 2 The Journey
            They set out on the road early morning.
        """.trimIndent()

        val gbkCharset = Charset.forName("GB18030")
        val bytes = content.toByteArray(gbkCharset)
        val parsed = LocalBookParser.parseTxt("The_Hobbit_Tolkien.txt", bytes)

        assertEquals("The_Hobbit", parsed.title)
        assertEquals("Tolkien", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1 The Beginning", parsed.chapters[0].title)
        assertEquals("Chapter 2 The Journey", parsed.chapters[1].title)
    }

    @Test
    fun `parseTxt with UTF-8 BOM`() {
        val content = "第一章 起源\n正文内容"
        val raw = content.toByteArray(StandardCharsets.UTF_8)
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + raw
        val parsed = LocalBookParser.parseTxt("测试BOM.txt", bomBytes)
        assertEquals("测试BOM", parsed.title)
        assertEquals(1, parsed.chapters.size)
        assertEquals("第一章 起源", parsed.chapters[0].title)
        assertEquals("正文内容", parsed.chapters[0].content)
    }

    @Test
    fun `parseTxt with unformatted text paginates gracefully`() {
        val longParagraph = "这是一段非常长的无章节短篇小说内容。".repeat(300)
        val bytes = longParagraph.toByteArray(StandardCharsets.UTF_8)
        val parsed = LocalBookParser.parseTxt("短篇散文.txt", bytes)

        assertEquals("短篇散文", parsed.title)
        assertTrue(parsed.chapters.size >= 2)
        assertEquals("第 1 节", parsed.chapters[0].title)
        assertEquals("第 2 节", parsed.chapters[1].title)
    }

    @Test
    fun `parseEpub extracts metadata, cover, ncx and xhtml chapters`() {
        val epubBytes = createSampleEpub(
            title = "精排三体",
            author = "刘慈欣",
            intro = "地球文明与三体文明的宇宙博弈。",
            coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), // mock jpeg
            chapters = listOf(
                "第一章 科学边界" to "<html><body><h1>第一章 科学边界</h1><p>汪淼觉得自己的眼睛出了问题。</p></body></html>",
                "第二章 倒计时" to "<html><body><h1>第二章 倒计时</h1><p>幽灵倒计时在他眼前闪烁。</p></body></html>"
            )
        )

        val parsed = LocalBookParser.parseEpub("三体.epub", epubBytes)
        assertEquals("精排三体", parsed.title)
        assertEquals("刘慈欣", parsed.author)
        assertEquals("地球文明与三体文明的宇宙博弈。", parsed.intro)
        assertNotNull(parsed.coverBytes)
        assertEquals("image/jpeg", parsed.coverContentType)
        assertEquals(2, parsed.chapters.size)
        assertEquals("第一章 科学边界", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].content.contains("汪淼觉得自己的眼睛出了问题。"))
        assertEquals("第二章 倒计时", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].content.contains("幽灵倒计时在他眼前闪烁。"))
    }

    @Test
    fun `importLocalBook in Database and reading routes`() {
        val tempDir = Files.createTempDirectory("local-book-db-test")
        val dbPath = tempDir.resolve("legado.sqlite").toString()
        val database = Database(dbPath)
        database.initialize("admin123")

        val coverCache = CoverCache(tempDir.resolve("covers"))

        val parsed = ParsedBook(
            title = "雪中悍刀行",
            author = "烽火戏诸侯",
            intro = "江湖是一张珠帘。",
            coverBytes = LocalBookParser.generateSvgCover("雪中悍刀行", "烽火戏诸侯").toByteArray(StandardCharsets.UTF_8),
            coverContentType = "image/svg+xml",
            chapters = listOf(
                ParsedChapter("第一章 小二上酒", "小二，来壶黄酒，切两斤熟牛肉！"),
                ParsedChapter("第二章 白狐儿脸", "听潮亭下，一位绝美白衣男子正在读书。")
            )
        )

        val coverKey = coverCache.saveCoverBytes(parsed.coverBytes!!, parsed.coverContentType!!)
        val bookId = "book123"
        val item = database.importLocalBook(bookId, parsed, coverKey)

        assertEquals("loc_book", item.sourceId)
        assertEquals("local://book123", item.bookUrl)
        assertEquals("雪中悍刀行", item.name)
        assertEquals("烽火戏诸侯", item.author)
        assertEquals(2, item.totalChapters)
        assertEquals(2, item.cachedChapters)
        assertEquals("ready", item.cacheState)

        // 验证 TOC
        val toc = database.getTocCache(item.sourceId, item.bookUrl)
        assertNotNull(toc)
        assertEquals(2, toc!!.size)
        assertEquals("第一章 小二上酒", toc[0].title)
        assertEquals("local://book123/chapter_1", toc[0].url)

        // 验证正文读取
        val content1 = database.cachedContent(item.sourceId, item.bookUrl, "local://book123/chapter_1")
        assertNotNull(content1)
        assertEquals("第一章 小二上酒", content1!!.title)
        assertTrue(content1.content.contains("小二，来壶黄酒"))

        val content2 = database.cachedContent(item.sourceId, item.bookUrl, "local://book123/chapter_2")
        assertNotNull(content2)
        assertEquals("第二章 白狐儿脸", content2!!.title)
        assertTrue(content2.content.contains("听潮亭下"))

        // 删除书籍
        val orphan = database.removeBookshelf(item.sourceId, item.bookUrl)
        assertNull(database.getTocCache(item.sourceId, item.bookUrl))
        assertNull(database.cachedContent(item.sourceId, item.bookUrl, "local://book123/chapter_1"))
        database.close()
        tempDir.toFile().deleteRecursively()
    }

    private fun createSampleEpub(
        title: String,
        author: String,
        intro: String,
        coverBytes: ByteArray,
        chapters: List<Pair<String, String>>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // mimetype (EPUB requirement)
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
            zos.closeEntry()

            // META-INF/container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            // OEBPS/cover.jpg
            zos.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
            zos.write(coverBytes)
            zos.closeEntry()

            // OEBPS/content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()
            val navPoints = StringBuilder()

            manifestItems.append("<item id=\"cover-image\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n")
            manifestItems.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")

            chapters.forEachIndexed { idx, (chTitle, _) ->
                val filename = "chapter_${idx + 1}.xhtml"
                manifestItems.append("<item id=\"ch_${idx + 1}\" href=\"$filename\" media-type=\"application/xhtml+xml\"/>\n")
                spineItems.append("<itemref idref=\"ch_${idx + 1}\"/>\n")
                navPoints.append("""
                    <navPoint id="np_${idx + 1}" playOrder="${idx + 1}">
                      <navLabel><text>$chTitle</text></navLabel>
                      <content src="$filename"/>
                    </navPoint>
                """.trimIndent())
            }

            zos.write("""
                <?xml version="1.0" encoding="utf-8"?>
                <package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:description>$intro</dc:description>
                    <meta name="cover" content="cover-image"/>
                  </metadata>
                  <manifest>
                    $manifestItems
                  </manifest>
                  <spine toc="ncx">
                    $spineItems
                  </spine>
                </package>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            // OEBPS/toc.ncx
            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zos.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head>
                    <meta name="dtb:uid" content="12345"/>
                  </head>
                  <docTitle><text>$title</text></docTitle>
                  <navMap>
                    $navPoints
                  </navMap>
                </ncx>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            // Chapters
            chapters.forEachIndexed { idx, (_, html) ->
                zos.putNextEntry(ZipEntry("OEBPS/chapter_${idx + 1}.xhtml"))
                zos.write(html.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
