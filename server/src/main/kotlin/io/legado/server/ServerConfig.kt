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
) {
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

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val rawDataDir = env["LEGADO_DATA_DIR"] ?: run {
                val rootData = Path.of("/data")
                if (java.nio.file.Files.isDirectory(rootData) && java.nio.file.Files.isWritable(rootData)) {
                    "/data"
                } else {
                    "./data"
                }
            }
            val dataDir = Path.of(rawDataDir).toAbsolutePath()
            return ServerConfig(
                host = env["LEGADO_HOST"] ?: "0.0.0.0",
                port = env["LEGADO_PORT"]?.toIntOrNull() ?: 8080,
                databasePath = env["LEGADO_DATABASE"] ?: dataDir.resolve("legado.sqlite").absolutePathString(),
                coverCacheDirectory = dataDir.resolve(COVER_DIR_NAME),
                webDavDirectory = dataDir.resolve("webdav"),
                localBooksDirectory = dataDir.resolve("local_books"),
                initialAdminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                secureCookies = env["LEGADO_SECURE_COOKIES"]?.toBooleanStrictOrNull() ?: true,
                comicCacheDirectory = dataDir.resolve(COMIC_DIR_NAME),
                illustrationCacheDirectory = dataDir.resolve(ILLUSTRATION_DIR_NAME),
                audioCacheDirectory = dataDir.resolve("audio"),
                // 配额按 GB 配置（支持小数），默认 10 GiB —— 见 ADR-028 的滑窗 GC 裁决
                imageCacheQuotaBytes = (env["LEGADO_MANGA_CACHE_MAX_GB"]?.toDoubleOrNull() ?: DEFAULT_IMAGE_CACHE_QUOTA_GB)
                    .takeIf { it > 0 }?.let { (it * 1024 * 1024 * 1024).toLong() }
                    ?: DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
                maxGlobalImageFetches = env["LEGADO_MAX_GLOBAL_IMAGE_FETCHES"]?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_MAX_GLOBAL_IMAGE_FETCHES,
                mangaPrefetchEnabled = env["LEGADO_MANGA_PREFETCH"]?.toBooleanStrictOrNull() ?: true,
                mangaGcEnabled = env["LEGADO_MANGA_GC"]?.toBooleanStrictOrNull() ?: true,
                // 音频：配额按 GB 配置（支持小数），默认 20 GiB（G-4 裁决）
                audioCacheQuotaBytes = (env["LEGADO_AUDIO_CACHE_MAX_GB"]?.toDoubleOrNull() ?: DEFAULT_AUDIO_CACHE_QUOTA_GB)
                    .takeIf { it > 0 }?.let { (it * 1024 * 1024 * 1024).toLong() }
                    ?: DEFAULT_AUDIO_CACHE_QUOTA_BYTES,
                maxGlobalAudioFetches = env["LEGADO_MAX_GLOBAL_AUDIO_FETCHES"]?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_MAX_GLOBAL_AUDIO_FETCHES,
                audioPrefetchEnabled = env["LEGADO_AUDIO_PREFETCH"]?.toBooleanStrictOrNull() ?: true,
                audioGcEnabled = env["LEGADO_AUDIO_GC"]?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}
