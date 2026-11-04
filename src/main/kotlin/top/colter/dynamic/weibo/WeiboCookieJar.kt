package top.colter.dynamic.weibo

import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal data class WeiboCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val hostOnly: Boolean = false,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expires: Long? = null,
)

/** Cookie values only enter the jar once; deleted or expired values have no configuration fallback. */
internal class WeiboCookieJar(
    source: String = "",
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : CookieHandler() {
    private val cookies = linkedMapOf<Triple<String, String, String>, WeiboCookie>()
    private var generation = 0L
    private var changedKeys: MutableSet<Triple<String, String, String>>? = null

    data class RequestCookies(val header: String, val generation: Long)
    data class Candidate(
        val jar: WeiboCookieJar,
        val generation: Long,
        val originalCookies: Map<Triple<String, String, String>, WeiboCookie>,
    )

    init {
        import(source)
    }

    // Requests explicitly use headerFor, including each redirect hop.
    override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> = emptyMap()

    @Synchronized
    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
        val host = uri.host?.lowercase() ?: return
        responseHeaders.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { raw ->
            runCatching { HttpCookie.parse(raw) }.getOrDefault(emptyList()).forEach { parsed ->
                val domain = parsed.domain?.lowercase()?.removePrefix(".") ?: host
                if (!trustedDomain(domain) || (host != domain && !host.endsWith(".$domain"))) return@forEach
                val path = parsed.path?.takeIf { it.startsWith('/') } ?: defaultPath(uri.path)
                val expires = when {
                    parsed.maxAge < 0 -> null
                    parsed.maxAge > Long.MAX_VALUE - nowSeconds() -> Long.MAX_VALUE
                    else -> nowSeconds() + parsed.maxAge
                }
                store(WeiboCookie(
                    name = parsed.name, value = parsed.value, domain = domain, path = path,
                    hostOnly = parsed.domain == null, secure = parsed.secure, httpOnly = parsed.isHttpOnly,
                    expires = expires,
                ))
            }
        }
    }

    @Synchronized
    fun requestCookies(uri: URI): RequestCookies = RequestCookies(headerFor(uri), generation)

    @Synchronized
    fun putIfCurrent(uri: URI, responseHeaders: Map<String, List<String>>, requestGeneration: Long) {
        if (requestGeneration == generation) put(uri, responseHeaders)
    }

    @Synchronized
    fun headerFor(uri: URI): String {
        purgeExpired()
        val host = uri.host?.lowercase() ?: return ""
        val path = uri.path.ifEmpty { "/" }
        return cookies.values.filter { cookie ->
            (host == cookie.domain || (!cookie.hostOnly && host.endsWith(".${cookie.domain}"))) &&
                (!cookie.secure || uri.scheme.equals("https", true)) &&
                (path == cookie.path || (path.startsWith(cookie.path) &&
                    (cookie.path.endsWith('/') || path.getOrNull(cookie.path.length) == '/')))
        }.sortedWith(compareByDescending<WeiboCookie> { it.path.length }.thenBy { it.name })
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun exportJson(): String {
        purgeExpired()
        return JsonArray(cookies.values.sortedWith(
            compareBy<WeiboCookie> { it.domain }.thenBy { it.path }.thenBy { it.name },
        ).map { cookie ->
            buildJsonObject {
                put("name", cookie.name)
                put("value", cookie.value)
                put("domain", if (cookie.hostOnly) cookie.domain else ".${cookie.domain}")
                put("path", cookie.path)
                put("hostOnly", cookie.hostOnly)
                put("secure", cookie.secure)
                put("httpOnly", cookie.httpOnly)
                cookie.expires?.let { put("expires", it) }
            }
        }).toString()
    }

    @Synchronized
    fun candidate(): Candidate = Candidate(
        WeiboCookieJar(exportJson(), nowSeconds).also { it.changedKeys = linkedSetOf() },
        generation,
        cookies.toMap(),
    )

    fun commit(candidate: Candidate, renewed: Boolean = false): Boolean {
        val changes = synchronized(candidate.jar) {
            candidate.jar.purgeExpired()
            candidate.jar.changedKeys.orEmpty().associateWith { candidate.jar.cookies[it] }
        }
        return synchronized(this) {
            if (generation != candidate.generation) return@synchronized false
            // Only verified response changes are authoritative; untouched snapshot values may be stale.
            changes.forEach { (key, cookie) ->
                if (renewed || cookies[key] == candidate.originalCookies[key]) {
                    if (cookie == null) cookies.remove(key) else store(cookie)
                }
            }
            generation += 1
            true
        }
    }

    @Synchronized
    fun isEmpty(): Boolean {
        purgeExpired()
        return cookies.isEmpty()
    }

    @Synchronized
    fun canRefresh(): Boolean {
        purgeExpired()
        val sso = cookies.values.filter { it.domain == "sina.com.cn" || it.domain == "login.sina.com.cn" }
        return sso.any { it.name == "ALC" && it.value.isNotBlank() } ||
            sso.any { it.name in setOf("tgc", "TGC", "TGT") && it.value.isNotBlank() }
    }

    private fun import(source: String) {
        val text = source.trim()
        if (text.isEmpty()) return
        if (text.startsWith('[') || text.startsWith('{')) {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
                throw IllegalArgumentException("微博 Cookie JSON 格式无效")
            }
            val rows = (root as? JsonArray) ?: ((root as? JsonObject)?.get("cookies") as? JsonArray)
                ?: throw IllegalArgumentException("微博 Cookie JSON 必须是数组或包含 cookies 数组")
            rows.forEach { row ->
                val item = row as? JsonObject ?: throw IllegalArgumentException("微博 Cookie 条目格式无效")
                fun text(key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull
                fun flag(key: String): Boolean? = (item[key] as? JsonPrimitive)?.booleanOrNull
                val domainText = text("domain") ?: throw IllegalArgumentException("微博 Cookie JSON 缺少域名")
                val expiry = ((item["expires"] ?: item["expirationDate"]) as? JsonPrimitive)?.doubleOrNull
                store(WeiboCookie(
                    name = text("name").orEmpty(), value = text("value").orEmpty(),
                    domain = domainText.lowercase().removePrefix("."),
                    path = text("path")?.takeIf { it.startsWith('/') } ?: "/",
                    hostOnly = flag("hostOnly") ?: !domainText.startsWith('.'),
                    secure = flag("secure") ?: true, httpOnly = flag("httpOnly") ?: false,
                    expires = expiry?.takeIf { it.isFinite() && it > 0 }?.toLong(),
                ))
            }
        } else {
            text.split(';').forEach { pair ->
                if ('=' !in pair) return@forEach
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                val domains = when (name) {
                    "ALC", "tgc", "TGC", "TGT" -> listOf("login.sina.com.cn")
                    "SUP", "SUR" -> listOf("sina.com.cn")
                    "ALF" -> listOf("weibo.com", "sina.com.cn")
                    "SRT", "SRF", "SVB" -> listOf("passport.weibo.com")
                    else -> listOf("weibo.com")
                }
                domains.forEach { domain ->
                    store(WeiboCookie(name, value, domain, httpOnly = name in setOf("ALC", "SUB", "SRT", "tgc", "TGC", "TGT")))
                }
            }
        }
    }

    private fun store(cookie: WeiboCookie) {
        require(cookie.name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) &&
            cookie.value.none { it == '\r' || it == '\n' || it == ';' }) { "微博 Cookie 名称或值无效" }
        require(trustedDomain(cookie.domain)) { "微博 Cookie 域名不受支持" }
        val key = Triple(cookie.name, cookie.domain, cookie.path)
        changedKeys?.add(key)
        if (cookie.expires?.let { it <= nowSeconds() } == true) cookies.remove(key) else cookies[key] = cookie
    }

    private fun purgeExpired() {
        cookies.entries.removeIf { it.value.expires?.let { expires -> expires <= nowSeconds() } == true }
    }

    private fun defaultPath(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    private fun trustedDomain(domain: String): Boolean =
        domain in setOf("weibo.com", "weibo.cn", "sina.com.cn") ||
            listOf("weibo.com", "weibo.cn", "sina.com.cn").any { domain.endsWith(".$it") }
}
