package io.legado.server

fun resetPasswordMain(args: Array<String>) {
    require(args.size == 1 && args[0].isNotBlank()) { "Usage: reset-password <new-password>" }
    val config = ServerConfig.fromEnvironment()
    Database(config.databasePath).also { it.initialize(config.initialAdminPassword); it.resetPassword(args[0]) }
    println("Password reset; all sessions have been revoked.")
}
