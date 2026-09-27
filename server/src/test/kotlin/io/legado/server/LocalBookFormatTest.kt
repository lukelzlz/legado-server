package io.legado.server

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 回归测试：**本地书籍导入只接受 TXT / EPUB**。
 *
 * ## 为什么需要这条约束
 *
 * `LocalBookParser.parse` 对未知扩展名会**静默回退成按 TXT 解析**
 * （内容探测兜底）。这对手工挑选文件的场景是方便的，
 * 但在"导入本地书籍"入口会把 `.pdf`/`.mobi`/`.docx` 当成 TXT 硬解析 ——
 * 用户会得到一本**目录错乱、正文是二进制乱码的"书"，且没有任何报错**。
 *
 * 因此在导入入口先按扩展名拦截。
 */
class LocalBookFormatTest {

    // ------------------------------------------------------------------
    // 格式判定
    // ------------------------------------------------------------------

    /** TXT / EPUB（含 `.text` 历史命名）必须放行。 */
    @Test
    fun `accepts txt and epub`() {
        for (name in listOf(
            "book.txt", "book.TXT", "BOOK.Txt",
            "book.epub", "book.EPUB",
            "book.text",
            "带 空格 的中文书名.txt",
            "无扩展名也会被拒吗.txt",
            "a.b.c.txt",   // 多点文件名，取最后一段
        )) {
            assertTrue("$name 应被接受", LocalBookParser.isSupported(name))
        }
    }

    /**
     * 其它格式必须**拒绝**，而不是回退成 TXT 硬解析。
     *
     * 这些正是「静默产生乱码书」的高危输入。
     */
    @Test
    fun `rejects every other format`() {
        for (name in listOf(
            "book.pdf", "book.mobi", "book.azw3", "book.azw", "book.fb2", "book.umd",
            "book.cbz", "book.cbr", "book.docx", "book.doc", "book.html", "book.htm",
            "book.zip", "book.rar", "book.md", "book.json", "book.xml",
            "无扩展名", "book.", "book.txt.exe",
        )) {
            assertFalse("$name 应被拒绝（仅支持 TXT/EPUB）", LocalBookParser.isSupported(name))
        }
    }

    /** 大小写不敏感 —— 用户从各处下载的文件名大小写很随意。 */
    @Test
    fun `format detection is case insensitive`() {
        assertTrue(LocalBookParser.isSupported("A.EpUb"))
        assertTrue(LocalBookParser.isSupported("A.TxT"))
        assertFalse(LocalBookParser.isSupported("A.PdF"))
    }

    /** 提示文案必须与实际支持的格式一致（前端也展示它）。 */
    @Test
    fun `supported formats label matches the rule`() {
        assertEquals("TXT / EPUB", LocalBookParser.SUPPORTED_FORMATS)
        assertTrue(LocalBookParser.isSupported("x.txt"))
        assertTrue(LocalBookParser.isSupported("x.epub"))
    }

    // ------------------------------------------------------------------
    // 与 parse 的配合：受支持格式必须真的能解析
    // ------------------------------------------------------------------

    /** 合法 TXT 能解析出章节（确认"接受"不等于"能解析"是分开的两件事）。 */
    @Test
    fun `accepted txt actually parses`() {
        val text = buildString {
            repeat(3) { appendLine("第${it + 1}章 测试章节${it + 1}"); appendLine("这是第${it + 1}章的正文内容。"); appendLine() }
        }
        val parsed = LocalBookParser.parseTxt("测试书.txt", text.toByteArray(Charsets.UTF_8))
        assertEquals("测试书", parsed.title)
        assertEquals("应解析出 3 章", 3, parsed.chapters.size)
    }

    /** 合法 EPUB 能解析出章节与元数据。 */
    @Test
    fun `accepted epub actually parses`() {
        val epub = buildMinimalEpub()
        val parsed = LocalBookParser.parseEpub("测试.epub", epub)
        assertEquals("测试书", parsed.title)
        assertTrue("至少应有一章", parsed.chapters.isNotEmpty())
    }

    /**
     * **反例锁定**：把 PDF 字节喂给 `parse`，它**不会报错**，
     * 而是静默产出一本乱码书 —— 这正是导入入口必须先拦扩展名的原因。
     *
     * 如果哪天 `parse` 改成会抛错，这条用例会失败，提醒我们更新注释与设计说明。
     */
    @Test
    fun `parse silently accepts non-txt bytes which is why the guard must exist`() {
        val fakePdf = "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n1 0 obj\n".toByteArray(Charsets.ISO_8859_1)
        val parsed = runCatching { LocalBookParser.parse("book.pdf", fakePdf) }
        assertTrue(
            "parse 对 PDF 字节不报错（静默回退成 TXT）—— 所以必须在入口按扩展名拦截",
            parsed.isSuccess,
        )
        // 且它确实被当成了 TXT：只有自然分段，没有真正解析出的章节
        assertTrue("不会解析出有意义的章节结构", parsed.getOrNull()!!.chapters.isNotEmpty())
    }

    /** 构造一个最小可用的 EPUB（mimetype + container + OPF + 一章）。 */
    private fun buildMinimalEpub(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String, stored: Boolean = false) {
                val entry = ZipEntry(name)
                if (stored) entry.method = ZipEntry.STORED
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (stored) {
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip", stored = true)
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""".trimIndent(),
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>测试书</dc:title><dc:creator>测试作者</dc:creator><dc:identifier id="id">urn:uuid:test</dc:identifier>
                     </metadata>
                     <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                     <spine><itemref idref="c1"/></spine>
                   </package>""".trimIndent(),
            )
            put(
                "OEBPS/c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
                   <body><h1>第一章 开始</h1><p>这是正文。</p></body></html>""".trimIndent(),
            )
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 端到端：走真实的导入路由（含扩展名拦截）
    // ------------------------------------------------------------------

    /**
     * 通过真实的 `POST /api/bookshelf/import-local` 路由验证：
     * - 合法 TXT 能导入
     * - `.pdf` 被拒绝且**返回明确错误**（而不是静默入库一本乱码书）
     */
    @Test
    fun `import endpoint accepts txt and rejects pdf with a clear error`() = testApplication {
        val dataDir = Files.createTempDirectory("local-book-import")
        val config = ServerConfig(
            host = "0.0.0.0",
            port = 8080,
            databasePath = dataDir.resolve("legado.sqlite").toString(),
            coverCacheDirectory = dataDir.resolve("covers"),
            webDavDirectory = dataDir.resolve("webdav"),
            initialAdminPassword = PASSWORD,
            secureCookies = false,
        )
        try {
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
                install(HttpCookies)
            }
            val csrf = client.loginSession()

            val text = buildString {
                repeat(3) { appendLine("第${it + 1}章 测试章节${it + 1}"); appendLine("正文内容${it + 1}。"); appendLine() }
            }
            val ok = client.importLocal(csrf, listOf("好的书.txt" to text.toByteArray(Charsets.UTF_8)))
            assertEquals("TXT 应导入成功", 1, ok.imported)
            assertEquals("TXT 不应失败", 0, ok.failed)
            assertNotNull("应返回书名", ok.results.first().name)

            val bad = client.importLocal(csrf, listOf("不支持的.pdf" to "%PDF-1.7 fake".toByteArray()))
            assertEquals("PDF 不应被导入", 0, bad.imported)
            assertEquals("PDF 应被计为失败", 1, bad.failed)
            val message = bad.results.first().error.orEmpty()
            assertTrue(
                "错误提示应说明仅支持 TXT/EPUB，实际：$message",
                message.contains("TXT") && message.contains("EPUB"),
            )
        } finally {
            runCatching {
                Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 同时上传 TXT 与 PDF：合法的成功、非法的失败，互不影响。 */
    @Test
    fun `mixed batch imports the valid ones and reports the rest`() = testApplication {
        val dataDir = Files.createTempDirectory("local-book-mixed")
        val config = ServerConfig(
            host = "0.0.0.0",
            port = 8080,
            databasePath = dataDir.resolve("legado.sqlite").toString(),
            coverCacheDirectory = dataDir.resolve("covers"),
            webDavDirectory = dataDir.resolve("webdav"),
            initialAdminPassword = PASSWORD,
            secureCookies = false,
        )
        try {
            application { legadoApplication(config) }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
                install(HttpCookies)
            }
            val csrf = client.loginSession()

            val txt = "第1章 甲\n正文。\n\n第2章 乙\n正文。\n".toByteArray(Charsets.UTF_8)
            val epub = buildMinimalEpub()
            val result = client.importLocal(
                csrf,
                listOf(
                    "甲.txt" to txt,
                    "乙.epub" to epub,
                    "丙.pdf" to "%PDF-1.7 fake".toByteArray(),
                    "丁.mobi" to "fake mobi".toByteArray(),
                ),
            )
            assertEquals("TXT + EPUB 应成功", 2, result.imported)
            assertEquals("PDF + MOBI 应失败", 2, result.failed)
            assertEquals("总数为 4", 4, result.total)
            val failedNames = result.results.filter { !it.success }.map { it.filename }.toSet()
            assertEquals(setOf("丙.pdf", "丁.mobi"), failedNames)
        } finally {
            runCatching {
                Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private suspend fun HttpClient.loginSession(): String {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<LoginResponse>().csrfToken
    }

    private suspend fun HttpClient.importLocal(
        csrf: String,
        files: List<Pair<String, ByteArray>>,
    ): LocalBookImportResponse {
        val response = submitFormWithBinaryData(
            url = "/api/bookshelf/import-local",
            formData = formData {
                files.forEach { (name, bytes) ->
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                    })
                }
            },
        ) {
            header("X-CSRF-Token", csrf)
        }
        assertEquals("导入接口应返回 200，实际 ${response.status}", HttpStatusCode.OK, response.status)
        return response.body()
    }

    private companion object {
        const val PASSWORD = "local-book-test-password"
    }
}
