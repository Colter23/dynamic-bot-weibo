package top.colter.dynamic.weibo

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeiboCookieJarTest {
    private val homepage = URI("https://weibo.com/")

    @Test
    fun `cookie deletion has no fallback and is scoped by domain and path`() {
        val jar = WeiboCookieJar("SUB=old; ALC=long; XSRF-TOKEN=token")
        jar.put(URI("https://passport.weibo.com/"), mapOf("Set-Cookie" to listOf("SUB=; Max-Age=0; Path=/")))
        assertTrue(jar.headerFor(homepage).contains("SUB=old"))
        jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=; Domain=.weibo.com; Max-Age=0; Path=/")))
        assertFalse(jar.headerFor(homepage).contains("SUB="))
        assertTrue(jar.headerFor(URI("https://login.sina.com.cn/sso/login.php")).contains("ALC=long"))
        jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=revived; Domain=.weibo.com; Path=/")))
        assertTrue(jar.headerFor(homepage).contains("SUB=revived"))
    }

    @Test
    fun `expiry is absolute and expired cookies do not revive on restart`() {
        var now = 1000L
        val jar = WeiboCookieJar(nowSeconds = { now })
        jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=value; Max-Age=60; Path=/")))
        val saved = jar.exportJson()
        now = 1061
        assertEquals("", jar.headerFor(homepage))
        assertEquals("[]", WeiboCookieJar(saved) { now }.exportJson())
        jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")))
        assertEquals("", jar.headerFor(homepage))
    }

    @Test
    fun `host only secure and path rules survive JSON roundtrip`() {
        val jar = WeiboCookieJar()
        jar.put(homepage, mapOf("Set-Cookie" to listOf("HOST=value; Secure; HttpOnly; Path=/private")))
        jar.put(homepage, mapOf("Set-Cookie" to listOf("DOMAIN=shared; Domain=.weibo.com; Path=/")))
        val restored = WeiboCookieJar(jar.exportJson())
        assertEquals("DOMAIN=shared", restored.headerFor(homepage))
        assertTrue(restored.headerFor(URI("https://weibo.com/private/item")).contains("HOST=value"))
        assertFalse(restored.headerFor(URI("https://weibo.com/private-other")).contains("HOST="))
        assertFalse(restored.headerFor(URI("http://weibo.com/private")).contains("HOST="))
        assertFalse(restored.headerFor(URI("https://passport.weibo.com/private")).contains("HOST="))
        assertTrue(restored.exportJson().contains("\"httpOnly\":true"))
    }

    @Test
    fun `cross domain duplicate names remain distinct and only matching values are sent`() {
        val jar = WeiboCookieJar("ALC=long; ALF=window; SUB=desktop")
        jar.put(URI("https://passport.weibo.cn/"), mapOf("Set-Cookie" to listOf("SUB=mobile; Domain=.weibo.cn; Path=/")))
        assertTrue(jar.headerFor(homepage).contains("SUB=desktop"))
        assertFalse(jar.headerFor(homepage).contains("mobile"))
        val restored = WeiboCookieJar("{\"cookies\":${jar.exportJson()}}")
        assertTrue(restored.canRefresh())
        assertTrue(restored.headerFor(URI("https://passport.weibo.cn/")).contains("SUB=mobile"))
        assertTrue(restored.headerFor(URI("https://login.sina.com.cn/")).contains("ALC=long"))
        assertFalse(restored.headerFor(homepage).contains("ALC="))
    }

    @Test
    fun `server cannot set unrelated domain or broad public suffix`() {
        val jar = WeiboCookieJar()
        jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=bad; Domain=.sina.com.cn", "SUB=bad; Domain=.com")))
        assertTrue(jar.isEmpty())
        assertTrue(jar.get(homepage, emptyMap()).isEmpty())
    }

    @Test
    fun `raw imports scope legacy SSO credentials and preserve actual JSON domains`() {
        val jar = WeiboCookieJar("TGC=legacy; SUP=user; SUR=fallback; ALF=window; SRF=passport; SUB=web")
        val sso = jar.headerFor(URI("https://login.sina.com.cn/sso/login.php"))
        assertTrue(sso.contains("TGC=legacy"))
        assertTrue(sso.contains("SUP=user"))
        assertTrue(sso.contains("ALF=window"))
        assertFalse(sso.contains("SUB=web"))
        assertTrue(jar.canRefresh())
        val explicit = WeiboCookieJar("""[{"name":"ALF","value":"only-web","domain":".weibo.com","path":"/"}]""")
        assertEquals("", explicit.headerFor(URI("https://login.sina.com.cn/")))
        assertFalse(explicit.canRefresh())
    }

    @Test
    fun `committed candidate invalidates older candidates and request cookie snapshots`() {
        val jar = WeiboCookieJar("SUB=old; XSRF-TOKEN=token")
        val first = jar.candidate()
        val older = jar.candidate()
        val oldRequest = jar.requestCookies(homepage)
        first.jar.put(homepage, mapOf("Set-Cookie" to listOf(
            "SUB=verified; Domain=.weibo.com; Path=/",
            "XSRF-TOKEN=; Domain=.weibo.com; Path=/; Max-Age=0",
        )))
        assertTrue(jar.commit(first))
        older.jar.put(homepage, mapOf("Set-Cookie" to listOf("SUB=stale; Domain=.weibo.com; Path=/")))
        assertFalse(jar.commit(older))
        jar.putIfCurrent(homepage, mapOf("Set-Cookie" to listOf("SUB=; Domain=.weibo.com; Path=/; Max-Age=0")), oldRequest.generation)
        assertEquals("SUB=verified", jar.headerFor(homepage))
    }
}
