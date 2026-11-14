package io.legado.server

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * `config/config.yaml` —— 面向用户的**配置文件**（替代「只能改环境变量」）。
 *
 * ## 为什么要有它
 *
 * 以前所有参数只有环境变量一条路（`LEGADO_PORT` / `ADMIN_PASSWORD` / …）。本地跑 jar 的人
 * 每次都得记一串 env，容器外几乎不可维护。现在改成：
 *
 * 1. 启动时在 **jar 所在目录**下找 `config/config.yaml`；
 * 2. **找不到就生成一份带注释的默认配置**（用户按注释改即可），然后照默认值继续启动；
 * 3. 优先级：**环境变量 > config.yaml > 内置默认值**——容器部署（只用 env）完全不受影响。
 *
 * ## 兼容约定
 *
 * - 配置文件**只补不改语义**：同名参数的环境变量仍然覆盖文件值，便于 Docker/CI 临时改参数；
 * - 解析失败（YAML 写坏了）**绝不让服务起不来**：打印告警后回落到环境变量/默认值；
 * - 文件里留空的项（`host:` 后面什么都不写）视为「没配」，继续往下一层回落。
 */
object ConfigFile {

    /** 配置目录名（相对 jar 所在目录）。 */
    const val DIRECTORY_NAME = "config"

    /** 配置文件名。 */
    const val FILE_NAME = "config.yaml"

    /** 解析出来的一层扁平键值（如 `server.port` -> `"8080"`）。 */
    typealias Values = Map<String, String>

    /**
     * 默认配置文件内容：**带中文注释**，用户按注释改完就是自己的模板。
     *
     * @param values 生成时写入的实际生效值（键用 YAML 路径，如 `server.port`）。
     *   首次启动若带了环境变量（例如启动脚本导出 `LEGADO_PORT=8080`、`LEGADO_DATA_DIR=...`），
     *   就把那些值写进文件 —— 否则「生成的模板」会和「实际跑的参数」不一致，
     *   下次启动（脚本不再导出 env）就会换个端口/换个数据目录，非常坑。
     */
    fun defaultContent(values: Values = emptyMap()): String {
        fun v(key: String, fallback: String): String = values[key]?.takeIf { it.isNotBlank() } ?: fallback
        return """
        # =============================================================================
        # Legado Server 配置文件
        #
        # 位置：与 legado-server.jar 同级的 config/config.yaml（首次启动自动生成）
        # 优先级：环境变量 > 本文件 > 内置默认值
        #   · 想在容器里临时改参数，仍然可以用 LEGADO_PORT / ADMIN_PASSWORD 等环境变量；
        #   · 某一项留空（冒号后什么都不写）等于「不配置」，会继续回落到环境变量/默认值。
        # 改完重启服务生效。
        # =============================================================================

        server:
          # 监听地址。0.0.0.0 = 所有网卡（局域网可访问），127.0.0.1 = 只本机
          host: ${v("server.host", "0.0.0.0")}
          # 监听端口（等价环境变量 LEGADO_PORT）
          port: ${v("server.port", "8080")}

        data:
          # 数据根目录（等价 LEGADO_DATA_DIR）。相对路径按「启动时的工作目录」解析
          dir: ${v("data.dir", "./data")}

        database:
          # 数据库类型：sqlite（默认）/ postgres
          # postgres：启动时会**自动建库（若不存在）+ 自动建表**，库名留空则用 legado。
          # 等价环境变量 LEGADO_DB_TYPE
          type: ${v("database.type", "sqlite")}

          sqlite:
            # 数据库文件。留空 = <data.dir>/legado.sqlite（等价 LEGADO_DATABASE）
            file: ${v("database.sqlite.file", "")}

          # PostgreSQL 连接参数（type 为 postgres 时使用）
          # 也可用环境变量 LEGADO_DB_URL / LEGADO_DB_USER / LEGADO_DB_PASSWORD 覆盖
          # 账号需要「建库 + 建表」权限；若库已由 DBA 建好则只连目标库、不再尝试建库。
          postgres:
            host: ${v("database.postgres.host", "127.0.0.1")}
            port: ${v("database.postgres.port", "5432")}
            database: ${v("database.postgres.database", "legado")}
            user: ${v("database.postgres.user", "postgres")}
            password: "${v("database.postgres.password", "")}"

        auth:
          # 首次启动时设置的管理员密码（等价 ADMIN_PASSWORD）。
          # 只在**数据库里还没有管理员**时生效；已初始化过就不会再被它覆盖。
          password: "${v("auth.password", "")}"
          # 会话 Cookie 是否带 Secure（等价 LEGADO_SECURE_COOKIES）。
          # 用 http:// 明文访问（局域网自建、反代未上 HTTPS）时必须改成 false，否则登录后立刻掉线。
          secure_cookies: ${v("auth.secure_cookies", "true")}

        cache:
          manga:
            # 漫画图片缓存配额（GB，支持小数；等价 LEGADO_MANGA_CACHE_MAX_GB）
            max_gb: ${v("cache.manga.max_gb", "10")}
            # 全局并发抓取上限（等价 LEGADO_MAX_GLOBAL_IMAGE_FETCHES）
            max_global_fetches: ${v("cache.manga.max_global_fetches", "6")}
            # 是否启用漫画后台预取 / 滑窗 GC（等价 LEGADO_MANGA_PREFETCH / LEGADO_MANGA_GC）
            prefetch: ${v("cache.manga.prefetch", "true")}
            gc: ${v("cache.manga.gc", "true")}

          audio:
            # 有声书音频缓存配额（GB，支持小数；等价 LEGADO_AUDIO_CACHE_MAX_GB）
            max_gb: ${v("cache.audio.max_gb", "20")}
            # 全局并发抓取上限（等价 LEGADO_MAX_GLOBAL_AUDIO_FETCHES）
            max_global_fetches: ${v("cache.audio.max_global_fetches", "3")}
            # 是否启用音频后台预取 / GC（等价 LEGADO_AUDIO_PREFETCH / LEGADO_AUDIO_GC）
            prefetch: ${v("cache.audio.prefetch", "true")}
            gc: ${v("cache.audio.gc", "true")}

        cover:
          # 封面自愈巡检（等价 LEGADO_COVER_SELFHEAL）：定时给缺封面的书架书补抓
          self_heal: ${v("cover.self_heal", "true")}
        """.trimIndent() + "\n"
    }

    /** 配置目录（`<baseDir>/config`）。 */
    fun directoryOf(baseDir: Path): Path = baseDir.resolve(DIRECTORY_NAME)

    /** 配置文件路径（`<baseDir>/config/config.yaml`）。 */
    fun fileOf(baseDir: Path): Path = directoryOf(baseDir).resolve(FILE_NAME)

    /**
     * 确保配置文件存在：不存在就写入默认内容并返回 `generated = true`。
     *
     * **绝不覆盖已有文件**（用户改过的模板必须保住）。
     */
    fun ensureDefault(baseDir: Path, values: Values = emptyMap()): Pair<Path, Boolean> {
        val file = fileOf(baseDir)
        if (Files.exists(file)) return file to false
        runCatching { Files.createDirectories(file.parent) }
        Files.writeString(file, defaultContent(values))
        return file to true
    }

    /**
     * 读取并**扁平化**配置（嵌套键用 `.` 连接，如 `cache.manga.max_gb`）。
     *
     * 解析失败返回 `null`（调用方据此回落环境变量/默认值并打印告警）。
     */
    fun readValues(file: Path): Values? = runCatching {
        if (!Files.exists(file)) return emptyMap()
        val root = Yaml().load<Any?>(Files.readString(file))
        flatten(root)
    }.getOrNull()

    /** 把 SnakeYAML 的嵌套结构压成 `a.b.c` -> 字符串（未配/空值不放进结果）。 */
    fun flatten(node: Any?, prefix: String = ""): Values {
        val out = LinkedHashMap<String, String>()
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                if (value is Map<*, *>) out.putAll(flatten(value, path))
                else {
                    val text = value?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) out[path] = text
                }
            }
            else -> Unit
        }
        return out
    }

    /**
     * **jar 所在目录**（配置文件放这里，用户解压/部署时一眼能看到）。
     *
     * 用 `protectionDomain.codeSource.location` 取自身位置；IDE/测试里拿到的是 classes 目录，
     * 此时回落到「当前工作目录」。
     */
    fun jarDirectory(): Path {
        val location = runCatching {
            ConfigFile::class.java.protectionDomain?.codeSource?.location?.toURI()
        }.getOrNull()
        val path = location?.let { runCatching { Path.of(it) }.getOrNull() }
        return if (path != null && Files.isRegularFile(path)) path.parent else Path.of("").toAbsolutePath()
    }
}
