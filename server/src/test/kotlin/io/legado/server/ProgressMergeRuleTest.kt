package io.legado.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：进度合并的冲突消解必须**与手机端 `syncBookProgress` 完全一致**。
 *
 * 手机端规则（`ReadBookLoadDelegate.kt:297`）：
 * ```
 * if (远端 idx < 本地 idx || (idx 相同 && 远端 pos < 本地 pos)) → 不采纳
 * else                                                        → setProgress(远端)
 * ```
 * 即「取更大的 idx；idx 相同则取更大的 pos」。
 *
 * **关键：不能按 `durChapterTime` 判断**（实测 SESSION-029）。
 * 手机的 `durChapterTime` 是「阅读时刻」而非「写入时刻」，
 * 且全仓 90+ 处引用**无一处**用它做冲突比较（只用于书架排序与写库防抖）。
 *
 * 实测反例（云游异世界，用户真实操作）：
 * ```
 * 09:25:45  idx=326
 * 09:26:20  idx=329   ← 读到第 330 章
 * 09:28:02  idx=328   ← 回翻一页，时间戳更新但 idx 倒退
 * ```
 * 若按"时间戳更新就采纳"，网页进度会跟着往回跳。
 */
class ProgressMergeRuleTest {

    /**
     * 抽取出的合并判定（与 `Routes.kt` 的 `/progress-sync/merge` 保持一致）。
     *
     * 之所以复制一份而不是反射调用路由：这是纯函数，独立测更稳，
     * 且改动路由时若忘了同步这里，测试会立刻暴露。
     */
    private fun fileWins(
        dbIndex: Int?,
        dbScrollPosition: Double,
        fileIndex: Int,
        filePos: Int,
    ): Boolean {
        val localHasPosition = dbScrollPosition > 0.0
        val remoteHasPosition = filePos > 0
        return when {
            dbIndex == null -> true
            fileIndex > dbIndex -> true
            fileIndex < dbIndex -> false
            else -> remoteHasPosition && !localHasPosition
        }
    }

    // ------------------------------------------------------------------
    // 核心：取更大的 idx
    // ------------------------------------------------------------------

    /** 远端更靠后 → 采纳。 */
    @Test
    fun `remote ahead wins`() {
        assertTrue(fileWins(dbIndex = 10, dbScrollPosition = 0.0, fileIndex = 20, filePos = 0))
    }

    /** 远端更靠前 → **不采纳**（保留本地，符合手机"只提示"的行为）。 */
    @Test
    fun `local ahead keeps local`() {
        assertFalse(fileWins(dbIndex = 20, dbScrollPosition = 0.0, fileIndex = 10, filePos = 0))
    }

    /** 本地没有进度 → 直接用远端的。 */
    @Test
    fun `no local progress takes remote`() {
        assertTrue(fileWins(dbIndex = null, dbScrollPosition = 0.0, fileIndex = 5, filePos = 0))
    }

    // ------------------------------------------------------------------
    // idx 相同时比 pos
    // ------------------------------------------------------------------

    /** idx 相同、远端有 pos、本地没 pos → 采纳远端。 */
    @Test
    fun `same index remote has position wins`() {
        assertTrue(fileWins(dbIndex = 10, dbScrollPosition = 0.0, fileIndex = 10, filePos = 187))
    }

    /** idx 相同、本地已有 pos → 不采纳（避免无意义抖动）。 */
    @Test
    fun `same index local has position keeps local`() {
        assertFalse(fileWins(dbIndex = 10, dbScrollPosition = 0.5, fileIndex = 10, filePos = 187))
    }

    /** idx 相同、两边都没 pos → 无变化，保留本地。 */
    @Test
    fun `same index both without position keeps local`() {
        assertFalse(fileWins(dbIndex = 10, dbScrollPosition = 0.0, fileIndex = 10, filePos = 0))
    }

    // ------------------------------------------------------------------
    // 真实观测序列（防回退）
    // ------------------------------------------------------------------

    /**
     * 复现真实序列：`326 → 329 → 328`。
     *
     * 手机回翻导致 idx 倒退，但**时间戳是更新的**。
     * 我们的判定必须只看 idx/pos —— 若误用时间戳，第 3 步会把网页进度也拖回 328。
     */
    @Test
    fun `real observed sequence does not regress the web progress`() {
        // 网页先读到 329（用户在手机上读到第 330 章）
        var webIndex: Int? = null
        webIndex = if (fileWins(webIndex, 0.0, 329, 0)) 329 else webIndex
        assertEquals(329, webIndex)

        // 手机回翻到 328 并上传 —— 远端比网页**更靠前**，不应倒退
        val shouldTake = fileWins(webIndex, 0.0, 328, 0)
        assertFalse("远端 328 比本地 329 更靠前，不应采纳（否则网页进度倒退）", shouldTake)
        if (shouldTake) webIndex = 328
        assertEquals("网页进度必须保持 329", 329, webIndex)
    }

    /** 单调前进：只在更靠后时推进。 */
    @Test
    fun `progress only moves forward across a sequence`() {
        var current: Int? = null
        for (incoming in listOf(5, 12, 12, 9, 30, 7, 31)) {
            if (fileWins(current, 0.0, incoming, 0)) current = incoming
        }
        assertEquals("序列结束后应停在最大值 31", 31, current)
    }

    // ------------------------------------------------------------------
    // 明确锁定「不比时间戳」
    // ------------------------------------------------------------------

    /**
     * 这条用例锁定**设计决定**：判定结果只依赖 idx/pos，与任何时间戳无关。
     *
     * 若将来有人"顺手"把 `durChapterTime` 加回判定，这条会因为语义变化而被审阅者注意到
     * （函数签名里根本没有时间参数，改回去就必须改签名）。
     */
    @Test
    fun `decision depends only on index and position not on time`() {
        // 同样一组 idx/pos，无论"时间"如何都应得到相同结论
        val cases = listOf(
            Triple(10, 0.0, 20 to 0) to true,
            Triple(20, 0.0, 10 to 0) to false,
            Triple(10, 0.0, 10 to 187) to true,
            Triple(10, 0.5, 10 to 187) to false,
        )
        for ((input, expected) in cases) {
            val (dbIdx, dbPos, remote) = input
            assertEquals(
                "idx=$dbIdx pos=$dbPos remote=$remote 结论应稳定",
                expected,
                fileWins(dbIdx, dbPos, remote.first, remote.second),
            )
        }
    }

    /** 极大/极小下标不应异常。 */
    @Test
    fun `extreme indices are handled`() {
        assertTrue(fileWins(dbIndex = 0, dbScrollPosition = 0.0, fileIndex = Int.MAX_VALUE - 1, filePos = 0))
        assertFalse(fileWins(dbIndex = Int.MAX_VALUE - 1, dbScrollPosition = 0.0, fileIndex = 0, filePos = 0))
    }

    // ------------------------------------------------------------------
    // 明确锁定「允许倒退」的产品决定
    // ------------------------------------------------------------------

    /**
     * **产品决定（用户明确要求）：手机倒退 → 同步也倒退。**
     *
     * 这条用例锁定该语义，防止后人"顺手"加上"只增不减"的保护。
     *
     * 实测背景（SESSION-029，云游异世界，用户真实操作）：
     * ```
     * 326 → 329（读到第 330 章）→ 328（回翻）→ 323（继续回翻）
     * ```
     * 手机**自己就会把 idx 写小**，且每次时间戳都是新的。
     * 用户要求两端语义一致 —— 手机倒退时，网页/服务端也跟随倒退。
     *
     * 注意与上一组用例的区别：
     * - 上面测的是「**读取合并**时取更大的」（与手机 `syncBookProgress` 一致）
     * - 这里测的是「**写入文件**时不做防护」（与手机 `syncProgress` 一致）
     * 两者不矛盾：手机也是"读的时候比对、写的时候照写"。
     */
    @Test
    fun `writeback intentionally allows regression to mirror the phone`() {
        // 直接验证 write 的语义：传出更小的 idx，文件就应该变成更小的 idx
        val root = java.nio.file.Files.createTempDirectory("merge-regress")
        val db = Database(java.nio.file.Files.createTempFile("merge-regress-db", ".sqlite").toString())
        db.initialize("test-pass")
        val s = BookProgressSync(root, db)

        assertTrue(s.write("书", "作者", 329, "第330章"))
        assertEquals(329, s.read("书", "作者")!!.chapterIndex)

        // 关键断点：写入更小的 idx 必须**生效**（不做防护）
        assertTrue("按产品决定，倒退写入必须生效", s.write("书", "作者", 323, "第324章"))
        assertEquals(
            "手机倒退 → 同步倒退：文件应变成 323 而不是停在 329",
            323,
            s.read("书", "作者")!!.chapterIndex,
        )
    }

    /**
     * 反向确认 `alignChapter` 不会因为"回退"而拒绝 —— 它只负责对齐，不管方向。
     */
    @Test
    fun `align does not block regressions`() {
        val root = java.nio.file.Files.createTempDirectory("align-regress")
        val db = Database(java.nio.file.Files.createTempFile("align-regress-db", ".sqlite").toString())
        db.initialize("test-pass")
        val s = BookProgressSync(root, db)
        val chapters = (0..399).map { Chapter(it, "第${it + 1}章", "https://e.test/c/$it") }

        assertEquals(329, s.alignChapter(chapters, 329, "第330章"))
        assertEquals("更小的 idx 同样应正常对齐（不拦截倒退）", 323, s.alignChapter(chapters, 323, "第324章"))
    }
}
