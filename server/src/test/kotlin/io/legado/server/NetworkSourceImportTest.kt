package io.legado.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * 网络书源导入：预览判定、票据生命周期与落库。
 *
 * 绝大部分用例走**注入式 `fetcher` 接缝**（不触网）：网络 I/O 不该出现在单测里。
 * SSRF 与协议校验用「不注入 fetcher」的用例覆盖 —— 那条路径在发出请求**之前**就会拒绝。
 */
class NetworkSourceImportTest {

    /** 每个用例一个独立临时库；**必须 close 后再删**，否则 Windows 下 WAL 占用会抛 FileSystemException。 */
    private fun withDatabase(block: (Database) -> Unit) {
        val tempDb = Files.createTempFile("legado-netimport", ".sqlite").toString()
        var db: Database? = null
        try {
            db = Database(tempDb)
            db.initialize("test-password-1234")
            block(db)
        } finally {
            db?.close()
            listOf(tempDb, "$tempDb-wal", "$tempDb-shm").forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
        }
    }

    private fun service(db: Database, payload: String) = NetworkSourceImport(db, fetcher = { payload })

    @Test
    fun `preview marks unknown sources as new and imports nothing by itself`() = withDatabase { db ->
        val target = service(
            db,
            """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"},{"bookSourceUrl":"https://b.example.com","bookSourceName":"B"}]""",
        )
        val preview = runBlocking { target.preview("https://example.com/list.json") }

        assertEquals(2, preview.total)
        assertEquals(2, preview.newCount)
        assertEquals(0, preview.updateCount)
        assertEquals(0, preview.invalidCount)
        assertTrue(preview.sources.all { it.status == NetworkSourceImport.STATUS_NEW })
        assertEquals("https://a.example.com", preview.sources[0].url)
        // 预览是只读的：绝不能顺手落库
        assertEquals(0, db.listSources(null).size)
    }

    @Test
    fun `preview marks an existing source as update when the url matches`() = withDatabase { db ->
        db.importSources(listOf("""{"bookSourceUrl":"https://a.example.com","bookSourceName":"旧名字"}"""))
        val target = service(db, """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"新名字"}]""")

        val preview = runBlocking { target.preview("https://example.com/list.json") }
        assertEquals(1, preview.updateCount)
        assertEquals(0, preview.newCount)
        assertEquals(NetworkSourceImport.STATUS_UPDATE, preview.sources.single().status)

        val result = runBlocking { target.commit(preview.token, listOf(0), null) }
        assertEquals(0, result.imported)
        assertEquals(1, result.updated)
        assertEquals("新名字", db.listSources(null).single().name)
    }

    @Test
    fun `name fallback rewrites the url so commit updates instead of inserting a duplicate`() = withDatabase { db ->
        // 真实场景：现有源用的是自定义中文 id（Legado 生态允许），而下载回来的书源 URL 是普通域名。
        db.importSources(listOf("""{"bookSourceUrl":"大灰狼融合VIP5.0","bookSourceName":"🍅大灰狼聚合5.9.30(vip完全版)"}"""))
        val target = service(db, """[{"bookSourceUrl":"https://www.langge.uk","bookSourceName":"🍅大灰狼聚合5.9.30(vip完全版)"}]""")

        val preview = runBlocking { target.preview("https://example.com/list.json") }
        assertEquals("名称兜底必须判为更新", 1, preview.updateCount)

        val result = runBlocking { target.commit(preview.token, listOf(0), null) }
        assertEquals(0, result.imported)
        assertEquals(1, result.updated)
        // 关键：不能因为 URL 不同就多插一条重复源
        assertEquals(1, db.listSources(null).size)
        assertEquals("大灰狼融合VIP5.0", db.listSources(null).single().id)
    }

    @Test
    fun `preview marks unparsable entries as invalid with a reason`() = withDatabase { db ->
        val oversized = """{"bookSourceUrl":"https://big.example.com","bookSourceName":"超大","padding":"${"x".repeat(1024 * 1024 + 10)}"}"""
        val payload = """[{"bookSourceUrl":"https://ok.example.com","bookSourceName":"正常"},{"bookSourceName":"缺URL"},$oversized]"""
        val target = service(db, payload)

        val preview = runBlocking { target.preview("https://example.com/list.json") }
        assertEquals(3, preview.total)
        assertEquals(1, preview.newCount)
        assertEquals(2, preview.invalidCount)
        assertEquals(NetworkSourceImport.STATUS_INVALID, preview.sources[1].status)
        assertTrue("必须给出原因", preview.sources[1].reason!!.contains("bookSourceUrl"))
        assertTrue("超限项也要给出原因", preview.sources[2].reason!!.contains("1 MiB"))

        // 只勾选不合格项 ⇒ 明确报「没选中可导入的」，而不是静默成功
        val error = assertThrows(NetworkImportException::class.java) {
            runBlocking { target.commit(preview.token, listOf(1, 2), null) }
        }
        assertEquals("import_selection_empty", error.code)
        assertEquals(0, db.listSources(null).size)
    }

    @Test
    fun `commit imports only the selected subset`() = withDatabase { db ->
        val target = service(
            db,
            """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"},{"bookSourceUrl":"https://b.example.com","bookSourceName":"B"}]""",
        )
        val preview = runBlocking { target.preview("https://example.com/list.json") }
        val result = runBlocking { target.commit(preview.token, listOf(1), null) }

        assertEquals(1, result.imported)
        assertEquals(listOf("https://b.example.com"), db.listSources(null).map { it.id })
    }

    @Test
    fun `commit applies the chosen group and never clears an existing one`() = withDatabase { db ->
        val payloadA = """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]"""
        val payloadB = """[{"bookSourceUrl":"https://b.example.com","bookSourceName":"B"}]"""

        // ① 不选分组 ⇒ 新源落「未分组」
        val first = service(db, payloadA)
        val p1 = runBlocking { first.preview("https://example.com/a.json") }
        runBlocking { first.commit(p1.token, listOf(0), null) }
        assertNull(db.listSources(null).first { it.id == "https://a.example.com" }.group)

        // ② 选了分组 ⇒ 套用该分组
        val second = service(db, payloadB)
        val p2 = runBlocking { second.preview("https://example.com/b.json") }
        runBlocking { second.commit(p2.token, listOf(0), "网络导入") }
        assertEquals("网络导入", db.listSources(null).first { it.id == "https://b.example.com" }.group)

        // ③ 已有分组的源再次导入且不选分组 ⇒ 分组必须保留（不能被悄悄清空）
        val third = service(db, payloadB)
        val p3 = runBlocking { third.preview("https://example.com/b.json") }
        runBlocking { third.commit(p3.token, listOf(0), null) }
        assertEquals("网络导入", db.listSources(null).first { it.id == "https://b.example.com" }.group)
    }

    @Test
    fun `commit rejects unknown and already consumed tickets`() = withDatabase { db ->
        val target = service(db, """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]""")

        val unknown = assertThrows(NetworkImportException::class.java) {
            runBlocking { target.commit("not-a-real-token", listOf(0), null) }
        }
        assertEquals("import_ticket_expired", unknown.code)

        val preview = runBlocking { target.preview("https://example.com/a.json") }
        runBlocking { target.commit(preview.token, listOf(0), null) }
        // 一次性票据：用完即失效，不能重放
        val reused = assertThrows(NetworkImportException::class.java) {
            runBlocking { target.commit(preview.token, listOf(0), null) }
        }
        assertEquals("import_ticket_expired", reused.code)
    }

    @Test
    fun `preview accepts wrapped collection payloads`() = withDatabase { db ->
        for (payload in listOf(
            """{"data":[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]}""",
            """{"sources":[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]}""",
            """{"bookSources":[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]}""",
            "\uFEFF[{\"bookSourceUrl\":\"https://a.example.com\",\"bookSourceName\":\"A\"}]",
        )) {
            assertEquals(1, runBlocking { service(db, payload).preview("https://example.com/a.json") }.newCount)
        }
    }

    @Test
    fun `preview reports non JSON and empty collections clearly`() = withDatabase { db ->
        val html = assertThrows(NetworkImportException::class.java) {
            runBlocking { service(db, "<html><body>404</body></html>").preview("https://example.com/a.json") }
        }
        assertEquals("import_content_invalid", html.code)

        val empty = assertThrows(NetworkImportException::class.java) {
            runBlocking { service(db, "[]").preview("https://example.com/a.json") }
        }
        assertEquals("import_empty", empty.code)
    }

    @Test
    fun `readBounded aborts once the body exceeds the limit`() = withDatabase { db ->
        val target = NetworkSourceImport(db)
        val under = target.run { ByteArrayInputStream(ByteArray(1024)).readBounded(NetworkSourceImport.MAX_BYTES) }
        assertEquals(1024, under.size)

        val over = assertThrows(NetworkImportException::class.java) {
            target.run { ByteArrayInputStream(ByteArray(NetworkSourceImport.MAX_BYTES + 1)).readBounded(NetworkSourceImport.MAX_BYTES) }
        }
        assertEquals("import_payload_too_large", over.code)
    }

    @Test
    fun `preview rejects empty non http and private targets without touching the network`() = withDatabase { db ->
        // 不注入 fetcher ⇒ 走真实的校验路径（在发起请求之前就会拒绝）
        val target = NetworkSourceImport(db)

        assertEquals(
            "import_url_required",
            assertThrows(NetworkImportException::class.java) { runBlocking { target.preview("   ") } }.code,
        )
        assertEquals(
            "import_url_scheme",
            assertThrows(NetworkImportException::class.java) { runBlocking { target.preview("ftp://example.com/a.json") } }.code,
        )
        assertEquals(
            "import_url_scheme",
            assertThrows(NetworkImportException::class.java) { runBlocking { target.preview("yuedu://booksource/importonline?src=x") } }.code,
        )
        for (blocked in listOf(
            "http://127.0.0.1:8080/a.json",
            "http://localhost/a.json",
            "http://169.254.169.254/latest/meta-data",
        )) {
            assertEquals(
                blocked,
                "import_target_blocked",
                assertThrows(NetworkImportException::class.java) { runBlocking { target.preview(blocked) } }.code,
            )
        }
    }

    @Test
    fun `preview ticket cache is bounded`() = withDatabase { db ->
        val target = service(db, """[{"bookSourceUrl":"https://a.example.com","bookSourceName":"A"}]""")
        repeat(12) { runBlocking { target.preview("https://example.com/$it.json") } }
        assertTrue(
            "票据数量必须有上限，否则反复调用 preview 会把服务端内存撑爆",
            target.ticketCount() <= 8,
        )
    }
}
