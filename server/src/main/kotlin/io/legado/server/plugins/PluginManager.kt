package io.legado.server.plugins

import io.legado.plugin.api.LegadoPlugin
import io.legado.plugin.api.PluginEvent
import io.legado.plugin.api.PluginEvents
import io.legado.plugin.api.PluginJson
import io.legado.plugin.api.PluginTask
import io.legado.server.BookCacheService
import io.legado.server.CoverCache
import io.legado.server.Database
import io.legado.server.RuleRunner
import io.legado.server.ServerConfig
import io.legado.server.SubscriptionService
import kotlinx.serialization.Serializable
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/** Serializable summary of one plugin, consumed by the web management UI. */
@Serializable
data class PluginRouteView(val method: String, val path: String, val public: Boolean)

@Serializable
data class PluginView(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val apiVersion: Int,
    val enabled: Boolean,
    val loaded: Boolean,
    val runtime: String,
    val hasServer: Boolean,
    val hasWeb: Boolean,
    val permissions: List<String>,
    val settingsSchema: List<PluginSettingField>,
    val routes: List<PluginRouteView> = emptyList(),
    val listenerCount: Int = 0,
    val error: String? = null,
    val directory: String,
)

/** Result of resolving a plugin route against an incoming request path. */
class PluginRouteMatch(
    val route: RegisteredRoute,
    val pathParameters: Map<String, String>,
)

/**
 * Owns the plugin lifecycle: discovery, activation, registration and teardown.
 *
 * Loading is deliberately failure-isolating. A plugin that throws while activating is kept in the
 * registry with its error recorded — never silently dropped — so the management UI can explain what
 * went wrong while every other plugin keeps running.
 */
class PluginManager(
    config: ServerConfig,
    private val database: Database,
    private val runner: RuleRunner,
    private val coverCache: CoverCache,
    private val bookCache: BookCacheService,
    private val subscriptions: SubscriptionService,
    private val log: (String, Throwable?) -> Unit,
) : AutoCloseable, PluginScheduler, PluginEventPublisher {

    val pluginsDirectory: Path = config.pluginsDirectory

    private val plugins = ConcurrentHashMap<String, LoadedPlugin>()

    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "legado-plugin-scheduler").apply { isDaemon = true }
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val services = PluginServices(
        database = database,
        runner = runner,
        coverCache = coverCache,
        bookCache = bookCache,
        subscriptions = subscriptions,
        scheduler = this,
        events = this,
        serverVersion = SERVER_VERSION,
        httpClient = httpClient,
        log = log,
    )

    fun start() {
        reload()
        publish(PluginEvents.SERVER_START, mapOf("pluginDirectory" to pluginsDirectory.toString()), "server")
    }

    /** Rescans the plugin folder from scratch; used at boot and by the "reload" endpoint. */
    fun reload(): Int {
        plugins.values.forEach(::deactivate)
        plugins.clear()
        Files.createDirectories(pluginsDirectory)

        val stored = database.listPluginStates()
        val folders = try {
            Files.list(pluginsDirectory).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { Files.isRegularFile(it.resolve(PluginManifests.FILE_NAME)) }
                    .sorted()
                    .collect(Collectors.toList())
            }
        } catch (error: Exception) {
            log("插件目录读取失败: ${error.message}", error)
            emptyList()
        }

        folders.forEach { folder ->
            val loaded = runCatching { loadFolder(folder, stored) }
                .onFailure { log("插件加载失败 (${folder.fileName}): ${it.message}", it) }
                .getOrNull()
            if (loaded != null) {
                log("插件已加载: ${loaded.id} v${loaded.manifest.version} enabled=${loaded.enabled} active=${loaded.active}", null)
            }
        }
        return plugins.size
    }

    private fun loadFolder(folder: Path, stored: Map<String, io.legado.server.PluginState>): LoadedPlugin {
        val descriptor = PluginManifests.read(folder)
        val state = stored[descriptor.manifest.id]
        val plugin = LoadedPlugin(descriptor, state?.enabled ?: descriptor.manifest.enabled)
        state?.settings?.let { json ->
            plugin.settings = runCatching { PluginJson.parseObject(json) }.getOrDefault(emptyMap())
        }
        plugins[plugin.id] = plugin
        if (plugin.enabled) activate(plugin)
        return plugin
    }

    fun list(): List<PluginView> = plugins.values.sortedBy { it.id }.map(::view)

    fun view(id: String): PluginView? = plugins[id]?.let(::view)

    fun find(id: String): LoadedPlugin? = plugins[id]

    fun setEnabled(id: String, enabled: Boolean): PluginView? {
        val plugin = plugins[id] ?: return null
        if (plugin.enabled == enabled && plugin.active == enabled) return view(plugin)
        database.savePluginState(id, enabled = enabled)
        plugin.enabled = enabled
        if (enabled) activate(plugin) else deactivate(plugin)
        log("插件 ${if (enabled) "已启用" else "已禁用"}: $id", null)
        return view(plugin)
    }

    fun settingsOf(id: String): Map<String, Any?>? = plugins[id]?.effectiveSettings()

    fun updateSettings(id: String, values: Map<String, Any?>): Map<String, Any?>? {
        val plugin = plugins[id] ?: return null
        // Only keys declared in plugin.json are persisted; an unrecognised key is almost always a
        // typo, and accepting it would silently create settings nothing ever reads.
        val declared = plugin.manifest.settings.map { it.key }.toSet()
        val filtered = if (declared.isEmpty()) values else values.filterKeys { it in declared }
        val merged = plugin.settings + filtered
        database.savePluginState(id, settings = PluginJson.write(merged))
        plugin.settings = merged
        return plugin.effectiveSettings()
    }

    /** Resolves the handler for `method` + `path`, honouring `{name}` and trailing `*` segments. */
    fun matchRoute(id: String, method: String, path: String): PluginRouteMatch? {
        val plugin = plugins[id]?.takeIf { it.active } ?: return null
        val normalized = path.trimStart('/')
        return plugin.routes
            .filter { it.method == "*" || it.method.equals(method, ignoreCase = true) }
            .firstNotNullOfOrNull { route ->
                matchPath(route.path, normalized)?.let { PluginRouteMatch(route, it) }
            }
    }

    private fun matchPath(template: String, actual: String): Map<String, String>? {
        val templateSegments = template.trim('/').split('/')
        val actualSegments = actual.trim('/').split('/')
        val parameters = mutableMapOf<String, String>()

        // A trailing '*' captures the remaining segments, which is what file-tree style plugins
        // (WebDAV, file browsers) need without demanding a full pattern language.
        val wildcard = templateSegments.lastOrNull() == "*"
        val fixed = if (wildcard) templateSegments.dropLast(1) else templateSegments
        if (wildcard) {
            if (actualSegments.size < fixed.size) return null
        } else if (templateSegments.size != actualSegments.size) {
            return null
        }

        fixed.forEachIndexed { index, segment ->
            val value = actualSegments.getOrNull(index) ?: return null
            when {
                segment.startsWith("{") && segment.endsWith("}") -> parameters[segment.substring(1, segment.length - 1)] = value
                segment != value -> return null
            }
        }
        if (wildcard) parameters["*"] = actualSegments.drop(fixed.size).joinToString("/")
        return parameters
    }

    // --- scheduler & event bus -------------------------------------------------------------

    override fun schedule(pluginId: String, initialDelayMs: Long, periodMs: Long, task: Runnable): PluginTask {
        val future = scheduler.scheduleWithFixedDelay(
            {
                runCatching { task.run() }.onFailure { log("[$pluginId] 定时任务执行失败: ${it.message}", it) }
            },
            initialDelayMs,
            periodMs,
            TimeUnit.MILLISECONDS,
        )
        val handle = PluginTask { future.cancel(false) }
        plugins[pluginId]?.tasks?.add(handle)
        return handle
    }

    override fun publish(type: String, payload: Map<String, Any?>, origin: String) {
        val event = PluginEvent(type, payload, origin)
        plugins.values.filter { it.active }.forEach { plugin ->
            // A failing listener must never break the caller (or other plugins), so every delivery
            // is isolated and only reported.
            plugin.listeners
                .filter { it.eventType == type || it.eventType == "*" }
                .forEach { listener ->
                    runCatching { listener.handler.onEvent(event) }
                        .onFailure { log("[${plugin.id}] 事件监听器失败 (${event.type}): ${it.message}", it) }
                }
        }
    }

    /** Convenience for host code that just wants to announce something. */
    fun publishHost(type: String, payload: Map<String, Any?>) = publish(type, payload, "server")

    override fun close() {
        publish(PluginEvents.SERVER_STOP, emptyMap(), "server")
        plugins.values.forEach(::deactivate)
        plugins.clear()
        scheduler.shutdownNow()
    }

    // --- activation ------------------------------------------------------------------------

    private fun activate(plugin: LoadedPlugin) {
        val context = PluginHostContext(plugin, services)
        try {
            context.prepare()
            loadJars(plugin, context)
            plugin.manifest.server?.let { script ->
                // BOM 同样要剥掉：Rhino 会在脚本首字符上直接报语法错误，而错误信息不会提到编码。
                val source = Files.readString(plugin.directory.resolve(script)).removePrefix(PluginManifests.BOM)
                val runtime = JsPluginRuntime(plugin, context, source)
                runtime.start()
                plugin.jsRuntime = runtime
            }
            plugin.active = true
            plugin.error = null
            publish(PluginEvents.PLUGIN_LOADED, mapOf("id" to plugin.id, "version" to plugin.manifest.version), "server")
        } catch (error: Throwable) {
            plugin.error = error.message ?: error.javaClass.simpleName
            log("[${plugin.id}] 激活失败: ${plugin.error}", error)
            deactivate(plugin)
        }
    }

    private fun loadJars(plugin: LoadedPlugin, context: PluginHostContext) {
        val jars = plugin.manifest.jars
        if (jars.isEmpty()) return
        val loader = URLClassLoader(
            jars.map { plugin.directory.resolve(it).toUri().toURL() }.toTypedArray(),
            javaClass.classLoader,
        )
        plugin.classLoader = loader
        val explicit = plugin.manifest.mainClass
        val instances: List<LegadoPlugin> = if (explicit != null) {
            val type = Class.forName(explicit, true, loader)
            listOf(type.getDeclaredConstructor().newInstance() as? LegadoPlugin
                ?: throw IllegalStateException("$explicit 未实现 LegadoPlugin"))
        } else {
            ServiceLoader.load(LegadoPlugin::class.java, loader).toList()
                .ifEmpty { throw IllegalStateException("jar 中未找到 LegadoPlugin 实现（需要 META-INF/services 声明）") }
        }
        instances.forEach { instance ->
            instance.activate(context)
            plugin.instances.add(instance)
        }
    }

    private fun deactivate(plugin: LoadedPlugin) {
        plugin.clearRegistrations()
        plugin.instances.reversed().forEach { runCatching { it.deactivate() }.onFailure { log("[${plugin.id}] deactivate 失败: ${it.message}", it) } }
        plugin.instances.clear()
        runCatching { plugin.jsRuntime?.close() }
        plugin.jsRuntime = null
        runCatching { plugin.classLoader?.close() }
        plugin.classLoader = null
        plugin.active = false
    }

    private fun view(plugin: LoadedPlugin): PluginView = PluginView(
        id = plugin.id,
        name = plugin.manifest.name,
        version = plugin.manifest.version,
        description = plugin.manifest.description,
        author = plugin.manifest.author,
        apiVersion = plugin.manifest.apiVersion,
        enabled = plugin.enabled,
        loaded = plugin.active,
        runtime = plugin.manifest.runtimeLabel,
        hasServer = plugin.manifest.hasServerSide,
        hasWeb = plugin.manifest.web != null,
        permissions = plugin.manifest.permissions,
        settingsSchema = plugin.manifest.settings,
        routes = plugin.routes.map { PluginRouteView(it.method, it.path, it.isPublic) },
        listenerCount = plugin.listeners.size,
        error = plugin.error,
        directory = plugin.directory.toString(),
    )

    private companion object {
        const val SERVER_VERSION = "0.1.0"
    }
}
