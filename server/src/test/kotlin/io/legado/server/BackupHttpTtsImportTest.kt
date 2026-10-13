package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份导入：`httpTTS.json`（HTTP TTS / 自定义朗读）必须真的进库。
 *
 * 背景（实测 2026-10-03）：`readSection` 只把**包内条目名**转小写、却拿 `fileName` 原样比较，
 * 而真实备份里是 `httpTTS.json`。调用方传 `"httpTTS.json"` 时
 * `"httptts.json" == "httpTTS.json"` 为假 ⇒ 静默返回 null ⇒ **这一整段没导入且毫无报错**，
 * 用户看到的就是「TTS 没给导入」。本用例锁死这条路径：
 * ① 字段名按真实备份逐字给出；② 断言导入后 `listHttpTts()` 里确实有它。
 */
class BackupHttpTtsImportTest {

    private val shelfJson = """
        [{"name":"测试书","origin":"大灰狼融合VIP5.0","bookUrl":"data:;base64,aa","tocUrl":"data:;base64,aa"}]
    """.trimIndent()

    /** 与真实备份 `httpTTS.json` 同形（含 `url` 里「地址 + 逗号 + JSON 选项」的实形态）。 */
    private val httpTtsJson = """
        [{"concurrentRate":"0","contentType":"audio/mpeg","enabledCookieJar":false,"header":"",
          "id":-29,"lastUpdateTime":1759716268087,"loginCheckJs":"","loginUi":"","loginUrl":"",
          "name":"1.百度",
          "url":"http://tts.baidu.com/text2audio,{\"method\":\"POST\",\"body\":\"tex={{java.encodeURI(speakText)}}\"}"},
         {"concurrentRate":"","contentType":"","enabledCookieJar":false,"header":"","id":1773496250425,
          "lastUpdateTime":1773496250425,"loginCheckJs":"","loginUi":"","loginUrl":"","name":"","url":""}]
    """.trimIndent()

    private fun zipWith(shelf: String, tts: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 条目名用**真实备份的大小写**（httpTTS.json），这正是当初踩坑的地方
            zip.putNextEntry(ZipEntry("bookshelf.json")); zip.write(shelf.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("httpTTS.json")); zip.write(tts.toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `httpTTS 段必须真的导入进库`() {
        val dbPath = Files.createTempFile("legado-httptts", ".sqlite").toString()
        val zipPath = Files.createTempFile("legado-httptts", ".zip")
        try {
            Files.write(zipPath, zipWith(shelfJson, httpTtsJson))
            val database = Database(dbPath)
            database.initialize("password-for-test")
            BackupImporter(database).import(java.nio.file.Path.of(zipPath.toString()))

            val list = database.listHttpTts()
            // 空 url 的那条按「未设置」跳过，故只剩 1 条
            assertEquals(1, list.size)
            val tts = list.single()
            assertEquals(-29L, tts.id)
            assertEquals("1.百度", tts.name)
            assertEquals("audio/mpeg", tts.contentType)
            assertEquals("0", tts.concurrentRate)
            assertEquals(1759716268087L, tts.lastUpdateTime)
            assertEquals(true, tts.url.startsWith("http://tts.baidu.com/text2audio,"))
            // url 后的 JSON 选项必须原样保留（含换行/引号在内的整串）
            assertEquals(true, tts.url.contains("\"method\":\"POST\""))
            database.close()
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(zipPath.toString())) }
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(dbPath)) }
            runCatching { Files.deleteIfExists(java.nio.file.Path.of("$dbPath-wal")) }
            runCatching { Files.deleteIfExists(java.nio.file.Path.of("$dbPath-shm")) }
        }
    }
}
