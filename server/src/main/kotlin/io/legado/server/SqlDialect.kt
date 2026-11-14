package io.legado.server

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * SQLite 写法 → PostgreSQL 的**翻译层**。
 *
 * ## 为什么翻译而不是改调用点
 * `Database.kt` 里有 280+ 处 `prepareStatement(...)`、28 处 `createStatement()`，逐条改方言
 * 既不可review也不可回归。所以方言差异全部**收口**在这里，并且只包在 JDBC 的两个接缝上
 * （[DialectConnection] / [DialectStatement]），业务代码一行不动。
 *
 * ## 覆盖的差异（都是真实会崩的点）
 * | SQLite 写法 | PostgreSQL |
 * | :-- | :-- |
 * | `insert … on conflict(c) do update set a=excluded.a` | 原样（PG 原生支持 `excluded`） |
 * | `on conflict(c) do nothing` | 原样 |
 * | `x = ? collate nocase` | `lower(x) = lower(?)` |
 * | `order by x collate nocase` | `order by lower(x)` |
 * | `group by x collate nocase` | 删掉（PG 要求 group by 表达式与 select 一致） |
 * | `pragma table_info(t)` | `information_schema.columns` |
 * | `pragma journal_mode = wal` | 跳过执行 |
 * | `json_extract(x,'$.k')` | `(x::jsonb #>> '{k}')` |
 * | `real`（列类型） | `double precision` |
 */
object SqlTranslator {

    /** 翻译一条 SQL。返回 `null` ⇒ 该语句在当前引擎下**应被跳过执行**（sqlite 专属 pragma）。 */
    fun translate(engine: DatabaseEngine, sql: String): String? {
        if (engine == DatabaseEngine.SQLITE) return sql
        if (sql.isBlank()) return sql
        if (sql.trimStart().startsWith("pragma", ignoreCase = true)) return translatePragma(engine, sql)
        var out = sql
        out = translateCollateNocase(engine, out)
        out = translateJsonExtract(engine, out)
        out = translateTypes(out)
        return out
    }

    // ------------------------------------------------------------------ pragma

    private val TABLE_INFO_RE = Regex("""(?is)^\s*pragma\s+table_info\s*\(\s*[`"']?([\w.]+)[`"']?\s*\)\s*;?\s*$""")

    /**
     * `pragma table_info(x)` 不能简单「跳过」——调用方拿它的 `ResultSet` 判列是否存在
     * （`migrate*` 全家都这么用），跳过会 NPE。所以翻译成 PG 的 `information_schema` 查询，
     * 并保证结果集的列名**仍然是 `name`**（调用方按 `rs.getString("name")` 取值）。
     */
    private fun translatePragma(engine: DatabaseEngine, sql: String): String? {
        val match = TABLE_INFO_RE.find(sql) ?: return null
        val table = match.groupValues[1].substringAfterLast('.')
        return when (engine) {
            DatabaseEngine.POSTGRES ->
                "select column_name as name from information_schema.columns " +
                    "where table_schema = current_schema() and table_name = '$table'"
            else -> null
        }
    }

    // ------------------------------------------------------------------ upsert

    // PG 原生支持 `on conflict … do update set x = excluded.x` / `do nothing`，
    // 与 SQLite 写法完全一致 ⇒ 不需要任何改写（MySQL 的 `on duplicate key update` 已随 MySQL 支持移除）。
    private fun translateUpsert(engine: DatabaseEngine, sql: String): String = sql

    // --------------------------------------------------------- collate nocase

    private val COLLATE_NOCASE_RE = Regex("(?i)\\s+collate\\s+nocase\\b")
    private val LIKE_TAIL_RE = Regex("(?i)\\blike\\s*$")
    private val GROUP_BY_TAIL_RE = Regex("(?i)\\b(group|order)\\s+by\\s*$")

    /**
     * `collate nocase` 是 SQLite 专有写法，PG 没有。等价语义用 `lower()` 包住两边。
     * 注意 `group by` 必须**只删不加** —— PG 要求 `group by` 的表达式与 `select` 列表里的完全一致，
     * 把 `trim(x)` 改成 `lower(trim(x))` 会让 `select trim(x) … group by lower(trim(x))` 直接报
     * 「column must appear in the GROUP BY clause」。
     */
    private fun translateCollateNocase(engine: DatabaseEngine, sql: String): String {
        if (!COLLATE_NOCASE_RE.containsMatchIn(sql)) return sql
        if (engine != DatabaseEngine.POSTGRES) return sql
        var out = sql
        repeat(64) {
            val match = COLLATE_NOCASE_RE.find(out) ?: return out
            val operandEnd = out.substring(0, match.range.first).trimEnd().length
            if (operandEnd <= 0) return out
            val operandStart = operandStart(out, operandEnd)
            val operand = out.substring(operandStart, operandEnd)
            val before = out.substring(0, operandStart).trimEnd()
            val eq = if (before.endsWith("=")) before.length - 1 else -1
            val like = LIKE_TAIL_RE.find(before)
            val by = GROUP_BY_TAIL_RE.find(before)
            out = when {
                eq >= 0 -> {
                    var opStart = eq
                    if (opStart > 0 && before[opStart - 1] in "!<>") opStart--
                    val leftEnd = before.substring(0, opStart).trimEnd().length
                    if (leftEnd <= 0) return out
                    val leftStart = operandStart(before, leftEnd)
                    val left = before.substring(leftStart, leftEnd)
                    val op = before.substring(opStart)
                    out.substring(0, leftStart) + "lower($left) $op lower($operand)" + out.substring(match.range.last + 1)
                }
                like != null -> {
                    val leftEnd = before.substring(0, like.range.first).trimEnd().length
                    if (leftEnd <= 0) return out
                    val leftStart = operandStart(before, leftEnd)
                    val left = before.substring(leftStart, leftEnd)
                    out.substring(0, leftStart) + "lower($left) like lower($operand)" + out.substring(match.range.last + 1)
                }
                by != null && by.groupValues[1].equals("order", ignoreCase = true) ->
                    // ⚠️ 库里的 `order by … collate nocase` 全部是**真列/表达式**（`name`、`trim(group_name)`），
                    // 所以这里统一包 `lower()` 保住「大小写不敏感排序」。
                    // 若哪天要按 **select 别名** 排序，**不要**写 `collate nocase`（PG 会报
                    // `order by lower(别名)` → column does not exist），直接 `order by 别名` 即可
                    // （见 `listSourceGroups`）。
                    out.substring(0, operandStart) + "lower($operand)" + out.substring(match.range.last + 1)
                // group by（以及极少见的其它上下文）：只删不加，见上面的说明
                else -> out.substring(0, match.range.first) + out.substring(match.range.last + 1)
            }
        }
        return out
    }

    /**
     * 从 [endExclusive] 向前取出一个「简单操作数」的起始下标。
     *
     * 覆盖库里真实出现的形态：裸列名、带点前缀（`s.group_name`）、参数 `?`、
     * 字符串字面量、函数调用（`trim(x)` / `coalesce(a, '')`）。
     */
    private fun operandStart(sql: String, endExclusive: Int): Int {
        var i = endExclusive - 1
        var depth = 0
        while (i >= 0) {
            val c = sql[i]
            when {
                c == ')' -> {
                    depth++
                    i--
                }
                c == '(' -> {
                    if (depth == 0) return i + 1
                    depth--
                    if (depth == 0) {
                        var j = i - 1
                        while (j >= 0 && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '.' || sql[j] == '`' || sql[j] == '"')) j--
                        return j + 1
                    }
                    i--
                }
                depth == 0 && (c.isWhitespace() || c == ',') -> return i + 1
                else -> i--
            }
        }
        return 0
    }

    // ------------------------------------------------------------ json_extract

    private val JSON_EXTRACT_RE = Regex("""(?i)json_extract\s*\(\s*([\w.`"]+)\s*,\s*'\$([.\w]+)'\s*\)""")

    /**
     * `json_extract(x,'$.k')`（SQLite JSON1）的 PG 等价写法。
     *
     * PG：`jsonb #>> '{k}'` 对「路径不存在 / JSON null」都返回 SQL `NULL`，语义天然与 SQLite 一致。
     */
    private fun translateJsonExtract(engine: DatabaseEngine, sql: String): String {
        if (!JSON_EXTRACT_RE.containsMatchIn(sql)) return sql
        return when (engine) {
            DatabaseEngine.POSTGRES -> JSON_EXTRACT_RE.replace(sql) { match ->
                val expression = match.groupValues[1]
                val keys = match.groupValues[2].trimStart('.').replace(".", ",")
                "(($expression)::jsonb #>> '{$keys}')"
            }
            else -> sql
        }
    }

    // ------------------------------------------------------------------ types

    private val REAL_WORD_RE = Regex("(?i)\\breal\\b")
    private val ALTER_ADD_RE = Regex("""(?is)^(\s*alter\s+table\s+[\w`"]+\s+add\s+(?:column\s+)?)([\w`"]+)(\s+)(\w+)(.*)$""")

    private fun translateTypes(sql: String): String {
        var out = sql
        if (REAL_WORD_RE.containsMatchIn(out)) {
            out = REAL_WORD_RE.replace(out, "double precision")
        }
        if (out.trimStart().startsWith("alter table", ignoreCase = true)) {
            out = translateAlterAddColumn(out)
        }
        return out
    }

    /** 增量迁移里的 `alter table … add column`：列类型同样要按方言映射。 */
    private fun translateAlterAddColumn(sql: String): String {
        val match = ALTER_ADD_RE.find(sql) ?: return sql
        val prefix = match.groupValues[1]
        val column = match.groupValues[2]
        val space = match.groupValues[3]
        val type = match.groupValues[4].lowercase()
        val rest = match.groupValues[5]
        val mapped = when (type) {
            "integer" -> "bigint"
            "real" -> "double precision"
            else -> return sql
        }
        return prefix + column + space + mapped + rest
    }
}

/**
 * 包装 `Connection`：把每条 SQL 在**下发前**翻译成目标方言。
 *
 * 只拦截两个接缝：
 * - `prepareStatement(...)` —— 翻译发生在 prepare 时，返回的 `PreparedStatement` 无需再包；
 * - `createStatement()` —— 返回的 [DialectStatement] 在 `execute*` 时翻译（`pragma` 一族走这条路）。
 */
internal class DialectConnection(
    private val delegate: Connection,
    private val engine: DatabaseEngine,
) : Connection by delegate {

    private fun translate(sql: String): String =
        SqlTranslator.translate(engine, sql) ?: EMPTY_RESULTSET_SQL

    override fun prepareStatement(sql: String): PreparedStatement = delegate.prepareStatement(translate(sql))

    override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement =
        delegate.prepareStatement(translate(sql), autoGeneratedKeys)

    override fun prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement =
        delegate.prepareStatement(translate(sql), resultSetType, resultSetConcurrency)

    override fun prepareStatement(
        sql: String,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): PreparedStatement = delegate.prepareStatement(translate(sql), resultSetType, resultSetConcurrency, resultSetHoldability)

    override fun prepareStatement(sql: String, columnNames: Array<out String>?): PreparedStatement =
        delegate.prepareStatement(translate(sql), columnNames)

    override fun prepareStatement(sql: String, columnIndexes: IntArray?): PreparedStatement =
        delegate.prepareStatement(translate(sql), columnIndexes)

    override fun createStatement(): Statement = DialectStatement(delegate.createStatement(), engine)

    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement =
        DialectStatement(delegate.createStatement(resultSetType, resultSetConcurrency), engine)

    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement =
        DialectStatement(delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), engine)

    private companion object {
        /** 被跳过的语句（sqlite 专属 pragma）经 `prepareStatement` 进来时的兜底：永远空结果。 */
        const val EMPTY_RESULTSET_SQL = "select 1 where 1 = 0"
    }
}

/**
 * 包装 `Statement`：SQL 在 `execute*` 时才拿到，所以在这里翻译；
 * 翻译结果为 `null`（sqlite 专属 pragma）时**不执行**，并给 `executeQuery` 返回空结果集
 * （调用方会遍历它判断列是否存在，不能 NPE）。
 */
internal class DialectStatement(
    private val delegate: Statement,
    private val engine: DatabaseEngine,
) : Statement by delegate {

    override fun execute(sql: String): Boolean {
        val translated = SqlTranslator.translate(engine, sql) ?: return false
        return delegate.execute(translated)
    }

    override fun execute(sql: String, autoGeneratedKeys: Int): Boolean {
        val translated = SqlTranslator.translate(engine, sql) ?: return false
        return delegate.execute(translated, autoGeneratedKeys)
    }

    override fun execute(sql: String, columnIndexes: IntArray?): Boolean {
        val translated = SqlTranslator.translate(engine, sql) ?: return false
        return delegate.execute(translated, columnIndexes)
    }

    override fun execute(sql: String, columnNames: Array<out String>?): Boolean {
        val translated = SqlTranslator.translate(engine, sql) ?: return false
        return delegate.execute(translated, columnNames)
    }

    override fun executeUpdate(sql: String): Int {
        val translated = SqlTranslator.translate(engine, sql) ?: return 0
        return delegate.executeUpdate(translated)
    }

    override fun executeUpdate(sql: String, autoGeneratedKeys: Int): Int {
        val translated = SqlTranslator.translate(engine, sql) ?: return 0
        return delegate.executeUpdate(translated, autoGeneratedKeys)
    }

    override fun executeUpdate(sql: String, columnIndexes: IntArray?): Int {
        val translated = SqlTranslator.translate(engine, sql) ?: return 0
        return delegate.executeUpdate(translated, columnIndexes)
    }

    override fun executeUpdate(sql: String, columnNames: Array<out String>?): Int {
        val translated = SqlTranslator.translate(engine, sql) ?: return 0
        return delegate.executeUpdate(translated, columnNames)
    }

    override fun executeLargeUpdate(sql: String): Long {
        val translated = SqlTranslator.translate(engine, sql) ?: return 0L
        return delegate.executeLargeUpdate(translated)
    }

    override fun executeQuery(sql: String): ResultSet {
        val translated = SqlTranslator.translate(engine, sql) ?: return EmptyResultSet.INSTANCE
        return delegate.executeQuery(translated)
    }

    override fun addBatch(sql: String) {
        val translated = SqlTranslator.translate(engine, sql) ?: return
        delegate.addBatch(translated)
    }
}

/**
 * 「被跳过的语句」要给 `executeQuery` 一个能安全遍历的空结果集。
 *
 * 手写 `ResultSet` 一百多个方法不现实，用动态代理：`next()` 恒 `false`，
 * 其余方法抛 `UnsupportedOperationException`（真被调用到说明调用点本就不该走这条路）。
 */
internal object EmptyResultSet {
    val INSTANCE: ResultSet = Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "next" -> false
            "previous", "first", "last", "absolute", "relative" -> false
            "wasNull" -> false
            "isClosed" -> true
            "isBeforeFirst", "isAfterLast", "isFirst", "isLast" -> false
            "close", "clearWarnings" -> Unit
            "getRow" -> 0
            "getFetchSize" -> 0
            "getFetchDirection" -> ResultSet.FETCH_FORWARD
            "getType" -> ResultSet.TYPE_FORWARD_ONLY
            "getConcurrency" -> ResultSet.CONCUR_READ_ONLY
            "getHoldability" -> ResultSet.HOLD_CURSORS_OVER_COMMIT
            "getStatement" -> null
            "toString" -> "EmptyResultSet"
            "hashCode" -> System.identityHashCode(method)
            "equals" -> false
            "isWrapperFor" -> false
            else -> throw UnsupportedOperationException("EmptyResultSet.${method.name}")
        }
    } as ResultSet
}
