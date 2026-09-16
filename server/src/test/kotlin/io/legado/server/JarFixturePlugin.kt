package io.legado.server

import io.legado.plugin.api.LegadoPlugin
import io.legado.plugin.api.PluginContext
import io.legado.plugin.api.PluginResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test fixture for the JVM (jar) plugin path.
 *
 * The plugin tests build a jar around *this* class at runtime, so `ServiceLoader` discovery,
 * class loading through a plugin classloader and `deactivate()` on shutdown are all exercised for
 * real instead of being simulated. It lives in the test sources on purpose: the server ships no
 * example plugins, but the jar runtime still needs coverage.
 */
class JarFixturePlugin : LegadoPlugin {

    override fun activate(context: PluginContext) {
        flag.set(false)
        context.route("GET", "/hello") { PluginResponse.json(mapOf("from" to "jar")) }
        context.routePublic("GET", "/open") { PluginResponse.text("open") }
    }

    override fun deactivate() {
        flag.set(true)
    }

    companion object {
        private val flag = AtomicBoolean(false)

        /** True once the host called [deactivate]; lets a test prove teardown really happened. */
        val deactivated: Boolean get() = flag.get()

        fun reset() = flag.set(false)
    }
}
