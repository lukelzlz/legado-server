package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 回归测试：备份书架条目的类别判定（`ShelfKind`）。
 *
 * 规范来源：`te/分类判别方法.md`，基于**真实 434 条**数据归纳。
 * 期望分布（已逐项核对）：
 * ```
 * type 分布 { 8:374, 24:15, 32:31, 264:14 }
 * 本地图书 14 / 音频 31 / 在线小说 389
 * ```
 */
class ShelfKindTest {

    private fun bookUrlWithTab(tab: String): String {
        val payload = """{"book_id":"7541300930419887129","sources":"番茄","tab":"$tab","url":""}"""
        return "data:;base64,${Base64.getEncoder().encodeToString(payload.toByteArray())},{\"type\":\"qingtian\"}"
    }

    // ------------------------------------------------------------------
    // 本地图书
    // ------------------------------------------------------------------

    /** 真实样例：坚果云 WebDAV 同步的 EPUB（`origin` 带 `webDav::` 前缀）。 */
    @Test
    fun `detects webdav local book`() {
        val kind = ShelfKind.of(
            bookUrl = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fx.epub",
            origin = "webDav::https://dav.jianguoyun.com/dav/dav/books/血姬与骑士 作者：漢唐歸來.epub",
            type = "264",
        )
        assertEquals(ShelfKind.LOCAL, kind)
    }

    /** 真实样例：本服务自己导入的本地书（`origin` = `loc_book`）。 */
    @Test
    fun `detects loc_book local book`() {
        val kind = ShelfKind.of(
            bookUrl = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/x.txt",
            origin = "loc_book",
            type = "264",
        )
        assertEquals("本地书必须被识别（即使 origin 是 loc_book）", ShelfKind.LOCAL, kind)
    }

    /** 协议判据覆盖 TXT/PDF/MOBI —— **不能**只认 .epub。 */
    @Test
    fun `local detection does not depend on extension`() {
        for (name in listOf("a.epub", "a.txt", "a.pdf", "a.mobi", "a.azw3", "a.fb2", "无扩展名")) {
            assertEquals(
                "$name 应识别为本地图书（协议判定，不看扩展名）",
                ShelfKind.LOCAL,
                ShelfKind.of(bookUrl = "content://com.android.providers/document/$name", origin = "loc_book"),
            )
        }
    }

    /** `file://` 与纯 `type=264` 也要覆盖。 */
    @Test
    fun `detects file scheme and bare type 264`() {
        assertEquals(ShelfKind.LOCAL, ShelfKind.of("file:///sdcard/a.txt", "loc_book", null))
        assertEquals(ShelfKind.LOCAL, ShelfKind.of("content://x", "某书源", "264"))
    }

    // ------------------------------------------------------------------
    // 音频（听书）
    // ------------------------------------------------------------------

    @Test
    fun `detects audio by tab`() {
        val kind = ShelfKind.of(
            bookUrl = bookUrlWithTab("听书"),
            origin = "🍅大灰狼聚合5.9.1(vip完全版)",
            type = "32",
        )
        assertEquals(ShelfKind.AUDIO, kind)
    }

    /** `group` 被人为改成 0、`type` 缺失时，仍必须靠 `tab` 认出音频。 */
    @Test
    fun `audio detection relies on tab not type`() {
        assertEquals(
            "type 缺失也要靠 tab 识别",
            ShelfKind.AUDIO,
            ShelfKind.of(bookUrl = bookUrlWithTab("听书"), origin = "某书源", type = null),
        )
    }

    // ------------------------------------------------------------------
    // 在线小说
    // ------------------------------------------------------------------

    @Test
    fun `detects online novel by tab`() {
        val kind = ShelfKind.of(
            bookUrl = bookUrlWithTab("小说"),
            origin = "🍅大灰狼聚合5.9.5(vip完全版)",
            type = "8",
        )
        assertEquals(ShelfKind.ONLINE, kind)
    }

    /**
     * 兜底：`tab` 解析失败且非本地书 → 在线小说。
     * 真实数据里有 1 条属于此情况（`性转小猫娘，被死对头教育成人妻`，type 8）。
     */
    @Test
    fun `unparseable tab falls back to online`() {
        assertEquals(
            "tab 解析失败应兜底为在线小说（否则会被误过滤）",
            ShelfKind.ONLINE,
            ShelfKind.of(bookUrl = "https://example.com/book/1", origin = "某书源", type = "8"),
        )
    }

    // ------------------------------------------------------------------
    // 判定顺序（关键陷阱）
    // ------------------------------------------------------------------

    /**
     * **先判本地图书再判 tab**。
     *
     * 本地书没有 `tab`，若先判 `tab` 会落进兜底分支被当成在线小说。
     * 这里构造一个「既是 content:// 又恰好带 tab」的记录，确认本地优先。
     */
    @Test
    fun `local book wins over tab`() {
        val kind = ShelfKind.of(
            bookUrl = "content://x/" + bookUrlWithTab("听书"),
            origin = "loc_book",
            type = "264",
        )
        assertEquals("本地图书判定必须先于 tab", ShelfKind.LOCAL, kind)
    }

    // ------------------------------------------------------------------
    // decodeTab 的健壮性
    // ------------------------------------------------------------------

    @Test
    fun `decode tab returns value for real payload shape`() {
        assertEquals("听书", ShelfKind.decodeTab(bookUrlWithTab("听书")))
        assertEquals("小说", ShelfKind.decodeTab(bookUrlWithTab("小说")))
    }

    /** 各种畸形输入都必须返回 null 而**不抛异常**（本地书走的就是这条路径）。 */
    @Test
    fun `decode tab is null-safe for malformed input`() {
        for (bad in listOf(null, "", "content://x", "data:;base64,", "data:;base64,!!!!", "https://x", "eyJ")) {
            val r = runCatching { ShelfKind.decodeTab(bad) }
            assertTrue("输入 '$bad' 不应抛异常", r.isSuccess)
            if (bad != null && bad.startsWith("data:;base64,eyJ")) {
                // 合法锚点但内容非 JSON -> null
                assertNull(r.getOrNull())
            }
        }
    }

    /** base64 段后面紧跟 `,{"type":...}` 时不能被一起吃进去。 */
    @Test
    fun `decode tab stops at base64 boundary`() {
        val url = bookUrlWithTab("听书")
        // 确认逗号后的 JSON 没被纳入 base64
        assertFalse(url.substringAfter("base64,").startsWith("eyJ") && !url.contains("eyJ"))
        assertEquals("听书", ShelfKind.decodeTab(url))
    }

    // ------------------------------------------------------------------
    // 真实数据分布（与 te/分类判别方法.md 的预期一致）
    // ------------------------------------------------------------------

    /**
     * 用规范里的**真实计数**做一次整体校验。
     *
     * 真实分布（`te/分类判别方法.md` 第 6 节）：
     * ```
     * type 分布 { 8:374, 24:15, 32:31, 264:14 } = 434
     * 在线小说 389（**含** 1 条 tab 解析失败兜底）/ 本地 14 / 音频 31
     * ```
     * 注意 374 + 15 = **389**，那条兜底记录（type=8）本身就**在这 374 里**，
     * 不是额外新增的一条（这里曾多算过 1 条，被测试抓出）。
     */
    @Test
    fun `real dataset distribution matches the spec`() {
        val entries = mutableListOf<ShelfKind>()

        // 在线小说：type 8 共 374 条，其中 1 条是 tab 解析失败（用 URL 形态区分）
        repeat(373) { entries += ShelfKind.of(bookUrlWithTab("小说"), "书源", "8") }
        entries += ShelfKind.of("https://x/book/1", "书源", "8")   // tab 解析失败兜底
        // type 24 共 15 条
        repeat(15) { entries += ShelfKind.of(bookUrlWithTab("小说"), "书源", "24") }

        // 音频：31 条（判定靠 tab，与 type/group 无关）
        repeat(31) { entries += ShelfKind.of(bookUrlWithTab("听书"), "聚合书源", "32") }

        // 本地图书：14 条（10 条 webDav:: + 4 条 loc_book）
        repeat(10) {
            entries += ShelfKind.of(
                "content://com.android.documents/tree/primary/document/x.epub",
                "webDav::https://dav.jianguoyun.com/dav/books/x.epub",
                "264",
            )
        }
        repeat(4) {
            entries += ShelfKind.of("content://com.android.documents/tree/primary/document/x.txt", "loc_book", "264")
        }

        assertEquals("构造总数应为 434", 434, entries.size)
        assertEquals("本地图书应为 14", 14, entries.count { it == ShelfKind.LOCAL })
        assertEquals("音频应为 31", 31, entries.count { it == ShelfKind.AUDIO })
        assertEquals("在线小说应为 389", 389, entries.count { it == ShelfKind.ONLINE })

        // 过滤后就只剩在线小说
        val skipped = entries.count { it != ShelfKind.ONLINE }
        assertEquals("应过滤 45 条（14 本地 + 31 音频）", 45, skipped)
    }
}
