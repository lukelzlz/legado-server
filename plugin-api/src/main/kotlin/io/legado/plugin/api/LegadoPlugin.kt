package io.legado.plugin.api

/**
 * Entry point of a JVM plugin.
 *
 * The server discovers implementations through [java.util.ServiceLoader], so a plugin jar must
 * ship `META-INF/services/io.legado.plugin.api.LegadoPlugin` naming its implementation class.
 * The class needs a public no-argument constructor.
 *
 * [activate] is called once while the server boots (or when the plugin is enabled at runtime).
 * Everything the plugin registers — routes, event listeners, scheduled tasks — is dropped again
 * when [deactivate] returns, so both methods must be safe to call repeatedly.
 */
interface LegadoPlugin {
    fun activate(context: PluginContext)

    fun deactivate() {}
}
