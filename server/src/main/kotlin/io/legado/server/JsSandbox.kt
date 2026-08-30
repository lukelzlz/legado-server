package io.legado.server

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class JsSandbox(private val runner: RuleRunner? = null) {
    private val sessionStore = ConcurrentHashMap<String, Any>()

    fun eval(script: String, bindings: Map<String, Any?> = emptyMap()): String? {
        val cleanScript = script.trim().removePrefix("@js:").removePrefix("js:").trim()
        if (cleanScript.isBlank()) return null

        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.setClassShutter(ClassShutter { false }) // Sandbox: block all Java reflection
            val scope = context.initSafeStandardObjects()

            // Inject bindings (e.g. result, src, baseUrl, key, page)
            bindings.forEach { (key, value) ->
                ScriptableObject.putProperty(scope, key, toJsValue(value, scope))
            }

            // Inject standard Legado 'java' bridge object
            ScriptableObject.putProperty(scope, "java", createJavaBridge(scope))

            val result = context.evaluateString(scope, cleanScript, "rule.js", 1, null)
            if (result == null || result == Context.getUndefinedValue()) {
                // If script set a global 'result' variable (common pattern: result = ...), read it
                val globalResult = ScriptableObject.getProperty(scope, "result")
                if (globalResult != null && globalResult != Context.getUndefinedValue()) {
                    return jsValueToString(context, scope, globalResult)
                }
                return null
            }
            return jsValueToString(context, scope, result)
        } catch (e: Exception) {
            // Return null or throw controlled RuleExecutionException
            return null
        } finally {
            Context.exit()
        }
    }

    private fun jsValueToString(context: Context, scope: Scriptable, value: Any?): String? = when (value) {
        null, Context.getUndefinedValue() -> null
        is CharSequence -> value.toString()
        is Number, is Boolean -> value.toString()
        else -> runCatching {
            NativeJSON.stringify(context, scope, value, null, null).toString()
        }.getOrNull() ?: value.toString()
    }

    private fun createJavaBridge(scope: Scriptable): NativeObject = NativeObject().also { api ->
        api.parentScope = scope

        // java.ajax(url)
        ScriptableObject.putProperty(api, "ajax", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val url = args.firstOrNull()?.toString() ?: return ""
                return runner?.fetch(url) ?: ""
            }
        })

        // java.base64Decode(str)
        ScriptableObject.putProperty(api, "base64Decode", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return runCatching {
                    val clean = input.trim()
                    val bytes = try {
                        Base64.getDecoder().decode(clean)
                    } catch (_: Exception) {
                        Base64.getUrlDecoder().decode(clean)
                    }
                    String(bytes, Charsets.UTF_8)
                }.getOrDefault("")
            }
        })

        // java.base64Encode(str)
        ScriptableObject.putProperty(api, "base64Encode", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
            }
        })

        // java.md5Encode(str) / java.md5(str)
        val md5Fn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val input = args.firstOrNull()?.toString() ?: return ""
                return MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
        }
        ScriptableObject.putProperty(api, "md5Encode", md5Fn)
        ScriptableObject.putProperty(api, "md5", md5Fn)

        // java.getString(rule)
        ScriptableObject.putProperty(api, "getString", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val rule = args.firstOrNull()?.toString() ?: return ""
                val src = ScriptableObject.getProperty(scope, "src")?.toString() ?: ""
                return NodeValue.document(src).value(rule) ?: ""
            }
        })

        // java.log(msg)
        ScriptableObject.putProperty(api, "log", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                return Context.getUndefinedValue()
            }
        })

        // java.put(key, val) / java.get(key)
        ScriptableObject.putProperty(api, "put", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                val value = args.getOrNull(1) ?: return Context.getUndefinedValue()
                sessionStore[key] = value
                return value
            }
        })
        ScriptableObject.putProperty(api, "get", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any {
                val key = args.firstOrNull()?.toString() ?: return Context.getUndefinedValue()
                return sessionStore[key] ?: Context.getUndefinedValue()
            }
        })
    }

    private fun toJsValue(value: Any?, scope: Scriptable): Any? = when (value) {
        null -> null
        is Map<*, *> -> NativeObject().also { obj ->
            obj.parentScope = scope
            value.forEach { (k, v) -> if (k is String) ScriptableObject.putProperty(obj, k, toJsValue(v, scope)) }
        }
        is List<*> -> Context.getCurrentContext().newArray(scope, value.map { toJsValue(it, scope) }.toTypedArray())
        else -> Context.javaToJS(value, scope)
    }
}
