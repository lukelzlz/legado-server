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
     * 漫画图片字节缓存目录（L1 唯一副本）。默认 `<data>/images`，与封面 `covers` 平级。
     */
    val imageCacheDirectory: Path = coverCacheDirectory.resolveSibling("images"),
    /**
     * 有声书音频缓存目录（方案 G2）。默认 `<data>/audio`。
     * P1 阶段暂未使用，先落配置以免后续再动这个类（见《方案G2-详细设计》G3）。
     */
    val audioCacheDirectory: Path = coverCacheDirectory.resolveSibling("audio"),
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
) {
    companion object {
        /** 默认 10 GiB（PR #45 `0ab69e2` 拍板）。 */
        const val DEFAULT_IMAGE_CACHE_QUOTA_GB: Double = 10.0
        const val DEFAULT_IMAGE_CACHE_QUOTA_BYTES: Long = (DEFAULT_IMAGE_CACHE_QUOTA_GB * 1024 * 1024 * 1024).toLong()
        const val DEFAULT_MAX_GLOBAL_IMAGE_FETCHES: Int = 6
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
                coverCacheDirectory = dataDir.resolve("covers"),
                webDavDirectory = dataDir.resolve("webdav"),
                localBooksDirectory = dataDir.resolve("local_books"),
                initialAdminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                secureCookies = env["LEGADO_SECURE_COOKIES"]?.toBooleanStrictOrNull() ?: true,
                imageCacheDirectory = dataDir.resolve("images"),
                audioCacheDirectory = dataDir.resolve("audio"),
                // 配额按 GB 配置（支持小数），默认 10 GiB —— 见 ADR-028 的滑窗 GC 裁决
                imageCacheQuotaBytes = (env["LEGADO_MANGA_CACHE_MAX_GB"]?.toDoubleOrNull() ?: DEFAULT_IMAGE_CACHE_QUOTA_GB)
                    .takeIf { it > 0 }?.let { (it * 1024 * 1024 * 1024).toLong() }
                    ?: DEFAULT_IMAGE_CACHE_QUOTA_BYTES,
                maxGlobalImageFetches = env["LEGADO_MAX_GLOBAL_IMAGE_FETCHES"]?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_MAX_GLOBAL_IMAGE_FETCHES,
            )
        }
    }
}
