package io.legado.server

import java.sql.Connection

/**
 * 建表脚本（DDL）的**唯一权威来源** + 分方言生成。
 *
 * ## 为什么以 SQLite 为权威、再翻译成 PostgreSQL
 * 既有 30+ 个测试全部跑在 SQLite 上，DDL 一旦「改写」就等于把测试基线也改了。
 * 所以这里 [SQLITE_DDL] **逐字保留**原来的建表脚本（`Database.initialize` 里原本就是它），
 * PostgreSQL 的 DDL 由它**转换生成** —— 表结构永远只有一份定义，不会漂移。
 *
 * （MySQL 支持已移除，原先的 `varchar(255)` 索引列 / `longtext` / `auto_increment` /
 * utf8mb4 那套规则一并删除。）
 *
 * ## 真实踩过的坑（这些不是理论问题，是建表立刻报错的）
 * 1. **`integer` 是 64 位（SQLite）vs 32 位（PG）**：本项目所有时间戳都是毫秒
 *    （`System.currentTimeMillis()`），映射成 `int` 会**直接溢出** ⇒ 一律 `bigint`；
 * 2. **自增主键语法不同**：SQLite `autoincrement` ⇒ PG `bigserial`；
 * 3. **`collate nocase` 与 `real` 在 PG 都不存在** ⇒ 分别删掉 / 换成 `double precision`。
 */
internal object DatabaseSchema {

    /** SQLite 建表脚本（**逐字保留**，全部既有测试都跑在这份 DDL 上）。 */
    val SQLITE_DDL: String = """
                create table if not exists app_user (
                  id integer primary key check (id = 1), password_hash text not null
                );
                create table if not exists session (
                  id text primary key, csrf_token text not null, expires_at integer not null
                );
                create table if not exists source (
                  id text primary key, name text not null, source_url text not null,
                  source_group text, enabled integer not null, is_js integer not null,
                  payload text not null, version integer not null, updated_at integer not null
                );
                create index if not exists source_name_idx on source(name);
                create index if not exists idx_source_name_nocase on source(name collate nocase);
                create table if not exists reading_progress (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  chapter_index integer not null, updated_at integer not null,
                  primary key (source_id, book_url)
                );
                create table if not exists cover_cache (
                  cache_key text primary key, content_type text not null,
                  file_path text not null default ''
                );
                create table if not exists app_setting (
                  key text primary key, value text not null, updated_at integer not null
                );
                create table if not exists book_group (
                  id integer primary key autoincrement,
                  name text not null unique collate nocase,
                  sort_order integer not null default 0,
                  -- 隐藏分组（PR #89）：权威 Schema 必须带上，否则新库要先建旧表再 alter 补列
                  is_private integer not null default 0,
                  created_at integer not null
                );
                create index if not exists idx_book_group_sort on book_group(sort_order asc, id asc);
                create table if not exists bookmark (
                  id integer primary key autoincrement,
                  book_name text not null,
                  book_author text,
                  chapter_index integer not null default 0,
                  chapter_name text,
                  chapter_pos integer not null default 0,
                  book_text text,
                  content text,
                  created_at integer not null,
                  unique(book_name, book_author, chapter_index, chapter_pos)
                );
                create index if not exists idx_bookmark_book on bookmark(book_name, book_author);
                /*
                 * 书籍封面登记（按「书名 + 作者」，与书架/书签同一套身份）：
                 *
                 * - 书在书架上时封面仍以 `book_shelf.cover_key` 为准，本表只是**兜底**
                 *   （书签里有、书架里没有的书 —— 用户要求「一股脑全存」后这种书是常态）；
                 * - 书签页上传/抓到的封面写在这里，书架行不存在也能显示；
                 * - `missing_attempts` 记录"连续抓了几次都没抓到"，到阈值就不再自动重试
                 *   （用户要求「超过五次没找到就标记」）。
                 *
                 * ⚠️ `book_author` 必须 `not null default ''`：SQLite 的 UNIQUE/PK 把 NULL 视为互不相等，
                 * 可空列做主键列会让同一本书插出多行（书签表踩过这个坑，见 AGENTS.md）。
                 */
                create table if not exists book_cover (
                  book_name text not null,
                  book_author text not null default '',
                  cover_key text,
                  missing_attempts integer not null default 0,
                  last_attempt_at integer not null default 0,
                  updated_at integer not null default 0,
                  primary key (book_name, book_author)
                );
                create table if not exists book_shelf (
                  source_id text not null, book_url text not null, name text not null,
                  author text, toc_url text not null, cover_url text, cover_key text,
                  last_read_at integer not null, completed integer not null default 0,
                  alternate_sources text, group_name text,
                  primary key (source_id, book_url)
                );
                create index if not exists book_shelf_last_read_idx on book_shelf(last_read_at desc);
                create table if not exists book_content_cache (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  title text, content text not null, cached_at integer not null,
                  raw_title text, raw_content text,
                  primary key (source_id, book_url, chapter_url)
                );
                create index if not exists book_content_cache_book_idx on book_content_cache(source_id, book_url);
                create table if not exists book_cache_status (
                  source_id text not null, book_url text not null, total_chapters integer not null default 0,
                  cached_chapters integer not null default 0, state text not null default 'idle',
                  last_error text, updated_at integer not null,
                  primary key (source_id, book_url)
                );
                create table if not exists source_subscription (
                  id integer primary key autoincrement, url text not null unique,
                  enabled integer not null, created_at integer not null, updated_at integer not null,
                  last_success_at integer, last_attempt_at integer, last_error text,
                  last_imported integer not null default 0, content_hash text
                );
                create index if not exists source_subscription_enabled_idx on source_subscription(enabled);
                create table if not exists rss_source (
                  id text primary key, name text not null, icon text, source_group text,
                  enabled integer not null default 1, single_url integer not null default 0,
                  sort_url text, custom_order integer not null default 0,
                  payload text not null, updated_at integer not null
                );
                create table if not exists book_toc_cache (
                  source_id text not null, toc_url text not null, chapters_json text not null, updated_at integer not null,
                  primary key (source_id, toc_url)
                );
                -- ------------------------------------------------------------------
                -- 全局搜索：目录（章节）索引
                --
                -- `book_toc_cache` 存的是整本目录的 JSON 大字段，**没法用 like 检索章名**；
                -- 这里把目录摊平成「一行一章」的可检索表。`book_url` 一律取**书架的 book_url**
                -- （不是 toc_url —— toc_url 有多种变体，见 Database.saveTocCache 的注释），
                -- 与 `book_shelf` 的主键对齐后才能 join 出书名/封面。
                --
                -- ⚠️ 只用 SQLite 与 PostgreSQL **都支持**的写法：没有 rowid、没有 insert or、
                -- 没有 changes()、没有 strftime（AGENTS.md 里 `order by rowid` 让 PG 秒 500 的教训）。
                -- ------------------------------------------------------------------
                create table if not exists book_chapter_index (
                  source_id text not null, book_url text not null, chapter_url text not null,
                  chapter_index integer not null, title text not null,
                  primary key (source_id, book_url, chapter_url)
                );
                -- 表达式索引：查询侧已统一写成 lower(title) / lower(name)，索引必须跟上才用得上。
                -- SQLite 与 PostgreSQL 都支持表达式索引（由现有生成器原样下发）。
                create index if not exists book_chapter_index_title_idx on book_chapter_index(lower(title));
                create index if not exists book_shelf_name_ci_idx on book_shelf(lower(name));
                create index if not exists book_shelf_author_ci_idx on book_shelf(lower(coalesce(author, '')));
                create table if not exists source_login_state (
                  source_id text primary key,
                  login_info text,
                  login_header text,
                  source_variable text,
                  source_kv text,
                  cookie_jar text,
                  updated_at integer not null
                );
                create table if not exists replace_rule (
                  id text primary key,
                  name text not null,
                  group_name text,
                  pattern text not null,
                  replacement text not null default '',
                  is_regex integer not null default 1,
                  scope text,
                  exclude_scope text,
                  scope_title integer not null default 0,
                  scope_content integer not null default 1,
                  is_enabled integer not null default 1,
                  sort_order integer not null default 0,
                  timeout_ms integer not null default 3000,
                  created_at integer not null,
                  updated_at integer not null
                );
                create index if not exists idx_replace_rule_enabled on replace_rule(is_enabled, sort_order asc);
                create index if not exists idx_replace_rule_group on replace_rule(group_name);
                create table if not exists http_tts (
                  id integer primary key,
                  name text not null,
                  url text not null,
                  header text,
                  content_type text,
                  concurrent_rate text,
                  login_url text,
                  login_check_js text,
                  login_ui text,
                  js_lib text,
                  enabled_cookie_jar integer not null default 0,
                  last_update_time integer not null
                );
                create index if not exists idx_http_tts_name on http_tts(name);
                -- ------------------------------------------------------------------
                -- 漫画链路（方案 E）：
                --   manga_chapter_cache = 章节图片清单 + 整章就绪度（L2 视图层）
                --   manga_image_cache   = 图片字节索引（L1 唯一副本 + GC 依据）
                -- 详见 docs/proposals/PROPOSAL-028-* 与 docs/decisions/ADR-028-*
                -- ------------------------------------------------------------------
                create table if not exists manga_chapter_cache (
                  source_id     text not null,
                  book_url      text not null,
                  chapter_url   text not null,
                  chapter_index integer not null default 0,
                  pinned        integer not null default 0,
                  images_json   text not null,
                  total         integer not null default 0,
                  ready         integer not null default 0,
                  skipped       integer not null default 0,
                  state         text not null default 'pending',
                  last_error    text,
                  created_at    integer not null,
                  updated_at    integer not null,
                  primary key (source_id, book_url, chapter_url)
                );
                create index if not exists idx_manga_chapter_state on manga_chapter_cache(source_id, book_url, state);
                create index if not exists idx_manga_chapter_pinned on manga_chapter_cache(pinned);
                create table if not exists manga_image_cache (
                  url_hash       text primary key,
                  cache_key      text not null default '',
                  source_id      text not null,
                  book_url       text not null,
                  chapter_url    text not null,
                  chapter_index  integer not null default 0,
                  segment_index  integer not null default 0,
                  upstream_url   text not null,
                  content_type   text not null default '',
                  byte_size      integer not null default 0,
                  width          integer,
                  height         integer,
                  state          text not null default 'pending',
                  last_error     text,
                  created_at     integer not null,
                  last_access_at integer not null,
                  file_path      text not null default ''
                );
                create index if not exists idx_manga_image_key on manga_image_cache(cache_key);
                create index if not exists idx_manga_image_book on manga_image_cache(source_id, book_url);
                create index if not exists idx_manga_image_chapter on manga_image_cache(source_id, book_url, chapter_url, segment_index);
                create index if not exists idx_manga_image_access on manga_image_cache(last_access_at asc);
                -- ------------------------------------------------------------------
                -- 有声书链路（方案 G2）：
                --   audio_cache = 音频字节索引（**URL 寻址**：key 可预测 ⇒ 前端直接拼 src，
                --                 不做内容寻址去重 —— 见 ADR-029 的 G-2 裁决）
                -- 没有"音频章节清单表"：整章就绪度由本表按 chapter_url 聚合即得（少一张表少一处漂移）。
                -- 详见 docs/proposals/PROPOSAL-029-* 与 docs/decisions/ADR-029-*
                -- ------------------------------------------------------------------
                create table if not exists audio_cache (
                  cache_key      text primary key,
                  source_id      text not null,
                  book_url       text not null,
                  chapter_url    text not null,
                  chapter_index  integer not null default 0,
                  segment_index  integer not null default 0,
                  upstream_url   text not null,
                  headers_json   text,
                  content_type   text not null default '',
                  byte_size      integer not null default 0,
                  duration_ms    integer not null default 0,
                  state          text not null default 'pending',
                  last_error     text,
                  pinned         integer not null default 0,
                  listened       integer not null default 0,
                  created_at     integer not null,
                  last_access_at integer not null,
                  file_path      text not null default ''
                );
                -- 刻意**不是**唯一索引：书源换了某一段的地址时，新 key 会挂在同一个 segment_index 上，
                -- 唯一索引会让那次 upsert 直接抛异常（一次书源变更 ⇒ 整章打不开）。
                -- 排序/拼装只需要索引能加速，不需要唯一性。
                create index if not exists idx_audio_cache_segment on audio_cache(source_id, book_url, chapter_url, segment_index);
                create index if not exists idx_audio_cache_book on audio_cache(source_id, book_url);
                create index if not exists idx_audio_cache_state on audio_cache(state);
                create index if not exists idx_audio_cache_access on audio_cache(last_access_at asc);
                create index if not exists idx_audio_cache_pinned on audio_cache(pinned);
                -- ------------------------------------------------------------------
                -- 小说正文插图链路：
                --   illustration_cache = 插图字节索引（URL 寻址 + 章节内序号 + 落盘相对路径）
                -- 落盘在 <data>/images/（与漫画 <data>/comic/ 分开，见 ADR-033）。
                -- 序号来自**正文里的出现顺序**，首次登记后不再改写（保证「序号 ↔ 文件」单射）。
                -- 详见 docs/proposals/PROPOSAL-034-* 与 docs/decisions/ADR-033-*
                -- ------------------------------------------------------------------
                create table if not exists illustration_cache (
                  url_hash       text primary key,
                  source_id      text not null,
                  book_url       text not null default '',
                  chapter_url    text not null default '',
                  chapter_index  integer not null default 0,
                  segment_index  integer not null default 0,
                  upstream_url   text not null,
                  content_type   text not null default '',
                  byte_size      integer not null default 0,
                  cache_key      text not null default '',
                  state          text not null default 'pending',
                  created_at     integer not null,
                  last_access_at integer not null,
                  file_path      text not null default ''
                );
                create index if not exists idx_illustration_chapter on illustration_cache(source_id, book_url, chapter_url, segment_index);
                create index if not exists idx_illustration_book on illustration_cache(source_id, book_url);
                """.trimIndent()

    private val CREATE_TABLE_RE = Regex("""(?is)^create\s+table\s+(?:if\s+not\s+exists\s+)?([`"\w.]+)\s*\((.*)\)$""")
    private val CREATE_INDEX_RE = Regex("""(?is)^create\s+index\s+(?:if\s+not\s+exists\s+)?([`"\w]+)\s+on\s+([`"\w.]+)\s*\((.*)\)$""")
    private val KEY_CONSTRAINT_RE = Regex("""(?is)^(?:primary\s+key|unique|constraint|foreign\s+key|check)\b""")
    private val NOCASE_RE = Regex("""(?i)\s*collate\s+nocase""")
    private val LINE_COMMENT_RE = Regex("""(?m)--[^\n]*""")
    private val BLOCK_COMMENT_RE = Regex("""(?s)/\*.*?\*/""")
    private val ORDER_SUFFIX_RE = Regex("""(?i)\s+(asc|desc)\s*$""")

    /** 引擎 ⇒ 一串**可逐条执行**的 DDL 语句（外部库目前只有 PostgreSQL）。 */
    fun statements(engine: DatabaseEngine): List<String> {
        if (engine == DatabaseEngine.SQLITE) return listOf(SQLITE_DDL)
        val clean = BLOCK_COMMENT_RE.replace(LINE_COMMENT_RE.replace(SQLITE_DDL, ""), "")
        val raw = clean.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val rendered = raw.mapNotNull { statement -> render(statement) }
        return if (engine == DatabaseEngine.POSTGRES) rendered + POSTGRES_EXTRA_DDL else rendered
    }

    /**
     * **PG 专属**的补充约束（与 SQLite 语义对齐，见 AGENTS/SESSION-059）。
     *
     * `book_group.name` 在 SQLite 里是 `unique collate nocase`（`News` / `news` 不允许共存）。
     * PG 没有 per-column 的 nocase 唯一约束，只能用**表达式唯一索引** `lower(name)` 达到同样效果。
     *
     * ⚠️ 但列级 `unique(name)` **必须保留**：`importBookGroups` 用的是
     * `insert … on conflict(name) do update`，PG 要求存在针对 `name` 列的普通唯一约束，
     * 只有表达式索引时 `on conflict(name)` 会报
     * 「there is no unique or exclusion constraint matching the ON CONFLICT specification」。
     * 所以是「普通唯一 + 表达式唯一」两条并存。
     */
    private val POSTGRES_EXTRA_DDL = listOf(
        "create unique index if not exists book_group_name_ci on book_group (lower(name))",
    )

    private fun render(statement: String): String? {
        CREATE_TABLE_RE.find(statement)?.let { match ->
            val table = match.groupValues[1].trim().trim('`', '"')
            val body = splitTopLevel(match.groupValues[2])
                .map { renderColumn(it) }
                .filter { it.isNotEmpty() }
                .joinToString(",\n")
            return "create table if not exists $table (\n$body\n)"
        }
        CREATE_INDEX_RE.find(statement)?.let { match ->
            val name = match.groupValues[1].trim()
            val table = match.groupValues[2].trim()
            val columns = splitTopLevel(match.groupValues[3]).map { renderIndexColumn(it) }
            return "create index if not exists $name on $table (${columns.joinToString(", ")})"
        }
        // 其它语句（本脚本里没有）原样下发，交给引擎自己判断
        return statement
    }

    private fun renderColumn(part: String): String {
        val text = part.trim()
        if (text.isEmpty()) return ""
        if (KEY_CONSTRAINT_RE.containsMatchIn(text)) return stripNocase(text)
        val tokens = text.split(Regex("\\s+"), limit = 3)
        val name = tokens[0]
        val rawType = tokens.getOrNull(1)?.lowercase() ?: return stripNocase(text)
        var rest = tokens.getOrNull(2).orEmpty()
        val type = when {
            rawType == "integer" && rest.contains("autoincrement", ignoreCase = true) -> "bigserial"
            rawType == "integer" -> "bigint"
            rawType == "real" -> "double precision"
            else -> rawType
        }
        if (rest.contains("autoincrement", ignoreCase = true)) {
            rest = rest.replace(Regex("""(?i)\s*autoincrement"""), "")
        }
        val constraints = stripNocase(rest).trim()
        return if (constraints.isEmpty()) "$name $type" else "$name $type $constraints"
    }

    private fun renderIndexColumn(raw: String): String {
        val column = raw.trim()
        val nocase = column.contains("nocase", ignoreCase = true)
        if (!nocase) return column
        // 查询侧已被翻译成 lower(x)，索引侧必须跟上，否则索引永远用不上
        val base = ORDER_SUFFIX_RE.replace(stripNocase(column), "").trim()
        return "lower($base)"
    }

    private fun stripNocase(text: String): String = NOCASE_RE.replace(text, "")

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(text.substring(start, index))
                    start = index + 1
                }
            }
        }
        parts.add(text.substring(start))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * 把分方言 DDL 真正落到库里（PostgreSQL 路径）。
 *
 * PG 支持 `create index if not exists`，所以重复启动天然幂等，不需要额外的「索引是否已存在」查询。
 */
internal fun applyExternalSchema(connection: Connection, engine: DatabaseEngine, log: (String) -> Unit) {
    var tables = 0
    var indexes = 0
    DatabaseSchema.statements(engine).forEach { sql ->
        val isIndex = sql.startsWith("create index", ignoreCase = true) ||
            sql.startsWith("create unique index", ignoreCase = true)
        try {
            connection.createStatement().use { it.executeUpdate(sql) }
        } catch (error: java.sql.SQLException) {
            // **只放行「唯一索引已有重复数据」这一种失败**：其它 DDL 错误必须照旧炸出来，
            // 否则表结构缺了也没人知道。
            if (isIndex && sql.contains("unique", ignoreCase = true) && isUniqueViolation(error)) {
                log(
                    "[db] 跳过唯一索引（库里已有重复数据，先清理再重启即可生效）：$sql\n" +
                        "         排查：select lower(name), count(*) from book_group group by 1 having count(*) > 1;",
                )
            } else {
                throw error
            }
        }
        if (isIndex) indexes++ else tables++
    }
    log("[db] 建表完成：$tables 张表 / $indexes 个索引")
}

/** PostgreSQL 唯一冲突（23505）/ SQLite 唯一约束（结果码 19）都算「已有重复数据」。 */
/** 是否为「唯一约束冲突」：PG 23505 / SQLite constraint failed / 通用 duplicate key 文案。 */
internal fun isUniqueViolation(error: java.sql.SQLException): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is java.sql.SQLException && current.sqlState == "23505") return true
        val message = current.message.orEmpty().lowercase()
        if (message.contains("duplicate key") ||
            message.contains("unique constraint") ||
            message.contains("unique index") ||
            message.contains("constraint failed")
        ) return true
        current = current.cause
    }
    return false
}
