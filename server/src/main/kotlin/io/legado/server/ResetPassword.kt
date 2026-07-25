package io.legado.server

fun resetPasswordMain(args: Array<String>) {
    require(args.size == 1 && args[0].length >= 12) { "Usage: reset-password <new-password-at-least-12-chars>" }
    val config = ServerConfig.fromEnvironment()
    Database(config.databasePath).also { it.initialize(config.initialAdminPassword); it.resetPassword(args[0]) }
    println("Password reset; all sessions have been revoked.")
}
