package io.legado.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Legado App 备份包（`backup<日期>-<设备名>.zip`）**导出器**。
 *
 * ## 导出范围：**固定 7 个文件**
 *
 * | 条目 | 承载内容 |
 * | :--- | :--- |
 * | `bookSource.json` | **书源及其分组** —— 分组是书源自带的 `bookSourceGroup` 字段，**不单独成文件** |
 * | `bookGroup.json` | **书籍分组**（书架分组） |
 * | `bookshelf.json` | **书架 + 阅读进度**（`durChapterIndex` / `durChapterTime` 等就在条目里） |
 * | `bookmark.json` | **书签 / 阅读记录** |
 * | `replaceRule.json` | **替换净化规则** |
 * | `httpTTS.json` | **自定义 HTTP TTS** |
 * | `rssSources.json` | **RSS 订阅源**（字段名逐字对齐，见 [rssSourceEntry]） |
 *
 * 这 7 个文件是**必须**的：少任何一个，导入端（Legado App 或本服务的 [BackupImporter]）
 * 都会缺一块数据。因此即使某一项为空，也会写出**空数组 `[]`**，而不是省略条目 ——
 * 「文件齐备」是格式契约的一部分。
 *
 * 真实 Legado 备份其实有 **19 个条目**（`backup2026-09-30-PEPM00.zip` 实测：bookshelf / bookmark /
 * bookGroup / bookSource / rssSources / replaceRule / readRecord / readRecordDetail /
 * readRecordSession / searchHistory / txtTocRule / httpTTS / keyboardAssists / dictRule /
 * servers / readConfig / shareReadConfig / themeConfig / config.xml）。其余 12 项
 * （阅读统计、字典、主题、键盘辅助…）**刻意不导出**：本服务要么没有该能力，
 * 要么本次需求不需要。字段与排版仍与真实备份逐字对齐，因此导出包可被正常导入。
 *
 * 导出条目随功能增加而增长（最初 4 个 → 补替换规则/TTS 后 6 个 → 补 RSS 后 7 个）。
 * **改动条目清单时务必全仓搜索「恰好 N 个文件」一类断言**：
 * `BackupExporterTest` 与 `WebDavRoutesTest` 各有一份（CI 红过一次，见交接文档第六节）。
 *
 * ## 格式契约（两个真实备份实测一致）
 *
 * | 条目 | 顶层 | 键数 |
 * | :--- | :--- | ---: |
 * | `bookSource.json` | Array | 26（**原样回放**数据库里的 payload） |
 * | `bookGroup.json` | Array | 6（`bookSort / enableRefresh / groupId / groupName / order / show`） |
 * | `bookshelf.json` | Array | 28（见 [shelfEntry]） |
 * | `bookmark.json` | Array | 8（`bookAuthor / bookName / bookText / chapterIndex / chapterName / chapterPos / content / time`） |
 *
 * 三条容易踩错、已实测确认的细节：
 *
 * 1. **条目顺序按名称（大小写不敏感）升序**（`bookGroup` → `bookmark` → `bookshelf` → `bookSource`），
 *    与真实备份一致。注意默认的 String 比较是 ASCII 序，会得到错误顺序（见 [export] 内注释）。
 * 2. **排版是 2 空格缩进 + `": "` + LF + 末尾无换行**（Gson `setPrettyPrinting` 的形态，
 *    也是 Legado 安卓端的产出）。kotlinx 的 `prettyPrint` 是 **4 空格**，直接用会不一致，
 *    故这里手写 [writeJson] 精确控制；同时**不转义 `< > &`**（真实备份里
 *    `"exploreUrl": "<js>…"` 就是原样尖括号，Gson 需 `disableHtmlEscaping` 才如此）。
 * 3. **条目内的空可选字段直接省略**，不写 `null` 也不写空串 —— 实测真实备份里
 *    `customCoverUrl` 只出现 3/220 与 33/378 次、`intro` 377/378 次，即 Gson 省略空值的行为。
 *    因此本导出器只写「服务端确实有值」的字段，不凭空补默认值。
 *    （注意与第 1 条的区别：省略的是**字段**，不是**文件**。）
 *
 * ## 已知有损字段（服务端没有对应能力，**刻意不编造**）
 *
 * | 字段 | 说明 |
 * | :--- | :--- |
 * | `durChapterPos` | 服务端存的是**百分比** `scroll_position`，Legado 存的是**字符偏移**，语义不同 ⇒ 写 0（与 [BackupImporter] 注释同源） |
 * | `intro` / `latestChapterTitle` / `variable` / `readConfig` / `wordCount` | 服务端无这些数据 ⇒ 省略 |
 * | `kind` | 真实备份是 `分类,评分,状态,来源` 四段；服务端只知道「是否读完」⇒ 只写状态词（`连载中` / `已完结`），而 [BackupImporter] 判完成正是 `kind.contains("完结")`，往返语义不失真 |
 */
class BackupExporter(private val database: Database) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 导出结果：文件名 + 字节内容 + 各部分条数（用于如实回报给用户）。 */
    data class Result(
        val fileName: String,
        val bytes: ByteArray,
        val books: Int,
        val sources: Int,
        val bookmarks: Int,
        val groups: Int,
        val rssSources: Int = 0,
    )

    /**
     * 按真实备份格式打包。
     *
     * @param deviceName 设备名后缀（为空则不追加后缀）。已由调用方清洗过非法字符。
     * @param today      用于文件名的日期，注入以便测试固定。
     */
    fun export(deviceName: String, today: LocalDate = LocalDate.now()): Result {
        val shelf = database.listBookshelf()
        val groups = database.listBookGroups()
        val bookmarks = database.listAllBookmarks()
        val sourcePayloads = database.exportSources(null)
        val rssSources = database.exportRssSources()

        // 分组名 → groupId：bookshelf 的 `group` 写的是**数字 id**，
        // 而本服务按「名字」关联（见 ADR-021），因此这里建反查表。
        val groupIdByName = groups.associate { it.name.lowercase() to it.id }
        // 书源 id → 展示名，用于 originName；直接从导出的 payload 里取，避免再查一次库。
        val sourceNameById = sourcePayloads.mapNotNull { payload ->
            val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.str("bookSourceUrl") ?: return@mapNotNull null
            id to (obj.str("bookSourceName") ?: id)
        }.toMap()

        val entries = linkedMapOf<String, JsonElement>(
            "bookGroup.json" to JsonArray(groups.map { groupEntry(it) }),
            "bookmark.json" to JsonArray(bookmarks.map { bookmarkEntry(it) }),
            "bookshelf.json" to JsonArray(shelf.mapIndexed { index, item -> shelfEntry(item, index, groupIdByName, sourceNameById) }),
            // 书源**原样回放**：payload 就是当初导入的那份 JSON，逐字忠实（分组已由 exportSources 合并进去）
            "bookSource.json" to JsonArray(sourcePayloads.map { json.parseToJsonElement(it) }),
            // 替换规则与 HTTP TTS：字段名逐字照抄真实备份（replaceRule.json / httpTTS.json），
            // 保证与 Legado 手机端互相可读。
            "replaceRule.json" to JsonArray(database.listReplaceRules().map { replaceRuleEntry(it) }),
            "httpTTS.json" to JsonArray(database.listHttpTts().map { httpTtsEntry(it) }),
            // RSS 订阅源。字段名与真实备份 `rssSources.json` 逐字一致（见 [rssSourceEntry]）。
            "rssSources.json" to JsonArray(rssSources.map { rssSourceEntry(it) }),
        )

        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                // 条目按名称**大小写不敏感**升序：真实备份是 bookGroup → bookmark → bookshelf → bookSource。
                // ⚠️ 不能用默认的 String 比较 —— 那是 ASCII 序，'G'(71)/'S'(83) 会排到 'm'(109)/'s'(115) 前面，
                // 得到 bookGroup → bookSource → bookmark → bookshelf，与参照物不一致。
                entries.entries.sortedBy { it.key.lowercase() }.forEach { (name, element) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(render(element).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            buffer.toByteArray()
        }
        return Result(
            fileName = fileName(deviceName, today),
            bytes = bytes,
            books = shelf.size,
            sources = sourcePayloads.size,
            bookmarks = bookmarks.size,
            groups = groups.size,
            rssSources = rssSources.size,
        )
    }

    /** 设备名后缀为空时退化为 `backup2026-09-30.zip`（与真实备份同构）。 */
    fun fileName(deviceName: String, today: LocalDate = LocalDate.now()): String {
        val suffix = deviceName.trim().takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
        return "backup${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}$suffix.zip"
    }

    // ------------------------------------------------------------------ 各条目

    /**
     * 替换规则条目：字段名与真实备份 `replaceRule.json` **逐字一致**
     * （实测 `backup2026-09-30-PEPM00.zip`：excludeScope/group/id/isEnabled/isRegex/name/order/
     * pattern/replacement/scope/scopeContent/scopeTitle/timeoutMillisecond）。
     *
     * 注意 `id`：备份里是**数字**（1767407348980），而本服务的规则 id 是字符串，
     * 能转成数字就写数字（与手机端一致），否则原样写字符串（Legado 侧按字符串解析也不会崩）。
     */
    private fun replaceRuleEntry(rule: ReplaceRule): JsonObject = buildJsonObject {
        put("id", rule.id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(rule.id))
        put("name", JsonPrimitive(rule.name))
        put("group", JsonPrimitive(rule.group ?: ""))
        put("pattern", JsonPrimitive(rule.pattern))
        put("replacement", JsonPrimitive(rule.replacement))
        put("isRegex", JsonPrimitive(rule.isRegex))
        put("isEnabled", JsonPrimitive(rule.isEnabled))
        put("scope", JsonPrimitive(rule.scope ?: ""))
        put("excludeScope", JsonPrimitive(rule.excludeScope ?: ""))
        put("scopeTitle", JsonPrimitive(rule.scopeTitle))
        put("scopeContent", JsonPrimitive(rule.scopeContent))
        put("order", JsonPrimitive(rule.order))
        put("timeoutMillisecond", JsonPrimitive(rule.timeoutMillisecond))
    }

    /**
     * HTTP TTS 条目：字段名与真实备份 `httpTTS.json` **逐字一致**
     * （实测 `backup2026-09-30-PEPM00.zip`：concurrentRate/contentType/enabledCookieJar/header/
     * id/lastUpdateTime/loginCheckJs/loginUi/loginUrl/name/url）。
     *
     * 额外写出 `jsLib`：参照包里没有这个字段（手机端该字段可为空），但本服务的 HttpTts 有，
     * 不写就会在「导出→再导入」时丢掉，故一并保留；Legado 侧读不认识的字段不影响。
     */
    private fun httpTtsEntry(tts: HttpTts): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(tts.id))
        put("name", JsonPrimitive(tts.name))
        put("url", JsonPrimitive(tts.url))
        put("header", JsonPrimitive(tts.header ?: ""))
        put("contentType", JsonPrimitive(tts.contentType ?: ""))
        put("concurrentRate", JsonPrimitive(tts.concurrentRate ?: ""))
        put("loginUrl", JsonPrimitive(tts.loginUrl ?: ""))
        put("loginCheckJs", JsonPrimitive(tts.loginCheckJs ?: ""))
        put("loginUi", JsonPrimitive(tts.loginUi ?: ""))
        put("jsLib", JsonPrimitive(tts.jsLib ?: ""))
        put("enabledCookieJar", JsonPrimitive(tts.enabledCookieJar))
        put("lastUpdateTime", JsonPrimitive(tts.lastUpdateTime))
    }

    /**
     * RSS 订阅源条目：字段名与真实备份 `rssSources.json` **逐字一致**。
     *
     * 实测参照包（`backup2026-09-30-PEPM00.zip`，8 条）的键数分布是 17~28 ——
     * 其中 **17 个字段 8/8 条全部出现**，其余 14 个按需出现。因此这里照抄同样策略：
     * **必写字段恒写**（含 `redirectPolicy`，真实数据 8/8 都有值），**可选字段只在有值时写**，
     * 与 Gson 省略空值的行为一致（与 [shelfEntry] 同一套约定）。
     *
     * 注意 `redirectPolicy` 是**字符串枚举**（实测值 `"ASK_CROSS_ORIGIN"`），不是整数。
     */
    private fun rssSourceEntry(source: RssSource): JsonObject = buildJsonObject {
        put("sourceUrl", JsonPrimitive(source.sourceUrl))
        put("sourceName", JsonPrimitive(source.sourceName))
        put("sourceIcon", JsonPrimitive(source.sourceIcon ?: ""))
        put("enabled", JsonPrimitive(source.enabled))
        put("customOrder", JsonPrimitive(source.customOrder))
        put("type", JsonPrimitive(source.type))
        put("articleStyle", JsonPrimitive(source.articleStyle))
        put("lastUpdateTime", JsonPrimitive(source.lastUpdateTime))
        put("singleUrl", JsonPrimitive(source.singleUrl))
        put("cacheFirst", JsonPrimitive(source.cacheFirst))
        put("preload", JsonPrimitive(source.preload))
        put("enableJs", JsonPrimitive(source.enableJs))
        put("showWebLog", JsonPrimitive(source.showWebLog))
        put("enabledCookieJar", JsonPrimitive(source.enabledCookieJar))
        put("loadWithBaseUrl", JsonPrimitive(source.loadWithBaseUrl))
        put("redirectPolicy", JsonPrimitive(source.redirectPolicy.ifBlank { "ASK_CROSS_ORIGIN" }))
        // ---- 可选字段：只在有值时写 ----
        source.sourceGroup?.takeIf { it.isNotBlank() }?.let { put("sourceGroup", JsonPrimitive(it)) }
        source.sourceComment?.takeIf { it.isNotBlank() }?.let { put("sourceComment", JsonPrimitive(it)) }
        source.header?.takeIf { it.isNotBlank() }?.let { put("header", JsonPrimitive(it)) }
        source.sortUrl?.takeIf { it.isNotBlank() }?.let { put("sortUrl", JsonPrimitive(it)) }
        source.ruleArticles?.takeIf { it.isNotBlank() }?.let { put("ruleArticles", JsonPrimitive(it)) }
        source.ruleLink?.takeIf { it.isNotBlank() }?.let { put("ruleLink", JsonPrimitive(it)) }
        source.ruleTitle?.takeIf { it.isNotBlank() }?.let { put("ruleTitle", JsonPrimitive(it)) }
        source.ruleImage?.takeIf { it.isNotBlank() }?.let { put("ruleImage", JsonPrimitive(it)) }
        source.rulePubDate?.takeIf { it.isNotBlank() }?.let { put("rulePubDate", JsonPrimitive(it)) }
        source.loginUrl?.takeIf { it.isNotBlank() }?.let { put("loginUrl", JsonPrimitive(it)) }
        source.loginUi?.takeIf { it.isNotBlank() }?.let { put("loginUi", JsonPrimitive(it)) }
        source.injectJs?.takeIf { it.isNotBlank() }?.let { put("injectJs", JsonPrimitive(it)) }
        source.shouldOverrideUrlLoading?.takeIf { it.isNotBlank() }?.let { put("shouldOverrideUrlLoading", JsonPrimitive(it)) }
        source.jsLib?.takeIf { it.isNotBlank() }?.let { put("jsLib", JsonPrimitive(it)) }
        source.contentBlacklist?.takeIf { it.isNotBlank() }?.let { put("contentBlacklist", JsonPrimitive(it)) }
    }

    private fun groupEntry(group: BookGroup): JsonObject = buildJsonObject {
        put("bookSort", JsonPrimitive(-1))
        put("enableRefresh", JsonPrimitive(true))
        put("groupId", JsonPrimitive(group.id))
        put("groupName", JsonPrimitive(group.name))
        put("order", JsonPrimitive(group.sortOrder))
        put("show", JsonPrimitive(false))
    }

    /**
     * 书签条目。字段顺序与省略规则照抄真实备份：
     * `bookAuthor, bookName, bookText, chapterIndex, chapterName, chapterPos, content, time`。
     * 其中 `content` 在真实备份里恒为 `""`（Legado 用它存批注），因此**保留空串**而不是省略。
     */
    private fun bookmarkEntry(mark: Bookmark): JsonObject = buildJsonObject {
        mark.bookAuthor?.takeIf { it.isNotBlank() }?.let { put("bookAuthor", JsonPrimitive(it)) }
        put("bookName", JsonPrimitive(mark.bookName))
        mark.bookText?.takeIf { it.isNotBlank() }?.let { put("bookText", JsonPrimitive(it)) }
        put("chapterIndex", JsonPrimitive(mark.chapterIndex))
        mark.chapterName?.takeIf { it.isNotBlank() }?.let { put("chapterName", JsonPrimitive(it)) }
        put("chapterPos", JsonPrimitive(mark.chapterPos))
        put("content", JsonPrimitive(mark.content.orEmpty()))
        put("time", JsonPrimitive(mark.createdAt))
    }

    /**
     * 书架条目（含阅读进度）。
     *
     * 字段顺序照抄真实备份（Gson 按类字段声明顺序输出）：author, bookUrl, canUpdate, coverUrl,
     * customCoverUrl, durChapterIndex, durChapterPos, durChapterTime, durChapterTitle, group, intro,
     * kind, lastCheckCount, lastCheckTime, latestChapterTime, latestChapterTitle, name, order, origin,
     * originName, originOrder, readConfig, syncTime, tocUrl, totalChapterNum, type, variable, wordCount。
     */
    private fun shelfEntry(
        book: BookshelfItem,
        index: Int,
        groupIdByName: Map<String, Long>,
        sourceNameById: Map<String, String>,
    ): JsonObject = buildJsonObject {
        book.author?.takeIf { it.isNotBlank() }?.let { put("author", JsonPrimitive(it)) }
        put("bookUrl", JsonPrimitive(book.bookUrl))
        put("canUpdate", JsonPrimitive(true))
        book.coverUrl?.takeIf { it.isNotBlank() }?.let { put("coverUrl", JsonPrimitive(it)) }
        // 阅读进度三件套
        put("durChapterIndex", JsonPrimitive(book.chapterIndex ?: 0))
        // 语义不同（百分比 vs 字符偏移），刻意写 0 而不是把百分比冒充成偏移
        put("durChapterPos", JsonPrimitive(0))
        put("durChapterTime", JsonPrimitive(book.lastReadAt))
        put("group", JsonPrimitive(book.groupName?.lowercase()?.let { groupIdByName[it] } ?: 0L))
        put("kind", JsonPrimitive(if (book.completed) "已完结" else "连载中"))
        put("lastCheckCount", JsonPrimitive(0))
        put("lastCheckTime", JsonPrimitive(0L))
        put("latestChapterTime", JsonPrimitive(0L))
        put("name", JsonPrimitive(book.name))
        // order 只影响书架手动排序；用稳定的递减序号（与真实备份的负值风格一致），
        // 保证同样的数据库多次导出得到完全相同的文件。
        put("order", JsonPrimitive(-(index + 1).toLong()))
        put("origin", JsonPrimitive(book.sourceId))
        sourceNameById[book.sourceId]?.let { put("originName", JsonPrimitive(it)) }
        put("originOrder", JsonPrimitive(0L))
        put("syncTime", JsonPrimitive(book.lastReadAt))
        put("tocUrl", JsonPrimitive(book.tocUrl))
        put("totalChapterNum", JsonPrimitive(book.totalChapters))
        // type 8 = 在线小说（服务端只处理这一类；本地书 264 / 音频 24 在导入时已被过滤）
        put("type", JsonPrimitive(8))
    }

    // ------------------------------------------------------------------ 排版

    /** 递归按 2 空格缩进输出（对齐 Gson `setPrettyPrinting` + `disableHtmlEscaping`）。 */
    private fun writeJson(element: JsonElement, depth: Int, out: StringBuilder) {
        when (element) {
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                element.forEachIndexed { index, child ->
                    indent(out, depth + 1)
                    writeJson(child, depth + 1, out)
                    if (index != element.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append(']')
            }
            is JsonObject -> {
                if (element.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val items = element.entries.toList()
                items.forEachIndexed { index, (key, value) ->
                    indent(out, depth + 1)
                    out.append(quote(key)).append(": ")
                    writeJson(value, depth + 1, out)
                    if (index != items.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append('}')
            }
            // JsonNull 是 JsonPrimitive 的子类，必须先判
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(if (element.isString) quote(element.content) else element.content)
        }
    }

    private fun render(element: JsonElement): String = StringBuilder().also { writeJson(element, 0, it) }.toString()

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    /**
     * 字符串转义。
     *
     * **刻意不转义 `<` `>` `&` `=` `'`** —— 真实备份里书源 `exploreUrl` 是 `<js>…</js>`、
     * 正文里也有这些字符，Gson 关掉 HTML 转义后正是原样输出。
     */
    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < '\u0020') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** 只写有值的键，省略空值（对齐真实备份的省略行为）。 */
    private fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
        JsonObject(JsonObjectBuilder().apply(block).map)

    private class JsonObjectBuilder {
        val map = LinkedHashMap<String, JsonElement>()
        fun put(key: String, value: JsonElement) {
            map[key] = value
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
