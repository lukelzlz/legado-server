package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * 书籍类型判定：[BookKind.resolveOrNull] 的优先序。
 *
 * ## 这个用例是为一个**真实 bug** 立的
 * 用户 2026-10-15 实测反馈：**「不要看源里的声明，有的源里没声明」**。
 * 当时加入书架的判定是 `BookKind.fromSourcePayload(源)`，于是聚合源（`大灰狼融合VIP5.0`，
 * 根本不声明 `bookSourceType`，但里面有真实的漫画频道）取到的漫画被**强制判成小说** ⇒
 * 前端进文字阅读器 ⇒ 用户看到的就是「漫画打不开」（而且是他亲口说的"取消漫画跳过"）。
 *
 * 正确优先序：**书自己的 `tab` > 书源声明**，都没有 ⇒ 不写库（按小说处理）。
 */
class BookKindResolveTest {

    /** 书自身的 base64 载荷（Legado 把 `tab` 放在 bookUrl 里）。 */
    private fun bookUrlWithTab(tab: String?): String {
        val json = if (tab == null) """{"book_id":"1"}""" else """{"book_id":"1","tab":"$tab"}"""
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return "data:;base64,$payload"
    }

    /** 聚合源：**没有** `bookSourceType` 字段（真实数据就是这样）。 */
    private val aggregateSource = """{"bookSourceUrl":"大灰狼融合VIP5.0","bookSourceName":"🍅大灰狼聚合5.9.30"}"""
    private val comicSource = """{"bookSourceUrl":"https://cn.webmota.com","bookSourceType":2}"""
    private val audioSource = """{"bookSourceUrl":"https://audio.example.com","bookSourceType":1}"""

    @Test
    fun `book's own tab decides even when the source declares nothing`() {
        // = 用户实测的那个 bug：聚合源 + tab=漫画 ⇒ 必须是 comic，不能是 novel
        assertEquals("comic", BookKind.resolveOrNull(null, bookUrlWithTab("漫画"), aggregateSource))
        // 书自己说是小说、源也不声明 ⇒ 谁都没指定 ⇒ null（不写库）
        assertNull(BookKind.resolveOrNull(null, bookUrlWithTab("小说"), aggregateSource))
    }

    @Test
    fun `book's own tab wins over a contradicting source declaration`() {
        // 源声明是文本(0)，但书自己说漫画 ⇒ 以书为准（源声明常常滞后/缺失）
        assertEquals("comic", BookKind.resolveOrNull(null, bookUrlWithTab("漫画"), """{"bookSourceType":0}"""))
    }

    @Test
    fun `falls back to the source declaration`() {
        assertEquals("comic", BookKind.resolveOrNull(null, bookUrlWithTab(null), comicSource))
        assertEquals("audio", BookKind.resolveOrNull(null, bookUrlWithTab(null), audioSource))
        // 书自己没说、源也没声明 ⇒ 不猜（调用方决定缺省）
        assertNull(BookKind.resolveOrNull(null, bookUrlWithTab(null), aggregateSource))
    }

    @Test
    fun `explicit caller request has the highest priority`() {
        assertEquals("comic", BookKind.resolveOrNull("comic", bookUrlWithTab("小说"), aggregateSource))
        assertEquals("audio", BookKind.resolveOrNull("audio", null, null))
        assertEquals("novel", BookKind.resolve("novel", bookUrlWithTab("漫画"), comicSource))
        // 非法值忽略，继续走后续判据
        assertEquals("comic", BookKind.resolveOrNull("乱写", bookUrlWithTab("漫画"), aggregateSource))
    }

    @Test
    fun `resolve defaults to novel while resolveOrNull stays empty`() {
        assertEquals("novel", BookKind.resolve(null, bookUrlWithTab(null), aggregateSource))
        assertNull(BookKind.resolveOrNull(null, bookUrlWithTab(null), aggregateSource))
    }

    @Test
    fun `audio tab is recognised`() {
        assertEquals("audio", BookKind.resolveOrNull(null, bookUrlWithTab("听书"), aggregateSource))
    }
}
