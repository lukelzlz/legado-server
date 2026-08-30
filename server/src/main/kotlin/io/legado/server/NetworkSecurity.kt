package io.legado.server

import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object NetworkSecurity {
    private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private const val MAX_DNS_CACHE_SIZE = 2000

    private data class CachedDns(val addresses: Array<InetAddress>, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, CachedDns>()

    fun clearCache() {
        dnsCache.clear()
    }

    fun cacheSize(): Int = dnsCache.size

    /**
     * Resolves host IP addresses with in-memory caching and validates that the target
     * is not a local/private network address (preventing SSRF).
     */
    fun resolveAndValidateSafeHttpTarget(uri: URI, serviceName: String = "目标") {
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("仅允许 HTTP(S) $serviceName 地址")
        }
        val host = uri.host.lowercase()
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        val addresses = if (cached != null && cached.expiresAt > now) {
            cached.addresses
        } else {
            val resolved = runCatching { InetAddress.getAllByName(host) }.getOrElse {
                throw IllegalArgumentException("无法解析 $serviceName 域名: $host (${it.message ?: "DNS解析失败"})")
            }
            if (dnsCache.size > MAX_DNS_CACHE_SIZE) {
                dnsCache.clear()
            }
            dnsCache[host] = CachedDns(resolved, now + DNS_CACHE_TTL_MS)
            resolved
        }
        for (address in addresses) {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.hostAddress == "169.254.169.254") {
                throw IllegalArgumentException("拒绝访问内网或本机地址")
            }
        }
    }
}
