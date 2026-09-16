package io.legado.server.plugins

import io.legado.plugin.api.PluginPermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plugin.json` of an installed plugin.
 *
 * Everything the host needs to load a plugin is declared here; the plugin's own code
 * (`server.js` / `web.js` / `*.jar`) stays a plain file next to the manifest. Keeping the manifest
 * declarative means the management UI can list, describe and configure a plugin without ever
 * executing its code.
 */
@Serializable
data class PluginManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "0.0.0",
    val description: String? = null,
    val author: String? = null,
    /** Host API generation the plugin was written against; must not exceed [PluginApiSupport.CURRENT]. */
    val apiVersion: Int = 1,
    /** Whether the plugin is started at boot. Users can flip this later from the UI. */
    val enabled: Boolean = true,
    /** Backend script executed in the Rhino sandbox, relative to the plugin folder. */
    val server: String? = null,
    /** Frontend ES module loaded by the web client through the host SDK. */
    val web: String? = null,
    /** JVM plugin jars, relative to the plugin folder. */
    val jars: List<String> = emptyList(),
    /** Explicit [io.legado.plugin.api.LegadoPlugin] implementation; auto-detected otherwise. */
    val mainClass: String? = null,
    val permissions: List<String> = emptyList(),
    /** Fields rendered by the UI and stored through [io.legado.plugin.api.PluginSettings]. */
    val settings: List<PluginSettingField> = emptyList(),
)

@Serializable
data class PluginSettingField(
    val key: String,
    val label: String = "",
    /** `text` | `password` | `number` | `boolean` | `select` | `textarea` */
    val type: String = "text",
    val default: JsonElement? = null,
    val options: List<PluginSettingOption> = emptyList(),
    val hint: String? = null,
)

@Serializable
data class PluginSettingOption(val label: String, val value: String)

/** The manifest plus the folder it was found in. */
data class PluginDescriptor(val manifest: PluginManifest, val directory: Path)

object PluginManifests {
    const val FILE_NAME = "plugin.json"

    /** Host API generation implemented by this server build. */
    const val CURRENT_API_VERSION = 1

    /** Byte-order mark some Windows editors prepend to UTF-8 files. */
    const val BOM = "\uFEFF"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Plugin ids become folder names and URL path segments, so they stay deliberately strict. */
    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    private val SETTING_TYPES = setOf("text", "password", "number", "boolean", "select", "textarea")

    /**
     * Reads and validates a plugin folder.
     *
     * Validation is strict on purpose: a plugin that would fail halfway through loading is far
     * harder to diagnose than one rejected up front with a concrete reason.
     */
    fun read(directory: Path): PluginDescriptor {
        val manifestFile = directory.resolve(FILE_NAME)
        require(Files.isRegularFile(manifestFile)) { "缺少 $FILE_NAME" }
        // Windows 上的编辑器（旧版记事本、部分 PowerShell 写法）默认给 UTF-8 文件加 BOM。
        // 带 BOM 的 JSON 无法解析，而且报错完全看不出是编码问题，所以在入口统一剥掉 ——
        // 与 SourceCodec.parse 对书源 JSON 的处理保持一致。
        val text = Files.readString(manifestFile).removePrefix(BOM)
        val manifest = try {
            json.decodeFromString(PluginManifest.serializer(), text)
        } catch (error: Exception) {
            throw IllegalArgumentException("$FILE_NAME 解析失败: ${error.message}")
        }
        validate(manifest, directory)
        return PluginDescriptor(manifest, directory)
    }

    private fun validate(manifest: PluginManifest, directory: Path) {
        require(ID_PATTERN.matches(manifest.id)) {
            "插件 id '${manifest.id}' 非法：只允许小写字母、数字以及 . _ -，且以字母或数字开头"
        }
        require(manifest.name.isNotBlank()) { "插件 name 不能为空" }
        require(manifest.apiVersion <= CURRENT_API_VERSION) {
            "插件要求 API 版本 ${manifest.apiVersion}，当前服务端仅支持 $CURRENT_API_VERSION"
        }
        manifest.server?.let { require(Files.isRegularFile(directory.resolve(it))) { "server 脚本不存在: $it" } }
        manifest.web?.let { require(Files.isRegularFile(directory.resolve(it))) { "web 模块不存在: $it" } }
        manifest.jars.forEach { require(Files.isRegularFile(directory.resolve(it))) { "插件 jar 不存在: $it" } }

        val unknown = manifest.permissions.filterNot { it in PluginPermissions.ALL }
        require(unknown.isEmpty()) { "未知权限: ${unknown.joinToString(", ")}" }

        val duplicateSettings = manifest.settings.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicateSettings.isEmpty()) { "设置项 key 重复: ${duplicateSettings.joinToString(", ")}" }
        manifest.settings.forEach { field ->
            require(SETTING_TYPES.contains(field.type)) {
                "设置项 '${field.key}' 的 type '${field.type}' 非法，可选值: ${SETTING_TYPES.joinToString(", ")}"
            }
            if (field.type == "select") {
                require(field.options.isNotEmpty()) { "设置项 '${field.key}' 为 select，必须提供 options" }
            }
        }

        require(manifest.server != null || manifest.web != null || manifest.jars.isNotEmpty()) {
            "插件至少要提供 server / web / jars 中的一项"
        }
    }
}

/** Convenience view over the manifest used by the HTTP layer and the management UI. */
val PluginManifest.runtimeLabel: String
    get() {
        val js = server != null
        val jar = jars.isNotEmpty()
        return when {
            js && jar -> "js+jar"
            jar -> "jar"
            js -> "js"
            else -> "none"
        }
    }

/** True when the plugin contributes any server-side behaviour (so it can register routes/hooks). */
val PluginManifest.hasServerSide: Boolean
    get() = server != null || jars.isNotEmpty()
