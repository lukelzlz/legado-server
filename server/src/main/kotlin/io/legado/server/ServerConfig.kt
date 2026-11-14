package io.legado.server

import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class ServerConfig(
    val host: String,
    val port: Int,
    val databasePath: String,
    val coverCacheDirectory: Path,
    val webDavDirectory: Path,
    val localBooksDirectory: Path = coverCacheDirectory.resolveSibling("local_books"),
    val initialAdminPassword: String?,
    val secureCookies: Boolean,
    /**
     * 漫画图片字节缓存目录（L1 唯一副本）。默认 `<data>/comic`，与封面 `covers` 平级。
     *
     * 落盘结构见 [ImageCache]：`<书名 (书哈希)>/<章节名 (章节哈希)>/8位分段号.扩展名`。
     */
    val comicCacheDirectory: Path = coverCacheDirectory.resolveSibling(COMIC_DIR_NAME),
    /**
     * **小说插图**缓存目录（预留，本次不实现写入）。默认 `<data>/images`，与 [comicCacheDirectory] 平级。
     *
     * 为什么必须与漫画分开：漫画缓存的读取定位是「扫目录名里的 `(书哈希)` / `(章节哈希)`」
     * （见 [AtomicFileStore.findByNameFree]），插图若混进同一个根，两套语义会互相扫到。
     * 启动时只保证这个目录**存在**（空目录），不写任何文件；写入实现留给后续改动。
     */
    val illustrationCacheDirectory: Path = coverCacheDirectory.resolveSibling(ILLUSTRATION_DIR_NAME),
    /**
     * 有声书音频缓存目录（方案 G2）。默认 `<data>/audio`。
     */
    val audioCacheDirectory: Path = coverCacheDirectory.resolveSibling("audio"),
    /**
     * 有声书音频缓存配额（字节）。默认 **20 GiB**（G-4 裁决），环境变量 `LEGADO_AUDIO_CACHE_MAX_GB`。
     *
     * 与漫画的 10 GiB **分开配置**：两者是不同量级的字节（一章音频 10~60 MiB vs 一章漫画十几 MB），
     * 共用一个配额会让"听书把漫画的额度挤掉"变成常态。
     */
    val audioCacheQuotaBytes: Long = DEFAULT_AUDIO_CACHE_QUOTA_BYTES,
    /**
     * 整机同时在飞的音频抓取上限。默认 **3**（ADR-029 定 `MAX_GLOBAL_AUDIO_FETCHES = 3`）。
     *
     * 数字比图片（6）小是刻意的：音频是 10~60 MiB 的大流，并发 3 已经在吃满带宽。
     * 同 AGENTS.md：必须加在**抓取收口**，加在单章任务内等于没限流。
     */
    val maxGlobalAudioFetches: Int = DEFAULT_MAX_GLOBAL_AUDIO_FETCHES,
    /**
     * 音频后台缓存/预取总开关（P2）。`LEGADO_AUDIO_PREFETCH=false` ⇒ 只按需抓当前播放的那一段。
     */
    val audioPrefetchEnabled: Boolean = true,
    /**
     * 音频缓存 GC 总开关（P2）。`LEGADO_AUDIO_GC=false` ⇒ 磁盘只增不减（排障用）。
     */
    val audioGcEnabled: Boolean = true,
    /**
     * 漫画图片缓存配额（字节）。默认 **10 GiB** —— 由 lukelzlz 于 2026-10-13 拍板
     * （PR #45 commit `0ab69e2`：原 2 GiB 上调至 10GB），环境变量 `LEGADO_MANGA_CACHE_MAX_GB` 覆盖。
     */
    val imageCacheQuotaBytes: Long = DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
    /**
     * 整机同时在飞的图片抓取上限。
     *
     * ⚠️ 必须加在**抓取收口**而不是单章任务内：任务数量本身无界时，单任务内的限流等于没限流
     * （历史事故见 SESSION-025：418 本续做把服务打到 4 分钟超时）。
     */
    val maxGlobalImageFetches: Int = DEFAULT_MAX_GLOBAL_IMAGE_FETCHES,
    /**
     * 后台预取总开关（P2）。`LEGADO_MANGA_PREFETCH=false` 关掉 ⇒ 只按需抓当前可视图片。
     *
     * 关掉它是 P2 的**回滚开关**：预取是纯增强，关掉后阅读链路必须完全照常（这是设计约束，
     * 所以预取的一切失败都只记日志、绝不冒泡到阅读接口）。
     */
    val mangaPrefetchEnabled: Boolean = true,
    /**
     * 图片缓存 GC 总开关（P2）。`LEGADO_MANGA_GC=false` 关掉 ⇒ 磁盘只增不减（排障用）。
     */
    val mangaGcEnabled: Boolean = true,
    /**
     * 封面**常驻自愈**总开关。`LEGADO_COVER_SELFHEAL=false` 关掉 ⇒ 只在导入时补抓，
     * 后期封面文件丢了不再自动补（排障/回滚用）。
     *
     * 默认开：`cover_key` 有值不等于封面还在（文件可能被磁盘清理/误删/换卷搬走），
     * 只有常驻巡检能把这种「书架一片裂图」自动治回来，见 [CoverSelfHeal]。
     */
    val coverSelfHealEnabled: Boolean = true,
    /**
     * 数据库类型：`sqlite`（默认）/ `postgres`。
     *
     * 两种都**真实可用**：postgres 会在启动时自动建库（若不存在）+ 自动建表，
     * 方言差异（`on conflict` / `collate nocase` / `pragma` / `real` / DDL）收敛在
     * [SqlTranslator] 与 [DatabaseSchema] 两处，业务代码无感。
     */
    val databaseType: String = "sqlite",
    /** 非 sqlite 时的 JDBC 连接串（由 [fromSources] 按类型+主机/端口/库名拼出）。 */
    val databaseJdbcUrl: String? = null,
    val databaseUser: String? = null,
    val databasePassword: String? = null,
) {
    /** 是否为关系型以外的外部库（postgres）——用于选择连接方式与方言。 */
    val isExternalDatabase: Boolean get() = databaseType.lowercase() != "sqlite"

    /**
     * 数据层连接规格（[Database] 的唯一入参）。
     *
     * sqlite ⇒ 只用 [databasePath]；postgres ⇒ 用 [databaseJdbcUrl] + 账号密码，
     * 启动时会自动建库 + 自动建表（见 [DatabaseSpec.ensureDatabase]）。
     */
    fun databaseSpec(): DatabaseSpec = if (isExternalDatabase) {
        DatabaseSpec(
            engine = DatabaseEngine.of(databaseType),
            path = databasePath,
            jdbcUrl = databaseJdbcUrl,
            user = databaseUser,
            password = databasePassword,
        )
    } else {
        DatabaseSpec.sqlite(databasePath)
    }

    companion object {
        /** 漫画缓存根目录名（**唯一来源**：默认路径与环境变量路径都引用它）。 */
        const val COMIC_DIR_NAME = "comic"

        /** 小说插图缓存根目录名（预留，不写入；改名时别把它与漫画根混起来）。 */
        const val ILLUSTRATION_DIR_NAME = "images"

        /** 封面缓存根目录名（`<书名 (书哈希).扩展名>`，见 [CoverCache]）。 */
        const val COVER_DIR_NAME = "covers"
        /** 默认 10 GiB（PR #45 `0ab69e2` 拍板）。 */
        const val DEFAULT_IMAGE_CACHE_QUOTA_GB: Double = 10.0
        const val DEFAULT_IMAGE_CACHE_QUOTA_BYTES: Long = (DEFAULT_IMAGE_CACHE_QUOTA_GB * 1024 * 1024 * 1024).toLong()
        const val DEFAULT_MAX_GLOBAL_IMAGE_FETCHES: Int = 6

        /** 音频默认 20 GiB（G-4 裁决）与并发 3（ADR-029）。 */
        const val DEFAULT_AUDIO_CACHE_QUOTA_GB: Double = 20.0
        const val DEFAULT_AUDIO_CACHE_QUOTA_BYTES: Long = (DEFAULT_AUDIO_CACHE_QUOTA_GB * 1024 * 1024 * 1024).toLong()
        const val DEFAULT_MAX_GLOBAL_AUDIO_FETCHES: Int = 3

        /** GC 巡检间隔。占用检查很便宜（一条 `sum`），真正的全盘扫描只在超阈值时才发生。 */
        const val IMAGE_GC_INTERVAL_MS: Long = 5 * 60 * 1000L

        /** 音频 GC 巡检间隔（同漫画的节奏）。 */
        const val AUDIO_GC_INTERVAL_MS: Long = 5 * 60 * 1000L

        /**
         * 封面自愈的**首轮延迟**：启动后先等 2 分钟再开始巡检。
         *
         * 见 AGENTS.md `[启动/缓存]`：启动期的续做/预取已经要吃 IO 与书源并发，
         * 封面自愈再挤进来就会把「服务已启动」变成「服务一直超时」。它是**纯保洁**，
         * 晚两分钟毫无损失。
         */
        const val COVER_SELF_HEAL_START_DELAY_MS: Long = 2 * 60 * 1000L

        /**
         * 封面自愈的巡检间隔（10 分钟一轮，每轮最多 20 本）。
         *
         * 20 本 / 10 分钟 = 120 本/小时，一个 400 本的车架半天内扫完一轮；
         * 而正常状态下「缺封面的书」是个位数，这个节奏对上游几乎不可见。
         */
        const val COVER_SELF_HEAL_INTERVAL_MS: Long = 10 * 60 * 1000L

        /**
         * 从 `config/config.yaml` + 环境变量装配配置。
         *
         * - 配置文件不存在 ⇒ 生成一份带注释的默认配置，并返回 [LoadResult.FirstRun]
         *   （**首次启动只生成配置、不真正启动**，用户改完再启动，见用户要求）；
         * - 优先级：环境变量 > 配置文件 > 内置默认值；
         * - 文件写坏了也不会让服务起不来（回落环境变量/默认值，只在日志里告警）。
         */
        fun load(
            baseDir: Path = ConfigFile.jarDirectory(),
            env: Map<String, String> = System.getenv(),
            /** 日志接缝（测试/自定义启动器可用）。 */
            log: (String) -> Unit = { message -> System.err.println(message) },
        ): LoadResult {
            // ① 文件不存在时，用「环境变量 + 内置默认值」算出的**实际生效值**去渲染模板：
            //    否则启动脚本导出的 LEGADO_DATA_DIR/LEGADO_PORT 会与生成的模板不一致，
            //    下次启动（脚本不再导出 env）就会换端口/换数据目录。
            // ② 配置文件位置支持环境变量（容器场景：jar 在 root 所有的 /app/lib，写不进去）：
            //    LEGADO_CONFIG_FILE > <LEGADO_DATA_DIR>/config/config.yaml > <jar>/config/config.yaml
            val explicitFile = runCatching { ConfigFile.resolveFile(baseDir, env) }
                .getOrDefault(ConfigFile.fileOf(baseDir))
            val templateValues = if (java.nio.file.Files.exists(explicitFile)) {
                emptyMap()
            } else {
                effectiveValues(fromSources(emptyMap(), env))
            }
            val (file, generated) = runCatching { ConfigFile.ensureDefaultAt(explicitFile, templateValues) }
                .getOrElse { error ->
                    log("[config] 无法在 $explicitFile 生成 config.yaml：${error.message}（继续用环境变量/默认值）")
                    return LoadResult.Ready(fromSources(emptyMap(), env))
                }
            if (generated) {
                log("[config] 已生成默认配置文件：$file")
                // 用户要求：**首次启动仅创建配置文件**，第二次启动再正式启动。
                // 但容器/K8s/CI 里「起一次就退出」会被判定为崩溃重启，而这类部署本来就用环境变量配置 ⇒
                // 只要检测到环境变量已把关键项配好，就不中断启动，直接 Ready。
                if (isEnvDriven(env)) {
                    log("[config] 检测到环境变量部署（无需等待人工改配置），本次直接启动")
                    return LoadResult.Ready(fromSources(emptyMap(), env))
                }
                // 桌面 / 裸 jar：让用户先按注释把数据库、端口、密码改好再启动。
                return LoadResult.FirstRun(file)
            }
            val values = ConfigFile.readValues(file)
            if (values == null) {
                log("[config] 解析失败：$file 不是合法 YAML，已回落到环境变量/默认值")
                return LoadResult.Ready(fromSources(emptyMap(), env))
            }
            log("[config] 已加载配置文件：$file（共 ${values.size} 项；环境变量优先）")
            return LoadResult.Ready(fromSources(values, env))
        }

        /**
         * 把一份 [ServerConfig] 反推成 config.yaml 的扁平键值（用于首次生成「与当前实际一致」的模板）。
         *
         * 只写会影响启动行为的项；路径全部转成绝对路径，避免生成的模板依赖启动时的工作目录。
         */
        fun effectiveValues(config: ServerConfig): ConfigFile.Values = linkedMapOf<String, String>().apply {
            put("server.host", config.host)
            put("server.port", config.port.toString())
            put("database.sqlite.file", config.databasePath)
            put("auth.secure_cookies", config.secureCookies.toString())
            // 数据目录从数据库文件反推（`<data>/legado.sqlite`），保证迁移到文件后仍指向同一份数据
            runCatching {
                val db = Path.of(config.databasePath)
                db.parent?.let { put("data.dir", it.toAbsolutePath().toString()) }
            }
            config.initialAdminPassword?.takeIf { it.isNotBlank() }?.let { put("auth.password", it) }
            put("cache.manga.max_gb", formatGb(config.imageCacheQuotaBytes))
            put("cache.manga.max_global_fetches", config.maxGlobalImageFetches.toString())
            put("cache.manga.prefetch", config.mangaPrefetchEnabled.toString())
            put("cache.manga.gc", config.mangaGcEnabled.toString())
            put("cache.audio.max_gb", formatGb(config.audioCacheQuotaBytes))
            put("cache.audio.max_global_fetches", config.maxGlobalAudioFetches.toString())
            put("cache.audio.prefetch", config.audioPrefetchEnabled.toString())
            put("cache.audio.gc", config.audioGcEnabled.toString())
            put("cover.self_heal", config.coverSelfHealEnabled.toString())
        }

        private fun formatGb(bytes: Long): String {
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            return if (gb == gb.toLong().toDouble()) gb.toLong().toString() else String.format("%.1f", gb)
        }

        /**
         * 判断这次启动是不是「纯环境变量部署」（Docker / K8s / CI）。
         *
         * 判据：只要用户显式给了**任一关键环境变量**，就说明他并不打算靠人工编辑 config.yaml，
         * 此时首次启动生成模板后不能退出（否则容器启动即 Exited）。
         * 故意不把 `LEGADO_DATA_DIR` 排除在外：镜像里它几乎总是被设置。
         */
        internal fun isEnvDriven(env: Map<String, String> = System.getenv()): Boolean {
            val keys = listOf(
                "ADMIN_PASSWORD", "LEGADO_CONFIG_FILE", "LEGADO_DATA_DIR", "LEGADO_HOST", "LEGADO_PORT",
                "LEGADO_DB_TYPE", "LEGADO_DB_URL", "LEGADO_DB_USER", "LEGADO_DB_PASSWORD",
                "LEGADO_SECURE_COOKIES", "LEGADO_DATABASE",
            )
            return keys.any { !env[it].isNullOrBlank() }
        }

        /**
         * 旧的「只读环境变量」入口。
         *
         * 保留它是因为**测试与 `reset-password` 都在用**：它们不应该因为
         * 「机器上恰好有 config.yaml」而读出别的数据库/密码。
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = fromSources(emptyMap(), env)

        /**
         * 真正的装配逻辑：`fileValues` 是 config.yaml 扁平化后的键值（如 `server.port`），
         * `env` 里的同名环境变量优先。
         */
        fun fromSources(fileValues: ConfigFile.Values = emptyMap(), env: Map<String, String> = System.getenv()): ServerConfig {
            /** 环境变量 > 配置文件；空串视为「没配」。 */
            fun pick(envKey: String, fileKey: String): String? =
                env[envKey]?.takeIf { it.isNotBlank() } ?: fileValues[fileKey]?.takeIf { it.isNotBlank() }

            fun pickBoolean(envKey: String, fileKey: String, default: Boolean): Boolean =
                pick(envKey, fileKey)?.toBooleanStrictOrNull() ?: default

            fun pickInt(envKey: String, fileKey: String, default: Int): Int =
                pick(envKey, fileKey)?.toIntOrNull()?.takeIf { it > 0 } ?: default

            fun pickGb(envKey: String, fileKey: String, defaultGb: Double): Long =
                (pick(envKey, fileKey)?.toDoubleOrNull() ?: defaultGb)
                    .takeIf { it > 0 }?.let { (it * 1024 * 1024 * 1024).toLong() }
                    ?: (defaultGb * 1024 * 1024 * 1024).toLong()

            val rawDataDir = pick("LEGADO_DATA_DIR", "data.dir") ?: run {
                val rootData = Path.of("/data")
                if (java.nio.file.Files.isDirectory(rootData) && java.nio.file.Files.isWritable(rootData)) {
                    "/data"
                } else {
                    "./data"
                }
            }
            val dataDir = Path.of(rawDataDir).toAbsolutePath()
            // 数据库类型只解析一次：`mysql` 已移除 ⇒ 这里**明确报错**（绝不静默回落 sqlite）
            val databaseType = databaseTypeOf(pick("LEGADO_DB_TYPE", "database.type"))
            return ServerConfig(
                host = pick("LEGADO_HOST", "server.host") ?: "0.0.0.0",
                port = pick("LEGADO_PORT", "server.port")?.toIntOrNull() ?: 8080,
                // 新键 database.sqlite.file；data.database 是旧键，保留兼容（老配置文件里写过它）
                databasePath = pick("LEGADO_DATABASE", "database.sqlite.file")
                    ?: pick("LEGADO_DATABASE", "data.database")
                    ?: dataDir.resolve("legado.sqlite").absolutePathString(),
                coverCacheDirectory = dataDir.resolve(COVER_DIR_NAME),
                webDavDirectory = dataDir.resolve("webdav"),
                localBooksDirectory = dataDir.resolve("local_books"),
                initialAdminPassword = pick("ADMIN_PASSWORD", "auth.password")?.takeIf { it.isNotBlank() },
                secureCookies = pickBoolean("LEGADO_SECURE_COOKIES", "auth.secure_cookies", true),
                comicCacheDirectory = dataDir.resolve(COMIC_DIR_NAME),
                illustrationCacheDirectory = dataDir.resolve(ILLUSTRATION_DIR_NAME),
                audioCacheDirectory = dataDir.resolve("audio"),
                // 配额按 GB 配置（支持小数），默认 10 GiB —— 见 ADR-028 的滑窗 GC 裁决
                imageCacheQuotaBytes = pickGb("LEGADO_MANGA_CACHE_MAX_GB", "cache.manga.max_gb", DEFAULT_IMAGE_CACHE_QUOTA_GB),
                maxGlobalImageFetches = pickInt(
                    "LEGADO_MAX_GLOBAL_IMAGE_FETCHES", "cache.manga.max_global_fetches", DEFAULT_MAX_GLOBAL_IMAGE_FETCHES,
                ),
                mangaPrefetchEnabled = pickBoolean("LEGADO_MANGA_PREFETCH", "cache.manga.prefetch", true),
                mangaGcEnabled = pickBoolean("LEGADO_MANGA_GC", "cache.manga.gc", true),
                // 音频：配额按 GB 配置（支持小数），默认 20 GiB（G-4 裁决）
                audioCacheQuotaBytes = pickGb("LEGADO_AUDIO_CACHE_MAX_GB", "cache.audio.max_gb", DEFAULT_AUDIO_CACHE_QUOTA_GB),
                maxGlobalAudioFetches = pickInt(
                    "LEGADO_MAX_GLOBAL_AUDIO_FETCHES", "cache.audio.max_global_fetches", DEFAULT_MAX_GLOBAL_AUDIO_FETCHES,
                ),
                audioPrefetchEnabled = pickBoolean("LEGADO_AUDIO_PREFETCH", "cache.audio.prefetch", true),
                audioGcEnabled = pickBoolean("LEGADO_AUDIO_GC", "cache.audio.gc", true),
                coverSelfHealEnabled = pickBoolean("LEGADO_COVER_SELFHEAL", "cover.self_heal", true),
                databaseType = databaseType,
                databaseJdbcUrl = externalJdbcUrl(databaseType, fileValues, env),
                databaseUser = when (databaseType) {
                    "postgres" -> pick("LEGADO_DB_USER", "database.postgres.user")
                    else -> null
                },
                databasePassword = when (databaseType) {
                    "postgres" -> pick("LEGADO_DB_PASSWORD", "database.postgres.password")
                    else -> null
                },
            )
        }

        /** 归一化数据库类型：只认 sqlite/postgres（`postgresql` 归一到 postgres），其余值按 sqlite。 */
        private fun databaseTypeOf(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "postgres", "postgresql" -> "postgres"
            "mysql" -> throw UnsupportedDatabaseTypeException(MYSQL_REMOVED_MESSAGE)
            else -> "sqlite"
        }

        /**
         * 外部数据库连接串（`LEGADO_DB_URL` 优先，其次按类型拼）。
         *
         * 注意：这里的库名（`database.postgres.database`）允许**尚不存在** ——
         * 服务启动时会自动建库（见 [DatabaseSpec.ensureDatabase]）。
         */
        private fun externalJdbcUrl(type: String, fileValues: ConfigFile.Values, env: Map<String, String>): String? {
            env["LEGADO_DB_URL"]?.takeIf { it.isNotBlank() }?.let { return it }
            fun fileValue(key: String) = fileValues[key]?.takeIf { it.isNotBlank() }
            return when (type) {
                "postgres" -> {
                    val host = fileValue("database.postgres.host") ?: "127.0.0.1"
                    val port = fileValue("database.postgres.port") ?: "5432"
                    val db = fileValue("database.postgres.database") ?: "legado"
                    "jdbc:postgresql://$host:$port/$db"
                }
                else -> null
            }
        }
    }

    /** [load] 的结果：要么「已就绪，可以启动」，要么「首次启动，只生成了配置」。 */
    sealed interface LoadResult {
        data class Ready(val config: ServerConfig) : LoadResult

        /** 首次启动：配置已生成到 [file]，本次**不启动**，等用户改完再启动。 */
        data class FirstRun(val file: Path) : LoadResult
    }
}
