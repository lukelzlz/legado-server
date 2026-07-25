package io.legado.server

import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class ServerConfig(
    val host: String,
    val port: Int,
    val databasePath: String,
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
                initialAdminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                secureCookies = env["LEGADO_SECURE_COOKIES"]?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}
