package io.legado.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Legado 手机端进度文件（`bookProgress/<书名>_<作者>.json`）的双向同步。
 *
 * 手机端备份里的进度长这样（实测）：
 * ```json
 * { "author": "梁灼安", "durChapterIndex": 4, "durChapterPos": 0,
 *   "durChapterTime": 1790465002215,
 *   "durChapterTitle": "第5 章 ：再考心智我甘心受辱",
 *   "name": "十日终焉我成魔\n第八十章 星尘归寂，余念长存" }
 * ```
 *
 * **关键难点**：文件里只有章节**标题**，没有 URL，而书源给的标题格式可能不同
 * （空格/全半角/标点差异）。因此采用「index 为主 + 标题规范化匹配为辅」的对齐策略，
 * 详见 [alignChapter]。
 */
class BookProgressSync(
    private val webDavRoot: Path,
    private val database: Database,
) {
    /** 进度文件夹名，默认 `legado/bookProgress`（可在「文件」设置页修改）。 */
    fun directoryName(): String {
        val raw = database.getSetting(SETTING_KEY)?.trim().orEmpty()
        return sanitizeDirectoryName(raw.ifBlank { DEFAULT_DIRECTORY })
    }

    fun setDirectoryName(name: String): String {
        val clean = sanitizeDirectoryName(name.ifBlank { DEFAULT_DIRECTORY })
        database.setSetting(SETTING_KEY, clean)
        return clean
    }

    /** 解析出进度目录（保证落在 WebDAV 根之内）。 */
    fun directory(): Path? {
        val dir = resolveSafely(directoryName()) ?: return null
        return if (dir.isDirectory()) dir else null
    }

    /**
     * 清洗用户输入的文件夹名（**支持多级子目录**，如 `legado/bookProgress`）。
     *
     * 手机端备份会自带一层 `legado` 目录，所以这里必须允许多级；
     * 但仍严格禁止任何逃出 WebDAV 根的写法：
     * 绝对路径、`..`、空字节、控制字符、Windows 盘符。
     */
    fun sanitizeDirectoryName(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/').trim('/')
        if (trimmed.isEmpty()) return DEFAULT_DIRECTORY
        // 逐段校验：任何一段非法就整体回退默认值
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return DEFAULT_DIRECTORY
        if (segments.size > MAX_DIR_DEPTH) return DEFAULT_DIRECTORY
        for (seg in segments) {
            if (seg == "." || seg == "..") return DEFAULT_DIRECTORY
            if (seg.any { it.isISOControl() || it == '\u0000' }) return DEFAULT_DIRECTORY
            if (seg.contains(':')) return DEFAULT_DIRECTORY      // Windows 盘符 / 备用数据流
            if (seg.length > MAX_DIR_NAME_LENGTH) return DEFAULT_DIRECTORY
        }
        return segments.joinToString("/")
    }

    /** 把目录名解析到 WebDAV 根下，并二次校验结果仍在根内。 */
    private fun resolveSafely(name: String): Path? = runCatching {
        val root = webDavRoot.toAbsolutePath().normalize()
        val candidate = root.resolve(name).normalize()
        // 关键防线：规范化之后仍必须位于根目录之内（防 `..` 穿越）
        if (!candidate.startsWith(root)) null else candidate
    }.getOrNull()

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 读取某本书的进度文件；不存在或格式非法返回 null（**绝不抛异常打断阅读**）。 */
    fun read(bookName: String, author: String?): FileProgress? = runCatching {
        val file = findFile(bookName, author) ?: return@runCatching null
        val text = file.readText()
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        FileProgress(
            chapterIndex = obj["durChapterIndex"]?.jsonPrimitive?.intOrNull,
            chapterTitle = obj["durChapterTitle"]?.jsonPrimitive?.contentOrNull,
            chapterPos = obj["durChapterPos"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = obj["durChapterTime"]?.jsonPrimitive?.longOrNull ?: 0L,
            fileName = file.fileName.toString(),
        )
    }.getOrNull()

    /**
     * 找到某本书的进度文件。
     *
     * 先按「书名_作者.json」精确查找；找不到再做**归一化匹配**——
     * 手机端会把书名里的换行/斜杠等字符去掉，直接按原名拼文件名会找不到。
     */
    private fun findFile(bookName: String, author: String?): Path? {
        val dir = directory() ?: return null
        val candidates = fileNameCandidates(bookName, author)
        for (name in candidates) {
            // 必须 try/catch：书名含换行等字符时，`resolve` 会抛 InvalidPathException
            // 而不是返回不存在的路径。若不拦，**第一个未清洗的候选就会中断整个查找**，
            // 导致后面真正匹配的那个候选永远试不到（实测踩过，见 SESSION-028）。
            val f = runCatching { dir.resolve(name) }.getOrNull() ?: continue
            if (Files.exists(f)) return f
        }
        // 兜底：归一化后逐文件比对（书名可能含被清洗掉的字符）
        val targetKey = normalizeForMatch(bookName)
        if (targetKey.isBlank()) return null
        return runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }
                    .filter { path ->
                        val stem = path.fileName.toString().removeSuffix(".json")
                        val fileBookName = stem.substringBeforeLast('_', stem)
                        normalizeForMatch(fileBookName) == targetKey
                    }
                    .findFirst().orElse(null)
            }
        }.getOrNull()
    }

    /** 生成可能的文件名（手机端命名规则：`<书名>_<作者>.json`，并去掉换行等非法字符）。 */
    internal fun fileNameCandidates(bookName: String, author: String?): List<String> {
        val names = linkedSetOf<String>()
        for (n in listOf(bookName, sanitizeFileComponent(bookName))) {
            if (n.isBlank()) continue
            for (a in listOfNotNull(author?.takeIf { it.isNotBlank() }, sanitizeFileComponent(author))) {
                names.add("${n}_$a.json")
            }
            names.add("$n.json")
        }
        return names.toList()
    }

    /**
     * 去掉手机端文件名不允许的字符。
     *
     * 注意这里用**原始字符串**写正则：普通字符串里 `"\\r"` 会变成字面量 `\r` 两个字符，
     * 而不是回车符，导致**真实换行不会被去掉**（实测漏掉书名含换行的场景）。
     */
    private fun sanitizeFileComponent(raw: String?): String =
        (raw ?: "").replace(Regex("""[\r\n/\\:*?"<>|]"""), "").trim()

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 把当前进度写回进度文件。
     *
     * - 文件已存在 → **原地更新**（保留手机端写入的其它字段与文件名）
     * - 不存在 → 创建 `<书名>_<作者>.json`
     *
     * 失败一律静默（返回 false），**绝不影响阅读主流程**——进度文件只是镜像，
     * 真正的权威数据仍是本地 SQLite。
     */
    fun write(
        bookName: String,
        author: String?,
        chapterIndex: Int,
        chapterTitle: String,
        chapterPos: Int = 0,
    ): Boolean = runCatching {
        val dir = ensureDirectory() ?: return@runCatching false
        val existing = findFile(bookName, author)
        val target = existing ?: dir.resolve(
            "${sanitizeFileComponent(bookName).ifBlank { "untitled" }}_${sanitizeFileComponent(author).ifBlank { "unknown" }}.json"
        )
        // 保留原有字段（name/author 等由手机端写入，不要覆盖成我们这边的形态）
        val previous = if (existing != null) {
            runCatching { Json.parseToJsonElement(existing.readText()) as? JsonObject }.getOrNull()
        } else null

        val payload = buildJsonObject {
            put("author", JsonPrimitive(previous?.get("author")?.jsonPrimitive?.contentOrNull ?: author ?: ""))
            put("durChapterIndex", JsonPrimitive(chapterIndex))
            put("durChapterPos", JsonPrimitive(previous?.get("durChapterPos")?.jsonPrimitive?.intOrNull ?: chapterPos))
            put("durChapterTime", JsonPrimitive(System.currentTimeMillis()))
            put("durChapterTitle", JsonPrimitive(chapterTitle))
            put("name", JsonPrimitive(previous?.get("name")?.jsonPrimitive?.contentOrNull ?: bookName))
        }
        // 原子写：先写临时文件再移动，避免手机端/WEB 端读到半截 JSON
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, formatProgressJson(payload))
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    private fun ensureDirectory(): Path? = runCatching {
        val dir = resolveSafely(directoryName()) ?: return@runCatching null
        if (!dir.exists()) Files.createDirectories(dir)
        if (dir.isDirectory()) dir else null
    }.getOrNull()

    /**
     * 按**手机端原有格式**序列化进度 JSON。
     *
     * 实测（SESSION-029）手机端文件的确切形态（以 `长征十日_安南十八子.json` 为模板）：
     * ```
     * {
     *   "author": "安南十八子",
     *   "durChapterIndex": 8,
     *   "durChapterPos": 0,
     *   "durChapterTime": 1790469228909,
     *   "durChapterTitle": "第八章：弄羊村兵分三路 花背鱼水情长（下）",
     *   "name": "长征十日"
     * }
     * ```
     * 要点：**2 空格缩进**、**冒号后一个空格**、**LF 行尾**、**末尾不加换行**、
     * 字段顺序固定。kotlinx 的默认输出是**单行紧凑**格式，与手机端不一致，故手工拼装。
     *
     * 另有部分文件（如 `十日终焉我成魔_梁灼安.json`）是单行紧凑格式，
     * 但用户明确要求**统一成多行缩进**，因此这里不再区分来源格式。
     */
    internal fun formatProgressJson(obj: JsonObject): String {
        val sb = StringBuilder()
        sb.append('{').append('\n')
        // 固定字段顺序：与手机端完全一致
        val order = listOf("author", "durChapterIndex", "durChapterPos", "durChapterTime", "durChapterTitle", "name")
        val keys = order.filter { obj.containsKey(it) } + obj.keys.filter { it !in order }
        keys.forEachIndexed { i, key ->
            val value = obj[key] ?: return@forEachIndexed
            sb.append("  ").append('"').append(escapeJsonString(key)).append("\": ")
            sb.append(renderJsonValue(value))
            if (i != keys.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append('}')
        return sb.toString()
    }

    /** 渲染 JSON 值：字符串走转义，数字/布尔/null 原样（与手机端写法一致）。 */
    private fun renderJsonValue(value: JsonElement): String = when (value) {
        is JsonPrimitive -> if (value.isString) "\"${escapeJsonString(value.content)}\"" else value.content
        else -> Json.encodeToString(JsonElement.serializer(), value)
    }

    /**
     * JSON 字符串转义。
     *
     * 必须显式处理 `\n`：书名里可能含换行（实测真实数据
     * `"name": "十日终焉我成魔\n第八十章 ..."`），若原样写入就会把 JSON 结构破坏成两行。
     */
    private fun escapeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 章节对齐
    // ------------------------------------------------------------------

    /**
     * 把进度文件里的章节对齐到当前书源的章节列表。
     *
     * 策略（按优先级）：
     * 1. `durChapterIndex` 在范围内，且该章标题与 `durChapterTitle` **规范化后相似** → 采用
     * 2. 按规范化标题**全表查找**
     * 3. 都不中 → 用 `durChapterIndex` 兜底（夹到 `0..size-1`）
     *
     * 为什么不只用 index：换源后章节数可能不同，直接按下标会错位。
     * 为什么不只用标题：手机端与书源的标题常有空白/全半角/标点差异。
     */
    internal fun alignChapter(
        chapters: List<Chapter>,
        fileIndex: Int?,
        fileTitle: String?,
    ): Int? {
        if (chapters.isEmpty()) return null
        val targetTitle = fileTitle?.let(::normalizeForMatch).orEmpty()

        if (fileIndex != null && fileIndex in chapters.indices && targetTitle.isNotBlank()) {
            val local = normalizeForMatch(chapters[fileIndex].title)
            if (local.isNotBlank() && titlesSimilar(local, targetTitle)) return fileIndex
        }

        if (targetTitle.isNotBlank()) {
            chapters.indexOfFirst { normalizeForMatch(it.title) == targetTitle }
                .takeIf { it >= 0 }?.let { return it }
            // 退一步：包含关系（手机端标题可能带卷名/副标题）
            chapters.indexOfFirst { c ->
                val local = normalizeForMatch(c.title)
                local.isNotBlank() && (local.contains(targetTitle) || targetTitle.contains(local))
            }.takeIf { it >= 0 }?.let { return it }
        }

        return fileIndex?.coerceIn(0, chapters.size - 1)
    }

    /**
     * 标题规范化：去掉所有空白与常见标点差异，全角转半角，统一小写。
     *
     * 目的是让「第5 章 ：再考心智我甘心受辱」与「第5章：再考心智我甘心受辱」视为同一个。
     */
    internal fun normalizeForMatch(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code == 0x3000 -> ' '                    // 全角空格
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()  // 全角 ASCII → 半角
                else -> ch
            }
            if (c.isWhitespace()) continue
            if (c in PUNCTUATION_IGNORED) continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** 标题是否足够相似（规范化后相等，或一方包含另一方且长度差距不大）。 */
    private fun titlesSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return longer.contains(shorter) && shorter.length.toDouble() / longer.length >= 0.6
    }

    internal companion object {
        const val SETTING_KEY = "progress_sync_directory"
        /**
         * 默认值必须带上手机端备份自带的 `legado` 层级。
         *
         * 实测（SESSION-028）：手机端备份解压后是 `webdav/legado/bookProgress/`，
         * 若默认写成 `bookProgress`，会**另建一个空目录**并永远读不到真实进度文件
         * （表现为「明明文件在那儿，却 fileFound=false」）。
         */
        const val DEFAULT_DIRECTORY = "legado/bookProgress"
        private const val MAX_DIR_NAME_LENGTH = 64
        private const val MAX_DIR_DEPTH = 4
        private val PUNCTUATION_IGNORED = setOf(
            '：', ':', '，', ',', '。', '.', '、', '；', ';', '！', '!', '？', '?',
            '（', '(', '）', ')', '【', '[', '】', ']', '《', '》', '"', '"', '\'', '\'',
            '·', '—', '－', '-', '_', '～', '~', '　',
        )
    }
}

/** 进度文件里我们关心的字段。 */
@Serializable
data class FileProgress(
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
    val chapterPos: Int = 0,
    val updatedAt: Long = 0,
    val fileName: String? = null,
)
