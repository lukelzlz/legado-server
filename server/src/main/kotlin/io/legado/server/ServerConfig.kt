package io.legado.server

import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class ServerConfig(
    val host: String,
    val port: Int,
    val databasePath: String,
    val coverCacheDirectory: Path,
    val initialAdminPassword: String?,
    val secureCookies: Boolean,
) {
    companion object {
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
                initialAdminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                secureCookies = env["LEGADO_SECURE_COOKIES"]?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}
