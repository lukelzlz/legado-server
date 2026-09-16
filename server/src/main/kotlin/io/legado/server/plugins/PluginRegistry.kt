package io.legado.server.plugins

import io.legado.plugin.api.LegadoPlugin
import io.legado.plugin.api.PluginEventListener
import io.legado.plugin.api.PluginRouteHandler
import io.legado.plugin.api.PluginTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** A route contributed by a plugin, mounted at `/api/plugins/<pluginId>/r/<path>`. */
class RegisteredRoute(
    val pluginId: String,
    val method: String,
    val path: String,
    val isPublic: Boolean,
    val handler: PluginRouteHandler,
)

class RegisteredListener(
    val pluginId: String,
    val eventType: String,
    val handler: PluginEventListener,
)

/** Schedules recurring plugin work; implemented by the manager so tasks die with the plugin. */
interface PluginScheduler {
    fun schedule(pluginId: String, initialDelayMs: Long, periodMs: Long, task: Runnable): PluginTask
}

/** Publishes events to every subscriber. */
interface PluginEventPublisher {
    fun publish(type: String, payload: Map<String, Any?>, origin: String)
}

/**
 * Everything the host knows about one plugin folder.
 *
 * The instance is created when a plugin loads and thrown away when it unloads, so all registrations
 * (routes, listeners, scheduled tasks) simply disappear with it — there is no separate cleanup
 * bookkeeping that could drift out of sync.
 */
class LoadedPlugin(
    val descriptor: PluginDescriptor,
    /** Effective enabled state: the persisted user choice, falling back to the manifest default. */
    @Volatile var enabled: Boolean,
) {
    val manifest: PluginManifest get() = descriptor.manifest
    val id: String get() = manifest.id
    val directory: Path get() = descriptor.directory

    /** Plugin-owned writable folder, created on load. */
    val dataDirectory: Path = directory.resolve("data")

    val routes = CopyOnWriteArrayList<RegisteredRoute>()
    val listeners = CopyOnWriteArrayList<RegisteredListener>()
    val tasks = CopyOnWriteArrayList<PluginTask>()

    /** Persisted settings merged over the manifest defaults. */
    @Volatile var settings: Map<String, Any?> = emptyMap()

    /** Non-null when the plugin failed to load or activate; surfaced by the management UI. */
    @Volatile var error: String? = null

    /** True once `activate` completed successfully. */
    @Volatile var active: Boolean = false

    /** JVM plugin instances created from the plugin jars, in load order. */
    val instances = CopyOnWriteArrayList<LegadoPlugin>()

    /** Rhino runtime, when the plugin ships `server.js`. */
    @Volatile var jsRuntime: JsPluginRuntime? = null

    /** Classloader holding the plugin jars; closed on unload so the files can be replaced. */
    @Volatile var classLoader: java.net.URLClassLoader? = null

    fun hasPermission(permission: String): Boolean = permission in manifest.permissions

    /** Cancels every scheduled task and forgets all registrations. */
    fun clearRegistrations() {
        tasks.forEach { runCatching { it.cancel() } }
        tasks.clear()
        routes.clear()
        listeners.clear()
    }

    /** Effective settings: manifest defaults overlaid with what the user stored. */
    fun effectiveSettings(): Map<String, Any?> {
        val defaults = manifest.settings.associate { it.key to it.default.toPlainValue() }
        return defaults + settings
    }
}

private fun JsonElement?.toPlainValue(): Any? = when (this) {
    null -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        else -> longOrNull ?: doubleOrNull ?: content
    }
    is JsonArray -> map { it.toPlainValue() }
    is JsonObject -> entries.associate { (key, value) -> key to value.toPlainValue() }
}
