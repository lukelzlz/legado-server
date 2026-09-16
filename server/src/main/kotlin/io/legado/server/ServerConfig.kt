package io.legado.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class ServerConfig(
    val host: String,
    val port: Int,
    val databasePath: String,
    val coverCacheDirectory: Path,
    /**
     * Where plugin folders live. The default only matters for callers that build a config by hand
     * (tests): each such config gets its own empty temp folder so plugin discovery stays isolated,
     * while [fromEnvironment] always supplies the real path next to the data directory.
     */
    val pluginsDirectory: Path = Files.createTempDirectory("legado-plugins").toAbsolutePath(),
    val initialAdminPassword: String?,
    val secureCookies: Boolean,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val dataDir = Path.of(env["LEGADO_DATA_DIR"] ?: "/data").toAbsolutePath()
            return ServerConfig(
                host = env["LEGADO_HOST"] ?: "0.0.0.0",
                port = env["LEGADO_PORT"]?.toIntOrNull() ?: 8080,
                databasePath = env["LEGADO_DATABASE"] ?: dataDir.resolve("legado.sqlite").absolutePathString(),
                coverCacheDirectory = dataDir.resolve("covers"),
                // Plugin folders live with the data they act on, so backing up (or Docker-mounting)
                // the data directory carries the installed plugins along with it.
                pluginsDirectory = Path.of(env["LEGADO_PLUGINS_DIR"] ?: dataDir.resolve("plugins").absolutePathString()).toAbsolutePath(),
                initialAdminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                secureCookies = env["LEGADO_SECURE_COOKIES"]?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}
