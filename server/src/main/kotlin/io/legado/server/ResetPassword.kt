package io.legado.server

fun resetPasswordMain(args: Array<String>) {
    require(args.size == 1 && args[0].isNotBlank()) { "Usage: reset-password <new-password>" }
    val config = ServerConfig.fromEnvironment()
    // 与正常启动走**同一个**连接规格：postgres 下重置密码也必须落在真实库里
    Database(config.databaseSpec()).also { it.initialize(config.initialAdminPassword); it.resetPassword(args[0]) }
    println("Password reset; all sessions have been revoked.")
}
